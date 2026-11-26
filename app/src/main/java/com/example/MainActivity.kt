package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.monitoring.CinemaViewfinderGuidesOverlay
import com.example.monitoring.ProWaveformView
import com.example.ui.ActiveSheet
import com.example.ui.CinemaCameraViewModel
import com.example.ui.CinemaUiState
import com.example.ui.components.CinemaBottomCaptureDeck
import com.example.ui.components.CinemaTopTelemetryBar
import com.example.ui.components.HalInspectorScreen
import com.example.ui.components.LogProfilesSheet
import com.example.ui.components.LutStudioSheet
import com.example.ui.components.ManualControlsBar
import com.example.ui.components.MonitoringQuickStrip
import com.example.ui.components.RecordingTallyBadge
import com.example.ui.components.SettingsSheet
import com.example.ui.theme.CinemaAmber
import com.example.ui.theme.CinemaMuted
import com.example.ui.theme.CinemaSilver
import com.example.ui.theme.CinemaWhite
import com.example.ui.theme.Honor600ProCamTheme
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid

class MainActivity : ComponentActivity() {

    private val viewModel: CinemaCameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            Honor600ProCamTheme {
                Honor600ProCamRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun Honor600ProCamRoot(viewModel: CinemaCameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camGranted = results[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val audioGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        viewModel.onPermissionsUpdated(camGranted, audioGranted)
    }

    val importCubeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "ImportedLUT"
            viewModel.importCubeFromUri(uri, fallbackName)
        }
    }

    val exportCubeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSelectedLutToUri(uri)
        }
    }

    LaunchedEffect(Unit) {
        val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionsUpdated(camOk, audioOk)
        if (!camOk) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val audioOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    viewModel.onPermissionsUpdated(camOk, audioOk)
                    if (camOk) viewModel.onResumeCamera()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.onPauseCamera()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Intercept system back button to dismiss active sheets or clean mode cleanly
    BackHandler(enabled = state.activeSheet != ActiveSheet.NONE || state.cleanCinemaMode) {
        if (state.activeSheet != ActiveSheet.NONE) {
            viewModel.openSheet(ActiveSheet.NONE)
        } else if (state.cleanCinemaMode) {
            viewModel.toggleCleanCinemaMode()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        if (!state.hasCameraPermission) {
            CameraPermissionGate(
                onRequestPermissions = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                },
                onOpenAppSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }
            )
        } else {
            CinemaCameraWorkspace(
                state = state,
                viewModel = viewModel,
                onImportCube = { importCubeLauncher.launch(arrayOf("*/*")) },
                onExportCube = {
                    val lutName = state.selectedLutPreset?.name
                        ?.lowercase()
                        ?.replace(Regex("[^a-z0-9]+"), "_") ?: "honor600_lut"
                    exportCubeLauncher.launch("${lutName}.cube")
                },
                onOpenGallery = {
                    val saved = state.lastSavedMedia
                    val intent = if (saved != null) {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(saved.uri, if (saved.isVideo) "video/mp4" else "image/jpeg")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        viewModel.postStatusBanner("Media saved to ${saved?.relativePath ?: "Pictures/Honor600ProCam"}")
                    }
                }
            )
        }
    }
}

