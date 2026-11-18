# PushUp Alarm

An alarm that keeps ringing until the camera counts 40 push-ups (target adjustable in the app).

## Build and run
1. Install Android Studio (Ladybug or newer). Open this folder (`PushUpAlarm`) as a project.
2. Let Gradle sync (needs internet once). Plug in your phone (USB debugging on) and press Run.
   Or from a terminal: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`.
3. Open the app, grant Camera + Notification permissions, pick a time, tap **Set alarm**.
   On Android 14+ it also opens a settings page: allow "full-screen intents" so the alarm screen
   appears over the lock screen.
4. Use **Practice** first to check that the camera counts your reps correctly.

## How to place the phone
Landscape, on the floor, 1.5-2 m away, you side-on to it so shoulder, elbow, wrist and hip are
all visible. Good light helps. Front camera is used, so you can see the screen.

## How it works
- `PoseAnalyzer` runs ML Kit Pose Detection (on-device, bundled model) on each camera frame.
- `PushUpCounter` (pure Kotlin) measures the elbow angle: a rep counts when it goes from
  straight (> 150 deg) to bent (< 95 deg) and back to straight. Half reps and standing arm
  movements are ignored.
- `AlarmScheduler` uses `AlarmManager.setAlarmClock` (exact, works in Doze) and re-arms daily
  and after reboot. `AlarmService` plays the sound; `AlarmRingActivity` shows the camera and
  stops the service at the target.

## Safety valve
Bottom-right of the alarm screen: hold the "Emergency stop" for 20 s to silence the alarm if the
camera or model fails. Change the duration in `AlarmRingActivity.kt` (`HoldToStop(20, ...)`).

## Tuning
Thresholds are in `PushUpCounter.kt` (`UP_THRESHOLD`, `DOWN_THRESHOLD`, `MAX_TORSO_DEG`).
The same logic is covered by `app/src/test/.../PushUpCounterTest.kt` (`./gradlew test`).

## Not yet verified
This was written without an Android SDK, so it has not been compiled or run on a device.
Expect to fix small build issues on first sync, and tune thresholds with real footage.
