# Ultron Android Manual Test Suite

This document defines the manual hardware and field testing procedures for verifying the Ultron Android voice assistant (`com.ultron.assistant`) on physical hardware.

Diagnostics log location:
```bash
/storage/emulated/0/Project/ultron_voice.log
```
To tail live diagnostics during manual tests in Termux:
```bash
tail -n 60 /storage/emulated/0/Project/ultron_voice.log
# or follow live:
tail -f /storage/emulated/0/Project/ultron_voice.log
```

---

## Test Cases

#### a) Airplane Mode from Start: Preparing -> Offline Voice Ready -> Speak Command
* **Preconditions:**
  1. Airplane Mode turned ON before launching app.
  2. First launch of Ultron Assistant with bundled offline model.
* **Steps:**
  1. Launch Ultron Assistant.
  2. Observe slim banner: `Preparing offline voice engine (N%)...` with progress bar.
  3. Wait a few seconds until banner shows `Offline voice ready`.
  4. Tap mic button and speak a command: *"what time is it"*.
* **Expected HUD State & Text:**
  - Banner updates progressively: `Preparing offline voice engine (N%)...` -> `Offline voice ready`.
  - Mic activates immediately without network.
  - Command is transcribed via local Vosk engine and Ultron speaks reply offline.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceSetup] State changed to: PREPARING (N%)`
  - `[UltronVoiceSetup] State changed to: READY (100%) Offline voice ready`
  - `[UltronVoskEngine] Vosk Model loaded successfully into memory`
  - `[UltronVoiceInput] Starting Vosk engine for STEP_VOSK`

---

### b) Kill App During First-Launch Copy -> Reopen -> Clean Restart
* **Preconditions:** Fresh install or cleared data (`pm clear com.ultron.assistant`).
* **Steps:**
  1. Launch Ultron Assistant so model copy starts.
  2. Immediately swipe away / force stop the app mid-copy (around 30-50%).
  3. Reopen Ultron Assistant.
* **Expected HUD State & Text:**
  - No crash, no broken state from partial temp files.
  - Installer cleanly wipes stale `vosk-model-tmp` and restarts copy cleanly from 0% to 100%.
  - Once completed, shows `Offline voice ready`.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceSetup] Starting offline voice model unpack from APK assets`
  - Clean restart without unhandled file corruption exceptions.

---

### c) Storage Almost Full (< 250 MB Free) -> "Free up storage" + Retry
* **Preconditions:** Internal storage free space under 250 MB.
* **Steps:**
  1. Launch Ultron Assistant on device with < 250 MB free space.
* **Expected HUD State & Text:**
  - Setup detects insufficient disk space and displays:
    `Offline voice setup failed: Free up storage (requires at least 250 MB free, found X MB)`
  - Banner presents interactive `[Retry]` button.
  - Tapping `[Retry]` re-checks space and retries safely.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceSetup] Free up storage (requires at least 250 MB free, found ...)`
  - `[UltronVoiceSetup] State changed to: FAILED (0%)`

---

### d) Offline-Only Mode with Wi-Fi ON Still Uses Vosk
* **Preconditions:**
  1. Phone connected to high-speed Wi-Fi with active internet.
  2. In Ultron Settings, set Voice Engine to `Offline only`.
* **Steps:**
  1. Tap mic button.
  2. Speak: *"what time is it"*.
* **Expected HUD State & Text:**
  - Speech is decoded via offline Vosk engine despite active internet.
  - Google online recognizer is bypassed completely.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] Evaluating plan step index=0: STEP_VOSK(4)`
  - `[UltronVoiceInput] Starting Vosk engine for STEP_VOSK`
  - No calls made to `STEP_SYSTEM_ONLINE`.

---

### e) Auto Mode: Online Uses Google First, Wi-Fi Off Mid-Session -> Next Tap Uses Vosk
* **Preconditions:**
  1. In Ultron Settings, Voice Engine is set to `Auto` (default).
  2. Wi-Fi connected and online.
* **Steps:**
  1. Tap mic and speak a command -> Transcribes via Google (`STEP_SYSTEM_ONLINE`).
  2. Turn off Wi-Fi and mobile data (or enable Airplane Mode).
  3. Tap mic and speak a command.
* **Expected HUD State & Text:**
  - First tap uses online recognition smoothly.
  - Second tap detects offline immediately without delay and seamlessly routes to Vosk (`STEP_VOSK`).
* **Expected Log Line in `ultron_voice.log`:**
  - First tap: `[UltronVoiceInput] Starting recognizer with step STEP_SYSTEM_ONLINE(0)`
  - Second tap: `[UltronVoiceInput] startListening requested (online=false)`
  - `[UltronVoiceInput] Evaluating plan step index=0: STEP_VOSK(4)`
  - `[UltronVoiceInput] Starting Vosk engine for STEP_VOSK`

