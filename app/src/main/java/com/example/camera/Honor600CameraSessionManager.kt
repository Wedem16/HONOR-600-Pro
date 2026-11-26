package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.gl.GLLutLogRenderer
import java.util.concurrent.Executors
import kotlin.math.pow

enum class FlashMode(val label: String) {
    OFF("OFF"),
    AUTO("AUTO"),
    TORCH("TORCH")
}

data class ManualControlState(
    val exposureCompSteps: Int = 0,
    val isoManualEnabled: Boolean = false,
    val isoValue: Int = 200,
    val shutterManualEnabled: Boolean = false,
    val shutterTimeNs: Long = 16_666_666L, // 1/60s
    val focusManualEnabled: Boolean = false,
    val focusDistanceDiopters: Float = 0f, // 0 = infinity
    val wbManualEnabled: Boolean = false,
    val wbKelvin: Int = 5500,
    val flashMode: FlashMode = FlashMode.OFF,
    val stabilizationEnabled: Boolean = true,
    val targetFps: Int = 30,
    val zoomRatio: Float = 1.0f
)

data class LiveCameraMetering(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val afState: Int? = null
)

/**
 * Manages real Camera2 device lifecycle, repeating preview sessions into [GLLutLogRenderer],
 * manual sensor/lens/AWB controls, JPEG still capture (both high-res sensor JPEG and GPU-graded
 * Log/LUT JPEG), and synchronized 24/30/60 FPS MediaCodec video recording sessions.
 */
