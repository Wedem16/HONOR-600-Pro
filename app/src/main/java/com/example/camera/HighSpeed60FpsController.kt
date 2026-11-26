package com.example.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

enum class VideoResolutionTier(
    val id: String,
    val label: String,
    val shortLabel: String,
    val targetWidth: Int,
    val targetHeight: Int
) {
    UHD_4K("4k", "4K UHD (3840×2160)", "4K", 3840, 2160),
    QHD_2_5K("2.5k", "2.5K QHD (2560×1440)", "2.5K", 2560, 1440),
    FHD_1080P("1080p", "1080p FHD (1920×1080)", "1080p", 1920, 1080),
    HD_720P("720p", "720p HD (1280×720)", "720p", 1280, 720)
}

enum class VideoCodecChoice(val mimeType: String, val label: String, val shortLabel: String) {
    HEVC_H265(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC / H.265", "H.265"),
    AVC_H264(MediaFormat.MIMETYPE_VIDEO_AVC, "AVC / H.264", "H.264")
}

enum class BitrateTier(val label: String, val multiplier: Float) {
    STANDARD("Standard", 1.0f),
    HIGH("High Bitrate", 1.6f),
    CINEMA_PRO("Cinema Pro", 2.2f)
}

data class VideoProfileSupport(
    val tier: VideoResolutionTier,
    val actualSize: Size,
    val supportedFps: List<Int>,
    val supportsHevc: Boolean,
    val supportsAvc: Boolean,
    val maxEncoderBitrateBps: Int,
    val requiresHighSpeedSessionFor60Fps: Boolean
) {
    val supports60Fps: Boolean get() = 60 in supportedFps
}

data class ActiveRecordingConfig(
    val size: Size,
    val resolutionLabel: String,
    val fps: Int,
    val codec: VideoCodecChoice,
    val bitrateBps: Int,
    val recordAudio: Boolean,
    val orientationHint: Int
)

/**
 * Probes real Camera2 stream capabilities and Android MediaCodec hardware encoders to discover
 * valid 4K/2.5K/1080p @ 24/30/60 FPS configurations, and manages surface-based MediaCodec +
 * MediaMuxer + AudioRecord capture so OpenGL ES 3.0 Log & 3D LUT processing runs before encoding.
 */
class HighSpeed60FpsController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var pendingTarget: PendingVideoTarget? = null
    private var expectAudio = false

    private val isRecordingFlag = AtomicBoolean(false)
    private val muxerLock = Any()
    private var videoDrainThread: Thread? = null
    private var audioThread: Thread? = null

    val isRecording: Boolean get() = isRecordingFlag.get()

