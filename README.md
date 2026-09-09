# Media Saver

A small, self-hosted web app that takes a link to a post or video, works out what
media is behind it, and hands the file to your browser as a normal download —
so it lands in your Downloads folder like anything else you save.

The UI is built for a phone: one input, big tap targets, live progress, and it
installs to the home screen so you can share links to it straight from other apps.

## Setup

```bash
./setup.sh
```

That creates a virtualenv and installs [yt-dlp](https://github.com/yt-dlp/yt-dlp),
which does the actual media extraction. Everything else is Python standard library.

**Python 3.10 or newer is required.** yt-dlp has dropped 3.9, and this matters more
than a deprecation notice suggests: pip will silently install the last yt-dlp release
that still supported your interpreter, and an outdated yt-dlp quietly loses formats.
On the 3.9 that ships with macOS, YouTube offered only a single 360p option; on 3.13
the same video offers everything up to 2160p. `setup.sh` picks the newest interpreter
it can find and rebuilds the venv if the existing one is older. If macOS only has
3.9, install a current Python first:

```bash
brew install python@3.13
```

**Install ffmpeg too** if you want high-resolution video:

```bash
brew install ffmpeg
```

Most platforms serve 1080p and above as separate video and audio streams. Without
ffmpeg those cannot be joined, and the app quietly limits you to the best
single-file format (often 360p–720p). The app detects ffmpeg at startup and greys
out any option it cannot deliver, so you will never get a silent video.

## Running it

```bash
./run.sh
```

It prints two addresses:

```
On this Mac  : http://localhost:8080
On your phone: http://192.168.1.42:8080   (same Wi-Fi)
```

Open the second one on your phone. Useful flags:

| Flag | What it does |
| --- | --- |
| `--port 9000` | Listen on a different port |
| `--host 127.0.0.1` | This Mac only; do not expose to the network |
| `--cookies-from-browser chrome` | Reuse your browser login (see below) |

## Using it

1. Copy a link from a social or video app.
2. Open Media Saver and tap **Paste** — it reads the link and looks it up straight away.
3. Pick a quality. **Best quality** is preselected.
4. The server fetches it, showing size, speed and time remaining, then your
   browser saves the file.

Paste a link to a playlist or a profile feed and you get a tappable list of items
instead; choose one to see its formats.

**Show all formats** under the quality list exposes every stream the site
offers — individual bitrates, audio-only tracks, alternate codecs — for when the
presets are not what you want.

### Install it to your home screen

On the phone, use *Add to Home Screen*. Two things improve once installed:

- It opens fullscreen, without browser chrome.
- On Android it registers as a share target, so you can hit **Share → Media Saver**
  inside another app and it fills in the link and starts looking it up.

On iOS, share sheets cannot target web apps; use the Share → Copy Link → **Paste**
flow instead, which is two taps.

### Private and login-walled posts

Plenty of sites (Vimeo, and anything behind a follower-only or age gate) will not
serve anything to a logged-out visitor. Start the server with your browser's
cookies to act as your logged-in self:

```bash
./run.sh --cookies-from-browser chrome     # or safari, firefox, brave, edge
```

macOS will ask for Keychain permission the first time Chrome's cookies are read.
Only do this on a machine you control — the cookies are your live login.

### When something fails

Sites change their internals constantly, and yt-dlp tracks those changes. If you
see *"this site's extractor failed"* or a format vanishes, update first:

```bash
./update.sh
```

That fixes the large majority of breakages. If formats look oddly limited — a
site offering one low resolution when you know it has more — check that the venv
is on a current Python, since that is what governs which yt-dlp you can install.

Sites that require an account (Vimeo and Reddit both do now) will not serve
anything to a logged-out visitor at all; `--cookies-from-browser` is the answer
there.

## How it works

```
browser  ──POST /api/probe──▶  yt-dlp metadata extraction (no download)
         ◀── title, thumbnail, quality options ──

browser  ──POST /api/jobs───▶  background thread downloads to .work/<job id>/
         ──GET  /api/jobs/id─▶  progress polled ~1×/sec
         ──GET  .../file ────▶  served as Content-Disposition: attachment
```

The download happens on the server rather than in the page because browsers
cannot fetch cross-origin media, and because separate video and audio streams
have to be merged before they are a usable file. Finished files live in `.work/`
for an hour and are then removed; the whole directory is wiped on restart.

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/probe` | `{url}` → title, thumbnail, quality options |
| `POST` | `/api/jobs` | `{url, selector, kind}` → job id |
| `GET` | `/api/jobs/<id>` | Status, progress, speed, ETA |
| `POST` | `/api/jobs/<id>/cancel` | Stop a running job |
| `GET` | `/api/jobs/<id>/file` | The file, as a download (supports Range) |
| `GET` | `/api/thumb?u=` | Thumbnail proxy (CDNs block hotlinking) |
| `GET` | `/api/health` | ffmpeg presence, yt-dlp version |

### A note on scope

This binds to `0.0.0.0` by default so your phone can reach it, which means anyone
on your network can use it. It is built for a home network — there is no
authentication. Do not port-forward it to the public internet. URLs pointing at
private or loopback addresses are rejected so the server cannot be used to reach
into your LAN.

Downloading is subject to each platform's terms and to copyright. Use it for
things you have the right to keep — your own uploads, your own archives,
openly-licensed material.
