#!/bin/bash
# Ultron Floating Button Launcher
# Launches Ultron Floating Assistant outside the terminal

export DISPLAY="${DISPLAY:-:0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_BIN="$SCRIPT_DIR/venv/bin/python3"

if [ ! -f "$PYTHON_BIN" ]; then
    PYTHON_BIN="python3"
fi

cd "$SCRIPT_DIR"
exec "$PYTHON_BIN" "$SCRIPT_DIR/floating_widget.py" "$@"
