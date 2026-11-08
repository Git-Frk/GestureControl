# GestureOS

Native Kotlin prototype for a wall-mounted **Nokia T20 running Android 13 (API 33)**.
Milestone 1 provides a mirrored front-camera preview, 21 hand landmarks, and local
recognition of open palm, closed fist, thumbs-up, and thumbs-down. It only displays
recognition feedback; it does not control Android or other apps.

## Command-line setup on macOS

Requires macOS, Conda (Miniforge/Miniconda), and the standard `curl`, `unzip`, and
`shasum` commands. Android Studio is unnecessary. From the repository root:

```bash
bash scripts/setup-macos.sh
source scripts/env.sh
./gradlew --no-daemon clean assembleDebug test lint
```

The setup script creates a local Conda environment with OpenJDK 17.0.18 and installs
Android command-line tools 19.0, platform 35, Build Tools 35.0.0, and platform-tools
(ADB). Read and accept the SDK license when prompted. All tools and caches are
under ignored `.tools/`; nothing is added to your shell profile. Source `scripts/env.sh`
in each new terminal. To use the environment through Conda directly:

```bash
conda activate "$PWD/.tools/env"
source scripts/env.sh
```

An existing toolchain also works: set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to an
SDK with the packages above, then run `./gradlew` without sourcing the local env.
Build versions are pinned: Gradle 8.11.1 (official wrapper with SHA-256 verification),
AGP 8.9.2, Kotlin 2.1.20, CameraX 1.4.2, and MediaPipe Tasks Vision 0.10.21.
`minSdk=33`; `compileSdk` and `targetSdk` are 35. This is a sideloaded prototype for
ARM64 / ARMv7 devices, not x86 emulators. Dependency upgrade notices and ChromeOS ABI
checks are excluded from lint for this pinned ARM-tablet scope; other lint warnings fail the build.

The first build needs internet to fetch dependencies and Google's version-1 float16
[Hand Landmarker model](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task).
The build verifies SHA-256
`fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`
and bundles the model in the APK. The downloaded model is a generated build asset,
not a repository binary. After a successful build, incremental builds can use
`./gradlew --offline assembleDebug test lint`; `clean` removes the model and needs a
download again. The app itself requires no internet and saves no camera frames.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Test reports: `app/build/reports/tests/`; lint: `app/build/reports/lint-results-debug.html`.
GitHub Actions runs the same checks on pushes and pull requests and retains reports
and a debug APK for seven days. A release signing key is intentionally not configured.

## Install and launch on the Nokia T20

Enable Developer options by tapping **Build number** seven times in Android Settings,
then enable **USB debugging**. Connect a data-capable USB cable, unlock the tablet,
and accept the computer's RSA prompt. In the configured terminal:

```bash
adb devices -l
# Replace SERIAL with the authorised tablet identifier from the list.
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell getprop ro.product.cpu.abilist
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -n dev.gestureos/.MainActivity
adb -s SERIAL logcat -s GestureOS CameraX AndroidRuntime
```

Tap **Start camera**, allow camera access **while using the app**, and ensure Android's
Camera access privacy toggle is on. If denied permanently, use **App permissions**
to allow Camera, then return. **Stop camera** releases capture and inference. The
app also releases them when paused, locked, or backgrounded, and resumes when you
return if you had left tracking enabled. No accessibility, overlay, microphone,
storage, notification, or foreground-service permission is requested.

For wireless debugging, if offered by the tablet's Android build, put the Mac and
tablet on the same trusted Wi-Fi network. In **Developer options → Wireless debugging**,
choose **Pair device with pairing code**:

```bash
adb pair TABLET_IP:PAIRING_PORT
# Enter the displayed pairing code at the prompt.
adb connect TABLET_IP:DEBUGGING_PORT
adb devices -l
```

