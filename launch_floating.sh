#!/bin/bash
# Ultron Gemini Floating Assistant Launcher
# Launches the Gemini Floating Capsule outside the terminal

export DISPLAY="${DISPLAY:-:0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"
exec python3 "$SCRIPT_DIR/floating_widget.py" "$@"
