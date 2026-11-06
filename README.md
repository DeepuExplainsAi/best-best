# Dkord by DeepuGupta

Free, open-source Android music app that streams YouTube Music **entirely on-device**.
No backend, no paid APIs, no ads, zero running cost. Licensed GPL-3.0

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

## Downloads
There is no download button on playlists or albums. Opening a playlist/album and tapping the top-bar download icon saves a **JSON file** (name + tracks) that can be imported again from Library. A single song is downloaded as tagged M4A from its three-dot menu → Download.

## Video (optional)
Audio stays the default. In the full player, **Play video** opens a separate screen with its own player (background audio is paused first, downloads are unaffected). It plays the best *muxed* (video + audio) progressive stream the extractor offers, up to 720p, with subtitle selection (CC button) when subtitle tracks exist, fullscreen/landscape, and retry. YouTube only offers muxed streams up to about 360p, so some videos show "no playable video stream"; blocked, age-restricted or region-locked videos show the matching message. Not verified on a device yet.

## Signing in: not available (and why)
Earlier versions opened Google's login in a WebView and copied YouTube cookies. That is removed. Dkord does not read cookies, spoof a browser, bypass certificate errors or allow cleartext HTTP.

- The signed-in YouTube Music features (liked songs, library, personalised home) use YouTube's private web API, authenticated by browser cookies (`SAPISIDHASH`). Google's official OAuth (Authorization Code + PKCE in Custom Tabs) does not grant access to that API, and Custom Tabs cannot share cookies with the app. The YouTube Data API does not provide the YouTube Music library either. So the feature cannot be done safely and is disabled; the app says so instead of faking a login.
- Guest mode is unchanged: search, playback, queue, downloads, local playlists, lyrics.
- Any manual cookie-paste workflow would need your explicit decision first; none is included.
- Old stored sessions are deleted on first start.

### "Not secure" vs "This browser or app may not be secure"
- **Not secure (TLS/HTTPS warning)**: the connection to a site has a certificate or HTTPS problem. Check the exact URL (must be `https://`), the certificate and redirects, the device date/time, and the network (captive portal, proxy, school/work filter). The fix is never to ignore the error: Dkord keeps certificate validation on and HTTPS enforced.
- **"This browser or app may not be secure"**: Google's refusal to show its sign-in page inside an embedded WebView. The connection is fine; Google blocks the app type. Changing the User-Agent is not a fix, and the supported route is a system browser/Custom Tab with OAuth, which does not apply here as explained above.

## Privacy
No analytics, no accounts on our side, no server. The app talks only to YouTube / Google video servers, LRCLIB (lyrics) and GitHub (extractor version check, only when you tap it).

## Languages
English and Hindi are included. Add a language by creating `res/values-xx/strings.xml` and adding it to `res/xml/locales_config.xml`.

## Disclaimer
Not affiliated with YouTube or Google. Use it in line with the laws and terms that apply to you.

## License
GPL-3.0, see [LICENSE](LICENSE).