---

### f) Tap Mic While PREPARING -> "Offline voice is still preparing, N%"
* **Preconditions:** Fresh install while asset unpacking is actively in progress (e.g. at 35%).
* **Steps:**
  1. While the banner indicates preparation is ongoing, tap the mic button.
* **Expected HUD State & Text:**
  - If in `Offline only` mode: Status shows `Offline voice is still preparing, 35%`.
  - Does not crash or deadlock.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] STEP_VOSK: still preparing (35%)`

---

## Output Voice & DSP Tests (Phase 6)

### g) Default Style Ultron: Deep, Slow, Metallic, Echoing Voice
* **Preconditions:** Fresh launch of Ultron Assistant. Default style is `Ultron`.
* **Steps:**
  1. Open settings drawer -> Scroll to **Voice Style**.
  2. Confirm `[Ultron]` button is active (blue highlight).
  3. Tap **🔊 Test Voice**.
* **Expected Acoustic Result:**
  - Test phrase spoken: *"Systems online. I am listening."*
  - Deep fundamental pitch (~0.62 pitch, 0.90 speed).
  - Subtle metallic harmonic ring from the 48 Hz ring modulator and 9 ms comb filter.
  - Large-room lair reverberation tail (~0.9 s decay) with clean decay and zero clicks/pops.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] speak requested: "Systems online. I am listening." (gen=..., style=ultron)`
  - `[UltronTTS] Speech playback started (style=ultron)`
  - `[UltronTTS] Speech playback completed`

---

### h) Switch Voice Style to Deep and Natural and Back
* **Preconditions:** Ultron open with Settings panel visible.
* **Steps:**
  1. Tap `[Deep]` button -> Tap **🔊 Test Voice**.
  2. Verify clean low voice with light room ambiance, but no metallic ring or comb filter.
  3. Tap `[Natural]` button -> Tap **🔊 Test Voice**.
  4. Verify clean unprocessed phone TTS output (today's behavior, pitch 0.95, rate 1.05).
  5. Tap `[Ultron]` button -> Tap **🔊 Test Voice**.
  6. Verify full metallic resonant Ultron acoustic profile returns.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] setVoiceStyle: clean_deep`
  - `[UltronTTS] setVoiceStyle: natural`
  - `[UltronTTS] setVoiceStyle: ultron`

---

### i) Change Offline Voice in Dropdown
* **Preconditions:** Multiple offline TTS voices installed on phone (e.g. English male/female variants).
* **Steps:**
  1. Open Voice Settings.
  2. Expand the **Voice** dropdown menu.
  3. Select an alternate offline voice.
  4. Tap **🔊 Test Voice**.
* **Expected Result:**
  - The newly selected offline voice is synthesized through the active Ultron DSP pipeline.
  - No network connectivity is requested.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] Voice name set: ...`
  - `[UltronTTS] Active TTS voice switched to: ...`

---

### j) Mic Lockout During Speech: No Self-Triggering
* **Preconditions:** Assistant is ready to speak.
* **Steps:**
  1. Ask a question or trigger a response (e.g. *"what time is it"*).
  2. While the assistant is actively speaking through the phone speaker, speak loudly near the mic.
* **Expected Result:**
  - Microphone input is locked during speech output (`isSpeaking` is true).
  - The assistant does not loop back or transcribe its own speech.
  - Recognition unlocks and resumes immediately upon `onSpeechCompleted()`.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] Speech playback started`
  - `[UltronTTS] Speech playback completed`
  - `[UltronVoiceInput] Listening resumed after speech completion`

---

### k) Clean Audio Cut on Stop or New Command
* **Preconditions:** Assistant is actively speaking a long sentence.
* **Steps:**
  1. During active speech, tap the microphone button or tap the close/stop button.
* **Expected Result:**
  - Speech cuts immediately with zero residual buffer drain or stutter.
  - `AudioTrack` stops and releases cleanly.
  - Mic unlocks and system state does not remain stuck in "speaking".
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] stop requested`
  - `[UltronTTS] Speech playback completed`

---

### l) Airplane Mode Speech Playback
* **Preconditions:** Airplane Mode turned ON (all network radios off).
* **Steps:**
  1. Open Ultron Assistant.
  2. Tap **🔊 Test Voice** in Settings.
