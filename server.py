#!/usr/bin/env python3
"""
Media Saver - a small self-hosted server that detects media behind a URL
(social media posts, video pages, direct links) and hands it to the browser
as a normal download.

Stdlib only, except for yt-dlp which does the extraction.

    python3 server.py --port 8080
"""

import argparse
import ipaddress
import json
import mimetypes
import os
import re
import shutil
import socket
import sys
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(BASE_DIR, "web")
WORK_DIR = os.path.join(BASE_DIR, ".work")

JOB_TTL_SECONDS = 60 * 60          # keep finished files around this long
MAX_PLAYLIST_ENTRIES = 60
IMAGE_EXTS = ("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")
PROBE_TIMEOUT = 60

COOKIE_BROWSER = None   # set by --cookies-from-browser

try:
    import yt_dlp
except ImportError:  # pragma: no cover - guarded at startup
    yt_dlp = None


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def has_ffmpeg():
    return shutil.which("ffmpeg") is not None


def human_size(n):
    if not n:
        return None
    units = ["B", "KB", "MB", "GB", "TB"]
    i = 0
    f = float(n)
    while f >= 1024 and i < len(units) - 1:
        f /= 1024.0
        i += 1
    return ("%.0f %s" if f >= 100 or i == 0 else "%.1f %s") % (f, units[i])


JUNK_TITLES = {"mpd", "m3u8", "index", "master", "manifest", "playlist",
               "video", "media", "stream", "chunklist"}


def nice_title(info, url):
    """Manifest URLs often carry no title, leaving yt-dlp to name the file after
    the extension (".mpd" -> "mpd.mp4"). Derive something from the URL instead."""
    title = (info.get("title") or "").strip().strip(".")
    if title and title.lower() not in JUNK_TITLES:
        return title

    parts = urllib.parse.urlsplit(url)
    host = (parts.hostname or "").replace("www.", "")
    # Walk the path backwards for the first segment that names something. The
    # last one is often ".mpd" or "index.m3u8"; the useful name sits above it.
    for segment in reversed([seg for seg in parts.path.split("/") if seg]):
        stem = os.path.splitext(segment.strip("."))[0].strip()
        if stem and stem.lower() not in JUNK_TITLES:
            return "%s - %s" % (host, stem) if host else stem
    return host or "media"


def safe_filename(name, fallback="media"):
    name = unicodedata.normalize("NFKD", name or "")
    name = re.sub(r"[\\/:*?\"<>|\x00-\x1f]", "_", name)
    name = re.sub(r"\s+", " ", name).strip(" .")
    if not name:
        name = fallback
    return name[:120]


def validate_url(raw):
    """Only allow public http(s) URLs. Keeps the server from being used as an
    SSRF hop into the LAN if it is exposed beyond localhost."""
    raw = (raw or "").strip()
    if not raw:
        raise ValueError("No URL provided.")
    if "://" not in raw:
        raw = "https://" + raw
    parts = urllib.parse.urlsplit(raw)
    if parts.scheme not in ("http", "https"):
        raise ValueError("Only http and https URLs are supported.")
    host = parts.hostname
    if not host:
        raise ValueError("That URL has no host.")
    if host.lower() in ("localhost", "localhost.localdomain") or host.endswith(".local"):
        raise ValueError("Local addresses are not allowed.")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        ip = None
    if ip is not None and (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved):
        raise ValueError("Private addresses are not allowed.")
    return raw


def ydl_opts_base():
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": False,
        "socket_timeout": 20,
        "retries": 3,
        "extractor_retries": 2,
        "ignoreerrors": False,
        "nocheckcertificate": False,
        "http_headers": {
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            )
        },
    }
    if COOKIE_BROWSER:
        opts["cookiesfrombrowser"] = (COOKIE_BROWSER,)
    return opts


