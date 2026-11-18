# Utho (उठो)

A simple app for exam aspirants. English or Hinglish (Hindi in English letters).

- **Wake-up alarm** that stops only after the camera counts your exercise: push-ups, squats, jumping jacks or a plank hold.
- **Study timer** with **voice check-ins**: every 15–60 minutes a pop-up appears (also over the lock screen) and
  listens for 15 seconds. It writes what you said in Hinglish with punctuation, picks the subject from your list and
  your mood by itself, then closes. If you say nothing it records "subject not selected, mood not said".
  The same pop-up opens from **Speak** on the home screen and in the journal.
- **Automatic journal** for each day, written from what the app recorded, in English or Hinglish.
- **Progress**: month calendar, week view, hours per subject, a forgiving streak (2 rest days a month).
- **Profile and settings**: exam, exam date, daily goal, subjects, language, theme, reminders, backup.
- Group study is planned for the next version.

Everything runs on the phone: the pose model (ML Kit) and speech recognition are on-device; nothing is uploaded.

## Code map
- `core/` pure Kotlin logic with unit tests: exercise counters, punctuation, Devanagari→Hinglish, topic and mood
  detection (also for English words the Hindi recogniser writes in Devanagari), journal writer, stats.
- `data/` storage (one JSON file + SharedPreferences).
- `alarm/` alarms, reminders, check-in scheduling, notifications.
- `voice/` speech recognition wrapper.  `camera/` pose analyzer.  `ui/` screens (Jetpack Compose).

## Build
GitHub Actions builds every push to this branch: unit tests, an emulator tour that screenshots every screen
(pushed to the `ci-output` branch), and a signed `Utho.apk` attached to a release.

The signing key in `keystore/` is deliberately public so any build can update the installed app.
It is only for side-loading; do not use it for a Play Store release.
