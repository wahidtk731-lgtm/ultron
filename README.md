# Ultron - 100% Offline Python AI Assistant 🤖

Ultron is a voice assistant built in Python that works just like Google Assistant or Siri, but **completely offline** with **zero API keys** (no Gemini, no OpenAI, no cloud dependency required).

Now featuring an **Always-On-Top Floating HUD Button** and an **Acoustic Phonetic Normalizer** that fixes recognition accuracy when offline STT hears *"Hey Ultron"* as *"Hey all thrown"*.

---

## Architecture Overview

```
🎤 Voice ("Hey Ultron open Chrome" or "Hey all thrown open Chrome")
  │
  ▼
[Vosk Offline STT Engine] (Converts speech to text on local CPU)
  │
  ▼
[Phonetic Normalization Engine] (Maps "all thrown", "all drone", etc. -> "ultron")
  │
  ▼
[Intent Classifier Model] (Trained from zero with Scikit-Learn NLP Pipeline)
  │ ── Intent: "open_app"
  │ ── Target Entity: "chrome"
  ▼
[Linux App Discovery Engine] (Fuzzy matches against installed apps / handlers)
  │
  ▼
[System Execution] (Launches app in background)
  │
  ▼
[Native Offline TTS] (Voice confirmation: "Opening chrome...")
```

---

## 🌟 New Features

### 1. Always-On-Top Floating Button & HUD 🛸
- **Floating HUD outside terminal:** Run Ultron over any window (Chrome, Geany, Sublime, Terminal, etc.).
- **Click-to-Speak:** Click the floating button from anywhere outside the terminal to speak your command immediately without even saying the wake word!
- **Continuous Wake-Word Listening:** Can also run quietly in the background waiting for *"Hey Ultron"*.
- **Draggable:** Drag and position the floating HUD anywhere on your desktop screen.
- **Dynamic Glow:** Visual glow states:
  - 🔵 **Cyan:** Ready / Idle
  - 🔴 **Red Pulse:** Listening for command
  - 🟡 **Amber:** Processing / Thinking
  - 🟢 **Green:** Speaking response

#### How to Run Floating Button:
```bash
# Option 1: Direct launcher
python3 floating_widget.py

# Option 2: Ultron CLI flag
python3 ultron.py --gui

# Option 3: Shell script
./launch_floating.sh

# Option 4: Linux App Menu
Open your ChromeOS / Linux Application Launcher and click "Ultron AI Assistant"!
```

---

### 2. Speech Accuracy & "All Thrown" Fix 🎯
Vosk's default offline dictionary does not contain the proper noun *"Ultron"*, causing the acoustic decoder to output phonetically closest words such as *"all thrown"*, *"all drone"*, *"all turn"*, or *"out run"*.

We resolved this with `speech_normalizer.py`:
- Translates any spoken variation (`"hey all thrown"`, `"all drone"`, `"hall drone"`, `"altron"`, `"ultra"`, etc.) directly into `"hey ultron"`.
- Wake word detector triggers on both original and normalized acoustic forms.
- Intent entity extractor automatically cleans wake word remnants before extracting app names.

---

## 🧠 How to Train the Model

You can train and test the model in seconds directly from the command line:

### 1. Re-train the model anytime:
```bash
python3 train_intent.py
```
This trains a TF-IDF + Logistic Regression NLP pipeline on all training samples and saves to `intent_model.pkl`.

### 2. Test any phrase or command:
```bash
python3 train_intent.py --test "hey all thrown open chrome"
python3 train_intent.py --test "all drone what time is it"
```
It shows the normalized query, the predicted intent class, and confidence percentage.

### 3. Add a new command interactively:
```bash
python3 train_intent.py --add "play my playlist" "open_app"
```
This automatically appends the phrase to `custom_intents.json` and retrains the model!

### 4. Add custom training samples manually:
Edit `custom_intents.json` or add tuples directly to `TRAINING_DATA` in `train_intent.py`:
```json
[
  { "text": "start my project", "intent": "open_app" },
  { "text": "turn on dark mode", "intent": "open_app" }
]
```
Then run `python3 train_intent.py`.

---

## 🎙️ Running Ultron Modes

1. **Floating Button HUD (Outside Terminal):**
   ```bash
   python3 ultron.py --gui
   ```

2. **Terminal Voice Mode (Microphone):**
   ```bash
   python3 ultron.py
   ```

3. **Interactive Text Mode (Testing without Mic):**
   ```bash
   python3 ultron.py --text
   ```

---

## 📂 Project Structure