def clean_error(exc):
    """Turn a yt-dlp exception into something a person can act on."""
    msg = re.sub(r"\x1b\[[0-9;]*m", "", str(exc))
    msg = re.sub(r"^ERROR:\s*", "", msg).strip()
    # yt-dlp appends long CLI hints and wiki links that mean nothing in a web UI.
    msg = re.split(r"\s*(?:Use --cookies|See\s+https://github\.com/yt-dlp"
                   r"|please report this issue|;\s*please report)", msg)[0].strip()
    msg = re.sub(r"^\[[\w:.-]+\]\s*(?:[\w-]+:\s+)?", "", msg).strip()
    lowered = msg.lower()

    if "unsupported url" in lowered:
        return ("This site isn't supported. Try the direct link to the post itself, "
                "or a direct link to the media file.")
    if ("only works when logged-in" in lowered or "login required" in lowered
            or "sign in" in lowered or "cookies" in lowered or "private" in lowered
            or "members-only" in lowered or "authentication" in lowered
            or "account" in lowered and "requir" in lowered):
        return ("This needs an account. Restart the server with "
                "--cookies-from-browser chrome (or safari/firefox) to reuse your "
                "existing login.")
    if "age" in lowered and "restrict" in lowered:
        return ("This is age-restricted. Restart the server with "
                "--cookies-from-browser chrome to reuse your login.")
    if "not available" in lowered or "removed" in lowered or "unavailable" in lowered:
        return "The media at that URL is unavailable, deleted, or region-blocked."
    if "requested format is not available" in lowered:
        return "That quality isn't available any more. Pick another format."
    if "http error 404" in lowered:
        return "That URL returned 404 — check the link is complete and still live."
    if "http error 403" in lowered:
        return "The site refused the request (403). It may be geo-blocked or need a login."
    if any(tok in msg for tok in ("NoneType", "Traceback", "object is not", "KeyError",
                                  "TypeError", "AttributeError", "IndexError")):
        return ("This site's extractor failed on that page. It is usually an "
                "out-of-date extractor - try: pip install -U yt-dlp")
    if ("nodename nor servname" in lowered or "name or service not known" in lowered
            or "failed to resolve" in lowered or "getaddrinfo" in lowered):
        return "That domain could not be found. Check the link for typos."
    if "connection" in lowered and ("refused" in lowered or "reset" in lowered):
        return "Could not connect to that site. It may be down or blocking us."
    if "timed out" in lowered or "timeout" in lowered:
        return "The site took too long to respond. Try again."
    return msg[:300] or "Could not read that link."


# --------------------------------------------------------------------------
# probing
# --------------------------------------------------------------------------

def describe_format(f):
    """Classify one yt-dlp format.

    yt-dlp uses the literal string "none" for a codec that is absent, and
    leaves the field unset when it simply does not know (typical for a direct
    link to a media file). Those two cases must not be conflated: an unknown
    codec means "one self-contained file", not "audio only".
    """
    vcodec = f.get("vcodec")
    acodec = f.get("acodec")
    codecs_known = bool(vcodec or acodec)
    has_video = bool(vcodec) and vcodec != "none"
    has_audio = bool(acodec) and acodec != "none"
    height = f.get("height")
    size = f.get("filesize") or f.get("filesize_approx")
    ext = (f.get("ext") or "").lower()

    if not codecs_known:
        kind = "file"
    elif has_video and has_audio:
        kind = "video"
    elif has_video:
        kind = "video-only"
    elif has_audio:
        kind = "audio"
    else:
        kind = "file"

    if kind in ("video", "video-only") and height:
        label = "%dp" % height
        if f.get("fps") and f["fps"] >= 50:
            label += "%d" % round(f["fps"])
    elif kind in ("video", "video-only"):
        label = f.get("resolution") or "Video"
    elif kind == "audio":
        abr = f.get("abr")
        label = "Audio %dk" % round(abr) if abr else "Audio"
    elif ext in IMAGE_EXTS:
        label = "Image"
    else:
        label = (ext.upper() + " file") if ext else "File"

    return {
        "format_id": f.get("format_id"),
        "ext": f.get("ext"),
        "label": label,
        "kind": kind,
        "height": height,
        "fps": f.get("fps"),
        "size": size,
        "size_human": human_size(size),
        "note": f.get("format_note") or "",
        "muxed": kind in ("video", "file"),
        "tbr": f.get("tbr") or 0,
        "vcodec": vcodec or "",
        "protocol": f.get("protocol") or "",
    }


