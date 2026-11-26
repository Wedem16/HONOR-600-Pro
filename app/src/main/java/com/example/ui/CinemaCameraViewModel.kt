package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.ActiveRecordingConfig
import com.example.camera.BitrateTier
import com.example.camera.CameraSensorCapability
import com.example.camera.FlashMode
import com.example.camera.HalInspectionReport
import com.example.camera.HighSpeed60FpsController
import com.example.camera.Honor600CameraSessionManager
import com.example.camera.Honor600HalReverseEngineer
import com.example.camera.LiveCameraMetering
import com.example.camera.ManualControlState
import com.example.camera.MediaStoreRepository
import com.example.camera.SavedMediaResult
import com.example.camera.VideoCodecChoice
import com.example.camera.VideoProfileSupport
import com.example.color.LogProfile
import com.example.color.LutEngine
import com.example.color.LutPreset
import com.example.data.CinemaPreferencesRepository
import com.example.gl.FocusPeakingColor
import com.example.gl.GLLutLogRenderer
import com.example.gl.LiveScopeTelemetry
import com.example.gl.RenderPipelineConfig
import com.example.monitoring.ScopeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CaptureMode(val label: String) {
    PHOTO("PHOTO"),
    VIDEO("VIDEO"),
    CINEMA("CINEMA")
}

enum class ActiveManualParameter(val label: String) {
    NONE("OFF"),
    EV("EV"),
    ISO("ISO"),
    SHUTTER("SHUTTER"),
    FOCUS("FOCUS"),
    WB("WB")
}

enum class ActiveSheet {
    NONE,
    LOG_PROFILES,
    LUT_STUDIO,
    HAL_INSPECTOR,
    SETTINGS
}

data class CinemaUiState(
    val hasCameraPermission: Boolean = false,
    val hasAudioPermission: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.CINEMA,
    val cleanCinemaMode: Boolean = false,
    val activeSheet: ActiveSheet = ActiveSheet.NONE,
    val activeManualParam: ActiveManualParameter = ActiveManualParameter.NONE,
    val halReport: HalInspectionReport? = null,
    val activeCamera: CameraSensorCapability? = null,
    val supportedVideoProfiles: List<VideoProfileSupport> = emptyList(),
    val selectedVideoProfile: VideoProfileSupport? = null,
    val selectedFps: Int = 30,
    val selectedCodec: VideoCodecChoice = VideoCodecChoice.HEVC_H265,
    val selectedBitrateTier: BitrateTier = BitrateTier.HIGH,
    val recordAudio: Boolean = true,
    val manualControls: ManualControlState = ManualControlState(),
    val liveMetering: LiveCameraMetering = LiveCameraMetering(),
    val logProfile: LogProfile = LogProfile.STANDARD_REC709,
    val availableLuts: List<LutPreset> = emptyList(),
    val favoriteLutIds: Set<String> = emptySet(),
    val selectedLutId: String = "none",
    val lutIntensity: Float = 0.85f,
    val renderConfig: RenderPipelineConfig = RenderPipelineConfig(),
    val scopeMode: ScopeMode = ScopeMode.HISTOGRAM,
    val showRuleOfThirds: Boolean = true,
    val showSafeAreas: Boolean = false,
    val telemetry: LiveScopeTelemetry = LiveScopeTelemetry(),
    val isRecording: Boolean = false,
    val recordingElapsedSec: Long = 0L,
    val isCapturingPhoto: Boolean = false,
    val lastSavedMedia: SavedMediaResult? = null,
    val statusBanner: String? = null
) {
    val selectedLutPreset: LutPreset?
        get() = availableLuts.firstOrNull { it.id == selectedLutId }

    val effectiveBitrateMbps: Float
        get() {
            val profile = selectedVideoProfile ?: return 0f
            val pixelsPerSec = profile.actualSize.width.toLong() * profile.actualSize.height.toLong() * selectedFps.toLong()
            val bpp = if (selectedCodec == VideoCodecChoice.HEVC_H265) 0.14 else 0.20
            val bps = (pixelsPerSec * bpp * selectedBitrateTier.multiplier)
                .toLong()
                .coerceIn(4_000_000L, minOf(profile.maxEncoderBitrateBps.toLong(), 150_000_000L).coerceAtLeast(4_000_000L))
            return bps / 1_000_000f
        }
}

class CinemaCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val halInspector = Honor600HalReverseEngineer(appContext)
    private val videoController = HighSpeed60FpsController(appContext)
    private val mediaStoreRepo = MediaStoreRepository(appContext)
    private val prefsRepo = CinemaPreferencesRepository(appContext)
    val lutEngine = LutEngine()

    private val _uiState = MutableStateFlow(
        CinemaUiState(availableLuts = lutEngine.getAllPresets())
    )
    val uiState: StateFlow<CinemaUiState> = _uiState.asStateFlow()

    val renderer = GLLutLogRenderer(appContext) { telemetry ->
        _uiState.update { it.copy(telemetry = telemetry) }
    }

    private val sessionManager = Honor600CameraSessionManager(
        context = appContext,
        renderer = renderer,
        videoController = videoController,
        mediaStoreRepository = mediaStoreRepo,
        onMeteringUpdated = { metering ->
            _uiState.update { it.copy(liveMetering = metering) }
        },
        onError = { message ->
            postStatusBanner(message)
        }
    )

    private var rendererReady = false
    private var timerJob: Job? = null
    private var bannerJob: Job? = null

    init {
        viewModelScope.launch {
            val persisted = prefsRepo.settingsFlow.first()
            val profile = LogProfile.fromId(persisted.selectedLogProfileId)
            val lutId = persisted.selectedLutId
            val lutEnabled = lutId != "none"
            _uiState.update { state ->
                state.copy(
                    favoriteLutIds = persisted.favoriteLutIds,
                    logProfile = profile,
                    selectedLutId = lutId,
                    lutIntensity = persisted.lutIntensity,
                    selectedCodec = if (persisted.preferHevc) VideoCodecChoice.HEVC_H265 else VideoCodecChoice.AVC_H264,
                    recordAudio = persisted.recordAudio,
                    showRuleOfThirds = persisted.showRuleOfThirds,
                    showSafeAreas = persisted.showSafeAreas,
                    manualControls = state.manualControls.copy(stabilizationEnabled = persisted.stabilizationEnabled),
                    renderConfig = state.renderConfig.copy(
                        logProfile = profile,
                        lutEnabled = lutEnabled,
                        lutIntensity = persisted.lutIntensity
                    )
                )
            }
            syncRendererConfig()
            if (lutEnabled) {
                loadAndUploadLutAsync(lutId)
            }
        }

        // Continuously observe favorites changes
        viewModelScope.launch {
            prefsRepo.settingsFlow.collect { prefs ->
                _uiState.update { it.copy(favoriteLutIds = prefs.favoriteLutIds) }
            }
        }
    }

    fun onPermissionsUpdated(cameraGranted: Boolean, audioGranted: Boolean) {
        val wasGranted = _uiState.value.hasCameraPermission
        _uiState.update {
            it.copy(
                hasCameraPermission = cameraGranted,
                hasAudioPermission = audioGranted
            )
        }
        if (cameraGranted && (!wasGranted || _uiState.value.halReport == null)) {
            discoverHardwareAndStartCamera()
        }
    }

    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        renderer.setPreviewSurface(surface, width, height)
    }

    fun onPreviewSurfaceSizeChanged(width: Int, height: Int) {
        renderer.updatePreviewViewport(width, height)
    }

    fun onPreviewSurfaceDestroyed() {
        renderer.setPreviewSurface(null, 1, 1)
    }

    fun onResumeCamera() {
        if (!_uiState.value.hasCameraPermission) return
        val activeCam = _uiState.value.activeCamera
        if (activeCam != null && rendererReady) {
            val size = _uiState.value.selectedVideoProfile?.actualSize ?: Size(1920, 1080)
            sessionManager.openCamera(activeCam.cameraId, size, _uiState.value.manualControls)
        } else {
            discoverHardwareAndStartCamera()
        }
    }

    fun onPauseCamera() {
        if (_uiState.value.isRecording) {
            stopRecording()
        }
        sessionManager.closeCamera()
    }

    private fun discoverHardwareAndStartCamera() {
        viewModelScope.launch(Dispatchers.Default) {
            val initialReport = halInspector.inspectDevice(null)
            applyHalReport(initialReport)

            // Also probe CameraX ProcessCameraProvider asynchronously for cross-API verification
            runCatching {
                val future = ProcessCameraProvider.getInstance(appContext)
                future.addListener({
                    val provider = runCatching { future.get() }.getOrNull()
                    if (provider != null) {
                        viewModelScope.launch(Dispatchers.Default) {
                            val verifiedReport = halInspector.inspectDevice(provider)
                            _uiState.update { it.copy(halReport = verifiedReport) }
                        }
                    }
                }, ContextCompat.getMainExecutor(appContext))
            }
        }
    }

    private fun applyHalReport(report: HalInspectionReport) {
        val preferredCamera = report.logicalCameras.firstOrNull {
            it.lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK &&
                it.lensRole == com.example.camera.DetectedLensRole.MAIN_WIDE
        } ?: report.logicalCameras.firstOrNull {
            it.lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: report.logicalCameras.firstOrNull()

        val profiles = preferredCamera?.let { videoController.detectSupportedProfiles(it.cameraId) }.orEmpty()
        val defaultProfile = profiles.firstOrNull { it.tier == com.example.camera.VideoResolutionTier.FHD_1080P }
            ?: profiles.firstOrNull()
        val defaultFps = when {
            defaultProfile == null -> 30
            30 in defaultProfile.supportedFps -> 30
            else -> defaultProfile.supportedFps.first()
        }

        _uiState.update { state ->
            val codec = if (defaultProfile?.supportsHevc == false) VideoCodecChoice.AVC_H264 else state.selectedCodec
            state.copy(
                halReport = report,
                activeCamera = preferredCamera,
                supportedVideoProfiles = profiles,
                selectedVideoProfile = defaultProfile,
                selectedFps = defaultFps,
                selectedCodec = codec,
                manualControls = state.manualControls.copy(targetFps = defaultFps)
            )
        }

        if (preferredCamera != null) {
            renderer.start {
                rendererReady = true
                val size = defaultProfile?.actualSize ?: Size(1920, 1080)
                sessionManager.openCamera(preferredCamera.cameraId, size, _uiState.value.manualControls)
            }
        }
    }

    fun selectCameraById(cameraId: String) {
        if (_uiState.value.isRecording) {
            postStatusBanner("Stop recording before switching lenses.")
            return
        }
        val report = _uiState.value.halReport ?: return
        val target = report.logicalCameras.firstOrNull { it.cameraId == cameraId } ?: return
        if (target.cameraId == _uiState.value.activeCamera?.cameraId) return

        viewModelScope.launch(Dispatchers.Default) {
            val profiles = videoController.detectSupportedProfiles(target.cameraId)
            val matchedProfile = profiles.firstOrNull { it.tier == _uiState.value.selectedVideoProfile?.tier }
                ?: profiles.firstOrNull()
            val fps = if (matchedProfile != null && _uiState.value.selectedFps in matchedProfile.supportedFps) {
                _uiState.value.selectedFps
            } else {
                matchedProfile?.supportedFps?.firstOrNull { it == 30 }
                    ?: matchedProfile?.supportedFps?.firstOrNull() ?: 30
            }

            val updatedControls = _uiState.value.manualControls.copy(
                targetFps = fps,
                isoManualEnabled = false,
                shutterManualEnabled = false,
                focusManualEnabled = false,
                zoomRatio = 1.0f,
                flashMode = if (target.supportsFlash) _uiState.value.manualControls.flashMode else FlashMode.OFF
            )

            _uiState.update {
                it.copy(
                    activeCamera = target,
                    supportedVideoProfiles = profiles,
                    selectedVideoProfile = matchedProfile,
                    selectedFps = fps,
                    manualControls = updatedControls
                )
            }

            if (rendererReady) {
                val previewSize = matchedProfile?.actualSize ?: Size(1920, 1080)
                sessionManager.openCamera(target.cameraId, previewSize, updatedControls)
            }
            postStatusBanner("Switched to ${target.lensRole.label} (ID ${target.cameraId})")
        }
    }

    fun toggleFrontRearCamera() {
        val report = _uiState.value.halReport ?: return
        val currentFacing = _uiState.value.activeCamera?.lensFacing
        val desiredFacing = if (currentFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }
        val next = report.logicalCameras.firstOrNull { it.lensFacing == desiredFacing }
        if (next != null) {
            selectCameraById(next.cameraId)
        } else {
            postStatusBanner("No secondary facing camera detected.")
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (_uiState.value.isRecording) return
        _uiState.update {
            it.copy(
                captureMode = mode,
                scopeMode = if (mode == CaptureMode.CINEMA && it.scopeMode == ScopeMode.OFF) {
                    ScopeMode.LUMA_WAVEFORM
                } else it.scopeMode
            )
        }
    }

    fun toggleCleanCinemaMode() {
        _uiState.update { it.copy(cleanCinemaMode = !it.cleanCinemaMode, activeSheet = ActiveSheet.NONE) }
    }

    fun openSheet(sheet: ActiveSheet) {
        _uiState.update { it.copy(activeSheet = if (it.activeSheet == sheet) ActiveSheet.NONE else sheet) }
    }

    fun selectManualParam(param: ActiveManualParameter) {
        _uiState.update {
            it.copy(activeManualParam = if (it.activeManualParam == param) ActiveManualParameter.NONE else param)
        }
    }

    fun selectResolution(profile: VideoProfileSupport) {
        if (_uiState.value.isRecording) return
        val fps = if (_uiState.value.selectedFps in profile.supportedFps) {
            _uiState.value.selectedFps
        } else {
            profile.supportedFps.maxOrNull() ?: 30
        }
        val codec = when {
            _uiState.value.selectedCodec == VideoCodecChoice.HEVC_H265 && !profile.supportsHevc -> VideoCodecChoice.AVC_H264
            _uiState.value.selectedCodec == VideoCodecChoice.AVC_H264 && !profile.supportsAvc -> VideoCodecChoice.HEVC_H265
            else -> _uiState.value.selectedCodec
        }
        val newControls = _uiState.value.manualControls.copy(targetFps = fps)
        _uiState.update {
            it.copy(
                selectedVideoProfile = profile,
                selectedFps = fps,
                selectedCodec = codec,
                manualControls = newControls
            )
        }
        sessionManager.updateControls(newControls)
    }

    fun selectFps(fps: Int) {
        if (_uiState.value.isRecording) return
        val profile = _uiState.value.selectedVideoProfile ?: return
        if (fps !in profile.supportedFps) {
            postStatusBanner("$fps FPS is not supported by this sensor at ${profile.tier.shortLabel}.")
            return
        }
        val newControls = _uiState.value.manualControls.copy(targetFps = fps)
        _uiState.update { it.copy(selectedFps = fps, manualControls = newControls) }
        sessionManager.updateControls(newControls)
    }

    fun cycleFlashMode() {
        val cam = _uiState.value.activeCamera ?: return
        if (!cam.supportsFlash) {
            postStatusBanner("Flash is not available on this lens.")
            return
        }
        val next = when (_uiState.value.manualControls.flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        updateManualControls { it.copy(flashMode = next) }
    }

    fun updateManualControls(transform: (ManualControlState) -> ManualControlState) {
        val updated = transform(_uiState.value.manualControls)
        _uiState.update { it.copy(manualControls = updated) }
        sessionManager.updateControls(updated)
    }

    fun selectLogProfile(profile: LogProfile) {
        _uiState.update { state ->
            state.copy(
                logProfile = profile,
                renderConfig = state.renderConfig.copy(logProfile = profile)
            )
        }
        syncRendererConfig()
        viewModelScope.launch { prefsRepo.saveLogProfile(profile.id) }
    }

    fun selectLut(lutId: String) {
        val enabled = lutId != "none"
        _uiState.update { state ->
            state.copy(
                selectedLutId = lutId,
                renderConfig = state.renderConfig.copy(lutEnabled = enabled)
            )
        }
        syncRendererConfig()
        if (enabled) {
            loadAndUploadLutAsync(lutId)
        }
        viewModelScope.launch { prefsRepo.saveSelectedLut(lutId, _uiState.value.lutIntensity) }
    }

    fun setLutIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        _uiState.update { state ->
            state.copy(
                lutIntensity = clamped,
                renderConfig = state.renderConfig.copy(lutIntensity = clamped)
            )
        }
        syncRendererConfig()
        viewModelScope.launch { prefsRepo.saveSelectedLut(_uiState.value.selectedLutId, clamped) }
    }

    fun toggleFavoriteLut(lutId: String) {
        viewModelScope.launch { prefsRepo.toggleFavoriteLut(lutId) }
    }

    fun importCubeFromUri(uri: Uri, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    lutEngine.importCube(stream, displayName.removeSuffix(".cube"))
                } ?: throw IllegalStateException("Unable to read selected .cube file.")
            }.onSuccess { (preset, data) ->
                renderer.uploadLut(data)
                _uiState.update { state ->
                    state.copy(
                        availableLuts = lutEngine.getAllPresets(),
                        selectedLutId = preset.id,
                        renderConfig = state.renderConfig.copy(lutEnabled = true)
                    )
                }
                syncRendererConfig()
                postStatusBanner("Imported 3D LUT: ${preset.name} (${data.size}³)")
            }.onFailure { err ->
                postStatusBanner("LUT import failed: ${err.message}")
            }
        }
    }

    fun exportSelectedLutToUri(uri: Uri) {
        val lutId = _uiState.value.selectedLutId
        if (lutId == "none") {
            postStatusBanner("Select a 3D LUT before exporting to .cube.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val data = lutEngine.loadLutData(lutId, appContext)
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    lutEngine.exportCube(data, out)
                } ?: throw IllegalStateException("Unable to open output stream for .cube export.")
                data.title
            }.onSuccess { title ->
                postStatusBanner("Exported \"$title\" as .cube")
            }.onFailure { err ->
                postStatusBanner("Export failed: ${err.message}")
            }
        }
    }

    fun cycleScopeMode() {
        val modes = ScopeMode.entries
        val next = modes[(modes.indexOf(_uiState.value.scopeMode) + 1) % modes.size]
        setScopeMode(next)
    }

    fun setScopeMode(mode: ScopeMode) {
        _uiState.update { state ->
            state.copy(
                scopeMode = mode,
                renderConfig = state.renderConfig.copy(telemetryEnabled = mode != ScopeMode.OFF)
            )
        }
        syncRendererConfig()
    }

    fun updateRenderOverlay(transform: (RenderPipelineConfig) -> RenderPipelineConfig) {
        _uiState.update { state ->
            state.copy(renderConfig = transform(state.renderConfig))
        }
        syncRendererConfig()
    }

    fun setGuides(ruleOfThirds: Boolean, safeAreas: Boolean) {
        _uiState.update { it.copy(showRuleOfThirds = ruleOfThirds, showSafeAreas = safeAreas) }
        viewModelScope.launch { prefsRepo.saveGuidePreferences(ruleOfThirds, safeAreas) }
    }

    fun setCodec(codec: VideoCodecChoice) {
        val profile = _uiState.value.selectedVideoProfile
        if (codec == VideoCodecChoice.HEVC_H265 && profile?.supportsHevc == false) {
            postStatusBanner("HEVC H.265 is not supported by this device's encoder for this resolution.")
            return
        }
        _uiState.update { it.copy(selectedCodec = codec) }
        viewModelScope.launch {
            prefsRepo.saveRecordingPreferences(
                preferHevc = codec == VideoCodecChoice.HEVC_H265,
                recordAudio = _uiState.value.recordAudio,
                stabilization = _uiState.value.manualControls.stabilizationEnabled
            )
        }
    }

    fun setBitrateTier(tier: BitrateTier) {
        _uiState.update { it.copy(selectedBitrateTier = tier) }
    }

    fun setRecordAudio(enabled: Boolean) {
        _uiState.update { it.copy(recordAudio = enabled) }
        viewModelScope.launch {
            prefsRepo.saveRecordingPreferences(
                preferHevc = _uiState.value.selectedCodec == VideoCodecChoice.HEVC_H265,
                recordAudio = enabled,
                stabilization = _uiState.value.manualControls.stabilizationEnabled
            )
        }
    }

    fun setStabilization(enabled: Boolean) {
        updateManualControls { it.copy(stabilizationEnabled = enabled) }
        viewModelScope.launch {
            prefsRepo.saveRecordingPreferences(
                preferHevc = _uiState.value.selectedCodec == VideoCodecChoice.HEVC_H265,
                recordAudio = _uiState.value.recordAudio,
                stabilization = enabled
            )
        }
    }

    fun setFocusPeakingColor(color: FocusPeakingColor) {
        updateRenderOverlay { it.copy(focusPeakingColor = color) }
    }

    fun onPrimaryShutterClicked() {
        val state = _uiState.value
        if (state.captureMode == CaptureMode.PHOTO) {
            capturePhoto()
        } else {
            if (state.isRecording) stopRecording() else startRecording()
        }
    }

    fun capturePhoto() {
        val state = _uiState.value
        if (state.isCapturingPhoto) return
        _uiState.update { it.copy(isCapturingPhoto = true) }
        val bakeGrade = state.logProfile != LogProfile.STANDARD_REC709 || state.selectedLutId != "none"
        val modeTag = buildString {
            append("PHOTO")
            if (state.logProfile != LogProfile.STANDARD_REC709) append("_${state.logProfile.shortCode}")
            if (state.selectedLutId != "none") append("_LUT")
        }
        sessionManager.capturePhoto(bakeColorGrade = bakeGrade, modeTag = modeTag) { result ->
            _uiState.update {
                it.copy(
                    isCapturingPhoto = false,
                    lastSavedMedia = result ?: it.lastSavedMedia
                )
            }
            if (result != null) {
                postStatusBanner("Saved ${result.displayName}")
            } else {
                postStatusBanner("Photo capture failed. Ensure camera is ready.")
            }
        }
    }

    private fun startRecording() {
        val state = _uiState.value
        val profile = state.selectedVideoProfile
        if (profile == null) {
            postStatusBanner("No supported video profile found for this camera.")
            return
        }
        val bitrate = videoController.computeTargetBitrate(
            size = profile.actualSize,
            fps = state.selectedFps,
            codec = state.selectedCodec,
            tier = state.selectedBitrateTier,
            maxEncoderBitrateBps = profile.maxEncoderBitrateBps
        )
        val orientation = state.activeCamera?.sensorOrientationDegrees ?: 90
        val config = ActiveRecordingConfig(
            size = profile.actualSize,
            resolutionLabel = profile.tier.shortLabel,
            fps = state.selectedFps,
            codec = state.selectedCodec,
            bitrateBps = bitrate,
            recordAudio = state.recordAudio && state.hasAudioPermission,
            orientationHint = orientation
        )
        val modeTag = "${state.logProfile.shortCode}_${state.selectedCodec.shortLabel}"

        sessionManager.startVideoRecording(config, modeTag) { ok, err ->
            if (ok) {
                _uiState.update { it.copy(isRecording = true, recordingElapsedSec = 0L) }
                startTimer()
                val audioNote = if (config.recordAudio) "Audio ON" else "Muted"
                postStatusBanner("REC ${profile.tier.shortLabel} ${state.selectedFps}fps • ${state.selectedCodec.shortLabel} • $audioNote")
            } else {
                postStatusBanner("Recording failed: ${err ?: "Encoder unavailable"}")
            }
        }
    }

    private fun stopRecording() {
        timerJob?.cancel()
        timerJob = null
        sessionManager.stopVideoRecording { result ->
            _uiState.update {
                it.copy(
                    isRecording = false,
                    recordingElapsedSec = 0L,
                    lastSavedMedia = result ?: it.lastSavedMedia
                )
            }
            if (result != null) {
                postStatusBanner("Saved ${result.displayName}")
            } else {
                postStatusBanner("Recording stopped.")
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _uiState.update { it.copy(recordingElapsedSec = it.recordingElapsedSec + 1L) }
            }
        }
    }

    private fun loadAndUploadLutAsync(lutId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val lutData = lutEngine.loadLutData(lutId, appContext)
            renderer.uploadLut(lutData)
        }
    }

    private fun syncRendererConfig() {
        renderer.updateConfig(_uiState.value.renderConfig)
    }

    fun postStatusBanner(msg: String) {
        _uiState.update { it.copy(statusBanner = msg) }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(3200L)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        sessionManager.release()
        renderer.release()
    }
}
