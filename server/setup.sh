#!/usr/bin/env bash
# One-time setup: create a virtualenv and install yt-dlp into it.
set -euo pipefail
cd "$(dirname "$0")"

# yt-dlp has deprecated Python 3.9, so prefer a newer interpreter when one exists.
PY=""
for c in python3.13 python3.12 python3.11 python3.10 python3; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "No python3 found."; exit 1; }
echo "Using $PY ($($PY -V 2>&1))"

# An existing venv is pinned to the interpreter that built it, so a stale one
# (say, the deprecated 3.9) has to be replaced rather than reused.
if [ -x .venv/bin/python ]; then
  have=$(./.venv/bin/python -c 'import sys;print("%d.%d"%sys.version_info[:2])')
  want=$("$PY" -c 'import sys;print("%d.%d"%sys.version_info[:2])')
  if [ "$have" != "$want" ]; then
    echo "Replacing existing venv (Python $have) with Python $want"
    rm -rf .venv
  fi
fi

"$PY" -m venv .venv
./.venv/bin/pip -q install --upgrade pip
./.venv/bin/pip -q install -r requirements.txt
echo "yt-dlp $(./.venv/bin/python -c 'import yt_dlp;print(yt_dlp.version.__version__)') installed."

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo
  echo "Note: ffmpeg is not installed. Without it, resolutions that ship video and"
  echo "audio as separate streams (most 1080p+) cannot be merged. Install with:"
  echo "    brew install ffmpeg"
fi

echo
echo "Done. Start it with:  ./run.sh"