def is_real_media(f):
    """Storyboards are exposed as formats but are contact sheets, not media."""
    if (f.get("ext") or "") == "mhtml" or (f.get("protocol") or "") == "mhtml":
        return False
    if (f.get("vcodec") or "") == "images":
        return False
    if "storyboard" in (f.get("format_note") or "").lower():
        return False
    return True


def rank_format(d):
    """Preference order for two formats offering the same thing.

    Protocol comes first: YouTube's HLS variants advertise a much higher tbr
    than the equivalent direct stream but frequently answer 401 to logged-out
    clients, so ranking on bitrate alone reliably picks a broken format. Then
    H.264, which plays everywhere, and only then bitrate.
    """
    direct = not d["protocol"].startswith(("m3u8", "http_dash"))
    return (direct, d["vcodec"].startswith(("avc1", "h264")), d["tbr"])


def build_options(info, ffmpeg):
    """Turn a raw yt-dlp format list into a short, tappable list of choices."""
    formats = [f for f in (info.get("formats") or [])
               if f.get("format_id") and is_real_media(f)]
    if not formats and info.get("url"):
        formats = [info]

    described = [describe_format(f) for f in formats]
    described = [d for d in described if d["format_id"]]

    muxed = [d for d in described if d["kind"] == "video" and d["height"]]
    video_only = [d for d in described if d["kind"] == "video-only" and d["height"]]
    audio_only = [d for d in described if d["kind"] == "audio"]
    plain_files = [d for d in described if d["kind"] == "file"]

    options = []
    seen = set()

    def add(opt):
        key = opt["selector"]
        if key in seen:
            return
        seen.add(key)
        options.append(opt)

    is_video = bool(muxed or video_only)

    if is_video:
        add({
            "selector": "best",
            "title": "Best quality",
            "subtitle": "Highest video + audio available",
            "kind": "video",
            "needs_ffmpeg": bool(video_only) and not muxed,
            "recommended": True,
        })

        # One entry per resolution the source actually offers. These are read
        # from the formats rather than matched against a fixed list of common
        # heights: plenty of sites ship non-standard ladders (100p/350p/750p),
        # and anything not on such a list would silently offer no video at all.
        heights = sorted({d["height"] for d in muxed + video_only if d["height"]},
                         reverse=True)[:8]
        for tier in heights:
            best_muxed = max((d for d in muxed if d["height"] == tier),
                             key=rank_format, default=None)
            best_split = max((d for d in video_only if d["height"] == tier),
                             key=rank_format, default=None)
            if best_muxed:
                add({
                    "selector": best_muxed["format_id"],
                    "title": "%dp" % tier,
                    "subtitle": " · ".join(x for x in [best_muxed["ext"].upper(),
                                                       best_muxed["size_human"],
                                                       "video + audio"] if x),
                    "kind": "video",
                    "needs_ffmpeg": False,
                })
            elif best_split and audio_only:
                add({
                    "selector": "%s+bestaudio" % best_split["format_id"],
                    "title": "%dp" % tier,
                    # The merge is written to MP4, so name the container the
                    # user actually receives, not the source stream's.
                    "subtitle": " · ".join(x for x in ["MP4",
                                                       best_split["size_human"],
                                                       "merged with audio"] if x),
                    "kind": "video",
                    "needs_ffmpeg": True,
                })

    if audio_only:
        best_audio = max(audio_only, key=rank_format, default=None)
        add({
            "selector": best_audio["format_id"] if best_audio else "bestaudio",
            "title": "Audio only",
            "subtitle": " · ".join(x for x in [(best_audio["ext"] or "").upper(),
                                               best_audio["size_human"] if best_audio else None,
                                               "no video"] if x),
            "kind": "audio",
            "needs_ffmpeg": False,
            "recommended": not is_video,
        })

    if not options:
        # A direct file link, an image, or a source that publishes the same
        # title as several alternative files (archive.org does this).
        def file_rank(d):
            ext = (d["ext"] or "").lower()
            # mp4 plays everywhere; size is a poor proxy for "best".
            return (0 if ext == "mp4" else 1 if ext in ("m4v", "mov", "webm") else 2,
                    -(d["size"] or 0))

        ranked = sorted(plain_files, key=file_rank)
        for i, d in enumerate(ranked[:8]):
            ext = (d["ext"] or "").lower()
            is_image = ext in IMAGE_EXTS
            add({
                "selector": d["format_id"],
                "title": ("Save image" if is_image else (ext.upper() or "Download"))
                         if len(ranked) > 1 else ("Save image" if is_image else "Download"),
                "subtitle": " \u00b7 ".join(x for x in [ext.upper() or None, d["size_human"]] if x)
                            or "Single file",
                "kind": "image" if is_image else "file",
                "needs_ffmpeg": False,
                "recommended": i == 0,
            })

    if not options:
        ext = (info.get("ext") or "").lower()
        add({
            "selector": "best",
            "title": "Save image" if ext in IMAGE_EXTS else "Download",
            "subtitle": ext.upper() or "Single file",
            "kind": "image" if ext in IMAGE_EXTS else "file",
            "needs_ffmpeg": False,
            "recommended": True,
        })

    return options, described


