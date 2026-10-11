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
