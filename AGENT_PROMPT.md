# TASK: Audit and clean up the Ultron Android assistant (Java + WebView HUD)

Project: Android voice assistant "Ultron", package com.ultron.assistant.
Code:     android/app/src/main/java/com/ultron/assistant/   (Java, NOT Kotlin)
UI:       android/app/src/main/assets/hud.html  (JS bridge object: AndroidUltron)
Manifest: android/app/src/main/AndroidManifest.xml
Symptom:  the assistant sometimes does not listen / does not respond.
Goal:     find and fix every other bug of the kinds listed below, without breaking
          working behavior.

## ENVIRONMENT RULES (Termux terminal, phone hardware)
- You run inside Termux. No GUI, no emulator, no Android Studio, no Gradle.
- Use `pkg install` (never apt/sudo). Do not install anything large without asking.
- Keep terminal output SMALL. Never `cat` a whole big file. Use:
    grep -n "pattern" file | head -40
    sed -n '120,180p' file
    wc -l file
- NEVER open or search these (huge/binary): *.apk, *.pkl, intent_model.json,
  expanded_training_data.json, favi.png, mipmap-*/*.png. Exclude them in every grep:
    grep -rn --include=*.java --include=*.xml --include=*.html "pattern" android/
- Ignore the Python desktop files (ultron.py, floating_widget.py, etc.). Android only.
- Terminal sessions can die. Write findings to files (AUDIT.md) as you go, and run
  `git add -A && git commit -m "<what changed>"` after every fix step.
- Never run interactive programs (nano, vim, less). Use non-interactive commands.
- Do not run `adb`, start the app, or invent runtime/logcat results. Runtime testing
  is done by me on the phone.

## BUILD FACTS (do not change)
- The real APK is built by build_apk.sh (aapt + javac/ecj + d8 + zipalign), and by
  .github/workflows/build-apk.yml. It is NOT built by Gradle. android/app/build.gradle
  is not used by the real build; report mismatches with the manifest, do not "fix"
  the build by switching to Gradle.
- Manifest values (targetSdk 28, permissions) are what ships.
- Java syntax must compile under `-source 7` (Termux build) and `-source 11` (CI).
  So: NO lambdas, NO streams, NO `var`, NO switch expressions, NO text blocks, NO
  default interface methods. Use anonymous classes like the existing code.
  Diamond `<>` and try-with-resources are OK.
- build_apk.sh compiles against an android-30 android.jar. Do NOT call any API newer
  than API 30 directly; use reflection or integer literals with a comment (existing
  code already does this for API 31/33 speech APIs). Do not add libraries.
- Keep changes small and reviewable. No reformatting, no renames of public methods,
  no moving files.

## ALREADY FIXED (do not redo, do not regress)
1. ACCESS_NETWORK_STATE added to the manifest (missing permission made a
   SecurityException get swallowed, so the app always believed it was offline).
2. UltronIndependentVoiceInput.java: per-error handling, bounded retries, engine and
   language switching, generation-guarded callbacks, 6 s "recognizer never ready"
   watchdog, partial-result recovery.
3. JS bridge strings in MainActivity and FloatingHUDService now use
   org.json.JSONObject.quote(...) instead of String.format("'%s'") (an apostrophe in
   "Didn't catch that" caused a JS syntax error and froze the HUD).
4. Removed the non-standard DICTATION_MODE extra.

## BUG CLASSES TO HUNT (grep the whole android/ folder for each)

A. Missing permissions / swallowed exceptions
 - For every Android API used, confirm the manifest declares the needed permission
   and that runtime permissions are requested where required (RECORD_AUDIO,
   BLUETOOTH_CONNECT on API 31+, overlay, notification listener).
 - Find every `catch (Exception ignored)` / `catch (Throwable ignored)` and empty
   catch. If it can hide a real failure, add Log.w(TAG, ...) with the message; for
   SecurityException log which permission is probably missing. Keep the catch if it
   guards OEM differences; just make it observable.

B. Dead or unused code
 - Methods never called, unused fields/imports, deprecated bridges
   (VoiceInputBridgeActivity), no-op methods (setEngine). Before deleting anything,
   grep hud.html for `AndroidUltron.` and keep every method it calls.

C. Unsafe strings sent into JavaScript / HTML
 - Grep `evaluateJavascript(`, `javascript:`, `String.format(` near JS. Every dynamic
   string must go through org.json.JSONObject.quote(...).
 - In hud.html, check innerHTML assignments that receive speech text, replies, or
   installed-app names. Use textContent unless HTML is intended.

D. Threading and lifecycle
 - SpeechRecognizer, TextToSpeech, WebView calls and Handlers: main thread only.
   AudioRecord loops: worker thread, stoppable flag, recorder released in finally.
 - Look for: stale callbacks after destroy(), double-start of listening (TTS onDone
   plus a postDelayed timer both calling startListening), Handler callbacks not
   removed in onDestroy, static references to Activity/Service
   (FloatingHUDService.instance), WebView not destroyed, TTS not shut down.
 - The mic must not open while TTS is speaking. Replace fixed delays (e.g. 2500 ms)
   with the TTS-completed callback where one exists.

E. Silent fallbacks that hide the real problem
 - Every fallback must (1) log why, (2) show a clear HUD message, (3) be bounded so
   it cannot loop.
 - UltronOfflineAcousticEngine (DTW over hand-typed phoneme vectors) cannot do real
   speech recognition. Make sure a low-confidence match NEVER launches an app or
   changes a system setting without confirmation. Add a comment at the top of the
   class stating its limits.

F. Security and secrets
 - Hard-coded API keys/URLs (Chromium speech endpoint key in
   UltronIndependentVoiceInput): isolate in a named constant with a comment, or
   remove if unofficial. Report which you chose.
 - Manifest components with exported="true": confirm each needs it. Check whether
   other apps can trigger FloatingHUDService (custom action) to open the mic.
 - WebView: JS is enabled, file access is enabled, and a JavascriptInterface exists.
   Confirm only file:///android_asset/hud.html loads; block navigation to any other
   URL in the WebViewClient; disable file access if hud.html does not need it.
 - The crash handler writes to /storage/emulated/0/Project/ultron_crash.log, which
   fails under scoped storage on Android 10+. Write to getExternalFilesDir(null) or
   getFilesDir(), and chain to the previous default UncaughtExceptionHandler so the
   process still terminates normally.

G. Manifest and config consistency
 - Compare manifest vs build.gradle (minSdk, targetSdk, versionCode/Name, package).
 - Every <service>/<activity> class must exist; foregroundServiceType must match the
   startForeground usage; notification channel created before startForeground.
 - res/xml/voice_interaction_service.xml and recognition_service.xml must reference
   existing classes and must not let recognition delegate to Ultron itself.

H. Command-handling logic
 - Check UltronIntentEngine and executeAction (in MainActivity AND FloatingHUDService,
   they are duplicated): null/empty entities, compound-command splitting, the
   `default:` branch launching apps/web searches from garbage input, and wrong
   mappings (media_play and media_pause both send key 85, so "pause" can start
   playback). Report duplicated logic; only merge it if the change is small.

I. Docs vs reality
 - README claims features that may not exist on Android (e.g. always-on "Hey Ultron"
   wake word). Verify each claim in code, fix the README, add "Known limitations".

## PROCESS (follow in this order)
1. READ ONLY first. Write AUDIT.md: table with columns
   ID | File:line | Class (A-I) | Severity (Critical/High/Medium/Low) | Problem | Fix.
   Do not edit code until AUDIT.md is written. Then STOP and show me the table
   (summary only, max 40 lines) and wait for my "go".
2. After "go": fix Critical and High first, one logical change per commit. After each
   commit, state which AUDIT.md IDs are closed.
3. Medium/Low: fix only if small and safe; otherwise mark "deferred" with a reason.
4. Anything new found mid-fix goes into AUDIT.md first, then gets fixed.

## VERIFICATION (required before saying "done")
- Compile check with the real toolchain if present:
    aapt package -f -m -J gen -M android/app/src/main/AndroidManifest.xml \
      -S android/app/src/main/res -I "$HOME/.android-sdk/android.jar"
    javac -source 7 -target 7 -d obj -cp "$HOME/.android-sdk/android.jar:gen" \
      $(find android/app/src/main/java gen -name "*.java")
  If javac/aapt/android.jar are missing, say so and do a careful read-through
  instead. NEVER claim "builds successfully" unless the command actually ran and
  exited 0; paste the exit code.
- Show grep proof (short output): no `String.format("...'%s'...` reaching
  evaluateJavascript; no empty catch blocks in files you touched; every
  `AndroidUltron.xxx` used in hud.html has a matching @JavascriptInterface method.
- Write MANUAL_TESTS.md for me to run on the phone: mic permission denied/granted,
  airplane mode with and without offline voice pack, tap-to-speak, floating overlay,
  TTS then re-listen, screen rotation, kill app mid-listen, and the apostrophe test
  ("Didn't catch that" must display). For each: expected HUD state.

## OUTPUT RULES
- End with `git diff --stat HEAD~N` style summary (files, +/- lines).
- State plainly what you could not verify.
- If a fix needs my decision (raise targetSdk, remove the DTW engine, remove the
  unofficial speech API), stop and ask with 2-3 options and your recommendation.
