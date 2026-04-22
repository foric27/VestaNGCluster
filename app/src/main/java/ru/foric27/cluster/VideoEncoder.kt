package ru.foric27.cluster

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Координатор видеопайплайна VirtualDisplay -> OpenGL -> MediaCodec -> UDP.
 *
 * Класс держит наружный фасад start/stop/relaunch/forceOutputFrame, а детали
 * display-launch, тайминга кадров и обработки выходных буферов делегирует
 * специализированным helper-компонентам в этом же пакете.
 */
class VideoEncoder(
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
    private val displayLauncher = VideoDisplayLauncher(context, streamConfig, preferredLaunchComponent)
    private val outputProcessor = VideoCodecOutputProcessor(udpSender, ::updateDynamicFpsStats, streamConfig)
    private val frameTimingController = VideoFrameTimingController(streamConfig.fps, DYNAMIC_KEEPALIVE_PERIOD_MS)

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
    @Volatile private var configAnnexB: ByteArray? = null

    @Volatile private var fpsWindowStartedAtMs: Long = 0L
    @Volatile private var fpsWindowFrames: Int = 0
    @Volatile private var hasPendingSurfaceFrame: Boolean = false
    @Volatile private var hasRenderedAnyFrame: Boolean = false
    @Volatile private var dynamicRenderScheduled: Boolean = false

    /**
     * Поднимает codec thread, настраивает MediaCodec и присоединяет к нему
     * persistent VirtualDisplay через GL-компоновщик.
     */
    fun start() {
        if (running) return
        stopping = false
        running = true
        resetFrameTrackingState()

        try {
            val handlerThread = HandlerThread("ClusterCodec", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            handlerThread.start()
            Log.i(TAG, "Приоритет потока кодека повышен: ClusterCodec -> ${Process.THREAD_PRIORITY_URGENT_DISPLAY}")
            codecThread = handlerThread
            codecHandler = Handler(handlerThread.looper)

            val mediaCodec = MediaCodec.createEncoderByType(MIME_AVC)
            encoder = mediaCodec
            mediaCodec.setCallback(codecCallback, codecHandler)
            mediaCodec.configure(buildCodecFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInputSurface = mediaCodec.createInputSurface()

            glComposer = GlFrameComposer(
                outputSurface = encoderInputSurface ?: throw IllegalStateException("encoderInputSurface == null"),
                width = width,
                height = height,
                blackMaskHeightPx = RuntimeConfig.Video.BLACK_BOTTOM_PX,
            )

            val surfaceTexture = SurfaceTexture(glComposer!!.inputTextureId).apply {
                setDefaultBufferSize(width, height)
            }
            vdSurfaceTexture = surfaceTexture
            vdInputSurface = Surface(surfaceTexture)
            surfaceTexture.setOnFrameAvailableListener(
                {
                    hasPendingSurfaceFrame = true
                    if (streamConfig.dynamicFps) {
                        scheduleDynamicFrameRender()
                    }
                },
                codecHandler,
            )

            mediaCodec.start()
            applyConfiguredBitrate()

            Log.i(
                TAG,
                "Профиль захвата: ${RuntimeConfig.Video.SIZE_SHORT}@${dpi}, ${if (streamConfig.dynamicFps) "dynamicFps<=" else "constantFps="}${streamConfig.fps}, bitrate=${streamConfig.bitrate}bps, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}, blackBottom=${RuntimeConfig.Video.BLACK_BOTTOM_PX}px",
            )

            acquireVirtualDisplayOrThrow()
            if (!streamConfig.dynamicFps) {
                scheduleConstantFpsTick(initial = true)
            } else {
                scheduleDynamicKeepaliveTick()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Не удалось запустить видеокодер", t)
            safeStopInternal(releasePersistentDisplay = false)
            running = false
            stopping = false
            throw t
        }
    }

    /**
     * Останавливает кодек и освобождает связанные поверхности, не уничтожая
     * persistent VirtualDisplay без явной необходимости.
     */
    fun stop() {
        stopping = true
        running = false
        safeStopInternal(releasePersistentDisplay = false)
        stopping = false
    }

    /**
     * Повторно активирует целевую activity на текущем display после recovery,
     * если сам VirtualDisplay всё ещё валиден.
     */
    fun relaunchTargetActivityIfNeeded(reason: String) {
        val displayId = virtualDisplay?.display?.displayId ?: VdspState.getDisplayId()
        if (displayId < 0) {
            Log.w(TAG, "Пропускаю повторный запуск навигатора: displayId недоступен, reason=$reason")
            return
        }

        val relaunch = Runnable {
            try {
                displayLauncher.launchOnDisplay(displayId)
                Log.i(TAG, "Повторно активирую навигатор на display=$displayId, reason=$reason")
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось повторно активировать навигатор на display=$displayId, reason=$reason", t)
            }
        }
        runOnCodecThread(relaunch)
    }

    /**
     * Принудительно просит кодек отдать кадр после wake/recovery-сценариев.
     */
    fun forceOutputFrame(reason: String) {
        val render = Runnable {
            if (!running || stopping) return@Runnable
            try {
                if (streamConfig.dynamicFps) {
                    drawKeepaliveFrame(SystemClock.elapsedRealtime())
                }
                requestSyncFrame()
                Log.i(TAG, "Принудительно отправляю кадр после выхода из сна, reason=$reason")
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось принудительно отправить кадр после выхода из сна, reason=$reason", t)
            }
        }
        runOnCodecThread(render)
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                onEncoderOutput(codec, index, info)
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка обработки output buffer -> рестарт", t)
                releaseOutputBufferQuietly(codec, index)
                safeRequestRestart()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Ошибка MediaCodec -> рестарт", e)
            safeRequestRestart()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val csd0 = format.getByteBuffer("csd-0")
            val csd1 = format.getByteBuffer("csd-1")
            configAnnexB = H264AnnexBUtil.buildConfigAnnexB(csd0, csd1)
            Log.i(TAG, "Формат энкодера изменён, CSD обновлён")
        }
    }

    private fun buildCodecFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, streamConfig.bitrate)
            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (_: Throwable) {
            }
            setInteger(MediaFormat.KEY_FRAME_RATE, streamConfig.fps)
            if (streamConfig.dynamicFps) {
                try {
                    setInteger(MediaFormat.KEY_CAPTURE_RATE, streamConfig.fps)
                } catch (_: Throwable) {
                }
            }
            try {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, streamConfig.iframeIntervalSec)
            } catch (_: Throwable) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, streamConfig.iframeIntervalSec.toFloat())
            }
            try {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            } catch (_: Throwable) {
            }
            try {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel32)
            } catch (_: Throwable) {
            }
            try {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeStopInternal(releasePersistentDisplay: Boolean) {
        try {
            virtualDisplay?.setSurface(null)
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось отвязать Surface от VirtualDisplay", t)
        }
        if (releasePersistentDisplay) {
            PersistentVirtualDisplay.releaseAll()
        } else {
            PersistentVirtualDisplay.detachSurface()
        }
        virtualDisplay = null

        // Сначала останавливаем кодек, чтобы поток не пытался читать из Surface
        try {
            encoder?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось остановить MediaCodec", t)
        }

        // Завершаем поток кодека до освобождения GL-ресурсов
        val thread = codecThread
        if (thread != null) {
            try {
                thread.quitSafely()
                thread.join(CODEC_THREAD_JOIN_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось корректно завершить поток кодека", t)
            }
        }
        codecThread = null
        codecHandler = null

        try {
            vdSurfaceTexture?.setOnFrameAvailableListener(null)
        } catch (_: Throwable) {
        }
        try {
            vdSurfaceTexture?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить SurfaceTexture VirtualDisplay", t)
        }
        vdSurfaceTexture = null

        try {
            glComposer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить GL-компоновщик", t)
        }
        glComposer = null

        try {
            vdInputSurface?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить surface VirtualDisplay", t)
        }
        vdInputSurface = null

        try {
            encoderInputSurface?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить входной Surface кодека", t)
        }
        encoderInputSurface = null

        try {
            encoder?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить MediaCodec", t)
        }
        encoder = null

        resetFrameTrackingState()
        notifyDisplayRemoved()
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
            Log.i(TAG, "Переиспользую существующий VirtualDisplay display=$displayId")
        } else {
            Log.i(TAG, "Создан VirtualDisplay display=$displayId")
        }

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
            Log.w(TAG, "Не удалось отправить broadcast о готовности VirtualDisplay", t)
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
            Log.w(TAG, "Не удалось отправить broadcast об исчезновении VirtualDisplay", t)
        }
    }

    private fun renderLatestFrame() {
        if (!running || stopping) return
        val surfaceTexture = vdSurfaceTexture ?: return
        val composer = glComposer ?: return
        try {
            composer.drawSurfaceFrame(
                surfaceTexture = surfaceTexture,
                presentationTimestampNs = if (streamConfig.dynamicFps) {
                    null
                } else {
                    frameTimingController.nextScheduledPresentationTimestampNs(System.nanoTime())
                },
            )
            hasRenderedAnyFrame = true
            hasPendingSurfaceFrame = false
            frameTimingController.markRendered(SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка GL-композиции кадра -> рестарт", t)
            safeRequestRestart()
        }
    }

    private fun renderConstantFpsFrame() {
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
            frameTimingController.markRendered(SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка постоянного FPS -> рестарт", t)
            safeRequestRestart()
        }
    }

    private fun scheduleConstantFpsTick(initial: Boolean = false) {
        val handler = codecHandler ?: return
        val delayMs = (1000L / streamConfig.fps.coerceAtLeast(1)).coerceAtLeast(1L)
        handler.removeCallbacks(constantFpsRunnable)
        handler.postDelayed(constantFpsRunnable, if (initial) delayMs else 0L)
    }

    private fun scheduleDynamicFrameRender() {
        val handler = codecHandler ?: return
        val delayMs = frameTimingController.delayUntilNextDynamicRender(SystemClock.elapsedRealtime())

        if (dynamicRenderScheduled) {
            if (delayMs <= 0L) {
                handler.removeCallbacks(dynamicFrameRunnable)
                handler.post(dynamicFrameRunnable)
            }
            return
        }

        dynamicRenderScheduled = true
        if (delayMs <= 0L) {
            handler.post(dynamicFrameRunnable)
        } else {
            handler.postDelayed(dynamicFrameRunnable, delayMs)
        }
    }

    private fun scheduleDynamicKeepaliveTick() {
        val handler = codecHandler ?: return
        handler.removeCallbacks(dynamicKeepaliveRunnable)
        handler.postDelayed(dynamicKeepaliveRunnable, DYNAMIC_KEEPALIVE_PERIOD_MS)
    }

    private fun onEncoderOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (stopping || !running) {
            releaseOutputBufferQuietly(codec, index)
            return
        }
        outputProcessor.process(codec, index, info, configAnnexB)
    }

    private val constantFpsRunnable = object : Runnable {
        override fun run() {
            if (!running || stopping || streamConfig.dynamicFps) return
            renderConstantFpsFrame()
            scheduleConstantFpsTick(initial = true)
        }
    }

    private val dynamicKeepaliveRunnable = object : Runnable {
        override fun run() {
            if (!running || stopping || !streamConfig.dynamicFps) return

            val nowMs = SystemClock.elapsedRealtime()
            val idleMs = frameTimingController.idleDurationMs(nowMs)
            if (frameTimingController.shouldEmitKeepalive(nowMs)) {
                try {
                    drawKeepaliveFrame(nowMs)
                    Log.i(TAG, "Отправляю keepalive-кадр для поддержания потока, idle=${idleMs}ms")
                } catch (t: Throwable) {
                    Log.e(TAG, "Ошибка keepalive-кадра в dynamicFps -> рестарт", t)
                    safeRequestRestart()
                    return
                }
            }

            scheduleDynamicKeepaliveTick()
        }
    }

    private val dynamicFrameRunnable = object : Runnable {
        override fun run() {
            dynamicRenderScheduled = false
            if (!running || stopping || !streamConfig.dynamicFps) return
            if (!hasPendingSurfaceFrame) return

            val remainingDelayMs = frameTimingController.delayUntilNextDynamicRender(SystemClock.elapsedRealtime())
            if (remainingDelayMs > 0L) {
                scheduleDynamicFrameRender()
                return
            }

            renderLatestFrame()
            if (hasPendingSurfaceFrame) {
                scheduleDynamicFrameRender()
            }
        }
    }

    private fun updateDynamicFpsStats() {
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
        Log.i(
            TAG,
            "Захват VDSP активен: actualFps=${String.format(Locale.US, "%.2f", fps)}, ${if (streamConfig.dynamicFps) "dynamicMaxFps" else "constantFps"}=${streamConfig.fps}, window=${elapsedMs}ms, frames=$fpsWindowFrames, blackBottom=${RuntimeConfig.Video.BLACK_BOTTOM_PX}px",
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
            Log.i(TAG, "Принудительно применяю битрейт кодека: ${streamConfig.bitrate}bps")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось принудительно применить битрейт кодека", t)
        }
    }

    private fun drawKeepaliveFrame(nowMs: Long) {
        glComposer?.drawLastFrame(frameTimingController.nextScheduledPresentationTimestampNs(System.nanoTime()))
        hasRenderedAnyFrame = true
        hasPendingSurfaceFrame = false
        frameTimingController.markRendered(nowMs)
    }

    private fun resetFrameTrackingState() {
        fpsWindowStartedAtMs = 0L
        fpsWindowFrames = 0
        hasPendingSurfaceFrame = false
        hasRenderedAnyFrame = false
        dynamicRenderScheduled = false
        frameTimingController.reset()
    }

    private fun safeRequestRestart() {
        try {
            restartCallback.requestRestart()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запросить перезапуск видеопайплайна", t)
        }
    }

    private class GlFrameComposer(
        outputSurface: Surface,
        private val width: Int,
        private val height: Int,
        private val blackMaskHeightPx: Int,
    ) {
        private val vertexBuffer: FloatBuffer = createFloatBuffer(FULL_RECT_VERTICES)
        private val texCoordBuffer: FloatBuffer = createFloatBuffer(FULL_RECT_TEX_COORDS)
        private val transformMatrix = FloatArray(16)

        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE

        val inputTextureId: Int
        private val program: Int
        private val positionLoc: Int
        private val texCoordLoc: Int
        private val textureMatrixLoc: Int
        private val timestampSanitizer = VideoFrameTimingController(fps = 1, keepalivePeriodMs = 1L)

        init {
            try {
                val recordableConfig = IntArray(1)
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val numConfigs = IntArray(1)

                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
                require(EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 0)) { "eglInitialize failed" }

                val attribList = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE,
                )
                require(EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                    "eglChooseConfig failed"
                }
                val config = configs[0] ?: error("EGL config == null")

                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE,
                )
                eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                require(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0)
                require(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
                makeCurrent()

                inputTextureId = createExternalTexture()
                program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
                positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
                texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
                textureMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
                GLES20.glViewport(0, 0, width, height)

                // Освобождаем EGL-контекст на потоке инициализации.
                // Дальше drawFrame будет привязывать его на codecHandler-потоке перед updateTexImage().
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            } catch (t: Throwable) {
                release()
                throw t
            }
        }

        fun drawSurfaceFrame(surfaceTexture: SurfaceTexture, presentationTimestampNs: Long? = null) {
            makeCurrent()
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
            val timestampNs = presentationTimestampNs
                ?: surfaceTexture.timestamp.takeIf { it > 0L }
                ?: System.nanoTime()
            drawPreparedFrame(timestampSanitizer.sanitizePresentationTimestamp(timestampNs))
        }

        fun drawLastFrame(presentationTimestampNs: Long? = null) {
            makeCurrent()
            val timestampNs = presentationTimestampNs ?: System.nanoTime()
            drawPreparedFrame(timestampSanitizer.sanitizePresentationTimestamp(timestampNs))
        }

        private fun drawPreparedFrame(timestampNs: Long) {

            GLES20.glViewport(0, 0, width, height)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionLoc)
            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glUniformMatrix4fv(textureMatrixLoc, 1, false, transformMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)

            if (blackMaskHeightPx > 0) {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(0, 0, width, blackMaskHeightPx)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                makeCurrent()
                GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
                GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun makeCurrent() {
            require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        }

        private fun createExternalTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return textureId
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                error("glLinkProgram failed: $log")
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("glCompileShader failed: $log")
            }
            return shader
        }

        private fun createFloatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }

        private companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
            private val FULL_RECT_VERTICES = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            )
            private val FULL_RECT_TEX_COORDS = floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            )
            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """
            private const val FRAGMENT_SHADER_OES = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_AVC = "video/avc"
        private const val CODEC_THREAD_JOIN_TIMEOUT_MS = 1_000L
        private const val FPS_LOG_WINDOW_MS = 2_000L
        private const val DYNAMIC_KEEPALIVE_PERIOD_MS = 1_500L
        
        private fun releaseOutputBufferQuietly(codec: MediaCodec, index: Int) {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Throwable) {
            }
        }
    }
}