@Composable
private fun CinemaCameraWorkspace(
    state: CinemaUiState,
    viewModel: CinemaCameraViewModel,
    onImportCube: () -> Unit,
    onExportCube: () -> Unit,
    onOpenGallery: () -> Unit
) {
    val rearLenses = remember(state.halReport) {
        state.halReport?.logicalCameras
            ?.filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
            .orEmpty()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live OpenGL ES 3.0 TextureView surface
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var windowSurface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            windowSurface?.release()
                            val surface = Surface(st)
                            windowSurface = surface
                            viewModel.onPreviewSurfaceAvailable(surface, width, height)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            viewModel.onPreviewSurfaceSizeChanged(width, height)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            viewModel.onPreviewSurfaceDestroyed()
                            windowSurface?.release()
                            windowSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera_gl_preview")
        )

        // 2. Viewfinder Guides (Rule of Thirds, Safe Areas, 50/50 Split divider, Manual Focus HUD)
        CinemaViewfinderGuidesOverlay(
            showRuleOfThirds = state.showRuleOfThirds,
            showSafeAreas = state.showSafeAreas,
            showSplitScreen = state.renderConfig.splitScreen,
            splitPosition = state.renderConfig.splitPosition,
            manualFocusEnabled = state.manualControls.focusManualEnabled,
            focusDistanceDiopters = state.manualControls.focusDistanceDiopters,
            focusPeakingEnabled = state.renderConfig.focusPeaking
        )

        // 3. Top Telemetry Bar + Quick Monitoring Strip
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            CinemaTopTelemetryBar(
                state = state,
                onCycleFlash = { viewModel.cycleFlashMode() },
                onCycleResolution = {
                    val list = state.supportedVideoProfiles
                    if (list.isNotEmpty()) {
                        val idx = list.indexOfFirst { it.tier == state.selectedVideoProfile?.tier }
                        val next = list[(idx + 1) % list.size]
                        viewModel.selectResolution(next)
                    }
                },
                onCycleFps = {
                    val fpsList = state.selectedVideoProfile?.supportedFps.orEmpty()
                    if (fpsList.isNotEmpty()) {
                        val idx = fpsList.indexOf(state.selectedFps)
                        val next = fpsList[(idx + 1) % fpsList.size]
                        viewModel.selectFps(next)
                    }
                },
                onOpenLogSheet = { viewModel.openSheet(ActiveSheet.LOG_PROFILES) },
                onOpenLutSheet = { viewModel.openSheet(ActiveSheet.LUT_STUDIO) },
                onOpenHalInspector = { viewModel.openSheet(ActiveSheet.HAL_INSPECTOR) },
                onOpenSettings = { viewModel.openSheet(ActiveSheet.SETTINGS) },
                onToggleCleanMode = { viewModel.toggleCleanCinemaMode() }
            )

            AnimatedVisibility(visible = !state.cleanCinemaMode) {
                MonitoringQuickStrip(
                    state = state,
                    onCycleScope = { viewModel.cycleScopeMode() },
                    onToggleZebra = {
                        viewModel.updateRenderOverlay { it.copy(zebraEnabled = !it.zebraEnabled) }
                    },
                    onToggleFocusPeaking = {
                        viewModel.updateRenderOverlay { it.copy(focusPeaking = !it.focusPeaking) }
                    },
                    onToggleFalseColor = {
                        viewModel.updateRenderOverlay { it.copy(falseColor = !it.falseColor) }
                    },
                    onToggleSplitScreen = {
                        viewModel.updateRenderOverlay { it.copy(splitScreen = !it.splitScreen) }
                    },
                    onToggleSafeGuides = {
                        viewModel.setGuides(state.showRuleOfThirds, !state.showSafeAreas)
                    },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // 4. Recording Tally Badge (live red timecode)
        if (state.isRecording) {
            RecordingTallyBadge(
                elapsedSec = state.recordingElapsedSec,
                fps = state.selectedFps,
                resolutionLabel = state.selectedVideoProfile?.tier?.shortLabel ?: "1080p",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 90.dp)
            )
        }

        // 5. Real-time Cinema Scope / Waveform Monitor (ProWaveformView)
        ProWaveformView(
            telemetry = state.telemetry,
            scopeMode = state.scopeMode,
            activeLogProfile = state.logProfile,
            onCycleScopeMode = { viewModel.cycleScopeMode() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = if (state.cleanCinemaMode) 58.dp else 94.dp, end = 10.dp)
        )

        // 6. Status Toast Banner
        AnimatedVisibility(
            visible = state.statusBanner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
        ) {
            state.statusBanner?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaWhite,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianVoid.copy(alpha = 0.85f))
                        .border(1.dp, CinemaAmber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("status_banner")
                )
            }
        }

        // 7. Bottom Manual Controls + Capture Deck
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(visible = !state.cleanCinemaMode) {
                ManualControlsBar(
                    state = state,
                    onSelectParam = { viewModel.selectManualParam(it) },
                    onUpdateControls = { viewModel.updateManualControls(it) },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            CinemaBottomCaptureDeck(
                state = state,
                rearLenses = rearLenses,
                onSelectLens = { viewModel.selectCameraById(it) },
                onSelectMode = { viewModel.setCaptureMode(it) },
                onShutterClick = { viewModel.onPrimaryShutterClicked() },
                onSwitchFrontRear = { viewModel.toggleFrontRearCamera() },
                onOpenGallery = onOpenGallery
            )
        }

        // 8. Modal Sheets: Log Profiles, 3D LUT Studio, Settings, HAL Inspector
        AnimatedVisibility(
            visible = state.activeSheet == ActiveSheet.LOG_PROFILES,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LogProfilesSheet(
                selectedProfile = state.logProfile,
                onSelectProfile = { viewModel.selectLogProfile(it) },
                onClose = { viewModel.openSheet(ActiveSheet.NONE) }
            )
        }

        AnimatedVisibility(
            visible = state.activeSheet == ActiveSheet.LUT_STUDIO,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LutStudioSheet(
                state = state,
                onSelectLut = { viewModel.selectLut(it) },
                onIntensityChange = { viewModel.setLutIntensity(it) },
                onToggleFavorite = { viewModel.toggleFavoriteLut(it) },
                onImportCube = onImportCube,
                onExportCube = onExportCube,
                onClose = { viewModel.openSheet(ActiveSheet.NONE) }
            )
        }

        AnimatedVisibility(
            visible = state.activeSheet == ActiveSheet.SETTINGS,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SettingsSheet(
                state = state,
                onSelectResolution = { viewModel.selectResolution(it) },
                onSelectFps = { viewModel.selectFps(it) },
                onSelectCodec = { viewModel.setCodec(it) },
                onSelectBitrateTier = { viewModel.setBitrateTier(it) },
                onToggleAudio = { viewModel.setRecordAudio(it) },
                onToggleStabilization = { viewModel.setStabilization(it) },
                onSelectScopeMode = { viewModel.setScopeMode(it) },
                onUpdateRenderOverlay = { viewModel.updateRenderOverlay(it) },
                onUpdateGuides = { rot, safe -> viewModel.setGuides(rot, safe) },
                onClose = { viewModel.openSheet(ActiveSheet.NONE) }
            )
        }

        AnimatedVisibility(
            visible = state.activeSheet == ActiveSheet.HAL_INSPECTOR,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            HalInspectorScreen(
                report = state.halReport,
                activeCameraId = state.activeCamera?.cameraId,
                videoProfiles = state.supportedVideoProfiles,
                onSelectCamera = { id ->
                    viewModel.selectCameraById(id)
                    viewModel.openSheet(ActiveSheet.NONE)
                },
                onCopiedReport = {
                    viewModel.postStatusBanner("Copied HONOR 600 HAL Capability Report to clipboard.")
                },
                onClose = { viewModel.openSheet(ActiveSheet.NONE) }
            )
        }
    }
}

@Composable
private fun CameraPermissionGate(
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF171D2A), ObsidianVoid)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ObsidianSurface)
                .border(1.dp, CinemaAmber.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(24.dp)
                .testTag("camera_permission_gate"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_cinema_camera_icon_1795703001027),
                contentDescription = "HONOR 600 Pro Cam Lens Emblem",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .border(2.dp, CinemaAmber, CircleShape)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = CinemaWhite
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.app_subtitle_fan_made),
                style = MaterialTheme.typography.labelSmall,
                color = CinemaAmber,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_camera_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = CinemaSilver,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = CinemaAmber, contentColor = ObsidianVoid),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("button_grant_camera")
            ) {
                Text(stringResource(R.string.permission_grant_button), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenAppSettings,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("button_open_settings")
            ) {
                Text(stringResource(R.string.permission_open_settings), color = CinemaSilver)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Photos & videos save directly via MediaStore with zero broad storage permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = CinemaMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}
