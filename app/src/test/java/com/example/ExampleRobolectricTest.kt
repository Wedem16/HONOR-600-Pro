package com.example

import android.content.Context
import android.util.Range
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import com.example.camera.BitrateTier
import com.example.camera.HighSpeed60FpsController
import com.example.camera.Honor600HalReverseEngineer
import com.example.camera.MediaStoreRepository
import com.example.camera.VideoCodecChoice
import com.example.color.LutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun appNameMatchesHonor600ProCam() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("HONOR 600 Pro Cam", context.getString(R.string.app_name))
    }

    @Test
    fun bundledCubeAssetsLoadAndParseCleanly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = LutEngine()
        context.assets.open("luts/harcourt_classic_bw.cube").use { stream ->
            val lut = engine.parseCube(stream, "Harcourt")
            assertEquals(3, lut.size)
            assertEquals(3 * 3 * 3 * 3, lut.rgbData.size)
        }
        context.assets.open("luts/kodak_2383_print.cube").use { stream ->
            val lut = engine.parseCube(stream, "Kodak")
            assertEquals(3, lut.size)
            assertEquals(3 * 3 * 3 * 3, lut.rgbData.size)
        }
    }

    @Test
    fun bundledGlslEs3ShadersExistInAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vertex = context.assets.open("shaders/cinema_vertex.glsl").bufferedReader().use { it.readText() }
        val fragment = context.assets.open("shaders/cinema_fragment.glsl").bufferedReader().use { it.readText() }
        assertTrue(vertex.contains("#version 300 es"))
        assertTrue(fragment.contains("sampler3D uLutTexture"))
        assertTrue(fragment.contains("applyLogProfile"))
    }

    @Test
    fun mediaStoreFilenameFormattingContainsModeAndResolution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = MediaStoreRepository(context)
        val photoName = repo.buildPhotoFileName(4000, 3000, "PHOTO_SLOG3")
        val videoName = repo.buildVideoFileName("4K", 60, "SLOG3_H265")
        assertTrue(photoName.startsWith("HONOR600_"))
        assertTrue(photoName.endsWith("_12MP_PHOTO_SLOG3.jpg"))
        assertTrue(videoName.startsWith("HONOR600_"))
        assertTrue(videoName.endsWith("_4K_60FPS_SLOG3_H265.mp4"))
    }

    @Test
    fun highSpeed60FpsControllerSelectsFixedFpsRangeAndSafeBitrate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = HighSpeed60FpsController(context)
        val ranges = listOf(Range(15, 30), Range(30, 30), Range(24, 24), Range(30, 60), Range(60, 60))
        assertEquals(Range(60, 60), controller.selectBestFpsRange(ranges, 60))
        assertEquals(Range(24, 24), controller.selectBestFpsRange(ranges, 24))

        val bitrate = controller.computeTargetBitrate(
            size = Size(3840, 2160),
            fps = 60,
            codec = VideoCodecChoice.HEVC_H265,
            tier = BitrateTier.HIGH,
            maxEncoderBitrateBps = 120_000_000
        )
        assertTrue("Bitrate $bitrate should stay within safe bounds", bitrate in 4_000_000..120_000_000)
    }

    @Test
    fun halReverseEngineerGeneratesFormattedReportWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hal = Honor600HalReverseEngineer(context)
        val report = hal.inspectDevice(null)
        assertNotNull(report)
        val text = report.formatTextReport()
        assertTrue(text.contains("HONOR 600 Pro Cam"))
    }
}
