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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.camera.ManualControlState
import com.example.ui.ActiveManualParameter
import com.example.ui.CinemaUiState
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.ObsidianContainer
import com.example.ui.theme.ObsidianVoid
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tactile pro-cinema manual control dock for EV, ISO, Shutter Speed, Manual Focus, and Kelvin WB.
 * Gracefully disables controls unsupported by the active camera hardware.
 */
@Composable
fun ManualControlsBar(
    state: CinemaUiState,
    onSelectParam: (ActiveManualParameter) -> Unit,
    onUpdateControls: ((ManualControlState) -> ManualControlState) -> Unit,
    modifier: Modifier = Modifier
) {
    val cam = state.activeCamera
    val controls = state.manualControls

    val evSupported = cam != null && cam.exposureCompRange.lower < cam.exposureCompRange.upper
    val isoSupported = cam?.supportsManualSensor == true && cam.isoRange != null
    val shutterSupported = cam?.supportsManualSensor == true && cam.exposureTimeRangeNs != null
    val focusSupported = cam?.supportsManualFocus == true
    val wbSupported = cam?.supportsManualPostProcessing == true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Expandable precision slider drawer when a parameter is selected
        if (state.activeManualParam != ActiveManualParameter.NONE) {
            ManualSliderCard(
                state = state,
                onUpdateControls = onUpdateControls
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Parameter readout selector strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianVoid.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val evVal = controls.exposureCompSteps * (cam?.exposureCompStep ?: 0.333f)
            ManualParamCell(
                title = "EV",
                value = String.format(Locale.US, "%+.1f", evVal),
                selected = state.activeManualParam == ActiveManualParameter.EV,
                isManual = controls.exposureCompSteps != 0,
                supported = evSupported,
                onClick = { onSelectParam(ActiveManualParameter.EV) },
                testTag = "manual_param_ev"
            )
            val displayIso = if (controls.isoManualEnabled) "${controls.isoValue}" else "A ${state.liveMetering.iso ?: ""}".trim()
            ManualParamCell(
                title = "ISO",
                value = displayIso,
                selected = state.activeManualParam == ActiveManualParameter.ISO,
                isManual = controls.isoManualEnabled,
                supported = isoSupported,
                onClick = { onSelectParam(ActiveManualParameter.ISO) },
                testTag = "manual_param_iso"
            )
            val displayShutter = if (controls.shutterManualEnabled) {
                formatShutterShort(controls.shutterTimeNs)
            } else {
                state.liveMetering.exposureTimeNs?.let { "A ${formatShutterShort(it)}" } ?: "AUTO"
            }
            ManualParamCell(
                title = "S",
                value = displayShutter,
                selected = state.activeManualParam == ActiveManualParameter.SHUTTER,
                isManual = controls.shutterManualEnabled,
                supported = shutterSupported,
                onClick = { onSelectParam(ActiveManualParameter.SHUTTER) },
                testTag = "manual_param_shutter"
            )
            val displayFocus = if (controls.focusManualEnabled) {
                if (controls.focusDistanceDiopters <= 0.05f) "∞" else String.format(Locale.US, "%.1fD", controls.focusDistanceDiopters)
            } else "AF"
            ManualParamCell(
                title = "MF",
                value = displayFocus,
                selected = state.activeManualParam == ActiveManualParameter.FOCUS,
                isManual = controls.focusManualEnabled,
                supported = focusSupported,
                onClick = { onSelectParam(ActiveManualParameter.FOCUS) },
                testTag = "manual_param_focus"
            )
            ManualParamCell(
                title = "WB",
                value = if (controls.wbManualEnabled) "${controls.wbKelvin}K" else "AWB",
                selected = state.activeManualParam == ActiveManualParameter.WB,
                isManual = controls.wbManualEnabled,
                supported = wbSupported,
                onClick = { onSelectParam(ActiveManualParameter.WB) },
                testTag = "manual_param_wb"
            )
        }
    }
}

@Composable
private fun ManualParamCell(
    title: String,
    value: String,
    selected: Boolean,
    isManual: Boolean,
    supported: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) CinemaAmber.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(enabled = supported) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !supported -> CinemaMuted.copy(alpha = 0.45f)
                selected || isManual -> CinemaAmber
                else -> CinemaMuted
            }
        )
        Text(
            text = if (supported) value else "N/A",
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !supported -> CinemaMuted.copy(alpha = 0.45f)
                selected || isManual -> CinemaWhite
                else -> CinemaWhite.copy(alpha = 0.85f)
            }
        )
    }
}

