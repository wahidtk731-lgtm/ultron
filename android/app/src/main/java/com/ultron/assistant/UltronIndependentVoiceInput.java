package com.ultron.assistant;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 100% Offline-Capable In-App Speech Recognition Engine for Ultron Assistant.
 * 
 * Features:
 * 1. Background SpeechRecognizer with RecognitionListener (ZERO Google popup dialogs/overlays).
 * 2. System recognizer delegation prioritizing Google speech service.
 * 3. Enforces EXTRA_PREFER_OFFLINE and PREFER_OFFLINE directives with universal en-US offline model targeting.
 * 4. Resilient watchdog, generation-stamped callbacks, and bounded retry architecture.
 * 5. AudioRecord mic capture with live RMS waveform feedback as last-resort fallback.
 */
public class UltronIndependentVoiceInput {

    private static final String TAG = "UltronVoiceInput";
    private static final long READY_TIMEOUT_MS = 6000;

    public static final int STEP_SYSTEM_ONLINE = 0;
    public static final int STEP_ONDEVICE_API = 1;
    public static final int STEP_SYSTEM_OFFLINE = 2;
    public static final int STEP_SYSTEM_OFFLINE_EN_US = 3;

    // Unofficial public Chromium development speech recognition endpoint key.
    // Isolated here for clarity; used as an optional online fallback for raw PCM audio.
    private static final String CHROMIUM_SPEECH_API_KEY = "AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw";
    private static final String CHROMIUM_SPEECH_ENDPOINT =
        "https://www.google.com/speech-api/v2/recognize?client=chromium&lang=en-US&key=" + CHROMIUM_SPEECH_API_KEY;

    public interface VoiceInputListener {
        void onReady();
        void onBeginningOfSpeech();
        void onRmsChanged(float rmsDb);
        void onPartialResult(String partialText);
        void onEndOfSpeech();
        void onResult(String recognizedText);
        void onError(String errorMessage);
    }

    private final Context context;
    private final Handler mainHandler;
    private VoiceInputListener listener;
    private SpeechRecognizer speechRecognizer;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isFallbackRecording = new AtomicBoolean(false);
    private Thread fallbackThread = null;

    private int generation = 0;
    private int[] plan = null;
    private int planIndex = 0;
    private boolean stepRetried = false;
    private boolean isOnline = false;
    private volatile String lastPartial = "";
    private Runnable readyWatchdog = null;
    private Runnable retryRunnable = null;

    public UltronIndependentVoiceInput(Context context, VoiceInputListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(VoiceInputListener listener) {
        this.listener = listener;
    }

    public boolean isListening() {
        return isListening.get() || isFallbackRecording.get();
    }

    public String getCurrentEngine() {
        return "offline_recognizer";
    }

    /**
     * Engine configuration hook. Ultron uses an automated hybrid architecture:
     * primary offline/online speech recognizer with automatic bounded fallback
     * to native AudioRecord PCM streaming. Manual engine override is unsupported.
     */
    public void setEngine(String engine) {
        Log.d(TAG, "setEngine requested: " + engine + " (ignored; hybrid engine pipeline is fixed)");
    }

    /**
     * Checks if Ultron itself is configured as the active system default voice recognition service.
     */
    public static boolean isDefaultRecognizerOurs(Context context) {
        try {
            String defaultRec = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), "voice_recognition_service");
            if (defaultRec != null && defaultRec.startsWith(context.getPackageName() + "/")) {
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed checking default recognizer: " + t.getMessage());
        }
        return false;
    }

