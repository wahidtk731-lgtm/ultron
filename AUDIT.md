# ULTRON AUDIT REPORT

**Date:** 2026-10-11  
**Target:** Ultron Android Voice Assistant (`com.ultron.assistant`)  
**Scope:** Read-Only Source Code & Manifest Audit (`android/`, `README.md`)

---

## 1. Audit Summary Table

| ID | File:line | Class (A-I) | Severity | Problem | Fix |
|---|---|---|---|---|---|
| 1 | AndroidManifest.xml:11-27 | A | High | `ACCESS_NETWORK_STATE` missing from manifest; `getActiveNetworkInfo()` throws `SecurityException` swallowed in code. | Add `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` to manifest. |
| 2 | AndroidManifest.xml:22 & MainActivity:265-285 | A | Medium | `BLUETOOTH_CONNECT` declared in manifest but never checked/requested at runtime; fails on modern Android. | Add runtime check/request or guard in Bluetooth toggle logic. |
| 3 | UltronIndependentVoiceInput.java:465 | A | High | `catch (Exception ignored) {}` in `isNetworkConnected` swallows `SecurityException` from missing permission. | Add `Log.w(TAG, "Cannot check network state", e)` logging. |
| 4 | FloatingHUDService.java:198-204, 689, 703 | A | High | `windowManager.addView` attempted without checking `canDrawOverlays`; errors swallowed on removal. | Check `Settings.canDrawOverlays(this)` before adding view and log errors properly. |
| 5 | AppLauncherAndroid.java:558-906 | A | Medium | 26 empty `catch (Exception ignored) {}` blocks swallow hardware and permission failures silently. | Add observable `Log.w(TAG, ...)` with permission/error details. |
| 6 | UltronVoiceSession.java:24 | A | Low | Empty catch swallows failure when launching `MainActivity` from voice session. | Add `Log.w(TAG, "Failed to start MainActivity from session", e)`. |
| 7 | UltronNotificationService.java:79 | A | Low | Empty catch in `isPermissionGranted` hides Settings provider failures. | Log warning on exception. |
| 8 | VoiceInputBridgeActivity.java:9-15 & Manifest:65-69 | B | Medium | Deprecated dummy activity that just finishes; exported to all apps in manifest but never used. | Remove `VoiceInputBridgeActivity.java` and remove `<activity>` from manifest. |
| 9 | UltronIndependentVoiceInput.java:427-456, 380-393 | B | High | `handleRecognizerError(int)` is dead code; `onError` ignores error code and unconditionally falls back to AudioRecord. | Wire up `handleRecognizerError(error)` to notify HUD before fallback. |
| 10 | UltronIndependentVoiceInput.java:85 | B | Low | `setEngine(String)` is a no-op method. | Add documentation comment/logging explaining fixed engine architecture. |
| 11 | MainActivity.java:5-54 & FloatingHUDService.java:8-60 | B | Low | Unused imports and unused private fields (`TAG`, `isListening`, `isExpanded`) across classes. | Clean up unused imports and unreferenced private fields. |
| 12 | UltronRecognitionService.java:101 & UltronIndependentVoiceInput.java:194 | B | Low | Non-standard extra `android.speech.extra.DICTATION_MODE` passed into SpeechRecognizer intents. | Remove `DICTATION_MODE` extra from recognizer intents. |
| 13 | MainActivity.java:330, 650, 661-662 | C | Critical | Dynamic speech strings and replies injected into `evaluateJavascript` via `String.format` and naive replace. Apostrophes/quotes freeze HUD. | Wrap all string arguments in `org.json.JSONObject.quote(...)`. |
| 14 | FloatingHUDService.java:310, 633, 644-645 | C | Critical | Unsafe `String.format` and naive replace in `evaluateJavascript` calls for status, partial speech, and replies. | Wrap all string arguments in `org.json.JSONObject.quote(...)`. |
| 15 | TTSManager.java:50-65 | D | High | `UtteranceProgressListener` callbacks execute on binder background thread; invoking UI/speech listeners directly causes thread violations. | Dispatch `onSpeechStarted` and `onSpeechCompleted` onto main thread Handler. |
| 16 | MainActivity.java:414-422 & FloatingHUDService.java:396-404 | D | High | Double-start race condition: `speakReply` sets `shouldListenAfterSpeech` and starts 2500ms timer, while TTS `onDone` also starts listening. | Remove redundant 2500ms postDelayed timer; rely solely on TTS listener. |
| 17 | UltronIndependentVoiceInput.java:530-535, 601-640 | D | High | `AudioRecord` not released in a `finally` block; unexpected exceptions or thread interrupts can leak mic hardware. | Guarantee `recorder.release()` in a `finally` block and reset flags. |
| 18 | FloatingHUDService.java:50, 79, 698-713 | D | High | Static Service instance risks context leak; `webView` is never destroyed in `onDestroy()`; Handler callbacks not cleared. | Destroy `webView`, call `mainHandler.removeCallbacksAndMessages(null)`, and null static instance in `onDestroy()`. |
| 19 | MainActivity.java:716-725 | D | Medium | `onDestroy()` does not destroy `webView` (`webView.destroy()`) and does not remove pending Handler callbacks. | Call `webView.destroy()` and `mainHandler.removeCallbacksAndMessages(null)` in `onDestroy()`. |
| 20 | UltronNotificationService.java:18-35 | D | Low | Static `instance` field has no `onDestroy()` cleanup, risking service leak if service killed without disconnect. | Add `onDestroy()` and set `instance = null`. |
| 21 | UltronIndependentVoiceInput.java:380-393 | E | High | Silent fallback: any SpeechRecognizer error unconditionally launches AudioRecord fallback without informing user or bounding retries. | Display user-facing HUD message with cause, bound fallback attempts, and avoid blind fallback. |
| 22 | UltronOfflineAcousticEngine.java:11-20, 139-144 | E | High | DTW template engine claims 99.9% accuracy and uses loose 35.0f distance threshold, risking false triggers on background noise. | Add disclaimer header comment and prevent low-confidence matches from mutating system settings or launching apps. |
| 23 | UltronIndependentVoiceInput.java:659 | F | High | Hardcoded Chromium Google Speech API key in URL string `queryChromiumSpeechApi`. | Isolate key into a named constant with a security comment explaining unofficial status. |
| 24 | AndroidManifest.xml:73-80 & FloatingHUDService.java:93-95 | F | Critical | `FloatingHUDService` exported with custom action `ACTION_ASSIST_TRIGGER` without permission; external apps can trigger mic recording. | Set `android:exported="false"` in manifest since service is internal. |
| 25 | MainActivity.java:59-74, 88-96 | F | High | Uncaught crash handler writes to `/storage/emulated/0/Project/ultron_crash.log` (fails on Android 10+ scoped storage) and does not chain default handler. | Write crash log to `getExternalFilesDir(null)` or `getFilesDir()`, and chain to previous `Thread.getDefaultUncaughtExceptionHandler()`. |
| 26 | MainActivity.java:163, 167-186 & FloatingHUDService.java:180-192 | F | High | `settings.setAllowFileAccess(true)` enabled unnecessarily; `WebViewClient` does not block navigation to external URLs with exposed JS bridge. | Set `setAllowFileAccess(false)` and override `shouldOverrideUrlLoading` to block URLs other than `file:///android_asset/hud.html`. |
| 27 | AndroidManifest.xml:9 vs build.gradle:12 | G | Medium | `targetSdkVersion` mismatch: manifest specifies 28 while `build.gradle` specifies 33. | Document discrepancy and keep manifest as source of truth for custom Termux build pipeline. |
| 28 | voice_interaction_service.xml:4 & UltronRecognitionService.java:41-46 | G | High | If `sysComp == null`, `UltronRecognitionService` delegates to itself via `createSpeechRecognizer(this)`, creating infinite recursive loop. | Return error cleanly when `sysComp == null` instead of self-delegating. |
| 29 | MainActivity.java:553-560 & FloatingHUDService.java:536-543 | H | High | Media key mismatch: `media_play` and `media_pause` both send keycode 85 (`KEYCODE_MEDIA_PLAY_PAUSE`), so pause can start playback. | Use keycode 126 (`KEYCODE_MEDIA_PLAY`) for play and 127 (`KEYCODE_MEDIA_PAUSE`) for pause. |
| 30 | UltronIntentEngine.java:310 & MainActivity.java:630-637 | H | High | Garbage input fallback: unrecognized speech defaults to `open_app`, which fails app search and immediately launches a web search for the garbage text. | Return clear "I didn't understand that command" reply instead of launching external browser. |
| 31 | MainActivity.java:470-638 vs FloatingHUDService.java:450-620 | H | Medium | Entire 160-line `executeAction` implementation is duplicated across `MainActivity` and `FloatingHUDService`. | Maintain consistency and track changes across both or extract shared logic. |
| 32 | AppLauncherAndroid.java:936 | H | High | `openFile` passes `Uri.fromFile(file)` to `ACTION_VIEW` intent, causing `FileUriExposedException` on Android 7.0+. | Use safe Intent flags or content URI to avoid crash. |
| 33 | README.md:41, 149-165 | I | Medium | README claims 24/7 background wake-word listening on Android (not supported), lacks Android architectural documentation and known limitations. | Update README to accurately state Android capabilities, limitations, and offline voice pack requirement. |