@Composable
private fun ManualSliderCard(
    state: CinemaUiState,
    onUpdateControls: ((ManualControlState) -> ManualControlState) -> Unit
) {
    val cam = state.activeCamera ?: return
    val controls = state.manualControls

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianVoid.copy(alpha = 0.86f))
            .border(1.dp, CinemaAmber.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("manual_slider_card")
    ) {
        when (state.activeManualParam) {
            ActiveManualParameter.EV -> {
                val range = cam.exposureCompRange
                val evVal = controls.exposureCompSteps * cam.exposureCompStep
                SliderHeader(
                    title = "EXPOSURE COMP",
                    readout = String.format(Locale.US, "%+.2f EV", evVal),
                    isAuto = controls.exposureCompSteps == 0,
                    onResetAuto = { onUpdateControls { it.copy(exposureCompSteps = 0) } }
                )
                Slider(
                    value = controls.exposureCompSteps.toFloat(),
                    onValueChange = { v ->
                        onUpdateControls {
                            it.copy(
                                isoManualEnabled = false,
                                shutterManualEnabled = false,
                                exposureCompSteps = v.roundToInt()
                            )
                        }
                    },
                    valueRange = range.lower.toFloat()..range.upper.toFloat().coerceAtLeast(range.lower + 1f),
                    colors = cinemaSliderColors(),
                    modifier = Modifier.testTag("slider_ev")
                )
            }

            ActiveManualParameter.ISO -> {
                val isoRange = cam.isoRange ?: return
                SliderHeader(
                    title = "SENSOR ISO",
                    readout = if (controls.isoManualEnabled) "ISO ${controls.isoValue}" else "AUTO",
                    isAuto = !controls.isoManualEnabled,
                    onResetAuto = { onUpdateControls { it.copy(isoManualEnabled = false) } }
                )
                Slider(
                    value = controls.isoValue.toFloat().coerceIn(isoRange.lower.toFloat(), isoRange.upper.toFloat()),
                    onValueChange = { v ->
                        onUpdateControls { it.copy(isoManualEnabled = true, isoValue = v.roundToInt()) }
                    },
                    valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat().coerceAtLeast(isoRange.lower + 50f),
                    colors = cinemaSliderColors(),
                    modifier = Modifier.testTag("slider_iso")
                )
            }

            ActiveManualParameter.SHUTTER -> {
                val shutters = listOf(
                    41_666_666L, // 1/24
                    33_333_333L, // 1/30
                    20_833_333L, // 1/48
                    16_666_666L, // 1/60
                    10_000_000L, // 1/100
                    8_333_333L,  // 1/120
                    4_000_000L,  // 1/250
                    2_000_000L,  // 1/500
                    1_000_000L,  // 1/1000
                    500_000L,    // 1/2000
                    250_000L     // 1/4000
                )
                val currentIdx = shutters.indices.minByOrNull {
                    kotlin.math.abs(shutters[it] - controls.shutterTimeNs)
                } ?: 3
                SliderHeader(
                    title = "SHUTTER SPEED",
                    readout = if (controls.shutterManualEnabled) formatShutterShort(controls.shutterTimeNs) else "AUTO",
                    isAuto = !controls.shutterManualEnabled,
                    onResetAuto = { onUpdateControls { it.copy(shutterManualEnabled = false) } }
                )
                Slider(
                    value = currentIdx.toFloat(),
                    onValueChange = { idx ->
                        val chosen = shutters[idx.roundToInt().coerceIn(0, shutters.lastIndex)]
                        onUpdateControls { it.copy(shutterManualEnabled = true, shutterTimeNs = chosen) }
                    },
                    valueRange = 0f..shutters.lastIndex.toFloat(),
                    steps = shutters.size - 2,
                    colors = cinemaSliderColors(),
                    modifier = Modifier.testTag("slider_shutter")
                )
            }

            ActiveManualParameter.FOCUS -> {
                val maxDiopters = cam.minFocusDistanceDiopters.coerceAtLeast(1f)
                val readout = if (!controls.focusManualEnabled) {
                    "AUTO FOCUS"
                } else if (controls.focusDistanceDiopters <= 0.05f) {
                    "∞ (INFINITY)"
                } else {
                    String.format(Locale.US, "%.2fm (%.1fD)", 1f / controls.focusDistanceDiopters, controls.focusDistanceDiopters)
                }
                SliderHeader(
                    title = "MANUAL FOCUS",
                    readout = readout,
                    isAuto = !controls.focusManualEnabled,
                    onResetAuto = { onUpdateControls { it.copy(focusManualEnabled = false) } }
                )
                Slider(
                    value = controls.focusDistanceDiopters.coerceIn(0f, maxDiopters),
                    onValueChange = { d ->
                        onUpdateControls { it.copy(focusManualEnabled = true, focusDistanceDiopters = d) }
                    },
                    valueRange = 0f..maxDiopters,
                    colors = cinemaSliderColors(),
                    modifier = Modifier.testTag("slider_focus")
                )
            }

            ActiveManualParameter.WB -> {
                SliderHeader(
                    title = "WHITE BALANCE",
                    readout = if (controls.wbManualEnabled) "${controls.wbKelvin}K" else "AUTO WB",
                    isAuto = !controls.wbManualEnabled,
                    onResetAuto = { onUpdateControls { it.copy(wbManualEnabled = false) } }
                )
                Slider(
                    value = controls.wbKelvin.toFloat().coerceIn(2500f, 8500f),
                    onValueChange = { k ->
                        val snapped = (k / 100f).roundToInt() * 100
                        onUpdateControls { it.copy(wbManualEnabled = true, wbKelvin = snapped) }
                    },
                    valueRange = 2500f..8500f,
                    colors = cinemaSliderColors(),
                    modifier = Modifier.testTag("slider_wb")
                )
            }

            ActiveManualParameter.NONE -> Unit
        }
    }
}

@Composable
private fun SliderHeader(
    title: String,
    readout: String,
    isAuto: Boolean,
    onResetAuto: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = CinemaMuted)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = readout, style = MaterialTheme.typography.labelLarge, color = CinemaAmber)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isAuto) CinemaAmber else ObsidianContainer)
                    .clickable { onResetAuto() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "AUTO",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAuto) ObsidianVoid else CinemaWhite
                )
            }
        }
    }
}

@Composable
private fun cinemaSliderColors() = SliderDefaults.colors(
    thumbColor = CinemaAmber,
    activeTrackColor = CinemaAmber,
    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
)

private fun formatShutterShort(ns: Long): String {
    if (ns <= 0L) return "AUTO"
    return if (ns >= 1_000_000_000L) {
        String.format(Locale.US, "%.1fs", ns / 1_000_000_000.0)
    } else {
        "1/${(1_000_000_000L / ns).coerceAtLeast(1)}"
    }
}
