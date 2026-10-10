package com.ultron.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * Native Android RecognitionService for Ultron Assistant.
 * Exposes Ultron directly in System Settings -> Languages & Input -> Voice Input.
 */
public class UltronRecognitionService extends RecognitionService {

    private Handler mainHandler;
    private Callback currentCallback;
    private UltronIndependentVoiceInput voiceInput;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, final Callback listener) {
        this.currentCallback = listener;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (listener != null) {
                        listener.readyForSpeech(new Bundle());
                        listener.beginningOfSpeech();
                    }

                    if (voiceInput == null) {
                        voiceInput = new UltronIndependentVoiceInput(UltronRecognitionService.this, new UltronIndependentVoiceInput.VoiceInputListener() {
                            @Override
                            public void onReady() {
                                try {
                                    if (currentCallback != null) {
                                        currentCallback.readyForSpeech(new Bundle());
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onBeginningOfSpeech() {
                                try {
                                    if (currentCallback != null) {
                                        currentCallback.beginningOfSpeech();
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onRmsChanged(float rmsDb) {
                                try {
                                    if (currentCallback != null) {
                                        currentCallback.rmsChanged(rmsDb);
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onPartialResult(String partialText) {
                                try {
                                    if (currentCallback != null) {
                                        Bundle b = new Bundle();
                                        ArrayList<String> list = new ArrayList<String>();
                                        list.add(partialText);
                                        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                                        currentCallback.partialResults(b);
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onEndOfSpeech() {
                                try {
                                    if (currentCallback != null) {
                                        currentCallback.endOfSpeech();
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onResult(String recognizedText) {
                                try {
                                    if (currentCallback != null) {
                                        Bundle b = new Bundle();
                                        ArrayList<String> list = new ArrayList<String>();
                                        list.add(recognizedText);
                                        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
                                        currentCallback.results(b);
                                    }
                                } catch (Exception ignored) {}
                            }

                            @Override
                            public void onError(String errorMessage) {
                                try {
                                    if (currentCallback != null) {
                                        currentCallback.error(SpeechRecognizer.ERROR_NO_MATCH);
                                    }
                                } catch (Exception ignored) {}
                            }
                        });
                    }
                    voiceInput.startListening();
                } catch (Exception e) {
                    try {
                        if (listener != null) {
                            listener.error(SpeechRecognizer.ERROR_CLIENT);
                        }
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    @Override
    protected void onStopListening(Callback listener) {
        if (voiceInput != null) {
            voiceInput.stopListening();
        }
        try {
            if (listener != null) {
                listener.endOfSpeech();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCancel(Callback listener) {
        if (voiceInput != null) {
            voiceInput.stopListening();
        }
        this.currentCallback = null;
    }

    @Override
    public void onDestroy() {
        if (voiceInput != null) {
            voiceInput.stopListening();
            voiceInput = null;
        }
        super.onDestroy();
    }
}
