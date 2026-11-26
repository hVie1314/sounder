# ROLE & OBJECTIVE
You are a Senior Android Systems Engineer specializing in low-memory media streaming and offline audio architecture.
Your mission is to build a lightweight, rock-solid offline Android MP3 Audiobook Player optimized specifically for:
- Massive MP3 files (8 GB – 10 GB per file, 100+ hours duration).
- Low-RAM Android devices (4 GB RAM, aggressive OS background process killing).
- 100% reliable playback resume (never loses exact timestamp across app kills or reboots).

# TECH STACK (STRICT)
- Language: Kotlin (Coroutines & Flow)
- UI: Jetpack Compose with Material 3 (Minimalist Dark/OLED-friendly default theme + Light/System toggle)
- Media Engine: AndroidX Media3 (`media3-exoplayer`, `media3-session`, `media3-ui`)
- Persistence: Room Database (SQLite with WAL mode) for playlists, tracks, and exact playback timestamps + Jetpack DataStore for user settings
- Architecture: Clean MVVM + Unidirectional Data Flow (Repository -> Service/UseCase -> ViewModel -> Compose UI)
- Build System: Gradle (Kotlin DSL), Min SDK 26 (Android 8.0), Target SDK 35, Compile SDK 35
- Dependency Injection: Lightweight manual DI (`AppContainer`) or Hilt (prefer clean manual DI to keep APK size minimal and build times fast).
- DO NOT use Rust/NDK, Flutter, or React Native. Keep APK footprint minimal (< 15 MB) and runtime RAM usage low (< 80 MB).

# CRITICAL ENGINEERING CONSTRAINTS (8–10 GB MP3 & 4 GB RAM)
You MUST implement these safeguards to prevent OOM, lag, and timestamp loss:

1. **Strict 64-bit Arithmetic Everywhere:**
   - Use `Long` (64-bit) for all file sizes, byte offsets, durations (ms), and seek positions (ms). Never downcast to `Int`.

2. **Zero-Lag Streaming & Low-RAM Buffering:**
   - Configure a custom `DefaultLoadControl` tailored for local storage audio (NOT network video):
     - `minBufferMs = 15_000`
     - `maxBufferMs = 30_000`
     - `bufferForPlaybackMs = 1_000`
     - `bufferForPlaybackAfterRebufferMs = 2_000`
     - `targetBufferBytes = 2 * 1024 * 1024` (cap buffer to ~2 MB RAM).
   - Never load full files or large embedded ID3 artwork into memory. Ignore or strictly downsample artwork.

3. **>4 GB MP3 Seeking & Xing Overflow Protection:**
   - Standard MP3 Xing headers use 32-bit file size fields that overflow on files > 4.29 GB, breaking seeking and duration calculation.
   - Configure Media3 `DefaultExtractorsFactory` with:
     - `setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS or Mp3Extractor.FLAG_DISABLE_ID3_METADATA)` as the primary ultra-fast, O(1) memory-safe seeking mode so 10 GB files load and seek to `05:20:00` instantaneously without scanning gigabytes of data.
     - Provide a toggle in Settings ("Chế độ tua chính xác cho file VBR / VBR Index Seeking") only as an optional fallback, defaulting to fast constant-bitrate seeking.
   - Use `ContentResolver` + `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` via Storage Access Framework (`ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_TREE`) so file access NEVER breaks the next day.

4. **Bulletproof Timestamp Persistence (Anti-Reset Architecture):**
   - Save `(trackUri, positionMs, durationMs, lastPlayedTimestamp, queueIndex)` to Room DB:
     - Periodically every 5 seconds while `isPlaying == true`.
     - Immediately on Pause, Seek, Track Switch, Audio Becoming Noisy (unplugged headphones), Sleep Timer trigger, and `MediaSessionService.onTaskRemoved` / `onDestroy`.
   - **ANTI-ZERO-OVERWRITE GUARD (MANDATORY):** Never persist `positionMs = 0L` unless the user explicitly pressed "Restart/Seek to 0" or the track naturally reached `STATE_ENDED`. During player initialization, buffering, or error states, block any write that would overwrite a saved non-zero position with `0L`.
   - **Cold-Start Resume:** When launching the app and restoring the last track, pass the saved `startPositionMs` directly into `player.setMediaItems(mediaItems, startIndex, savedPositionMs)` before calling `player.prepare()`.

