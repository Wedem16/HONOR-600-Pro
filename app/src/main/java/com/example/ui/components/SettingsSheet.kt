package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.camera.BitrateTier
import com.example.camera.VideoCodecChoice
import com.example.camera.VideoProfileSupport
import com.example.gl.FocusPeakingColor
import com.example.gl.RenderPipelineConfig
import com.example.monitoring.ScopeMode
import com.example.ui.CinemaUiState
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaSilver
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import kotlin.math.roundToInt

@Composable
fun SettingsSheet(
    state: CinemaUiState,
    onSelectResolution: (VideoProfileSupport) -> Unit,
    onSelectFps: (Int) -> Unit,
    onSelectCodec: (VideoCodecChoice) -> Unit,
    onSelectBitrateTier: (BitrateTier) -> Unit,
    onToggleAudio: (Boolean) -> Unit,
    onToggleStabilization: (Boolean) -> Unit,
    onSelectScopeMode: (ScopeMode) -> Unit,
    onUpdateRenderOverlay: ((RenderPipelineConfig) -> RenderPipelineConfig) -> Unit,
    onUpdateGuides: (Boolean, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 540.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(ObsidianSurface.copy(alpha = 0.97f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding()
            .padding(16.dp)
            .testTag("sheet_settings")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Cinema Settings & Monitoring", style = MaterialTheme.typography.titleMedium, color = CinemaWhite)
                Text("Only hardware-verified video modes are selectable", style = MaterialTheme.typography.bodySmall, color = CinemaMuted)
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("close_settings_sheet")) {
                Icon(Icons.Filled.Close, contentDescription = "Close Settings", tint = CinemaSilver)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video Resolution Selector
            SectionLabel("VIDEO RESOLUTION")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.supportedVideoProfiles.forEach { profile ->
                    val selected = profile.tier == state.selectedVideoProfile?.tier
                    SelectablePill(
                        label = profile.tier.shortLabel,
                        selected = selected,
                        enabled = !state.isRecording,
                        onClick = { onSelectResolution(profile) },
                        modifier = Modifier.weight(1f).testTag("settings_res_${profile.tier.id}")
                    )
                }
            }

            // Frame Rate Selector (24 / 30 / 60 FPS with unsupported disabled gracefully)
            SectionLabel("FRAME RATE (24 / 30 / 60 FPS)")
            val supportedFps = state.selectedVideoProfile?.supportedFps.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(24, 30, 60).forEach { fps ->
                    val supported = fps in supportedFps
                    SelectablePill(
                        label = if (supported) "$fps FPS" else "$fps N/A",
                        selected = state.selectedFps == fps,
                        enabled = supported && !state.isRecording,
                        onClick = { onSelectFps(fps) },
                        modifier = Modifier.weight(1f).testTag("settings_fps_$fps")
                    )
                }
            }

            // Codec & Bitrate
            SectionLabel("ENCODER CODEC & BITRATE")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VideoCodecChoice.entries.forEach { codec ->
                    val supported = when (codec) {
                        VideoCodecChoice.HEVC_H265 -> state.selectedVideoProfile?.supportsHevc != false
                        VideoCodecChoice.AVC_H264 -> state.selectedVideoProfile?.supportsAvc != false
                    }
                    SelectablePill(
                        label = codec.label,
                        selected = state.selectedCodec == codec,
                        enabled = supported && !state.isRecording,
                        onClick = { onSelectCodec(codec) },
                        modifier = Modifier.weight(1f).testTag("settings_codec_${codec.shortLabel}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BitrateTier.entries.forEach { tier ->
                    SelectablePill(
                        label = tier.label,
                        selected = state.selectedBitrateTier == tier,
                        enabled = !state.isRecording,
                        onClick = { onSelectBitrateTier(tier) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Hardware Switches
            SettingsToggleRow(
                title = "Record Microphone Audio",
                subtitle = if (state.hasAudioPermission) "48 kHz AAC 192 kbps" else "Grant microphone permission to enable audio",
                checked = state.recordAudio && state.hasAudioPermission,
                enabled = state.hasAudioPermission,
                onCheckedChange = onToggleAudio
            )

            SettingsToggleRow(
                title = "Video Stabilization (OIS / EIS)",
                subtitle = if (state.activeCamera?.supportsStabilization == true) "Hardware stabilization active" else "Not reported by active lens",
                checked = state.manualControls.stabilizationEnabled,
                enabled = state.activeCamera?.supportsStabilization == true,
                onCheckedChange = onToggleStabilization
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // Monitoring Scopes
            SectionLabel("LIVE WAVEFORM & SCOPE MONITOR")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScopeMode.entries.forEach { mode ->
                    SelectablePill(
                        label = mode.shortLabel,
                        selected = state.scopeMode == mode,
                        enabled = true,
                        onClick = { onSelectScopeMode(mode) },
                        modifier = Modifier.weight(1f).testTag("settings_scope_${mode.name}")
                    )
                }
            }

            SettingsToggleRow(
                title = "Zebra Exposure Warnings",
                subtitle = "Threshold: ${(state.renderConfig.zebraThreshold * 100f).roundToInt()} IRE",
                checked = state.renderConfig.zebraEnabled,
                enabled = true,
                onCheckedChange = { on -> onUpdateRenderOverlay { it.copy(zebraEnabled = on) } }
            )
            if (state.renderConfig.zebraEnabled) {
                Slider(
                    value = state.renderConfig.zebraThreshold,
                    onValueChange = { th -> onUpdateRenderOverlay { it.copy(zebraThreshold = th) } },
                    valueRange = 0.70f..0.98f,
                    colors = SliderDefaults.colors(thumbColor = CinemaAmber, activeTrackColor = CinemaAmber)
                )
            }

            SettingsToggleRow(
                title = "Focus Peaking (Sobel GPU Edge)",
                subtitle = "Highlights sharpest focus planes in real time",
                checked = state.renderConfig.focusPeaking,
                enabled = true,
                onCheckedChange = { on -> onUpdateRenderOverlay { it.copy(focusPeaking = on) } }
            )
            if (state.renderConfig.focusPeaking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FocusPeakingColor.entries.forEach { color ->
                        SelectablePill(
                            label = color.label,
                            selected = state.renderConfig.focusPeakingColor == color,
                            enabled = true,
                            onClick = { onUpdateRenderOverlay { it.copy(focusPeakingColor = color) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            SettingsToggleRow(
                title = "False-Color IRE Exposure Map",
                subtitle = "Purple <5 • Green 45 (Mid) • Pink 60 (Skin) • Red >94",
                checked = state.renderConfig.falseColor,
                enabled = true,
                onCheckedChange = { on -> onUpdateRenderOverlay { it.copy(falseColor = on) } }
            )

            SettingsToggleRow(
                title = "50/50 Split-Screen Comparison",
                subtitle = "Left: Unprocessed sensor • Right: Log + 3D LUT",
                checked = state.renderConfig.splitScreen,
                enabled = true,
                onCheckedChange = { on -> onUpdateRenderOverlay { it.copy(splitScreen = on) } }
            )

            SettingsToggleRow(
                title = "Rule-of-Thirds Grid & Reticle",
                subtitle = "Classic cinema framing overlay",
                checked = state.showRuleOfThirds,
                enabled = true,
                onCheckedChange = { on -> onUpdateGuides(on, state.showSafeAreas) }
            )

            SettingsToggleRow(
                title = "Action & Title Safe-Area Guides",
                subtitle = "90% Action Safe & 80% Title Safe broadcast markers",
                checked = state.showSafeAreas,
                enabled = true,
                onCheckedChange = { on -> onUpdateGuides(state.showRuleOfThirds, on) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Text(
                text = "HONOR 600 Pro Cam is an independent fan-made camera application and is not affiliated with or endorsed by HONOR. Media files are saved to Pictures/Honor600ProCam and Movies/Honor600ProCam.",
                style = MaterialTheme.typography.bodySmall,
                color = CinemaMuted
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = CinemaAmber)
}

@Composable
private fun SelectablePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    !enabled -> ObsidianContainer.copy(alpha = 0.45f)
                    selected -> CinemaAmber
                    else -> ObsidianContainer
                }
            )
            .border(
                1.dp,
                if (selected && enabled) CinemaAmber else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> CinemaMuted.copy(alpha = 0.5f)
                selected -> ObsidianVoid
                else -> CinemaWhite
            }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) CinemaWhite else CinemaMuted
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CinemaMuted
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianVoid,
                checkedTrackColor = CinemaAmber
            )
        )
    }
}
