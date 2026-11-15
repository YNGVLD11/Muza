# Muza

Muza is a native Android application for extracting and downloading audio tracks from YouTube in MP3 format. The application is built with a focus on strict utilitarian minimalism, efficient background processing, and modern Android architecture standards.

## Architecture and Technology Stack

- **Platform:** Native Android (Min SDK 26, Target SDK 35)
- **Programming Language:** Kotlin 2.0.21
- **UI Framework:** Jetpack Compose (Material 3 BOM 2024.11.00)
- **Audio Extraction Engine:** `io.github.junkfood02.youtubedl-android:library:0.17.4` (yt-dlp embedded runtime)
- **Audio Conversion & Tagging:** `io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4`
- **Asynchronous Architecture:** Kotlin Coroutines, StateFlow, SupervisorJob
- **File System & Persistence:**
  - Android Storage Access Framework (SAF) via `DocumentFile`
  - Preferences: `androidx.datastore:datastore-preferences`
- **Image Loading:** Coil Compose (`io.coil-kt:coil-compose`)
- **Networking:** OkHttp 4.12.0
- **Target ABI:** `arm64-v8a` (optimized native library packaging)

## Key Technical Features

1. **Decoupled FIFO Queue Management:**
   - Link parsing and queue state are completely decoupled from UI input state.
   - Immediate asynchronous metadata prefetching with animated skeleton states.
   - Non-blocking batch enqueueing supporting multiple URL formats (including shorts, tracking query parameters, and plain IDs).

2. **Foreground Download Service:**
   - Background execution backed by `DownloadService` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
   - Rate-limited notification updates (throttled to >= 1.2s and >= 3% progress deltas) to prevent notification shade reordering and stutter on custom OEM skins.
   - Clean lifecycle management: automatic transition from ongoing progress to a single dismissible completion notification upon queue completion.

3. **Storage Access Framework (SAF):**
   - Direct integration with user-selected folders using persisted URI permissions (`ACTION_OPEN_DOCUMENT_TREE`).
   - Compliant with Android Scoped Storage policies (Android 10 - 15) without legacy broad file system access permissions.

4. **Share Target Integration:**
   - System intent filter supporting `ACTION_SEND` and `ACTION_VIEW` for incoming YouTube links directly from the YouTube application or web browsers.
   - Automated URL extraction from mixed text content.

5. **Authentication Support:**
   - WebView-based YouTube authentication utility with automatic Netscape format cookie export (`cookies.txt`) for restricted or high-quality audio streams.

## Project Structure

```
app/src/main/java/com/muza/app/
├── data/
│   ├── Config.kt                # Constants and application configuration
│   ├── PreferencesManager.kt    # DataStore persistence layer
│   └── UpdateChecker.kt         # Engine and release update check logic
├── service/
│   ├── DownloadQueueManager.kt  # Reactive FIFO queue coordinator
│   ├── DownloadService.kt       # Foreground service running extraction and conversion
│   └── DownloadState.kt         # Observable state definitions
├── ui/
│   ├── components/              # Jetpack Compose UI components (Cards, Shimmers, Inputs)
│   ├── screens/                 # MainScreen, SettingsScreen, YouTubeAuthScreen, ViewModels
│   └── theme/                   # Material 3 monochromatic typography and palette
└── util/
    ├── CookieNetscapeConverter.kt # Netscape HTTP Cookie file builder
    ├── FileHelper.kt              # SAF file copy and cleanup routines
    └── UrlExtractor.kt            # Regular expression and URL normalization engine
```

## Building the Project

### Prerequisites
- JDK 17 (recommended: Eclipse Temurin or OpenJDK 17)
- Android SDK with API Level 35 and Build Tools 35.0.0
- Gradle 8.12 (managed via included Gradle Wrapper)

### Compilation Commands

1. **Assemble Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   Output path: `app/build/outputs/apk/debug/app-debug.apk`

2. **Assemble Release APK:**
   ```bash
   ./gradlew assembleRelease
   ```
   Output path: `app/build/outputs/apk/release/app-release-unsigned.apk`

## License

This project is licensed under the Apache License 2.0. Third-party components (`yt-dlp`, `ffmpeg`, `youtubedl-android`) remain subject to their respective licenses.
