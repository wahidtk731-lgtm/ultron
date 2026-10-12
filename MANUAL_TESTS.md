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

### a) Online: Speak "what time is it"
* **Preconditions:** Wi-Fi or mobile cellular data enabled and validated (`NET_CAPABILITY_VALIDATED` is true).
* **Steps:**
  1. Open Ultron Assistant.
  2. Tap the mic button (or allow auto-listen on launch).
  3. Clearly speak: *"what time is it"*.
* **Expected HUD State & Text:**
  - Status glows cyan/blue waveform: `Listening... Speak your command`
  - Partial updates display as words are spoken.
  - Assistant responds with the current time (e.g. `"The time is 11:05 AM"`).
  - Status resets to `Ultron Ready • Tap mic to speak`.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] startListening requested (online=true)`
  - `[UltronVoiceInput] Starting recognizer with step STEP_SYSTEM_ONLINE(0)`
  - `[UltronVoiceInput] onReadyForSpeech gen=1`
  - `[UltronVoiceInput] onBeginningOfSpeech gen=1`
  - `[UltronVoiceInput] Speech recognized: what time is it`

---

### b) Airplane Mode, No Offline Pack (Phase 1)
* **Preconditions:**
  1. Airplane Mode turned ON (Wi-Fi and mobile data OFF).
  2. No Google offline speech recognition pack installed (or language set to unsupported locale).
* **Steps:**
  1. Open Ultron Assistant in Airplane Mode.
  2. Tap the stadium capsule mic button.
  3. Wait as the engine steps through the offline plan:
     `{STEP_ONDEVICE_API -> STEP_SYSTEM_OFFLINE -> STEP_SYSTEM_OFFLINE_EN_US -> STEP_SYSTEM_ONLINE}`.
* **Expected HUD State & Text:**
  - HUD displays informative message:
    `No internet and no offline voice pack found. Download English in Settings > Google > Voice > Offline speech recognition. [E<code>]`
  - Mic button remains interactive and ready for user interaction.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] startListening requested (online=false)`
  - `[UltronVoiceInput] Evaluating plan step index=...`
  - `[UltronVoiceInput] advanceOrFail called with error=...`
  - `[UltronVoiceInput] Final failure notified: error=... online=false`

---

### c) Airplane Mode With Google Offline English Pack
* **Preconditions:**
  1. Google offline speech recognition English pack installed via:
     **Settings -> Google -> Settings for Google apps -> Search, Assistant & Voice -> Voice -> Offline speech recognition**.
  2. Airplane Mode turned ON (Wi-Fi and mobile data OFF).
* **Steps:**
  1. Open Ultron Assistant in Airplane Mode.
  2. Tap the mic button and speak: *"what time is it"* (or *"turn on flashlight"*).
* **Expected HUD State & Text:**
  - Mic activates immediately with audio waveform feedback.
  - Recognized query is displayed.
  - Assistant processes command and speaks reply offline.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] startListening requested (online=false)`
  - `[UltronVoiceInput] Starting recognizer with step STEP_SYSTEM_OFFLINE(2)` (or `STEP_ONDEVICE_API(1)`)
  - `[UltronVoiceInput] onReadyForSpeech gen=1`
  - `[UltronVoiceInput] Speech recognized: what time is it`

---

### d) Airplane Mode With Bundled Vosk (Phase 3)
* **Preconditions:**
  1. APK built with bundled Vosk engine (Phase 3).
  2. Airplane Mode turned ON.
* **Steps:**
  1. Fresh launch of Ultron Assistant.
  2. On first run, observe asset decompression.
  3. Speak a voice command offline.
* **Expected HUD State & Text:**
  - First run displays HUD status: `Preparing offline voice engine...`
  - Subsequent runs listen immediately without external pack dependencies.
  - Command is transcribed and executed 100% offline.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoskEngine] Unpacking bundled Vosk model...`
  - `[UltronVoiceInput] Starting recognizer with step STEP_VOSK`
  - `[UltronVoskEngine] Speech recognized: ...`

---

### e) Wi-Fi Connected But No Data (Captive / Dead Portal)
* **Preconditions:**
  1. Connect phone to a Wi-Fi router or hotspot with WAN/internet disconnected (or a captive portal with no data access).
  2. Android network status indicates "Connected, no internet" (`NET_CAPABILITY_VALIDATED` is false).
* **Steps:**
  1. Open Ultron Assistant.
  2. Tap the mic button.
* **Expected HUD State & Text:**
  - App detects lack of internet validation immediately without stalling for network HTTP timeouts.
  - Routes directly to offline recognition plan.
  - If offline pack or Vosk is available, recognizes speech seamlessly.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] startListening requested (online=false)`
  - Recognizer selects offline step sequence `{STEP_ONDEVICE_API, STEP_SYSTEM_OFFLINE, ...}` rather than hanging on online speech servers.

---

### f) Microphone Permission Denied
* **Preconditions:**
  1. Revoke microphone permission:
     **Settings -> Apps -> Ultron Assistant -> Permissions -> Microphone -> Don't allow**.
* **Steps:**
  1. Open Ultron Assistant.
  2. Tap the mic button (or decline runtime permission prompt).
* **Expected HUD State & Text:**
  - Status updates to error state:
    `Microphone permission required. Tap to grant. [E9]`
  - App does not crash.
* **Expected Log Line in `ultron_voice.log`:**
  - `[UltronVoiceInput] notifyError: Microphone permission required. Tap to grant. [E9]`

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
