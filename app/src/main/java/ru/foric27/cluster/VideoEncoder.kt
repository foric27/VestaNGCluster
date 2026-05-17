package ru.foric27.cluster

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
 * Координатор видеопайплайна VirtualDisplay -> OpenGL -> MediaCodec -> UDP.
 *
 * Класс держит наружный фасад start/stop/relaunch/forceOutputFrame, а детали
 * display-launch, тайминга кадров и обработки выходных буферов делегирует
 * специализированным helper-компонентам в этом же пакете.
 */
internal class VideoEncoder(
    private val context: Context,
    private val streamConfig: StreamConfig,
    private val preferredLaunchComponent: String?,
    private val udpSender: UdpSender,
    private val restartCallback: RestartCallback,
) {

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
    private var hasPendingSurfaceFrame: Boolean = false
    private var hasRenderedAnyFrame: Boolean = false

    /**
     * Поднимает codec thread, настраивает MediaCodec и присоединяет к нему
     * persistent VirtualDisplay через GL-компоновщик.
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

            val mediaCodec = MediaCodec.createEncoderByType(MIME_AVC)
                .apply {
                    setCallback(codecCallback, codecHandler)
                    configure(buildCodecFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                .also { encoder = it }
            encoderInputSurface = mediaCodec.createInputSurface()

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

            mediaCodec.start()
            applyConfiguredBitrate()

            Timber.tag(TAG).i(
                "Профиль захвата: ${RuntimeConfig.Video.SIZE_SHORT}@${dpi}, constantFps=${streamConfig.fps}, bitrate=${streamConfig.bitrate}bps, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}, profile=${RuntimeConfig.Video.ENCODER_PROFILE}, level=${RuntimeConfig.Video.ENCODER_LEVEL}",
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
     */
    fun stop() {
        transitionLifecycle(VideoCaptureLifecycleEvent.STOP_REQUESTED)
        stopping = true
        running = false
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
     */
    fun relaunchTargetActivityIfNeeded(reason: String) {
        val displayId = virtualDisplay?.display?.displayId ?: VdspState.getDisplayId()
        if (displayId < 0) {
            Timber.tag(TAG).w("Пропускаю повторный запуск навигатора: displayId недоступен, reason=$reason")
            return
        }
        runOnCodecThread {
            try {
                displayLauncher.launchOnDisplay(displayId)
                Timber.tag(TAG).i("Повторно активирую навигатор на display=$displayId, reason=$reason")
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Не удалось повторно активировать навигатор на display=$displayId, reason=$reason")
            }
        }
    }

    /**
     * Принудительно просит кодек отдать кадр после wake/recovery-сценариев.
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
                Timber.tag(TAG).e(t, "Ошибка обработки output buffer -> рестарт")
                releaseOutputBufferQuietly(codec, index)
                safeRequestRestart()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Timber.tag(TAG).e(e, "Ошибка MediaCodec -> рестарт")
            safeRequestRestart()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val csd0 = format.getByteBuffer("csd-0")
            val csd1 = format.getByteBuffer("csd-1")
            val built = H264AnnexBUtil.buildConfigAnnexB(csd0, csd1)
            configAnnexB = built
            Timber.tag(TAG).w("onOutputFormatChanged: csd0=%s, csd1=%s, configAnnexB=%s",
                csd0?.remaining()?.toString() ?: "null",
                csd1?.remaining()?.toString() ?: "null",
                built?.size?.toString() ?: "null")
        }
    }

    private fun buildCodecFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, streamConfig.bitrate)
            trySetInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, streamConfig.fps)
            try {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, streamConfig.iframeIntervalSec)
            } catch (_: Throwable) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, streamConfig.iframeIntervalSec.toFloat())
            }
            trySetInteger(MediaFormat.KEY_PROFILE, RuntimeConfig.Video.ENCODER_PROFILE)
            trySetInteger(MediaFormat.KEY_LEVEL, RuntimeConfig.Video.ENCODER_LEVEL)
            trySetInteger(MediaFormat.KEY_PRIORITY, 0)
        }
    }

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
            Timber.tag(TAG).e(t, "Ошибка GL-композиции кадра -> рестарт")
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
            nextFrameTimeNs += frameIntervalNs
            renderFrame()
            scheduleNextFrame()
        }
    }

    private fun onEncoderOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (stopping || !running) {
            releaseOutputBufferQuietly(codec, index)
            return
        }
        outputProcessor.process(codec, index, info, configAnnexB)
    }

    private fun releaseOutputBufferQuietly(codec: MediaCodec, index: Int) {
        try {
            codec.releaseOutputBuffer(index, false)
        } catch (_: Throwable) {
            // На остановке буфер уже может быть недоступен.
        }
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
        Timber.tag(TAG).i(
            "Захват VDSP активен: actualFps=${String.format(Locale.US, "%.2f", fps)}, targetFps=${streamConfig.fps}, window=${elapsedMs}ms, frames=$fpsWindowFrames",
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
        private const val FPS_LOG_WINDOW_MS = 2_000L
    }
}