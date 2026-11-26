# HONOR 600 Pro Cam — Advanced Cinema Camera App

> **Disclaimer:** HONOR 600 Pro Cam is an independent, fan-made open Android cinema camera application inspired by professional cinema monitors and color pipelines. It is **not** an official HONOR product and is not affiliated with or endorsed by HONOR.

## Overview

HONOR 600 Pro Cam is a native Kotlin + Jetpack Compose Android camera application designed for HONOR 600 devices running MagicOS (and compatible Android 8.0+ / API 26+ devices). It pairs real Android **Camera2 & CameraX APIs** with a dedicated **OpenGL ES 3.0** real-time color grading pipeline, hardware surface-input **24 / 30 / 60 FPS HEVC (H.265) & H.264** video recording, **11 mathematical Log & HDR transfer profiles**, **18 original built-in 3D LUT looks + `.cube` import/export**, and a live **Cinema Monitoring Suite** driven by real pixel telemetry.

---

## Core Architecture & Source Modules

| Module | File Path | Responsibility |
| :--- | :--- | :--- |
| **App Entry & UI Host** | `app/src/main/java/com/example/MainActivity.kt` | Edge-to-edge dark cinema UI, runtime `CAMERA` / `RECORD_AUDIO` permission flow, SAF `.cube` launchers, orientation handling, and `TextureView` GL surface binding. |
| **HAL Sensor Inspector** | `app/src/main/java/com/example/camera/Honor600HalReverseEngineer.kt` | Enumerates logical & physical camera IDs (`CameraCharacteristics.physicalCameraIds`), hardware levels, focal lengths, 35mm equivalents, FPS ranges, minimum frame durations, output formats, OIS/EIS stabilization modes, and generates a copyable capability report. |
| **60 FPS Video Engine** | `app/src/main/java/com/example/camera/HighSpeed60FpsController.kt` | Probes actual `StreamConfigurationMap` & `MediaCodecList` capabilities for 4K UHD, 2.5K QHD, and 1080p FHD at 24/30/60 FPS, computes spec-safe bitrates, and runs `MediaCodec` + `MediaMuxer` + `AudioRecord` (AAC). |
| **Camera2 Session Manager** | `app/src/main/java/com/example/camera/Honor600CameraSessionManager.kt` | Manages Camera2 `CameraDevice`, repeating `CameraCaptureSession`, manual ISO/shutter/focus/Kelvin WB/EV controls, flash/torch, OIS/EIS stabilization, lens switching, and full-res / GPU-graded JPEG photo capture. |
| **Log & HDR Engine** | `app/src/main/java/com/example/color/LogProfileEngine.kt` | Mathematical forward transfer functions for Rec.709, ITU-R BT.2100 HLG HDR, and 9 clearly labeled simulated/inspired cinema Log curves (HONOR-inspired Log, S-Log3, ARRI LogC, C-Log3, V-Log, Apple Log, F-Log2, RED Log, Blackmagic Film). |
| **3D LUT Engine** | `app/src/main/java/com/example/color/LutEngine.kt` | 33×33×33 original built-in 3D LUT generator (HONOR Studio Looks, Cinema Film Looks, Creative Grades), full Adobe `.cube` parser & exporter, and bundled `.cube` asset loader. |
| **OpenGL ES 3.0 Renderer** | `app/src/main/java/com/example/gl/GLLutLogRenderer.kt` | Dedicated EGL14 + GLES 3.0 thread consuming `GL_TEXTURE_EXTERNAL_OES`, uploading `GL_TEXTURE_3D` with hardware trilinear interpolation, dual-rendering Preview + clean-feed Video Encoder surface, and downsampled pixel readback for live scopes. |
| **Live Scopes & Guides** | `app/src/main/java/com/example/monitoring/ProWaveformView.kt` | Live RGB/Luma Histogram, Luma Waveform, RGB Parade, Log Transfer Curve with real exposure IRE indicator, Rule-of-Thirds, Action/Title Safe Areas, 50/50 Split-Screen divider, and Manual Focus Assistance HUD. |
| **MediaStore Manager** | `app/src/main/java/com/example/camera/MediaStoreRepository.kt` | Zero-storage-permission Android 10+ MediaStore organizer saving to `Pictures/Honor600ProCam/` and `Movies/Honor600ProCam/` with date, time, resolution, FPS, and mode filenames. |
| **OpenGL ES 3.0 Shaders** | `app/src/main/assets/shaders/cinema_vertex.glsl`, `cinema_fragment.glsl` | GLSL ES 3.00 shaders executing Log transfer functions, 3D LUT sampling, Zebra exposure warnings, Sobel Focus Peaking, False-Color IRE map, and 50/50 split comparison. |

---

## HONOR 600 MagicOS Camera Capability Report & Compatibility Notes

Because MagicOS exposes camera capabilities dynamically per SKU, firmware revision, and HAL policy, **HONOR 600 Pro Cam never fabricates sensors or forces unsupported video modes**. Instead, on launch and inside the built-in **HAL Inspector** (`Memory` icon in top bar), the app queries Android's real `CameraManager` and `MediaCodecList` directly:

- **Logical & Physical Camera Discovery**: Inspects `cameraManager.cameraIdList` and `CameraCharacteristics.physicalCameraIds` (API 28+), classifying genuine Main Wide, Ultrawide, Telephoto, Macro, Auxiliary, and Front lenses based on actual focal lengths, physical sensor dimensions, 35mm equivalent focal length, and minimum focus distance.
- **Hardware Level & Manual Controls**: Checks `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR` (manual ISO & shutter speed), `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING` (manual Kelvin white balance), and `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` (manual focus diopters). Any manual control not reported by a lens is gracefully disabled in the HUD (`N/A`).
- **60 FPS & Encoder Matrix**: Validates 4K UHD (3840×2160), 2.5K QHD (2560×1440), and 1080p FHD (1920×1080) across 24 / 30 / 60 FPS against both Camera2 `StreamConfigurationMap` (standard + high-speed ranges) and hardware `video/hevc` / `video/avc` `MediaCodecInfo.VideoCapabilities`.
- **Pre-Encode Real-Time Grading**: Video frames flow from Camera2 → `SurfaceTexture` → OpenGL ES 3.0 (Log OETF + 33³ 3D LUT) → hardware `MediaCodec` input `Surface`, burning the selected color grade directly into the MP4 while keeping monitoring overlays (Zebra, Focus Peaking, False Color, Split-Screen) strictly on the viewfinder.

---

## File Organization

- **Photos**: `Pictures/Honor600ProCam/HONOR600_YYYYMMDD_HHMMSS_<RES>_<MODE>.jpg`
- **Videos**: `Movies/Honor600ProCam/HONOR600_YYYYMMDD_HHMMSS_<RES>_<FPS>FPS_<LOG>_<CODEC>.mp4`

---

## Build & Test Instructions

1. Open the project in Android Studio (AGP 9.1+, JDK 11+, Android SDK 36, minSdk 26).
2. Run unit and Robolectric tests:
   ```bash
   gradle :app:testDebugUnitTest
   ```
3. Build the installable debug APK:
   ```bash
   gradle :app:assembleDebug
   ```
   Output APK path: `app/build/outputs/apk/debug/app-debug.apk`.
