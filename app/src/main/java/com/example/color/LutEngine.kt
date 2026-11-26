package com.example.color

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class LutCategory(val label: String) {
    NONE("Bypass"),
    HONOR_STUDIO("HONOR Studio"),
    CINEMA_FILM("Cinema Film"),
    CREATIVE("Creative Grades"),
    CUSTOM("Imported .cube")
}

data class LutPreset(
    val id: String,
    val name: String,
    val category: LutCategory,
    val description: String,
    val accentHex: Long,
    val isBuiltIn: Boolean = true,
    val assetPath: String? = null
)

/**
 * Packed 3D Lookup Table ready for OpenGL ES 3.0 `GL_TEXTURE_3D` upload (RGB unsigned bytes or floats).
 * Indexing order follows Adobe `.cube` specification: R varies fastest, then G, then B.
 */
data class Lut3DData(
    val title: String,
    val size: Int,
    val rgbData: FloatArray // length = size * size * size * 3
) {
    fun toRgbByteArray(): ByteArray {
        val out = ByteArray(rgbData.size)
        for (i in rgbData.indices) {
            out[i] = (rgbData[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /**
     * CPU trilinear lookup useful for verification, unit testing, and still-image preview.
     */
    fun sampleTrilinear(rIn: Float, gIn: Float, bIn: Float): FloatArray {
        val maxIdx = (size - 1).toFloat()
        val rPos = rIn.coerceIn(0f, 1f) * maxIdx
        val gPos = gIn.coerceIn(0f, 1f) * maxIdx
        val bPos = bIn.coerceIn(0f, 1f) * maxIdx

        val r0 = rPos.toInt().coerceIn(0, size - 1)
        val g0 = gPos.toInt().coerceIn(0, size - 1)
        val b0 = bPos.toInt().coerceIn(0, size - 1)
        val r1 = min(r0 + 1, size - 1)
        val g1 = min(g0 + 1, size - 1)
        val b1 = min(b0 + 1, size - 1)

        val fr = rPos - r0
        val fg = gPos - g0
        val fb = bPos - b0

        val out = FloatArray(3)
        for (ch in 0..2) {
            val c000 = at(r0, g0, b0, ch)
            val c100 = at(r1, g0, b0, ch)
            val c010 = at(r0, g1, b0, ch)
            val c110 = at(r1, g1, b0, ch)
            val c001 = at(r0, g0, b1, ch)
            val c101 = at(r1, g0, b1, ch)
            val c011 = at(r0, g1, b1, ch)
            val c111 = at(r1, g1, b1, ch)

            val c00 = c000 + (c100 - c000) * fr
            val c10 = c010 + (c110 - c010) * fr
            val c01 = c001 + (c101 - c001) * fr
            val c11 = c011 + (c111 - c011) * fr

            val c0 = c00 + (c10 - c00) * fg
            val c1 = c01 + (c11 - c01) * fg
            out[ch] = (c0 + (c1 - c0) * fb).coerceIn(0f, 1f)
        }
        return out
    }

    private fun at(r: Int, g: Int, b: Int, ch: Int): Float {
        val index = ((b * size + g) * size + r) * 3 + ch
        return rgbData[index]
    }
}

/**
 * Original 3D LUT generator, Adobe `.cube` parser, and `.cube` exporter.
 * All built-in looks are original mathematical color-science approximations (no copyrighted LUTs).
 */
class LutEngine {

    private val customLutMap = LinkedHashMap<String, Pair<LutPreset, Lut3DData>>()
    private val cache = HashMap<String, Lut3DData>()

    val builtInPresets: List<LutPreset> = listOf(
        LutPreset("none", "No LUT (Bypass)", LutCategory.NONE, "Clean camera or raw Log output.", 0xFF64748B),

        // HONOR Studio Looks
        LutPreset("harcourt_classic_bw", "Harcourt Classic B&W", LutCategory.HONOR_STUDIO, "Sculpted studio monochrome with velvety shadows and luminous skin highlights.", 0xFFD4D4D8, assetPath = "luts/harcourt_classic_bw.cube"),
        LutPreset("harcourt_radiant", "Harcourt Radiant Color", LutCategory.HONOR_STUDIO, "Warm studio key-light glow with refined contrast and soft golden highlights.", 0xFFF59E0B),
        LutPreset("harcourt_vibrant", "Harcourt Vibrant Portrait", LutCategory.HONOR_STUDIO, "Rich editorial portrait color with controlled skin fidelity and punchy micro-contrast.", 0xFFFB7185),
        LutPreset("honor_master_texture", "HONOR Master Texture", LutCategory.HONOR_STUDIO, "Deep tonal clarity with natural balanced saturation and filmic roll-off.", 0xFF38BDF8),

        // Cinema Film Looks
        LutPreset("kodak_2383_print", "Kodak Vision3 2383 Print", LutCategory.CINEMA_FILM, "Original approximation of 2383 theatrical print stock: teal shadows, creamy highlights.", 0xFF0EA5E9, assetPath = "luts/kodak_2383_print.cube"),
        LutPreset("kodak_2383_warm", "Kodak 2383 Warm", LutCategory.CINEMA_FILM, "Warm D55 tungsten-biased print emulation with amber skin tones and deep blacks.", 0xFFF97316),
        LutPreset("fuji_eterna_250d", "Fujifilm Eterna 250D", LutCategory.CINEMA_FILM, "Low-contrast cinema negative feel with soft pastel greens and gentle highlight roll-off.", 0xFF34D399),
        LutPreset("kodak_portra", "Kodak Portra-Inspired", LutCategory.CINEMA_FILM, "Natural warm skin rendering, airy highlights, and softly desaturated greens.", 0xFFFBBF24),
        LutPreset("cinestill_tungsten", "CineStill Tungsten Night", LutCategory.CINEMA_FILM, "Cool tungsten night shadows with warm halation-inspired highlight lift.", 0xFF22D3EE),
        LutPreset("fujichrome_velvia", "Fujichrome Velvia-Inspired", LutCategory.CINEMA_FILM, "Vivid slide-film saturation, crisp contrast, and lush landscape greens and magentas.", 0xFF10B981),
        LutPreset("kodak_trix_bw", "Kodak Tri-X B&W", LutCategory.CINEMA_FILM, "Classic punchy photojournalism monochrome with rich midtone grit.", 0xFFA1A1AA),

        // Creative Grades
        LutPreset("teal_orange", "Teal & Orange", LutCategory.CREATIVE, "Blockbuster complementary grade separating cool cyan shadows from warm amber skin.", 0xFF06B6D4),
        LutPreset("dune_ochre", "Dune Ochre & Sand", LutCategory.CREATIVE, "Desert atmospheric ochre highlights with muted bleached shadows.", 0xFFD97706),
        LutPreset("cyberpunk_neon", "Cyberpunk Neon Night", LutCategory.CREATIVE, "Electric cyan shadows with magenta-violet highlights for night street cinema.", 0xFFE879F9),
        LutPreset("silver_retention", "Silver Retention", LutCategory.CREATIVE, "Bleach-bypass inspired crisp contrast, deep metallic blacks, and low saturation.", 0xFF94A3B8),
        LutPreset("neo_noir_emerald", "Neo-Noir Emerald", LutCategory.CREATIVE, "Moody green-emerald cinema shadows with restrained desaturated highlights.", 0xFF059669),
        LutPreset("film_1970s", "1970s Film", LutCategory.CREATIVE, "Faded nostalgic shadows, warm amber mids, and subtle vintage green cast.", 0xFFCA8A04),
        LutPreset("nordic_pine", "Moody Nordic Pine", LutCategory.CREATIVE, "Desaturated cool slate skies paired with deep pine greens and crisp shadows.", 0xFF2DD4BF)
    )

    fun getAllPresets(): List<LutPreset> = builtInPresets + customLutMap.values.map { it.first }

    fun getPresetById(id: String): LutPreset =
        getAllPresets().firstOrNull { it.id == id } ?: builtInPresets.first()

    /**
     * Loads or generates the 3D LUT data for [presetId] at high 33x33x33 GPU precision.
     */
    fun loadLutData(presetId: String, context: Context? = null, size: Int = DEFAULT_LUT_SIZE): Lut3DData {
        customLutMap[presetId]?.let { return it.second }
        val cacheKey = "${presetId}_$size"
        cache[cacheKey]?.let { return it }

        val preset = getPresetById(presetId)
        val generated = generateBuiltInLut(presetId, preset.name, size)
        cache[cacheKey] = generated
        return generated
    }

    /**
     * Loads a bundled `.cube` LUT file directly from Android `assets/`.
     */
    fun loadBundledCubeAsset(context: Context, assetPath: String, fallbackTitle: String): Lut3DData {
        return context.assets.open(assetPath).use { stream ->
            parseCube(stream, fallbackTitle)
        }
    }

    /**
     * Imports a user `.cube` 3D LUT from an [InputStream] (typically from SAF `OpenDocument`).
     */
    fun importCube(inputStream: InputStream, fallbackName: String): Pair<LutPreset, Lut3DData> {
        val data = parseCube(inputStream, fallbackName)
        val cleanId = "custom_${System.currentTimeMillis()}_${data.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "_")}"
        val preset = LutPreset(
            id = cleanId,
            name = data.title,
            category = LutCategory.CUSTOM,
            description = "Custom imported ${data.size}³ .cube 3D LUT.",
            accentHex = 0xFFF2B643,
            isBuiltIn = false
        )
        customLutMap[cleanId] = preset to data
        return preset to data
    }

    /**
     * Parses standard Adobe `.cube` 3D LUT text streams (supports 17³, 33³, 65³, DOMAIN_MIN/MAX).
     */
    fun parseCube(inputStream: InputStream, fallbackTitle: String = "Custom LUT"): Lut3DData {
        var title = fallbackTitle
        var lutSize = -1
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>(33 * 33 * 33 * 3)

        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    when {
                        trimmed.startsWith("TITLE", ignoreCase = true) -> {
                            title = trimmed.substringAfter("TITLE", "")
                                .trim()
                                .trim('"')
                                .ifBlank { fallbackTitle }
                        }
                        trimmed.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                            lutSize = trimmed.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: -1
                        }
                        trimmed.startsWith("DOMAIN_MIN", ignoreCase = true) -> {
                            val parts = trimmed.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                domainMin = floatArrayOf(
                                    parts[1].toFloatOrNull() ?: 0f,
                                    parts[2].toFloatOrNull() ?: 0f,
                                    parts[3].toFloatOrNull() ?: 0f
                                )
                            }
                        }
                        trimmed.startsWith("DOMAIN_MAX", ignoreCase = true) -> {
                            val parts = trimmed.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                domainMax = floatArrayOf(
                                    parts[1].toFloatOrNull() ?: 1f,
                                    parts[2].toFloatOrNull() ?: 1f,
                                    parts[3].toFloatOrNull() ?: 1f
                                )
                            }
                        }
                        trimmed.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                            throw IllegalArgumentException("1D .cube LUTs are not supported; please select a 3D .cube LUT.")
                        }
                        else -> {
                            val parts = trimmed.split(Regex("\\s+"))
                            if (parts.size >= 3) {
                                val r = parts[0].toFloatOrNull()
                                val g = parts[1].toFloatOrNull()
                                val b = parts[2].toFloatOrNull()
                                if (r != null && g != null && b != null) {
                                    val rn = normalizeDomain(r, domainMin[0], domainMax[0])
                                    val gn = normalizeDomain(g, domainMin[1], domainMax[1])
                                    val bn = normalizeDomain(b, domainMin[2], domainMax[2])
                                    values.add(rn)
                                    values.add(gn)
                                    values.add(bn)
                                }
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
        }

        require(lutSize in 2..65) { "Invalid or missing LUT_3D_SIZE in .cube file." }
        val expectedFloats = lutSize * lutSize * lutSize * 3
        require(values.size >= expectedFloats) {
            "Incomplete .cube file: expected ${expectedFloats / 3} RGB triplets, found ${values.size / 3}."
        }
        val array = FloatArray(expectedFloats) { values[it] }
        return Lut3DData(title = title, size = lutSize, rgbData = array)
    }

    /**
     * Exports a [Lut3DData] instance to standard Adobe `.cube` format.
     */
    fun exportCube(lut: Lut3DData, outputStream: OutputStream) {
        OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
            writer.write("# Generated by HONOR 600 Pro Cam (Original Approximation)\n")
            writer.write("TITLE \"${lut.title.replace("\"", "")}\"\n")
            writer.write("LUT_3D_SIZE ${lut.size}\n")
            writer.write("DOMAIN_MIN 0.0 0.0 0.0\n")
            writer.write("DOMAIN_MAX 1.0 1.0 1.0\n\n")
            val totalTriplets = lut.size * lut.size * lut.size
            for (i in 0 until totalTriplets) {
                val base = i * 3
                writer.write(
                    String.format(
                        Locale.US,
                        "%.6f %.6f %.6f\n",
                        lut.rgbData[base].coerceIn(0f, 1f),
                        lut.rgbData[base + 1].coerceIn(0f, 1f),
                        lut.rgbData[base + 2].coerceIn(0f, 1f)
                    )
                )
            }
            writer.flush()
        }
    }

    fun generateBuiltInLut(presetId: String, title: String, size: Int = DEFAULT_LUT_SIZE): Lut3DData {
        val data = FloatArray(size * size * size * 3)
        val denom = (size - 1).toFloat()
        var idx = 0
        for (b in 0 until size) {
            val bf = b / denom
            for (g in 0 until size) {
                val gf = g / denom
                for (r in 0 until size) {
                    val rf = r / denom
                    val graded = transformColor(presetId, rf, gf, bf)
                    data[idx++] = graded[0].coerceIn(0f, 1f)
                    data[idx++] = graded[1].coerceIn(0f, 1f)
                    data[idx++] = graded[2].coerceIn(0f, 1f)
                }
            }
        }
        return Lut3DData(title = title, size = size, rgbData = data)
    }

    private fun transformColor(presetId: String, rIn: Float, gIn: Float, bIn: Float): FloatArray {
        var r = rIn
        var g = gIn
        var b = bIn

        when (presetId) {
            "none" -> return floatArrayOf(r, g, b)

            // --- HONOR STUDIO LOOKS ---
            "harcourt_classic_bw" -> {
                // Red-filtered studio portrait monochrome with velvety blacks and sculpted mid-highlights
                var luma = 0.34f * r + 0.56f * g + 0.10f * b
                luma = sCurve(luma, 0.38f)
                luma = liftGammaGain(luma, -0.025f, 0.96f, 1.05f)
                // Subtle warm platinum selenium print hint
                return floatArrayOf(luma * 1.01f, luma, luma * 0.985f)
            }
            "harcourt_radiant" -> {
                r = sCurve(r, 0.22f); g = sCurve(g, 0.20f); b = sCurve(b, 0.24f)
                val out = adjustSaturation(r, g, b, 1.08f)
                return splitTone(out[0], out[1], out[2], floatArrayOf(0.02f, 0.01f, -0.02f), floatArrayOf(0.06f, 0.03f, -0.04f))
            }
            "harcourt_vibrant" -> {
                r = sCurve(r, 0.26f); g = sCurve(g, 0.24f); b = sCurve(b, 0.28f)
                val sat = adjustSaturation(r, g, b, 1.24f)
                // Protect skin warmth while deepening cool shadows
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.02f, 0.0f, 0.03f), floatArrayOf(0.04f, 0.015f, -0.01f))
            }
            "honor_master_texture" -> {
                r = sCurve(r, 0.18f); g = sCurve(g, 0.18f); b = sCurve(b, 0.18f)
                val sat = adjustSaturation(r, g, b, 1.06f)
                val lifted = floatArrayOf(
                    liftGammaGain(sat[0], -0.01f, 0.98f, 1.03f),
                    liftGammaGain(sat[1], -0.01f, 0.99f, 1.02f),
                    liftGammaGain(sat[2], 0.0f, 1.01f, 1.01f)
                )
                return lifted
            }

            // --- CINEMA FILM LOOKS ---
            "kodak_2383_print" -> {
                // Classic print stock S-curve with cool cyan shadows and creamy warm highlights
                r = sCurve(r, 0.34f); g = sCurve(g, 0.32f); b = sCurve(b, 0.36f)
                val sat = adjustSaturation(r, g, b, 1.12f)
                val toned = splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.055f, 0.015f, 0.05f), floatArrayOf(0.045f, 0.02f, -0.04f))
                return floatArrayOf(
                    softClip(toned[0], 0.97f),
                    softClip(toned[1], 0.96f),
                    softClip(toned[2], 0.94f)
                )
            }
            "kodak_2383_warm" -> {
                r = sCurve(r, 0.32f); g = sCurve(g, 0.30f); b = sCurve(b, 0.34f)
                val sat = adjustSaturation(r, g, b, 1.10f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.02f, 0.005f, 0.02f), floatArrayOf(0.08f, 0.035f, -0.06f))
            }
            "fuji_eterna_250d" -> {
                // Gentle cinema negative roll-off with lifted shadows and refined cool-emerald tones
                r = liftGammaGain(sCurve(r, 0.10f), 0.025f, 1.02f, 0.96f)
                g = liftGammaGain(sCurve(g, 0.10f), 0.030f, 1.00f, 0.97f)
                b = liftGammaGain(sCurve(b, 0.12f), 0.032f, 1.02f, 0.95f)
                val sat = adjustSaturation(r, g, b, 0.88f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.02f, 0.025f, 0.015f), floatArrayOf(0.02f, 0.01f, -0.015f))
            }
            "kodak_portra" -> {
                r = liftGammaGain(sCurve(r, 0.14f), 0.018f, 0.97f, 1.02f)
                g = liftGammaGain(sCurve(g, 0.14f), 0.016f, 0.99f, 1.00f)
                b = liftGammaGain(sCurve(b, 0.16f), 0.014f, 1.02f, 0.96f)
                val sat = adjustSaturation(r, g, b, 0.96f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(0.01f, 0.015f, -0.01f), floatArrayOf(0.045f, 0.02f, -0.035f))
            }
            "cinestill_tungsten" -> {
                r = sCurve(r, 0.25f); g = sCurve(g, 0.24f); b = sCurve(b, 0.22f)
                val sat = adjustSaturation(r, g, b, 1.14f)
                val toned = splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.07f, 0.01f, 0.09f), floatArrayOf(0.08f, -0.01f, -0.03f))
                // Subtle warm red highlight halation lift on brightest pixels
                val luma = 0.2126f * toned[0] + 0.7152f * toned[1] + 0.0722f * toned[2]
                val halo = ((luma - 0.78f) / 0.22f).coerceIn(0f, 1f)
                return floatArrayOf(toned[0] + halo * 0.08f, toned[1] + halo * 0.01f, toned[2] - halo * 0.02f)
            }
            "fujichrome_velvia" -> {
                r = sCurve(r, 0.34f); g = sCurve(g, 0.34f); b = sCurve(b, 0.36f)
                val sat = adjustSaturation(r, g, b, 1.38f)
                return floatArrayOf(sat[0] * 1.03f, sat[1] * 1.04f, sat[2] * 1.02f)
            }
            "kodak_trix_bw" -> {
                var luma = 0.28f * r + 0.59f * g + 0.13f * b
                luma = sCurve(luma, 0.48f)
                luma = liftGammaGain(luma, 0.015f, 1.02f, 1.04f)
                return floatArrayOf(luma, luma, luma)
            }

            // --- CREATIVE GRADES ---
            "teal_orange" -> {
                r = sCurve(r, 0.28f); g = sCurve(g, 0.26f); b = sCurve(b, 0.30f)
                val sat = adjustSaturation(r, g, b, 1.18f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.10f, 0.035f, 0.085f), floatArrayOf(0.11f, 0.03f, -0.095f))
            }
            "dune_ochre" -> {
                r = liftGammaGain(sCurve(r, 0.22f), 0.02f, 0.94f, 1.05f)
                g = liftGammaGain(sCurve(g, 0.22f), 0.015f, 0.99f, 0.96f)
                b = liftGammaGain(sCurve(b, 0.25f), 0.005f, 1.12f, 0.80f)
                val sat = adjustSaturation(r, g, b, 0.82f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(0.03f, 0.01f, -0.03f), floatArrayOf(0.09f, 0.05f, -0.08f))
            }
            "cyberpunk_neon" -> {
                r = sCurve(r, 0.36f); g = sCurve(g, 0.32f); b = sCurve(b, 0.30f)
                val sat = adjustSaturation(r, g, b, 1.32f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.08f, 0.04f, 0.11f), floatArrayOf(0.10f, -0.04f, 0.11f))
            }
            "silver_retention" -> {
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val contrastLuma = sCurve(luma, 0.45f)
                val desat = adjustSaturation(r, g, b, 0.48f)
                return floatArrayOf(
                    mix(desat[0], contrastLuma, 0.45f) * 0.98f,
                    mix(desat[1], contrastLuma, 0.45f),
                    mix(desat[2], contrastLuma, 0.45f) * 1.03f
                )
            }
            "neo_noir_emerald" -> {
                r = sCurve(r, 0.30f); g = sCurve(g, 0.28f); b = sCurve(b, 0.32f)
                val sat = adjustSaturation(r, g, b, 0.80f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.06f, 0.055f, 0.015f), floatArrayOf(-0.01f, 0.035f, -0.02f))
            }
            "film_1970s" -> {
                r = liftGammaGain(sCurve(r, 0.16f), 0.045f, 0.95f, 0.98f)
                g = liftGammaGain(sCurve(g, 0.16f), 0.040f, 0.98f, 0.96f)
                b = liftGammaGain(sCurve(b, 0.18f), 0.025f, 1.06f, 0.86f)
                val sat = adjustSaturation(r, g, b, 0.86f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(0.01f, 0.03f, -0.015f), floatArrayOf(0.06f, 0.03f, -0.05f))
            }
            "nordic_pine" -> {
                r = sCurve(r, 0.26f); g = sCurve(g, 0.24f); b = sCurve(b, 0.24f)
                val sat = adjustSaturation(r, g, b, 0.74f)
                return splitTone(sat[0], sat[1], sat[2], floatArrayOf(-0.045f, 0.025f, 0.04f), floatArrayOf(-0.02f, 0.01f, 0.03f))
            }
            else -> return floatArrayOf(r, g, b)
        }
    }

    private fun sCurve(x: Float, amount: Float): Float {
        val v = x.coerceIn(0f, 1f)
        val smooth = v * v * (3f - 2f * v)
        return mix(v, smooth, amount)
    }

    private fun liftGammaGain(x: Float, lift: Float, gamma: Float, gain: Float): Float {
        val lifted = (x + lift * (1f - x)).coerceIn(0f, 1f)
        val gammaCorrected = lifted.toDouble().pow(gamma.toDouble()).toFloat()
        return (gammaCorrected * gain).coerceIn(0f, 1f)
    }

    private fun adjustSaturation(r: Float, g: Float, b: Float, sat: Float): FloatArray {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return floatArrayOf(
            (luma + (r - luma) * sat).coerceIn(0f, 1f),
            (luma + (g - luma) * sat).coerceIn(0f, 1f),
            (luma + (b - luma) * sat).coerceIn(0f, 1f)
        )
    }

    private fun splitTone(
        r: Float,
        g: Float,
        b: Float,
        shadowShift: FloatArray,
        highlightShift: FloatArray
    ): FloatArray {
        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
        val shadowWeight = (1f - luma) * (1f - luma)
        val highlightWeight = luma * luma
        return floatArrayOf(
            (r + shadowShift[0] * shadowWeight + highlightShift[0] * highlightWeight).coerceIn(0f, 1f),
            (g + shadowShift[1] * shadowWeight + highlightShift[1] * highlightWeight).coerceIn(0f, 1f),
            (b + shadowShift[2] * shadowWeight + highlightShift[2] * highlightWeight).coerceIn(0f, 1f)
        )
    }

    private fun softClip(x: Float, knee: Float): Float {
        if (x <= knee) return x
        val excess = (x - knee) / max(1f - knee, 0.001f)
        return knee + (1f - knee) * (excess / (1f + excess))
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun normalizeDomain(v: Float, minV: Float, maxV: Float): Float {
        val span = maxV - minV
        if (span <= 0.00001f) return v.coerceIn(0f, 1f)
        return ((v - minV) / span).coerceIn(0f, 1f)
    }

    companion object {
        const val DEFAULT_LUT_SIZE = 33
    }
}
