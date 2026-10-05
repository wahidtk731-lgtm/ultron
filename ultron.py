#!/usr/bin/env python3
"""
Ultron - Local Offline AI Assistant
Runs 100% locally with zero cloud API keys.
Wake words: "Hey Ultron", "Ultron", with full phonetic normalization for Vosk ("all thrown", "all drone", etc.)
"""

import os
import sys

# Auto-switch to virtualenv if dependencies are missing in the invoking interpreter
try:
    import speech_recognition
    import edge_tts
    import rapidfuzz
    import vosk
    import sounddevice
    import sklearn
except ImportError:
    base_dir = os.path.dirname(os.path.abspath(__file__))
    venv_py = os.path.join(base_dir, "venv", "bin", "python3")
    if os.path.exists(venv_py) and sys.executable != venv_py:
        if sys.argv and sys.argv[0] != "-c":
            os.execv(venv_py, [venv_py] + sys.argv)

import json
import re
import pickle
import queue
import argparse
import datetime

# Local modules
from app_launcher import AppLauncher
from tts_engine import TTSEngine
from train_intent import train_and_save_model
from download_model import download_and_setup_model
from speech_normalizer import (
    normalize_speech,
    is_wake_word,
    strip_wake_words,
    WAKE_WORDS
)

class UltronAssistant:
    def __init__(self, model_dir="model", intent_file="intent_model.pkl"):
        self.base_dir = os.path.dirname(os.path.abspath(__file__))
        self.model_dir = os.path.join(self.base_dir, model_dir)
        self.intent_file = os.path.join(self.base_dir, intent_file)
        
        self.tts = TTSEngine()
        self.launcher = AppLauncher()
        self.intent_model = None
        self.vosk_model = None

        self._ensure_intent_model()

    def _ensure_intent_model(self):
        """Ensures the intent classification model is trained and loaded."""
        if not os.path.exists(self.intent_file):
            print("[*] Intent model not found. Training intent model from zero...")
            self.intent_model = train_and_save_model(self.intent_file)
        else:
            with open(self.intent_file, "rb") as f:
                self.intent_model = pickle.load(f)

    def _ensure_stt_model(self):
        """Ensures offline Vosk speech-to-text model is available."""
        if not os.path.exists(self.model_dir):
            print("[*] Offline speech recognition model not found. Downloading...")
            download_and_setup_model()

        try:
            from vosk import Model, KaldiRecognizer
            print("[*] Loading offline speech recognition model into memory...")
            self.vosk_model = Model(self.model_dir)
            return KaldiRecognizer(self.vosk_model, 16000)
        except Exception as e:
            print(f"[-] Error initializing Vosk speech model: {e}")
            return None

    def extract_target_app(self, text):
        """Extracts the app entity from the command text."""
        # 1. Normalize phonetic variants of Ultron
        norm = normalize_speech(text)
        # 2. Strip wake words
        cleaned = strip_wake_words(norm)
        # 3. Strip trigger verbs and filler words
        cleaned = re.sub(
            r"\b(open|launch|start|run|please|can you|could you|close|terminate|kill|exit|the|an|a|app|application)\b",
            " ",
            cleaned
        )
        return re.sub(r"\s+", " ", cleaned).strip()

    def extract_search_query(self, text):
        """Extracts the search terms from the command text."""
        norm = normalize_speech(text)
        cleaned = strip_wake_words(norm)
        cleaned = re.sub(
            r"\b(search google for|search the web for|search for|google|look up|search)\b",
            " ",
            cleaned
        )
        return re.sub(r"\s+", " ", cleaned).strip()

    def process_command(self, user_text):
        """Classifies intent and executes the corresponding system action."""
        if not user_text or not user_text.strip():
            return True

        # Normalize phonetic speech (e.g., 'hey all thrown' -> 'hey ultron')
        normalized = normalize_speech(user_text)

        # Strip wake words first so intent classifier focuses on the action
        command_body = strip_wake_words(normalized)

        # If user only said the wake word ("Hey Ultron", "all thrown")
        if not command_body:
            self.tts.speak("Yes, I'm listening. What would you like me to do?")
            return True

        intent = self.intent_model.predict([command_body])[0]

        if intent == "open_app":
            app_name = self.extract_target_app(normalized)
            if app_name:
                self.tts.speak(f"Opening {app_name}...")
                success, msg = self.launcher.launch(app_name)
                if not success:
                    self.tts.speak(msg)
            else:
                self.tts.speak("Which application or website would you like me to open?")

        elif intent == "close_app":
            app_name = self.extract_target_app(normalized)
            if app_name:
                self.tts.speak(f"Closing {app_name}...")
                success, msg = self.launcher.close(app_name)
                self.tts.speak(msg)
            else:
                self.tts.speak("Which application should I close?")

        elif intent == "query_time":
            now = datetime.datetime.now()
            time_str = now.strftime("%I:%M %p on %A, %B %d")
            self.tts.speak(f"The current time is {time_str}.")

        elif intent == "search_web":
            query = self.extract_search_query(normalized)
            if query:
                self.tts.speak(f"Searching web for {query}...")
                self.launcher.search_web(query)
            else:
                self.tts.speak("What would you like me to search for?")

        elif intent == "greet":
            self.tts.speak("Hello! Ultron is online and ready for your commands.")

        elif intent == "query_capabilities":
            self.tts.speak("I am Ultron, your local AI assistant. I can open apps, launch websites, open your projects, tell the time, search the web, and close applications—all locally.")

        elif intent == "exit":
            self.tts.speak("Going offline. Goodbye!")
            return False

        return True

    def run_interactive_mode(self):
        """Text-based interactive terminal mode (no microphone needed)."""
        print("\n" + "="*50)
        print("🤖 ULTRON INTERACTIVE TERMINAL MODE")
        print("Type commands like:")
        print("  - 'hey ultron open browser' (or 'hey all thrown open chrome')")
        print("  - 'open terminal'")
        print("  - 'open geany'")
        print("  - 'open sublime'")
        print("  - 'open youtube'")
        print("  - 'what time is it'")
        print("  - 'search google for python'")
        print("  - 'close geany'")
        print("  - 'exit'")
        print("="*50 + "\n")

        self.tts.speak("Ultron interactive mode is ready.")

        while True:
            try:
                user_input = input("\n👤 You: ")
                if not user_input.strip():
                    continue
                should_continue = self.process_command(user_input)
                if not should_continue:
                    break
            except (KeyboardInterrupt, EOFError):
                print("\nExiting...")
                break

    def run_voice_mode(self):
        """Microphone voice listening mode using sounddevice & Vosk."""
        recognizer = self._ensure_stt_model()
        if not recognizer:
            print("[-] Speech recognition could not be initialized.")
            return

        try:
            import sounddevice as sd
        except ImportError:
            print("[-] sounddevice module not found. Run: pip install sounddevice")
            return

        audio_queue = queue.Queue()

        def audio_callback(indata, frames, time, status):
            if status:
                print(f"[!] Audio Status: {status}", file=sys.stderr)
            audio_queue.put(bytes(indata))

        try:
            device_index = sd.default.device[0]
            device_info = sd.query_devices(device_index, "input")
            dev_name = device_info.get("name", "Default")
        except Exception:
            dev_name = "Default"

        print("\n" + "="*55)
        print("🎙️ ULTRON VOICE MODE ACTIVATED")
        print(f"Using Audio Input: {dev_name}")
        print("Say: 'Hey Ultron open [application/website]'")
        print("Phonetic tolerance: 'all thrown', 'all drone', 'altron' automatically mapped!")
        print("Tip: Run 'python3 ultron.py --gui' for Floating Button outside terminal!")
        print("Press Ctrl+C to stop.")
        print("="*55 + "\n")

        self.tts.speak("Voice systems online. Listening for wake word.")

        silence_counter = 0
        warned_silence = False

        try:
            with sd.RawInputStream(
                samplerate=16000,
                blocksize=4000,
                dtype="int16",
                channels=1,
                callback=audio_callback
            ):
                while True:
                    data = audio_queue.get()
                    
                    # Quick silence check for ChromeOS permissions
                    if len(data) > 0 and max(data) == 0:
                        silence_counter += 1
                        if silence_counter > 50 and not warned_silence:
                            print("\n⚠️ Notice: Pure silence detected (level: 0).")
                            print("   If on ChromeOS, go to: Settings -> Linux -> Toggle ON 'Allow Linux to access your microphone'")
                            warned_silence = True
                    else:
                        silence_counter = 0

                    if recognizer.AcceptWaveform(data):
                        result = json.loads(recognizer.Result())
                        raw_text = result.get("text", "").strip()

                        if raw_text:
                            norm_text = normalize_speech(raw_text)
                            print(f"\n[Heard]: {raw_text} (Normalized: {norm_text})")
                            if is_wake_word(raw_text) or is_wake_word(norm_text):
                                should_continue = self.process_command(norm_text)
                                if not should_continue:
                                    break
                    else:
                        # Real-time partial speech recognition
                        partial = json.loads(recognizer.PartialResult())
                        part_text = partial.get("partial", "").strip()
                        if part_text:
                            norm_part = normalize_speech(part_text)
                            print(f"\r🎤 Heard: {norm_part}   ", end="", flush=True)

        except KeyboardInterrupt:
            print("\nUltron shutting down...")
        except Exception as e:
            print(f"\n[-] Microphone / Audio Input Error: {e}")
            print("[*] Tip: You can test commands anytime using: python3 ultron.py --text")

def main():
    parser = argparse.ArgumentParser(description="Ultron Local AI Assistant")
    parser.add_argument("--text", "-t", action="store_true", help="Run in text test mode without microphone")
    parser.add_argument("--gui", "--floating", "-g", action="store_true", help="Run in Floating Button HUD mode outside terminal")
    args = parser.parse_args()

    if args.gui:
        from floating_widget import UltronFloatingWidget
        print("[*] Launching Ultron Floating Button HUD...")
        app = UltronFloatingWidget()
        app.run()
    elif args.text:
        assistant = UltronAssistant()
        assistant.run_interactive_mode()
    else:
        assistant = UltronAssistant()
        try:
            assistant.run_voice_mode()
        except Exception as e:
            print(f"[!] Falling back to text mode: {e}")
            assistant.run_interactive_mode()

if __name__ == "__main__":
    main()
