#!/usr/bin/env bash
# Sites change constantly; refreshing yt-dlp fixes most "extractor failed" errors.
set -euo pipefail
cd "$(dirname "$0")"
./.venv/bin/pip install --upgrade yt-dlp
./.venv/bin/python -c 'import yt_dlp;print("now on yt-dlp", yt_dlp.version.__version__)'
