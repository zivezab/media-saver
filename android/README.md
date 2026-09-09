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

## Getting to what you saved

Saving a file is only half the job — a download you then have to hunt for in a
file manager is not really finished. Three things lead back to it:

- **The finished download** shows **Play** and **Share** the moment it completes.
- **The notification** says "Saved - tap to play" and opens the file directly.
  It is on its own channel, so it can stay audible while progress stays silent.
- **"Saved on this phone"** lists everything the app has saved, and survives
  restarts. Each entry has Play and Share, with its size and folder.

Playing and sharing hand other apps the MediaStore `content://` Uri with read
permission attached, so any installed player or app can receive it. Entries the
user later deletes from the gallery are pruned from the list on next launch,
rather than left as dead rows.

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

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Installing on a phone

```bash
./install.sh
```

That is the whole thing. The script finds `adb` for you — it lives inside the
SDK and is normally not on `PATH`, which is the usual reason a bare `adb`
command fails — builds the APK if it is missing, checks the phone before trying
anything, and installs.

| | |
| --- | --- |
| `./install.sh` | Install, building first if needed |
| `./install.sh --build` | Force a rebuild, then install |
| `./install.sh --reinstall` | Uninstall the existing copy first |
| `./install.sh --devices` | Just show what adb can see |

It checks the phone's API level and CPU ABI up front, because both fail with
unhelpful messages otherwise: an Android 9 phone or a 32-bit one would
otherwise just report a generic install failure.

**On Xiaomi, Redmi and POCO phones** (and some Oppo, Vivo and Realme), USB
installs are blocked by default and fail with `INSTALL_FAILED_USER_RESTRICTED`,
or with no reason at all. Turn on both *Install via USB* and *USB debugging
(Security settings)* in Developer options — both generally require being signed
into a Mi account, and the phone may need mobile data on before the toggles
stick. Watch the phone's screen during the install too, since it often shows a
confirmation prompt that quietly times out. If the install still will not go
through, the script copies the APK into the phone's Downloads so you can tap it
in Files and install it that way.

For USB debugging generally: Settings > About phone > tap *Build number* seven
times, then Settings > Developer options > *USB debugging*, and accept the
authorisation prompt when you plug the phone in.

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

## Posts that need an account

Some posts are not served to a logged-out client. Anything marked sensitive on
X is the common case, and Vimeo and Reddit hide everything. The app passes your
own session to yt-dlp with `--cookies`.

### Getting cookies, on the phone alone

Android stops any app reading another app's or browser's cookie store, so the
installed X and Instagram apps **cannot** be read - there is no API for it at
any permission level, only root. A bookmarklet does not help either: session
cookies are `HttpOnly` and invisible to page JavaScript.

What works without a computer:

1. Install Firefox for Android (or Kiwi Browser) - both take extensions.
2. Add "Get cookies.txt LOCALLY".
3. Sign in to the site there.
4. Export, then either paste the text into the app or import the file.

Account icon > paste into the box > **Check and save**, or **Import a
cookies.txt file**. Both accept a full cookies.txt; the paste box also takes a
plain `name=value; name=value` string, in which case pick the site first so the
cookies can be given a domain.

Nothing is saved unless the text actually contains a recognised session cookie,
and the dialog reports which sites it found. That check reads the file back
rather than trusting what was pasted, so it cannot claim a sign-in that is not
there. It cannot tell whether the session is still live - only that a login is
present.

### Notes for anyone changing this

- Domains are matched on the bare host. Exporters disagree about writing
  `.x.com` with the include-subdomains flag versus a host-only `x.com`; yt-dlp
  accepts either, so the app must too.
- A capture only counts if the site's session cookie is present. Every site
  hands a visitor throwaway cookies, so accepting "any cookie" reports a
  sign-in that never happened.
- The file holds a live session. It stays in app-private storage, is never
  logged, and *Sign out of all* deletes it.
- There is no in-app WebView login. It was tried and removed: X, Instagram,
  Reddit and Vimeo all render a blank page inside another app's WebView, on
  purpose, because embedded browsers are a phishing vector. The same WebView
  renders ordinary pages fine, so this is their choice, not a bug.

## The library

Everything the app has saved is listed under the link box, and the list
survives restarts.

| | |
| --- | --- |
| Grouping | By site, so youtube.com and x.com stay apart. Toggle in settings |
| Search | Substring, or wildcards - `Big*Bunny`, `ep0?.mp4` |
| Sort | Date, size or name; tap the same sort again to reverse it |
| Layout | List or grid |
| Thumbnails | Real poster frames, from MediaStore. Can be turned off |
| Per item | Play, Share, and Delete with confirmation - Delete removes the file, not just the row |
| Playback | In-app, via ExoPlayer - no app switch |
| Duplicates | Detected on size plus name; one button removes all but the newest of each, after confirming |

Duplicates are matched on size and name rather than by hashing, deliberately:
these files run to hundreds of megabytes, and re-reading each one to compare
digests would cost far more than the problem is worth. Two downloads of the
same format of the same video have identical byte lengths.

## Playing

**Play** opens the file inside the app, using ExoPlayer: scrubber, pause,
skip back 5 / forward 15, track and speed options, and a rotate button.
Tapping the "Saved - tap to play" notification lands in the same player rather
than handing the file to another app.

**Zoom**: pinch with two fingers to magnify up to 6x, and drag with two fingers
to move around while magnified. The on-screen `+` and `-` buttons do the same in
half steps, and the scale badge doubles as the way back to 1x. A separate button
switches between fitting the whole frame and cropping it to fill the screen,
which is what removes the black bars on a phone held upright.

Two implementation details are load-bearing:

- The player is inflated from `res/layout/player_view.xml` purely to set
  `surface_type="texture_view"`, which cannot be set in code. PlayerView
  defaults to a SurfaceView, which is punched through the window and ignores a
  parent's scale - zooming would move the controls and leave the picture where
  it was.
- Zoom gestures are read on the pointer **Initial** pass, and only when two or
  more fingers are down. PlayerView is a real Android View and consumes the
  touches it receives, so an ordinary gesture modifier on the parent never sees
  them; taking only multi-finger events leaves single taps to the player's own
  controller.

The player still offers **open in another app**, and that is not decoration:
ExoPlayer will not decode everything a phone's stock player might, and 2160p
and 1440p from YouTube are VP9 remuxed into MP4. When playback fails, the
player says so and points at that button rather than showing a black screen.

Closing the player releases it. An ExoPlayer left alive holds a hardware codec
and keeps audio focus, which is exactly the kind of thing that goes unnoticed
until the phone stops playing anything else.

## Settings

Theme (system/light/dark), accent colour (dynamic, or a fixed palette), list or
grid, sort field and direction, group by site, thumbnails on or off, full file
path on or off, and the download folder name. Changing the folder affects new
downloads; files already saved stay where they are.

## File naming

Titles come from arbitrary web pages, so names are made safe for every
filesystem the file might reach - not just Android's. Path separators, colons
and the other Windows-illegal characters become underscores; control characters
and the bidi/zero-width marks that make a name display differently from what it
is are stripped; leading dots (which hide the file) and trailing dots and spaces
(which Windows silently drops) are removed; Windows device names like `CON` and
`LPT1` are prefixed; and the stem is cut to 180 bytes so it stays inside the
255-byte limit once non-ASCII is encoded.

## Known limits

- **Playlists** are not supported; a playlist link resolves to the first video.
  Use the server app for playlists.
- **Sensitive and login-walled posts** need a sign-in; see above. Whether X's
  in-app login works on a given device depends on its bot detection, so the
  cookies.txt import is the reliable route.
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
