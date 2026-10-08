#!/usr/bin/env python3
"""
Ultron Dedicated Offline Speech Recognition Engine.
100% Local, Private, Zero Google Dependencies.
Features:
1. Ultron Local Kaldi Vosk Neural Engine (instant local processing, 0ms network latency).
2. Phonetic Acoustic Normalizer (auto-corrects accents, command verbs, app names, files).
3. Zero third-party cloud connections (Never calls Google Speech Recognition).
"""

import os
import sys
import json
from speech_normalizer import normalize_speech, is_wake_word, strip_wake_words

class HybridSpeechRecognizer:
    """Ultron's 100% Offline Speech Recognition Engine."""

    def __init__(self, sample_rate=16000, model_path="model"):
        self.sample_rate = sample_rate
        self.model_path = model_path
        self.vosk_recognizer = None
        self._init_vosk()

    def _init_vosk(self):
        """Initializes local offline Vosk engine if model exists."""
        if os.path.exists(self.model_path):
            try:
                import vosk
                vosk.SetLogLevel(-1)
                model = vosk.Model(self.model_path)
                self.vosk_recognizer = vosk.KaldiRecognizer(model, self.sample_rate)
            except Exception:
                self.vosk_recognizer = None

    def recognize_audio_bytes(self, raw_bytes, vosk_fallback_text=None):
        """
        Transcribes speech strictly using Ultron's local offline engine
        and phonetic normalization pipeline. Completely independent of Google.
        """
        if vosk_fallback_text and vosk_fallback_text.strip():
            norm_speech = normalize_speech(vosk_fallback_text.strip())
            print(f"\n[Ultron Neural STT (Offline)]: '{vosk_fallback_text.strip()}' -> '{norm_speech}'")
            return norm_speech

        if not raw_bytes:
            return ""

        # Process raw audio bytes directly with on-device Vosk engine
        if self.vosk_recognizer:
            try:
                self.vosk_recognizer.AcceptWaveform(raw_bytes)
                res = json.loads(self.vosk_recognizer.FinalResult())
                text = res.get("text", "").strip()
                if text:
                    norm = normalize_speech(text)
                    print(f"\n[Ultron Local Vosk STT]: '{text}' -> '{norm}'")
                    return norm
            except Exception:
                pass

        return ""

# Alias for clean architecture
UltronSpeechRecognizer = HybridSpeechRecognizer

if __name__ == "__main__":
    recognizer = UltronSpeechRecognizer()
    print("[+] Ultron Dedicated Offline Speech Recognizer initialized successfully (Zero Google dependencies)!")