- `floating_widget.py` - Draggable, always-on-top HUD with click-to-speak and status visualization.
- `speech_normalizer.py` - Phonetic normalizer fixing "all thrown" -> "ultron".
- `ultron.py` - Assistant core with intent dispatcher and voice loops.
- `train_intent.py` - Offline NLP machine learning training engine.
- `app_launcher.py` - Linux & ChromeOS app discovery and launcher.
- `tts_engine.py` - Offline speech engine using native espeak / pyttsx3.
- `download_model.py` - Automatic Vosk offline model downloader.
- `test_mic.py` - Microphone diagnostic & live audio VU meter.
- `launch_floating.sh` - One-click launcher script.
- `android/` - Native Android Assistant app (offline SpeechRecognizer, TTS, IntentEngine).
- `.github/workflows/build-apk.yml` - Cloud APK builder pipeline (builds APK with zero local storage).
- `pwa/` - Progressive Web App for instant 1-click WebAPK installation on Android.
- `serve_pwa.py` - Zero-dependency local server for mobile testing.
- `build_apk.sh` - Interactive APK packaging guide and script.

---

## 📱 Android APK & Mobile Installation

You can convert and install Ultron Assistant on Android **without installing heavy SDKs, NDKs, or multi-gigabyte build tools**:

### Option 1: Instant 1-Click Install (PWA / WebAPK)
Zero build tools or downloads required:
```bash
python3 serve_pwa.py
```
Open `http://<your-device-ip>:8085` in Google Chrome on your Android phone or ChromeOS and tap **"Install App"** / **"Add to Home Screen"**. It installs as a real Android app with offline speech and HUD!

### Option 2: Cloud GitHub Actions Build (Zero Local Disk Space)
A complete native Android project is provided in `android/` with a pre-configured GitHub Actions workflow:
1. Push your changes: `git push origin main`
2. GitHub Actions will automatically compile `UltronAssistant.apk` in the cloud.
3. Download the signed `.apk` from your repository's **Actions** tab and install it via `adb install` or your phone's file manager!

### Option 3: Standalone Local Termux Build (Zero Gradle / Studio)
Ultron can be packaged entirely inside Termux on device using lightweight command-line tools:
```bash
# Generate R.java
aapt package -f -m -J gen -M android/app/src/main/AndroidManifest.xml -S android/app/src/main/res -I "$HOME/.android-sdk/android.jar"
# Compile Java classes (Java 7 compatible)
ecj -cp "$HOME/.android-sdk/android.jar:gen" -d obj $(find android/app/src/main/java gen -name "*.java")
```

---

## 🏗️ Android Architecture & Capabilities

The native Android app (`com.ultron.assistant`) is organized into:
- **`MainActivity`:** Edge-to-edge bottom stadium sheet HUD mimicking the modern Google Assistant UI. Triggers via launcher or system default assistant gesture (long press home/power).
- **`FloatingHUDService`:** Foreground service providing an always-on-top draggable floating pill over other apps with one-tap mic activation and real-time audio waveforms.
- **`UltronIndependentVoiceInput`:** Hybrid voice input engine using Android's native `SpeechRecognizer` with bounded fallback to raw PCM `AudioRecord` processing.
- **`UltronRecognitionService`:** Standard Android voice interaction service binding into Android system settings.
- **`AppLauncherAndroid`:** Zero-dependency app scanner and system settings controller (Wi-Fi, Bluetooth, volume, torch, notifications).

---

## ⚠️ Known Android Limitations

1. **No 24/7 Locked Background Wake Word:** Modern Android OS battery optimization (Doze mode) and microphone privacy rules prevent third-party background services from streaming raw microphone audio continuously 24/7 while the device is locked. Ultron wakes instantly via the system Assistant gesture, notification quick action, or tap-to-speak on the floating capsule overlay.
2. **Offline Speech Recognition Requirement:** For 100% offline speech recognition, the device must have an offline speech recognition pack installed via **Android Settings -> Google / Language & Input -> Speech Recognition -> Offline Speech Recognition (English)**. If offline packs are not installed, online recognition is used when internet is connected.
3. **Bluetooth Toggling on Android 12+ (API 31+):** Direct programmatic toggling of Bluetooth without user interaction is restricted by Android OS. Ultron redirects to the system Bluetooth settings panel if `BLUETOOTH_CONNECT` runtime permission is unavailable.
4. **Scoped Storage Compliance:** File notes and crash dumps are written to app-specific external storage (`context.getExternalFilesDir(null)`) to ensure full compatibility with Android 10+ scoped storage restrictions.
5. **Java 7 Source Compatibility:** The codebase avoids Java 8+ language features (lambdas, method references, streams, `var`) to ensure seamless compilation with lightweight `ecj` in Termux and Dalvik/D8 runtimes.