* **Expected Result:**
  - Speech synthesizes locally using the offline TTS voice.
  - UltronVoiceDsp processes and plays audio without error or network dependency.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] speak requested: ... (style=ultron)`
  - `[UltronTTS] Speech playback completed`

---

### m) No Offline Voice Fallback / Missing Language
* **Preconditions:** Device has no offline TTS voice for the active language.
* **Steps:**
  1. Launch Ultron Assistant.
  2. Observe the Voice Settings banner.
* **Expected HUD State & Text:**
  - Warning notice displayed:
    `"For the best Ultron sound install a male English voice: Settings > Text-to-speech > Google > Install voice data"`
  - Text replies still render in the HUD.
  - `onSpeechCompleted()` fires immediately without hanging the recognition loop.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronTTS] No suitable offline voice found for language en`

---

## TTSManager onSpeechCompleted Exactly-Once Verification

`TTSManager` guarantees `listener.onSpeechCompleted()` is called **exactly once** across all execution paths:

| Path | Condition | Behavior | Completion Callback |
|---|---|---|---|
| **Path 1: Empty text** | `text == null \|\| text.trim().isEmpty()` | Returns immediately | `notifySpeechCompleted()` called synchronously |
| **Path 2: TTS not initialized / failed** | `!isReady \|\| isInitFailed \|\| tts == null` | Audio skipped | `notifySpeechCompleted()` called synchronously |
| **Path 3: Plain TTS (Natural) Success** | `currentProfile.name.equals("natural")` | Plain `tts.speak()` | UtteranceProgressListener `onDone` calls `notifySpeechCompleted()` once |
| **Path 4: Plain TTS (Natural) Error** | Synthesis fails in plain mode | Error triggered | UtteranceProgressListener `onError` calls `notifySpeechCompleted()` once |
| **Path 5: DSP Pipeline Normal Completion** | Sentence synthesis & AudioTrack stream succeed | Audio plays & drains | `playProcessedAudio()` finally block calls `notifySpeechCompleted()` once |
| **Path 6: DSP Pipeline Error Fallback** | `synthesizeToFile` fails, times out, or non-PCM16 WAV | Falls back to plain TTS | `fallbackToPlainTts()` runs plain speak which calls `notifySpeechCompleted()` once |
| **Path 7: Explicit `stop()` called** | User taps stop or speaks new command | Cancels current generation | `stop()` calls `notifySpeechCompleted()` once |
| **Path 8: Shutdown** | App activity/service destroys | Releases all resources | `shutdown()` delegates to `stop()`, completing and clearing callbacks |

---

## Ultron Voice DSP Tuning Guide

All acoustic parameters are centralized in [`UltronVoiceProfile.java`](file:///data/data/com.termux/files/home/ultron/android/app/src/main/java/com/ultron/assistant/UltronVoiceProfile.java). You can tune them and preview immediately using `tools/VoiceDspCli.java` without rebuilding the APK.

### Tuning by Ear

| Symptom | Cause | How to Tune in `UltronVoiceProfile.java` |
|---|---|---|
| **"Too robotic / harsh"** | Excessive ring modulation or comb resonance | Reduce `ringModDepth` (e.g. from `0.18f` to `0.10f` or `0.0f`). Reduce `combFeedback` (e.g. from `0.30f` to `0.15f`) or reduce `combMix` (e.g. `0.20f`). |
| **"Too muffled / dark"** | Low-shelf boost too high or high-shelf cut too steep | Lower `lowShelfGainDb` (e.g. from `4.0f` to `2.0f`). Raise `highShelfGainDb` (e.g. from `-3.0f` to `-1.0f` or `0.0f`). |
| **"Too echoey / hollow"** | Reverb wet mix or decay time too high | Reduce `reverbWet` (e.g. from `0.14f` to `0.08f`). Reduce `reverbDecaySec` (e.g. from `0.90f` to `0.50f`). |
| **"Too quiet / weak"** | Drive saturation too low or peak ceiling too low | Increase `drive` (e.g. from `1.25f` to `1.40f`). Increase `peakNormalize` (e.g. from `0.89f` to `0.95f`). |
| **"Pitch too cartoonish / deep"** | Base pitch outside optimal formant range | Adjust `enginePitch` (recommended range `0.58f` to `0.72f`; default `0.62f`). |

### Fast Tuning with `VoiceDspCli` (No APK Build Required)

Run the host-side CLI in Termux to generate and test audio profiles:
```bash
# Run host-side test suite and render ultron_voice_test_ultron.wav:
java -cp tools:android/app/src/main/java com.ultron.assistant.VoiceDspCli

# Listen to the rendered output directly:
# Output files saved to /storage/emulated/0/Project/:
# - ultron_voice_test_ultron.wav
# - ultron_voice_test_clean_deep.wav
# - ultron_voice_test_natural.wav
```
