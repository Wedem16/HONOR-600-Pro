package com.example

import android.util.Range
import android.util.Size
import com.example.camera.BitrateTier
import com.example.camera.HighSpeed60FpsController
import com.example.camera.VideoCodecChoice
import com.example.color.LogProfile
import com.example.color.LogProfileEngine
import com.example.color.LutCategory
import com.example.color.LutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CinemaCameraEngineUnitTest {

    @Test
    fun allLogProfilesAreMonotonicAndBounded() {
        for (profile in LogProfile.entries) {
            val curve = LogProfileEngine.sampleCurve(profile, 64)
            assertEquals("Curve length for ${profile.id}", 64, curve.size)
            for (i in curve.indices) {
                assertTrue(
                    "${profile.id} value out of [0,1] at $i: ${curve[i]}",
                    curve[i] in 0f..1f
                )
                if (i > 0) {
                    assertTrue(
                        "${profile.id} must be non-decreasing at $i (${curve[i - 1]} -> ${curve[i]})",
                        curve[i] + 1e-4f >= curve[i - 1]
                    )
                }
            }
        }
    }

    @Test
    fun proprietaryLogProfilesAreClearlyMarkedSimulated() {
        assertTrue(!LogProfile.STANDARD_REC709.isSimulated)
        assertTrue(!LogProfile.HLG_HDR.isSimulated)
        assertTrue(LogProfile.HONOR_LOG.isSimulated)
        assertTrue(LogProfile.SONY_SLOG3.isSimulated)
        assertTrue(LogProfile.ARRI_LOGC.isSimulated)
        assertTrue(LogProfile.CANON_CLOG3.isSimulated)
        assertTrue(LogProfile.PANASONIC_VLOG.isSimulated)
        assertTrue(LogProfile.APPLE_LOG.isSimulated)
        assertTrue(LogProfile.FUJIFILM_FLOG2.isSimulated)
        assertTrue(LogProfile.RED_LOG.isSimulated)
        assertTrue(LogProfile.BLACKMAGIC_FILM.isSimulated)
    }

    @Test
    fun builtInLutCatalogCoversAllRequiredCategoriesAndLooks() {
        val engine = LutEngine()
        val presets = engine.getAllPresets()
        assertTrue("Expected at least 18 built-in looks plus bypass", presets.size >= 19)
        assertEquals(4, presets.count { it.category == LutCategory.HONOR_STUDIO })
        assertEquals(7, presets.count { it.category == LutCategory.CINEMA_FILM })
        assertEquals(7, presets.count { it.category == LutCategory.CREATIVE })

        // Verify identity lookup for bypass LUT
        val bypass = engine.generateBuiltInLut("none", "Bypass", size = 9)
        val mid = bypass.sampleTrilinear(0.5f, 0.25f, 0.75f)
        assertEquals(0.5f, mid[0], 0.01f)
        assertEquals(0.25f, mid[1], 0.01f)
        assertEquals(0.75f, mid[2], 0.01f)
    }

    @Test
    fun cubeExportAndReimportRoundTripPreserves3DLutData() {
        val engine = LutEngine()
        val original = engine.generateBuiltInLut("teal_orange", "Teal & Orange Test", size = 9)
        val out = ByteArrayOutputStream()
        engine.exportCube(original, out)
        val cubeText = out.toString(Charsets.UTF_8.name())
        assertTrue(cubeText.contains("TITLE \"Teal & Orange Test\""))
        assertTrue(cubeText.contains("LUT_3D_SIZE 9"))

        val parsed = engine.parseCube(ByteArrayInputStream(out.toByteArray()), "Fallback")
        assertEquals("Teal & Orange Test", parsed.title)
        assertEquals(9, parsed.size)
        assertEquals(original.rgbData.size, parsed.rgbData.size)

        val origSample = original.sampleTrilinear(0.62f, 0.44f, 0.28f)
        val parsedSample = parsed.sampleTrilinear(0.62f, 0.44f, 0.28f)
        for (c in 0..2) {
            assertEquals(origSample[c], parsedSample[c], 0.002f)
        }
    }

    @Test
    fun monochromeBuiltInLutsProduceBalancedNeutralOutput() {
        val engine = LutEngine()
        val triX = engine.generateBuiltInLut("kodak_trix_bw", "Tri-X", size = 9)
        val sample = triX.sampleTrilinear(0.8f, 0.3f, 0.1f)
        assertEquals(sample[0], sample[1], 0.002f)
        assertEquals(sample[1], sample[2], 0.002f)
    }
}
