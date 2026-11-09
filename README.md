# GestureOS

Native Kotlin prototype for a wall-mounted **Nokia T20 running Android 13 (API 33)**.
Milestone 1 provides a mirrored front-camera preview, 21 hand landmarks, and local
recognition of open palm, closed fist, thumbs-up, and thumbs-down. **Milestone 2A**
adds foreground gesture control of media volume. **Milestone 2B (0.3.2)** adds an
in-app virtual cursor and pinch selection of six test targets. No accessibility,
system-wide overlay, background camera service, or screen wake is implemented.

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
adb -s SERIAL logcat -s GestureOS GestureOSVolume GestureOSCursor CameraX AndroidRuntime
```

Tap **Start camera**, allow camera access **while using the app**, and ensure Android's
Camera access privacy toggle is on. If denied permanently, use **App permissions**
to allow Camera, then return. **Stop camera** releases capture and inference. The
app also releases them when paused, locked, or backgrounded, and resumes when you
return if you had left tracking enabled. No accessibility, overlay, microphone,
storage, notification, or foreground-service permission is requested. Media volume
uses the normal, install-time `MODIFY_AUDIO_SETTINGS` permission; there is no new
runtime permission prompt or request for Do Not Disturb access.

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
thumb vertically toward the top/bottom of the display, with its tip visibly beyond
the curled fingers. A slight bend toward/away from the camera is supported. Sideways thumbs,
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

## Gesture-controlled media volume (Milestone 2A)

Start the camera, then turn on **Enable gesture volume**. The switch starts off
and switches off when tracking stops, the app pauses, rotation restarts the camera,
or tuning opens. After enabling, release any existing thumb pose and confirm a new
one. Open palm and closed fist remain recognition-only gestures.

- Confirmed thumbs-up raises `STREAM_MUSIC` by one Android volume step; thumbs-down
  lowers it. This changes the tablet's media volume, including media playing in
  another app while GestureOS is visible. Ring, notification, alarm, and call streams
  are not targeted; the app does not request audio focus or start playback.
- Holding the same confirmed thumb repeats after **1200 ms**, then at most once
  every **700 ms**. Tune recognition includes a repeat enable checkbox and persisted
  first-delay (600–5000 ms) / interval (400–3000 ms) settings.
- A **400 ms** global minimum interval and **1500 ms** direction reversal cooldown
  prevent rapid adjustments. The opposite confirmed pose must remain held during
  its cooldown. These guards and frame freshness thresholds are in `VolumeControlConfig.kt`.
- Every action requires a fresh frame with agreement between raw and confirmed
  recognition. Unknown poses, unconfirmed poses, raw disagreement, or observation
  gaps over 350 ms cancel the hold. Frames older than 350 ms are rejected. Re-arming
  needs a new recognition confirmation: release the thumb if the label remains
  confirmed after an interruption. No timer generates volume actions, and no missed
  repeats are replayed after a stall.
- The volume number/bar is read back from Android; it refreshes every second while
  the app is resumed, including changes made with hardware buttons. Feedback reports
  increases, decreases, repeats, minimum/maximum, or rejected/unchanged requests.
- Reaching a limit, fixed-volume output, device policy, a security exception, or an
  ignored adjustment stops further attempts for that hold. Release and confirm again
  to retry. Android's DND and safe-listening/output restrictions are respected; the app
  does not bypass them or request broader access. Behavior varies by audio route and
  must be checked on the T20. See [AudioManager](https://developer.android.com/reference/android/media/AudioManager#adjustStreamVolume(int,%20int,%20int)).

The app stores repeat settings, not the enabled switch state. An app pause/resume or
Stop/Start therefore requires re-enabling volume control. In-app feedback is used;
there is no floating system-wide volume overlay.

## Virtual cursor and pinch selection (Milestone 2B)

The left pane shows the mirrored preview and landmarks. The right pane contains six
native, selectable test buttons. Start the camera and enable **Enable in-app cursor**.
Cursor and gesture-volume modes are mutually exclusive. Enabling either switches the
other off. Both switch off on pause, camera stop/restart, rotation, and either tuning
dialog. Their enabled state is never persisted. Camera and existing recognition
continue while cursor mode is active, with the palm/fist/thumb diagnostics below
the cursor diagnostics.

- **Point** by extending the index finger with the thumb and three other fingers curled.
  Move the index tip inside the green rectangle on the preview. Its absolute position
  maps to the whole test area. The cursor follows the mirrored preview direction.
- **Select** by pointing for 200 ms to arm, aiming at a target, and bringing thumb tip
  to index tip. The pointer freezes as the fingers approach so the closing movement
  cannot drag the click. An armed pointer can remain frozen for up to 800 ms while
  the index bends before contact, after which fresh pointing is required. This
  transition window is configurable in `CursorConfig.kt`. The other three fingers
  may relax during the armed pinch. Hold contact through 80 ms debounce plus 200 ms
  confirmation.
  One confirmed pinch invokes that target's normal in-app click handler. Holding
  the pinch never repeats. The selected target turns green and the selection count
  increments. Pinching between targets reports a miss. Selection is confined to
  the six test buttons.
- **Re-arm** by separating the tips for at least 150 ms and pointing again for 200 ms.
  A 450 ms click cooldown also applies. Starting with a pinch, a brief pinch, unrelated
  poses, missing/partial/invalid hands, stale/duplicate frames, large wrist jumps,
  and observation gaps cannot complete a pending click. Tracking loss hides the cursor
  and requires fresh pointing. A 100 ms watchdog cancels frames older than 350 ms.
- Pinch separation uses the 3D thumb-tip/index-tip distance divided by palm width.
  Entry is **0.30 palm widths** and release is **0.45**. The gap provides hysteresis.
  Crossing the release threshold aborts an unfinished click immediately, even if
  the fingers close again before the release debounce completes. Contact tucked
  into the palm is rejected. These geometric thresholds estimate fingertip contact.
- **Tune cursor** persists sensitivity, smoothing time, dead zone, all four active
  region edges, pinch entry/release, pointing arm duration, pinch debounce/confirmation,
  release debounce, and click cooldown. Defaults use the middle 70% of each visible
  preview dimension, sensitivity 1.0, an 85 ms time-based smoothing filter, a 0.004
  normalized dead zone, and a bounded movement speed. A smaller region or higher
  sensitivity reduces hand travel. Smoothing trades responsiveness for steadiness.
  Re-enable the desired action mode after tuning.
- Mapping uses the same CameraX transform as the landmarks, accounting for the shared
  crop, upright rotation, preview aspect ratio and front-camera mirroring once. The
  active region excludes letterbox bars. Output is clamped to the interactive area,
  with the cursor inset from its edges. A size/rotation change clears the old position.
- Diagnostics show `IDLE`, `POINTING`, `PINCHING`, `CLICK_CONFIRMING`, `CLICKED`, or
  `TRACKING_LOST`, normalized cursor coordinates, arming status, measured pinch ratio,
  confirmation progress, and click feedback. Existing processed FPS, inference and
  pipeline timings remain. **Analysis → cursor draw** measures monotonic time from
  analyzer entry to the test view's drawing pass, with a rolling mean of up to 30
  rendered updates. It includes inference, main-thread delivery and draw scheduling. Sensor exposure,
  pre-analyzer delay, GPU presentation and display scanout are outside this measurement.

### Nokia T20 cursor test

Use one whole hand at **60–90 cm** in even light. The initial defaults require physical
calibration and repeated target-selection checks at that distance.

1. Install the debug APK using the ADB commands above. Confirm preview and landmarks
   align, both action switches start off, and all six target buttons are visible.
2. Enable the cursor and confirm volume switches off. Point and move left/right,
   up/down, diagonally, and to every active-region edge. Check direction, full-area
   reach, clamping and steadiness. Test both hands separately in portrait and landscape.
3. Aim at each target and pinch briefly to confirm. Check exactly one selection and
   a stable cursor during closing/confirmation. Hold a pinch for five seconds and check that the
   count must remain unchanged after the first click. Release, point, and pinch again.
4. Try short contacts, starting with a pinch, small separations in the threshold band,
   a closed fist, open palm and vertical thumbs. None should generate unintended
   selections. Remove/reintroduce the hand during confirmation: no delayed click.
5. Try sensitivity, smoothing and region changes and re-enable afterward. Restart the
   app and verify settings persist with both action modes off. Background, lock,
   rotate and stop/start during a pinch and confirm cancellation and fresh re-arming.
6. Enable gesture volume and confirm cursor mode switches off. Recheck thumbs-up/down and
   held repeats. Enable cursor again and verify that pointing/pinching leaves media volume steady.
7. At both 60 and 90 cm, record target hits/misses, false selections, processed FPS,
   inference time and analysis-to-draw mean during a 15-minute run. Test the lighting
   and wall-mounted angle you intend to use. Use `GestureOSCursor` logs for state
   transitions and selections.

## Implementation and verification limits

Version **0.3.2 / Milestone 2B** passed the debug APK build, **99 unit tests in each
of debug and release** (31 recognition, 28 volume, 40 cursor), and Android lint with
**zero issues**, using the local Conda JDK. APK signature, 16 KB ZIP alignment,
bundled-model checksum, and absence of network/overlay/service permissions were
checked. No dependencies were added. The GitHub workflow has not been run remotely.

On **8 November 2026**, ADB installation and cold launch of 0.3.0 succeeded on the
Nokia T20. The preview and all six targets rendered without a startup exception.
A no-hand snapshot showed 9.9 processed FPS, 96.2 ms inference and 101.7 ms pipeline
time at 640×480. The user reported correct cursor movement but unreliable pinch
selection. Logs captured several completed pinch selections, and a screenshot
showed a 97 ms mean analysis-to-draw latency across the last 30 updates. These are
initial observations. The failing transition showed the index bending before tip
separation reached the pinch threshold. Version 0.3.1 adds the bounded 800 ms
transition window and regression coverage for that sequence. Temporary measurement
logging has been removed. On **9 November 2026**, the user reported improved
selection with 0.3.1 but some remaining failures. Version 0.3.2 permits the other fingers to relax during an
armed pinch and calibrates the default entry/release ratios to 0.30/0.45. Saved
custom thresholds are preserved. Version 0.3.2 installed and cold-launched on the
T20. The user confirmed consistent selections and one click per held pinch. Device
logs captured selections on all six targets. UI checks verified that each action
mode switches the other off, and Home/return disarms cursor interaction while the
camera resumes. Volume increases, decreases and held repeats were also logged
while volume mode was enabled. Broader checks for rotation, lock/unlock, settings
persistence, lighting, both hands, and sustained accuracy at 60 and 90 cm remain
in the device checklist above.

Initial Nokia T20 test, **8 November 2026**: ADB verified Android 13 / API 33 and
ARM64 support; installation and cold launch succeeded. The front-camera preview
and native inference ran on the tablet, and the user confirmed visible hand
landmarks. In the guided test at approximately 60–90 cm, the user reported that
all four gestures showed the correct label and reached CONFIRMED. Logcat also
captured open-palm, thumbs-up, and thumbs-down confirmations; the fist result is
user-reported. One no-hand snapshot showed 10.0 processed FPS, 90.7 ms inference,
and 95.8 ms total pipeline time at 640×480. These are initial observations, not
a sustained performance or accuracy benchmark.

- `camera/`: lifecycle-bound CameraX, shared preview/analysis viewport, preferred
  640×480 input, keep-latest backpressure, single worker, default 12 FPS cap.
- `tracking/`: bundled MediaPipe model, one hand, CPU delegate, synchronous VIDEO
  mode on the worker so tracking is retained without async image ownership races.
- `recognition/`: pure Kotlin geometry, aspect-corrected landmarks, smoothing,
  and time-based confirmation/cooldown state, independent of Android actions.
- `volume/`: pure Kotlin repeat/reversal state machine and media-volume controller,
  plus an `AudioManager` adapter. Unit tests use a fake audio device to exercise limits
  and restrictions without changing the development machine's audio.
- `cursor/`: pure Kotlin pointing geometry, palm-relative pinch measurement, affine
  mapping, time-based pointer filtering, target hit testing and interaction state machine.
- `ui/`: local cursor test buttons and tuning, draw-time latency measurement; preview overlay uses CameraX transforms for crop, rotation, fit, and front
  mirroring. Frames are not mirrored before classification. Diagnostics and lifecycle
  orchestration live in `MainActivity`.

Automated tests use synthetic landmarks, captured T20 hand-pose regression fixtures,
and deterministic timestamps. They cover
the four rules, scale/mirroring, modest noise, ambiguous/invalid input, aspect ratio,
tracking loss, hold/cooldown, and smoothing. **They do not measure real-world accuracy.**
The user has subsequently confirmed Milestone 1 successfully tested on the T20.
Milestone 2A was subsequently exercised on the T20: the user reported working
gesture volume, and device logs/screen feedback confirmed increases, decreases,
and held repeats. Thumbs-down was less reliable. Captures identified two causes:
tucked index/middle fingers can have straight middle joints but bent fingertip joints,
and the straight-thumb angle check rejected a thumb bent toward/away from the camera.
The user reported that the finger-curl-only change in 0.2.1 still labelled bent
thumbs as fists. Version **0.2.2** checks both finger joints and uses visible thumb-tip
clearance beyond all four curled fingers for directional gestures (default 0.2 palm
widths, configurable in `RecognitionConfig.kt`). Vertical direction, projected
length, and reach are still required. Open-palm geometry, hold timing, and volume
safeguards are unchanged. Regression tests reproduce the old bent-thumb failure and
cover captured fists, mirroring, scale, inversion, and synthetic bends in both depth
directions. These samples are regression checks, not an accuracy benchmark.
Temporary coordinate logging used for diagnosis is removed from the delivered APK.
Version 0.2.2 installed and cold-launched successfully on the T20. In the guided
retest, the user confirmed that bent thumbs-down improved and thumbs-up and closed
fist stayed correctly labelled. The user also confirmed bent thumbs-down lowered
volume with spaced repeats and stopped on release/change of pose. Device logs
recorded increases and decreases, with an approximately 1.2-second initial repeat
delay and 0.7-second subsequent intervals. Routing, restrictions, limits, sustained accuracy,
and the GitHub workflow are not yet comprehensively verified. Complete these checks:

1. Start at a comfortable mid-range media volume. If desired, play familiar media
   in another app that supports background playback, then return to GestureOS.
   Start the camera and confirm volume gestures are initially off. Thumbs must not
   alter volume until the switch is enabled.
2. Enable gesture volume, release the hand, and at **60–90 cm** confirm thumbs-up,
   then release and confirm thumbs-down. Check one media step in each direction,
   the displayed level/action, and the audible change if media is playing.
3. Hold thumbs-up: check one initial step, a roughly 1.2-second pause, then roughly
   0.7-second steps. Remove the hand or change to palm/fist: changes must stop. Test
   a brief/unconfirmed thumb and rapid direction changes; neither should cause a burst.
4. In Tune recognition, disable repeats. Re-enable volume afterward and check a
   long hold produces only one step. Change repeat timing, restart the app, and check
   the timings persist while volume control remains off until enabled.
5. With playback stopped, test minimum and maximum. Verify the limit feedback and
   no repeated adjustment attempts while the same thumb stays held. Release and
   confirm the opposite thumb to move away from the limit.
6. Disable the switch, Stop/Start, background/return, lock/unlock, rotate, and open/
   cancel tuning. Check adjustments stop, the switch is off, and re-enabling requires
   a fresh confirmation. Check camera/landmark alignment and recognition diagnostics
   still work in portrait and landscape. Scroll the lower panel for all controls.
7. Press the tablet's volume buttons: the displayed media level should update within
   about one second. Test each output you use (speaker, wired, Bluetooth); check
   muted/fixed-volume behavior, DND, and any device-policy/safe-volume restrictions.
   Restrictions should show feedback and never crash or repeatedly retry while held.
8. Run for 15 minutes and compare processed FPS/inference timing with Milestone 1.
   Capture `GestureOSVolume` logs with the ADB command above when reporting issues.

True screen-off wake, simulated standby, system-wide input, overlays, and background
tracking remain outside the implemented milestones. Verify Milestone 2B on the T20
before extending interaction beyond this app.

The supplied project brief is ignored as local input. SDK paths, signing files,
logs, caches, and build outputs are excluded from Git. Only this README is project
documentation. Upstream references: [CameraX transforms](https://developer.android.com/media/camera/camerax/transform-output),
[MediaPipe Hand Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android),
and [AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).
