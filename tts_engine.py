#!/usr/bin/env python3
"""
Advanced Text-to-Speech Engine for Ultron.
Features:
- Primary: Ultra-realistic natural AI neural voice (Microsoft Edge Neural Voice, zero API keys required).
- Fallback: Offline tuned Jarvis-style deep synthetic voice via espeak.
- UI callback support for live subtitle feedback on Floating HUD.
"""

import os
import sys
import shutil
import asyncio
import tempfile
import subprocess
import threading

class TTSEngine:
    def __init__(self, voice_enabled=True, voice="en-US-ChristopherNeural"):
        self.voice_enabled = voice_enabled
        self.voice = voice  # High-quality options: en-US-ChristopherNeural, en-US-GuyNeural, en-US-BrianNeural
        self.callback = None
        self.mpg123_bin = shutil.which("mpg123")
        self.espeak_bin = shutil.which("espeak") or shutil.which("espeak-ng")
        self.has_edge_tts = False

        try:
            import edge_tts
            self.has_edge_tts = True
        except ImportError:
            self.has_edge_tts = False

    def set_callback(self, cb):
        """Sets a listener callback function(text) for UI display updates."""
        self.callback = cb

    def _speak_neural(self, text):
        """Generates natural neural audio using edge-tts and plays via mpg123."""
        import edge_tts

        async def _synth():
            communicate = edge_tts.Communicate(text, self.voice)
            with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
                tmp_path = f.name
            try:
                await communicate.save(tmp_path)
                subprocess.run(
                    [self.mpg123_bin, "-q", tmp_path],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
            finally:
                if os.path.exists(tmp_path):
                    try:
                        os.remove(tmp_path)
                    except Exception:
                        pass

        asyncio.run(_synth())

    def _speak_espeak(self, text, block=True):
        """Fallback to local espeak with tuned Jarvis acoustic pitch and rate."""
        if not self.espeak_bin:
            return
        try:
            # -v en-us+m3: American male variant 3 (Jarvis timbre)
            # -p 42: Lower pitch (deep authoritative AI voice)
            # -s 160: Controlled cadence
            cmd = [self.espeak_bin, "-v", "en-us+m3", "-p", "42", "-s", "160", text]
            if block:
                subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

    def speak(self, text, block=True):
        """Prints message, triggers UI callback, and speaks using natural neural voice."""
        print(f"\n🤖 Ultron: {text}")

        # Update UI if callback is hooked
        if self.callback:
            try:
                self.callback(text)
            except Exception:
                pass

        if not self.voice_enabled or not text:
            return

        # 1. Try ultra-realistic neural voice first
        if self.has_edge_tts and self.mpg123_bin:
            try:
                self._speak_neural(text)
                return
            except Exception:
                # If network is unavailable or request fails, gracefully fallback
                pass

        # 2. Offline fallback to tuned Jarvis espeak voice
        self._speak_espeak(text, block=block)

if __name__ == "__main__":
    tts = TTSEngine()
    tts.speak("Greetings. I am Ultron. All systems are operational.")