def probe(url):
    opts = ydl_opts_base()
    opts["extract_flat"] = "in_playlist"
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    ffmpeg = has_ffmpeg()

    if info.get("_type") == "playlist" or (info.get("entries") is not None):
        entries = []
        for e in (info.get("entries") or [])[:MAX_PLAYLIST_ENTRIES]:
            if not e:
                continue
            entries.append({
                "title": e.get("title") or "Untitled",
                "url": e.get("url") or e.get("webpage_url"),
                "duration": e.get("duration"),
                "thumbnail": e.get("thumbnail"),
            })
        return {
            "type": "playlist",
            "title": nice_title(info, url),
            "uploader": info.get("uploader") or info.get("channel") or "",
            "count": len(entries),
            "entries": entries,
            "source": info.get("extractor_key") or "",
            "ffmpeg": ffmpeg,
        }

    options, formats = build_options(info, ffmpeg)
    thumb = info.get("thumbnail")
    if not thumb:
        thumbs = info.get("thumbnails") or []
        if thumbs:
            thumb = thumbs[-1].get("url")

    return {
        "type": "single",
        "title": nice_title(info, url),
        "uploader": info.get("uploader") or info.get("channel") or info.get("uploader_id") or "",
        "duration": info.get("duration"),
        "thumbnail": thumb,
        "webpage_url": info.get("webpage_url") or url,
        "source": info.get("extractor_key") or "",
        "options": options,
        "formats": formats,
        "ffmpeg": ffmpeg,
    }


# --------------------------------------------------------------------------
# download jobs
# --------------------------------------------------------------------------

class Job(object):
    def __init__(self, url, selector, kind):
        self.id = uuid.uuid4().hex
        self.url = url
        self.selector = selector
        self.kind = kind
        self.status = "queued"       # queued | running | ready | error | cancelled
        self.streams_done = 0        # a merged format downloads video, then audio
        self.streams_expected = 2 if "+" in selector else 1
        self.progress = 0.0
        self.downloaded = 0
        self.total = None
        self.speed = None
        self.eta = None
        self.title = None
        self.path = None
        self.filename = None
        self.error = None
        self.created = time.time()
        self.finished = None
        self.cancel = threading.Event()
        self.dir = os.path.join(WORK_DIR, self.id)

    def public(self):
        return {
            "id": self.id,
            "status": self.status,
            "progress": round(self.progress, 4),
            "downloaded": self.downloaded,
            "downloaded_human": human_size(self.downloaded),
            "total": self.total,
            "total_human": human_size(self.total),
            "speed_human": (human_size(self.speed) + "/s") if self.speed else None,
            "eta": self.eta,
            "title": self.title,
            "filename": self.filename,
            "error": self.error,
        }


