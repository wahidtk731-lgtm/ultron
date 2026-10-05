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
# ultron
