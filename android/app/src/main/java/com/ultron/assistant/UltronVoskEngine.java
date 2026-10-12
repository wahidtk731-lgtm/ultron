package com.ultron.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 100% Offline On-Device Speech Recognition Engine using Kaldi Vosk.
 * Runs completely locally on the device CPU without any network connectivity.
 */
public class UltronVoskEngine {

    private static final String TAG = "UltronVoskEngine";
    private static final float SAMPLE_RATE = 16000.0f;
    private static final long SILENCE_TIMEOUT_MS = 8000; // 8 seconds silence watchdog

    public interface Listener {
        void onReady();
        void onPartialResult(String partialText);
        void onResult(String finalText);
        void onError(int errorCode, String errorMessage);
    }

    private static UltronVoskEngine sInstance;

    // Cached Vosk Model
    private static volatile org.vosk.Model sCachedModel = null;
    private static volatile boolean sModelLoading = false;
    private static volatile boolean sModelLoadFailed = false;
    private static volatile String sModelLoadError = null;

    private final Context appContext;
    private final Handler mainHandler;

    private org.vosk.Recognizer currentRecognizer;
    private org.vosk.android.SpeechService currentSpeechService;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private Runnable silenceWatchdog;
    private Listener currentListener;

    private UltronVoskEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized UltronVoskEngine get(Context context) {
        if (sInstance == null) {
            sInstance = new UltronVoskEngine(context);
        }
        return sInstance;
    }

    public static boolean isModelLoaded() {
        return sCachedModel != null;
    }

    public static boolean isReady(Context context) {
        if (sCachedModel != null) {
            return true;
        }
        return UltronVoiceSetup.get(context).isReady() && !sModelLoadFailed;
    }

    public static String getModelLoadError() {
        return sModelLoadError;
    }

