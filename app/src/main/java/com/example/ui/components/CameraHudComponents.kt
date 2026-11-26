package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.camera.CameraSensorCapability
import com.example.camera.FlashMode
import com.example.monitoring.ScopeMode
import com.example.ui.ActiveSheet
import com.example.ui.CaptureMode
import com.example.ui.CinemaUiState
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaSilver
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorCyan
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.TallyRed
import java.util.Locale

@Composable
fun CinemaTopTelemetryBar(
    state: CinemaUiState,
    onCycleFlash: () -> Unit,
    onCycleResolution: () -> Unit,
    onCycleFps: () -> Unit,
    onOpenLogSheet: () -> Unit,
    onOpenLutSheet: () -> Unit,
    onOpenHalInspector: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleCleanMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianVoid.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Quick technical pills (RES, FPS, LOG, LUT)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryChip(
                    text = state.selectedVideoProfile?.tier?.shortLabel ?: "1080p",
                    active = true,
                    onClick = onCycleResolution,
                    testTag = "chip_resolution"
                )
                TelemetryChip(
                    text = "${state.selectedFps}FPS",
                    active = state.selectedFps == 60,
                    activeColor = PhosphorGreen,
                    onClick = onCycleFps,
                    testTag = "chip_fps"
                )
                TelemetryChip(
                    text = state.logProfile.shortCode,
                    active = state.logProfile.shaderId != 0,
                    activeColor = CinemaAmber,
                    onClick = onOpenLogSheet,
                    testTag = "chip_log_profile"
                )
                TelemetryChip(
                    text = if (state.selectedLutId == "none") "LUT OFF" else (state.selectedLutPreset?.name?.take(10)?.uppercase(Locale.US) ?: "LUT"),
                    active = state.selectedLutId != "none",
                    activeColor = PhosphorCyan,
                    onClick = onOpenLutSheet,
                    testTag = "chip_lut"
                )
                TelemetryChip(
                    text = String.format(Locale.US, "%s %.0fM", state.selectedCodec.shortLabel, state.effectiveBitrateMbps),
                    active = false,
                    onClick = onOpenSettings,
                    testTag = "chip_codec"
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Right: Flash, HAL Inspector, Clean Mode, Settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onCycleFlash,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("button_flash")
                ) {
                    val icon = when (state.manualControls.flashMode) {
                        FlashMode.OFF -> Icons.Filled.FlashOff
                        FlashMode.AUTO -> Icons.Filled.FlashAuto
                        FlashMode.TORCH -> Icons.Filled.FlashOn
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Flash mode: ${state.manualControls.flashMode.label}",
                        tint = if (state.manualControls.flashMode == FlashMode.OFF) CinemaSilver else CinemaAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onOpenHalInspector,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("button_hal_inspector")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = "Open HAL Sensor Inspector",
                        tint = if (state.activeSheet == ActiveSheet.HAL_INSPECTOR) CinemaAmber else CinemaSilver,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onToggleCleanMode,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("button_clean_mode")
                ) {
                    Icon(
                        imageVector = if (state.cleanCinemaMode) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = "Toggle Clean Cinema Monitoring Mode",
                        tint = if (state.cleanCinemaMode) CinemaAmber else CinemaSilver,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("button_settings")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open Camera Settings",
                        tint = if (state.activeSheet == ActiveSheet.SETTINGS) CinemaAmber else CinemaSilver,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Monitoring Quick-Toggle Strip (Scopes, Zebra, Focus Peaking, False Color, 50/50 Split)
        if (!state.cleanCinemaMode) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonitoringMiniPill(
                    label = "SCOPE:${state.scopeMode.shortLabel}",
                    active = state.scopeMode != ScopeMode.OFF,
                    onClick = { onCycleScopePlaceholder() },
                    visible = false
                )
            }
        }
    }
}

private fun onCycleScopePlaceholder() = Unit

@Composable
fun MonitoringQuickStrip(
    state: CinemaUiState,
    onCycleScope: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleSplitScreen: () -> Unit,
    onToggleSafeGuides: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonitoringMiniPill(
            label = "SCOPE ${state.scopeMode.shortLabel}",
            active = state.scopeMode != ScopeMode.OFF,
            onClick = onCycleScope,
            testTag = "pill_scope"
        )
        MonitoringMiniPill(
            label = "ZEBRA",
            active = state.renderConfig.zebraEnabled,
            onClick = onToggleZebra,
            testTag = "pill_zebra"
        )
        MonitoringMiniPill(
            label = "PEAK",
            active = state.renderConfig.focusPeaking,
            onClick = onToggleFocusPeaking,
            testTag = "pill_peaking"
        )
        MonitoringMiniPill(
            label = "FALSE",
            active = state.renderConfig.falseColor,
            onClick = onToggleFalseColor,
            testTag = "pill_false_color"
        )
        MonitoringMiniPill(
            label = "50/50",
            active = state.renderConfig.splitScreen,
            onClick = onToggleSplitScreen,
            testTag = "pill_split"
        )
        MonitoringMiniPill(
            label = "SAFE",
            active = state.showSafeAreas,
            onClick = onToggleSafeGuides,
            testTag = "pill_safe"
        )
    }
}