5. **Background Playback & System Integration:**
   - Run playback inside a `MediaSessionService` with a proper Foreground Service notification (`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`).
   - Enable `player.setWakeMode(C.WAKE_MODE_LOCAL)` and `setHandleAudioBecomingNoisy(true)` and `setAudioAttributes(..., handleAudioFocus = true)`.
   - Expose full controls (Play/Pause, Prev/Next, Seek +/- N seconds custom commands) to Notification Shade, Lock Screen, and Bluetooth/Headset media buttons.

# FUNCTIONAL REQUIREMENTS

## 1. File & Queue Management
- Pick single or multiple `.mp3` files (and optionally import a folder of `.mp3` files) using Android Storage Access Framework (SAF).
- Display a clean Queue/Playlist view: show filename, duration (formatted `HH:MM:SS`), current progress bar/percentage, reorder or remove items, and clear queue.
- Automatically remember the entire last queue, active track, and exact position on app reopen.

## 2. Core Playback Screen (Minimalist & Modern UX)
- Clean, distraction-free player screen focused on audiobook listening:
  - Track title (clean filename without `.mp3`), Queue position (e.g., `2 / 12`).
  - Large, clear `HH:MM:SS` elapsed time and total duration.
  - Smooth interactive seek bar supporting long durations (e.g., 100+ hours).
  - Primary controls: Previous Track, Rewind `-N` sec, Play/Pause (prominent FAB/button), Forward `+N` sec, Next Track.
  - Quick action bar: **Playback Speed**, **Jump to Time**, **Sleep Timer**, **Queue Drawer/Sheet**, **Volume indicator/slider** (synced with `AudioManager.STREAM_MUSIC`, hardware volume keys always control media volume).

## 3. Playback Speed Control
- Preset chips: default `0.5x`, `1.0x`, `2.0x` (customizable presets in Settings).
- Fine adjustment: `-0.1x` / `+0.1x` stepper buttons and slider (range `0.5x` to `3.0x`).
- Pitch-preserving playback via Media3 `PlaybackParameters(speed, 1.0f)`.
- Persist selected speed across sessions.

## 4. Jump to Time
- Dedicated modal dialog allowing direct numeric input for `Hours : Minutes : Seconds` (e.g., `05:20:00`).
- Validate input against track total duration, show clear error if out of bounds, and execute instant seek + persist new position.

## 5. Seek Step Customization
- Default forward/backward step: `10` seconds.
- Configurable in Settings (quick options: 5s, 10s, 15s, 30s, 60s, or custom seconds).
- Reflected immediately on both the Player UI and Lock Screen / Notification controls.

## 6. Sleep Timer
- Preset options: 15m, 30m, 45m, 60m, Custom minutes, and "End of current file".
- Live countdown badge visible on the Player screen.
- Smooth 3-second volume fade-out before pausing, then restore player volume to `1.0f` and immediately save the exact playback timestamp to Room DB.

## 7. Settings Screen
- Customize default speed presets (e.g., edit the 3 quick-select speed buttons).
- Customize forward/rewind skip duration (seconds).
- Optional "Auto-rewind on resume" (e.g., 0s, 3s, 5s — default 0s/exact).
- Theme selector (Dark / OLED Black / Light / System).
- VBR/CBR seek mode toggle (default: Fast >4GB-safe CBR seeking).

# DELIVERABLES & REPOSITORY STANDARDS
1. **Git Repository:**
   - Initialize Git with a proper Android `.gitignore`.
   - Use clean, atomic commits (Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`).
   - Include a clear `README.md` (Vietnamese + English technical notes) explaining architecture, >4GB MP3 handling, and how to build/install.
2. **Code Organization:**
   - Clean package structure: `data/local` (Room, DataStore), `data/repository`, `player` (MediaSessionService, CustomExtractors, SleepTimer), `ui/player`, `ui/queue`, `ui/settings`, `ui/theme`, `util` (TimeFormatter).
3. **Build & APK Output:**
   - Verify compilation with `./gradlew assembleDebug` and `./gradlew assembleRelease` (unsigned/debug-signed APK ready to sideload onto Android).
   - Run unit tests for time formatting, 64-bit timestamp persistence guards, and jump-to-time validation.
   - Output final `.apk` path clearly for the user.