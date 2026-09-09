# Media Saver for Android

A standalone Android app that finds the media behind a link and saves it to the
phone. Extraction runs on the device — there is no server, and nothing needs to
be running on a computer. It works on mobile data, away from home.

## What it does

1. Share a link into the app from any other app's share sheet, or paste one.
2. It reads the link and lists the qualities that are actually available.
3. Pick one. It downloads, merges video and audio if they came separately, and
   saves the result into the phone's shared storage.

Files land in `Movies/Media Saver`, `Music/Media Saver` or `Download/Media Saver`
depending on type, so they appear in the gallery, music app and Files like
anything else saved on the phone. No storage permission is requested: saving
goes through MediaStore, which does not need one.

Downloads run in a foreground service, so leaving the app or locking the screen
part-way through a large video does not kill them. Progress shows in the
notification shade.

## Building it

Requires a JDK (17 or 21) and the Android SDK. On a Mac with Homebrew:

```bash
brew install openjdk@21 android-commandlinetools
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

Point the build at them and assemble:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on
a plugged-in phone with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` holds the SDK path and is not in version control; the build
creates it, or you can write `sdk.dir=/opt/homebrew/share/android-commandlinetools`
yourself.

### Why the APK is ~113 MB

It carries a Python runtime, yt-dlp and ffmpeg, because all of the work happens
on the phone. Only `arm64-v8a` is included — every Android phone made in the
last several years is arm64, and adding the other ABIs would multiply the size
for no practical gain. If you need to run on an older 32-bit device, add
`armeabi-v7a` to `abiFilters` in `app/build.gradle.kts`.

## Version pinning

| | Version | Why |
| --- | --- | --- |
| Gradle | 8.14.3 | AGP 8.x cannot run on Gradle 9.6+, which removed an internal API it uses |
| AGP | 8.13.2 | Newest that works with `compileSdk 36` |
| AndroidX | SDK-36 line | Newer releases require `compileSdk 37` and AGP 9.1+, and android-37 is not in the SDK channel yet |

Moving any one of these forward means moving all of them, plus installing
`platforms;android-37`.

## Keeping it working

The app updates its own copy of yt-dlp from the stable channel on first run and
once a day after that, in the background, before the first lookup.

This is not housekeeping — it is what makes the app work at all. The yt-dlp
compiled into the library was already months old at build time, and a stale
yt-dlp fails in a way that looks like a bug in the app: metadata still reads
fine, the full quality list appears, and then the download itself gets
`HTTP 403 Forbidden`. That is exactly what happened during development, and the
update fixed it.

If downloads start failing after a long period offline, opening the app while
connected lets it update.

## Known limits

- **Playlists** are not supported; a playlist link resolves to the first video.
  Use the server app for playlists.
- **Sites that require an account** (Vimeo and Reddit both do now) cannot be read,
  since there is no way to supply your login. The server app can, via browser
  cookies.
- **Android 10 (API 29) and above.** This is what lets the app save without a
  storage permission.
- **2160p and 1440p from YouTube are VP9**, because no H.264 exists at those
  heights. They are remuxed into MP4 and some stock players will not play them;
  1080p and below are H.264 + AAC and play everywhere.

## Layout

| File | What it holds |
| --- | --- |
| `Formats.kt` | Turns yt-dlp's format list into the quality choices, and ranks them |
| `Extractor.kt` | yt-dlp lifecycle: unpacking, self-update, metadata lookup, error messages |
| `DownloadRepository.kt` | Runs downloads and holds their state |
| `DownloadService.kt` | Foreground service and progress notification |
| `MediaStoreSaver.kt` | Writes the finished file into shared storage |
| `SaverScreen.kt` | The Compose UI |

`Formats.kt` mirrors [`../server/server.py`](../server/server.py). Both were
shaped by the same testing, and the comments explain the non-obvious rules —
particularly why HLS formats are ranked below direct ones.

Usage and legal terms are in the [top-level README](../README.md).