JOBS = {}
JOBS_LOCK = threading.Lock()


def reap_jobs():
    now = time.time()
    with JOBS_LOCK:
        stale = [j for j in JOBS.values()
                 if j.finished and now - j.finished > JOB_TTL_SECONDS]
        for j in stale:
            JOBS.pop(j.id, None)
    for j in stale:
        shutil.rmtree(j.dir, ignore_errors=True)


def format_selector(selector, kind, ffmpeg):
    """Expand a UI choice into a yt-dlp format string.

    Merged output is written to MP4, so the audio is steered towards AAC/m4a and
    the video towards H.264: Opus or VP9 remuxed into MP4 is technically legal
    but will not play in QuickTime or on iOS, which is where these files land.
    Each preference falls back to "whatever exists" so nothing becomes
    undownloadable.
    """
    if selector == "best":
        if kind == "audio":
            return "bestaudio[ext=m4a]/bestaudio/best"
        if not ffmpeg:
            return "best[vcodec!=none][acodec!=none]/best"
        return ("bestvideo[vcodec^=avc1]+bestaudio[ext=m4a]/"
                "bestvideo*+bestaudio[ext=m4a]/"
                "bestvideo*+bestaudio/best")
    if selector in ("bestaudio", "bestvideo"):
        return selector + "/best"
    if selector.endswith("+bestaudio"):
        video_id = selector[:-len("+bestaudio")]
        if not ffmpeg:
            return video_id + "/best"
        return "{v}+bestaudio[ext=m4a]/{v}+bestaudio/{v}/best".format(v=video_id)
    if "+" in selector and not ffmpeg:
        return selector.split("+")[0] + "/best"
    return selector + "/best"


def run_job(job):
    os.makedirs(job.dir, exist_ok=True)
    ffmpeg = has_ffmpeg()

    def hook(d):
        if job.cancel.is_set():
            raise yt_dlp.utils.DownloadError("cancelled")
        if d.get("status") == "downloading":
            job.status = "running"
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            job.total = total
            job.downloaded = d.get("downloaded_bytes") or 0
            job.speed = d.get("speed")
            job.eta = d.get("eta")
            if total:
                # A merged format is fetched as two separate downloads, each
                # reporting its own 0-100%. Spread them across one bar so the
                # progress never appears to run backwards.
                span = max(job.streams_expected, job.streams_done + 1)
                frac = job.downloaded / float(total)
                job.progress = min(0.99, (job.streams_done + frac) / span)
        elif d.get("status") == "finished":
            job.streams_done += 1
            job.progress = min(0.99, job.streams_done / float(
                max(job.streams_expected, job.streams_done)))

    def pp_hook(d):
        if d.get("status") == "started":
            job.status = "processing"

    resolved_format = format_selector(job.selector, job.kind, ffmpeg)
    job.streams_expected = 2 if "+" in resolved_format.split("/")[0] else 1

    opts = ydl_opts_base()
    opts.update({
        "outtmpl": os.path.join(job.dir, "%(title).100B.%(ext)s"),
        "format": resolved_format,
        "noplaylist": True,
        "progress_hooks": [hook],
        "postprocessor_hooks": [pp_hook],
        "restrictfilenames": False,
        "windowsfilenames": True,
        "overwrites": True,
        "concurrent_fragment_downloads": 4,
    })
    if ffmpeg and job.kind == "video":
        opts["merge_output_format"] = "mp4"

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(job.url, download=True)
        if job.cancel.is_set():
            job.status = "cancelled"
            job.finished = time.time()
            return

        job.title = nice_title(info, job.url)
        produced = []
        for root, _dirs, files in os.walk(job.dir):
            for name in files:
                if name.endswith((".part", ".ytdl", ".temp")):
                    continue
                produced.append(os.path.join(root, name))
        if not produced:
            raise RuntimeError("Nothing was downloaded.")

        if len(produced) > 1:
            archive = os.path.join(WORK_DIR, job.id + "_bundle")
            shutil.make_archive(archive, "zip", job.dir)
            job.path = archive + ".zip"
            job.filename = safe_filename(job.title) + ".zip"
        else:
            job.path = produced[0]
            ext = os.path.splitext(job.path)[1] or ""
            job.filename = safe_filename(job.title) + ext

        job.total = os.path.getsize(job.path)
        job.downloaded = job.total
        job.progress = 1.0
        job.status = "ready"
    except Exception as exc:  # noqa: BLE001 - surfaced to the client
        if job.cancel.is_set():
            job.status = "cancelled"
        else:
            job.status = "error"
            job.error = clean_error(exc)
        shutil.rmtree(job.dir, ignore_errors=True)
    finally:
        job.finished = time.time()
        reap_jobs()


