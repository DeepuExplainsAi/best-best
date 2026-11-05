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
- [ ] Part 2: StreamResolver, search, login + auth
- [ ] Part 3: player service
- [ ] Part 4: downloads, Room, playlists
- [ ] Part 5: UI, settings, translations, polish

## Test after Part 1
Actions tab: "Build debug APK" is green and artifact `dkord-debug-apk` installs and shows a placeholder text.
