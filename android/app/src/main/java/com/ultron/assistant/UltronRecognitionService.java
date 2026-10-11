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
        UltronDebugLog.log(TAG, "onStartListening requested");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    stopDelegate();

                    ComponentName sysComp = UltronIndependentVoiceInput.findSystemRecognitionService(UltronRecognitionService.this);
                    if (sysComp == null) {
                        UltronDebugLog.log(TAG, "No underlying system SpeechRecognizer available to delegate to");
                        Log.e(TAG, "No underlying system SpeechRecognizer available to delegate to; aborting to prevent recursive loop.");
                        if (listener != null) {
                            try { listener.error(SpeechRecognizer.ERROR_CLIENT); } catch (Exception e) { Log.d(TAG, "Listener error notify failed: " + e.getMessage()); }
                        }
                        return;
                    }

                    UltronDebugLog.log(TAG, "Delegating to system recognizer: " + sysComp.flattenToShortString());
                    delegateRecognizer = SpeechRecognizer.createSpeechRecognizer(UltronRecognitionService.this, sysComp);

                    delegateRecognizer.setRecognitionListener(new RecognitionListener() {
                        @Override
                        public void onReadyForSpeech(Bundle params) {
                            UltronDebugLog.log(TAG, "delegate onReadyForSpeech");
                            try { if (listener != null) listener.readyForSpeech(params); } catch (Exception e) { Log.d(TAG, "readyForSpeech dispatch failed: " + e.getMessage()); }
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            UltronDebugLog.log(TAG, "delegate onBeginningOfSpeech");
                            try { if (listener != null) listener.beginningOfSpeech(); } catch (Exception e) { Log.d(TAG, "beginningOfSpeech dispatch failed: " + e.getMessage()); }
                        }

                        @Override
                        public void onRmsChanged(float rmsdB) {
                            try { if (listener != null) listener.rmsChanged(rmsdB); } catch (Exception e) { Log.d(TAG, "rmsChanged dispatch failed: " + e.getMessage()); }
                        }

                        @Override
                        public void onBufferReceived(byte[] buffer) {
                            try { if (listener != null) listener.bufferReceived(buffer); } catch (Exception e) { Log.d(TAG, "bufferReceived dispatch failed: " + e.getMessage()); }
                        }

                        @Override
                        public void onEndOfSpeech() {
                            UltronDebugLog.log(TAG, "delegate onEndOfSpeech");
                            try { if (listener != null) listener.endOfSpeech(); } catch (Exception e) { Log.d(TAG, "endOfSpeech dispatch failed: " + e.getMessage()); }
                        }

                        @Override
                        public void onError(int error) {
                            UltronDebugLog.log(TAG, "delegate onError: " + error);
                            try { if (listener != null) listener.error(error); } catch (Exception e) { Log.d(TAG, "error dispatch failed: " + e.getMessage()); }
                            stopDelegate();
                        }

                        @Override
                        public void onResults(Bundle results) {
                            UltronDebugLog.log(TAG, "delegate onResults");
                            try { if (listener != null) listener.results(results); } catch (Exception e) { Log.d(TAG, "results dispatch failed: " + e.getMessage()); }
                            stopDelegate();
                        }

                        @Override
                        public void onPartialResults(Bundle partialResults) {
                            UltronDebugLog.log(TAG, "delegate onPartialResults");
                            try { if (listener != null) listener.partialResults(partialResults); } catch (Exception e) { Log.d(TAG, "partialResults dispatch failed: " + e.getMessage()); }
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

                    delegateRecognizer.startListening(offlineIntent);

                } catch (Exception e) {
                    Log.e(TAG, "Error starting delegated recognition: " + e.getMessage());
                    try {
                        if (listener != null) {
                            listener.error(SpeechRecognizer.ERROR_CLIENT);
                        }
                    } catch (Exception ex) {
                        Log.d(TAG, "Failed notifying listener error: " + ex.getMessage());
                    }
                    stopDelegate();
                }
            }
        });
    }

    private void stopDelegate() {
        if (delegateRecognizer != null) {
            UltronDebugLog.log(TAG, "stopDelegate destroying recognizer");
            try {
                delegateRecognizer.stopListening();
                delegateRecognizer.cancel();
                delegateRecognizer.destroy();
            } catch (Exception e) {
                Log.d(TAG, "delegateRecognizer destroy: " + e.getMessage());
            }
            delegateRecognizer = null;
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        if (delegateRecognizer != null) {
            try {
                delegateRecognizer.stopListening();
            } catch (Exception e) {
                Log.d(TAG, "delegateRecognizer stopListening: " + e.getMessage());
            }
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
