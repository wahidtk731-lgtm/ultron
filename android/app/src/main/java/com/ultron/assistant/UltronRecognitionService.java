package com.ultron.assistant;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import java.util.ArrayList;

/**
 * Native Android RecognitionService for Ultron Assistant.
 * Exposes Ultron directly in System Settings -> Languages & Input -> Voice Input.
 * Delegates cleanly to underlying system speech engine so Ultron functions seamlessly
 * across all Android apps.
 */
public class UltronRecognitionService extends RecognitionService {

    private static final String TAG = "UltronRecService";
    private Handler mainHandler;
    private SpeechRecognizer delegateRecognizer;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onStartListening(final Intent recognizerIntent, final Callback listener) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    stopDelegate();

                    ComponentName sysComp = UltronIndependentVoiceInput.findSystemRecognitionService(UltronRecognitionService.this);
                    if (sysComp != null) {
                        delegateRecognizer = SpeechRecognizer.createSpeechRecognizer(UltronRecognitionService.this, sysComp);
                    } else {
                        delegateRecognizer = SpeechRecognizer.createSpeechRecognizer(UltronRecognitionService.this);
                    }

                    delegateRecognizer.setRecognitionListener(new RecognitionListener() {
                        @Override
                        public void onReadyForSpeech(Bundle params) {
                            try { if (listener != null) listener.readyForSpeech(params); } catch (Exception ignored) {}
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            try { if (listener != null) listener.beginningOfSpeech(); } catch (Exception ignored) {}
                        }

                        @Override
                        public void onRmsChanged(float rmsdB) {
                            try { if (listener != null) listener.rmsChanged(rmsdB); } catch (Exception ignored) {}
                        }

                        @Override
                        public void onBufferReceived(byte[] buffer) {
                            try { if (listener != null) listener.bufferReceived(buffer); } catch (Exception ignored) {}
                        }

                        @Override
                        public void onEndOfSpeech() {
                            try { if (listener != null) listener.endOfSpeech(); } catch (Exception ignored) {}
                        }

                        @Override
                        public void onError(int error) {
                            try { if (listener != null) listener.error(error); } catch (Exception ignored) {}
                            stopDelegate();
                        }

                        @Override
                        public void onResults(Bundle results) {
                            try { if (listener != null) listener.results(results); } catch (Exception ignored) {}
                            stopDelegate();
                        }

                        @Override
                        public void onPartialResults(Bundle partialResults) {
                            try { if (listener != null) listener.partialResults(partialResults); } catch (Exception ignored) {}
                        }

                        @Override
                        public void onEvent(int eventType, Bundle params) {}
                    });

                    Intent offlineIntent = UltronIndependentVoiceInput.buildOfflineRecognizerIntent(UltronRecognitionService.this);
                    if (recognizerIntent != null && recognizerIntent.getExtras() != null) {
                        offlineIntent.putExtras(recognizerIntent.getExtras());
                    }
                    offlineIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                    offlineIntent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
                    offlineIntent.putExtra("android.speech.extra.DICTATION_MODE", true);

                    delegateRecognizer.startListening(offlineIntent);

                } catch (Exception e) {
                    Log.e(TAG, "Error starting delegated recognition: " + e.getMessage());
                    try {
                        if (listener != null) {
                            listener.error(SpeechRecognizer.ERROR_CLIENT);
                        }
                    } catch (Exception ignored) {}
                    stopDelegate();
                }
            }
        });
    }

    private void stopDelegate() {
        if (delegateRecognizer != null) {
            try {
                delegateRecognizer.stopListening();
                delegateRecognizer.cancel();
                delegateRecognizer.destroy();
            } catch (Exception ignored) {}
            delegateRecognizer = null;
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        if (delegateRecognizer != null) {
            try {
                delegateRecognizer.stopListening();
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onCancel(Callback listener) {
        stopDelegate();
    }

    @Override
    public void onDestroy() {
        stopDelegate();
        super.onDestroy();
    }
}