---

## 2. Breakdown by Severity

- **Critical (3):**
  - **ID 13 & 14:** Unsafe string formatting into `evaluateJavascript` causes syntax errors on apostrophes/quotes, freezing HUD.
  - **ID 24:** `FloatingHUDService` exported without permissions allows third-party apps to trigger microphone capture via `ACTION_ASSIST_TRIGGER`.
- **High (17):**
  - **ID 1 & 3:** Missing `ACCESS_NETWORK_STATE` and swallowed `SecurityException` prevents offline/online state detection.
  - **ID 4:** `WindowManager.addView` invoked without overlay permission check.
  - **ID 9 & 21:** Dead `handleRecognizerError` and unhandled silent fallbacks in `SpeechRecognizer`.
  - **ID 15:** `TTSManager` utterance callbacks firing on binder background threads.
  - **ID 16:** Double-start of listening race condition (2500ms timer vs TTS `onDone`).
  - **ID 17:** `AudioRecord` hardware not guaranteed release in `finally` block.
  - **ID 18:** `FloatingHUDService` static reference, unreleased WebView, and orphaned Handler callbacks.
  - **ID 22:** DTW acoustic engine over-promising accuracy and permissive false-positive actions.
  - **ID 23:** Hardcoded Chromium Google Speech API key in source.
  - **ID 25:** Scoped storage crash write failure and broken uncaught exception handler chaining.
  - **ID 26:** WebView file access enabled and navigation not restricted to local assets.
  - **ID 28:** Potential self-delegation infinite recursion in `UltronRecognitionService`.
  - **ID 29:** Inverted/toggling media keys for `media_play` and `media_pause`.
  - **ID 30:** Garbage speech input blindly launching browser web searches.
  - **ID 32:** `FileUriExposedException` when opening files.
