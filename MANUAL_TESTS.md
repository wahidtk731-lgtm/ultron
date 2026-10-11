# Ultron Android Manual Test Suite

This document defines the manual hardware and field testing procedures for verifying the Ultron Android voice assistant (`com.ultron.assistant`).

Every test case lists the **Preconditions / Action Steps**, the **Expected HUD State & Text**, and the **Expected Line in `/storage/emulated/0/Project/ultron_voice.log`**.

---

## Diagnostics Log Location
```bash
/storage/emulated/0/Project/ultron_voice.log
```
To tail live diagnostics during manual tests in Termux:
```bash
tail -f /storage/emulated/0/Project/ultron_voice.log
```

---

## 1. Microphone Permission Denied vs Granted

### 1A: Mic Permission Denied
* **Steps:**
  1. Revoke microphone permission: **Settings -> Apps -> Ultron Assistant -> Permissions -> Microphone -> Don't allow**.
  2. Launch Ultron Assistant.
  3. When prompted by Android system dialog, tap **Deny**.
  4. Tap the stadium capsule microphone button.
* **Expected HUD State & Text:**
  - Status indicator changes to error state.
  - Text displayed: `Microphone permission required. [E9 dev=... en=...]`
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] handleRecognizerError error=9 attempt=1`
  - *(or `[UltronVoiceInput] startListening requested` followed by permission guard failure log)*

### 1B: Mic Permission Granted
* **Steps:**
  1. Enable microphone permission: **Settings -> Apps -> Ultron Assistant -> Permissions -> Microphone -> Allow only while using the app**.
  2. Launch Ultron Assistant.
* **Expected HUD State & Text:**
  - Status indicator glows cyan/blue waveform.
  - Text displayed: `Listening... Speak your command`
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] onReadyForSpeech gen=1`

---

## 2. Airplane Mode With & Without Offline Voice Pack

### 2A: Airplane Mode with Offline Voice Pack
* **Steps:**
  1. Ensure an offline speech recognition language pack (e.g., English US) is installed via **Settings -> Google -> Settings for Google apps -> Search, Assistant & Voice -> Voice -> Offline speech recognition**.
  2. Turn on **Airplane Mode** (Wi-Fi and Cellular data turned OFF).
  3. Open Ultron Assistant and speak: *"Turn on flashlight"*.
* **Expected HUD State & Text:**
  - HUD displays recognized query: `"turn on flashlight"`
  - Status transitions from `Listening...` to `Thinking...` and then replies: `"Turning flashlight on"`
  - Flashlight illuminates.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] beginRecognition gen=1 attempt=1 onDevice=true forceEn=false`
  - `[UltronVoiceInput] onReadyForSpeech gen=1`
  - `[UltronVoiceInput] Speech recognized: turn on flashlight`

### 2B: Airplane Mode without Offline Voice Pack
* **Steps:**
  1. In **Airplane Mode**, uninstall or remove offline voice data (or test on device with no offline Greco models).
  2. Open Ultron Assistant.
  3. Speak any phrase or remain silent until recognizer returns network/offline pack error.
* **Expected HUD State & Text:**
  - Status updates to retry / informative offline prompt.
  - Text displayed: `Offline voice pack missing. Tap settings to download. [E2 dev=true en=false]`
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] handleRecognizerError error=2 attempt=...` (or error=1 / error=10)

---

## 3. Open From Launcher (Must Auto-Listen)

* **Steps:**
  1. Ensure microphone permission is granted.
  2. Force-stop Ultron Assistant from recent apps or application settings.
  3. Tap the Ultron Assistant app icon from the home screen launcher.
  4. Do not touch or tap the screen.
* **Expected HUD State & Text:**
  - As soon as the WebView HUD finishes rendering, listening starts automatically without any manual tap.
  - Center stadium capsule illuminates with active RMS audio level glow.
  - Text displayed: `Listening... Speak your command`
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] startListening requested`
  - `[UltronVoiceInput] onReadyForSpeech gen=1`

---

## 4. Assist Gesture (Must Auto-Listen on FIRST Invocation)

* **Steps:**
  1. Set Ultron as device default assistant: **Settings -> Apps -> Default apps -> Digital assistant app -> Ultron Assistant**.
  2. Ensure Ultron is not running in the foreground (switch to home screen).
  3. Trigger the assistant gesture: long-press the **Home button** or hold the **Power button** (or swipe up from screen corner on gesture navigation).
  4. Observe initial display state.
* **Expected HUD State & Text:**
  - Ultron HUD launches immediately from the gesture.
  - Auto-listening begins on the **FIRST** invocation (no need for a second gesture trigger or a screen tap).
  - Text displayed: `Listening... Speak your command`
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceSession] onShow` (invoked with `Intent.ACTION_ASSIST`)
  - `[UltronVoiceInput] startListening requested`
  - `[UltronVoiceInput] onReadyForSpeech gen=1`

---

## 5. Voice Query: Say "What time is it"

* **Steps:**
  1. Open Ultron Assistant.
  2. Clearly speak: *"What time is it?"*.
  3. Wait for assistant processing and response.
* **Expected HUD State & Text:**
  - Partial speech appears while speaking: `"what time is it"`
  - HUD displays query: `"what time is it"`
  - Assistant responds with current local time (e.g. `"The time is 11:05 AM"`).
  - Status updates to `Ultron Ready • Tap mic to speak` after TTS playback finishes.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] onBeginningOfSpeech gen=1`
  - `[UltronVoiceInput] Partial: what time is it`
  - `[UltronVoiceInput] onEndOfSpeech gen=1`
  - `[UltronVoiceInput] Speech recognized: what time is it`

---

## 6. The Apostrophe Test ("I didn't catch that" Recovery)

* **Steps:**
  1. Open Ultron Assistant.
  2. Stay completely silent or murmur unintelligibly until recognizer times out.
  3. Observe HUD display and verify it does NOT freeze or stay stuck on "Thinking...".
  4. Immediately tap the center microphone button again.
* **Expected HUD State & Text:**
  - HUD text cleanly renders: `I didn't catch that. Tap the mic and try again. [E7 dev=... en=...]` (or `[E6 ...]`)
  - No JavaScript `Uncaught SyntaxError` appears in WebConsole/logcat.
  - The mic button remains fully interactive and immediately starts listening again on tap.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] onError gen=1 error=7` (or `error=6`)
  - `[UltronVoiceInput] handleRecognizerError error=7 attempt=1`

---

## 7. Floating Overlay Mic

* **Steps:**
  1. In Ultron settings, enable **Floating Capsule Overlay** (or trigger command *"Turn on floating mode"*).
  2. Ensure **Display over other apps** permission is granted.
  3. Navigate to an external application (e.g. Settings, Chrome, or Launcher).
  4. Tap the floating microphone capsule over the other application.
  5. Speak: *"Open Camera"*.
* **Expected HUD State & Text:**
  - Floating pill animates with live RMS sound level waves over the third-party application.
  - HUD displays `"Opening Camera"` and camera application is brought to foreground.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] startListening requested`
  - `[UltronVoiceInput] onReadyForSpeech gen=...`
  - `[UltronVoiceInput] Speech recognized: open camera`
