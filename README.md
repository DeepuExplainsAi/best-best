# Dkord

Free, open-source Android music app that streams YouTube Music **entirely on-device**.
No backend, no paid APIs, no ads, zero running cost. Licensed GPL-3.0 (NewPipeExtractor is GPL-3.0).

## Features
Search (songs, albums, artists, playlists, live suggestions) · Home with moods, charts and new releases · background playback with media notification, lock-screen and Bluetooth controls · queue, shuffle, repeat, speed, sleep timer · synced lyrics (LRCLIB) · offline downloads as tagged M4A (title, artist, album, cover) with pause/resume that survives app kill · local playlists with JSON import/export · optional YouTube Music sign-in for your liked songs, library and personalised home · dark / light / AMOLED / Material You themes.

## Stack
Kotlin, Jetpack Compose (Material 3), NewPipeExtractor (JitPack), Media3, Room, DataStore, WorkManager, Coil, Koin. Exact versions: `gradle/libs.versions.toml`.

## Install
Every push builds a debug APK: open the **Actions** tab, pick the latest run, download the `dkord-debug-apk` artifact, unzip and install.

## Build
Requires JDK 21 and the Android SDK (platform 37).
```
./gradlew testDebugUnitTest assembleDebug
```

## How playback works (and why it can break)
Streams are resolved on the phone through a chain: NewPipeExtractor, then the YouTube InnerTube ANDROID_VR and IOS clients, then both again with fresh visitor data. Only audio-only streams (m4a/opus) are used. URLs are cached until they expire and re-resolved automatically on HTTP 403.
YouTube changes often (SABR, PO tokens), so extraction can break every few weeks. If a song will not play: **Settings → Troubleshooting → Update extractor** shows whether a newer extractor exists (it ships inside the app, so you update the app), and **Report broken playback** copies the redacted log and opens a GitHub issue. The **Debug log** screen shows what happened.
Clear messages are shown for bot checks, age-restricted, kids-restricted, private and region-locked tracks.

## Signing in (optional)
**Use a secondary Google account, not your main one.** Automated access to YouTube can get an account restricted. Sign-in uses a normal Google page in a WebView; the session cookies are stored only on your device in EncryptedSharedPreferences and sent only to YouTube as a `SAPISIDHASH` header. Nothing is sent to any other server and no cookie or key is in this repository. Sign out in Settings wipes everything.

## Privacy
No analytics, no accounts on our side, no server. The app talks only to YouTube / Google video servers, LRCLIB (lyrics) and GitHub (extractor version check, only when you tap it).

## Languages
English and Hindi are included. Add a language by creating `res/values-xx/strings.xml` and adding it to `res/xml/locales_config.xml`.

## Disclaimer
Not affiliated with YouTube or Google. Use it in line with the laws and terms that apply to you.

## License
GPL-3.0, see [LICENSE](LICENSE).
