package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.camera.CameraSensorCapability
import com.example.camera.HalInspectionReport
import com.example.camera.VideoProfileSupport
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaSilver
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorCyan
import com.example.ui.theme.PhosphorGreen
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Built-in Camera2 / CameraX HAL Inspector screen displaying real discovered logical and physical
 * sensors, focal lengths, FPS ranges, minimum frame durations, output formats, stabilization modes,
 * and hardware encoder capabilities without inventing unsupported cameras.
 */
@Composable
fun HalInspectorScreen(
    report: HalInspectionReport?,
    activeCameraId: String?,
    videoProfiles: List<VideoProfileSupport>,
    onSelectCamera: (String) -> Unit,
    onCopiedReport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.97f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .testTag("screen_hal_inspector")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = CinemaAmber, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "HAL Sensor & Capability Inspector",
                        style = MaterialTheme.typography.titleMedium,
                        color = CinemaWhite
                    )
                    Text(
                        text = report?.let { "${it.deviceManufacturer} ${it.deviceModel} • Android ${it.androidVersion} (API ${it.apiLevel})" }
                            ?: "Probing Camera2 & CameraX HAL...",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMuted
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("close_hal_inspector")) {
                Icon(Icons.Filled.Close, contentDescription = "Close HAL Inspector", tint = CinemaSilver)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (report == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Inspecting device Camera2 HAL...", color = CinemaSilver)
            }
            return
        }

        // Copy full hardware diagnostic report button
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(report.formatTextReport()))
                onCopiedReport()
            },
            colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber, contentColor = ObsidianVoid),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("button_copy_hal_report")
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copy Full Hardware Capability Report", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Lens Detection Summary
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianSurface)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("DETECTED SENSOR MATRIX (GENUINE HAL ONLY)", style = MaterialTheme.typography.labelSmall, color = CinemaAmber)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CapabilityFlagBadge("ULTRAWIDE", report.hasUltrawide, Modifier.weight(1f))
                        CapabilityFlagBadge("TELEPHOTO", report.hasTelephoto, Modifier.weight(1f))
                        CapabilityFlagBadge("MACRO", report.hasMacro, Modifier.weight(1f))
                        CapabilityFlagBadge("AUX", report.hasAuxiliary, Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Logical IDs: ${report.logicalCameras.size}  •  Physical Sub-IDs: ${report.physicalSubCameras.size}  •  CameraX Verified: ${report.cameraXAccessibleIds.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaSilver
                    )
                }
            }

            // Active Camera 60 FPS Video Encoder Matrix
            if (videoProfiles.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianSurface)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "VERIFIED 60 FPS & ENCODER MATRIX (SENSOR ID ${activeCameraId ?: "-"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = PhosphorCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        videoProfiles.forEach { prof ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${prof.tier.shortLabel} (${prof.actualSize.width}×${prof.actualSize.height})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CinemaWhite
                                )
                                Text(
                                    text = "${prof.supportedFps.joinToString("/") { "${it}p" }} • ${if (prof.supportsHevc) "H.265" else "H.264"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (prof.supports60Fps) PhosphorGreen else CinemaSilver
                                )
                            }
                        }
                    }
                }
            }

            // Logical Cameras Detail Cards
            items(report.logicalCameras, key = { it.cameraId }) { cam ->
                SensorDiagnosticCard(
                    cam = cam,
                    isActive = cam.cameraId == activeCameraId,
                    onActivate = { onSelectCamera(cam.cameraId) }
                )
            }

            // Physical Sub-Cameras section (when exposed by multi-camera HAL)
            if (report.physicalSubCameras.isNotEmpty()) {
                item {
                    Text(
                        text = "EXPOSED PHYSICAL SUB-CAMERAS",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaAmber,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(report.physicalSubCameras.values.toList(), key = { "phys_${it.cameraId}" }) { phys ->
                    SensorDiagnosticCard(
                        cam = phys,
                        isActive = false,
                        isPhysicalSub = true,
                        onActivate = null
                    )
                }
            }
        }
    }
}

@Composable
private fun CapabilityFlagBadge(label: String, supported: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (supported) PhosphorGreen.copy(alpha = 0.16f) else ObsidianElevated)
            .border(
                1.dp,
                if (supported) PhosphorGreen else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp)
            )
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (supported) PhosphorGreen else CinemaMuted
        )
    }
}

@Composable
private fun SensorDiagnosticCard(
    cam: CameraSensorCapability,
    isActive: Boolean,
    isPhysicalSub: Boolean = false,
    onActivate: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) CinemaAmber.copy(alpha = 0.08f) else ObsidianContainer)
            .border(
                1.dp,
                if (isActive) CinemaAmber else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(12.dp)
            )
            .let { if (onActivate != null) it.clickable { onActivate() } else it }
            .padding(12.dp)
            .testTag("hal_sensor_card_${cam.cameraId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${if (isPhysicalSub) "Physical" else "Camera"} ID ${cam.cameraId} — ${cam.lensRole.label}",
                style = MaterialTheme.typography.titleSmall,
                color = if (isActive) CinemaAmber else CinemaWhite
            )
            Text(
                text = cam.hardwareLevelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = PhosphorCyan,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(PhosphorCyan.copy(alpha = 0.16f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(6.dp))

        DiagnosticRow("Lens Facing", "${cam.lensFacingLabel} (${cam.displayZoomLabel})")
        DiagnosticRow("Physical Camera IDs", if (cam.physicalCameraIds.isEmpty()) "None" else cam.physicalCameraIds.joinToString())
        DiagnosticRow(
            "Sensor Resolution",
            cam.sensorPixelArray?.let { "${it.width}×${it.height} (~${String.format(Locale.US, "%.1f", cam.maxMegapixels)} MP)" } ?: "Unknown"
        )
        DiagnosticRow(
            "Focal Length",
            buildString {
                append(cam.focalLengthsMm.joinToString { String.format(Locale.US, "%.2fmm", it) }.ifEmpty { "N/A" })
                cam.equivalent35mmFocalLength?.let { append(" (${it.roundToInt()}mm eq)") }
            }
        )
        DiagnosticRow("Available FPS Ranges", cam.availableFpsRanges.joinToString { "[${it.lower}-${it.upper}]" })
        DiagnosticRow(
            "Min Frame Duration",
            cam.minFrameDurationNs?.let { String.format(Locale.US, "%.2f ms", it / 1_000_000.0) } ?: "N/A"
        )
        DiagnosticRow("Supported Formats", cam.supportedOutputFormats.joinToString())
        DiagnosticRow(
            "Stabilization Modes",
            "OIS: ${cam.opticalStabilizationModes.joinToString()} | EIS: ${cam.digitalStabilizationModes.joinToString()}"
        )
        DiagnosticRow(
            "Manual Sensor",
            "ISO ${cam.isoRange?.let { "${it.lower}-${it.upper}" } ?: "Auto"} • MF: ${if (cam.supportsManualFocus) "Yes" else "Fixed"}"
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = CinemaMuted,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = CinemaWhite,
            modifier = Modifier.weight(0.58f)
        )
    }
}
