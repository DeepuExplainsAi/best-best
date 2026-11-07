# Dkord by DeepuGupta

Free, open-source Android music client. YouTube Music search, stream resolution and playback run on the phone; no custom paid backend is included. Optional Listen Together rooms use a public relay. Licensed GPL-3.0.

## Features
Search songs, albums, artists and playlists · Home with moods, charts and collapsed-by-default New Releases · background audio with notification, lock-screen and Bluetooth controls · music-only related-radio autoplay · queue, shuffle, repeat, speed and sleep timer · synced lyrics (LRCLIB) · tagged M4A downloads with pause/resume · local playlists with JSON import/export · song sharing · Listen Together rooms · compact in-app updates · dark / light / AMOLED / Material You themes.

## Install and updates
Download the APK from [the latest stable release](https://github.com/DeepuExplainsAi/dkord/releases/latest). Official updates use the same package/signing identity: install over the existing app, **without uninstalling**, to retain playlists, likes, downloads and settings. Uninstalling generally removes local app data.

Home shows a small **Update / Later** notice, not a blocking dialog. Later defers that version for 24 hours; a different newer version can still appear. Foreground checks are throttled to 30 minutes. Settings also provides a manual check and live downloaded / total / remaining MB. APKs are downloaded only after consent, and `/releases/latest` is resolved again before the download, so skipped releases do not require intermediate installations. An old ready APK does not block newer-release checks.

The download is bounded and checked for size and optional release SHA-256. Before installation, the app verifies the APK's package, advertised version, increasing versionCode and installed signing certificates. Android's system installer/unknown-source permission UI completes installation. Downloads share app-lifetime state across screens/rotation; this is **not** a promise of update-download continuation after process death.

## Languages
The app interface defaults to **English even when the phone uses another language**. Choose **Settings → Appearance → App language → English / Hindi** to change it explicitly. Android 13+ per-app language choices are also honored; resetting the app-language override returns to the app's English default. Original song/artist metadata is not translated, and selecting an interface language retains the device region used for music discovery. Android-owned permission, installer and file-picker screens are controlled by Android.

English and Hindi are the translations actually included. To add a supported language, provide a complete `res/values-xx/strings.xml`, register its `AppLanguage`/Settings labels and `res/xml/locales_config.xml` entry, and extend the translation contract tests. Do not expose untranslated language options.

## Listen Together
1. The host starts a song, opens **Listen Together** from the full-player menu, and taps **Create room**.
2. Use **Copy code** or **Share** to send the ten-character code to everyone listening.
3. Each friend opens Listen Together, enters/pastes the same code, and taps **Join room**, then **Sync & play**.
4. Turn on **Follow host** to follow later song/playback changes. The host uses **Open player** to control the music. Multiple friends can use the same room code.

Going back does not leave the room; **Leave room** stops sharing/following without stopping ordinary local playback. Keep Dkord running: room sessions are app-process-lifetime, not persistent accounts or a guaranteed always-on room service. Reconnection resumes from the last received cursor, with backoff for network errors/rate limiting. Independent buffering/network/device clocks mean synchronization is approximate, not sample-accurate.

### Room transport and privacy (developer details)
The current transport is HTTPS to the **public ntfy.sh relay**, with a twelve-hour cache query. Room payloads contain video ID/title/artist/thumbnail, playback position/state/timestamp and at most ten upcoming tracks. They never contain audio streams, YouTube cookies or account credentials. Each phone resolves its own audio.

Room messages are **not end-to-end encrypted**, and anyone who knows the code can read or publish to its topic. There is no authenticated host identity or participant-presence/count protocol; the app does not invent these. Do not share a code publicly. Public-relay availability and free-service rate limits can affect room delivery. Live two-device/device compatibility has not been verified by the source/static checks below.

## New Releases
The Home section stays collapsed until opened. Categories: All / Hollywood / Bollywood / **Choliwood** / Punjabi / Tamil / Telugu. Windows: last 24 hours / 7 days / 10 days. Discovery expands music albums and uses music-song searches; publication/upload metadata determines recency, **not a guarantee of the original commercial release date**. Unknown dates are not guessed; date/network failures have retryable states instead of pretending the catalogue is empty. Tapping a song retains the full shelf as the queue and existing related autoplay.

## Build and regression checks
Stack: Kotlin, Jetpack Compose (Material 3), AppCompat application locales, NewPipeExtractor, Media3, Room, DataStore, WorkManager, Coil and Koin. Exact versions are in `gradle/libs.versions.toml`. Android builds require JDK 21 and SDK platform 37.

```sh
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py' -v
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Both GitHub Actions workflows are **manual-only**; a push does not build or publish:
- **Build debug APK** runs the Python regressions, full Android unit suite and debug build, then uploads `dkord-debug-apk`. Without the existing signing secrets, a developer debug APK cannot update an official install.
- **Release APK** takes a tag matching `versionName` (currently `v0.6.0`), runs the Python and full Android unit suites/build, requires the existing stable key (`DKORD_KEYSTORE_B64`, `DKORD_KEYSTORE_PASS`, alias `dkord`), and checks the actual old/new APK signatures, package and increasing versionCode. Only then does it create the tag/release page and attach the APK. No previous release is overwritten or removed. Start a **fresh run on the prepared branch**, not a re-run of old jobs.
- Both workflows preserve Android unit-test reports as an artifact, including failed runs where reports exist. Resource/source/workflow checks are not a substitute for Android compilation, UI/device tests or a real multi-phone room session. Current validation/execution status is recorded in `HANDOFF.md`; pending tests are not claimed as passing.

## How playback works (and why it can break)
Streams are resolved on the phone through a chain: NewPipeExtractor, then the YouTube InnerTube ANDROID_VR and IOS clients, then both again with fresh visitor data. Only audio-only streams (m4a/opus) are used. URLs are cached until they expire and re-resolved automatically on HTTP 403.

YouTube changes often, so extraction can break. **Settings → Troubleshooting → Update extractor** checks the bundled extractor's upstream version; updating the extractor requires an app update. **Report broken playback** copies a redacted log and opens a GitHub issue; **Debug log** shows recent events. Bot checks, age/kids restrictions, private/region-locked/unavailable tracks and network errors have explicit messages. Guest mode does not bypass account/age restrictions.

## Downloads
A single song downloads as tagged M4A from its three-dot menu → Download. A playlist/album's top-bar export saves a **JSON file** (name + tracks), not all of its audio; import it again from Library. Normal music downloads use WorkManager and support pause/resume across app restarts independently of the in-app APK updater.

## Video (optional)
Audio stays the default. **Play video** opens a separate player and pauses background audio; downloads are unaffected. It selects a muxed progressive stream up to 720p, with subtitles when available, fullscreen/landscape and retry. YouTube often offers muxed streams only up to about 360p. Blocked/unavailable videos show an error rather than an invented stream. Device validation remains required.

## Signing in: unavailable
Cookie-copy WebView sign-in is removed. Signed-in YouTube Music library/personalization relies on private cookie-authenticated APIs; official Google OAuth does not provide that library access. The app states that sign-in is unavailable rather than faking it, and old stored sessions are cleared. Anonymous search, radio, playback, downloads, local playlists and lyrics remain available. No cookie-paste workflow or TLS/certificate bypass is included.

## Network/privacy
No analytics and no Dkord account server. The app contacts YouTube/Google for music/media, LRCLIB for lyrics, and GitHub for foreground app-update checks and explicit extractor-version checks. Only creating/joining a room starts ntfy room traffic; leaving cancels its jobs. TLS validation stays enabled. The room privacy limitations above apply even though their technical explanation is no longer displayed as a large paragraph on the main room screen.

## Disclaimer and license
Not affiliated with YouTube or Google. Use the app in line with applicable laws and service terms. GPL-3.0; see [LICENSE](LICENSE).