class Honor600CameraSessionManager(
    private val context: Context,
    private val renderer: GLLutLogRenderer,
    private val videoController: HighSpeed60FpsController,
    private val mediaStoreRepository: MediaStoreRepository,
    private val onMeteringUpdated: (LiveCameraMetering) -> Unit,
    private val onError: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var stillImageReader: ImageReader? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var activeCameraId: String? = null

    private var controlState = ManualControlState()
    private var previewSize = Size(1920, 1080)
    private var photoSize = Size(1920, 1080)
    private var pendingPhotoCallback: ((SavedMediaResult?) -> Unit)? = null
    private var pendingPhotoModeTag: String = "PHOTO"

    fun startThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("Honor600Camera2Thread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, desiredPreviewSize: Size, initialControls: ManualControlState) {
        startThread()
        controlState = initialControls
        cameraHandler?.post {
            closeCameraInternal()
            activeCameraId = cameraId
            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                currentCharacteristics = chars
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val availPreview = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
                previewSize = chooseBestSize(availPreview, desiredPreviewSize.width, desiredPreviewSize.height)
                val availPhoto = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                photoSize = availPhoto.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: previewSize

                renderer.setCameraBufferSize(previewSize.width, previewSize.height)

                stillImageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val saved = runCatching {
                                mediaStoreRepository.saveJpegPhoto(
                                    jpegBytes = bytes,
                                    width = image.width,
                                    height = image.height,
                                    modeTag = pendingPhotoModeTag
                                )
                            }.getOrNull()
                            pendingPhotoCallback?.invoke(saved)
                            pendingPhotoCallback = null
                        } finally {
                            image.close()
                        }
                    }, cameraHandler)
                }

                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            createCaptureSessionInternal()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (cameraDevice == camera) cameraDevice = null
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (cameraDevice == camera) cameraDevice = null
                            onError("Camera2 hardware error ($error) on sensor $cameraId")
                        }
                    },
                    cameraHandler
                )
            } catch (se: SecurityException) {
                onError("Camera permission not granted.")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open camera $cameraId", t)
                onError("Unable to open camera $cameraId: ${t.message}")
            }
        }
    }

    fun updateControls(newControls: ManualControlState) {
        controlState = newControls
        cameraHandler?.post {
            applyRepeatingRequestInternal()
        }
    }

    /**
     * Captures a still photo. When [bakeColorGrade] is true (a Log profile or 3D LUT is active),
     * captures the OpenGL ES 3.0 graded frame cleanly; otherwise triggers full-resolution Camera2 JPEG capture.
     */
    fun capturePhoto(
        bakeColorGrade: Boolean,
        modeTag: String,
        onComplete: (SavedMediaResult?) -> Unit
    ) {
        if (bakeColorGrade) {
            cameraHandler?.post {
                val jpeg = renderer.captureGradedJpegSnapshot(previewSize.width, previewSize.height)
                val saved = if (jpeg != null) {
                    runCatching {
                        mediaStoreRepository.saveJpegPhoto(
                            jpegBytes = jpeg,
                            width = previewSize.width,
                            height = previewSize.height,
                            modeTag = modeTag
                        )
                    }.getOrNull()
                } else null
                onComplete(saved)
            }
            return
        }

        cameraHandler?.post {
            val device = cameraDevice
            val session = captureSession
            val readerSurface = stillImageReader?.surface
            if (device == null || session == null || readerSurface == null) {
                onComplete(null)
                return@post
            }
            try {
                pendingPhotoModeTag = modeTag
                pendingPhotoCallback = onComplete
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(readerSurface)
                applyManualParameters(builder, isStillCapture = true)
                val orientation = currentCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                session.capture(builder.build(), null, cameraHandler)
            } catch (t: Throwable) {
                Log.e(TAG, "Still capture failed", t)
                pendingPhotoCallback = null
                onComplete(null)
            }
        }
    }

    fun startVideoRecording(
        config: ActiveRecordingConfig,
        modeTag: String,
        onStarted: (Boolean, String?) -> Unit
    ) {
        cameraHandler?.post {
            try {
                val encoderSurface = videoController.startRecording(config, mediaStoreRepository, modeTag)
                renderer.setRecordingSurface(encoderSurface, config.size.width, config.size.height)
                onStarted(true, null)
            } catch (t: Throwable) {
                Log.e(TAG, "Video recording start failed", t)
                renderer.setRecordingSurface(null, 1, 1)
                onStarted(false, t.message ?: "Failed to start video encoder")
            }
        }
    }

    fun stopVideoRecording(onStopped: (SavedMediaResult?) -> Unit) {
        cameraHandler?.post {
            renderer.setRecordingSurface(null, 1, 1)
            val saved = videoController.stopRecording(mediaStoreRepository)
            onStopped(saved)
        }
    }

    fun closeCamera() {
        cameraHandler?.post { closeCameraInternal() }
    }

    fun release() {
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        sessionExecutor.shutdown()
    }

    private fun closeCameraInternal() {
        if (videoController.isRecording) {
            renderer.setRecordingSurface(null, 1, 1)
            videoController.stopRecording(mediaStoreRepository)
        }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { stillImageReader?.close() }
        stillImageReader = null
    }

    private fun createCaptureSessionInternal() {
        val device = cameraDevice ?: return
        val previewSurface = renderer.getCameraInputSurface()
        if (previewSurface == null || !previewSurface.isValid) {
            onError("OpenGL ES 3.0 camera surface is not ready.")
            return
        }
        val targets = mutableListOf<Surface>(previewSurface)
        stillImageReader?.surface?.let { targets.add(it) }

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null) {
                    session.close()
                    return
                }
                captureSession = session
                applyRepeatingRequestInternal()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError("Camera2 session configuration failed for sensor $activeCameraId")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputs = targets.map { OutputConfiguration(it) }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    sessionExecutor,
                    stateCallback
                )
                device.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(targets, stateCallback, cameraHandler)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createCaptureSession failed", t)
            onError("Failed to create camera session: ${t.message}")
        }
    }

    private fun applyRepeatingRequestInternal() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val previewSurface = renderer.getCameraInputSurface() ?: return
        if (!previewSurface.isValid) return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(previewSurface)
            applyManualParameters(builder, isStillCapture = false)
            session.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    private var frameTick = 0
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        frameTick++
                        if (frameTick % 10 == 0) {
                            onMeteringUpdated(
                                LiveCameraMetering(
                                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                                    exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                    focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                                    afState = result.get(CaptureResult.CONTROL_AF_STATE)
                                )
                            )
                        }
                    }
                },
                cameraHandler
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Repeating request failed: ${t.message}")
        }
    }

    private fun applyManualParameters(builder: CaptureRequest.Builder, isStillCapture: Boolean) {
        val chars = currentCharacteristics ?: return
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val supportsManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val supportsManualPost = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Frame rate selection (24 / 30 / 60 FPS strictly clamped to real supported ranges)
        val availFps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val bestRange = videoController.selectBestFpsRange(availFps, controlState.targetFps)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)

        // Exposure: Manual ISO/Shutter when supported and enabled, else Auto with EV compensation
        val manualExposureRequested = (controlState.isoManualEnabled || controlState.shutterManualEnabled) && supportsManualSensor
        if (manualExposureRequested) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, controlState.isoValue.coerceIn(range.lower, range.upper))
            }
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
                val maxForFps = (1_000_000_000L / controlState.targetFps.coerceAtLeast(1)).coerceAtMost(range.upper)
                val desired = controlState.shutterTimeNs.coerceIn(range.lower, maxForFps)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, desired)
            }
        } else {
            val aeMode = when {
                isStillCapture && controlState.flashMode == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                controlState.exposureCompSteps.coerceIn(evRange.lower, evRange.upper)
            )
        }

        // Flash / Torch
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (hasFlash) {
            val flashVal = when (controlState.flashMode) {
                FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                FlashMode.AUTO -> if (isStillCapture) CameraMetadata.FLASH_MODE_SINGLE else CameraMetadata.FLASH_MODE_OFF
                FlashMode.OFF -> CameraMetadata.FLASH_MODE_OFF
            }
            builder.set(CaptureRequest.FLASH_MODE, flashVal)
        }

        // Focus: Manual Diopters when supported, else Continuous Video/Picture AF
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (controlState.focusManualEnabled && minFocus > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, controlState.focusDistanceDiopters.coerceIn(0f, minFocus))
        } else {
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
            val afMode = when {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                else -> CameraMetadata.CONTROL_AF_MODE_AUTO
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        }

        // White Balance: Manual Kelvin via RGGB gains when supported, else Auto WB
        if (controlState.wbManualEnabled && supportsManualPost) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggbGains(controlState.wbKelvin))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        // Video Stabilization (OIS & EIS when supported by device)
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty()
        val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
        if (controlState.stabilizationEnabled) {
            if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        } else {
            if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF in oisModes) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF in eisModes) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
        }

        // Digital Zoom (Control Zoom Ratio on API 30+, Scaler Crop fallback on API 26-29)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val safeZoom = controlState.zoomRatio.coerceIn(1f, maxZoom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeZoom)
        } else if (safeZoom > 1.01f) {
            chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { activeRect ->
                val cropW = (activeRect.width() / safeZoom).toInt()
                val cropH = (activeRect.height() / safeZoom).toInt()
                val left = (activeRect.width() - cropW) / 2
                val top = (activeRect.height() - cropH) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
            }
        }
    }

    private fun chooseBestSize(sizes: List<Size>, targetW: Int, targetH: Int): Size {
        if (sizes.isEmpty()) return Size(1920, 1080)
        // Prefer 16:9 size closest to target (capped at 1920x1080 for smooth 60 FPS GPU preview throughput)
        val cappedW = targetW.coerceAtMost(1920)
        val cappedH = targetH.coerceAtMost(1080)
        sizes.firstOrNull { it.width == cappedW && it.height == cappedH }?.let { return it }
        return sizes.minByOrNull {
            kotlin.math.abs(it.width - cappedW) + kotlin.math.abs(it.height - cappedH)
        } ?: sizes.first()
    }

    /**
     * Converts color temperature (2500K–8500K) into calibrated Camera2 RGGB channel gains.
     */
    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = kelvin.coerceIn(2000, 10000) / 100.0
        val red = if (temp <= 66.0) {
            255.0
        } else {
            (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }
        val green = if (temp <= 66.0) {
            (99.4708025861 * kotlin.math.ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }
        val blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> (138.5177312231 * kotlin.math.ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }
        // Invert illuminant color to compute white-balance gain factors normalized to green = 1.0
        val gNorm = (green / 255.0).coerceAtLeast(0.2)
        val rGain = (gNorm / (red / 255.0).coerceAtLeast(0.2)).toFloat().coerceIn(0.6f, 2.8f)
        val bGain = (gNorm / (blue / 255.0).coerceAtLeast(0.2)).toFloat().coerceIn(0.6f, 2.8f)
        return RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
    }

    companion object {
        private const val TAG = "Honor600SessionMgr"
    }
}
