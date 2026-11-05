# Dkord

Free, open-source Android music app that streams YouTube Music **entirely on-device**.
No backend, no paid APIs, no ads. Licensed GPL-3.0 (NewPipeExtractor is GPL-3.0).

> Status: under construction. See [HANDOFF.md](HANDOFF.md) for progress.

## Stack
Kotlin, Jetpack Compose (Material 3), NewPipeExtractor, Media3, Room, DataStore, WorkManager, Coil, Koin.

## Build
Requires JDK 21 and the Android SDK (platform 37).

```
./gradlew assembleDebug
```

Every push builds a debug APK in GitHub Actions (artifact `dkord-debug-apk`).

## Languages
English is the default; other languages follow the system or the per-app language setting (Android 13+).

## License
GPL-3.0, see [LICENSE](LICENSE).