    /**
     * Inspects actual Camera2 `StreamConfigurationMap` and `MediaCodecList` capabilities for
     * [cameraId] and returns strictly supported resolution/FPS profiles without forcing invalid modes.
     */
    fun detectSupportedProfiles(cameraId: String): List<VideoProfileSupport> {
        val chars = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return emptyList()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val standardSizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
        val highSpeedSizes = runCatching { map.highSpeedVideoSizes?.toList().orEmpty() }.getOrDefault(emptyList())
        val aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val results = mutableListOf<VideoProfileSupport>()

        for (tier in VideoResolutionTier.entries) {
            val matchedSize = findMatchingSize(standardSizes, tier.targetWidth, tier.targetHeight)
                ?: findMatchingSize(highSpeedSizes, tier.targetWidth, tier.targetHeight)
                ?: continue

            val hevcCap = findVideoEncoderCapabilities(codecList, MediaFormat.MIMETYPE_VIDEO_HEVC)
            val avcCap = findVideoEncoderCapabilities(codecList, MediaFormat.MIMETYPE_VIDEO_AVC)

            val supportedFps = mutableListOf<Int>()
            var requiresHighSpeed60 = false

            for (fps in listOf(24, 30, 60)) {
                val cameraSupportsFps = isCameraFpsSupported(
                    map = map,
                    size = matchedSize,
                    fps = fps,
                    aeFpsRanges = aeFpsRanges,
                    highSpeedSizes = highSpeedSizes
                )
                if (fps == 60 && cameraSupportsFps.second) {
                    requiresHighSpeed60 = true
                }
                val encoderSupports = isEncoderSupported(hevcCap, matchedSize, fps) ||
                    isEncoderSupported(avcCap, matchedSize, fps)
                if (cameraSupportsFps.first && encoderSupports) {
                    supportedFps.add(fps)
                }
            }

            if (supportedFps.isNotEmpty()) {
                val bestFps = supportedFps.max()
                val hevcOk = isEncoderSupported(hevcCap, matchedSize, bestFps)
                val avcOk = isEncoderSupported(avcCap, matchedSize, bestFps)
                val maxBitrate = maxOf(
                    hevcCap?.bitrateRange?.upper ?: 20_000_000,
                    avcCap?.bitrateRange?.upper ?: 20_000_000
                )
                results.add(
                    VideoProfileSupport(
                        tier = tier,
                        actualSize = matchedSize,
                        supportedFps = supportedFps,
                        supportsHevc = hevcOk,
                        supportsAvc = avcOk,
                        maxEncoderBitrateBps = maxBitrate,
                        requiresHighSpeedSessionFor60Fps = requiresHighSpeed60
                    )
                )
            }
        }

        // Fallback to largest available standard video size at 30 FPS if standard tiers weren't present (e.g. emulator)
        if (results.isEmpty() && standardSizes.isNotEmpty()) {
            val fallback = standardSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 }
                ?: standardSizes.last()
            results.add(
                VideoProfileSupport(
                    tier = VideoResolutionTier.HD_720P,
                    actualSize = fallback,
                    supportedFps = listOf(30),
                    supportsHevc = findVideoEncoderCapabilities(codecList, MediaFormat.MIMETYPE_VIDEO_HEVC) != null,
                    supportsAvc = true,
                    maxEncoderBitrateBps = 16_000_000,
                    requiresHighSpeedSessionFor60Fps = false
                )
            )
        }
        return results
    }

    /**
     * Selects the cleanest AE FPS range supported by the camera for [targetFps].
     */
    fun selectBestFpsRange(availableRanges: List<Range<Int>>, targetFps: Int): Range<Int> {
        if (availableRanges.isEmpty()) return Range(targetFps, targetFps)
        // Prefer fixed frame rate [targetFps, targetFps] for rock-solid cinema pacing
        availableRanges.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }
        // Next prefer variable range peaking at targetFps with highest lower bound
        availableRanges.filter { it.upper == targetFps }.maxByOrNull { it.lower }?.let { return it }
        // Next prefer range containing targetFps
        availableRanges.filter { targetFps in it.lower..it.upper }
            .minByOrNull { it.upper - it.lower }?.let { return it }
        return availableRanges.maxByOrNull { it.upper } ?: Range(30, 30)
    }

    /**
     * Calculates a safe target video bitrate based on resolution, FPS, codec efficiency, and bitrate tier,
     * clamped strictly to the encoder's reported ceiling.
     */
    fun computeTargetBitrate(
        size: Size,
        fps: Int,
        codec: VideoCodecChoice,
        tier: BitrateTier,
        maxEncoderBitrateBps: Int
    ): Int {
        val pixelsPerSec = size.width.toLong() * size.height.toLong() * fps.toLong()
        // Base bits-per-pixel factor tuned for crisp mobile cinema video
        val bpp = if (codec == VideoCodecChoice.HEVC_H265) 0.14 else 0.20
        val baseBps = (pixelsPerSec * bpp * tier.multiplier).toLong()
        val floorBps = 4_000_000L
        val ceilingBps = minOf(maxEncoderBitrateBps.toLong(), 150_000_000L).coerceAtLeast(floorBps)
        return baseBps.coerceIn(floorBps, ceilingBps).toInt()
    }

    /**
     * Starts hardware surface-input encoding and returns the encoder [Surface] for [GLLutLogRenderer].
     */
    fun startRecording(
        config: ActiveRecordingConfig,
        mediaStoreRepository: MediaStoreRepository,
        modeTag: String
    ): Surface {
        if (isRecordingFlag.get()) {
            throw IllegalStateException("Recording already in progress.")
        }

        val target = mediaStoreRepository.prepareVideoOutput(
            resolutionTag = config.resolutionLabel,
            fps = config.fps,
            modeTag = modeTag
        )
        pendingTarget = target
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        expectAudio = config.recordAudio && hasAudioPerm

        try {
            val videoFormat = MediaFormat.createVideoFormat(
                config.codec.mimeType,
                config.size.width,
                config.size.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val vEncoder = MediaCodec.createEncoderByType(config.codec.mimeType)
            vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = vEncoder.createInputSurface()
            vEncoder.start()

            videoEncoder = vEncoder
            inputSurface = surface
            mediaMuxer = MediaMuxer(target.fileDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(config.orientationHint)
            }

            if (expectAudio) {
                setupAudioPipeline()
            }

            isRecordingFlag.set(true)
            startDrainThreads()
            return surface
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start MediaCodec recording", t)
            cleanupResources(succeeded = false, mediaStoreRepository = mediaStoreRepository)
            throw t
        }
    }

    fun stopRecording(mediaStoreRepository: MediaStoreRepository): SavedMediaResult? {
        if (!isRecordingFlag.getAndSet(false)) return null

        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { videoDrainThread?.join(2500) }
        runCatching { audioThread?.join(1500) }

        val didStartMuxer = synchronized(muxerLock) { muxerStarted }
        return cleanupResources(succeeded = didStartMuxer, mediaStoreRepository = mediaStoreRepository)
    }

    private fun setupAudioPipeline() {
        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            expectAudio = false
            return
        }
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aEncoder.start()

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                channelMask,
                encoding,
                minBuf * 2
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                audioEncoder = aEncoder
                audioRecord = recorder
                recorder.startRecording()
            } else {
                recorder.release()
                aEncoder.stop()
                aEncoder.release()
                expectAudio = false
            }
        } catch (se: SecurityException) {
            aEncoder.stop()
            aEncoder.release()
            expectAudio = false
        }
    }

    private fun startDrainThreads() {
        videoDrainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            val encoder = videoEncoder ?: return@Thread
            while (true) {
                val outIndex = runCatching { encoder.dequeueOutputBuffer(bufferInfo, 10_000) }.getOrElse { break }
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!isRecordingFlag.get()) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            if (videoTrackIndex == -1) {
                                videoTrackIndex = mediaMuxer?.addTrack(encoder.outputFormat) ?: -1
                                tryStartMuxerLocked()
                            }
                        }
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            synchronized(muxerLock) {
                                if (muxerStarted && videoTrackIndex >= 0) {
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    runCatching {
                                        mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                                    }
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        }, "Honor600VideoDrain").apply { start() }

        if (expectAudio && audioEncoder != null && audioRecord != null) {
            audioThread = Thread({
                val encoder = audioEncoder ?: return@Thread
                val recorder = audioRecord ?: return@Thread
                val bufferInfo = MediaCodec.BufferInfo()
                val readChunk = ByteArray(2048)
                val startNs = System.nanoTime()

                while (isRecordingFlag.get()) {
                    val readBytes = recorder.read(readChunk, 0, readChunk.size)
                    if (readBytes > 0) {
                        val inIdx = runCatching { encoder.dequeueInputBuffer(5_000) }.getOrElse { -1 }
                        if (inIdx >= 0) {
                            val inBuf: ByteBuffer? = encoder.getInputBuffer(inIdx)
                            inBuf?.clear()
                            inBuf?.put(readChunk, 0, readBytes)
                            val ptsUs = (System.nanoTime() - startNs) / 1_000L
                            encoder.queueInputBuffer(inIdx, 0, readBytes, ptsUs, 0)
                        }
                    }
                    drainAudioOutput(encoder, bufferInfo)
                }
                // Send EOS to audio encoder
                val inIdx = runCatching { encoder.dequeueInputBuffer(5_000) }.getOrElse { -1 }
                if (inIdx >= 0) {
                    val ptsUs = (System.nanoTime() - startNs) / 1_000L
                    encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainAudioOutput(encoder, bufferInfo)
            }, "Honor600AudioEncode").apply { start() }
        }
    }

    private fun drainAudioOutput(encoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = runCatching { encoder.dequeueOutputBuffer(bufferInfo, 0) }.getOrElse { break }
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (audioTrackIndex == -1) {
                            audioTrackIndex = mediaMuxer?.addTrack(encoder.outputFormat) ?: -1
                            tryStartMuxerLocked()
                        }
                    }
                }
                outIndex >= 0 -> {
                    val data = encoder.getOutputBuffer(outIndex)
                    if (data != null && bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        synchronized(muxerLock) {
                            if (muxerStarted && audioTrackIndex >= 0) {
                                data.position(bufferInfo.offset)
                                data.limit(bufferInfo.offset + bufferInfo.size)
                                runCatching {
                                    mediaMuxer?.writeSampleData(audioTrackIndex, data, bufferInfo)
                                }
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                else -> break
            }
        }
    }

    private fun tryStartMuxerLocked() {
        if (muxerStarted) return
        val videoReady = videoTrackIndex >= 0
        val audioReady = !expectAudio || audioTrackIndex >= 0
        if (videoReady && audioReady) {
            runCatching {
                mediaMuxer?.start()
                muxerStarted = true
            }
        }
    }

    private fun cleanupResources(
        succeeded: Boolean,
        mediaStoreRepository: MediaStoreRepository
    ): SavedMediaResult? {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null

        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        var finalSuccess = succeeded
        synchronized(muxerLock) {
            if (muxerStarted) {
                runCatching { mediaMuxer?.stop() }.onFailure { finalSuccess = false }
            }
            runCatching { mediaMuxer?.release() }
            mediaMuxer = null
            muxerStarted = false
        }

        val target = pendingTarget
        pendingTarget = null
        return if (target != null) {
            mediaStoreRepository.finalizeVideoOutput(target, finalSuccess)
        } else null
    }

    private fun findMatchingSize(sizes: List<Size>, w: Int, h: Int): Size? {
        sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        // Accept close aspect-matched cinema variant (e.g. 2560x1440 or 2560x1152 if device uses 2.5K)
        return sizes.firstOrNull {
            kotlin.math.abs(it.width - w) <= 64 && kotlin.math.abs(it.height - h) <= 64
        }
    }

    private fun isCameraFpsSupported(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Size,
        fps: Int,
        aeFpsRanges: List<Range<Int>>,
        highSpeedSizes: List<Size>
    ): Pair<Boolean, Boolean> {
        val minDurationNs = runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }
            .getOrDefault(0L)
        val maxFpsFromDuration = if (minDurationNs > 0L) 1_000_000_000.0 / minDurationNs else 60.0
        val aeSupports = aeFpsRanges.any { it.upper >= fps }

        if (aeSupports && maxFpsFromDuration >= (fps - 1.5)) {
            return true to false
        }

        if (fps >= 60 && highSpeedSizes.contains(size)) {
            val hsRanges = runCatching { map.getHighSpeedVideoFpsRangesFor(size) }.getOrNull()
            if (hsRanges != null && hsRanges.any { it.upper >= fps }) {
                return true to true
            }
        }
        return false to false
    }

    private fun findVideoEncoderCapabilities(
        codecList: MediaCodecList,
        mimeType: String
    ): MediaCodecInfo.VideoCapabilities? {
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                val caps = runCatching { info.getCapabilitiesForType(mimeType).videoCapabilities }.getOrNull()
                if (caps != null) return caps
            }
        }
        return null
    }

    private fun isEncoderSupported(
        caps: MediaCodecInfo.VideoCapabilities?,
        size: Size,
        fps: Int
    ): Boolean {
        if (caps == null) return false
        return runCatching {
            caps.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "HighSpeed60Fps"
    }
}
