# TruyenCast — Offline Android Audiobook Player

**TruyenCast** là trình phát sách nói / truyện audio offline tối giản, nhẹ và bền bỉ cho Android (`minSdk 26`, `targetSdk 35`), được thiết kế chuyên biệt để xử lý các tệp **MP3 kích thước cực lớn (8–10 GB, dài hơn 100 giờ)** trên các thiết bị **RAM thấp (4 GB)** mà không bị lag, OOM hay mất vị trí nghe khi OS tắt tiến trình nền.

---

## Tính năng chính / Key Features

- **Hỗ trợ tệp MP3 > 4 GB (8–10 GB) & > 100 giờ:**
  - Dùng `Long` (64-bit) cho toàn bộ kích thước tệp, thời lượng, vị trí phát và phép tính tua.
  - Bộ đếm thời gian `HH:MM:SS` dùng font `Monospace` với `fontFeatureSettings = "tnum"` (tabular numerals) giúp số giây không bị nhảy khung hình.
- **Lưu vết vị trí tuyệt đối (Bulletproof Resume):**
  - Tự động lưu vào SQLite (Room WAL mode) mỗi 5 giây khi đang phát, đồng thời lưu ngay khi Pause, Seek, chuyển bài, rút tai nghe (`ACTION_AUDIO_BECOMING_NOISY`), Sleep Timer hết giờ, hoặc vuốt tắt app (`onTaskRemoved` / `onDestroy`).
  - **Anti-Zero-Overwrite Guard:** Chặn tuyệt đối việc ghi đè `0L` lên vị trí đã lưu trong lúc player đang khởi tạo, buffering hoặc gặp lỗi.
- **Điều khiển nghe truyện chuyên sâu:**
  - Chọn 1 file, nhiều file hoặc cả thư mục MP3 qua Android Storage Access Framework (SAF) kèm `takePersistableUriPermission` và sắp xếp tên chương tự nhiên (`Tap 2` trước `Tap 10`).
  - Điều chỉnh tốc độ `0.5x – 3.0x` (giữ nguyên cao độ `PlaybackParameters(speed, 1.0f)`) với 3 nút preset tùy biến được trong Cài đặt.
  - **Jump to Time:** Nhập trực tiếp `Giờ : Phút : Giây` (VD: `05:20:00`) có kiểm tra giới hạn thời lượng.
  - **Sleep Timer:** Đặt `15p`, `30p`, `45p`, `60p`, số phút tùy chỉnh hoặc `Hết file hiện tại`; tự động giảm dần âm lượng (fade-out) trong 3 giây cuối trước khi tạm dừng, lưu timestamp và khôi phục mức âm lượng `1.0f`.
  - **Notification & Lockscreen:** Đủ nút Play/Pause, Prev/Next và nút tua `+/- N giây` cập nhật động theo Settings.
  - **Đồng bộ âm lượng `STREAM_MUSIC`:** Thanh trượt trong app đồng bộ hai chiều với phím cứng tăng/giảm âm lượng.
  - **Giao diện tối giản:** Mặc định nền **OLED Đen tuyền**, hỗ trợ chuyển sang Dark / Light / System.

---

## Kiến trúc & Cơ chế xử lý >4 GB / Low-RAM

### 1. >4 GB MP3 Seeking & Xing Overflow Protection
Standard MP3 Xing headers store total file byte size in a 32-bit unsigned integer, which overflows past `4.29 GB` and breaks seeking/duration parsing on 8–10 GB audiobooks.

- **Giải pháp trong [`LowRamMediaConfig.kt`](app/src/main/java/com/offline/audiobookplayer/player/LowRamMediaConfig.kt):**
  - Cấu hình `DefaultExtractorsFactory` với cờ mặc định:
    ```kotlin
    Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS or
        Mp3Extractor.FLAG_DISABLE_ID3_METADATA
    ```
  - Cho phép tính vị trí tua trong $O(1)$ mà không quét hàng gigabyte dữ liệu, tránh tràn Xing header 32-bit và bỏ qua metadata/ảnh bìa ID3 nhúng cỡ lớn để tránh OOM.
  - Cung cấp tùy chọn **VBR Index Seeking** trong Settings như chế độ dự phòng cho các file VBR < 4 GB.
  - Dùng `NoOpBitmapLoader` để ngăn `MediaSession` giải mã bitmap ảnh bìa từ metadata.

