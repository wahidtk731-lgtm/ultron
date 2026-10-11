# Ultron Android Manual Test Suite

This document defines the manual testing procedures for verifying the Ultron Android voice assistant (`com.ultron.assistant`) across hardware, OS versions (Android 7.0 through Android 14+), and edge-case operational states.

---

## Pre-requisites & Test Setup
1. **Device:** Physical Android device or Android Emulator (API 24 to API 34).
2. **Build and Install:**
   ```bash
   # From Termux:
   aapt package -f -m -J gen -M android/app/src/main/AndroidManifest.xml -S android/app/src/main/res -I "$HOME/.android-sdk/android.jar"
   ecj -cp "$HOME/.android-sdk/android.jar:gen" -d obj $(find android/app/src/main/java gen -name "*.java")
   # Package and install apk via adb or package manager
   ```

---

## Test Cases

### 1. Microphone Permission Denied vs Granted
* **Objective:** Ensure the app handles microphone permission cleanly without crashing or hanging in an invalid state.
* **Steps:**
  1. Go to **Android Settings -> Apps -> Ultron Assistant -> Permissions -> Microphone** and set to **Don't Allow**.
  2. Launch Ultron Assistant (`MainActivity`).
  3. Verify the system permission request dialog appears.
  4. Tap **Deny**.
  5. Tap the microphone icon on the stadium capsule HUD.
  6. Verify the HUD displays `Microphone permission required` and does not crash.
  7. Return to app permissions, grant **Allow while using app**.
  8. Re-open Ultron and tap the microphone icon.
* **Expected Result:** Audio waveform glows cyan/red pulse and listening begins immediately without error.

---

### 2. Airplane Mode / Offline vs Online State
* **Objective:** Verify offline speech recognition behavior and automatic fallback mechanisms.
* **Test 2A: Offline with Offline Speech Pack Installed**
  1. Ensure English offline voice model is downloaded (**Android Settings -> Google -> Settings for Google Apps -> Search, Assistant & Voice -> Voice -> Offline speech recognition**).
  2. Enable **Airplane Mode** (Wi-Fi and mobile data OFF).
  3. Tap Ultron microphone.
  4. Speak command: *"Open Calculator"*.
* **Expected Result:** Ultron processes speech locally via on-device `SpeechRecognizer`, replies *"Opening Calculator"*, and launches the Calculator app.

* **Test 2B: Offline without Offline Speech Pack Installed**
  1. In Airplane mode on a device lacking offline voice packs.
  2. Tap Ultron microphone.
  3. Speak command.
* **Expected Result:** App cleanly catches the network/speech engine error, HUD displays `"Didn't catch that. Tap mic to retry."` or falls back to bounded `AudioRecord` (max 1 fallback attempt), without crash or infinite recursion loop.

---

### 3. Tap-to-Speak & Single Utterance Flow
* **Objective:** Verify standard primary interaction loop.
* **Steps:**
  1. Open Ultron Assistant from home screen or long-press Home/Power assist gesture.
  2. Tap the glowing center microphone button.
  3. Say *"What time is it?"*.
* **Expected Result:**
  - HUD status updates from `Listening...` -> `Thinking...`.
  - Ultron speaks the current time via offline `TTSManager`.
  - HUD displays query and assistant response without syntax errors.

---

### 4. Floating Overlay Capsule & Window Management
* **Objective:** Verify always-on-top draggable capsule behavior across external applications.
* **Steps:**
  1. In Ultron settings, enable **Floating Capsule Overlay** (or execute command *"Turn on floating mode"*).
  2. Ensure **Display over other apps** permission is granted.
  3. Open an external application (e.g., Google Chrome or YouTube).
  4. Tap the floating capsule on the screen.
  5. Speak *"Turn on flashlight"*.
  6. Drag the floating capsule to a new screen corner.
  7. Tap the capsule again and speak *"Close floating mode"*.
* **Expected Result:**
  - The capsule floats steadily over external apps without flickering.
  - Flashlight turns on and voice confirmation speaks.
  - Drag gesture moves the capsule smoothly.
  - Close command tears down `FloatingHUDService` and removes overlay window from `WindowManager`.

---

### 5. TTS Voice Output & Conversation Re-Listen Sync
* **Objective:** Ensure no double-activation race condition between TTS completion and speech input.
* **Steps:**
  1. Trigger an interactive assistant command (e.g. asking for confirmation).
  2. Observe the microphone activation timing while TTS speaks.
* **Expected Result:**
  - Ultron does NOT open the microphone while it is actively speaking through the speaker (no audio feedback loop).
  - The microphone opens cleanly only *after* the TTS utterance completion event (`onSpeechCompleted`) fires.
  - No 2500ms blind timeout collision occurs.

---

### 6. Screen Rotation & Configuration Changes
* **Objective:** Verify WebView state and microphone recording survive screen orientation shifts.
* **Steps:**
  1. Open Ultron in portrait mode.
  2. Tap mic to begin listening.
  3. Rotate device to landscape mode.
  4. Complete speech command.
* **Expected Result:**
  - Activity does not leak window context.
  - Speech recognizer completes utterance or recovers gracefully without crashing.
  - HUD remains properly centered at bottom edge.

---

### 7. Process Kill / Abrupt Exit Mid-Listen
* **Objective:** Ensure hardware microphone hardware resources and services are released without leaking.
* **Steps:**
  1. Tap microphone to enter active listening state.
  2. While waveform is oscillating, immediately press device **Back** button or swipe app away from Recent Apps.
  3. Open Android Camera or Voice Recorder app and record audio.
* **Expected Result:**
  - Camera/Recorder accesses microphone with zero `AudioRecord in use` hardware errors.
  - `MainActivity.onDestroy()` correctly releases `AudioRecord`, shuts down `TTSManager`, and cancels `mainHandler` callbacks.

---

### 8. Apostrophe & Special Character String Injection ("Didn't catch that")
* **Objective:** Verify JavaScript evaluation is immune to quotation and apostrophe breaking.
* **Steps:**
  1. Mumble or remain completely silent until recognizer times out.
  2. Observe HUD error message: *"Didn't catch that. Tap mic to speak."*.
  3. Speak a query with quotes or apostrophes: *"Search for what's the weather today"*.
* **Expected Result:**
  - The HUD displays `"Didn't catch that"` smoothly.
  - No JavaScript uncaught syntax error (`Uncaught SyntaxError: Unexpected identifier`) occurs.
  - The WebView HUD remains fully responsive.