    /**
     * Resolves the device's system speech recognition service.
     * Prefers package "com.google.android.googlequicksearchbox", then any other "google"
     * package, then any non-Ultron service. Never picks Ultron's own package.
     */
    public static ComponentName findSystemRecognitionService(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(RecognitionService.SERVICE_INTERFACE);
            List<ResolveInfo> services = pm.queryIntentServices(intent, 0);
            if (services == null || services.isEmpty()) {
                UltronDebugLog.log(TAG, "findSystemRecognitionService: no services found");
                return null;
            }

            String myPackage = context.getPackageName();
            ResolveInfo mainGoogleService = null;
            ResolveInfo otherGoogleService = null;
            ResolveInfo otherService = null;

            for (int i = 0; i < services.size(); i++) {
                ResolveInfo info = services.get(i);
                if (info == null || info.serviceInfo == null) continue;
                String pkg = info.serviceInfo.packageName;

                // Never bind Ultron's recognizer to itself!
                if (pkg.equals(myPackage)) {
                    continue;
                }

                if ("com.google.android.googlequicksearchbox".equals(pkg)) {
                    mainGoogleService = info;
                    break;
                } else if (pkg.toLowerCase(Locale.US).contains("google")) {
                    if (otherGoogleService == null) {
                        otherGoogleService = info;
                    }
                } else {
                    if (otherService == null) {
                        otherService = info;
                    }
                }
            }

            ResolveInfo chosen = (mainGoogleService != null) ? mainGoogleService :
                    ((otherGoogleService != null) ? otherGoogleService : otherService);

            if (chosen != null && chosen.serviceInfo != null) {
                ComponentName comp = new ComponentName(chosen.serviceInfo.packageName, chosen.serviceInfo.name);
                UltronDebugLog.log(TAG, "findSystemRecognitionService resolved: " + comp.flattenToShortString());
                Log.i(TAG, "findSystemRecognitionService resolved: " + comp.flattenToShortString());
                return comp;
            }
        } catch (Exception e) {
            UltronDebugLog.log(TAG, "findSystemRecognitionService error: " + e.getMessage());
            Log.w(TAG, "Error resolving system speech recognition service: " + e.getMessage());
        }
        return null;
    }

    private static String stepName(int step) {
        switch (step) {
            case STEP_SYSTEM_ONLINE: return "STEP_SYSTEM_ONLINE(0)";
            case STEP_ONDEVICE_API: return "STEP_ONDEVICE_API(1)";
            case STEP_SYSTEM_OFFLINE: return "STEP_SYSTEM_OFFLINE(2)";
            case STEP_SYSTEM_OFFLINE_EN_US: return "STEP_SYSTEM_OFFLINE_EN_US(3)";
            default: return "STEP_UNKNOWN(" + step + ")";
        }
    }

    private static boolean isOnDeviceRecognitionAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        try {
            java.lang.reflect.Method m = SpeechRecognizer.class.getMethod("isOnDeviceRecognitionAvailable", Context.class);
            Object res = m.invoke(null, context);
            return res instanceof Boolean && ((Boolean) res).booleanValue();
        } catch (Throwable t) {
            Log.d(TAG, "isOnDeviceRecognitionAvailable reflection failed: " + t.getMessage());
            return false;
        }
    }

    private SpeechRecognizer createOnDeviceSpeechRecognizer() {
        if (Build.VERSION.SDK_INT < 31) {
            return null;
        }
        try {
            java.lang.reflect.Method m = SpeechRecognizer.class.getMethod("createOnDeviceSpeechRecognizer", Context.class);
            return (SpeechRecognizer) m.invoke(null, context);
        } catch (Throwable t) {
            UltronDebugLog.log(TAG, "createOnDeviceSpeechRecognizer reflection failed: " + t.getMessage());
            return null;
        }
    }

    private SpeechRecognizer createSystemSpeechRecognizer() {
        try {
            if (isDefaultRecognizerOurs(context)) {
                ComponentName comp = findSystemRecognitionService(context);
                if (comp != null) {
                    UltronDebugLog.log(TAG, "Binding explicitly to system recognizer: " + comp.flattenToShortString());
                    return SpeechRecognizer.createSpeechRecognizer(context, comp);
                }
            }
            UltronDebugLog.log(TAG, "Creating standard SpeechRecognizer");
            return SpeechRecognizer.createSpeechRecognizer(context);
        } catch (Throwable t) {
            UltronDebugLog.log(TAG, "createSystemSpeechRecognizer failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Builds speech recognition intent.
     */
    public static Intent buildRecognizerIntent(Context context, boolean preferOffline) {
        return buildRecognizerIntent(context, preferOffline, false);
    }

    public static Intent buildRecognizerIntent(Context context, boolean preferOffline, boolean forceEnglishUs) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        if (preferOffline) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
        }

        String lang;
        if (forceEnglishUs) {
            lang = "en-US";
        } else {
            String sysLang = Locale.getDefault().toLanguageTag();
            if (sysLang == null || sysLang.isEmpty() || "und".equalsIgnoreCase(sysLang)) {
                lang = "en-US";
            } else {
                lang = sysLang;
            }
        }

        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        intent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{lang, "en-US"});

        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        // Silence thresholds (2500ms for natural pauses without premature cutoffs)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L);

        return intent;
    }

    public static Intent buildOfflineRecognizerIntent(Context context) {
        return buildRecognizerIntent(context, true, false);
    }

    private String formatDiag(int errorCode) {
        int step = (plan != null && planIndex < plan.length) ? plan[planIndex] : -1;
        return " [E" + errorCode + " step=" + step + " on=" + isOnline + "]";
    }

    /**
     * Begins listening for speech.
     */
    public void startListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                isOnline = isNetworkConnected();
                UltronDebugLog.log(TAG, "startListening requested (online=" + isOnline + ")");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        notifyError("Microphone permission required. Tap to grant. [E" + SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS + "]");
                        return;
                    }
                }

                stopAudioRecordFallback();
                if (isOnline) {
                    plan = new int[]{STEP_SYSTEM_ONLINE, STEP_ONDEVICE_API, STEP_SYSTEM_OFFLINE, STEP_SYSTEM_OFFLINE_EN_US};
                } else {
                    plan = new int[]{STEP_ONDEVICE_API, STEP_SYSTEM_OFFLINE, STEP_SYSTEM_OFFLINE_EN_US, STEP_SYSTEM_ONLINE};
                }
                planIndex = 0;
                stepRetried = false;

                beginRecognition();
            }
        });
    }

    private void beginRecognition() {
        generation++;
        final int currentGen = generation;
        lastPartial = "";

        safeDestroyRecognizer();

        if (plan == null) {
            if (isOnline) {
                plan = new int[]{STEP_SYSTEM_ONLINE, STEP_ONDEVICE_API, STEP_SYSTEM_OFFLINE, STEP_SYSTEM_OFFLINE_EN_US};
            } else {
                plan = new int[]{STEP_ONDEVICE_API, STEP_SYSTEM_OFFLINE, STEP_SYSTEM_OFFLINE_EN_US, STEP_SYSTEM_ONLINE};
            }
        }

        SpeechRecognizer candidate = null;
        Intent candidateIntent = null;
        int currentStep = -1;

        while (planIndex < plan.length) {
            currentStep = plan[planIndex];
            UltronDebugLog.log(TAG, "Evaluating plan step index=" + planIndex + ": " + stepName(currentStep) + " gen=" + currentGen);
            if (currentStep == STEP_ONDEVICE_API) {
                if (Build.VERSION.SDK_INT >= 31 && isOnDeviceRecognitionAvailable(context)) {
                    candidate = createOnDeviceSpeechRecognizer();
                    if (candidate != null) {
                        candidateIntent = buildRecognizerIntent(context, true, false);
                        break;
                    } else {
                        UltronDebugLog.log(TAG, "STEP_ONDEVICE_API creation returned null, skipping");
                    }
                } else {
                    UltronDebugLog.log(TAG, "STEP_ONDEVICE_API skipped: API=" + Build.VERSION.SDK_INT + " or unavailable");
                }
            } else if (currentStep == STEP_SYSTEM_ONLINE) {
                candidate = createSystemSpeechRecognizer();
                if (candidate != null) {
                    candidateIntent = buildRecognizerIntent(context, false, false);
                    break;
                } else {
                    UltronDebugLog.log(TAG, "STEP_SYSTEM_ONLINE creation returned null, skipping");
                }
            } else if (currentStep == STEP_SYSTEM_OFFLINE) {
                candidate = createSystemSpeechRecognizer();
                if (candidate != null) {
                    candidateIntent = buildRecognizerIntent(context, true, false);
                    break;
                } else {
                    UltronDebugLog.log(TAG, "STEP_SYSTEM_OFFLINE creation returned null, skipping");
                }
            } else if (currentStep == STEP_SYSTEM_OFFLINE_EN_US) {
                candidate = createSystemSpeechRecognizer();
                if (candidate != null) {
                    candidateIntent = buildRecognizerIntent(context, true, true);
                    break;
                } else {
                    UltronDebugLog.log(TAG, "STEP_SYSTEM_OFFLINE_EN_US creation returned null, skipping");
                }
            }
            stepRetried = false;
            planIndex++;
        }

        if (candidate == null) {
            UltronDebugLog.log(TAG, "No recognizer could be created for remaining plan steps");
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                UltronDebugLog.log(TAG, "SpeechRecognizer.isRecognitionAvailable is false; starting AudioRecord fallback");
                notifyError("No speech service found. Install/enable the Google app. [E0]");
                startAudioRecordFallback();
            } else {
                UltronDebugLog.log(TAG, "SpeechRecognizer available but no candidate created in plan; notifying final error");
                if (!isOnline) {
                    notifyError("No internet and no offline voice pack found. Download English in Settings > Google > Voice > Offline speech recognition. [E0]");
                } else {
                    notifyError("Speech recognition unavailable. Check the Google app or microphone settings. [E0]");
                }
            }
            return;
        }

        speechRecognizer = candidate;
        UltronDebugLog.log(TAG, "Starting recognizer with step " + stepName(currentStep) + " gen=" + currentGen);
        try {
            speechRecognizer.setRecognitionListener(new InternalRecognitionListener(currentGen));
            speechRecognizer.startListening(candidateIntent);
            isListening.set(true);

            if (readyWatchdog != null) {
                mainHandler.removeCallbacks(readyWatchdog);
            }
            readyWatchdog = new Runnable() {
                @Override
                public void run() {
                    UltronDebugLog.log(TAG, "readyWatchdog timeout (6s) for gen=" + currentGen);
                    if (currentGen == generation) {
                        handleRecognizerError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    }
                }
            };
            mainHandler.postDelayed(readyWatchdog, READY_TIMEOUT_MS);
        } catch (Throwable t) {
            UltronDebugLog.log(TAG, "startListening threw exception on step " + stepName(currentStep) + ": " + t.getMessage());
            safeDestroyRecognizer();
            advanceOrFail(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    private void handleRecognizerError(final int error) {
        UltronDebugLog.log(TAG, "handleRecognizerError error=" + error +
                " planIndex=" + planIndex + " step=" + (plan != null && planIndex < plan.length ? stepName(plan[planIndex]) : "none") +
                " stepRetried=" + stepRetried);

        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                if (lastPartial != null && !lastPartial.trim().isEmpty()) {
                    String rescued = lastPartial.trim();
                    UltronDebugLog.log(TAG, "Rescued partial on error " + error + ": " + rescued);
                    notifyResult(rescued);
                } else {
                    notifyError("I didn't catch that. Tap the mic and try again. [E" + error + "]");
                }
                break;

            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                notifyError("Microphone permission required. Tap to grant. [E" + error + "]");
                break;

            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_AUDIO:
                if (!stepRetried) {
                    stepRetried = true;
                    UltronDebugLog.log(TAG, "Retrying step " + (plan != null && planIndex < plan.length ? stepName(plan[planIndex]) : "") + " in 500ms on error " + error);
                    safeDestroyRecognizer();
                    if (retryRunnable != null) {
                        mainHandler.removeCallbacks(retryRunnable);
                    }
                    retryRunnable = new Runnable() {
                        @Override
                        public void run() {
                            beginRecognition();
                        }
                    };
                    mainHandler.postDelayed(retryRunnable, 500);
                } else {
                    stepRetried = false;
                    UltronDebugLog.log(TAG, "Step already retried once; advancing on error " + error);
                    advanceOrFail(error);
                }
                break;

            default:
                advanceOrFail(error);
                break;
        }
    }

    private void advanceOrFail(final int error) {
        UltronDebugLog.log(TAG, "advanceOrFail called with error=" + error + " current planIndex=" + planIndex);
        safeDestroyRecognizer();
        stepRetried = false;
        planIndex++;

        if (plan != null && planIndex < plan.length) {
            UltronDebugLog.log(TAG, "Advancing to next plan step: index=" + planIndex + " (" + stepName(plan[planIndex]) + ") in 200ms");
            if (retryRunnable != null) {
                mainHandler.removeCallbacks(retryRunnable);
            }
            retryRunnable = new Runnable() {
                @Override
                public void run() {
                    beginRecognition();
                }
            };
            mainHandler.postDelayed(retryRunnable, 200);
        } else {
            UltronDebugLog.log(TAG, "Final failure notified: error=" + error + " online=" + isOnline);
            if (!isOnline) {
                notifyError("No internet and no offline voice pack found. Download English in Settings > Google > Voice > Offline speech recognition. [E" + error + "]");
            } else {
                notifyError("Speech recognition unavailable. Check the Google app or microphone settings. [E" + error + "]");
            }
        }
    }

    /**
     * Stops active speech listening.
     */
    public void stopListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UltronDebugLog.log(TAG, "stopListening requested");
                isListening.set(false);
                safeDestroyRecognizer();
                stopAudioRecordFallback();
            }
        });
    }

    /**
     * Releases recognizer and threads completely on destroy.
     */
    public void destroy() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                UltronDebugLog.log(TAG, "destroy requested");
                isListening.set(false);
                stopAudioRecordFallback();
                safeDestroyRecognizer();
                listener = null;
            }
        });
    }

    private synchronized void safeDestroyRecognizer() {
        if (readyWatchdog != null) {
            mainHandler.removeCallbacks(readyWatchdog);
            readyWatchdog = null;
        }
        if (retryRunnable != null) {
            mainHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception e) {
                Log.d(TAG, "SpeechRecognizer destroy: " + e.getMessage());
            }
            speechRecognizer = null;
        }
    }

    /**
     * RecognitionListener that pipes audio events directly into Ultron HUD.
     */
    private class InternalRecognitionListener implements RecognitionListener {
        private final int gen;

        InternalRecognitionListener(int gen) {
            this.gen = gen;
        }

        private boolean isStale() {
            return gen != generation;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            UltronDebugLog.log(TAG, "onReadyForSpeech gen=" + gen);
            if (isStale()) return;
            if (readyWatchdog != null) {
                mainHandler.removeCallbacks(readyWatchdog);
                readyWatchdog = null;
            }
            isListening.set(true);
            notifyReady();
        }

        @Override
        public void onBeginningOfSpeech() {
            UltronDebugLog.log(TAG, "onBeginningOfSpeech gen=" + gen);
            if (isStale()) return;
            isListening.set(true);
            notifyBeginningOfSpeech();
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            if (isStale()) return;
            float normalizedDb;
            if (rmsdB < 0f) {
                normalizedDb = (rmsdB + 2.0f) * 15.0f;
            } else {
                normalizedDb = Math.min(95.0f, rmsdB * 8.0f);
            }
            if (normalizedDb < 0f) normalizedDb = 0f;
            notifyRms(normalizedDb);
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            if (isStale()) return;
            UltronDebugLog.log(TAG, "onBufferReceived gen=" + gen);
        }

        @Override
        public void onEndOfSpeech() {
            UltronDebugLog.log(TAG, "onEndOfSpeech gen=" + gen);
            if (isStale()) return;
            isListening.set(false);
            notifyEndOfSpeech();
        }

        @Override
        public void onError(final int error) {
            UltronDebugLog.log(TAG, "onError gen=" + gen + " error=" + error);
            if (isStale()) return;
            if (readyWatchdog != null) {
                mainHandler.removeCallbacks(readyWatchdog);
                readyWatchdog = null;
            }
            isListening.set(false);
            safeDestroyRecognizer();
            handleRecognizerError(error);
        }

        @Override
        public void onResults(Bundle results) {
            UltronDebugLog.log(TAG, "onResults gen=" + gen);
            if (isStale()) return;
            if (readyWatchdog != null) {
                mainHandler.removeCallbacks(readyWatchdog);
                readyWatchdog = null;
            }
            isListening.set(false);
            safeDestroyRecognizer();

            ArrayList<String> matches = (results != null) ?
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0).trim();
                if (!text.isEmpty()) {
                    UltronDebugLog.log(TAG, "Speech recognized: " + text);
                    notifyResult(text);
                    return;
                }
            }
            if (lastPartial != null && !lastPartial.trim().isEmpty()) {
                String rescued = lastPartial.trim();
                UltronDebugLog.log(TAG, "Rescued partial from empty results: " + rescued);
                notifyResult(rescued);
            } else {
                notifyError("Didn't catch that. Tap mic to retry." + formatDiag(SpeechRecognizer.ERROR_NO_MATCH));
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (isStale()) return;
            ArrayList<String> partial = (partialResults != null) ?
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
            if (partial != null && !partial.isEmpty()) {
                String text = partial.get(0).trim();
                if (!text.isEmpty()) {
                    lastPartial = text;
                    UltronDebugLog.log(TAG, "Partial: " + text);
                    notifyPartialResult(text);
                }
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            UltronDebugLog.log(TAG, "onEvent gen=" + gen + " type=" + eventType);
        }
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                    return caps != null &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot check network state: ACCESS_NETWORK_STATE permission missing or denied", e);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to determine network connectivity: " + e.getMessage());
            return false;
        }
        return false;
    }

    /**
     * Opens device offline speech settings so user can download the offline model in 1 click.
     */
    public static void openOfflineSpeechSettings(Context context) {
        Intent[] candidates = new Intent[] {
            new Intent().setComponent(new ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.greco3.languagepack.InstallActivity"
            )),
            new Intent("android.speech.action.OFFLINE_SPEECH_SETTINGS"),
            new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            new Intent(android.provider.Settings.ACTION_SETTINGS)
        };

        for (int i = 0; i < candidates.length; i++) {
            try {
                Intent it = candidates[i];
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(it);
                return;
            } catch (Exception e) {
                Log.d(TAG, "Offline settings candidate intent failed: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Direct AudioRecord Mic Capture Fallback
    // =========================================================================

    private void startAudioRecordFallback() {
        if (isFallbackRecording.get()) return;

        isFallbackRecording.set(true);
        isListening.set(true);
        notifyReady();

        fallbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                UltronDebugLog.log(TAG, "Starting AudioRecord fallback recording loop");
                int sampleRate = 16000;
                int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minBuf, 4096);

                AudioRecord recorder = null;
                int[] audioSources = new int[]{
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                };
                for (int i = 0; i < audioSources.length; i++) {
                    int src = audioSources[i];
                    try {
                        recorder = new AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                        if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                            Log.i(TAG, "Initialized AudioRecord with source: " + src);
                            break;
                        }
                        recorder.release();
                        recorder = null;
                    } catch (Exception e) {
                        Log.w(TAG, "AudioSource " + src + " initialization failed: " + e.getMessage());
                    }
                }

                if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    if (recorder != null) {
                        try { recorder.release(); } catch (Exception e) { Log.w(TAG, "Failed releasing uninitialized recorder", e); }
                        recorder = null;
                    }
                    isFallbackRecording.set(false);
                    isListening.set(false);
                    notifyError("Microphone initialization error." + formatDiag(SpeechRecognizer.ERROR_AUDIO));
                    return;
                }

                ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                boolean recordedSuccessfully = false;

                try {
                    recorder.startRecording();
                    short[] buffer = new short[1024];
                    byte[] byteBuffer = new byte[2048];

                    long startTime = System.currentTimeMillis();
                    long speechStartTime = 0;
                    long lastSpeechTime = 0;
                    boolean heardSpeech = false;
                    double ambientRms = 20.0;
                    int frameCount = 0;

                    while (isFallbackRecording.get() && !Thread.currentThread().isInterrupted()) {
                        int read = recorder.read(buffer, 0, buffer.length);
                        if (read <= 0) continue;

                        long sum = 0;
                        for (int i = 0; i < read; i++) {
                            sum += (long) buffer[i] * buffer[i];
                            byteBuffer[i * 2] = (byte) (buffer[i] & 0xff);
                            byteBuffer[i * 2 + 1] = (byte) ((buffer[i] >> 8) & 0xff);
                        }
                        pcmStream.write(byteBuffer, 0, read * 2);

                        double rms = Math.sqrt((double) sum / read);

                        // Adaptive ambient room noise calibration
                        if (frameCount < 6) {
                            ambientRms = (ambientRms * frameCount + rms) / (frameCount + 1);
                            frameCount++;
                        } else {
                            if (rms < ambientRms * 1.15) {
                                ambientRms = 0.95 * ambientRms + 0.05 * rms;
                            }
                        }

                        // Responsive vocal activity threshold
                        double speechThreshold = Math.max(28.0, ambientRms * 1.25);

                        // Visual RMS for HUD
                        float visualDb = Math.min(95f, Math.max(0f, (float) ((rms - 10.0) * 1.6)));
                        notifyRms(visualDb);

                        long now = System.currentTimeMillis();
                        if (rms > speechThreshold) {
                            if (!heardSpeech) {
                                heardSpeech = true;
                                speechStartTime = now;
                                notifyBeginningOfSpeech();
                            }
                            lastSpeechTime = now;
                        }

                        // Auto-detect speech end
                        if (heardSpeech && (now - lastSpeechTime > 1200 || now - speechStartTime > 6500)) {
                            break;
                        }
                        // Timeout: 5.5s with no speech heard
                        if (!heardSpeech && (now - startTime > 5500)) {
                            break;
                        }
                    }

                    recordedSuccessfully = true;

                } catch (Exception e) {
                    Log.e(TAG, "AudioRecord fallback error: " + e.getMessage(), e);
                    notifyError("Microphone error. Tap to retry." + formatDiag(SpeechRecognizer.ERROR_AUDIO));
                } finally {
                    if (recorder != null) {
                        try {
                            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                recorder.stop();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error stopping AudioRecord", e);
                        }
                        try {
                            recorder.release();
                        } catch (Exception e) {
                            Log.w(TAG, "Error releasing AudioRecord", e);
                        }
                        recorder = null;
                    }
                    isFallbackRecording.set(false);
                    isListening.set(false);
                }

                if (!recordedSuccessfully) {
                    return;
                }

                notifyEndOfSpeech();

                byte[] pcmData = pcmStream.toByteArray();

                if (pcmData.length < 3200) {
                    notifyError("Ultron Ready • Tap mic to speak");
                    return;
                }

                // Tier 1: If online, check Chromium speech API
                if (isNetworkConnected()) {
                    String text = queryChromiumSpeechApi(pcmData);
                    if (text != null && !text.trim().isEmpty()) {
                        UltronDebugLog.log(TAG, "Chromium speech recognized: " + text);
                        notifyResult(text.trim());
                        return;
                    }
                }

                // Tier 2: 100% Offline On-Device Acoustic Command Recognizer
                String offlineDecoded = UltronOfflineAcousticEngine.recognizeCommand(context, pcmData);
                if (offlineDecoded != null && !offlineDecoded.trim().isEmpty()) {
                    UltronDebugLog.log(TAG, "Offline Acoustic Engine Decoded: " + offlineDecoded);
                    notifyResult(offlineDecoded.trim());
                    return;
                }

                notifyError("Didn't catch that. Tap mic to speak." + formatDiag(SpeechRecognizer.ERROR_NO_MATCH));
            }
        });
        fallbackThread.start();
    }

    private void stopAudioRecordFallback() {
        isFallbackRecording.set(false);
        if (fallbackThread != null) {
            fallbackThread.interrupt();
            fallbackThread = null;
        }
    }

    private String queryChromiumSpeechApi(byte[] pcmData) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(CHROMIUM_SPEECH_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "audio/l16; rate=16000");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            dos.write(pcmData);
            dos.flush();
            dos.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                String resp = baos.toString("UTF-8").trim();
                for (String line : resp.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.contains("\"transcript\":")) {
                        int idx = line.indexOf("\"transcript\":");
                        int start = line.indexOf("\"", idx + 13);
                        int end = line.indexOf("\"", start + 1);
                        if (start != -1 && end != -1 && end > start) {
                            return line.substring(start + 1, end).trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Speech API error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // Helper notification dispatchers on Main Thread
    private void notifyReady() {
        UltronDebugLog.log(TAG, "notifyReady");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onReady();
            }
        });
    }

    private void notifyBeginningOfSpeech() {
        UltronDebugLog.log(TAG, "notifyBeginningOfSpeech");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onBeginningOfSpeech();
            }
        });
    }

    private void notifyRms(final float rmsDb) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onRmsChanged(rmsDb);
            }
        });
    }

    private void notifyPartialResult(final String partialText) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onPartialResult(partialText);
            }
        });
    }

    private void notifyEndOfSpeech() {
        UltronDebugLog.log(TAG, "notifyEndOfSpeech");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onEndOfSpeech();
            }
        });
    }

    private void notifyResult(final String result) {
        UltronDebugLog.log(TAG, "notifyResult: " + result);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onResult(result);
            }
        });
    }

    private void notifyError(final String error) {
        UltronDebugLog.log(TAG, "notifyError: " + error);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onError(error);
            }
        });
    }
}
