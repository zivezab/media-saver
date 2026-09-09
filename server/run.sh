#!/usr/bin/env bash
# Start the Media Saver server. Extra arguments are passed through, e.g.
#   ./run.sh --port 9000 --cookies-from-browser chrome
set -euo pipefail
cd "$(dirname "$0")"
[ -x .venv/bin/python ] || { echo "Run ./setup.sh first."; exit 1; }
exec ./.venv/bin/python server.py "$@"
