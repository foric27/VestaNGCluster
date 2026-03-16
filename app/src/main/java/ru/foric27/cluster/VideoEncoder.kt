package ru.foric27.cluster

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Выводит целевую activity на `VirtualDisplay`, а затем композит кадр через OpenGL
 * в `Surface` энкодера.
 *
 * Такой подход позволяет независимо от содержимого activity принудительно
 * закрашивать нижнюю область кадра чёрным прямо перед подачей в `MediaCodec`.
 * Значение FPS в конфигурации используется как верхняя граница для кодека,
 * а фактический FPS остаётся динамическим и определяется реальными обновлениями UI.
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

    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderInputSurface: Surface? = null
    private var vdInputSurface: Surface? = null
    private var vdSurfaceTexture: SurfaceTexture? = null
    private var glComposer: GlFrameComposer? = null
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null

    @Volatile private var running: Boolean = false
    @Volatile private var stopping: Boolean = false
    @Volatile private var configAnnexB: ByteArray? = null

    @Volatile private var fpsWindowStartedAtMs: Long = 0L
    @Volatile private var fpsWindowFrames: Int = 0
    @Volatile private var hasPendingSurfaceFrame: Boolean = false
    @Volatile private var hasRenderedAnyFrame: Boolean = false

    fun start() {
        if (running) return
        stopping = false
        running = true

        try {
            val handlerThread = HandlerThread("ClusterCodec")
            handlerThread.start()
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
                    if (streamConfig.dynamicFps) {
                        renderLatestFrame()
                    } else {
                        hasPendingSurfaceFrame = true
                    }
                },
                codecHandler,
            )

            mediaCodec.start()

            Log.i(
                TAG,
                "Профиль захвата: ${RuntimeConfig.Video.SIZE_SHORT}@${dpi}, ${if (streamConfig.dynamicFps) "dynamicFps<=" else "constantFps="}${streamConfig.fps}, bitrate=${streamConfig.bitrate}bps, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}, blackBottom=${RuntimeConfig.Video.BLACK_BOTTOM_PX}px",
            )

            acquireVirtualDisplayOrThrow()
            if (!streamConfig.dynamicFps) {
                scheduleConstantFpsTick(initial = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Не удалось запустить VideoEncoder", t)
            safeStopInternal(releasePersistentDisplay = false)
            running = false
            stopping = false
            throw t
        }
    }

    fun stop() {
        stopping = true
        running = false
        safeStopInternal(releasePersistentDisplay = false)
        stopping = false
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                onEncoderOutput(codec, index, info)
            } catch (t: Throwable) {
                Log.e(TAG, "Ошибка onOutputBufferAvailable -> рестарт", t)
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
            setInteger(MediaFormat.KEY_FRAME_RATE, streamConfig.fps)
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
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (_: Throwable) {
                }
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

        try {
            vdInputSurface?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить surface VirtualDisplay", t)
        }
        vdInputSurface = null

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
            Log.w(TAG, "Не удалось освободить GL composer", t)
        }
        glComposer = null

        try {
            encoderInputSurface?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить input Surface кодека", t)
        }
        encoderInputSurface = null

        try {
            encoder?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось остановить MediaCodec", t)
        }
        try {
            encoder?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось освободить MediaCodec", t)
        }
        encoder = null

        val thread = codecThread
        if (thread != null) {
            try {
                thread.quitSafely()
                thread.join(CODEC_THREAD_JOIN_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "Не удалось корректно завершить codecThread", t)
            }
        }
        codecThread = null
        codecHandler = null
        fpsWindowStartedAtMs = 0L
        fpsWindowFrames = 0
        hasPendingSurfaceFrame = false
        hasRenderedAnyFrame = false
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

        VdspState.set(displayId, width, height)
        notifyDisplayReady(displayId)

        if (previousDisplayId >= 0 && previousDisplayId == displayId) {
            Log.i(TAG, "Переиспользую существующий VirtualDisplay display=$displayId")
        } else {
            Log.i(TAG, "Создан VirtualDisplay display=$displayId")
        }

        launchFixedActivityOnDisplayBestEffort(displayId)
    }

    private fun notifyDisplayReady(displayId: Int) {
        try {
            context.sendBroadcast(
                android.content.Intent(VdspState.ACTION_VDSP_READY)
                    .setPackage(context.packageName)
                    .putExtra("displayId", displayId)
                    .putExtra("width", width)
                    .putExtra("height", height),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось отправить broadcast о готовности VirtualDisplay", t)
        }
    }

    private fun launchFixedActivityOnDisplayBestEffort(displayId: Int) {
        val commands = YandexLaunchTarget.buildPreferredCommands(preferredLaunchComponent)
        var started: YandexLaunchTarget.LaunchCommand? = null
        var lastShellResult = RootShell.Result(-1, "", "not_started")
        var lastDirectError: Throwable? = null

        for (command in commands) {
            val direct = launchViaIntentBestEffort(displayId, command)
            if (direct.success) {
                started = command
                Log.i(TAG, "Proxy activity запрошена через Context.startActivity на display=$displayId: ${command.component}, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
                break
            }
            lastDirectError = direct.error

            val shellResult = RootShell.su(listOf(YandexLaunchTarget.buildProxyAmStartCommand(displayId, command)))
            lastShellResult = shellResult
            if (shellResult.ok()) {
                started = command
                Log.i(TAG, "Proxy activity запрошена через su/am start на display=$displayId: ${command.component}, visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
                break
            }
        }

        if (started != null) {
            Log.i(TAG, "Запрос на активацию activity для вывода отправлен на display=$displayId: ${started.component} (${started.note}), visibleArea=${YandexLaunchTarget.CLUSTER_VISIBLE_AREA_SHORT}")
            return
        }

        if (isLaunchTargetMissing(lastShellResult) || lastDirectError is ActivityNotFoundException) {
            notifyLaunchAppMissing(commands.lastOrNull())
        }
        Log.w(
            TAG,
            "Не удалось запустить Яндекс.Навигатор на display=$displayId. " +
                "Последняя попытка: ${commands.lastOrNull()?.component ?: "не задана"}. " +
                "DirectError=${lastDirectError?.message ?: "null"}. " +
                "STDOUT=${lastShellResult.out} STDERR=${lastShellResult.err}",
        )
    }

    private fun launchViaIntentBestEffort(
        displayId: Int,
        command: YandexLaunchTarget.LaunchCommand,
    ): LaunchAttempt {
        val intent = YandexLaunchTarget.buildProxyIntent(command)

        return try {
            val options = if (Build.VERSION.SDK_INT >= 26) {
                ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                    .toBundle()
            } else {
                null
            }
            context.startActivity(intent, options)
            LaunchAttempt(true, null)
        } catch (t: Throwable) {
            LaunchAttempt(false, t)
        }
    }

    private fun isLaunchTargetMissing(result: RootShell.Result): Boolean {
        val text = buildString {
            append(result.out)
            append('\n')
            append(result.err)
        }.lowercase()

        return text.contains("error type 3") ||
            text.contains("does not exist") ||
            text.contains("activity class")
    }

    private fun notifyLaunchAppMissing(command: YandexLaunchTarget.LaunchCommand?) {
        val msg = context.getString(
            R.string.msg_output_app_not_found_fmt,
            command?.component ?: YandexLaunchTarget.COMPONENT_AUTO_CLUSTER,
        )
        RootShell.publishUserWarning(msg)
    }

    private fun renderLatestFrame() {
        if (!running || stopping) return
        val surfaceTexture = vdSurfaceTexture ?: return
        val composer = glComposer ?: return
        try {
            composer.drawSurfaceFrame(surfaceTexture)
            hasRenderedAnyFrame = true
            hasPendingSurfaceFrame = false
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
                composer.drawSurfaceFrame(surfaceTexture)
                hasPendingSurfaceFrame = false
                hasRenderedAnyFrame = true
            } else {
                composer.drawLastFrame()
            }
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

    private fun onEncoderOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (stopping || !running) {
            releaseOutputBufferQuietly(codec, index)
            return
        }

        if (info.size <= 0) {
            releaseOutputBufferQuietly(codec, index)
            return
        }

        val outBuffer = codec.getOutputBuffer(index)
        if (outBuffer == null) {
            releaseOutputBufferQuietly(codec, index)
            return
        }

        val payload = outBuffer.readBytes(info.offset, info.size)
        releaseOutputBufferQuietly(codec, index)

        val payloadAnnexB = H264AnnexBUtil.ensureAnnexB(payload) ?: return
        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (codecConfig) {
            return
        }

        val annexB = if (keyFrame) prependCodecConfigIfNeeded(payloadAnnexB, configAnnexB) else payloadAnnexB
        udpSender.sendFrame(annexB)
        updateDynamicFpsStats()
    }

    private val constantFpsRunnable = object : Runnable {
        override fun run() {
            if (!running || stopping || streamConfig.dynamicFps) return
            renderConstantFpsFrame()
            scheduleConstantFpsTick(initial = true)
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
            "VD capture active: actualFps=${String.format(Locale.US, "%.2f", fps)}, ${if (streamConfig.dynamicFps) "dynamicMaxFps" else "constantFps"}=${streamConfig.fps}, window=${elapsedMs}ms, frames=$fpsWindowFrames, blackBottom=${RuntimeConfig.Video.BLACK_BOTTOM_PX}px",
        )
        fpsWindowStartedAtMs = nowMs
        fpsWindowFrames = 0
    }

    private fun prependCodecConfigIfNeeded(frameAnnexB: ByteArray, configAnnexB: ByteArray?): ByteArray {
        val config = configAnnexB ?: return frameAnnexB
        if (config.isEmpty()) return frameAnnexB
        if (frameAnnexB.size >= config.size && frameAnnexB.copyOfRange(0, config.size).contentEquals(config)) {
            return frameAnnexB
        }
        return ByteArray(config.size + frameAnnexB.size).also { out ->
            System.arraycopy(config, 0, out, 0, config.size)
            System.arraycopy(frameAnnexB, 0, out, config.size, frameAnnexB.size)
        }
    }

    private fun safeRequestRestart() {
        try {
            restartCallback.requestRestart()
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось запросить перезапуск пайплайна", t)
        }
    }

    private fun ByteBuffer.readBytes(offset: Int, size: Int): ByteArray {
        val dup = duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        return ByteArray(size).also { dup.get(it) }
    }

    private data class LaunchAttempt(
        val success: Boolean,
        val error: Throwable?,
    )

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

        init {
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
        }

        fun drawSurfaceFrame(surfaceTexture: SurfaceTexture) {
            makeCurrent()
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
            val timestampNs = surfaceTexture.timestamp.takeIf { it > 0L } ?: System.nanoTime()
            drawPreparedFrame(timestampNs)
        }

        fun drawLastFrame() {
            makeCurrent()
            drawPreparedFrame(System.nanoTime())
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
        
        private fun releaseOutputBufferQuietly(codec: MediaCodec, index: Int) {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: Throwable) {
            }
        }
    }
}