@Composable
fun RecordingTallyBadge(
    elapsedSec: Long,
    fps: Int,
    resolutionLabel: String,
    modifier: Modifier = Modifier
) {
    val hrs = elapsedSec / 3600
    val mins = (elapsedSec % 3600) / 60
    val secs = elapsedSec % 60
    val tc = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ObsidianVoid.copy(alpha = 0.85f))
            .border(1.dp, TallyRed, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .testTag("recording_tally_badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(TallyRed)
        )
        Text(
            text = "REC  $tc",
            style = MaterialTheme.typography.labelLarge,
            color = CinemaWhite
        )
        Text(
            text = "$resolutionLabel • ${fps}P",
            style = MaterialTheme.typography.labelSmall,
            color = CinemaAmber
        )
    }
}

@Composable
fun CinemaBottomCaptureDeck(
    state: CinemaUiState,
    rearLenses: List<CameraSensorCapability>,
    onSelectLens: (String) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onShutterClick: () -> Unit,
    onSwitchFrontRear: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianVoid.copy(alpha = 0.82f))
            .padding(top = 8.dp, bottom = 10.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Lens selector row (only genuine detected lenses)
        if (rearLenses.size > 1 && !state.cleanCinemaMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rearLenses.forEach { lens ->
                    val selected = lens.cameraId == state.activeCamera?.cameraId
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) CinemaAmber else ObsidianContainer.copy(alpha = 0.85f))
                            .border(
                                1.dp,
                                if (selected) CinemaAmber else Color.White.copy(alpha = 0.2f),
                                CircleShape
                            )
                            .clickable(enabled = !state.isRecording) { onSelectLens(lens.cameraId) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("lens_pill_${lens.cameraId}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lens.displayZoomLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) ObsidianVoid else CinemaWhite
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Mode switcher: PHOTO | VIDEO | CINEMA
        if (!state.cleanCinemaMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CaptureMode.entries.forEach { mode ->
                    val selected = state.captureMode == mode
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) CinemaAmber else CinemaMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !state.isRecording) { onSelectMode(mode) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("mode_${mode.name.lowercase(Locale.US)}")
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Primary Shutter Row: Gallery | Precision Shutter Button | Front/Rear Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Gallery shortcut button
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianContainer)
                    .border(
                        1.5.dp,
                        if (state.lastSavedMedia != null) CinemaAmber else Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onOpenGallery() }
                    .testTag("button_gallery"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "Open Gallery",
                    tint = if (state.lastSavedMedia != null) CinemaAmber else CinemaWhite,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Large Circular Cinema Shutter / Record Button
            CinemaShutterButton(
                isPhotoMode = state.captureMode == CaptureMode.PHOTO,
                isRecording = state.isRecording,
                onClick = onShutterClick
            )

            // Front / Rear Camera Switch
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(ObsidianContainer)
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    .clickable(enabled = !state.isRecording) { onSwitchFrontRear() }
                    .testTag("button_switch_camera"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = "Switch Front and Rear Camera",
                    tint = if (state.isRecording) CinemaMuted else CinemaWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun CinemaShutterButton(
    isPhotoMode: Boolean,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val innerCorner by animateDpAsState(
        targetValue = if (isRecording) 8.dp else 30.dp,
        animationSpec = spring(),
        label = "shutterCorner"
    )
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 28.dp else 56.dp,
        animationSpec = spring(),
        label = "shutterSize"
    )
    val innerColor by animateColorAsState(
        targetValue = when {
            isPhotoMode -> CinemaWhite
            isRecording -> TallyRed
            else -> TallyRed
        },
        label = "shutterColor"
    )

    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .border(3.5.dp, if (isRecording) TallyRed else CinemaWhite, CircleShape)
            .clickable { onClick() }
            .testTag("shutter_button"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(innerColor)
        )
    }
}

@Composable
private fun TelemetryChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String,
    activeColor: Color = CinemaAmber
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) activeColor.copy(alpha = 0.18f) else ObsidianContainer.copy(alpha = 0.78f))
            .border(
                1.dp,
                if (active) activeColor else Color.White.copy(alpha = 0.16f),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(testTag)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) activeColor else CinemaWhite
        )
    }
}

@Composable
private fun MonitoringMiniPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String = "",
    visible: Boolean = true
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) CinemaAmber.copy(alpha = 0.22f) else ObsidianVoid.copy(alpha = 0.65f))
            .border(
                1.dp,
                if (active) CinemaAmber else Color.White.copy(alpha = 0.15f),
                RoundedCornerShape(5.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .let { if (testTag.isNotEmpty()) it.testTag(testTag) else it }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) CinemaAmber else CinemaSilver
        )
    }
}
