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
- [x] Part 3: player service. CI green (20 unit tests + APK). Pushed.
- [x] Part 4: downloads, Room, playlists. CI green (tests + APK). Pushed.
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

## Part 3 summary (what exists, `app/src/main/java/app/dkord/player/`)
- `PlaybackService` (MediaSessionService, foregroundServiceType=mediaPlayback): ExoPlayer with audio focus (handleAudioFocus=true), becoming-noisy handling, wake mode network. Media3 builds the notification/lock-screen/Bluetooth handling. `MediaButtonReceiver` is declared so headset buttons restart playback after process death (uses `onPlaybackResumption` + `QueueStore`).
- Tracks are queued as `dkord://track/<videoId>`. `StreamUriResolver` resolves the real URL when ExoPlayer opens it (always fresh), sets cache key `<id>|<itag>`. `OpenRangeResolver` adds `Range: bytes=0-` for open reads (VERIFIED: without it googlevideo throttles to ~30KB/s). Data source chain: Resolving(dkord->url) -> CacheDataSource(AudioCache, 512MB LRU) -> Resolving(open Range) -> OkHttp.
- 403/410 -> `StreamModule.invalidate(id)` + one automatic re-prepare per track (`urlRetryFor`). Other failures -> `PlaybackEvents.failures` (SharedFlow of `PlaybackFailure` with `PlaybackProblem` enum; UI maps to strings, `err_*` strings exist) and auto-skip to next track (max 3 in a row, not for NETWORK).
- `PlayerController` (Koin singleton): lazy MediaController connection, `play/playNext/addToQueue/move/remove/skipTo/seek/shuffle/repeat/speed/sleep`, `state: StateFlow<PlayerUiState>`. Position polled every 500ms while playing.
- `SleepTimer` (countdown with 8s volume fade + end-of-track via `setPauseAtEndOfMediaItems`), `SavedQueue`/`QueueStore` (SharedPreferences JSON, windowed to 500 tracks).
- POST_NOTIFICATIONS requested in `MainActivity` on API 33+. Part 5 should replace the ask with a proper rationale UI.
- Not done in Part 3 (intentionally): downloaded-file playback (Part 4 must make `StreamUriResolver` return local files for downloaded ids), audio-quality setting UI (`StreamConfig.quality` exists), cache size setting (AudioCache ctor takes maxBytes; Part 5 should add a setting + clear via `AudioCache.clear()`).

## NOT verified on a device (Part 3)
Everything in `player/` is compile-checked and the pure logic is unit-tested, but never run: notification appearance, lock-screen, Bluetooth buttons, audio focus on calls, background kill/restore, 403 recovery. See test list below.

## Test after Part 3 (phone)
1. Search -> Play all: audio starts within ~2s. 2. Lock screen shows controls with title/artist/art; pause/next work. 3. Press home, wait 5 min: still plays. 4. Swipe app from recents while playing: music continues; pause then swipe: service stops. 5. Bluetooth/headset play-pause, unplug headphones pauses. 6. Incoming call pauses and resumes after. 7. Shuffle/Repeat/Speed buttons, "Sleep 1m" fades out and pauses after ~1 min. 8. Kill app, press headset play: last queue resumes. 9. Airplane mode mid-song: error is shown, no crash. Rotate screen: playback continues. Send DebugLog lines on failure.

## Environment notes (Part 4 session)
- Sandbox can reset mid-session (BRIDGE_OFFLINE): re-run `git config user.name/email` and `gh auth setup-git` before pushing.
- Python heredocs sometimes hang the tool: write the script to /tmp/x.py first, then run it. Verify with `git status`/grep after.

## Part 4 summary (what exists)
- `data/db/`: Room `AppDatabase` v1 (exportSchema=false): tracks, playlists, playlist_items (position), liked, history, downloads (state QUEUED/RUNNING/PAUSED/DONE/FAILED, bytes/total/mime/localUri). DAOs in `Daos.kt`.
- `data/library/LibraryRepository`: playlist CRUD, add (dedupe)/remove/move, liked toggle, history (kept 300; PlaybackService records every played item), `exportJson/importJson` using `PlaylistJson` (`LibraryDoc`, app="dkord", version 1; rejects foreign files).
- `data/settings/SettingsRepository` (DataStore, `settings: StateFlow<Settings>`): quality, theme (SYSTEM/LIGHT/DARK/AMOLED), dynamicColor, downloadTree (SAF uri), cacheMb. `StreamConfig` now reads quality live from it. NOTE: AudioCache max size is read once at process start; a changed cacheMb applies on next app start.
- `data/download/`: `DownloadManager` (enqueue/pause/resume/pauseAll/resumeAll/remove/localUri, `downloads()` Flow), `DownloadWorker` (CoroutineWorker, foreground dataSync, one track at a time, unique work "downloads" APPEND_OR_REPLACE, resets RUNNING->QUEUED on start), `TrackDownloader` (resolve preferM4a, Range resume only when mime equal, 403 -> invalidate + retry, 4 attempts), `Mp4Tagger` (pure Kotlin: title/artist/album/cover in moov/udta/meta/ilst, shifts stco/co64; unit-tested), `DownloadStorage` (app Music/Dkord folder or SAF tree; `keepAccess(uri)` must be called after the folder picker).
- Offline playback: `StreamUriResolver` takes `localUri(id)`; `SchemeRoutingDataSource` sends file/content uris to DefaultDataSource, remote to cache chain. Downloaded files whose file was deleted are dropped from DB on next play.
- Manifest: FOREGROUND_SERVICE_DATA_SYNC + SystemForegroundService type merge. Webm (opus) fallback downloads are saved untagged as .webm.
- Tests: `Part4Test` (tagger, names, JSON). Not unit-tested: Room DAOs, worker (need device).

## NOT verified on a device (Part 4)
Download notification, pause/resume mid-file, kill app during download then resume, SAF folder output, offline playback in airplane mode, tags visible in a file manager/music player.

## Test after Part 4
UI for this arrives in Part 5, so test then: download 2 songs, pause one, kill app, reopen -> resumes; airplane mode -> downloaded song plays; check file in Music/Dkord has title+cover in another player.
