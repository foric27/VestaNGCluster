package ru.foric27.cluster

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class GlFrameComposer(
    outputSurface: Surface,
    private val width: Int,
    private val height: Int,
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
        try {
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
            // Далее drawFrame будет привязывать его на codecHandler-потоке перед updateTexImage().
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
        renderInternal(surfaceTexture, presentationTimestampNs)
    }

    fun drawLastFrame(presentationTimestampNs: Long? = null) {
        renderInternal(null, presentationTimestampNs)
    }

    private fun renderInternal(surfaceTexture: SurfaceTexture?, presentationTimestampNs: Long?) {
        makeCurrent()

        if (surfaceTexture != null) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transformMatrix)
        }

        val timestampNs = presentationTimestampNs
            ?: surfaceTexture?.timestamp?.takeIf { it > 0L }
            ?: System.nanoTime()

        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
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

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeCurrent()
            if (inputTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
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
        if (EGL14.eglGetCurrentContext() == eglContext) return
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(coords)
        buffer.position(0)
        return buffer
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