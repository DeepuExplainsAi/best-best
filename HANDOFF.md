# HANDOFF (read this first if you are a new AI/dev)

App: **Dkord**, package `app.dkord`, repo `dkord` (DeepuExplainsAi). User speaks Hinglish/Hindi; reply in Hinglish.
Rules: all on-device, no owned backend, no secrets committed, GPL-3.0. Part 12 exception: optional Listen Together uses the public ntfy.sh relay behind RoomTransport; it sends only music metadata/queue/playback timing, never audio, account data, cookies or tokens. Anyone with the code can read/publish; this is not end-to-end encrypted. For Part 12 work sequentially A–F; after each part: update this file, commit, push, update this file again; then continue automatically without asking.
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
- [x] Part 5: UI, settings, lyrics, debug log, Hindi, README. CI green. Pushed. Independent review done; its fixes applied.
- [x] Part 6: branding "Dkord by DeepuGupta" header, remove download buttons (single-song download stays in the 3-dot track menu only), playlist/album "download" = export JSON file (no MP3s). CODE DONE (ExportJsonButton in top bar of Detail + local lists; Download buttons removed; track 3-dot menu keeps Download for single songs). CI result: check `gh run list`.
- [x] Part 7 (CI green): video. `data/stream/VideoResolver` (NewPipe muxed progressive + subtitles, separate from audio chain/cache), `VideoPicker`/`SubtitlePicker` (tested in Part7Test), `ui/player/VideoScreen` (own ExoPlayer + media3-ui PlayerView, pauses audio first, fullscreen=landscape, retry, error states; `Dest.Video`). Entry: "Play video" button in FullPlayer. NOTE: muxed streams on YouTube max ~360p (itag 18); adaptive-only videos show 'no video stream'. Not device-verified.
- [x] Part 8 (CI green): WebView login + LoginScreen + cookie capture REMOVED. Decision: OAuth cannot reach YouTube Music library/home (needs web cookies), so sign-in is disabled honestly (Settings item + Library text `login_unsupported`); `AuthStore` now only deletes the old stored session; `CookieSource` always null so InnerTube is anonymous. README has Video, Downloads, sign-in limitation and TLS-vs-embedded-browser sections. Manual cookie workflow NOT added (ask user first).
- [x] Part 9 (user feedback): removed sign-in text/items; logo (user's neon-tree image) = launcher mipmap + header `drawable-nodpi/logo.png`; Download button in FullPlayer (saves to public Music/Dkord via MediaStore, API29+); Settings > App update (`data/update/AppUpdater`: checks GitHub latest release, downloads APK, system installer installs OVER old app; data kept because same package + stable signing key). CI signs with keystore from repo secrets DKORD_KEYSTORE_B64/DKORD_KEYSTORE_PASS (keystore copy only in sandbox /root/ks; if secrets lost, users must uninstall once). versionName 0.3.0/code 3: bump versionCode every release. NOTE: v0.1.0/v0.2.0 APKs were debug-signed with a different key, so updating from them to 0.3.0 needs ONE uninstall.
- [x] Part 10 (CI green, released v0.3.1): logo cropped to a true circle (launcher + header); Theme wraps content in Surface(contentColor=onBackground) so all icons/buttons are visible in dark mode (full player icons used LocalContentColor with no Surface); Home: Recently played shelf first, then YouTube home shelves, then 12 curated search shelves (Bollywood, Punjabi, Romantic, Arijit, Lo-fi, Workout, Devotional, Old, English, Hip-hop, Sad, Party) loaded in `HomeViewModel.extra`. Release v0.3.1 (versionCode 4).
- [x] Part 11 (CI green, released v0.4.0): Autoplay. `Settings.autoplay` (default on; Settings switch + button in FullPlayer). `PlayerController.maybeAutoplay`: when playing the last/second-last track (or ended) and repeat is off, appends up to 15 related songs from `MusicRepository.related` (NewPipe related, fallback search). Home/Detail shelves now play the WHOLE shelf from the tapped song (`ShelfRow(onPlay)`), fixing "stops at the end" for the Recently-played shelf. Repeat button shows a toast (off/all/one). Release v0.4.0 (versionCode 5).
- [ ] NEXT: device-test (v0.3.1 is the first build to verify update-over-install from v0.3.0) per checklists above.
- Source brief for 6-8: user's "Dkord: Video Playback + Secure Google Sign-in" brief (items 1 and 2) + extra asks: header text, no download buttons, playlist download = JSON.
- Workflow rule from user: after EACH part: update this file, commit, push, update this file again; continue to next part without asking.

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

## Part 5 summary (what exists, `ui/`)
- `App.kt`: bottom nav (Home/Search/Library/Downloads) + pushed `Dest` stack saved as JSON (`Nav.kt`, survives rotation/process death), mini player, full player slide-over, snackbar for playback failures (maps `PlaybackProblem` to strings), BackHandlers (predictive back enabled in manifest). `MainActivity` only sets theme + `App()`.
- `screens/`: Home (chips, shelves, pull-to-refresh, reloads on login change), Search (debounced suggestions, filters, paging), Detail (album/artist/playlist/mood, play/shuffle/download), Library (liked, recent, local playlists, import/export JSON via SAF, account library shelves, sign-in), LocalLists (liked, recent, playlist with rename/delete/move up-down/remove), Downloads (queue with progress, pause/resume/cancel, finished list), Settings (quality, theme incl. AMOLED, dynamic color, download folder SAF, cache size/clear, sign in/out, update extractor, report broken, debug log), DebugLogScreen.
- `player/PlayerUi.kt`: MiniPlayer (swipe sideways = next/prev, swipe up = open), FullPlayer (blurred art background, swipe down closes, swipe art = next/prev, seek bar, lyrics panel with LRCLIB synced lyrics tap-to-seek, queue panel with remove/jump, speed, sleep timer, like).
- `theme/Theme.kt`: YouTube-Music-like dark/red; light; AMOLED; Material You optional.
- `common/Actions` (single place for like/download/playlist actions), `Components.kt` (rows, cards, TrackMenu, PlaylistPicker, dialogs).
- `data/lyrics/Lyrics.kt`: LRCLIB client + LRC parser (not unit-tested yet).
- Strings: English default + Hindi (`values-hi`). `locales_config.xml` lists only en, hi.
- Review fixes applied: download pause/remove race (lazy job + recheck), finish() uncancellable + conditional DB commit, resume identity = mime#itag (stored in downloads.mime column), Content-Range start check, OkHttp call cancelled with coroutine, history recorded only when a track really plays, mood ViewModel key includes params, search job cancellation, SAF IO on Dispatchers.IO.

## NOT verified on a device (everything UI)
Nothing in Part 5 has been run on a phone (sandbox has no emulator). First thing a human/AI with a device should do: install CI artifact `dkord-debug-apk`, then run the checklist below and fix what breaks.

## Test after Part 5 (phone)
1. Home loads shelves; pull to refresh; chips filter. 2. Search "arijit": suggestions while typing, tap result plays; filters Albums/Artists open a detail page. 3. Mini player: tap opens full player; swipe down closes; swipe art/mini sideways changes track; seek works; lyrics tab shows synced lines; queue tab jumps/removes; speed + sleep timer. 4. Rotate during playback and during a pushed screen: nothing resets. Kill app from recents with music paused, reopen: queue restored. 5. Song menu -> Download: Downloads tab shows progress, pause/resume, kill app mid-download and reopen: continues; finished file plays in airplane mode; file in Android/data/app.dkord/files/Music/Dkord has tags. 6. Library: create playlist, add songs from menu, reorder, rename, delete; export JSON then import it back. 7. Settings: theme AMOLED/light, dynamic color, quality, clear cache, download folder picker; Debug log shows lines; Report broken copies log and opens GitHub. 8. Sign in with SECONDARY account: Library shows your playlists, Home becomes personalised, Settings shows your name; Sign out clears.

## Known gaps / ideas (not done)
- Queue reorder in UI (controller has `moveInQueue`; the panel only offers jump/remove). Drag-to-reorder for local playlists (uses up/down buttons).
- Only en + hi strings; other languages in locales_config were removed until translated.
- No unit tests for LRC parser, Room DAOs, UI. Release build (minify) never built/tested: R8 rules may need additions (check `assembleRelease` in CI).
- Audio cache size change applies on next app start. AMOLED uses pure black surfaces only for background/surface.
- Logged-in parsing (liked songs/library/account name) unverified; SAPISIDHASH acceptance unverified.
- Sign-in does not yet pass cookies to stream resolvers (bot-check videos may still fail even when logged in).

## Part 12 — v0.5.0 (code complete; final manual CI pending)
- [x] A: Share in FullPlayer and the track menu. Pure `core/TrackShare.kt` formatter + unit tests; Android ACTION_SEND text/plain chooser in `ui/common/Share.kt`. English/Hindi error text and Hindi Share label added. Existing playback/download actions unchanged.
- [x] B: Two songs-only curated shelves immediately after Bollywood Hits: Choliwood - Popular / Choliwood - Latest. Query strings use popular/new bhojpuri songs internally; English/Hindi UI labels always say Choliwood. Existing ShelfRow whole-shelf queue behavior retained. A pushed as 6d0e8a0.
- [x] C: Click-to-open New Releases section, 24h/7d chips. NewReleasesRepository interleaves YT Music new-releases songs with four SONGS-only searches, dedupes/caps at 40, fetches StreamInfo uploadDate with Semaphore(6), skips unknown dates, and caches dated candidates in memory for 30 min. Pure ReleaseWindow filter + tests for exact boundaries, sort/dedupe, lazy loading, concurrency/cap and TTL. This uses upload dates, not guaranteed original song release dates. B pushed as 8290a3e.
- [x] D: Optional Listen Together (FullPlayer overflow menu -> Dest.ListenTogether). RoomTransport + NtfyRoomTransport isolate public ntfy.sh relay (poll since=12h then streaming JSON resumed after cached relay id). RoomCode uses SecureRandom, ten unambiguous uppercase/digit characters. RoomCodec only serializes requested metadata/timing/next <=10 tracks and enforces UTF-8 payload <4000 bytes; rejects malformed versions/ids. RoomController publishes host changes/seeks and 15s playing heartbeats, never publishes guests, ignores stale/future messages, extrapolates guest position, supports manual sync/auto-follow/leave/retry, and logs only generic error categories. PlayerRoomPlayback adapter uses existing public commands; player/autoplay/settings/download implementation unchanged. Privacy/no-audio/encryption/anyone-with-code notices and all new labels translated. Tests cover codes/JSON/limits/relay/poll/resume/roles/heartbeats/sync/errors/cancellation. C pushed as 291fe80.
- [x] E: versionCode 6 / versionName 0.5.0. Both workflows are workflow_dispatch-only; automatic push/PR/tag triggers removed. Build steps restored unchanged (temporary diagnostics removed). Release validates input tag against v + versionName before setup/build, runs testDebugUnitTest assembleDebug, verifies exactly one nonempty APK, then creates --latest release targeting GITHUB_SHA with feature notes. No release dispatched/published. D passed CI 42354683271 (tests + APK).
- [x] F: Code/static review and test additions complete. Final v0.5.0 manual CI is BLOCKED by GitHub integration Actions-write permission (HTTP403), not reported as passed. All 31 new English resource keys exist in Hindi with matching placeholders (Hindi Share label also added); no missing new UI resources or unused explicit imports found. Release tag guard and missing/empty/ambiguous APK gates tested without publishing. Existing player/service/settings/downloads/data-yt files verified byte-identical to base. New tests: 28 across six test classes.

### Part 12 Listen Together notes / device checks
- No owned backend. Public relay exception described at the top; anyone with a code can read AND publish. Codes are not passwords/authentication, and messages are not end-to-end encrypted. No device/user identifiers, account data, cookies, tokens or audio are sent. Room state is memory-only, not restored after process death.
- Session continues off the room screen while this app process lives; Leave cancels network/subscription/sync work. No new foreground service or background-process guarantee was added. Host alone publishes; guest playback resolves its own streams locally. Existing radio autoplay is not changed.
- Two-device checks still required: create/share/join, late join/cache, manual sync, automatic follow, host seek/pause/track transitions/queue, leave/rejoin, network loss, HTTP429, app background/process death. Public ntfy reachability cannot be live-tested from this sandbox's network allowlist.

- Part 12D CI follow-up: A/B/C builds and tests passed. D exposed a stream-buffering regression (a finite/offline stream could throw EOF before a guest received the buffered last event); the live flow now uses rendezvous delivery. Explicit FullPlayer slider/lyric seek notifications also cover small seeks without changing PlayerController or its radio logic. Pending controller acknowledgements are handled briefly so stale positions are not re-published. A temporary diagnostic annotation step was used while debugging and was removed in Part E.

### Part 12 manual builds / release
- Build APK: Actions -> Build debug APK -> Run workflow, select this working branch (after merge select main). Runs tests + assembleDebug, artifact `dkord-debug-apk`.
- Release: Actions -> Release APK -> Run workflow, select intended commit/branch and input `v0.5.0`. It fails before building if the tag does not equal `v` + app versionName. Tests/build/APK verification must succeed before publication. Release is marked latest so the existing app updater sees it. Stable signing setup remains unchanged. Do not publish a release without the user's request.

### Part 12 final review
- Home section/chip headings received from upstream are also branded Choliwood (English or Hindi source labels); actual song titles/artists and internal search queries are never rewritten. Tests cover both languages and preservation of existing labels.
- Final checks: resource XML/unique keys/EN-HI placeholders, explicit import references, valid R.string refs, protected core-file diff, manual-only workflow triggers, unchanged build-job steps, tag validation (matching/mismatching/injection-like input), APK gating (missing/empty/one/ambiguous), added-content secret-literal scan, and git diff --check.
- Final verification uses manually dispatched build.yml on this branch; release.yml is not dispatched. Real phone UI, live public relay reachability and two-device behavior remain device checks, not claims based on unit tests.

### Part 12 exact file manifest

| File | Change |
| --- | --- |
| `.github/workflows/build.yml` | Manual workflow_dispatch only; original test/build/signing/artifact steps preserved. |
| `.github/workflows/release.yml` | Manual required tag input; version match, tests/build, APK gate, feature notes and latest release targeting the selected SHA. |
| `HANDOFF.md` | Part 12 A–F progress, public relay exception/privacy, CI/manual release instructions, tests, limitations and this file manifest. |
| `app/build.gradle.kts` | Version code 6, version name 0.5.0 only. |
| `app/src/main/java/app/dkord/core/TrackShare.kt` | Pure title/artist/YouTube Music URL share formatter. |
| `app/src/main/java/app/dkord/data/releases/ReleaseWindow.kt` | Pure 24-hour/7-day date filtering, newest-first ordering and deduplication. |
| `app/src/main/java/app/dkord/data/releases/NewReleasesRepository.kt` | Lazy song sources, interleaved deduplicated 40-candidate cap, six upload-date lookups maximum, 30-minute memory cache. |
| `app/src/main/java/app/dkord/data/room/RoomCode.kt` | Secure random unambiguous ten-character room codes, validation and topic mapping. |
| `app/src/main/java/app/dkord/data/room/RoomMessage.kt` | Versioned metadata-only JSON, safe parsing/relay envelopes, strict UTF-8 size and queue limits, position extrapolation. |
| `app/src/main/java/app/dkord/data/room/RoomTransport.kt` | Replaceable transport interface and safe relay error type. |
| `app/src/main/java/app/dkord/data/room/NtfyRoomTransport.kt` | Anonymous public relay publish, cached 12-hour poll, cursor-resumed JSON stream, cancellation and HTTP429 handling. |
| `app/src/main/java/app/dkord/data/room/RoomPlayback.kt` | Room-only playback interface/state and next-ten-tracks message snapshots. |
| `app/src/main/java/app/dkord/data/room/PlayerRoomPlayback.kt` | Adapter from room sync to existing local player public commands; no shared audio. |
| `app/src/main/java/app/dkord/data/room/RoomController.kt` | Host changes/seeks/15-second heartbeats, guest cache/live/manual/automatic follow, session leave/retry and privacy-safe error logging. |
| `app/src/main/java/app/dkord/di/AppModule.kt` | Singleton bindings for new releases, room transport/controller and local playback adapter. |
| `app/src/main/java/app/dkord/ui/App.kt` | Listen Together screen route and FullPlayer callback. |
| `app/src/main/java/app/dkord/ui/Nav.kt` | Serializable ListenTogether destination. |
| `app/src/main/java/app/dkord/ui/common/Components.kt` | Share item in the existing track menu. |
| `app/src/main/java/app/dkord/ui/common/Share.kt` | Android ACTION_SEND text/plain chooser and localized failure handling. |
| `app/src/main/java/app/dkord/ui/player/PlayerUi.kt` | Share button, Listen Together overflow entry, room-only seek notifications and unused-import cleanup; existing player actions retained. |
| `app/src/main/java/app/dkord/ui/screens/Home.kt` | Choliwood shelves after Bollywood, localized branded headings/chips, lazy New Releases section, original whole-shelf playback retained. |
| `app/src/main/java/app/dkord/ui/screens/NewReleases.kt` | Expandable lazy shelf, 24h/7d chips, loading/retry/empty states. |
| `app/src/main/java/app/dkord/ui/screens/ListenTogether.kt` | Create/join/share code, host/guest status, sync/follow/leave/retry and public-relay/privacy notices. |
| `app/src/main/res/values/strings.xml` | English labels/messages for all new UI and Choliwood branding. |
| `app/src/main/res/values-hi/strings.xml` | Hindi equivalents for every new UI string; exact Choliwood brand spelling retained. |
| `app/src/test/java/app/dkord/TrackShareTest.kt` | 2 pure share-text/Unicode/privacy tests. |
| `app/src/test/java/app/dkord/HomeLabelsTest.kt` | 3 English/Hindi/unchanged-heading branding tests. |
| `app/src/test/java/app/dkord/NewReleasesTest.kt` | 5 window/boundary/order/dedupe/laziness/concurrency/cap/cache/missing-date tests. |
| `app/src/test/java/app/dkord/RoomMessageTest.kt` | 10 room code/JSON/UTF-8/queue/metadata/position tests. |
| `app/src/test/java/app/dkord/RoomControllerTest.kt` | 5 host heartbeat/change/seek, guest follow/roles, stale-message/leave, rate-limit/log-privacy tests. |
| `app/src/test/java/app/dkord/RoomTransportTest.kt` | 3 cached poll/resume, bounded anonymous publishing and HTTP429 tests using fake HTTP responses. |

### Final CI status / next action
- A/B/C passed GitHub Actions. D plus the seek/stream fixes passed run 42354683271 (`testDebugUnitTest assembleDebug`, APK artifact uploaded) at commit 3c030f8. Those runs include the 25 new share/date/room tests. The final review adds three Home branding tests (28 new tests total), cleans imports, and bumps the version/workflows; these last changes are NOT yet claimed CI-verified.
- Manual dispatch of build.yml for this branch was rejected with HTTP403 `Resource not accessible by integration`. Git push/read access works, but the Arena GitHub integration needs Actions-write permission/reconnection. Do not re-enable automatic push/PR triggers to work around it.
- Owner action: GitHub Actions -> Build debug APK -> Run workflow -> select `arena/2f0c527a-dkord`, or reconnect GitHub in Arena with the needed Actions permission. Then verify the final tests/APK and download `dkord-debug-apk`. No release/tag has been created or published.
- Final review also checked each new @Test declaration; a missing `fun` keyword in the newly added branding test was corrected before handoff.

## v0.5.0 release preparation — keep update-over-install compatibility
- User explicitly requested publishing v0.5.0 with the same signing identity so existing installs do not need uninstalling. `applicationId` remains app.dkord, versionCode is 6 (published v0.4.0 is code 5), and the existing DKORD_KEYSTORE_B64 / DKORD_KEYSTORE_PASS secrets plus alias dkord are retained. No key is generated, rotated or replaced.
- Release workflow now fails closed when the existing signing secrets are missing/invalid; it cannot silently publish with a fresh default debug key. Password is passed through the step environment rather than interpolated into shell source.
- Before `gh release create`, the runner downloads the current latest published APK, verifies old/new APK signatures with apksigner, compares signer certificate SHA-256 digests, checks both packages are app.dkord, requires a higher versionCode and checks the built versionName matches the requested tag. Any mismatch/tool/download failure stops publication. The old release/APK is never deleted or replaced.
- Compatibility-guard logic was checked locally with simulated verifier outputs: matching certs accepted; changed/missing/invalid cert, different package, non-increasing version, wrong versionName and missing previous APK rejected. Missing signing secrets also fail before building. These simulations are not a claim that actual APK certificates have already been compared; that must pass on the release runner.
- The final Part 12 source commit 9f18026 did subsequently pass manually dispatched build/test run 42357141232 (resolving the earlier final-build note). The code merged to main as fe80ee9. Release hardening is on the new active branch arena/27a3df64-dkord.
- Release preflight: latest published release is v0.4.0 with Dkord-v0.4.0.apk; v0.5.0 tag does not yet exist. GitHub rejected listing signing-secret names with HTTP403; no secret value was requested, retrieved, logged or committed. A release workflow dispatch will be attempted using its existing secret references.

- Release dispatch result: `gh workflow run release.yml --ref arena/27a3df64-dkord -f tag=v0.5.0` was rejected with HTTP403 `Resource not accessible by integration`. Publishing is blocked on GitHub Actions-write permission; no release/tag was created by this attempt. Do not weaken signing gates or publish an unverified/local-debug APK as a workaround.
- Owner next action: reconnect/authorize GitHub in Arena with Actions-write permission, OR GitHub -> Actions -> Release APK -> Run workflow -> choose branch `arena/27a3df64-dkord` -> tag `v0.5.0`. This branch contains the new certificate-comparison guard; main does not yet contain that hardening commit. Tests/build plus actual old/new certificate/package/version checks must all pass before the workflow publishes the new latest release.

## Release guard correction — SDK 37 certificate labels
- Owner manually ran Release APK #6: https://github.com/DeepuExplainsAi/dkord/actions/runs/42358252640 at `1f89a62`. The stable-key check, `testDebugUnitTest assembleDebug`, and APK preparation succeeded. The compatibility parser failed with `no verified signing certificate was found`; publication was skipped. This is not evidence of different signing keys, and no v0.5.0 release/tag was created by that run.
- Root cause: the inline parser required the legacy `Signer #1 certificate SHA-256 digest:` label. Current apksigner source (GrapheneOS/platform_tools_apksig, `ApkSignerTool.java`) uses labels such as `V3.0 Signer: certificate SHA-256 digest:` for a single signer. The old regex misses these even after apksigner exits successfully.
- Extracted the compatibility check into `.github/scripts/verify_apk_update.py` and added `.github/scripts/test_verify_apk_update.py`. Support includes legacy numbered signers, SDK 37 scheme-prefixed/colon labels and SDK-range labels. Only successfully verified APK signer certificate SHA-256 values count; public-key and source-stamp digests cannot substitute. Malformed/unknown certificate output, failed signature checks, changed/additional signers, wrong packages and invalid versions still block publication.
- `python3 -B -m unittest discover -s .github/scripts -p 'test_*.py' -v`: **26 tests passed**, including CLI/subprocess wiring against fake tools. These are regression simulations, not actual APK cryptographic verification. The release workflow runs them before Java setup/build. Static ordering/manual-only/signing-reference checks and unchanged-app/build-configuration checks passed; no local Java/SDK provisioning or Android build was performed.
- Original DKORD_KEYSTORE_B64 / DKORD_KEYSTORE_PASS, alias dkord, Gradle signing settings, applicationId app.dkord and version 6 / 0.5.0 are unchanged. The old release/APK is not modified. Actual old/new APK certificate equality must still pass on the next Actions release run.
- Full Actions log download is still blocked by EOF at results-receiver.actions.githubusercontent.com; run/job metadata and the supplied screenshot identify the failed guard. Latest published release remains v0.4.0 at this check.
- Next: start a **fresh Run workflow** for Release APK, branch `arena/27a3df64-dkord`, tag `v0.5.0`, after this fix is pushed. Do **not** use Re-run jobs on run 42358252640: that reuses the old commit and parser. The Arena connection previously received HTTP403 for dispatch; owner UI execution or reconnecting GitHub with Actions-write permission is still needed. Keep all compatibility gates enabled.

## v0.5.0 published — actual signing compatibility verified
- Owner's fresh manual Release APK run **42358984473** succeeded at commit `e2168bc572078349a14107db99decf5aa351eefb`: guard regression tests, original stable-key validation, Android unit tests/APK build, actual old/new APK signature/package/version compatibility check, and publication all passed. https://github.com/DeepuExplainsAi/dkord/actions/runs/42358984473
- **v0.5.0 is now the latest published, non-draft/non-prerelease release**, published 2026-11-07T09:17:24Z. Release tag/target commit is `e2168bc572078349a14107db99decf5aa351eefb`. Asset `Dkord-v0.5.0.apk` is 30,483,450 bytes. https://github.com/DeepuExplainsAi/dkord/releases/tag/v0.5.0
- This supersedes the earlier pending-release/actual-certificate-comparison notes. The actual gate accepted the existing signing identity, unchanged app.dkord package and versionCode increase from the previous published v0.4.0 to v0.5.0. Old v0.4.0 and its APK remain available and unchanged. No key rotation or replacement occurred.
- A user without the app can install the new APK fresh. An existing compatible stable-key install can update in place without uninstalling; this preserves existing app data. Previously uninstalling the app generally deletes local app data, so a fresh install does not by itself recover it. No phone installation/UI test is claimed here.
- Main still points to `fe80ee913e2f3e7afc8cf1195fa0c35dfa72c67d`: A-F feature code was already merged there, while release-workflow/parser/test hardening remains on `arena/27a3df64-dkord`. No PR exists for this active branch yet; publishing a release does not merge it into main. All differences from main are release tooling/tests/HANDOFF; app/player/settings/download/UI files have not changed during release preparation/fixes.

## Part 13 — update experience, reliable New Releases and UI polish (in progress)
- New user request supersedes finishing the interrupted PR immediately: Home update prompt with Update/Later; Settings APK total/downloaded/remaining MB; fix empty New Releases, add categories and up to ten-day discovery while keeping it collapsed by default; preserve full-shelf/related autoplay; subtle professional animations without filler text. All work stays on arena/27a3df64-dkord. The previously requested PR has not yet been created; include these completed changes when ready, do not merge/publish without authorization.
- [x] A code complete: shared app-lifetime UpdateCoordinator/state, quiet foreground checks (30-minute throttle), persistent 24-hour Later per version, explicit download consent, Settings progress in decimal MB, cancellation/retry and installer/unknown-source permission flow. Download continues across Settings navigation/rotation while the app process is alive (not a background-service/process-death promise).
- Update download reads GitHub asset size and optional SHA-256, throttles progress events, writes a partial file and verifies size/hash before atomic completion. Android adapter also checks package, higher versionCode, displayed versionName and exact installed signing identity before opening the system installer. Invalid/partial APKs never intentionally reach installation; no key rotation/data reset.
- A adds 19 Kotlin tests for metadata/progress/defer policy, coordinator lifecycle/consent/cancellation/permissions/retry, and fake-HTTP downloads. They are source-complete but NOT yet executed: no local Java/SDK provisioning or APK build. English/Hindi update strings/placeholders and declarations checked; git diff --check passes. Existing release-signature workflow gates stay enabled.
- [x] B code complete: New Releases discovery/categories/date extraction/caching/error fixes and tests; see detailed section below. Actions/device validation pending.
- [x] C code/static review complete: subtle motion and 0.6.0/code 7 prepared. Manual Actions validation/PR status is recorded below.

### Part 13 B — New Releases discovery and category filters (code complete)
- Kept the click-to-expand Home section collapsed by default, with a short expand/fade + arrow animation. Added horizontal All / Hollywood / Bollywood / Choliwood / Punjabi / Tamil / Telugu chips and 24h / 7d / 10d windows; 7d is the useful default. UI text is English/Hindi; Choliwood branding remains exact in both languages. Tapping a result still passes the full shelf and clicked index to the existing player; music-only related autoplay is untouched.
- Fixed source weaknesses: dated/current-month SONGS queries (at most two search pages each), and expansion of new-release albums into playable tracks (up to four albums, max three concurrent lookups). No plain YouTube video search, login/cookies or changes to existing MusicRepository/search/mapItem/radio behavior.
- Date-only metadata fetch reads bounded main watch-page meta/microformat publication dates, without needing successful audio extraction. It never guesses dates from titles, descriptions or unrelated recommendation JSON; unknown dates stay excluded. Up to six lookups and 40 candidates remain enforced; publication timestamps determine recency, not a guarantee of a song's original commercial-release date.
- Separate 30-minute per-category caches share the three windows; genuine empty results expire after one minute, Retry forces a fresh fetch, and all-date/source failures show a retryable error instead of being cached as "no songs". Collapse/leaving the section cancels active requests.
- B adds 16 Kotlin tests (six repository tests plus seven metadata/query tests and three album-discovery tests); existing five release tests retain boundary/cap/concurrency/cache assertions, with missing-date handling updated to the explicit error. Sources/resources/brand/protected-file checks and git diff --check pass. Kotlin tests and actual on-phone/live YouTube behavior still await Actions/device verification; no local SDK/JDK was provisioned.

### Part 13 C — professional motion and next-version preparation (code complete)
- Added short shared Compose motion timings, subtle 1.5% press feedback with existing Material ripple/accessibility/long-press retained, tab crossfades with saved tab state, stack-depth-aware push/back transitions, and short player panel/art fades. The artwork transition now renders its own outgoing/incoming track snapshot rather than both sides reading the newest thumbnail. Existing Material dialogs/sheets retain their native opening motion; animations respect system animator-duration scale.
- No filler headings/explanations added to Home. Update prompt is version/size plus Update/Later, and Settings retains only actionable status/progress/errors. All new visible text and accessibility labels have English/Hindi entries. PlayerController/PlaybackService, anonymous related radio, normal music downloads, settings storage and room transport/controller remain unchanged.
- Prepared next app version **0.6.0 / versionCode 7**, keeping app.dkord, minSdk and original signing configuration untouched. Release notes now use the input tag dynamically and describe the new update/discovery/motion changes. Workflows remain manual-only and all signing/package/version publication gates remain enabled. No v0.6.0 tag/release is authorized or created by this feature task.
- Three navigation-direction tests bring Part 13 to **38 new Kotlin tests** (19 update + 16 releases + 3 navigation). Kotlin/Android compilation/tests remain unclaimed until a fresh manual Actions run; no local Java/Gradle/SDK install/build was attempted. Existing **26 Python release-guard tests passed locally**. Resource/XML/placeholders/test-declaration/version/signing/protected-file/manual-trigger checks and git diff --check pass.

### Part 13 phone acceptance checklist (not yet device-verified)
1. Install the next same-key APK over v0.5.0 without uninstalling; verify library/settings/downloads remain.
2. On a future newer published version, Home offers Update/Later only; Later suppresses that version for 24h, not a different newer version. Check offline startup stays usable and no APK downloads before consent.
3. Start update: Settings shows downloaded / total MB and remaining MB with live progress. Navigate away/back/rotate; the shared job remains. Cancel/retry, lost internet, unknown-source permission return, system installer cancel/retry and invalid APK rejection need phone checks. Process-death/background-service download continuation is not promised.
4. New Releases stays collapsed by default; expand, switch All/Hollywood/Bollywood/Choliwood/Punjabi/Tamil/Telugu and 24h/7d/10d, Retry and collapse during load. Verify live metadata/discovery results on a phone; sandbox internet cannot reach YouTube. Genuine missing recent releases can still be empty, but date/network failures must not pretend to be an empty catalogue. Dates are publication/upload metadata, not guaranteed original commercial release dates.
5. Tap any release: full-shelf queue starts at the tapped track and existing related-radio autoplay extends it near the end (unless the user has disabled autoplay). Check tabs/back/full-player/art/queue/lyrics/sheets and disabled system animations; no playback/download regression or flicker.

### Part 13 final validation status
- Final code commit `bff904b` pushed. Attempting `gh workflow run build.yml --repo DeepuExplainsAi/dkord --ref arena/27a3df64-dkord` again for this new task was rejected with HTTP403 `Resource not accessible by integration`; no run was started by that command. Do not restore automatic triggers or bypass this permission failure.
- Owner action: Actions -> **Build debug APK** -> **Run workflow** -> branch **arena/27a3df64-dkord**. Use a fresh run, not Re-run jobs on an older commit. This validates all new Kotlin code/tests and builds the next APK without publishing anything. Reconnecting GitHub in Arena with Actions-write permission is the alternative; no tokens/keystores/passwords should be sent in chat.
- 38 new Kotlin tests remain unexecuted at this point. No new APK, tag or v0.6.0 release is claimed. The successful v0.5.0 run 42358984473 validates the earlier release-guard code only, not these new Part 13 app changes.

### Part 13 PR / waiting for the next user brief
- PR **#3 is OPEN (not draft, not merged)** from arena/27a3df64-dkord into main: https://github.com/DeepuExplainsAi/dkord/pull/3 . It includes release hardening and the Part 13 update/discovery/motion work; the description explicitly discloses pending Android validation.
- At the validation question, the user chose to send more improvements instead of confirming a manual build/reconnection. Wait for that brief; continue on this same branch/PR, do not merge or publish the next version. No Build debug APK run for this branch exists at the latest check; 38 new Kotlin tests/new APK build remain pending. Only the local/static/Python validation already recorded is claimed.

## Part 14 — room flow, English default, compact latest-only updates (in progress)
The owner requested these together before the next release: remove the prominent technical Listen Together paragraph in favor of practical numbered instructions; improve the real room flow; make the Home update notice compact/non-modal and upgrade directly to the latest published stable APK; default every app screen to English until the user explicitly selects a supported translation. Finish regression review first. Do not ask for repeated mid-task manual builds; if dispatch is still forbidden, finish preparation and provide one fresh **Release APK** workflow instruction for **v0.6.0** on this branch. No merge or next-version publication has happened.

### 14 A — practical Listen Together (code/static review complete)
- Replaced the main relay/encryption/account-data paragraphs with four numbered Create / Share / Join / Sync instructions, professional room/track cards, Copy + Share, connection/sync status, and a direct Open player action for the host. No invented member count, presence, or encryption claim. Technical transport/privacy facts remain developer documentation, not the main room UI.
- Fixed real synchronization/session issues: local related-autoplay suffixes no longer restart the host's song at every heartbeat; failed manual sync clears on successful retry; old jobs cannot write into a different room; new rooms require fresh Follow host consent. Duplicate create/join taps are ignored. Pasted grouped/spaced codes normalize without accepting URLs or invalid symbols.
- Guest connections reconnect from the last cursor while retaining the host song; failures back off 5 seconds (60 seconds for rate limiting). A paused host retries a failed publication even without a playing heartbeat. Leave still cancels room/sync jobs and does not stop ordinary playback. Metadata-only protocol, public relay, ten-track limit and existing player/radio/download engines remain unchanged.
- Added **13 Kotlin regression tests** (9 controller + 4 code/queue policy), including one host with three guests, reconnect/cursor/rate-limit behavior, paused publishing retry, late old syncs, consent, double taps and queue suffixes. These are **not compiled/run yet**. Changed Kotlin files pass a Python-hosted syntax parser; this is not a Kotlin type check. English/Hindi resources/placeholders, test declarations, git diff --check and all **26 Python release-guard tests PASS**. No local Java/Gradle/Android SDK/build was provisioned or run.
- Next: app-wide explicit English-first language selection, compact latest-only update UX/regressions, full feasible regression review and release workflow documentation. PR #3 stays open on the same branch.

### 14 B — app-wide English-first language (code/static review complete)
- Fresh/existing installs without an explicit supported app-language choice now use **English**, regardless of the phone language. Added **Settings → Appearance → App language** with English (default) / Hindi; these are the only translations actually shipped. No fake unsupported-language entries.
- Uses Android's app LocaleManager on API 33+ (honors explicit Android per-app language changes) and AppCompat application locales on API 26–32. MainActivity now extends AppCompatActivity with its matching no-action-bar theme and stable appcompat 1.7.1 dependency. The small legacy application-resource adapter also covers notification/worker strings; preference storage is separate and existing settings are untouched. Activity recreation keeps saveable navigation and app-lifetime player/room/update instances, not a new playback session.
- Default/reset/migration policy never infers Hindi from the phone. Region is retained when selecting UI language; NewPipe initialization continues to use the existing device music region/language, independently of the interface override. Phone configuration changes reapply the explicit/default app locale. Changing app language does not translate original song/artist metadata.
- Completed missing Hindi fallbacks: all **225 English keys have 225 matching Hindi keys**, no duplicates, all placeholders/reference checks pass; branding stays Dkord / Choliwood. Removed misleading playback-error suggestions to sign into the intentionally disabled login flow.
- Added **6 Kotlin language-policy tests** for English default under different phone locales, explicit selection, supported tags/country, Android app-language precedence, upgrade migration and reset. **57 new Kotlin tests since Part 13 remain uncompiled/unrun**. Language changed-source syntax parsing, complete resource checks, git diff --check and **26 Python guard tests PASS**. No local Android build/provisioning and no additional owner build request.