    /**
     * Preloads the Vosk model on a background worker thread.
     */
    public static synchronized void preloadModel(final Context context) {
        if (sCachedModel != null || sModelLoading) {
            return;
        }
        final File modelDir = UltronVoiceSetup.get(context).getModelDir();
        if (!modelDir.exists() || !UltronVoiceSetup.get(context).isReady()) {
            return;
        }

        sModelLoading = true;
        sModelLoadFailed = false;
        sModelLoadError = null;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    UltronDebugLog.log(TAG, "Loading Vosk Model from " + modelDir.getAbsolutePath());
                    org.vosk.Model model = new org.vosk.Model(modelDir.getAbsolutePath());
                    sCachedModel = model;
                    sModelLoadFailed = false;
                    UltronDebugLog.log(TAG, "Vosk Model loaded successfully into memory");
                } catch (Throwable t) {
                    sModelLoadFailed = true;
                    sModelLoadError = t.getMessage();
                    UltronDebugLog.log(TAG, "Vosk Model load failed: " + t.getMessage());
                    Log.e(TAG, "Failed to load Vosk model", t);
                } finally {
                    sModelLoading = false;
                }
            }
        }, "VoskModelPreloader").start();
    }

    public boolean isListening() {
        return isListening.get();
    }

    /**
     * Starts listening for speech using the offline Vosk engine.
     */
    public synchronized void startListening(final Listener listener) {
        stopListening();

        this.currentListener = listener;

        if (sCachedModel == null) {
            // Attempt synchronous load if ready
            File modelDir = UltronVoiceSetup.get(appContext).getModelDir();
            if (modelDir.exists()) {
                try {
                    UltronDebugLog.log(TAG, "Synchronously loading Vosk Model from " + modelDir.getAbsolutePath());
                    sCachedModel = new org.vosk.Model(modelDir.getAbsolutePath());
                    sModelLoadFailed = false;
                } catch (Throwable t) {
                    sModelLoadFailed = true;
                    sModelLoadError = t.getMessage();
                    UltronDebugLog.log(TAG, "Synchronous Vosk Model load failed: " + t.getMessage());
                    dispatchError(SpeechRecognizer.ERROR_SERVER, "Offline speech model failed to load: " + t.getMessage());
                    return;
                }
            } else {
                dispatchError(SpeechRecognizer.ERROR_SERVER, "Offline speech model not installed");
                return;
            }
        }

        try {
            currentRecognizer = new org.vosk.Recognizer(sCachedModel, SAMPLE_RATE);
            currentSpeechService = new org.vosk.android.SpeechService(currentRecognizer, SAMPLE_RATE);

            isListening.set(true);

            boolean started = currentSpeechService.startListening(new org.vosk.android.RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    if (!isListening.get()) return;
                    resetSilenceWatchdog();
                    String partial = parseJsonField(hypothesis, "partial");
                    if (!partial.isEmpty()) {
                        dispatchPartialResult(partial);
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    if (!isListening.get()) return;
                    String text = parseJsonField(hypothesis, "text");
                    if (!text.isEmpty()) {
                        stopListening();
                        dispatchResult(text);
                    }
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    if (!isListening.get()) return;
                    String text = parseJsonField(hypothesis, "text");
                    stopListening();
                    if (!text.isEmpty()) {
                        dispatchResult(text);
                    } else {
                        dispatchError(SpeechRecognizer.ERROR_NO_MATCH, "Didn't catch that. Tap mic to speak.");
                    }
                }

                @Override
                public void onError(Exception exception) {
                    if (!isListening.get()) return;
                    UltronDebugLog.log(TAG, "Vosk SpeechService error: " + exception.getMessage());
                    stopListening();
                    dispatchError(SpeechRecognizer.ERROR_AUDIO, "Audio recording error: " + exception.getMessage());
                }

                @Override
                public void onTimeout() {
                    if (!isListening.get()) return;
                    UltronDebugLog.log(TAG, "Vosk SpeechService timeout");
                    stopListening();
                    dispatchError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "I didn't hear anything. Tap the mic and speak.");
                }
            });

            if (!started) {
                isListening.set(false);
                dispatchError(SpeechRecognizer.ERROR_AUDIO, "Could not start audio recording");
                return;
            }

            UltronDebugLog.log(TAG, "Vosk SpeechService started listening");
            dispatchReady();
            resetSilenceWatchdog();

        } catch (Throwable t) {
            UltronDebugLog.log(TAG, "startListening exception: " + t.getMessage());
            stopListening();
            dispatchError(SpeechRecognizer.ERROR_AUDIO, "Failed to initialize Vosk: " + t.getMessage());
        }
    }

    public synchronized void stopListening() {
        if (silenceWatchdog != null) {
            mainHandler.removeCallbacks(silenceWatchdog);
            silenceWatchdog = null;
        }

        isListening.set(false);

        if (currentSpeechService != null) {
            try {
                currentSpeechService.stop();
                currentSpeechService.shutdown();
            } catch (Throwable t) {
                Log.w(TAG, "Error stopping SpeechService", t);
            }
            currentSpeechService = null;
        }

        if (currentRecognizer != null) {
            try {
                currentRecognizer.close();
            } catch (Throwable t) {
                Log.w(TAG, "Error closing Recognizer", t);
            }
            currentRecognizer = null;
        }
    }

    private void resetSilenceWatchdog() {
        if (silenceWatchdog != null) {
            mainHandler.removeCallbacks(silenceWatchdog);
        }
        silenceWatchdog = new Runnable() {
            @Override
            public void run() {
                if (isListening.get()) {
                    UltronDebugLog.log(TAG, "Silence watchdog triggered (" + (SILENCE_TIMEOUT_MS / 1000) + "s)");
                    stopListening();
                    dispatchError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "I didn't hear anything. Tap the mic and speak.");
                }
            }
        };
        mainHandler.postDelayed(silenceWatchdog, SILENCE_TIMEOUT_MS);
    }

    private void dispatchReady() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentListener != null) {
                    currentListener.onReady();
                }
            }
        });
    }

    private void dispatchPartialResult(final String partial) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentListener != null) {
                    currentListener.onPartialResult(partial);
                }
            }
        });
    }

    private void dispatchResult(final String result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentListener != null) {
                    currentListener.onResult(result);
                }
            }
        });
    }

    private void dispatchError(final int errorCode, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (currentListener != null) {
                    currentListener.onError(errorCode, message);
                }
            }
        });
    }

    private static String parseJsonField(String jsonStr, String key) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject obj = new JSONObject(jsonStr);
            return obj.optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
