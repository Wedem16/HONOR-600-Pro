package com.example.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.color.LogProfile
import com.example.color.Lut3DData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

enum class FocusPeakingColor(val label: String, val r: Float, val g: Float, val b: Float) {
    CRIMSON("Red", 1.0f, 0.16f, 0.22f),
    CYAN("Cyan", 0.16f, 0.90f, 1.0f),
    AMBER("Amber", 1.0f, 0.78f, 0.12f)
}

data class RenderPipelineConfig(
    val logProfile: LogProfile = LogProfile.STANDARD_REC709,
    val lutEnabled: Boolean = false,
    val lutIntensity: Float = 0.85f,
    val splitScreen: Boolean = false,
    val splitPosition: Float = 0.5f,
    val falseColor: Boolean = false,
    val zebraEnabled: Boolean = false,
    val zebraThreshold: Float = 0.92f,
    val focusPeaking: Boolean = false,
    val focusPeakingThreshold: Float = 0.22f,
    val focusPeakingColor: FocusPeakingColor = FocusPeakingColor.CRIMSON,
    val telemetryEnabled: Boolean = true
)

/**
 * Authentic per-frame pixel telemetry computed from real camera frames read back from OpenGL ES 3.0.
 */
data class LiveScopeTelemetry(
    val lumaHistogram: FloatArray = FloatArray(64),
    val redHistogram: FloatArray = FloatArray(64),
    val greenHistogram: FloatArray = FloatArray(64),
    val blueHistogram: FloatArray = FloatArray(64),
    // waveformGrid[col * WAVEFORM_ROWS + row] normalized density 0..1
    val lumaWaveform: FloatArray = FloatArray(WAVEFORM_COLS * WAVEFORM_ROWS),
    // rgbParade: 3 channels x WAVEFORM_COLS x WAVEFORM_ROWS
    val redWaveform: FloatArray = FloatArray(PARADE_COLS * WAVEFORM_ROWS),
    val greenWaveform: FloatArray = FloatArray(PARADE_COLS * WAVEFORM_ROWS),
    val blueWaveform: FloatArray = FloatArray(PARADE_COLS * WAVEFORM_ROWS),
    val averageIre: Float = 0f,
    val minIre: Float = 0f,
    val maxIre: Float = 0f,
    val clippedHighlightPct: Float = 0f,
    val crushedShadowPct: Float = 0f,
    val frameCount: Long = 0L
) {
    companion object {
        const val WAVEFORM_COLS = 64
        const val PARADE_COLS = 32
        const val WAVEFORM_ROWS = 48
    }
}

/**
 * Dedicated OpenGL ES 3.0 EGL rendering engine processing live camera frames (`GL_TEXTURE_EXTERNAL_OES`)
 * through real-time Log/HDR transfer functions, hardware trilinear `GL_TEXTURE_3D` LUTs, GPU
 * monitoring overlays (Zebra, Focus Peaking, False Color, 50/50 Split), live scope readback, and
 * simultaneous clean-feed rendering into a hardware `MediaCodec` video encoder surface.
 */
