# HANDOFF (read this first if you are a new AI/dev)

App: **Dkord**, package `app.dkord`, repo `best-best` (DeepuExplainsAi). User speaks Hinglish/Hindi; reply in Hinglish.
Rules: all on-device, no backend, no secrets committed, GPL-3.0. Work in 5 parts; after each part: update this file, commit, push, update this file again; then continue automatically without asking.
Default app language English; many translations (values-xx) planned in Part 5.
UI should look like YouTube Music (dark, red accent, chips, mini player).

## Verified versions (2026-11-05)
AGP 9.5.0, Kotlin 2.4.21, KSP 2.3.13, Compose BOM 2026.09.00, Media3 1.11.1, NewPipeExtractor v0.26.5 (JitPack), desugar_jdk_libs_nio 2.1.5, Koin 4.2.2 (chosen over Hilt), Coil 3.6.3, Room 2.8.5, WorkManager 2.12.0, Gradle 9.8.2, compileSdk/targetSdk 37, minSdk 26.

## Environment notes
- gh token now has `workflow` scope; git credential helper = `gh auth git-credential`. Use `GIT_TERMINAL_PROMPT=0 git push`.
- Git identity set locally to DeepuExplainsAi noreply.
- Sandbox has 1 CPU / ~500MB RAM: cannot run Gradle builds locally. Builds are verified through GitHub Actions (`gh run watch`).
- JDK 21 installed via apt; `mkdir -p /usr/share/man/man1` was needed. `sh` has no brace expansion.

## Progress
- [x] Part 1: setup + CI. Pushed to main; CI green, artifact `dkord-debug-apk` uploaded.
- [x] Part 2: StreamResolver, search, login + auth. CI green (13 unit tests + APK). Pushed.
- [ ] Part 3: player service
- [ ] Part 4: downloads, Room, playlists
- [ ] Part 5: UI, settings, translations, polish

## Test after Part 1
Actions tab: "Build debug APK" is green and artifact `dkord-debug-apk` installs and shows a placeholder text.

## Part 2 summary (what exists)
- `data/stream/`: `StreamResolver` interface; chain = NewPipeResolver -> InnerTube ANDROID_VR -> IOS -> ANDROID_VR+fresh visitor -> IOS+fresh visitor (`Resolvers.kt` `StreamModule`). `CachedStreamResolver` caches until URL expiry (60s margin); call `StreamModule.invalidate(videoId)` on HTTP 403 (Part 3 must do this). `AudioPicker` = audio-only m4a/opus, never video. `StreamError` = BotCheck / AgeRestricted / MadeForKids / Geo / Private / Unavailable / Network / NoAudio, with user-facing messages. Client identities live in `ClientProfile` (first place to edit when YouTube breaks).
- `data/yt/`: `InnerTubeClient` (WEB_REMIX, adds SAPISIDHASH headers when logged in), `MusicParser` (pure Kotlin, tested on real fixtures in `app/src/test/resources/fixtures`), `MusicRepository` (search/suggestions via NewPipe; home/explore/charts/moods/album/artist/playlist/library via InnerTube).
- `data/auth/`: `SapisidHash`, `AuthStore` (EncryptedSharedPreferences, `captureFromWebView`, `logout`). `ui/LoginScreen.kt` = WebView login with desktop Chrome UA.
- `core/DebugLog` (ring buffer, redacts cookie/signature patterns) and `data/stream/ExtractorHealth` (upstream version check, report body) are ready for the Settings/Debug screens in Part 5.
- `MainActivity` is a TEMPORARY Part 2 test harness; Part 5 replaces it.
- DI is Koin (`di/AppModule.kt`). NewPipe is initialised in `DkordApp`.

## Verified live from the sandbox on 2026-11-05 (no login)
- NewPipe default path: OK for normal videos. ANDROID_VR and IOS clients return audio URLs (itag 140 m4a, 251 opus); URLs download with HTTP 206 under any User-Agent; ~6h expiry.
- Bot-check videos stay blocked on every client without login (expected; this IP is a datacenter IP). Age-restricted -> AgeRestricted error. Chain returns the most informative error.
- Search (songs/albums/artists/playlists) and suggestions work. InnerTube home/explore/moods/charts/album/artist/playlist parse correctly.
- Dead clients (do NOT re-add): ANDROID_MUSIC, WEB_EMBEDDED, TV_EMBEDDED (all error without PO token/login).

## NOT verified (needs a real phone + secondary Google account)
- WebView login flow, cookie capture, SAPISIDHASH accepted by YouTube, `likedSongs()/libraryPlaylists()` parsing for a logged-in account (parser reuses the same renderers but no fixture exists), `account()` header parsing.
- Whether logged-in sessions fix bot-check/age-restricted playback (stream URLs still come from non-authenticated clients; Part 3 may pass cookies to NewPipe/Downloader if needed).

## Test after Part 2 (on phone, install CI artifact)
1. Search a song -> "N results". 2. "Resolve first" -> "OK via newpipe: audio/webm ...". 3. Sign in with a SECONDARY account -> returns to app, button becomes "Log out". 4. "Load liked songs" shows a count. 5. Log out clears. Send the log lines if anything fails.

## Environment notes (added)
- Local JVM test loop works: kotlinc 2.4.21 in /tmp/kotlinc, jars in /tmp/npe (may vanish if sandbox resets). Needs `/swapfile` (1.5G) and `JAVA_OPTS=-Xmx700m`. Gradle itself cannot run here; use `gh run watch`.
