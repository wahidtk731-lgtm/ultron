#!/usr/bin/env python3
"""
Advanced Natural Male TTS Engine for Ultron.
Features:
- High-quality, natural Microsoft Neural Male Voice (zero MB model footprint, studio AI quality).
- Strictly male voice (en-US-BrianNeural / en-US-AndrewNeural / en-US-GuyNeural) - NO female/lady voices.
- Zero robotic echo or boring metallic pitch distortion.
- Local persistent MP3 cache in ~/.cache/ultron/tts_cache for 0ms instant offline playback.
- Clean offline fallback to standard espeak (clean pitch & rate, no metallic echo filters).
- Thread-safe, non-blocking UI callback integration for Google Assistant Edge UI subtitles.
"""

import os
import sys
import hashlib
import shutil
import asyncio
import subprocess
import threading

class TTSEngine:
    def __init__(self, voice="en-US-BrianNeural", voice_enabled=True):
        self.voice_enabled = voice_enabled
        # World-class natural AI male voices (Strictly male, no female/girl voice)
        self.voice = voice  # Options: en-US-BrianNeural, en-US-AndrewNeural, en-US-GuyNeural
        self.fallback_voices = ["en-US-AndrewNeural", "en-US-GuyNeural", "en-US-ChristopherNeural"]
        self.callback = None
        self.finish_callback = None
        
        # Audio players
        self.mpg123_bin = shutil.which("mpg123")
        self.paplay_bin = shutil.which("paplay")
        self.espeak_bin = shutil.which("espeak") or shutil.which("espeak-ng")
        
        # Local persistent cache directory
        self.cache_dir = os.path.expanduser("~/.cache/ultron/tts_cache")
        try:
            os.makedirs(self.cache_dir, exist_ok=True)
        except Exception:
            pass

        self.has_edge_tts = False
        try:
            import edge_tts
            self.has_edge_tts = True
        except ImportError:
            self.has_edge_tts = False

    def set_callback(self, cb):
        """Sets a listener callback function(text) for UI subtitle display updates."""
        self.callback = cb

    def _get_cache_path(self, text, voice_name):
        """Computes a unique cache filename based on text hash and voice."""
        key = f"{voice_name}::{text.strip()}".encode("utf-8")
        h = hashlib.sha256(key).hexdigest()[:24]
        return os.path.join(self.cache_dir, f"{h}.mp3")

    def _play_audio_file(self, file_path):
        """Plays an audio file via mpg123 cleanly without blocking or echoing."""
        if not os.path.exists(file_path):
            return False

        if self.mpg123_bin:
            try:
                res = subprocess.run(
                    [self.mpg123_bin, "-q", file_path],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL
                )
                return res.returncode == 0
            except Exception:
                pass
        return False

    def _synthesize_edge_tts(self, text, output_path, voice_name):
        """Synthesizes text using edge-tts asynchronously."""
        import edge_tts

        async def _synth():
            communicate = edge_tts.Communicate(text, voice_name)
            await communicate.save(output_path)

        try:
            # Handle potential existing event loops in current thread
            try:
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    # Run in a separate thread if event loop is already active
                    res = []
                    t = threading.Thread(target=lambda: res.append(asyncio.run(_synth())))
                    t.start()
                    t.join(timeout=8.0)
                    return os.path.exists(output_path) and os.path.getsize(output_path) > 100
                else:
                    loop.run_until_complete(_synth())
            except RuntimeError:
                asyncio.run(_synth())
            return os.path.exists(output_path) and os.path.getsize(output_path) > 100
        except Exception:
            return False

    def _speak_neural(self, text):
        """Attempts neural voice playback from cache or Edge-TTS."""
        if not self.has_edge_tts or not self.mpg123_bin:
            return False

        # 1. Check local persistent cache
        cached_file = self._get_cache_path(text, self.voice)
        if os.path.exists(cached_file) and os.path.getsize(cached_file) > 100:
            return self._play_audio_file(cached_file)

        # 2. Try primary male voice (Brian)
        success = self._synthesize_edge_tts(text, cached_file, self.voice)
        if success:
            return self._play_audio_file(cached_file)

        # 3. Try alternative male voice fallbacks
        for alt_voice in self.fallback_voices:
            alt_cache = self._get_cache_path(text, alt_voice)
            if self._synthesize_edge_tts(text, alt_cache, alt_voice):
                return self._play_audio_file(alt_cache)

        return False

    def _speak_clean_offline(self, text, block=True):
        """Clean offline male voice fallback (NO echo, NO boring robotic metallic pitch drop)."""
        if not self.espeak_bin:
            return

        try:
            # -v en-us: Standard English male voice
            # -p 50: Natural human pitch (not pitch 42 which creates robotic echo!)
            # -s 155: Natural human speaking rate
            cmd = [self.espeak_bin, "-v", "en-us", "-p", "50", "-s", "155", text]
            if block:
                subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass

    def set_finish_callback(self, cb):
        """Sets a listener callback for when speech audio playback completes."""
        self.finish_callback = cb

    def speak(self, text, block=True):
        """
        Speaks text using beautiful, natural neural male voice.
        Triggers UI callback for visual subtitles and finish callback on completion.
        """
        if not text or not str(text).strip():
            return

        clean_text = str(text).strip()
        print(f"\n🤖 Ultron: {clean_text}")

        # Update UI subtitle callback (start of speaking)
        if self.callback:
            try:
                self.callback(clean_text)
            except Exception:
                pass

        try:
            if not self.voice_enabled:
                return

            # 1. Ultra-realistic natural male neural voice (0 MB model, beautiful AI voice)
            neural_success = self._speak_neural(clean_text)
            if not neural_success:
                # 2. Offline fallback (clean, natural male tone, zero echo)
                self._speak_clean_offline(clean_text, block=block)
        finally:
            # Trigger finish callback when playback completes
            if self.finish_callback:
                try:
                    self.finish_callback()
                except Exception:
                    pass

if __name__ == "__main__":
    tts = TTSEngine()
    tts.speak("Greetings. I am Ultron. All systems are operational.")