# --------------------------------------------------------------------------
# http layer
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "MediaSaver/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("  %s\n" % (fmt % args))

    # -- small response helpers ------------------------------------------
    def send_json(self, obj, status=HTTPStatus.OK):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_error_json(self, message, status=HTTPStatus.BAD_REQUEST):
        self.send_json({"error": message}, status)

    def read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except ValueError:
            return {}

    def serve_static(self, relpath, cache="no-cache"):
        path = os.path.normpath(os.path.join(WEB_DIR, relpath))
        if not path.startswith(WEB_DIR) or not os.path.isfile(path):
            self.send_error_json("Not found", HTTPStatus.NOT_FOUND)
            return
        ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
        if path.endswith(".webmanifest"):
            ctype = "application/manifest+json"
        with open(path, "rb") as fh:
            body = fh.read()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text/") or "json" in ctype or "javascript" in ctype else ""))
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    # -- routing ----------------------------------------------------------
    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path in ("/", "/index.html"):
            return self.serve_static("index.html")
        if path in ("/app.js", "/style.css", "/sw.js", "/manifest.webmanifest", "/icon.svg"):
            return self.serve_static(path.lstrip("/"))
        if path == "/api/health":
            return self.send_json({"ok": True, "ffmpeg": has_ffmpeg(),
                                   "ytdlp": yt_dlp.version.__version__,
                                   "cookies": COOKIE_BROWSER})
        if path == "/api/thumb":
            return self.proxy_thumb(query.get("u", [""])[0])
        if path.startswith("/api/jobs/"):
            rest = path[len("/api/jobs/"):]
            if rest.endswith("/file"):
                return self.serve_job_file(rest[:-len("/file")])
            return self.job_status(rest)
        return self.send_error_json("Not found", HTTPStatus.NOT_FOUND)

    def do_POST(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        if path == "/api/probe":
            return self.handle_probe()
        if path == "/api/jobs":
            return self.handle_create_job()
        if path.startswith("/api/jobs/") and path.endswith("/cancel"):
            return self.handle_cancel(path[len("/api/jobs/"):-len("/cancel")])
        return self.send_error_json("Not found", HTTPStatus.NOT_FOUND)

    # -- endpoints --------------------------------------------------------
    def handle_probe(self):
        data = self.read_json()
        try:
            url = validate_url(data.get("url"))
        except ValueError as exc:
            return self.send_error_json(str(exc))
        try:
            return self.send_json(probe(url))
        except Exception as exc:  # noqa: BLE001
            return self.send_error_json(clean_error(exc), HTTPStatus.UNPROCESSABLE_ENTITY)

    def handle_create_job(self):
        data = self.read_json()
        try:
            url = validate_url(data.get("url"))
        except ValueError as exc:
            return self.send_error_json(str(exc))
        selector = (data.get("selector") or "best").strip()
        # Format ids are not always plain words: DASH sources use ids like
        # "video_eng=2200000". The value is handed to yt-dlp's format parser,
        # never to a shell, so this only needs to exclude obvious junk.
        if not re.match(r"^[A-Za-z0-9_+\-./=:~@]{1,160}$", selector):
            return self.send_error_json("Invalid format selection.")
        kind = data.get("kind") if data.get("kind") in ("video", "audio", "image", "file") else "video"

        job = Job(url, selector, kind)
        with JOBS_LOCK:
            JOBS[job.id] = job
        threading.Thread(target=run_job, args=(job,), daemon=True).start()
        return self.send_json(job.public(), HTTPStatus.ACCEPTED)

    def job_status(self, job_id):
        job = JOBS.get(job_id)
        if not job:
            return self.send_error_json("Unknown job", HTTPStatus.NOT_FOUND)
        return self.send_json(job.public())

    def handle_cancel(self, job_id):
        job = JOBS.get(job_id)
        if not job:
            return self.send_error_json("Unknown job", HTTPStatus.NOT_FOUND)
        job.cancel.set()
        return self.send_json({"ok": True})

    def serve_job_file(self, job_id):
        job = JOBS.get(job_id)
        if not job or job.status != "ready" or not job.path or not os.path.isfile(job.path):
            return self.send_error_json("File not ready", HTTPStatus.NOT_FOUND)

        size = os.path.getsize(job.path)
        ctype = mimetypes.guess_type(job.filename)[0] or "application/octet-stream"
        start, end = 0, size - 1
        status = HTTPStatus.OK

        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(0, size - int(m.group(2)))
                if start <= end < size:
                    status = HTTPStatus.PARTIAL_CONTENT

        length = end - start + 1
        quoted = urllib.parse.quote(job.filename)
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if status == HTTPStatus.PARTIAL_CONTENT:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header(
            "Content-Disposition",
            "attachment; filename=\"%s\"; filename*=UTF-8''%s" % (
                re.sub(r'[^\x20-\x7e]', "_", job.filename).replace('"', ""), quoted),
        )
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

        with open(job.path, "rb") as fh:
            fh.seek(start)
            remaining = length
            while remaining > 0:
                chunk = fh.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)

    def proxy_thumb(self, url):
        """Thumbnails are proxied because most CDNs block cross-origin hotlinking."""
        try:
            url = validate_url(url)
        except ValueError as exc:
            return self.send_error_json(str(exc))
        req = urllib.request.Request(url, headers=ydl_opts_base()["http_headers"])
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                ctype = resp.headers.get("Content-Type", "image/jpeg")
                if not ctype.startswith("image/"):
                    return self.send_error_json("Not an image", HTTPStatus.BAD_REQUEST)
                body = resp.read(8 * 1024 * 1024)
        except Exception:  # noqa: BLE001
            return self.send_error_json("Thumbnail unavailable", HTTPStatus.BAD_GATEWAY)
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.end_headers()
        self.wfile.write(body)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:  # noqa: BLE001
        return "127.0.0.1"
    finally:
        s.close()


