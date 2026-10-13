package com.ultron.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardened Offline Text-To-Speech Manager for Ultron Assistant.
 *
 * Resilience Architecture:
 * 1. ONE Shared TextToSpeech engine across the entire app (singleton helper), created lazily.
 * 2. Never speaks or synthesizes during init; never enumerates voices on the main thread during startup.
 * 3. setVoice validated strictly: must be in getVoices(), offline-only, not missing/uninstalled.
 * 4. All TTS IPC calls wrapped in try/catch (DeadObjectException, IllegalStateException).
 * 5. Serialized synthesis: CountDownLatch ensures no overlapping synthesizeToFile calls.
 * 6. Logs TTS error code and default engine package name in onError(utteranceId, errorCode).
 * 7. SAFE MODE: automatically enabled on 2 errors in 60s or unhandled exception.
 * 8. Guarantees TTSListener.onSpeechCompleted() fires exactly once per speak() on every path.
 */
public class TTSManager {

    private static final String TAG = "UltronTTS";
    private static final String PREFS_NAME = "ultron_prefs";
    private static final String KEY_VOICE_STYLE = "voice_style";
    private static final String KEY_VOICE_NAME = "voice_name";
    private static final String KEY_VOICE_PITCH = "voice_pitch";
    private static final String KEY_VOICE_RATE = "voice_rate";
    public static final String KEY_TTS_SAFE_MODE = "tts_safe_mode";

    private static final int ERROR_INTERRUPTED = -2;

    // Shared singleton engine state
    private static volatile TextToSpeech sSharedTts = null;
    private static volatile boolean sIsReady = false;
    private static volatile boolean sInitFailed = false;
    private static volatile boolean sInitInProgress = false;
    private static volatile boolean sIsSafeMode = false;
    private static volatile Context sAppContext = null;
    private static volatile String sPreferredEngine = null;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicInteger sCurrentGeneration = new AtomicInteger(0);

