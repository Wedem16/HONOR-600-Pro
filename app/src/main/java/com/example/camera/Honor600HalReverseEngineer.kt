package com.example.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class DetectedLensRole(val label: String, val badge: String) {
    ULTRAWIDE("Ultrawide", "0.6x"),
    MAIN_WIDE("Main Wide", "1.0x"),
    TELEPHOTO("Telephoto", "2x+"),
    MACRO("Macro", "MACRO"),
    FRONT("Front", "FRONT"),
    AUXILIARY("Auxiliary", "AUX")
}

data class CameraSensorCapability(
    val cameraId: String,
    val physicalCameraIds: List<String>,
    val isLogicalMultiCamera: Boolean,
    val lensFacing: Int,
    val lensFacingLabel: String,
    val lensRole: DetectedLensRole,
    val displayZoomLabel: String,
    val hardwareLevel: Int,
    val hardwareLevelLabel: String,
    val sensorPixelArray: Size?,
    val sensorPhysicalSizeMm: SizeF?,
    val focalLengthsMm: List<Float>,
    val equivalent35mmFocalLength: Float?,
    val apertures: List<Float>,
    val minFocusDistanceDiopters: Float,
    val supportsManualFocus: Boolean,
    val isoRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val exposureCompRange: Range<Int>,
    val exposureCompStep: Float,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val supportsFlash: Boolean,
    val availableFpsRanges: List<Range<Int>>,
    val highSpeedVideoSizes: List<Size>,
    val supportedVideoSizes: List<Size>,
    val supportedPhotoSizes: List<Size>,
    val minFrameDurationNs: Long?,
    val supportedOutputFormats: List<String>,
    val opticalStabilizationModes: List<String>,
    val digitalStabilizationModes: List<String>,
    val sensorOrientationDegrees: Int,
    val maxDigitalZoom: Float
) {
    val maxMegapixels: Double
        get() {
            val maxSize = supportedPhotoSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: sensorPixelArray
            return if (maxSize != null) (maxSize.width.toLong() * maxSize.height.toLong()) / 1_000_000.0 else 0.0
        }

    val supports60Fps: Boolean
        get() = availableFpsRanges.any { it.upper >= 60 } || highSpeedVideoSizes.isNotEmpty()

    val supportsStabilization: Boolean
        get() = opticalStabilizationModes.any { it != "OFF" } || digitalStabilizationModes.any { it != "OFF" }
}

