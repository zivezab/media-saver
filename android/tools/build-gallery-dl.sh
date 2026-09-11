#!/usr/bin/env bash
#
# Rebuild app/src/main/assets/gallery-dl.pyz - the photo extractor bundled with
# the app. Run it to pick up a new gallery-dl release:
#
#     tools/build-gallery-dl.sh            # latest release
#     tools/build-gallery-dl.sh 1.32.11    # a specific version
#
# The app also updates gallery-dl on its own at runtime, so this is about what
# a fresh install starts with, not about keeping it current.
#
# Only pure-Python wheels are accepted. The archive runs on the Python 3.12 that
# youtubedl-android ships on the phone; anything with a compiled extension would
# be built for this Mac and fail there, so pip is told to refuse it outright.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-}"
SPEC="gallery-dl${VERSION:+==$VERSION}"
OUT="app/src/main/assets/gallery-dl.pyz"

PIP="${PIP:-}"
if [ -z "$PIP" ]; then
  for c in ../server/.venv/bin/pip pip3; do
    if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then PIP="$c"; break; fi
  done
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"$PIP" install -q --target "$WORK/build" \
  --platform any --only-binary=:all: --python-version 3.12 --implementation py \
  "$SPEC"

if find "$WORK/build" \( -name "*.so" -o -name "*.pyd" \) | grep -q .; then
  echo "A compiled extension slipped in; it would not run on the phone." >&2
  exit 1
fi

rm -rf "$WORK/build/bin" "$WORK/build/share"
find "$WORK/build" -name "__pycache__" -type d -prune -exec rm -rf {} +

cat > "$WORK/build/__main__.py" <<'PY'
# Entry point for the gallery-dl bundle shipped with Media Saver.
#
# The app can download a newer gallery-dl wheel and point GDL_OVERRIDE at it.
# Wheels are zip files and gallery-dl is pure Python, so putting one at the
# front of sys.path makes it win over the copy inside this archive - which is
# how the photo extractor stays current without a new app release.
import os
import sys

override = os.environ.get("GDL_OVERRIDE")
if override and os.path.isfile(override):
    sys.path.insert(0, override)

from gallery_dl import main

sys.exit(main())
PY

python3 -m zipapp "$WORK/build" -o "$OUT" -c
BUILT=$(ls "$WORK/build" | grep -oE '^gallery_dl-[0-9]+(\.[0-9]+)*' | head -1 | sed 's/gallery_dl-//')
echo "$BUILT" > app/src/main/assets/gallery-dl.version
SIZE=$(wc -c < "$OUT" | awk '{printf "%.1f MB", $1/1048576}')
echo "Built $OUT (gallery-dl $BUILT, $SIZE)"
