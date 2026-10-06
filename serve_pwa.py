#!/usr/bin/env python3
"""
Lightweight Web & PWA Server for Ultron Assistant.
Runs with ZERO external libraries (pure Python standard library).
Allows testing in any browser and instant 1-click installation on Android / ChromeOS!
"""

import http.server
import socketserver
import os
import sys
import webbrowser

PORT = 8085
DIRECTORY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pwa")

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

def run():
    os.chdir(DIRECTORY)
    with socketserver.TCPServer(("", PORT), Handler) as httpd:
        url = f"http://localhost:{PORT}"
        print(f"==================================================")
        print(f"  🤖 Ultron Assistant Web & Android PWA Server")
        print(f"  Running at: {url}")
        print(f"  - On Android: Open {url} in Chrome and tap 'Add to Home Screen'")
        print(f"  - On Desktop: Open in Chrome/Browser to test Gemini HUD")
        print(f"==================================================")
        try:
            webbrowser.open(url)
        except Exception:
            pass
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")

if __name__ == "__main__":
    run()