data class HalInspectionReport(
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceBrand: String,
    val androidVersion: String,
    val apiLevel: Int,
    val logicalCameras: List<CameraSensorCapability>,
    val physicalSubCameras: Map<String, CameraSensorCapability>,
    val hasUltrawide: Boolean,
    val hasTelephoto: Boolean,
    val hasMacro: Boolean,
    val hasAuxiliary: Boolean,
    val cameraXAccessibleIds: List<String>
) {
    fun formatTextReport(): String = buildString {
        appendLine("====================================================")
        appendLine("HONOR 600 Pro Cam — Hardware HAL Capability Report")
        appendLine("====================================================")
        appendLine("Device: $deviceManufacturer $deviceModel ($deviceBrand)")
        appendLine("OS: Android $androidVersion (API $apiLevel)")
        appendLine("Logical Cameras Exposed by HAL: ${logicalCameras.size}")
        appendLine("CameraX Verified IDs: ${if (cameraXAccessibleIds.isEmpty()) "Pending/Unavailable" else cameraXAccessibleIds.joinToString()}")
        appendLine("Detected Lens Matrix: Ultrawide=$hasUltrawide, Telephoto=$hasTelephoto, Macro=$hasMacro, Auxiliary=$hasAuxiliary")
        appendLine()

        logicalCameras.forEach { cam ->
            appendLine("--- Logical Camera ID: ${cam.cameraId} [${cam.lensRole.label} / ${cam.lensFacingLabel}] ---")
            appendLine("  Hardware Level      : ${cam.hardwareLevelLabel}")
            appendLine("  Physical Sub-IDs    : ${if (cam.physicalCameraIds.isEmpty()) "None" else cam.physicalCameraIds.joinToString()}")
            appendLine("  Sensor Resolution   : ${cam.sensorPixelArray?.let { "${it.width}x${it.height}" } ?: "Unknown"} (~${String.format(Locale.US, "%.1f", cam.maxMegapixels)} MP)")
            appendLine("  Physical Sensor     : ${cam.sensorPhysicalSizeMm?.let { String.format(Locale.US, "%.2f x %.2f mm", it.width, it.height) } ?: "Unknown"}")
            appendLine("  Focal Lengths       : ${cam.focalLengthsMm.joinToString { String.format(Locale.US, "%.2fmm", it) }} (35mm eq: ${cam.equivalent35mmFocalLength?.let { "${it.roundToInt()}mm" } ?: "N/A"})")
            appendLine("  Apertures           : ${cam.apertures.joinToString { String.format(Locale.US, "f/%.2f", it) }}")
            appendLine("  ISO Range           : ${cam.isoRange?.let { "${it.lower} - ${it.upper}" } ?: "Auto Only"}")
            appendLine("  Shutter Range       : ${cam.exposureTimeRangeNs?.let { formatShutterRange(it) } ?: "Auto Only"}")
            appendLine("  Manual Focus        : ${if (cam.supportsManualFocus) "Supported (max ${String.format(Locale.US, "%.2f", cam.minFocusDistanceDiopters)} D)" else "Fixed Focus"}")
            appendLine("  FPS Ranges          : ${cam.availableFpsRanges.joinToString { "[${it.lower}, ${it.upper}]" }}")
            appendLine("  Min Frame Duration  : ${cam.minFrameDurationNs?.let { String.format(Locale.US, "%.2f ms (%.1f FPS max)", it / 1_000_000.0, 1_000_000_000.0 / it) } ?: "N/A"}")
            appendLine("  High-Speed Sizes    : ${if (cam.highSpeedVideoSizes.isEmpty()) "None" else cam.highSpeedVideoSizes.take(6).joinToString { "${it.width}x${it.height}" }}")
            appendLine("  Stabilization (OIS) : ${cam.opticalStabilizationModes.joinToString()}")
            appendLine("  Stabilization (EIS) : ${cam.digitalStabilizationModes.joinToString()}")
            appendLine("  Output Formats      : ${cam.supportedOutputFormats.joinToString()}")
            appendLine()
        }

        if (physicalSubCameras.isNotEmpty()) {
            appendLine("=== Physical Sub-Camera Details ===")
            physicalSubCameras.values.forEach { phys ->
                appendLine("  Physical ID ${phys.cameraId}: ${phys.lensRole.label}, focal=${phys.focalLengthsMm.firstOrNull() ?: 0f}mm, eq35=${phys.equivalent35mmFocalLength?.roundToInt() ?: 0}mm, res=${phys.sensorPixelArray?.let { "${it.width}x${it.height}" } ?: "N/A"}")
            }
        }
        appendLine("Note: Only sensors genuinely exposed by Android Camera2/CameraX HAL are reported.")
    }

    private fun formatShutterRange(range: Range<Long>): String {
        val minSec = if (range.lower > 0) "1/${(1_000_000_000L / range.lower).coerceAtLeast(1)}s" else "0s"
        val maxSec = if (range.upper >= 1_000_000_000L) {
            String.format(Locale.US, "%.1fs", range.upper / 1_000_000_000.0)
        } else {
            "1/${(1_000_000_000L / range.upper.coerceAtLeast(1L)).coerceAtLeast(1)}s"
        }
        return "$minSec to $maxSec"
    }
}

/**
 * Discovers real camera sensors, physical sub-cameras, focal lengths, FPS ranges, output formats,
 * and stabilization capabilities directly through Android Camera2 APIs and CameraX interop.
 * Never fabricates sensors or claims hidden cameras are unlocked unless exposed by the HAL.
 */
