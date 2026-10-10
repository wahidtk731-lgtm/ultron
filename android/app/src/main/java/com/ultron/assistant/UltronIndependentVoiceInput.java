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
import android.net.NetworkInfo;
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
 * Resilient In-App Speech Recognition Engine for Ultron Assistant.
 * 
 * Features:
 * 1. Background SpeechRecognizer with RecognitionListener (ZERO Google popup dialogs/overlays).
 * 2. Explicit binding to system speech recognition engine to avoid recursive self-binding.
 * 3. Proper BCP-47 String language tagging and silence tolerance thresholds.
 * 4. Resilient fallback to direct AudioRecord mic capture with on-device waveform if SpeechRecognizer is blocked or unavailable.
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
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isFallbackRecording = new AtomicBoolean(false);
    private Thread fallbackThread = null;

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
        return "in_app_recognizer";
    }

    public void setEngine(String engine) {
        // Retained for compatibility with HUD settings
    }

    /**
     * Resolves the device's actual system speech recognition service (Google or OEM)
     * while explicitly avoiding Ultron's own RecognitionService to prevent recursive loops.
     */
    public static ComponentName findSystemRecognitionService(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(RecognitionService.SERVICE_INTERFACE);
            List<ResolveInfo> services = pm.queryIntentServices(intent, 0);
            if (services == null || services.isEmpty()) {
                return null;
            }

            String myPackage = context.getPackageName();
            ResolveInfo googleService = null;
            ResolveInfo otherService = null;

            for (int i = 0; i < services.size(); i++) {
                ResolveInfo info = services.get(i);
                if (info == null || info.serviceInfo == null) continue;
                String pkg = info.serviceInfo.packageName;

                // Never bind Ultron's recognizer to itself!
                if (pkg.equals(myPackage)) {
                    continue;
                }

                if (pkg.toLowerCase(Locale.US).contains("google")) {
                    googleService = info;
                    break;
                }
                if (otherService == null) {
                    otherService = info;
                }
            }

            ResolveInfo chosen = (googleService != null) ? googleService : otherService;
            if (chosen != null && chosen.serviceInfo != null) {
                return new ComponentName(chosen.serviceInfo.packageName, chosen.serviceInfo.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving system speech recognition service: " + e.getMessage());
        }
        return null;
    }

    /**
     * Lazily initializes the internal SpeechRecognizer instance and attaches the listener.
     */
    private synchronized void ensureRecognizer() {
        if (speechRecognizer != null) {
            return;
        }

        try {
            ComponentName comp = findSystemRecognitionService(context);
            if (comp != null) {
                Log.i(TAG, "Binding SpeechRecognizer directly to system engine: " + comp.flattenToShortString());
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context, comp);
            } else {
                Log.i(TAG, "Binding default SpeechRecognizer");
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            }
        } catch (Exception e) {
            Log.w(TAG, "createSpeechRecognizer failed with ComponentName, trying default: " + e.getMessage());
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            } catch (Exception ex) {
                Log.e(TAG, "Failed creating default SpeechRecognizer: " + ex.getMessage());
                speechRecognizer = null;
            }
        }

        if (speechRecognizer != null) {
            speechRecognizer.setRecognitionListener(new InternalRecognitionListener());
        }
    }

    /**
     * Builds standard Android SpeechRecognizer intent without external dialogs.
     */
    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        // EXTRA_LANGUAGE must be a String (BCP-47)
        String lang = Locale.getDefault().toLanguageTag();
        if (lang == null || lang.isEmpty() || "und".equalsIgnoreCase(lang)) {
            lang = "en-US";
        }
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        intent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{lang, "en-US"});

        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        // Generous silence thresholds so microphone does not close immediately
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1800L);

        return intent;
    }

    /**
     * Begins listening for speech strictly in-app.
     */
    public void startListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                // 1. Verify microphone permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        notifyError("Microphone permission required. Tap to grant.");
                        return;
                    }
                }

                // Stop any running fallback recording
                stopAudioRecordFallback();

                try {
                    ensureRecognizer();

                    if (speechRecognizer == null) {
                        Log.w(TAG, "SpeechRecognizer unavailable, activating direct AudioRecord capture");
                        startAudioRecordFallback();
                        return;
                    }

                    // Cancel any previous session to ensure clean state
                    try {
                        speechRecognizer.cancel();
                    } catch (Exception ignored) {}

                    Intent intent = buildRecognizerIntent();
                    speechRecognizer.startListening(intent);
                    isListening.set(true);
                    notifyReady();

                } catch (Exception e) {
                    Log.e(TAG, "Error starting SpeechRecognizer: " + e.getMessage(), e);
                    safeDestroyRecognizer();
                    startAudioRecordFallback();
                }
            }
        });
    }

    /**
     * Stops active speech listening.
     */
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
                isListening.set(false);
                stopAudioRecordFallback();
                safeDestroyRecognizer();
                listener = null;
            }
        });
    }

    private synchronized void safeDestroyRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    /**
     * RecognitionListener that pipes audio events directly into Ultron HUD.
     */
    private class InternalRecognitionListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            Log.i(TAG, "SpeechRecognizer onReadyForSpeech");
            isListening.set(true);
            notifyReady();
        }

        @Override
        public void onBeginningOfSpeech() {
            Log.i(TAG, "SpeechRecognizer onBeginningOfSpeech");
            isListening.set(true);
            notifyBeginningOfSpeech();
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // Normalize Android SpeechRecognizer RMS (-2dB to 12dB) into 0-95dB for HUD waveform
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
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            Log.i(TAG, "SpeechRecognizer onEndOfSpeech");
            isListening.set(false);
            notifyEndOfSpeech();
        }

        @Override
        public void onError(int error) {
            isListening.set(false);
            Log.w(TAG, "SpeechRecognizer onError: " + error);

            // Re-instantiate on connection or busy errors
            if (error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == 9 /* ERROR_INSUFFICIENT_PERMISSIONS or ERROR_SERVER_DISCONNECTED on Android 12 */) {
                safeDestroyRecognizer();
            }

            // If SpeechRecognizer has client/busy issues, transparently trigger AudioRecord fallback
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                Log.i(TAG, "Switching to AudioRecord fallback after SpeechRecognizer error " + error);
                startAudioRecordFallback();
                return;
            }

            handleRecognizerError(error);
        }

        @Override
        public void onResults(Bundle results) {
            isListening.set(false);
            ArrayList<String> matches = (results != null) ?
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
            ArrayList<String> partial = (partialResults != null) ?
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
    }

    private void handleRecognizerError(int error) {
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
                message = "Network error. Check connection or tap mic.";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "Microphone busy. Tap to retry.";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
            default:
                message = "Tap mic to speak.";
                break;
        }

        notifyError(message);
    }

    // =========================================================================
    // Direct AudioRecord Mic Capture Fallback (Pure in-app, 0 dialogs)
    // =========================================================================

    private void startAudioRecordFallback() {
        if (isFallbackRecording.get()) return;

        isFallbackRecording.set(true);
        isListening.set(true);
        notifyReady();

        fallbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int sampleRate = 16000;
                int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(minBuf, 4096);

                AudioRecord recorder = null;
                try {
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                        isFallbackRecording.set(false);
                        isListening.set(false);
                        notifyError("Microphone initialization error.");
                        return;
                    }

                    recorder.startRecording();
                    ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                    short[] buffer = new short[1024];
                    byte[] byteBuffer = new byte[2048];

                    long startTime = System.currentTimeMillis();
                    long speechStartTime = 0;
                    long lastSpeechTime = 0;
                    boolean heardSpeech = false;

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
                        float db = (float) (20.0 * Math.log10(rms + 1e-4));
                        notifyRms(Math.min(95f, Math.max(0f, db)));

                        long now = System.currentTimeMillis();
                        if (rms > 350.0) {
                            if (!heardSpeech) {
                                heardSpeech = true;
                                speechStartTime = now;
                                notifyBeginningOfSpeech();
                            }
                            lastSpeechTime = now;
                        }

                        // Auto-detect speech end: 1.8s of silence after speech, or 5.5s maximum
                        if (heardSpeech && (now - lastSpeechTime > 1800 || now - speechStartTime > 5500)) {
                            break;
                        }
                        if (!heardSpeech && (now - startTime > 4500)) {
                            break;
                        }
                    }

                    recorder.stop();
                    recorder.release();
                    recorder = null;

                    isFallbackRecording.set(false);
                    isListening.set(false);
                    notifyEndOfSpeech();

                    byte[] pcmData = pcmStream.toByteArray();
                    if (!heardSpeech || pcmData.length < 3200) {
                        notifyError("Didn't hear that. Tap mic to speak.");
                        return;
                    }

                    String text = queryChromiumSpeechApi(pcmData);
                    if (text != null && !text.trim().isEmpty()) {
                        notifyResult(text.trim());
                    } else {
                        notifyError("Didn't catch that. Tap mic to retry.");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "AudioRecord fallback error: " + e.getMessage());
                    if (recorder != null) {
                        try { recorder.release(); } catch (Exception ignored) {}
                    }
                    isFallbackRecording.set(false);
                    isListening.set(false);
                    notifyError("Microphone error. Tap to retry.");
                }
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
        String endpoint = "https://www.google.com/speech-api/v2/recognize?client=chromium&lang=en-US&key=AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
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
