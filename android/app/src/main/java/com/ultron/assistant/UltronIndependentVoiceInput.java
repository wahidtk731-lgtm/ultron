package com.ultron.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-App Speech Recognition Engine for Ultron Assistant.
 * Uses an internal background SpeechRecognizer and RecognitionListener
 * so speech recognition stays entirely inside the app with zero Google popup overlays.
 */
public class UltronIndependentVoiceInput {

    private static final String TAG = "UltronVoiceInput";

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
    private Intent recognizerIntent;
    private final AtomicBoolean isListening = new AtomicBoolean(false);

    public UltronIndependentVoiceInput(Context context, VoiceInputListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(VoiceInputListener listener) {
        this.listener = listener;
    }

    public boolean isListening() {
        return isListening.get();
    }

    public String getCurrentEngine() {
        return "in_app_recognizer";
    }

    public void setEngine(String engine) {
        // Standard in-app speech recognizer
    }

    /**
     * Initializes the background listener and starts listening in-app.
     * Guaranteed: ZERO startActivityForResult or external Google dialog popups.
     */
    public void startListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                // 1. Check microphone permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        notifyError("Microphone permission required. Tap to grant.");
                        return;
                    }
                }

                // 2. Stop any existing session
                safeCancelAndDestroyRecognizer();

                // 3. Verify recognition service availability on device
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "SpeechRecognizer.isRecognitionAvailable returned false");
                    notifyError("Speech recognition not available on device.");
                    return;
                }

                try {
                    // 4. Initialize internal SpeechRecognizer instance
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                    if (speechRecognizer == null) {
                        notifyError("Could not initialize speech recognizer.");
                        return;
                    }

                    // 5. Build in-app recognition intent
                    recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                    recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
                    recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                    recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                    }

                    // 6. Set internal background RecognitionListener
                    speechRecognizer.setRecognitionListener(new RecognitionListener() {
                        @Override
                        public void onReadyForSpeech(Bundle params) {
                            isListening.set(true);
                            notifyReady();
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            notifyBeginningOfSpeech();
                        }

                        @Override
                        public void onRmsChanged(float rmsdB) {
                            // Normalize Android SpeechRecognizer RMS (-2dB to 12dB) into 0-95dB for HUD waveform
                            float normalizedDb;
                            if (rmsdB < 0f) {
                                normalizedDb = (rmsdB + 2.0f) * 15.0f;
                            } else {
                                normalizedDb = Math.min(95.0f, rmsdB * 7.5f);
                            }
                            if (normalizedDb < 0f) normalizedDb = 0f;
                            notifyRms(normalizedDb);
                        }

                        @Override
                        public void onBufferReceived(byte[] buffer) {
                            // Audio buffer stream
                        }

                        @Override
                        public void onEndOfSpeech() {
                            isListening.set(false);
                            notifyEndOfSpeech();
                        }

                        @Override
                        public void onError(int error) {
                            isListening.set(false);
                            handleRecognizerError(error);
                        }

                        @Override
                        public void onResults(Bundle results) {
                            isListening.set(false);
                            ArrayList<String> matches = results != null ?
                                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                            if (matches != null && !matches.isEmpty()) {
                                String text = matches.get(0).trim();
                                if (!text.isEmpty()) {
                                    Log.i(TAG, "In-app Speech Recognized: " + text);
                                    notifyResult(text);
                                    return;
                                }
                            }
                            notifyError("Didn't catch that. Tap mic to retry.");
                        }

                        @Override
                        public void onPartialResults(Bundle partialResults) {
                            ArrayList<String> partial = partialResults != null ?
                                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                            if (partial != null && !partial.isEmpty()) {
                                String text = partial.get(0).trim();
                                if (!text.isEmpty()) {
                                    notifyPartialResult(text);
                                }
                            }
                        }

                        @Override
                        public void onEvent(int eventType, Bundle params) {}
                    });

                    // 7. Directly start listening on internal recognizer
                    speechRecognizer.startListening(recognizerIntent);
                    isListening.set(true);
                    notifyReady();

                } catch (Exception e) {
                    Log.e(TAG, "Failed to start in-app SpeechRecognizer: " + e.getMessage(), e);
                    isListening.set(false);
                    safeCancelAndDestroyRecognizer();
                    notifyError("Microphone error. Tap to retry.");
                }
            }
        });
    }

    public void stopListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                isListening.set(false);
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer.stopListening();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    public void destroy() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                isListening.set(false);
                safeCancelAndDestroyRecognizer();
                listener = null;
            }
        });
    }

    private void safeCancelAndDestroyRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    private void handleRecognizerError(int error) {
        safeCancelAndDestroyRecognizer();

        String message;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "Didn't hear that. Tap mic to speak.";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "Listening timed out. Tap mic to speak.";
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error. Tap mic to retry.";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Microphone permission required.";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout. Tap mic to retry.";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "Speech engine busy. Tap to retry.";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
            default:
                message = "Tap mic to speak.";
                break;
        }

        notifyError(message);
    }

    // Listener notification helper methods
    private void notifyReady() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onReady();
            }
        });
    }

    private void notifyBeginningOfSpeech() {
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
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onEndOfSpeech();
            }
        });
    }

    private void notifyResult(final String result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onResult(result);
            }
        });
    }

    private void notifyError(final String error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onError(error);
            }
        });
    }
}