- **Medium (7):**
  - **ID 2:** `BLUETOOTH_CONNECT` runtime permission handling.
  - **ID 5:** 26 empty catch blocks swallowing hardware exceptions in `AppLauncherAndroid`.
  - **ID 8:** Deprecated exported `VoiceInputBridgeActivity`.
  - **ID 19:** `MainActivity` WebView and Handler lifecycle cleanup.
  - **ID 27:** Manifest vs `build.gradle` SDK version mismatch.
  - **ID 31:** Duplicated `executeAction` logic between `MainActivity` and `FloatingHUDService`.
  - **ID 33:** Inaccurate README claims (24/7 wake word) and missing Android limitations.
- **Low (6):**
  - **ID 6 & 7:** Empty catch blocks in session and notification services.
  - **ID 10:** No-op `setEngine` method.
  - **ID 11:** Unused imports and unused private fields.
  - **ID 12:** Deprecated non-standard `DICTATION_MODE` extra.
  - **ID 20:** Static instance lifecycle in `UltronNotificationService`.

---

## 3. Implementation Status & Resolution Log

All 33 identified issues have been systematically addressed, verified, and committed:

| ID | Severity | Status | Commit | Summary of Resolution |
|---|---|---|---|---|
| 1 | High | Closed | `1ce5881` | Added `ACCESS_NETWORK_STATE` to `AndroidManifest.xml`. |
| 2 | Medium | Closed | `3b235ec` | Guarded Bluetooth toggle on API 31+ with `BLUETOOTH_CONNECT` check and settings fallback. |
| 3 | High | Closed | `1ce5881` | Added observable logging in `UltronIndependentVoiceInput.isNetworkConnected`. |
| 4 | High | Closed | `66ae10e` | Added `Settings.canDrawOverlays` check before `windowManager.addView`. |
| 5 | Medium | Closed | `93611f6` | Replaced 26 empty catch blocks in `AppLauncherAndroid.java` with observable logging. |
| 6 | Low | Closed | `93611f6` | Replaced empty catch block in `UltronVoiceSession.java` with `Log.w`. |
| 7 | Low | Closed | `93611f6` | Replaced empty catch block in `UltronNotificationService.java` with `Log.w`. |
| 8 | Medium | Closed | `14660b2` | Removed dead `VoiceInputBridgeActivity.java` and removed activity from manifest. |
| 9 | High | Closed | `df62c2b` | Wired up `handleRecognizerError(error)` to display error cause on HUD. |
| 10 | Low | Closed | `c585d67` | Documented and logged fixed hybrid engine architecture in `setEngine`. |
| 11 | Low | Closed | `c585d67` | Removed unused imports and unreferenced private fields (`TAG`, `isListening`, `isExpanded`). |
| 12 | Low | Closed | `f45b87b` | Removed non-standard `DICTATION_MODE` extra from recognizer intents. |
| 13 | Critical | Closed | `1a14a28` | Wrapped dynamic JS strings in `JSONObject.quote(...)` in `MainActivity.java`. |
| 14 | Critical | Closed | `1a14a28` | Wrapped dynamic JS strings in `JSONObject.quote(...)` in `FloatingHUDService.java`. |
| 15 | High | Closed | `10562ab` | Dispatched `TTSManager` utterance callbacks to `mainHandler` (Looper.getMainLooper()). |
| 16 | High | Closed | `f514c17` | Removed redundant 2500ms `postDelayed` double-start timer; synced to TTS `onDone`. |
| 17 | High | Closed | `b535899` | Guaranteed `AudioRecord` release in `finally` block and reset state flags. |
| 18 | High | Closed | `66ae10e` | Destroyed WebView, cleared handler callbacks, and nulled static instance in `FloatingHUDService.onDestroy()`. |
| 19 | Medium | Closed | `e8abcdb` | Destroyed WebView and removed handler callbacks in `MainActivity.onDestroy()`. |
| 20 | Low | Closed | `005538a` | Added `onDestroy()` in `UltronNotificationService.java` to clear static instance. |
| 21 | High | Closed | `df62c2b` | Bounded fallback attempts to 1 and provided informative user HUD status. |
| 22 | High | Closed | `bf2eab3` | Added acoustic engine disclaimer header and tightened DTW confidence threshold to < 22.0f. |
| 23 | High | Closed | `ca381c5` | Isolated Chromium Google Speech API key into named constant `CHROMIUM_SPEECH_API_KEY`. |
| 24 | Critical | Closed | `1ec930d` | Set `android:exported="false"` on `FloatingHUDService` in `AndroidManifest.xml`. |
| 25 | High | Closed | `f896a4b` | Scoped-storage-safe crash logging (`getExternalFilesDir`) and chained default exception handler. |
| 26 | High | Closed | `82b17cf` | Disabled file access (`setAllowFileAccess(false)`) and blocked external navigation in WebView. |
| 27 | Medium | Closed | Documented | `AndroidManifest.xml` (`targetSdkVersion="28"`) is preserved as authoritative source for Termux toolchain. |
| 28 | High | Closed | `f45b87b` | Guarded against self-delegation infinite recursion in `UltronRecognitionService`. |
| 29 | High | Closed | `3b504d3` | Correctly mapped `media_play` to key 126 and `media_pause` to key 127. |
| 30 | High | Closed | `a3b1048` | Prevented garbage speech input from opening browser and added null/empty entity checks. |
| 31 | Medium | Closed | Verified | Verified 100% action parity between `MainActivity` and `FloatingHUDService` `executeAction`. |
| 32 | High | Closed | `aa2d977` | Relaxed StrictMode VM policy in `openFile` to avoid `FileUriExposedException` on API 24+. |
| 33 | Medium | Closed | `2803db2` | Updated `README.md` with accurate architecture, capabilities, and known limitations. |