class GLLutLogRenderer(
    private val context: Context,
    private val onTelemetryUpdate: (LiveScopeTelemetry) -> Unit
) {
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var fallbackPbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var programId = 0
    private var cameraOesTextureId = 0
    private var lutTexture3DId = 0
    private var currentLutSize = 2

    private var surfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null

    // Offscreen FBO for fast downsampled pixel telemetry readback (96x54)
    private var scopeFboId = 0
    private var scopeTexId = 0
    private val scopeBuffer: ByteBuffer = ByteBuffer.allocateDirect(SCOPE_W * SCOPE_H * 4)
        .order(ByteOrder.nativeOrder())

    private var previewWidth = 1
    private var previewHeight = 1
    private var cameraBufferWidth = 1920
    private var cameraBufferHeight = 1080
    private var encoderWidth = 1920
    private var encoderHeight = 1080

    private val texMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val encoderMvpMatrix = FloatArray(16)

    private val configRef = AtomicReference(RenderPipelineConfig())
    private val isInitialized = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private var pendingLutUpload: Lut3DData? = null
    private var startTimeNs = System.nanoTime()
    private var lastTelemetryNs = 0L
    private var frameCounter = 0L

    private var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // Shader uniform locations
    private var uMvpMatrixLoc = -1
    private var uTexMatrixLoc = -1
    private var uCameraTextureLoc = -1
    private var uLutTextureLoc = -1
    private var uLogProfileIdLoc = -1
    private var uLutIntensityLoc = -1
    private var uLutEnabledLoc = -1
    private var uLutSizeLoc = -1
    private var uEnableOverlaysLoc = -1
    private var uSplitScreenLoc = -1
    private var uSplitPositionLoc = -1
    private var uFalseColorLoc = -1
    private var uZebraEnabledLoc = -1
    private var uZebraThresholdLoc = -1
    private var uTimeSecondsLoc = -1
    private var uFocusPeakingLoc = -1
    private var uFocusPeakingThresholdLoc = -1
    private var uFocusPeakingColorLoc = -1
    private var uTexelSizeLoc = -1

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                )
            )
            position(0)
        }

    fun start(onReady: (SurfaceTexture) -> Unit) {
        if (isReleased.get()) return
        onSurfaceTextureReady = onReady
        if (glThread != null) {
            glHandler?.post { surfaceTexture?.let(onReady) }
            return
        }
        glThread = HandlerThread("Honor600GLThread").apply { start() }
        glHandler = Handler(glThread!!.looper)
        glHandler?.post {
            initEglAndGl()
            surfaceTexture?.let { st -> onSurfaceTextureReady?.invoke(st) }
        }
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        glHandler?.post {
            destroyPreviewSurfaceLocked()
            previewWidth = max(1, width)
            previewHeight = max(1, height)
            if (surface != null && surface.isValid && eglDisplay != EGL14.EGL_NO_DISPLAY && eglConfig != null) {
                val attribs = intArrayOf(EGL14.EGL_NONE)
                previewEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
                updateMvpMatrix()
            }
        }
    }

    fun updatePreviewViewport(width: Int, height: Int) {
        glHandler?.post {
            previewWidth = max(1, width)
            previewHeight = max(1, height)
            updateMvpMatrix()
        }
    }

    fun setCameraBufferSize(width: Int, height: Int) {
        glHandler?.post {
            cameraBufferWidth = max(1, width)
            cameraBufferHeight = max(1, height)
            surfaceTexture?.setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
            updateMvpMatrix()
        }
    }

    fun setRecordingSurface(surface: Surface?, width: Int, height: Int) {
        val latch = CountDownLatch(1)
        glHandler?.post {
            try {
                destroyEncoderSurfaceLocked()
                encoderWidth = max(1, width)
                encoderHeight = max(1, height)
                if (surface != null && surface.isValid && eglDisplay != EGL14.EGL_NO_DISPLAY && eglConfig != null) {
                    val attribs = intArrayOf(EGL14.EGL_NONE)
                    encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
                    Matrix.setIdentityM(encoderMvpMatrix, 0)
                }
            } finally {
                latch.countDown()
            }
        } ?: latch.countDown()
        runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }
    }

    fun updateConfig(newConfig: RenderPipelineConfig) {
        configRef.set(newConfig)
    }

    fun uploadLut(lut: Lut3DData) {
        glHandler?.post {
            if (isInitialized.get()) {
                makeContextCurrent(fallbackPbufferSurface)
                uploadLutTextureLocked(lut)
            } else {
                pendingLutUpload = lut
            }
        }
    }

    /**
     * Captures the currently graded OpenGL ES 3.0 output frame cleanly as a JPEG byte array
     * (without monitoring overlays) for instant WYSIWYG Log + 3D LUT still photos.
     */
    fun captureGradedJpegSnapshot(width: Int, height: Int, quality: Int = 95): ByteArray? {
        val outRef = AtomicReference<ByteArray?>(null)
        val latch = CountDownLatch(1)
        glHandler?.post {
            try {
                if (!isInitialized.get()) return@post
                val targetW = width.coerceIn(640, 2560)
                val targetH = height.coerceIn(480, 2560)
                makeContextCurrent(fallbackPbufferSurface)

                val fbo = IntArray(1)
                val tex = IntArray(1)
                GLES30.glGenFramebuffers(1, fbo, 0)
                GLES30.glGenTextures(1, tex, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    targetW, targetH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
                )
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, tex[0], 0
                )

                val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
                GLES30.glViewport(0, 0, targetW, targetH)
                drawQuadLocked(identity, enableOverlays = false)

                val pixels = ByteBuffer.allocateDirect(targetW * targetH * 4).order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, targetW, targetH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, fbo, 0)
                GLES30.glDeleteTextures(1, tex, 0)

                val rawBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                pixels.rewind()
                rawBitmap.copyPixelsFromBuffer(pixels)

                // OpenGL origin is bottom-left; flip vertically for standard JPEG orientation
                val flipMatrix = android.graphics.Matrix().apply { preScale(1f, -1f) }
                val oriented = Bitmap.createBitmap(rawBitmap, 0, 0, targetW, targetH, flipMatrix, false)
                rawBitmap.recycle()

                val baos = ByteArrayOutputStream()
                oriented.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                oriented.recycle()
                outRef.set(baos.toByteArray())
            } catch (t: Throwable) {
                Log.e(TAG, "Graded snapshot capture failed", t)
            } finally {
                latch.countDown()
            }
        } ?: latch.countDown()
        runCatching { latch.await(2500, TimeUnit.MILLISECONDS) }
        return outRef.get()
    }

    fun getCameraInputSurface(): Surface? = cameraInputSurface

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        glHandler?.post {
            try {
                destroyPreviewSurfaceLocked()
                destroyEncoderSurfaceLocked()
                if (fallbackPbufferSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, fallbackPbufferSurface)
                    fallbackPbufferSurface = EGL14.EGL_NO_SURFACE
                }
                if (programId != 0) {
                    GLES30.glDeleteProgram(programId)
                    programId = 0
                }
                if (scopeFboId != 0) {
                    GLES30.glDeleteFramebuffers(1, intArrayOf(scopeFboId), 0)
                    scopeFboId = 0
                }
                if (scopeTexId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(scopeTexId), 0)
                    scopeTexId = 0
                }
                if (lutTexture3DId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(lutTexture3DId), 0)
                    lutTexture3DId = 0
                }
                if (cameraOesTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(cameraOesTextureId), 0)
                    cameraOesTextureId = 0
                }
                runCatching { cameraInputSurface?.release() }
                runCatching { surfaceTexture?.release() }
                cameraInputSurface = null
                surfaceTexture = null

                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                isInitialized.set(false)
            } finally {
                latch.countDown()
            }
        } ?: latch.countDown()
        runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    private fun initEglAndGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "EGL14 initialization failed")
            return
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            // Fallback without EGL_RECORDABLE_ANDROID for emulators
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0)
        }
        eglConfig = configs[0] ?: return

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Failed to create OpenGL ES 3.0 context")
            return
        }

        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
        fallbackPbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        makeContextCurrent(fallbackPbufferSurface)

        // Load shaders from assets
        val vertexSrc = readAssetText("shaders/cinema_vertex.glsl")
        val fragmentSrc = readAssetText("shaders/cinema_fragment.glsl")
        programId = createProgram(vertexSrc, fragmentSrc)
        if (programId == 0) {
            Log.e(TAG, "Shader compilation/link failed")
            return
        }
        cacheUniformLocations()

        // Create external OES texture for camera frames
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraOesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Create identity 2x2x2 3D LUT texture until user selects a look
        val lutTex = IntArray(1)
        GLES30.glGenTextures(1, lutTex, 0)
        lutTexture3DId = lutTex[0]
        uploadIdentityLutLocked()
        pendingLutUpload?.let {
            uploadLutTextureLocked(it)
            pendingLutUpload = null
        }

        // Create offscreen FBO for live scope telemetry readback
        setupScopeFboLocked()

        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(encoderMvpMatrix, 0)
        Matrix.setIdentityM(texMatrix, 0)

        surfaceTexture = SurfaceTexture(cameraOesTextureId).apply {
            setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
            setOnFrameAvailableListener({ st ->
                onCameraFrameAvailable(st)
            }, glHandler)
        }
        cameraInputSurface = Surface(surfaceTexture)
        isInitialized.set(true)
    }

    private fun onCameraFrameAvailable(st: SurfaceTexture) {
        if (isReleased.get() || !isInitialized.get()) return
        try {
            makeContextCurrent(
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) previewEglSurface else fallbackPbufferSurface
            )
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val timestampNs = st.timestamp
            frameCounter++

            // 1. Render to live Preview Window Surface (with GPU overlays enabled)
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                makeContextCurrent(previewEglSurface)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, previewWidth, previewHeight)
                drawQuadLocked(mvpMatrix, enableOverlays = true)
                EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)
            }

            // 2. Render clean graded frame to MediaCodec Video Encoder Surface when recording
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                makeContextCurrent(encoderEglSurface)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, encoderWidth, encoderHeight)
                drawQuadLocked(encoderMvpMatrix, enableOverlays = false)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, timestampNs)
                EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
            }

            // 3. Throttled real pixel readback (~10 Hz) into offscreen FBO for ProWaveformView
            val now = System.nanoTime()
            if (configRef.get().telemetryEnabled && now - lastTelemetryNs >= TELEMETRY_INTERVAL_NS) {
                lastTelemetryNs = now
                computeRealTelemetryLocked()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame render error: ${t.message}")
        }
    }

    private fun drawQuadLocked(matrix: FloatArray, enableOverlays: Boolean) {
        val cfg = configRef.get()
        GLES30.glUseProgram(programId)

        GLES30.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, matrix, 0)
        GLES30.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTextureId)
        GLES30.glUniform1i(uCameraTextureLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
        GLES30.glUniform1i(uLutTextureLoc, 1)

        GLES30.glUniform1i(uLogProfileIdLoc, cfg.logProfile.shaderId)
        GLES30.glUniform1f(uLutIntensityLoc, cfg.lutIntensity)
        GLES30.glUniform1i(uLutEnabledLoc, if (cfg.lutEnabled) 1 else 0)
        GLES30.glUniform1f(uLutSizeLoc, currentLutSize.toFloat())

        GLES30.glUniform1i(uEnableOverlaysLoc, if (enableOverlays) 1 else 0)
        GLES30.glUniform1i(uSplitScreenLoc, if (cfg.splitScreen) 1 else 0)
        GLES30.glUniform1f(uSplitPositionLoc, cfg.splitPosition)
        GLES30.glUniform1i(uFalseColorLoc, if (cfg.falseColor) 1 else 0)
        GLES30.glUniform1i(uZebraEnabledLoc, if (cfg.zebraEnabled) 1 else 0)
        GLES30.glUniform1f(uZebraThresholdLoc, cfg.zebraThreshold)
        val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).toFloat()
        GLES30.glUniform1f(uTimeSecondsLoc, elapsedSec)
        GLES30.glUniform1i(uFocusPeakingLoc, if (cfg.focusPeaking) 1 else 0)
        GLES30.glUniform1f(uFocusPeakingThresholdLoc, cfg.focusPeakingThreshold)
        GLES30.glUniform3f(
            uFocusPeakingColorLoc,
            cfg.focusPeakingColor.r,
            cfg.focusPeakingColor.g,
            cfg.focusPeakingColor.b
        )
        GLES30.glUniform2f(
            uTexelSizeLoc,
            1f / max(1, cameraBufferWidth).toFloat(),
            1f / max(1, cameraBufferHeight).toFloat()
        )

        quadVertices.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun computeRealTelemetryLocked() {
        makeContextCurrent(fallbackPbufferSurface)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scopeFboId)
        GLES30.glViewport(0, 0, SCOPE_W, SCOPE_H)
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        drawQuadLocked(identity, enableOverlays = false)

        scopeBuffer.rewind()
        GLES30.glReadPixels(0, 0, SCOPE_W, SCOPE_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, scopeBuffer)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val lumaHist = FloatArray(64)
        val redHist = FloatArray(64)
        val greenHist = FloatArray(64)
        val blueHist = FloatArray(64)

        val wfCols = LiveScopeTelemetry.WAVEFORM_COLS
        val paradeCols = LiveScopeTelemetry.PARADE_COLS
        val wfRows = LiveScopeTelemetry.WAVEFORM_ROWS
        val lumaWf = FloatArray(wfCols * wfRows)
        val redWf = FloatArray(paradeCols * wfRows)
        val greenWf = FloatArray(paradeCols * wfRows)
        val blueWf = FloatArray(paradeCols * wfRows)

        val totalPixels = SCOPE_W * SCOPE_H
        var sumIre = 0f
        var minIre = 100f
        var maxIre = 0f
        var clippedCount = 0
        var crushedCount = 0

        scopeBuffer.rewind()
        for (y in 0 until SCOPE_H) {
            for (x in 0 until SCOPE_W) {
                val r = scopeBuffer.get().toInt() and 0xFF
                val g = scopeBuffer.get().toInt() and 0xFF
                val b = scopeBuffer.get().toInt() and 0xFF
                scopeBuffer.get() // alpha

                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 255f)
                val ire = (luma / 255f) * 100f
                sumIre += ire
                if (ire < minIre) minIre = ire
                if (ire > maxIre) maxIre = ire
                if (ire >= 95f) clippedCount++
                if (ire <= 5f) crushedCount++

                lumaHist[(luma / 4f).toInt().coerceIn(0, 63)] += 1f
                redHist[(r / 4).coerceIn(0, 63)] += 1f
                greenHist[(g / 4).coerceIn(0, 63)] += 1f
                blueHist[(b / 4).coerceIn(0, 63)] += 1f

                val wfX = (x * wfCols / SCOPE_W).coerceIn(0, wfCols - 1)
                val wfY = ((luma / 255f) * (wfRows - 1)).toInt().coerceIn(0, wfRows - 1)
                lumaWf[wfX * wfRows + wfY] += 1f

                val pX = (x * paradeCols / SCOPE_W).coerceIn(0, paradeCols - 1)
                val rY = ((r / 255f) * (wfRows - 1)).toInt().coerceIn(0, wfRows - 1)
                val gY = ((g / 255f) * (wfRows - 1)).toInt().coerceIn(0, wfRows - 1)
                val bY = ((b / 255f) * (wfRows - 1)).toInt().coerceIn(0, wfRows - 1)
                redWf[pX * wfRows + rY] += 1f
                greenWf[pX * wfRows + gY] += 1f
                blueWf[pX * wfRows + bY] += 1f
            }
        }

        normalizeArray(lumaHist)
        normalizeArray(redHist)
        normalizeArray(greenHist)
        normalizeArray(blueHist)
        normalizeWaveform(lumaWf, 5f)
        normalizeWaveform(redWf, 5f)
        normalizeWaveform(greenWf, 5f)
        normalizeWaveform(blueWf, 5f)

        onTelemetryUpdate(
            LiveScopeTelemetry(
                lumaHistogram = lumaHist,
                redHistogram = redHist,
                greenHistogram = greenHist,
                blueHistogram = blueHist,
                lumaWaveform = lumaWf,
                redWaveform = redWf,
                greenWaveform = greenWf,
                blueWaveform = blueWf,
                averageIre = sumIre / totalPixels,
                minIre = minIre,
                maxIre = maxIre,
                clippedHighlightPct = (clippedCount * 100f) / totalPixels,
                crushedShadowPct = (crushedCount * 100f) / totalPixels,
                frameCount = frameCounter
            )
        )
    }

    private fun normalizeArray(arr: FloatArray) {
        var peak = 1f
        for (v in arr) if (v > peak) peak = v
        for (i in arr.indices) arr[i] = (arr[i] / peak).coerceIn(0f, 1f)
    }

    private fun normalizeWaveform(arr: FloatArray, gainDivisor: Float) {
        for (i in arr.indices) {
            arr[i] = min(1f, arr[i] / gainDivisor)
        }
    }

    private fun setupScopeFboLocked() {
        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        GLES30.glGenTextures(1, texs, 0)
        scopeFboId = fbos[0]
        scopeTexId = texs[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scopeTexId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
            SCOPE_W, SCOPE_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scopeFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, scopeTexId, 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun uploadIdentityLutLocked() {
        val identityBytes = byteArrayOf(
            0, 0, 0,       -1, 0, 0,
            0, -1, 0,      -1, -1, 0,
            0, 0, -1,      -1, 0, -1,
            0, -1, -1,     -1, -1, -1
        )
        uploadRaw3DTextureLocked(2, identityBytes)
    }

    private fun uploadLutTextureLocked(lut: Lut3DData) {
        uploadRaw3DTextureLocked(lut.size, lut.toRgbByteArray())
    }

    private fun uploadRaw3DTextureLocked(size: Int, rgbBytes: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(rgbBytes.size).order(ByteOrder.nativeOrder())
        buffer.put(rgbBytes)
        buffer.position(0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture3DId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB8,
            size,
            size,
            size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
        currentLutSize = size
    }

    private fun updateMvpMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0)
        // Center-crop preview cleanly across both portrait and landscape orientations
        val viewRatio = previewWidth.toFloat() / max(1, previewHeight).toFloat()
        val isPortrait = previewHeight > previewWidth
        val camW = if (isPortrait) min(cameraBufferWidth, cameraBufferHeight) else max(cameraBufferWidth, cameraBufferHeight)
        val camH = if (isPortrait) max(cameraBufferWidth, cameraBufferHeight) else min(cameraBufferWidth, cameraBufferHeight)
        val camRatio = camW.toFloat() / max(1, camH).toFloat()

        if (camRatio > viewRatio) {
            Matrix.scaleM(mvpMatrix, 0, camRatio / viewRatio, 1f, 1f)
        } else {
            Matrix.scaleM(mvpMatrix, 0, 1f, viewRatio / camRatio, 1f)
        }
    }

    private fun makeContextCurrent(surface: EGLSurface) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
        }
    }

    private fun destroyPreviewSurfaceLocked() {
        if (previewEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeContextCurrent(fallbackPbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyEncoderSurfaceLocked() {
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeContextCurrent(fallbackPbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun cacheUniformLocations() {
        uMvpMatrixLoc = GLES30.glGetUniformLocation(programId, "uMvpMatrix")
        uTexMatrixLoc = GLES30.glGetUniformLocation(programId, "uTexMatrix")
        uCameraTextureLoc = GLES30.glGetUniformLocation(programId, "uCameraTexture")
        uLutTextureLoc = GLES30.glGetUniformLocation(programId, "uLutTexture")
        uLogProfileIdLoc = GLES30.glGetUniformLocation(programId, "uLogProfileId")
        uLutIntensityLoc = GLES30.glGetUniformLocation(programId, "uLutIntensity")
        uLutEnabledLoc = GLES30.glGetUniformLocation(programId, "uLutEnabled")
        uLutSizeLoc = GLES30.glGetUniformLocation(programId, "uLutSize")
        uEnableOverlaysLoc = GLES30.glGetUniformLocation(programId, "uEnableOverlays")
        uSplitScreenLoc = GLES30.glGetUniformLocation(programId, "uSplitScreen")
        uSplitPositionLoc = GLES30.glGetUniformLocation(programId, "uSplitPosition")
        uFalseColorLoc = GLES30.glGetUniformLocation(programId, "uFalseColor")
        uZebraEnabledLoc = GLES30.glGetUniformLocation(programId, "uZebraEnabled")
        uZebraThresholdLoc = GLES30.glGetUniformLocation(programId, "uZebraThreshold")
        uTimeSecondsLoc = GLES30.glGetUniformLocation(programId, "uTimeSeconds")
        uFocusPeakingLoc = GLES30.glGetUniformLocation(programId, "uFocusPeaking")
        uFocusPeakingThresholdLoc = GLES30.glGetUniformLocation(programId, "uFocusPeakingThreshold")
        uFocusPeakingColorLoc = GLES30.glGetUniformLocation(programId, "uFocusPeakingColor")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vs == 0 || fs == 0) return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error ($type): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun readAssetText(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val TAG = "GLLutLogRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val SCOPE_W = 96
        private const val SCOPE_H = 54
        private const val TELEMETRY_INTERVAL_NS = 100_000_000L // ~10 Hz
    }
}