The debugging port is on the main Wireless debugging screen and usually differs
from the pairing port. Use the resulting `IP:PORT` identifier in place of `SERIAL`
in the installation commands. If pairing/discovery is unavailable, use USB.
See the [official ADB guide](https://developer.android.com/tools/adb).

## Using the test interface

Show **one whole hand at 60–90 cm** in good, even light. Face the palm toward the
camera for the open palm and fist. For thumbs, curl the four fingers and point the
extended thumb vertically toward the top/bottom of the display. Sideways thumbs,
partial hands, and other poses should remain unrecognised. Both hands are supported
individually; only one is tracked at a time.

The large label is the current candidate. **HOLDING** progresses to **CONFIRMED**
after a continuous hold; **COOLDOWN** limits repeat confirmations. An unchanged
pose confirms once. Losing the hand or changing to an unknown pose clears the state.
The raw label bypasses smoothing and hold logic. No gesture confidence is displayed:
the classifier uses geometric rules, not a learned gesture probability.

Diagnostics show processed-result FPS (not camera FPS), inference duration, total
worker processing time (conversion + inference + classification), actual upright
input size, and intentionally throttled frames. CameraX's own dropped frames and
camera-to-display latency are not included. FPS settles after about one second.
Use **Tune recognition** for persisted hold/cooldown durations, smoothing alpha,
inference cap, and straight-finger threshold. Other geometry and MediaPipe detection,
presence, and tracking thresholds are in `RecognitionConfig.kt`.

## Implementation and verification limits

Verified on macOS ARM64 with the local Conda JDK: debug APK compilation, 23 unit
tests in each of the debug and release configurations, and Android lint with zero
issues. APK signature, ZIP alignment, bundled-model checksum, and absence of network
permissions were checked. ADB reported no attached device. No Android runtime or
Nokia T20 behavior is claimed as tested.

- `camera/`: lifecycle-bound CameraX, shared preview/analysis viewport, preferred
  640×480 input, keep-latest backpressure, single worker, default 12 FPS cap.
- `tracking/`: bundled MediaPipe model, one hand, CPU delegate, synchronous VIDEO
  mode on the worker so tracking is retained without async image ownership races.
- `recognition/`: pure Kotlin geometry, aspect-corrected landmarks, smoothing,
  and time-based confirmation/cooldown state. No Android actions are implemented.
- `ui/`: preview overlay uses CameraX transforms for crop, rotation, fit, and front
  mirroring. Frames are not mirrored before classification. Diagnostics and lifecycle
  orchestration live in `MainActivity`.

Automated tests use synthetic landmarks and deterministic timestamps. They cover
the four rules, scale/mirroring, modest noise, ambiguous/invalid input, aspect ratio,
tracking loss, hold/cooldown, and smoothing. **They do not measure real-world accuracy.**
The GitHub workflow must be exercised after pushing. Physical camera behavior,
overlay alignment, native inference, permission flows, and thermal performance
remain unverified until the following tablet checks are completed:

1. Confirm model/API with ADB, install, deny permission once, then grant it. Check
   both permission retry and returning from App permissions.
2. Check front preview and all 21 landmarks, including fingertip alignment at the
   center and edges. Rotate portrait, landscape, and 180°; check fit/letterboxing.
3. At **60, 75, and 90 cm**, test each of the four gestures with each hand. Hold ten
   times per gesture; record correct confirmations, misses, false confirmations,
   FPS and processing times. Try both bright and dim room lighting.
4. Try sideways thumbs, pointing, relaxed hands, rapid pose changes, partial hands,
   two hands, and no hand. Confirm uncertainty and hand loss clear stale feedback.
5. Change tuning, restart, and verify persistence. Stop/start repeatedly; background,
   return, lock/unlock, rotate, and toggle camera privacy. Close other camera apps
   if capture fails; ensure Start camera can recover without stale landmarks.
6. Run for at least 15 minutes while plugged in; compare FPS/inference at the start
   and end and assess warmth. Check `adb -s SERIAL shell dumpsys thermalservice`
   and `adb -s SERIAL shell dumpsys meminfo dev.gestureos` if available.
7. Turn Wi-Fi off after installation and verify local recognition still works.

Acceptance of the physical-device criteria depends on these checks. True screen-off
wake, simulated standby, system-wide input, overlays, and background tracking are
outside Milestone 1 and are not implemented. Next step: run the above checks on the
T20, then calibrate thresholds and the processing cap from the measurements.

The supplied project brief is ignored as local input. SDK paths, signing files,
logs, caches, and build outputs are excluded from Git. Only this README is project
documentation. Upstream references: [CameraX transforms](https://developer.android.com/media/camera/camerax/transform-output),
[MediaPipe Hand Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android),
and [AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
