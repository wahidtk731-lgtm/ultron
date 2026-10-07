package com.ultron.assistant;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Native Android Offline Text-To-Speech Manager for Ultron.
 * Locks input while speaking to prevent mic loopback / self-triggering.
 */
public class TTSManager {

    private static final String TAG = "UltronTTS";
    private TextToSpeech tts;
    private boolean isReady = false;
    private final TTSListener listener;

    public interface TTSListener {
        void onSpeechStarted();
        void onSpeechCompleted();
    }

    public TTSManager(Context context, TTSListener listener) {
        this.listener = listener;
        this.tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(Locale.US);
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    res = tts.setLanguage(Locale.getDefault());
                }
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isReady = true;
                    tts.setPitch(0.95f);      // Confident natural tone
                    tts.setSpeechRate(1.05f);  // Crisp pacing
                    setupListener();
                }
            } else {
                Log.e(TAG, "TTS Initialization failed: " + status);
            }
        });
    }

    private void setupListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                if (listener != null) listener.onSpeechStarted();
            }

            @Override
            public void onDone(String utteranceId) {
                if (listener != null) listener.onSpeechCompleted();
            }

            @Override
            public void onError(String utteranceId) {
                if (listener != null) listener.onSpeechCompleted();
            }
        });
    }

    public void speak(String text) {
        if (!isReady || tts == null || text == null || text.trim().isEmpty()) {
            if (listener != null) listener.onSpeechCompleted();
            return;
        }

        String utteranceId = "ultron_" + System.currentTimeMillis();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    public void setPitch(float pitch) {
        if (tts != null && isReady) {
            tts.setPitch(pitch);
        }
    }

    public void setSpeechRate(float rate) {
        if (tts != null && isReady) {
            tts.setSpeechRate(rate);
        }
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
