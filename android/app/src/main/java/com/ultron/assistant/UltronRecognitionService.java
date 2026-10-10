package com.ultron.assistant;

import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean isRecording = false;

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
                    startCapture(listener);
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

    private void startCapture(final Callback listener) {
        stopCapture();
        try {
            int sampleRate = 16000;
            int bufferSize = Math.max(AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (listener != null) listener.error(SpeechRecognizer.ERROR_AUDIO);
                return;
            }
            audioRecord.startRecording();
            isRecording = true;

            recordThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    short[] buffer = new short[1024];
                    long start = System.currentTimeMillis();

                    while (isRecording && !Thread.currentThread().isInterrupted()) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read <= 0) continue;

                        if (System.currentTimeMillis() - start > 3500) {
                            break;
                        }
                    }

                    stopCapture();

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (currentCallback != null) {
                                    currentCallback.endOfSpeech();
                                    Bundle b = new Bundle();
                                    ArrayList<String> results = new ArrayList<String>();
                                    results.add("Ultron Voice Input");
                                    b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results);
                                    currentCallback.results(b);
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                }
            });
            recordThread.start();
        } catch (Exception e) {
            try {
                if (listener != null) listener.error(SpeechRecognizer.ERROR_CLIENT);
            } catch (Exception ignored) {}
        }
    }

    private void stopCapture() {
        isRecording = false;
        if (recordThread != null) {
            recordThread.interrupt();
            recordThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        stopCapture();
        try {
            if (listener != null) listener.endOfSpeech();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCancel(Callback listener) {
        stopCapture();
        this.currentCallback = null;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}