class Honor600HalReverseEngineer(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun inspectDevice(cameraProvider: ProcessCameraProvider? = null): HalInspectionReport {
        val logicalList = mutableListOf<CameraSensorCapability>()
        val physicalMap = LinkedHashMap<String, CameraSensorCapability>()

        val cameraXIds = runCatching {
            cameraProvider?.availableCameraInfos?.mapNotNull { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
            }.orEmpty()
        }.getOrDefault(emptyList())

        val rawIds = runCatching { cameraManager.cameraIdList }.getOrDefault(emptyArray())
        val backRearFocalCandidates = mutableListOf<Pair<String, Float>>()

        for (id in rawIds) {
            val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val eq = compute35mmEquivalent(chars)
                if (eq != null) backRearFocalCandidates.add(id to eq)
            }
        }
        val mainRearEq = backRearFocalCandidates
            .minByOrNull { kotlin.math.abs(it.second - 26f) }
            ?.second ?: 26f

        for (id in rawIds) {
            val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val cap = parseCharacteristics(id, chars, mainRearEq)
            logicalList.add(cap)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (physId in cap.physicalCameraIds) {
                    if (!physicalMap.containsKey(physId)) {
                        val physChars = runCatching { cameraManager.getCameraCharacteristics(physId) }.getOrNull()
                        if (physChars != null) {
                            physicalMap[physId] = parseCharacteristics(physId, physChars, mainRearEq)
                        }
                    }
                }
            }
        }

        val allDetected = logicalList + physicalMap.values
        val hasUltrawide = allDetected.any { it.lensRole == DetectedLensRole.ULTRAWIDE }
        val hasTelephoto = allDetected.any { it.lensRole == DetectedLensRole.TELEPHOTO }
        val hasMacro = allDetected.any { it.lensRole == DetectedLensRole.MACRO }
        val hasAuxiliary = allDetected.any { it.lensRole == DetectedLensRole.AUXILIARY }

        return HalInspectionReport(
            deviceManufacturer = Build.MANUFACTURER ?: "Unknown",
            deviceModel = Build.MODEL ?: "Unknown",
            deviceBrand = Build.BRAND ?: "Unknown",
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            apiLevel = Build.VERSION.SDK_INT,
            logicalCameras = logicalList,
            physicalSubCameras = physicalMap,
            hasUltrawide = hasUltrawide,
            hasTelephoto = hasTelephoto,
            hasMacro = hasMacro,
            hasAuxiliary = hasAuxiliary,
            cameraXAccessibleIds = cameraXIds
        )
    }

    private fun parseCharacteristics(
        cameraId: String,
        chars: CameraCharacteristics,
        mainRearEq35: Float
    ): CameraSensorCapability {
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val facingLabel = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_BACK -> "Rear"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val hwLevelLabel = when (hwLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "LEGACY"
        }

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val isLogical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { chars.physicalCameraIds.toList().sorted() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty()
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq35 = compute35mmEquivalent(chars)

        val minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val supportsManualFocus = minFocusDistance > 0f &&
            (chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) == true)

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStepRational = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val evStep = evStepRational?.toFloat() ?: 0.333f

        val supportsManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val supportsManualPost = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities
        val supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        val supportsFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = map?.getOutputSizes(MediaCodec::class.java)?.toList()
            ?.sortedByDescending { it.width.toLong() * it.height.toLong() }.orEmpty()
        val photoSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList()
            ?.sortedByDescending { it.width.toLong() * it.height.toLong() }.orEmpty()
        val highSpeedSizes = runCatching { map?.highSpeedVideoSizes?.toList().orEmpty() }.getOrDefault(emptyList())

        val outputFormats = map?.outputFormats?.map { formatName(it) }?.distinct().orEmpty()
        val minFrameDuration = videoSizes.firstOrNull()?.let { size ->
            runCatching { map?.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrNull()
        }?.takeIf { it > 0L }

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.map { if (it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) "OIS_ON" else "OFF" }
            ?: listOf("OFF")

        val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.map { mode ->
                when (mode) {
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "EIS_ON"
                    2 -> "PREVIEW_STABILIZATION"
                    else -> "OFF"
                }
            } ?: listOf("OFF")

        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val role = classifyLensRole(
            facing = facing,
            eq35 = eq35,
            minFocusDiopters = minFocusDistance,
            pixelArray = pixelArray,
            capabilities = capabilities
        )

        val zoomLabel = when (role) {
            DetectedLensRole.FRONT -> "FRONT"
            DetectedLensRole.MACRO -> "MACRO"
            DetectedLensRole.AUXILIARY -> "AUX"
            else -> {
                if (eq35 != null && mainRearEq35 > 0f) {
                    val ratio = eq35 / mainRearEq35
                    when {
                        ratio < 0.8f -> String.format(Locale.US, "%.1fx", ratio.coerceAtLeast(0.5f))
                        ratio in 0.8f..1.35f -> "1.0x"
                        else -> String.format(Locale.US, "%.1fx", ratio)
                    }
                } else {
                    role.badge
                }
            }
        }

        return CameraSensorCapability(
            cameraId = cameraId,
            physicalCameraIds = physicalIds,
            isLogicalMultiCamera = isLogical,
            lensFacing = facing,
            lensFacingLabel = facingLabel,
            lensRole = role,
            displayZoomLabel = zoomLabel,
            hardwareLevel = hwLevel,
            hardwareLevelLabel = hwLevelLabel,
            sensorPixelArray = pixelArray,
            sensorPhysicalSizeMm = physicalSize,
            focalLengthsMm = focalLengths,
            equivalent35mmFocalLength = eq35,
            apertures = apertures,
            minFocusDistanceDiopters = minFocusDistance,
            supportsManualFocus = supportsManualFocus,
            isoRange = isoRange,
            exposureTimeRangeNs = exposureRange,
            exposureCompRange = evRange,
            exposureCompStep = evStep,
            supportsManualSensor = supportsManualSensor,
            supportsManualPostProcessing = supportsManualPost,
            supportsRaw = supportsRaw,
            supportsFlash = supportsFlash,
            availableFpsRanges = fpsRanges,
            highSpeedVideoSizes = highSpeedSizes,
            supportedVideoSizes = videoSizes,
            supportedPhotoSizes = photoSizes,
            minFrameDurationNs = minFrameDuration,
            supportedOutputFormats = outputFormats,
            opticalStabilizationModes = oisModes,
            digitalStabilizationModes = eisModes,
            sensorOrientationDegrees = orientation,
            maxDigitalZoom = maxZoom
        )
    }

    private fun compute35mmEquivalent(chars: CameraCharacteristics): Float? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (sensor.width <= 0f || sensor.height <= 0f) return null
        val diagMm = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
        if (diagMm <= 0.01f) return null
        // Full-frame 35mm sensor diagonal is 43.2666mm
        return focal * (43.2666f / diagMm)
    }

    private fun classifyLensRole(
        facing: Int,
        eq35: Float?,
        minFocusDiopters: Float,
        pixelArray: Size?,
        capabilities: Set<Int>
    ): DetectedLensRole {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return DetectedLensRole.FRONT
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in capabilities &&
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in capabilities
        ) {
            return DetectedLensRole.AUXILIARY
        }
        val mp = if (pixelArray != null) (pixelArray.width.toLong() * pixelArray.height.toLong()) / 1_000_000.0 else 12.0
        if (mp <= 2.6 && minFocusDiopters >= 15f) {
            return DetectedLensRole.MACRO
        }
        if (eq35 != null) {
            return when {
                eq35 < 20f -> DetectedLensRole.ULTRAWIDE
                eq35 > 45f -> DetectedLensRole.TELEPHOTO
                else -> DetectedLensRole.MAIN_WIDE
            }
        }
        return DetectedLensRole.MAIN_WIDE
    }

    private fun formatName(format: Int): String = when (format) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.DEPTH16 -> "DEPTH16"
        ImageFormat.HEIC -> "HEIC"
        else -> "FMT_$format"
    }
}
