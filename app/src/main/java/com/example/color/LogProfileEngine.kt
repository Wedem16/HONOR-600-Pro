package com.example.color

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Defines real-time cinema Log and HDR transfer profiles for the OpenGL ES 3.0 grading pipeline.
 * Proprietary cinema profiles are explicitly labeled as simulated/inspired mathematical
 * approximations designed to preserve shadow and highlight latitude on mobile camera feeds.
 */
enum class LogProfile(
    val shaderId: Int,
    val id: String,
    val displayName: String,
    val shortCode: String,
    val isSimulated: Boolean,
    val middleGreyIre: Float,
    val targetDynamicRangeStops: String,
    val description: String
) {
    STANDARD_REC709(
        shaderId = 0,
        id = "rec709",
        displayName = "Standard Rec.709",
        shortCode = "REC709",
        isSimulated = false,
        middleGreyIre = 42f,
        targetDynamicRangeStops = "8-9 stops",
        description = "Standard broadcast Rec.709 tone curve with natural display contrast."
    ),
    HLG_HDR(
        shaderId = 1,
        id = "hlg_hdr",
        displayName = "HLG HDR (BT.2100)",
        shortCode = "HLG",
        isSimulated = false,
        middleGreyIre = 50f,
        targetDynamicRangeStops = "11-12 stops",
        description = "ITU-R BT.2100 Hybrid Log-Gamma OETF compatible with SDR and HDR displays."
    ),
    HONOR_LOG(
        shaderId = 2,
        id = "honor_log",
        displayName = "HONOR-Inspired Log",
        shortCode = "H-LOG",
        isSimulated = true,
        middleGreyIre = 40f,
        targetDynamicRangeStops = "11+ stops (sim)",
        description = "Simulated HONOR Magic-Log inspired flat curve with soft highlight knee roll-off."
    ),
    SONY_SLOG3(
        shaderId = 3,
        id = "slog3",
        displayName = "Sony S-Log3-Inspired",
        shortCode = "SLOG3",
        isSimulated = true,
        middleGreyIre = 41f,
        targetDynamicRangeStops = "13+ stops (sim)",
        description = "Simulated cineon-style S-Log3 transfer approximation preserving deep shadows."
    ),
    ARRI_LOGC(
        shaderId = 4,
        id = "arri_logc",
        displayName = "ARRI LogC-Inspired",
        shortCode = "LOGC",
        isSimulated = true,
        middleGreyIre = 39f,
        targetDynamicRangeStops = "14 stops (sim)",
        description = "Simulated piecewise linear-log curve inspired by ARRI LogC filmic latitude."
    ),
    CANON_CLOG3(
        shaderId = 5,
        id = "canon_clog3",
        displayName = "Canon C-Log3-Inspired",
        shortCode = "CLOG3",
        isSimulated = true,
        middleGreyIre = 34f,
        targetDynamicRangeStops = "13 stops (sim)",
        description = "Simulated Canon C-Log3 inspired curve balanced for clean shadows and easy grading."
    ),
    PANASONIC_VLOG(
        shaderId = 6,
        id = "panasonic_vlog",
        displayName = "Panasonic V-Log-Inspired",
        shortCode = "V-LOG",
        isSimulated = true,
        middleGreyIre = 42f,
        targetDynamicRangeStops = "13 stops (sim)",
        description = "Simulated Panasonic V-Log inspired flat transfer curve with wide highlight roll-off."
    ),
    APPLE_LOG(
        shaderId = 7,
        id = "apple_log",
        displayName = "Apple Log-Inspired",
        shortCode = "A-LOG",
        isSimulated = true,
        middleGreyIre = 48f,
        targetDynamicRangeStops = "12+ stops (sim)",
        description = "Simulated Apple Log piecewise quadratic + log2 transfer approximation."
    ),
    FUJIFILM_FLOG2(
        shaderId = 8,
        id = "fujifilm_flog2",
        displayName = "Fujifilm F-Log2-Inspired",
        shortCode = "FLOG2",
        isSimulated = true,
        middleGreyIre = 39f,
        targetDynamicRangeStops = "13+ stops (sim)",
        description = "Simulated Fujifilm F-Log2 inspired wide-latitude curve suited for film-print LUTs."
    ),
    RED_LOG(
        shaderId = 9,
        id = "red_log",
        displayName = "RED Log-Inspired",
        shortCode = "REDLOG",
        isSimulated = true,
        middleGreyIre = 33f,
        targetDynamicRangeStops = "14 stops (sim)",
        description = "Simulated Log3G10-inspired transfer function prioritizing upper highlight retention."
    ),
    BLACKMAGIC_FILM(
        shaderId = 10,
        id = "bmd_film",
        displayName = "Blackmagic Film-Inspired",
        shortCode = "BMD-F",
        isSimulated = true,
        middleGreyIre = 38f,
        targetDynamicRangeStops = "13 stops (sim)",
        description = "Simulated Blackmagic Film Gen5 inspired logarithmic curve with smooth toe."
    );

    companion object {
        fun fromId(id: String?): LogProfile =
            entries.firstOrNull { it.id == id } ?: STANDARD_REC709
    }
}

