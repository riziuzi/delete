# Utho (उठो)

A simple app for exam aspirants. Hindi and English.

- **Wake-up alarm** that stops only after the camera counts your exercise: push-ups, squats, jumping jacks or a plank hold.
- **Study timer** with **voice check-ins**: every 15–60 minutes the screen wakes up, listens for 15 seconds,
  writes down what you said (with punctuation) and files that time under one of your subjects.
- **Automatic journal** for each day, written from what the app recorded, in English or Hindi. You add your mood
  and, by voice, a line for tomorrow.
- **Progress**: month calendar, week view, hours per subject, a forgiving streak (2 rest days a month).
- **Profile and settings**: exam, exam date, daily goal, subjects, language, theme, reminders, backup.
- Group study is planned for the next version.

Everything runs on the phone: the pose model (ML Kit) and speech recognition are on-device; nothing is uploaded.

## Code map
- `core/` pure Kotlin logic with unit tests: exercise counters, punctuation, topic tagging, journal writer, stats.
- `data/` storage (one JSON file + SharedPreferences).
- `alarm/` alarms, reminders, check-in scheduling, notifications.
- `voice/` speech recognition wrapper.  `camera/` pose analyzer.  `ui/` screens (Jetpack Compose).

## Build
GitHub Actions builds every push to this branch: unit tests, an emulator tour that screenshots every screen
(pushed to the `ci-output` branch), and a signed `Utho.apk` attached to a release.

The signing key in `keystore/` is deliberately public so any build can update the installed app.
It is only for side-loading; do not use it for a Play Store release.
