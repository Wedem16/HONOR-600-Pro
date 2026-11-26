package com.example.monitoring

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.color.LogProfile
import com.example.color.LogProfileEngine
import com.example.gl.LiveScopeTelemetry
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PhosphorCyan
import com.example.ui.theme.PhosphorGreen
import com.example.ui.theme.ScopeBlue
import com.example.ui.theme.ScopeRed
import com.example.ui.theme.TallyRed
import java.util.Locale

enum class ScopeMode(val shortLabel: String) {
    OFF("OFF"),
    HISTOGRAM("HIST"),
    LUMA_WAVEFORM("LUMA"),
    RGB_PARADE("RGB"),
    LOG_CURVE("CURVE")
}

/**
 * Professional real-time cinema monitoring panel driven exclusively by live OpenGL ES 3.0 camera
 * pixel readbacks ([LiveScopeTelemetry]) and exact [LogProfileEngine] transfer curves.
 */
@Composable
fun ProWaveformView(
    telemetry: LiveScopeTelemetry,
    scopeMode: ScopeMode,
    activeLogProfile: LogProfile,
    onCycleScopeMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (scopeMode == ScopeMode.OFF) return

    Column(
        modifier = modifier
            .width(196.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianVoid.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .clickable { onCycleScopeMode() }
            .padding(8.dp)
            .testTag("pro_waveform_view")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (scopeMode) {
                    ScopeMode.HISTOGRAM -> "RGB HISTOGRAM"
                    ScopeMode.LUMA_WAVEFORM -> "LUMA WAVEFORM"
                    ScopeMode.RGB_PARADE -> "RGB PARADE"
                    ScopeMode.LOG_CURVE -> "${activeLogProfile.shortCode} CURVE"
                    ScopeMode.OFF -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = CinemaAmber
            )
            Text(
                text = String.format(Locale.US, "%.0f IRE", telemetry.averageIre),
                style = MaterialTheme.typography.labelSmall,
                color = if (telemetry.clippedHighlightPct > 5f) TallyRed else CinemaWhite
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF05070A))
        ) {
            when (scopeMode) {
                ScopeMode.HISTOGRAM -> LiveHistogramCanvas(telemetry, Modifier.fillMaxSize())
                ScopeMode.LUMA_WAVEFORM -> LiveLumaWaveformCanvas(telemetry, Modifier.fillMaxSize())
                ScopeMode.RGB_PARADE -> LiveRgbParadeCanvas(telemetry, Modifier.fillMaxSize())
                ScopeMode.LOG_CURVE -> LiveLogCurveCanvas(activeLogProfile, telemetry, Modifier.fillMaxSize())
                ScopeMode.OFF -> Unit
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "SH %.1f%%", telemetry.crushedShadowPct),
                style = MaterialTheme.typography.labelSmall,
                color = PhosphorCyan
            )
            Text(
                text = String.format(Locale.US, "HI %.1f%%", telemetry.clippedHighlightPct),
                style = MaterialTheme.typography.labelSmall,
                color = if (telemetry.clippedHighlightPct > 2f) TallyRed else CinemaMuted
            )
        }
    }
}

@Composable
private fun LiveHistogramCanvas(telemetry: LiveScopeTelemetry, modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawGraticule()
        drawHistogramChannel(telemetry.redHistogram, ScopeRed.copy(alpha = 0.55f))
        drawHistogramChannel(telemetry.greenHistogram, PhosphorGreen.copy(alpha = 0.55f))
        drawHistogramChannel(telemetry.blueHistogram, ScopeBlue.copy(alpha = 0.55f))
        drawHistogramStroke(telemetry.lumaHistogram, CinemaWhite.copy(alpha = 0.85f))
    }
}

