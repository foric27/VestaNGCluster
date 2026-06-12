package ru.foric27.cluster.video
import ru.foric27.cluster.config.*
import ru.foric27.cluster.service.*
import ru.foric27.cluster.ui.*
import ru.foric27.cluster.util.*

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun MediaFormat.trySetInteger(key: String, value: Int) {
    try {
        setInteger(key, value)
    } catch (_: Throwable) {
        // Опциональная настройка кодека, можно безопасно пропустить.
    }
}

/**
 * Причина ошибки запуска кодека.
 *
 * [CONFIGURE] — ошибка при configure, [START] — ошибка при start.
 */
internal enum class VideoEncoderStartupFailureReason {
    CONFIGURE,
    START,
}

/**
 * Исключение при запуске кодека.
 *
 * @property reason фаза ошибки (configure/start)
 */
internal class VideoEncoderStartupException(
    val reason: VideoEncoderStartupFailureReason,
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * Поддерживаемая пара profile/level кодека.
 *
 * @property profile AVC profile кодека
 * @property level AVC level кодека
 */
internal data class CodecProfileLevelSupport(
    val profile: Int,
    val level: Int,
)

/**
 * Снимок возможностей аппаратного кодека.
 *
 * @property codecName название кодека
 * @property supportsCbr поддержка CBR-режима
 * @property supportedProfileLevels список поддерживаемых пар profile/level
 */
internal data class EncoderCapabilitySnapshot(
    val codecName: String,
    val supportsCbr: Boolean,
    val supportedProfileLevels: List<CodecProfileLevelSupport>,
)

/**
 * Кандидат конфигурации кодека для перебора.
 *
 * @property label метка для логирования
 * @property profile AVC profile или null для fallback
 * @property level AVC level или null для fallback
 * @property bitrateMode режим битрейта или null если не поддерживается
 */
internal data class CodecConfigCandidate(
    val label: String,
    val profile: Int?,
    val level: Int?,
    val bitrateMode: Int?,
)

/**
 * Строит упорядоченный список кандидатов конфигурации кодека.
 *
 * Порядок: точный profile+level → запрошенный profile с fallback level →
 * fallback profiles (Baseline) → generic fallback.
 *
 * @param capabilities снимок возможностей кодека
 * @param requestedProfile запрошенный AVC profile
 * @param requestedLevel запрошенный AVC level
 * @return упорядоченный список кандидатов для перебора
 */
internal fun buildCodecConfigCandidates(
    capabilities: EncoderCapabilitySnapshot,
    requestedProfile: Int,
    requestedLevel: Int,
): List<CodecConfigCandidate> {
    val bitrateMode = if (capabilities.supportsCbr) {
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    } else {
        null
    }
    val candidates = linkedSetOf<CodecConfigCandidate>()
    val sameProfileLevels = capabilities.supportedProfileLevels
        .filter { it.profile == requestedProfile }
        .map { it.level }
        .sortedDescending()
    val exactOrHigherLevelSupported = sameProfileLevels.any { it >= requestedLevel }
    if (exactOrHigherLevelSupported) {
        candidates += CodecConfigCandidate(
            label = "requested_profile_level",
            profile = requestedProfile,
            level = requestedLevel,
            bitrateMode = bitrateMode,
        )
    } else if (sameProfileLevels.isNotEmpty()) {
        candidates += CodecConfigCandidate(
            label = "requested_profile_supported_level",
            profile = requestedProfile,
            level = sameProfileLevels.first(),
            bitrateMode = bitrateMode,
        )
    }

    val fallbackProfiles = listOf(
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
    )
    fallbackProfiles.forEach { profile ->
        val fallbackLevel = capabilities.supportedProfileLevels
            .filter { it.profile == profile }
            .maxOfOrNull { it.level }
            ?: return@forEach
        candidates += CodecConfigCandidate(
            label = "fallback_profile_${profile}_level_${fallbackLevel}",
            profile = profile,
            level = fallbackLevel,
            bitrateMode = bitrateMode,
        )
    }

    candidates += CodecConfigCandidate(
        label = "generic_surface_avc",
        profile = null,
        level = null,
        bitrateMode = bitrateMode,
    )
    return candidates.toList()
}

/**
 * Координатор видеопайплайна VirtualDisplay → OpenGL → MediaCodec → UDP.
 *
 * Управляет полным жизненным циклом video encoding:
 * 1. Создание [VirtualDisplay] через [PersistentVirtualDisplay]
 * 2. Запуск target activity на secondary display через [VideoDisplayLauncher]
 * 3. SurfaceTexture → [GlFrameComposer] → MediaCodec input surface
 * 4. MediaCodec H.264 encoding → [VideoCodecOutputProcessor] (Annex B)
 * 5. UDP отправка через [UdpSender]
 *
 * Класс держит наружный фасад [start]/[stop]/[relaunch]/[forceOutputFrame], а детали
 * display-launch, тайминга кадров и обработки выходных буферов делегирует
 * специализированным helper-компонентам в этом же пакете.
 *
 * **State machine:**
 * ```
 * IDLE → START_REQUESTED → PREPARING → START_COMPLETED → RUNNING → STOPPED → IDLE
 *                                     ↘ ERROR → IDLE
 * ```
 *
 * **Thread safety:**
 * - [encoder], [virtualDisplay], [hasPendingSurfaceFrame], [hasRenderedAnyFrame] — `@Volatile`
 * - [codecHandler] работает в отдельном [HandlerThread] для callback'ов MediaCodec
 * - [lifecycleState] защищён [lifecycleLock]
 *
 * @param context контекст приложения
 * @param streamConfig конфигурация потока (размер, fps, bitrate, launch component)
 * @param preferredLaunchComponent компонент для запуска на secondary display
 * @param udpSender sender для отправки encoded video по UDP
 * @param restartCallback callback для запроса перезапуска при критических ошибках
 */
internal class VideoEncoder(
    private val context: Context,
    private val streamConfig: StreamConfig,
    private val preferredLaunchComponent: String?,
    private val udpSender: UdpSender,
    private val restartCallback: RestartCallback,
) {

    /**
     * Callback для запроса перезапуска видеопайплайна при критических ошибках.
     */
    interface RestartCallback {
        fun requestRestart()
    }

    private val width: Int = streamConfig.width
    private val height: Int = streamConfig.height
    private val dpi: Int = streamConfig.dpi
    private val frameIntervalNs: Long = (1_000_000_000L / streamConfig.fps.coerceAtLeast(1)).coerceAtLeast(1L)
    private val displayLauncher = VideoDisplayLauncher(preferredLaunchComponent)
    private val outputProcessor = VideoCodecOutputProcessor(udpSender, ::logFpsStats, streamConfig)
    private val frameTimingController = VideoFrameTimingController(streamConfig.fps)

    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    private var encoderInputSurface: Surface? = null
    private var vdInputSurface: Surface? = null
    private var vdSurfaceTexture: SurfaceTexture? = null
    private var glComposer: GlFrameComposer? = null
    private var codecThread: HandlerThread? = null
    @Volatile private var codecHandler: Handler? = null

    @Volatile private var running: Boolean = false
    @Volatile private var stopping: Boolean = false
    private var lifecycleState: VideoCaptureLifecycleState = VideoCaptureLifecycleState.IDLE
    @Volatile private var configAnnexB: ByteArray? = null

    private var nextFrameTimeNs: Long = 0L
    private var fpsWindowStartedAtMs: Long = 0L
    private var fpsWindowFrames: Int = 0
    @Volatile private var hasPendingSurfaceFrame: Boolean = false
    @Volatile private var hasRenderedAnyFrame: Boolean = false
    private var lastRestartErrorLogMs: Long = 0L
    private var suppressedRestartErrorCount: Int = 0

    /**
     * Поднимает codec thread, настраивает MediaCodec и присоединяет к нему
     * persistent VirtualDisplay через GL-компоновщик.
     *
     * Порядок инициализации:
     * 1. HandlerThread с приоритетом [Process.THREAD_PRIORITY_URGENT_DISPLAY]
     * 2. Создание и конфигурация MediaCodec (H.264 encoder)
     * 3. Создание VirtualDisplay с SurfaceTexture
     * 4. Запуск [GlFrameComposer] для копирования кадров
     * 5. Запуск target activity на secondary display
     * 6. Старт MediaCodec (async mode с callback на codecHandler)
     *
     * При ошибке на любом этапе — [VideoEncoderStartupException] с причиной
     * [VideoEncoderStartupFailureReason.CONFIGURE] или [VideoEncoderStartupFailureReason.START].
     *
     * Thread-safe: проверяет [running] флаг и [lifecycleState] перед запуском.
     */
    fun start() {
        if (running) return
        if (!transitionLifecycle(VideoCaptureLifecycleEvent.START_REQUESTED)) {
            Timber.tag(TAG).w("Пропускаю start(): недопустимый lifecycle state=$lifecycleState")
            return
        }
        stopping = false
        running = true
        configAnnexB = null
        outputProcessor.clearStoredConfig()
        resetFrameTrackingState()

        try {
            val handlerThread = HandlerThread("ClusterCodec", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            handlerThread.start()
            Timber.tag(TAG).i("Приоритет потока кодека повышен: ClusterCodec -> ${Process.THREAD_PRIORITY_URGENT_DISPLAY}")
            codecThread = handlerThread
            codecHandler = Handler(handlerThread.looper)

            val configuredEncoder = createConfiguredEncoder()
            val mediaCodec = configuredEncoder.codec.also { encoder = it }
            encoderInputSurface = configuredEncoder.inputSurface

            glComposer = GlFrameComposer(
                outputSurface = encoderInputSurface ?: throw IllegalStateException("encoderInputSurface == null"),
                width = width,
                height = height,
            )

            val surfaceTexture = SurfaceTexture(glComposer!!.inputTextureId)
                .apply { setDefaultBufferSize(width, height) }
                .also { vdSurfaceTexture = it }
            vdInputSurface = Surface(surfaceTexture)
            surfaceTexture.setOnFrameAvailableListener(
                { hasPendingSurfaceFrame = true },
                codecHandler,
            )

            try {
                mediaCodec.start()
            } catch (t: Throwable) {
                throw VideoEncoderStartupException(
                    reason = VideoEncoderStartupFailureReason.START,
                    message = "Не удалось запустить MediaCodec после configure: ${configuredEncoder.candidate.label}",
                    cause = t,
                )
            }
            applyConfiguredBitrate()

            Timber.tag(TAG).i(
                "Профиль захвата: ${RuntimeConfig.Video.SIZE_SHORT}@${dpi}, constantFps=${streamConfig.fps}, bitrate=${streamConfig.bitrate}bps, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}, profile=${configuredEncoder.candidate.profile ?: RuntimeConfig.Video.ENCODER_PROFILE}, level=${configuredEncoder.candidate.level ?: RuntimeConfig.Video.ENCODER_LEVEL}, codecCandidate=${configuredEncoder.candidate.label}",
            )

            acquireVirtualDisplayOrThrow()
            transitionLifecycle(VideoCaptureLifecycleEvent.START_COMPLETED)
            nextFrameTimeNs = System.nanoTime()
            scheduleNextFrame()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Не удалось запустить видеокодер")
            safeStopInternal(releasePersistentDisplay = false)
            running = false
            stopping = false
            transitionLifecycle(VideoCaptureLifecycleEvent.START_FAILED)
            throw t
        }
    }

    /**
     * Останавливает кодек и освобождает связанные поверхности, не уничтожая
     * persistent VirtualDisplay без явной необходимости.
     *
     * Порядок остановки (критичен для избежания deadlock):
     * 1. Снятие listener'ов с SurfaceTexture
     * 2. Остановка MediaCodec (flush + stop)
     * 3. Освобождение GL composer
     * 4. Освобождение Surface/SurfaceTexture
     * 5. Join codec thread
     * 6. Сброс флагов [running], [stopping]
     */
    fun stop() {
        transitionLifecycle(VideoCaptureLifecycleEvent.STOP_REQUESTED)
        stopping = true
        running = false
        // Очищаем очередь handler'а от pending frame runnable'ов
        codecHandler?.removeCallbacksAndMessages(null)
        safeStopInternal(releasePersistentDisplay = false)
        stopping = false
        transitionLifecycle(VideoCaptureLifecycleEvent.STOP_COMPLETED)
    }

    private fun transitionLifecycle(event: VideoCaptureLifecycleEvent): Boolean {
        val next = VideoCaptureLifecycleStateMachine.transition(lifecycleState, event) ?: return false
        lifecycleState = next
        return true
    }

    /**
     * Повторно активирует целевую activity на текущем display после recovery,
     * если сам VirtualDisplay всё ещё валиден.
     *
     * Используется при wake recovery и watchdog restart для восстановления
     * отображения навигатора/медиа на secondary display без пересоздания
     * VirtualDisplay.
     *
     * @param reason причина relaunch (для логирования)
     */
    fun relaunchTargetActivityIfNeeded(reason: String) {
        val displayId = virtualDisplay?.display?.displayId ?: VdspState.getDisplayId()
        if (displayId < 0) {
            Timber.tag(TAG).w("Пропускаю повторный запуск навигатора: displayId недоступен, reason=$reason")
            return
        }
        runOnCodecThread {
            try {
                displayLauncher.launchOnDisplay(displayId, force = true)
                Timber.tag(TAG).i("Повторно активирую навигатор на display=$displayId, reason=$reason")
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Не удалось повторно активировать навигатор на display=$displayId, reason=$reason")
            }
        }
    }

    /**
     * Принудительно просит кодек отдать кадр после wake/recovery-сценариев.
     *
     * При восстановлении после sleep/wake pipeline может застрять в состоянии
     * без pending frames. Этот метод форсирует отправку одного кадра через
     * [scheduleNextFrame] для "пробуждения" кодека.
     *
     * @param reason причина force frame (для логирования)
     */
    fun forceOutputFrame(reason: String) {
        runOnCodecThread {
            if (!running || stopping) return@runOnCodecThread
            try {
                drawKeepaliveFrame()
                requestSyncFrame()
                Timber.tag(TAG).i("Принудительно отправляю кадр после выхода из сна, reason=$reason")
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Не удалось принудительно отправить кадр после выхода из сна, reason=$reason")
            }
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                onEncoderOutput(codec, index, info)
            } catch (t: Throwable) {
                logRestartError(t, "Ошибка обработки output buffer -> рестарт")
                VideoCodecUtil.releaseOutputBufferQuietly(codec, index)
                safeRequestRestart()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            logRestartError(e, "Ошибка MediaCodec -> рестарт")
            safeRequestRestart()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val csd0 = format.getByteBuffer("csd-0")
            val csd1 = format.getByteBuffer("csd-1")
            val built = H264AnnexBUtil.buildConfigAnnexB(csd0, csd1)
            configAnnexB = built
            Timber.tag(TAG).i("onOutputFormatChanged: csd0=%s, csd1=%s, configAnnexB=%s",
                csd0?.remaining()?.toString() ?: "null",
                csd1?.remaining()?.toString() ?: "null",
                built?.size?.toString() ?: "null")
        }
    }

    /**
     * Создаёт и конфигурирует MediaCodec H.264 encoder с подбором рабочей конфигурации.
     *
     * Пробует кандидатов из [buildCodecConfigCandidates] по порядку и возвращает первый
     * успешно сконфигурированный encoder с его input surface.
     *
     * @return сконфигурированный encoder, input surface и выбранный кандидат
     * @throws VideoEncoderStartupException если ни один кандидат не подошёл
     */
    private fun createConfiguredEncoder(): ConfiguredEncoder {
        val capabilities = inspectEncoderCapabilities()
        Timber.tag(TAG).i(
            "MediaCodec capabilities: codec=${capabilities.codecName}, supportsCbr=${capabilities.supportsCbr}, profiles=${capabilities.supportedProfileLevels.joinToString { "${it.profile}/${it.level}" }}",
        )
        val candidates = buildCodecConfigCandidates(
            capabilities = capabilities,
            requestedProfile = RuntimeConfig.Video.ENCODER_PROFILE,
            requestedLevel = RuntimeConfig.Video.ENCODER_LEVEL,
        )
        var lastError: Throwable? = null
        candidates.forEach { candidate ->
            var mediaCodec: MediaCodec? = null
            var inputSurface: Surface? = null
            try {
                val format = buildCodecFormat(candidate)
                Timber.tag(TAG).i("Пробую configure MediaCodec: ${describeCodecCandidate(candidate)}")
                mediaCodec = MediaCodec.createEncoderByType(MIME_AVC)
                mediaCodec.setCallback(codecCallback, codecHandler)
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = mediaCodec.createInputSurface()
                Timber.tag(TAG).i("MediaCodec configure успешен: ${describeCodecCandidate(candidate)}")
                return ConfiguredEncoder(mediaCodec, inputSurface, candidate)
            } catch (t: Throwable) {
                lastError = t
                Timber.tag(TAG).w(t, "MediaCodec configure не удался: ${describeCodecCandidate(candidate)}")
                runCatching { inputSurface?.release() }
                runCatching { mediaCodec?.release() }
            }
        }
        throw VideoEncoderStartupException(
            reason = VideoEncoderStartupFailureReason.CONFIGURE,
            message = "Не удалось подобрать рабочую конфигурацию MediaCodec для H.264 encoder",
            cause = lastError ?: IllegalStateException("configure failed without cause"),
        )
    }

    /**
     * Сканирует capability H.264 encoder'а на устройстве.
     *
     * Возвращает имя кодека, флаг поддержки CBR и список доступных profile/level.
     * Используется для выбора оптимальной конфигурации перед созданием encoder'а.
     *
     * @return snapshot capability выбранного encoder'а
     * @throws VideoEncoderStartupException при ошибке чтения capability
     */
    private fun inspectEncoderCapabilities(): EncoderCapabilitySnapshot {
        val mediaCodec = MediaCodec.createEncoderByType(MIME_AVC)
        try {
            val codecInfo = mediaCodec.codecInfo
            val capabilities = codecInfo.getCapabilitiesForType(MIME_AVC)
            val profileLevels = capabilities.profileLevels
                ?.map { CodecProfileLevelSupport(profile = it.profile, level = it.level) }
                .orEmpty()
            val supportsCbr = runCatching {
                capabilities.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
            }.getOrDefault(false)
            return EncoderCapabilitySnapshot(
                codecName = codecInfo.name,
                supportsCbr = supportsCbr,
                supportedProfileLevels = profileLevels,
            )
        } catch (t: Throwable) {
            throw VideoEncoderStartupException(
                reason = VideoEncoderStartupFailureReason.START,
                message = "Не удалось прочитать capability encoder'а H.264",
                cause = t,
            )
        } finally {
            runCatching { mediaCodec.release() }
        }
    }

    private fun buildCodecFormat(candidate: CodecConfigCandidate): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, streamConfig.bitrate)
            candidate.bitrateMode?.let { trySetInteger(MediaFormat.KEY_BITRATE_MODE, it) }
            setInteger(MediaFormat.KEY_FRAME_RATE, streamConfig.fps)
            try {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, streamConfig.iframeIntervalSec)
            } catch (_: Throwable) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, streamConfig.iframeIntervalSec.toFloat())
            }
            candidate.profile?.let { trySetInteger(MediaFormat.KEY_PROFILE, it) }
            candidate.level?.let { trySetInteger(MediaFormat.KEY_LEVEL, it) }
            trySetInteger(MediaFormat.KEY_PRIORITY, 0)
        }
    }

    private fun describeCodecCandidate(candidate: CodecConfigCandidate): String {
        return "candidate=${candidate.label}, profile=${candidate.profile ?: "default"}, level=${candidate.level ?: "default"}, bitrateMode=${candidate.bitrateMode ?: "default"}, size=${width}x${height}, fps=${streamConfig.fps}, bitrate=${streamConfig.bitrate}"
    }

    /**
     * Безопасная остановка внутренних ресурсов кодера с опциональным освобождением VirtualDisplay.
     *
     * Порядок остановки критичен для избежания deadlock:
     * 1. Отсоединение/освобождение VirtualDisplay
     * 2. Остановка MediaCodec
     * 3. Освобождение GL composer
     * 4. Завершение codec thread
     * 5. Освобождение Surface/SurfaceTexture
     * 6. Освобождение MediaCodec
     *
     * @param releasePersistentDisplay если true — полностью освобождает VirtualDisplay,
     *                                 иначе только отсоединяет Surface
     */
    private fun safeStopInternal(releasePersistentDisplay: Boolean) {
        if (releasePersistentDisplay) {
            PersistentVirtualDisplay.releaseAll()
        } else {
            PersistentVirtualDisplay.detachSurface()
        }
        virtualDisplay = null

        stopCodec()
        releaseGlComposer()
        terminateCodecThread()
        releaseSurfaceResources()
        releaseMediaCodec()

        outputProcessor.clearStoredConfig()
        resetFrameTrackingState()
        notifyDisplayRemoved()
    }

    private fun stopCodec() {
        releaseSafely("Не удалось остановить MediaCodec") {
            encoder?.stop()
        }
    }

    private fun releaseGlComposer() {
        val composer = glComposer
        val handler = codecHandler
        if (composer == null) {
            glComposer = null
            return
        }
        if (handler == null || handler.looper.thread == Thread.currentThread()) {
            releaseSafely("") { composer.release() }
            glComposer = null
            return
        }
        // Ждём выполнения release на codec thread — без этого looper может выйти
        // до завершения EGL cleanup, что приводит к disconnect failed.
        val latch = CountDownLatch(1)
        handler.post {
            releaseSafely("") { composer.release() }
            latch.countDown()
        }
        releaseSafely("") {
            latch.await(CODEC_THREAD_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        glComposer = null
    }

    private fun terminateCodecThread() {
        codecThread?.let { thread ->
            if (thread == Thread.currentThread()) {
                thread.quitSafely()
                codecThread = null
                codecHandler = null
                return
            }
            releaseSafely("Не удалось корректно завершить поток кодека") {
                thread.quitSafely()
                // Не ждём если текущий поток — это codec thread (защита от deadlock)
                if (thread != Thread.currentThread()) {
                    thread.join(CODEC_THREAD_JOIN_TIMEOUT_MS)
                }
            }
        }
        codecThread = null
        codecHandler = null
    }

    private fun releaseSurfaceResources() {
        releaseSafely("") {
            vdSurfaceTexture?.setOnFrameAvailableListener(null)
        }
        releaseSafely("Не удалось освободить SurfaceTexture VirtualDisplay") {
            vdSurfaceTexture?.release()
        }
        vdSurfaceTexture = null

        releaseSafely("Не удалось освободить surface VirtualDisplay") {
            vdInputSurface?.release()
        }
        vdInputSurface = null

        releaseSafely("Не удалось освободить входной Surface кодека") {
            encoderInputSurface?.release()
        }
        encoderInputSurface = null
    }

    private fun releaseMediaCodec() {
        releaseSafely("Не удалось освободить MediaCodec") {
            encoder?.release()
        }
        encoder = null
    }

    private inline fun releaseSafely(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (tag.isNotEmpty()) {
                Timber.tag(TAG).w(t, tag)
            }
        }
    }

    private fun acquireVirtualDisplayOrThrow() {
        val surface = vdInputSurface ?: throw IllegalStateException("vdInputSurface == null")
        val previousDisplayId = VdspState.getDisplayId()
        val vd = PersistentVirtualDisplay.acquire(
            context = context,
            width = width,
            height = height,
            dpi = dpi,
            surface = surface,
        )
        virtualDisplay = vd

        val displayId = vd.display?.displayId ?: -1
        if (displayId < 0) {
            throw IllegalStateException("VirtualDisplay создан без displayId")
        }

        val displayState = VdspState.set(displayId, width, height)
        notifyDisplayReady(displayId, displayState)

        if (previousDisplayId >= 0 && previousDisplayId == displayId) {
            Timber.tag(TAG).i("Переиспользую существующий VirtualDisplay display=$displayId")
        } else {
            Timber.tag(TAG).i("Создан VirtualDisplay display=$displayId")
        }

        Timber.tag(TAG).i("Запускаю displayLauncher.launchOnDisplay($displayId), component=$preferredLaunchComponent")
        displayLauncher.launchOnDisplay(displayId)
    }

    private fun notifyDisplayReady(displayId: Int, displayState: VdspState.DisplayState) {
        try {
            context.sendBroadcast(
                android.content.Intent(VdspState.ACTION_VDSP_READY)
                    .setPackage(context.packageName)
                    .putExtra(VdspState.EXTRA_DISPLAY_ID, displayId)
                    .putExtra(VdspState.EXTRA_WIDTH, width)
                    .putExtra(VdspState.EXTRA_HEIGHT, height),
            )
            context.sendBroadcast(
                android.content.Intent(VdspState.ACTION_VDSP_STATE_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(VdspState.EXTRA_DISPLAY_ID, displayId)
                    .putExtra(VdspState.EXTRA_WIDTH, width)
                    .putExtra(VdspState.EXTRA_HEIGHT, height)
                    .putExtra(VdspState.EXTRA_STATE, displayState.wireValue),
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось отправить broadcast о готовности VirtualDisplay")
        }
    }

    private fun notifyDisplayRemoved() {
        VideoDisplayLauncher.clearLaunchState()
        try {
            context.sendBroadcast(
                android.content.Intent(VdspState.ACTION_VDSP_STATE_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(VdspState.EXTRA_DISPLAY_ID, -1)
                    .putExtra(VdspState.EXTRA_WIDTH, width)
                    .putExtra(VdspState.EXTRA_HEIGHT, height)
                    .putExtra(VdspState.EXTRA_STATE, VdspState.DisplayState.REMOVED.wireValue),
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось отправить broadcast об исчезновении VirtualDisplay")
        }
    }

    private fun renderFrame() {
        if (!running || stopping) return
        val surfaceTexture = vdSurfaceTexture ?: return
        val composer = glComposer ?: return
        try {
            if (hasPendingSurfaceFrame || !hasRenderedAnyFrame) {
                composer.drawSurfaceFrame(
                    surfaceTexture = surfaceTexture,
                    presentationTimestampNs = frameTimingController.nextScheduledPresentationTimestampNs(System.nanoTime()),
                )
                hasPendingSurfaceFrame = false
                hasRenderedAnyFrame = true
            } else {
                composer.drawLastFrame(frameTimingController.nextScheduledPresentationTimestampNs(System.nanoTime()))
            }
            // markFrameScheduled удалён — nextScheduledPresentationTimestampNs уже
            // фиксирует lastPresentationTimestampNs через sanitizePresentationTimestamp.
            // Повторный вызов markFrameScheduled(System.nanoTime()) перезаписывал
            // scheduled timestamp wall-clock значением, создавая compounded drift.
        } catch (t: Throwable) {
            logRestartError(t, "Ошибка GL-композиции кадра -> рестарт")
            safeRequestRestart()
        }
    }

    private fun scheduleNextFrame() {
        val handler = codecHandler ?: return
        handler.removeCallbacks(frameLoopRunnable)
        val nowNs = System.nanoTime()
        val delayNs = nextFrameTimeNs - nowNs
        if (delayNs > 0) {
            handler.postDelayed(frameLoopRunnable, delayNs / 1_000_000L)
        } else {
            handler.post(frameLoopRunnable)
        }
    }

    private val frameLoopRunnable = object : Runnable {
        override fun run() {
            if (!running || stopping) return
            val nowNs = System.nanoTime()
            if (nowNs < nextFrameTimeNs) {
                scheduleNextFrame()
                return
            }
            // Если кадр опоздал более чем на 2 интервала, сбрасываем nextFrameTimeNs
            // чтобы избежать накопления задержки (compounded drift)
            val lagNs = nowNs - nextFrameTimeNs
            if (lagNs > frameIntervalNs * 2) {
                nextFrameTimeNs = nowNs
                Timber.tag(TAG).d("Кадр опоздал на ${lagNs / 1_000_000L}мс, сбрасываю тайминг")
            } else {
                nextFrameTimeNs += frameIntervalNs
            }
            renderFrame()
            scheduleNextFrame()
        }
    }

    private fun onEncoderOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (stopping || !running) {
            VideoCodecUtil.releaseOutputBufferQuietly(codec, index)
            return
        }
        outputProcessor.process(codec, index, info, configAnnexB)
    }

    private fun logFpsStats() {
        val nowMs = SystemClock.elapsedRealtime()
        if (fpsWindowStartedAtMs == 0L) {
            fpsWindowStartedAtMs = nowMs
            fpsWindowFrames = 1
            return
        }
        fpsWindowFrames += 1
        val elapsedMs = nowMs - fpsWindowStartedAtMs
        if (elapsedMs < FPS_LOG_WINDOW_MS) {
            return
        }
        val fps = fpsWindowFrames * 1000.0 / elapsedMs.toDouble()
        val fpsDeviation = fps - streamConfig.fps
        val fpsDeviationPercent = (fpsDeviation / streamConfig.fps) * 100.0
        val fpsStatus = when {
            fpsDeviationPercent < -10.0 -> "LOW"
            fpsDeviationPercent > 10.0 -> "HIGH"
            else -> "OK"
        }
        Timber.tag(TAG).d(
            "Захват VDSP активен: actualFps=${String.format(Locale.US, "%.2f", fps)}, targetFps=${streamConfig.fps}, fpsStatus=$fpsStatus, window=${elapsedMs}ms, frames=$fpsWindowFrames",
        )
        fpsWindowStartedAtMs = nowMs
        fpsWindowFrames = 0
    }

    private fun runOnCodecThread(task: Runnable) {
        val handler = codecHandler
        if (handler != null) {
            handler.post(task)
        } else {
            task.run()
        }
    }

    private fun requestSyncFrame() {
        encoder?.setParameters(
            Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            },
        )
    }

    private fun applyConfiguredBitrate() {
        try {
            encoder?.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, streamConfig.bitrate)
                },
            )
            Timber.tag(TAG).i("Принудительно применяю битрейт кодека: ${streamConfig.bitrate}bps")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось принудительно применить битрейт кодека")
        }
    }

    private fun drawKeepaliveFrame() {
        glComposer?.drawLastFrame(frameTimingController.nextScheduledPresentationTimestampNs(System.nanoTime()))
        hasRenderedAnyFrame = true
        hasPendingSurfaceFrame = false
        // markFrameScheduled удалён — см. renderFrame()
    }

    private fun resetFrameTrackingState() {
        fpsWindowStartedAtMs = 0L
        fpsWindowFrames = 0
        hasPendingSurfaceFrame = false
        hasRenderedAnyFrame = false
        nextFrameTimeNs = 0L
        frameTimingController.reset()
    }

    private fun logRestartError(error: Throwable, message: String) {
        val nowMs = SystemClock.elapsedRealtime()
        val elapsed = nowMs - lastRestartErrorLogMs
        if (lastRestartErrorLogMs == 0L || elapsed >= RESTART_ERROR_LOG_WINDOW_MS) {
            if (suppressedRestartErrorCount > 0) {
                Timber.tag(TAG).w("Ошибки рестарта видеопайплайна подавлены $suppressedRestartErrorCount раз")
                suppressedRestartErrorCount = 0
            }
            Timber.tag(TAG).e(error, message)
            lastRestartErrorLogMs = nowMs
        } else {
            suppressedRestartErrorCount++
        }
    }

    private fun safeRequestRestart() {
        try {
            restartCallback.requestRestart()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Не удалось запросить перезапуск видеопайплайна")
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_AVC = "video/avc"
        private const val CODEC_THREAD_JOIN_TIMEOUT_MS = 1_000L
        private const val FPS_LOG_WINDOW_MS = 5_000L
        private const val RESTART_ERROR_LOG_WINDOW_MS = 5_000L
    }

    private data class ConfiguredEncoder(
        val codec: MediaCodec,
        val inputSurface: Surface,
        val candidate: CodecConfigCandidate,
    )
}