object LogProfileEngine {

    /**
     * Converts normalized display sRGB value [0..1] to approximate scene linear light [0..1].
     */
    fun srgbToLinear(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }

    /**
     * Evaluates the exact mathematical transfer function matching `cinema_fragment.glsl`.
     * Accepts normalized sRGB input [0..1] and returns encoded normalized output [0..1].
     */
    fun evaluateTransfer(srgbInput: Float, profile: LogProfile): Float {
        val clamped = srgbInput.coerceIn(0f, 1f)
        if (profile == LogProfile.STANDARD_REC709) return clamped
        val lin = srgbToLinear(clamped)
        return evaluateLinearOetf(lin, profile)
    }

    /**
     * Evaluates the profile OETF directly from normalized linear light [0..1].
     */
    fun evaluateLinearOetf(linearInput: Float, profile: LogProfile): Float {
        val x = max(linearInput, 0f).toDouble()
        val result = when (profile) {
            LogProfile.STANDARD_REC709 -> {
                val v = x.coerceIn(0.0, 1.0)
                if (v < 0.018) 4.5 * v else 1.099 * v.pow(0.45) - 0.099
            }
            LogProfile.HLG_HDR -> {
                val v = x.coerceIn(0.0, 1.0)
                val a = 0.17883277
                val b = 0.28466892
                val c = 0.55991073
                if (v <= 1.0 / 12.0) sqrt(3.0 * v) else a * ln(12.0 * v - b) + c
            }
            LogProfile.HONOR_LOG -> {
                val compressed = x / (1.0 + 0.14 * x)
                0.115 + 0.258 * log10(1.0 + 18.5 * compressed)
            }
            LogProfile.SONY_SLOG3 -> {
                if (x >= 0.01125) {
                    (420.0 + (log10(x + 0.01) - log10(0.18 + 0.01)) * 261.5) / 1023.0
                } else {
                    (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0
                }
            }
            LogProfile.ARRI_LOGC -> {
                val cut = 0.010591
                if (x > cut) 0.247190 * log10(5.555556 * x + 0.052272) + 0.385537
                else 5.367655 * x + 0.092809
            }
            LogProfile.CANON_CLOG3 -> {
                if (x < 0.014) 1.9754798 * x + 0.12512219
                else 0.36726845 * log10(14.98325 * x + 1.0) + 0.12783901
            }
            LogProfile.PANASONIC_VLOG -> {
                if (x < 0.01) 5.6 * x + 0.125
                else 0.241514 * log10(x + 0.00873) + 0.598206
            }
            LogProfile.APPLE_LOG -> {
                val r0 = -0.05641088
                if (x < 0.01) {
                    val d = x - r0
                    47.28711236 * d * d
                } else {
                    0.08550479 * log2(x + 0.00964052) + 0.69336945
                }
            }
            LogProfile.FUJIFILM_FLOG2 -> {
                if (x >= 0.000889) 0.245281 * log10(5.555556 * x + 0.064829) + 0.384316
                else 8.799461 * x + 0.092864
            }
            LogProfile.RED_LOG -> {
                0.224282 * log10((x + 0.01) * 155.975327 + 1.0)
            }
            LogProfile.BLACKMAGIC_FILM -> {
                if (x < 0.005) 8.28360593 * x + 0.09246575
                else 0.08692876 * ln(x + 0.00549407) + 0.53001334
            }
        }
        return result.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Samples [samples] points along the transfer curve for live rendering in [ProWaveformView].
     */
    fun sampleCurve(profile: LogProfile, samples: Int = 64): FloatArray {
        val count = samples.coerceAtLeast(2)
        return FloatArray(count) { idx ->
            val input = idx.toFloat() / (count - 1).toFloat()
            evaluateTransfer(input, profile)
        }
    }
}