### 2. Low-RAM Buffering (~2 MB Cap)
- Cấu hình `DefaultLoadControl` cho lưu trữ cục bộ trong [`LowRamMediaConfig.kt`](app/src/main/java/com/offline/audiobookplayer/player/LowRamMediaConfig.kt):
  - `minBufferMs = 15_000`, `maxBufferMs = 30_000`
  - `bufferForPlaybackMs = 1_000`, `bufferForPlaybackAfterRebufferMs = 2_000`
  - `targetBufferBytes = 2 * 1024 * 1024` (~2 MB RAM), `prioritizeTimeOverSizeThresholds = false`
- Bật `C.WAKE_MODE_LOCAL`, `setHandleAudioBecomingNoisy(true)` và `AudioAttributes` (`CONTENT_TYPE_SPEECH`, `handleAudioFocus = true`).

### 3. Cấu trúc mã nguồn (`com.offline.audiobookplayer`)
```text
app/src/main/java/com/offline/audiobookplayer/
├── data/
│   ├── local/           # TruyenCastDatabase (WAL), Entities, DAOs, SettingsDataStore
│   ├── model/           # UserSettings
│   └── repository/      # AudiobookRepository (Room transactions + Timestamp Guard)
├── di/                  # AppContainer (lightweight manual DI)
├── player/              # AudioPlaybackService, DynamicSeekPlayer, LowRamMediaConfig,
│                        # PlaybackTimestampGuard, SleepTimerManager
├── ui/
│   ├── player/          # PlayerScreen, PlayerViewModel, PlayerUiState,
│   │                    # SpeedBottomSheet, JumpToTimeDialog, SleepTimerBottomSheet
│   ├── queue/           # QueueBottomSheet
│   ├── settings/        # SettingsScreen
│   └── theme/           # Color, Type (tabular monospace), Theme (OLED/Dark/Light/System)
├── util/                # TimeFormatter (64-bit), SafFileHelper, MediaVolumeObserver
├── MainActivity.kt
└── TruyenCastApplication.kt
```

---

## Build & Kiểm thử / Build & Testing

### Yêu cầu môi trường
- JDK 17 (`JAVA_HOME` trỏ tới JDK 17)
- Android SDK Platform 35 & Build-Tools 35 (`sdk.dir` trong `local.properties` hoặc biến môi trường `ANDROID_HOME`)

### Lệnh Gradle
```bash
# Chạy Unit Tests (TimeFormatter, Jump-to-Time validation, Anti-Zero-Overwrite Guard, SleepTimer, SAF sort)
./gradlew testDebugUnitTest

# Build bản Debug APK
./gradlew assembleDebug

# Build bản Release APK (tối ưu R8 minify + resource shrinking, sẵn sàng cài trực tiếp)
./gradlew assembleRelease
```

Trên Windows PowerShell:
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

---

## Cài đặt APK lên điện thoại / Installing the APK

File Release APK tối ưu R8 được xuất tại:
`app/build/outputs/apk/release/app-release.apk`

1. **Cài trực tiếp trên điện thoại:** Chép file `app-release.apk` vào máy Android và mở để cài đặt (cho phép cài ứng dụng từ nguồn không xác định nếu được hỏi). Bản release đã được cấu hình ký mặc định bằng debug keystore trong `app/build.gradle.kts` để sideload trực tiếp không gặp lỗi *"App not installed"* (khi phát hành lên Play Store cần thay bằng release keystore riêng).
2. **Cài qua ADB (USB Debugging):**
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