@Composable
private fun LiveLumaWaveformCanvas(telemetry: LiveScopeTelemetry, modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawGraticule()
        val cols = LiveScopeTelemetry.WAVEFORM_COLS
        val rows = LiveScopeTelemetry.WAVEFORM_ROWS
        val cellW = size.width / cols.toFloat()
        val cellH = size.height / rows.toFloat()

        for (x in 0 until cols) {
            val base = x * rows
            for (y in 0 until rows) {
                val intensity = telemetry.lumaWaveform[base + y]
                if (intensity > 0.04f) {
                    val topY = size.height - ((y + 1) * cellH)
                    val color = when {
                        y >= (rows * 0.94f) -> TallyRed.copy(alpha = (intensity * 0.95f).coerceIn(0.2f, 1f))
                        else -> PhosphorGreen.copy(alpha = (intensity * 0.9f).coerceIn(0.15f, 1f))
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(x * cellW, topY),
                        size = Size(cellW + 0.6f, cellH + 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveRgbParadeCanvas(telemetry: LiveScopeTelemetry, modifier: Modifier) {
    Canvas(modifier = modifier) {
        drawGraticule()
        val thirdW = size.width / 3f
        // Vertical dividers between R, G, B parades
        drawLine(Color.White.copy(alpha = 0.14f), Offset(thirdW, 0f), Offset(thirdW, size.height), 1f)
        drawLine(Color.White.copy(alpha = 0.14f), Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), 1f)

        drawParadeChannel(telemetry.redWaveform, ScopeRed, 0f, thirdW)
        drawParadeChannel(telemetry.greenWaveform, PhosphorGreen, thirdW, thirdW)
        drawParadeChannel(telemetry.blueWaveform, ScopeBlue, thirdW * 2f, thirdW)
    }
}

@Composable
private fun LiveLogCurveCanvas(
    profile: LogProfile,
    telemetry: LiveScopeTelemetry,
    modifier: Modifier
) {
    val curvePoints = remember(profile) { LogProfileEngine.sampleCurve(profile, 64) }
    Canvas(modifier = modifier) {
        drawGraticule()
        // Linear diagonal reference
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = Offset(0f, size.height),
            end = Offset(size.width, 0f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
        )

        // Highlight active camera exposure range band (min..max IRE)
        if (telemetry.frameCount > 0L) {
            val avgY = size.height * (1f - (telemetry.averageIre / 100f).coerceIn(0f, 1f))
            drawLine(
                color = PhosphorCyan.copy(alpha = 0.5f),
                start = Offset(0f, avgY),
                end = Offset(size.width, avgY),
                strokeWidth = 1.2f
            )
        }

        // Exact mathematical transfer curve
        val path = Path()
        for (i in curvePoints.indices) {
            val x = (i.toFloat() / (curvePoints.size - 1)) * size.width
            val y = size.height * (1f - curvePoints[i].coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = CinemaAmber, style = Stroke(width = 2.4f))

        // Middle grey 18% marker
        val midIn = 0.18f
        val midOut = LogProfileEngine.evaluateLinearOetf(midIn, profile)
        drawCircle(
            color = PhosphorGreen,
            radius = 3.5f,
            center = Offset(midIn * size.width, size.height * (1f - midOut))
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraticule() {
    val gridColor = Color.White.copy(alpha = 0.09f)
    for (i in 1..3) {
        val y = size.height * (i / 4f)
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
    }
    for (i in 1..3) {
        val x = size.width * (i / 4f)
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramChannel(
    bins: FloatArray,
    color: Color
) {
    if (bins.isEmpty()) return
    val stepX = size.width / (bins.size - 1).coerceAtLeast(1)
    val path = Path().apply {
        moveTo(0f, size.height)
        for (i in bins.indices) {
            lineTo(i * stepX, size.height * (1f - bins[i].coerceIn(0f, 1f)))
        }
        lineTo(size.width, size.height)
        close()
    }
    drawPath(path, color = color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramStroke(
    bins: FloatArray,
    color: Color
) {
    if (bins.isEmpty()) return
    val stepX = size.width / (bins.size - 1).coerceAtLeast(1)
    val path = Path()
    for (i in bins.indices) {
        val x = i * stepX
        val y = size.height * (1f - bins[i].coerceIn(0f, 1f))
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = 1.4f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParadeChannel(
    data: FloatArray,
    color: Color,
    xOffset: Float,
    width: Float
) {
    val cols = LiveScopeTelemetry.PARADE_COLS
    val rows = LiveScopeTelemetry.WAVEFORM_ROWS
    val cellW = width / cols.toFloat()
    val cellH = size.height / rows.toFloat()
    for (x in 0 until cols) {
        val base = x * rows
        for (y in 0 until rows) {
            val v = data[base + y]
            if (v > 0.05f) {
                drawRect(
                    color = color.copy(alpha = (v * 0.9f).coerceIn(0.18f, 1f)),
                    topLeft = Offset(xOffset + x * cellW, size.height - (y + 1) * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f)
                )
            }
        }
    }
}

/**
 * Full-screen viewfinder framing guides: Rule-of-Thirds grid, Action/Title Safe Areas,
 * 50/50 split-screen comparison divider, and manual focus distance assistance readout.
 */
@Composable
fun CinemaViewfinderGuidesOverlay(
    showRuleOfThirds: Boolean,
    showSafeAreas: Boolean,
    showSplitScreen: Boolean,
    splitPosition: Float,
    manualFocusEnabled: Boolean,
    focusDistanceDiopters: Float,
    focusPeakingEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Rule of Thirds
            if (showRuleOfThirds) {
                val guideColor = Color.White.copy(alpha = 0.24f)
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f
                drawLine(guideColor, Offset(thirdW, 0f), Offset(thirdW, size.height), 1f)
                drawLine(guideColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), 1f)
                drawLine(guideColor, Offset(0f, thirdH), Offset(size.width, thirdH), 1f)
                drawLine(guideColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), 1f)

                // Center crosshair reticle
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawLine(CinemaAmber.copy(alpha = 0.6f), Offset(cx - 12f, cy), Offset(cx + 12f, cy), 1.5f)
                drawLine(CinemaAmber.copy(alpha = 0.6f), Offset(cx, cy - 12f), Offset(cx, cy + 12f), 1.5f)
            }

            // Broadcast Action Safe (90%) & Title Safe (80%) guides
            if (showSafeAreas) {
                val actionInsetX = size.width * 0.05f
                val actionInsetY = size.height * 0.05f
                drawRect(
                    color = CinemaAmber.copy(alpha = 0.48f),
                    topLeft = Offset(actionInsetX, actionInsetY),
                    size = Size(size.width - actionInsetX * 2f, size.height - actionInsetY * 2f),
                    style = Stroke(width = 1.4f)
                )
                val titleInsetX = size.width * 0.10f
                val titleInsetY = size.height * 0.10f
                drawRect(
                    color = PhosphorCyan.copy(alpha = 0.42f),
                    topLeft = Offset(titleInsetX, titleInsetY),
                    size = Size(size.width - titleInsetX * 2f, size.height - titleInsetY * 2f),
                    style = Stroke(
                        width = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                )
            }

            // 50/50 Split-Screen divider
            if (showSplitScreen) {
                val sx = size.width * splitPosition.coerceIn(0.1f, 0.9f)
                drawLine(
                    color = CinemaAmber,
                    start = Offset(sx, 0f),
                    end = Offset(sx, size.height),
                    strokeWidth = 2.5f
                )
            }
        }

        if (showSplitScreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ORIGINAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = CinemaWhite,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ObsidianVoid.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
                Text(
                    text = "LOG + LUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = CinemaAmber,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ObsidianVoid.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        if (manualFocusEnabled) {
            val focusText = if (focusDistanceDiopters <= 0.05f) {
                "MF ∞"
            } else {
                val meters = 1.0f / focusDistanceDiopters
                if (meters >= 1.0f) String.format(Locale.US, "MF %.2fm", meters)
                else String.format(Locale.US, "MF %.0fcm", meters * 100f)
            }
            val badge = if (focusPeakingEnabled) "$focusText  •  PEAK ON" else focusText
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = CinemaAmber,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 92.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianVoid.copy(alpha = 0.75f))
                    .border(1.dp, CinemaAmber.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("manual_focus_assist_badge")
            )
        }
    }
}