def main():
    parser = argparse.ArgumentParser(description="Media Saver server")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="0.0.0.0",
                        help="0.0.0.0 lets your phone reach it over Wi-Fi (default)")
    parser.add_argument("--cookies-from-browser", dest="cookies", default=None,
                        metavar="BROWSER",
                        help="reuse this browser's login cookies (chrome, safari, "
                             "firefox, brave, edge) so private or login-walled "
                             "posts can be read")
    args = parser.parse_args()

    global COOKIE_BROWSER
    COOKIE_BROWSER = args.cookies

    if yt_dlp is None:
        sys.exit("yt-dlp is not installed. Run:  pip install -U yt-dlp")

    os.makedirs(WORK_DIR, exist_ok=True)
    shutil.rmtree(WORK_DIR, ignore_errors=True)
    os.makedirs(WORK_DIR, exist_ok=True)

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True

    print("\n  Media Saver")
    print("  ───────────")
    print("  On this Mac : http://localhost:%d" % args.port)
    if args.host == "0.0.0.0":
        print("  On your phone: http://%s:%d   (same Wi-Fi)" % (lan_ip(), args.port))
    if COOKIE_BROWSER:
        print("  cookies     : reusing %s login cookies" % COOKIE_BROWSER)
    print("  ffmpeg      : %s" % ("found" if has_ffmpeg() else "missing - merged HD formats disabled"))
    print("  Ctrl-C to stop\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n  Stopped.")
    finally:
        shutil.rmtree(WORK_DIR, ignore_errors=True)


if __name__ == "__main__":
    main()