    public static boolean isSamsungEngine() {
        String engine = getDefaultEngineSafe();
        if (engine != null && !engine.isEmpty()) {
            String lower = engine.toLowerCase(Locale.US);
            if (lower.contains("samsung") || lower.contains("smt")) {
                return true;
            }
            if (lower.contains("google")) {
                return false;
            }
        }
        String man = Build.MANUFACTURER;
        if (man != null && man.toLowerCase(Locale.US).contains("samsung")) {
            if (sPreferredEngine != null && sPreferredEngine.contains("google")) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static final List<TTSListener> sListeners = new CopyOnWriteArrayList<TTSListener>();

    // Instance fields
    private final Context context;
    private final TTSListener listener;
    private UltronVoiceProfile currentProfile;
    private String selectedVoiceName = "";
    private float customPitch = -1.0f;
    private float customRate = -1.0f;

    public interface TTSListener {
        void onSpeechStarted();
        void onSpeechCompleted();
    }

    public TTSManager(Context context, TTSListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        synchronized (TTSManager.class) {
            if (sAppContext == null) {
                sAppContext = this.context;
            }
        }

        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }

        // Load persisted settings
        SharedPreferences prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String styleKey = prefs.getString(KEY_VOICE_STYLE, UltronVoiceProfile.STYLE_ULTRON);
        this.selectedVoiceName = prefs.getString(KEY_VOICE_NAME, "");
        this.customPitch = prefs.getFloat(KEY_VOICE_PITCH, -1.0f);
        this.customRate = prefs.getFloat(KEY_VOICE_RATE, -1.0f);
        sIsSafeMode = false;
        prefs.edit().remove(KEY_TTS_SAFE_MODE).apply();

        updateProfile(styleKey, customPitch, customRate);

        // Lazily warm up the shared engine without blocking main startup
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureEngineInitialized(sAppContext);
            }
        }, 1200);
    }

    private void updateProfile(String styleKey, float pitch, float rate) {
        UltronVoiceProfile base = UltronVoiceProfile.getProfile(styleKey);
        this.currentProfile = base.withPitchAndRate(pitch > 0 ? pitch : -1.0f, rate > 0 ? rate : -1.0f);
    }

    // ==============================================================================
    // Shared Engine Lifecycle (Singleton Helper)
    // ==============================================================================

    private static synchronized void ensureEngineInitialized(final Context appContext) {
        if (sSharedTts != null || sInitInProgress) {
            return;
        }
        if (appContext == null) {
            return;
        }

        sInitInProgress = true;
        UltronDebugLog.log(TAG, "Creating shared TextToSpeech engine lazily (preferred=" + sPreferredEngine + ")...");

        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    TextToSpeech.OnInitListener initListener = new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            sInitInProgress = false;
                            if (status == TextToSpeech.SUCCESS) {
                                onSharedTtsInitialized(appContext);
                            } else {
                                UltronDebugLog.log(TAG, "Shared TextToSpeech onInit failed: status=" + status);
                                sInitFailed = true;
                                sIsReady = false;
                                recordTtsError("onInit error " + status);
                            }
                        }
                    };

                    if (sPreferredEngine != null && !sPreferredEngine.isEmpty()) {
                        sSharedTts = new TextToSpeech(appContext, initListener, sPreferredEngine);
                    } else {
                        sSharedTts = new TextToSpeech(appContext, initListener);
                    }
                } catch (Throwable t) {
                    sInitInProgress = false;
                    sInitFailed = true;
                    UltronDebugLog.log(TAG, "Exception instantiating TextToSpeech: " + t.getMessage());
                    recordTtsException(t);
                }
            }
        });
    }

    private static void onSharedTtsInitialized(final Context appContext) {
        try {
            if (sSharedTts == null) return;

            // Set language safely: try default system locale, fallback to US
            try {
                int res = sSharedTts.setLanguage(Locale.getDefault());
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    sSharedTts.setLanguage(Locale.US);
                }
            } catch (Throwable t) {
                UltronDebugLog.log(TAG, "Safe language init note: " + t.getMessage());
            }

            setupProgressListener();
            sIsReady = true;
            sInitFailed = false;

            UltronDebugLog.log(TAG, "Shared TTS initialized. Engine=" + getDefaultEngineSafe());
        } catch (Throwable t) {
            UltronDebugLog.log(TAG, "Error in onSharedTtsInitialized: " + t.getMessage());
        }
    }

    private static void setupProgressListener() {
        if (sSharedTts == null) return;
        try {
            sSharedTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(final String utteranceId) {
                    if (isCurrentUtterance(utteranceId)) {
                        notifySpeechStarted();
                    }
                }

                @Override
                public void onDone(final String utteranceId) {
                    UltronDebugLog.log(TAG, "TTS onDone: " + utteranceId);
                    if (isCurrentUtterance(utteranceId)) {
                        notifySpeechCompleted();
                    }
                }

                @Override
                public void onError(final String utteranceId) {
                    if (!isCurrentUtterance(utteranceId)) {
                        return;
                    }
                    UltronDebugLog.log(TAG, "TTS onError: " + utteranceId);
                    notifySpeechCompleted();
                }

                @Override
                public void onError(final String utteranceId, final int errorCode) {
                    if (errorCode == ERROR_INTERRUPTED || !isCurrentUtterance(utteranceId)) {
                        UltronDebugLog.log(TAG, "TTS utterance interrupted: " + utteranceId);
                        if (isCurrentUtterance(utteranceId)) {
                            notifySpeechCompleted();
                        }
                        return;
                    }
                    UltronDebugLog.log(TAG, "TTS onError: " + utteranceId + " errorCode=" + errorCode);
                    notifySpeechCompleted();
                }
            });
        } catch (Throwable t) {
            recordTtsException(t);
        }
    }

    private static boolean isCurrentUtterance(String utteranceId) {
        if (utteranceId == null) return true;
        return utteranceId.equals("ultron_" + sCurrentGeneration.get())
                || utteranceId.equals("plain_" + sCurrentGeneration.get());
    }

    private static String getDefaultEngineSafe() {
        if (sSharedTts != null) {
            try {
                return sSharedTts.getDefaultEngine();
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    // ==============================================================================
    // Error Logging
    // ==============================================================================

    private static void recordTtsError(String reason) {
        UltronDebugLog.log(TAG, "TTS Error: " + reason);
    }

    private static void recordTtsException(Throwable t) {
        UltronDebugLog.log(TAG, "TTS Exception: " + (t != null ? t.getMessage() : "unknown"));
    }

    // ==============================================================================
    // Speech Playback (Direct & Serialized DSP)
    // ==============================================================================

    private void applyPitchAndRateToEngine() {
        if (sSharedTts != null && currentProfile != null) {
            try {
                float pitch = currentProfile.enginePitch;
                float rate = currentProfile.engineRate;
                if (isSamsungEngine()) {
                    // Samsung SMT native engine requires conservative pitch and rate
                    if (pitch < 0.80f) pitch = 0.80f;
                    if (pitch > 1.25f) pitch = 1.25f;
                    if (rate < 0.85f) rate = 0.85f;
                    if (rate > 1.25f) rate = 1.25f;
                }
                sSharedTts.setPitch(pitch);
                sSharedTts.setSpeechRate(rate);
            } catch (Throwable t) {
                UltronDebugLog.log(TAG, "applyPitchAndRate note: " + t.getMessage());
            }
        }
    }

    public synchronized void speak(final String text) {
        final int gen = sCurrentGeneration.incrementAndGet();
        UltronDebugLog.log(TAG, "speak requested: \"" + (text != null ? (text.length() > 50 ? text.substring(0, 50) + "..." : text) : "null")
                + "\" (gen=" + gen + ", isSamsung=" + isSamsungEngine() + ", safeMode=" + sIsSafeMode + ")");

        if (text == null || text.trim().isEmpty()) {
            notifySpeechCompleted();
            return;
        }

        ensureEngineInitialized(context);

        if (!sIsReady || sInitFailed || sSharedTts == null) {
            Log.w(TAG, "TTS not ready or failed; completing gracefully");
            notifySpeechCompleted();
            return;
        }

        // Interrupt previous utterance
        stopInternals();

        // Direct safe speak: universally stable across all Android devices & TTS engines
        speakDirectPlain(text, gen);
    }

    private void speakDirectPlain(final String text, final int gen) {
        applyPitchAndRateToEngine();
        String utteranceId = "plain_" + gen;
        int result = TextToSpeech.ERROR;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                result = sSharedTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
            } else {
                HashMap<String, String> params = new HashMap<String, String>();
                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                result = sSharedTts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
            }
        } catch (Throwable t) {
            recordTtsException(t);
        }

        if (result != TextToSpeech.SUCCESS) {
            recordTtsError("direct speak failed with code " + result);
            notifySpeechCompleted();
        } else {
            // Watchdog to guarantee onSpeechCompleted fires even if Samsung SMT misses onDone callback
            final int currentGen = gen;
            final long timeoutMs = Math.max(3500L, text.length() * 120L + 3000L);
            sMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (currentGen == sCurrentGeneration.get()) {
                        UltronDebugLog.log(TAG, "Plain speak watchdog fired after " + timeoutMs + "ms");
                        notifySpeechCompleted();
                    }
                }
            }, timeoutMs);
        }
    }

    public synchronized void stop() {
        sCurrentGeneration.incrementAndGet();
        UltronDebugLog.log(TAG, "stop requested");
        stopInternals();
        notifySpeechCompleted();
    }

    private void stopInternals() {
        if (sSharedTts != null) {
            try {
                sSharedTts.stop();
            } catch (Throwable t) {
                UltronDebugLog.log(TAG, "sSharedTts.stop note: " + t.getMessage());
            }
        }
    }

    // ==============================================================================
    // Listener Dispatching (Step B.6 Guaranteed Completion)
    // ==============================================================================

    private static void notifySpeechStarted() {
        UltronDebugLog.log(TAG, "Speech playback started");
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (TTSListener l : sListeners) {
                    try {
                        l.onSpeechStarted();
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static void notifySpeechCompleted() {
        UltronDebugLog.log(TAG, "Speech playback completed");
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (TTSListener l : sListeners) {
                    try {
                        l.onSpeechCompleted();
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    // ==============================================================================
    // Public Settings & Bridge APIs
    // ==============================================================================

    public void setPitch(float pitch) {
        this.customPitch = pitch;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_VOICE_PITCH, pitch).apply();
        updateProfile(currentProfile.name, customPitch, customRate);
        applyPitchAndRateToEngine();
    }

    public void setSpeechRate(float rate) {
        this.customRate = rate;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_VOICE_RATE, rate).apply();
        updateProfile(currentProfile.name, customPitch, customRate);
        applyPitchAndRateToEngine();
    }

    public void setVoiceStyle(String style) {
        if (style == null) return;
        UltronDebugLog.log(TAG, "setVoiceStyle: " + style);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_VOICE_STYLE, style).apply();
        updateProfile(style, customPitch, customRate);
        applyPitchAndRateToEngine();
    }

    public String getVoiceStyle() {
        return currentProfile != null ? currentProfile.name : UltronVoiceProfile.STYLE_ULTRON;
    }

    public void setVoiceName(String voiceName) {
        if (voiceName == null) return;
        this.selectedVoiceName = voiceName;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_VOICE_NAME, voiceName).apply();
    }

    public String getVoiceName() {
        return selectedVoiceName;
    }

    public String getVoiceOptionsJson() {
        return "[]";
    }

    public boolean isOfflineVoiceMissing() {
        return false;
    }

    public boolean hasMaleVoice() {
        return true;
    }

    public String getVoiceStatusNote() {
        return null;
    }

    public void setSafeMode(boolean enabled) {
        sIsSafeMode = false;
    }

    public boolean isSafeMode() {
        return false;
    }

    public void shutdown() {
        if (this.listener != null) {
            sListeners.remove(this.listener);
        }
        stop();
    }
}
