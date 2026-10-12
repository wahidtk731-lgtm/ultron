package com.ultron.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advanced Offline Text-To-Speech Manager for Ultron.
 * Features:
 * - 100% Offline voice selection (strictly filters out network-dependent voices).
 * - Stylized Ultron audio pipeline (synthesizeToFile -> UltronWav -> UltronVoiceDsp -> AudioTrack).
 * - Automatic fallback to standard TTS if DSP/synthesis encounters issues.
 * - Guarantees TTSListener.onSpeechCompleted() is called exactly once per speak() request.
 */
public class TTSManager {

    private static final String TAG = "UltronTTS";
    private static final String PREFS_NAME = "ultron_prefs";
    private static final String KEY_VOICE_STYLE = "voice_style";
    private static final String KEY_VOICE_NAME = "voice_name";
    private static final String KEY_VOICE_PITCH = "voice_pitch";
    private static final String KEY_VOICE_RATE = "voice_rate";

    private final Context context;
    private final TTSListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService dspExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger currentGeneration = new AtomicInteger(0);

    private TextToSpeech tts;
    private volatile boolean isReady = false;
    private volatile boolean isInitFailed = false;
    private volatile boolean isSpeaking = false;
    private volatile boolean offlineVoiceMissing = false;
    private volatile boolean hasMaleVoice = false;

    private UltronVoiceProfile currentProfile;
    private String selectedVoiceName = "";
    private float customPitch = -1.0f;
    private float customRate = -1.0f;

    // Active AudioTrack and latch tracking for clean cancellation
    private volatile AudioTrack activeAudioTrack = null;
    private volatile CountDownLatch activeSynthLatch = null;
    private volatile File activeTempFile = null;

    public interface TTSListener {
        void onSpeechStarted();
        void onSpeechCompleted();
    }

    public static class OfflineVoiceInfo {
        public final String name;
        public final String locale;
        public final String display;
        public final boolean isMaleHint;

        public OfflineVoiceInfo(String name, String locale, String display, boolean isMaleHint) {
            this.name = name;
            this.locale = locale;
            this.display = display;
            this.isMaleHint = isMaleHint;
        }
    }

    private final List<OfflineVoiceInfo> availableOfflineVoices = new ArrayList<OfflineVoiceInfo>();

    public TTSManager(Context context, TTSListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        // Load persisted settings
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String styleKey = prefs.getString(KEY_VOICE_STYLE, UltronVoiceProfile.STYLE_ULTRON);
        this.selectedVoiceName = prefs.getString(KEY_VOICE_NAME, "");
        this.customPitch = prefs.getFloat(KEY_VOICE_PITCH, -1.0f);
        this.customRate = prefs.getFloat(KEY_VOICE_RATE, -1.0f);

        updateProfile(styleKey, customPitch, customRate);

        initTts();
    }

    private void updateProfile(String styleKey, float pitch, float rate) {
        UltronVoiceProfile base = UltronVoiceProfile.getProfile(styleKey);
        this.currentProfile = base.withPitchAndRate(pitch > 0 ? pitch : -1.0f, rate > 0 ? rate : -1.0f);
    }

    private void initTts() {
        this.tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    onTtsInitialized();
                } else {
                    Log.e(TAG, "TextToSpeech init failed with status: " + status);
                    isInitFailed = true;
                    isReady = false;
                }
            }
        });
    }

    private void onTtsInitialized() {
        try {
            int res = tts.setLanguage(Locale.US);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts.setLanguage(Locale.getDefault());
            }

            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Neither Locale.US nor default locale is supported by TTS");
                offlineVoiceMissing = true;
            } else {
                discoverAndSelectVoice();
            }

            setupProgressListener();
            applyPitchAndRateToEngine();
            isReady = true;
            Log.i(TAG, "TTS initialized successfully. Offline voice missing=" + offlineVoiceMissing + ", male voice=" + hasMaleVoice);
        } catch (Throwable t) {
            Log.e(TAG, "Error configuring TTS: " + t.getMessage(), t);
            isInitFailed = true;
        }
    }

    private void discoverAndSelectVoice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        availableOfflineVoices.clear();
        hasMaleVoice = false;

        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) {
                offlineVoiceMissing = true;
                return;
            }

            Locale targetLocale = Locale.US;
            Voice chosenVoice = null;
            Voice defaultVoice = null;
            try {
                defaultVoice = tts.getDefaultVoice();
            } catch (Throwable ignored) {}

            for (Voice v : voices) {
                if (v == null) continue;

                // 1. Check network requirement: strictly reject network voices
                if (v.isNetworkConnectionRequired()) {
                    continue;
                }

                // 2. Check installation feature
                Set<String> features = v.getFeatures();
                if (features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    continue;
                }

                // 3. Match language
                Locale vLoc = v.getLocale();
                if (vLoc != null && vLoc.getLanguage().equalsIgnoreCase(targetLocale.getLanguage())) {
                    String name = v.getName();
                    boolean maleHint = isMaleVoiceHint(name);
                    if (maleHint) {
                        hasMaleVoice = true;
                    }

                    String display = buildVoiceDisplayName(v, maleHint);
                    if (defaultVoice != null && v.getName().equalsIgnoreCase(defaultVoice.getName())) {
                        display = display + " [Default]";
                    }
                    availableOfflineVoices.add(new OfflineVoiceInfo(name, vLoc.toString(), display, maleHint));

                    if (name.equalsIgnoreCase(selectedVoiceName)) {
                        chosenVoice = v;
                    }
                }
            }

            // Sort voices: default first, then male-sounding, then alphabetically
            final Voice finalDefault = defaultVoice;
            Collections.sort(availableOfflineVoices, new Comparator<OfflineVoiceInfo>() {
                @Override
                public int compare(OfflineVoiceInfo o1, OfflineVoiceInfo o2) {
                    boolean isDef1 = finalDefault != null && o1.name.equalsIgnoreCase(finalDefault.getName());
                    boolean isDef2 = finalDefault != null && o2.name.equalsIgnoreCase(finalDefault.getName());
                    if (isDef1 != isDef2) {
                        return isDef1 ? -1 : 1;
                    }
                    if (o1.isMaleHint != o2.isMaleHint) {
                        return o1.isMaleHint ? -1 : 1;
                    }
                    return o1.display.compareToIgnoreCase(o2.display);
                }
            });

            // Voice selection logic:
            // 1. If user explicitly specified a voice preference, try to apply it
            if (selectedVoiceName != null && !selectedVoiceName.trim().isEmpty() && chosenVoice != null) {
                try {
                    int res = tts.setVoice(chosenVoice);
                    if (res == TextToSpeech.ERROR) {
                        Log.w(TAG, "tts.setVoice returned ERROR for " + chosenVoice.getName() + "; clearing pref and using default");
                        chosenVoice = defaultVoice;
                        this.selectedVoiceName = "";
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                               .edit().remove(KEY_VOICE_NAME).apply();
                    } else {
                        UltronDebugLog.log(TAG, "TTS voice set from user preference: " + chosenVoice.getName());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Exception applying voice " + chosenVoice.getName() + ": " + t.getMessage() + "; using default");
                    chosenVoice = defaultVoice;
                    this.selectedVoiceName = "";
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                           .edit().remove(KEY_VOICE_NAME).apply();
                }
            } else {
                // 2. No custom voice selected: KEEP the system's verified default voice!
                // NEVER force an uninstalled male voice descriptor, which causes Google TTS to crash.
                chosenVoice = defaultVoice;
                this.selectedVoiceName = "";
                UltronDebugLog.log(TAG, "TTS keeping verified default engine voice: " + (defaultVoice != null ? defaultVoice.getName() : "system default"));
            }

            if (chosenVoice == null && availableOfflineVoices.isEmpty()) {
                offlineVoiceMissing = true;
                UltronDebugLog.log(TAG, "TTS no offline voice found for language " + targetLocale.getLanguage());
            } else {
                offlineVoiceMissing = false;
            }

        } catch (Throwable t) {
            Log.w(TAG, "Voice discovery encountered issue: " + t.getMessage());
        }
    }

    private boolean isMaleVoiceHint(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        // Common male voice hints for Android Google TTS & common engines
        return lower.contains("iom-local")
                || lower.contains("iol-local")
                || lower.contains("tpd-local")
                || lower.contains("en-us-x-iom")
                || lower.contains("en-us-x-iol")
                || lower.contains("en-us-x-tpd")
                || lower.contains("male")
                || lower.contains("man");
    }

    private String buildVoiceDisplayName(Voice v, boolean maleHint) {
        String name = v.getName();
        String simpleName = name;
        int lastSlash = simpleName.lastIndexOf('/');
        if (lastSlash >= 0) simpleName = simpleName.substring(lastSlash + 1);

        String gender = maleHint ? " (Male)" : " (Default/Female)";
        return simpleName + gender;
    }

    private void setupProgressListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(final String utteranceId) {
                if (utteranceId != null && utteranceId.startsWith("plain_")) {
                    notifySpeechStarted();
                }
            }

            @Override
            public void onDone(final String utteranceId) {
                if (utteranceId != null && utteranceId.startsWith("synth_")) {
                    CountDownLatch latch = activeSynthLatch;
                    if (latch != null) {
                        latch.countDown();
                    }
                } else if (utteranceId != null && utteranceId.startsWith("plain_")) {
                    notifySpeechCompleted();
                }
            }

            @Override
            public void onError(final String utteranceId) {
                Log.w(TAG, "Utterance error received: " + utteranceId);
                if (utteranceId != null && utteranceId.startsWith("synth_")) {
                    CountDownLatch latch = activeSynthLatch;
                    if (latch != null) {
                        latch.countDown();
                    }
                } else if (utteranceId != null && utteranceId.startsWith("plain_")) {
                    notifySpeechCompleted();
                }
            }

            @Override
            public void onError(final String utteranceId, int errorCode) {
                Log.w(TAG, "Utterance error received: " + utteranceId + " (code=" + errorCode + ")");
                onError(utteranceId);
            }
        });
    }

    private void applyPitchAndRateToEngine() {
        if (tts != null && currentProfile != null) {
            tts.setPitch(currentProfile.enginePitch);
            tts.setSpeechRate(currentProfile.engineRate);
        }
    }

    public synchronized void speak(final String text) {
        final int gen = currentGeneration.incrementAndGet();
        UltronDebugLog.log(TAG, "speak requested: \"" + (text != null ? (text.length() > 50 ? text.substring(0, 50) + "..." : text) : "null") + "\" (gen=" + gen + ", style=" + (currentProfile != null ? currentProfile.name : "none") + ")");

        if (text == null || text.trim().isEmpty()) {
            notifySpeechCompleted();
            return;
        }

        if (!isReady || isInitFailed || tts == null) {
            Log.w(TAG, "TTS not ready or failed; skipping audio and completing");
            notifySpeechCompleted();
            return;
        }

        // Cancel previous utterance cleanly
        stopPlaybackInternals();

        // STYLE_NATURAL or profile with DSP disabled: use direct TTS
        if (!currentProfile.enableDsp || UltronVoiceProfile.STYLE_NATURAL.equalsIgnoreCase(currentProfile.name)) {
            applyPitchAndRateToEngine();
            isSpeaking = true;
            String utteranceId = "plain_" + gen;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                try {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                } catch (Throwable t) {
                    Log.w(TAG, "tts.speak failed: " + t.getMessage(), t);
                    notifySpeechCompleted();
                }
            } else {
                HashMap<String, String> params = new HashMap<String, String>();
                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                try {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
                } catch (Throwable t) {
                    Log.w(TAG, "tts.speak failed: " + t.getMessage(), t);
                    notifySpeechCompleted();
                }
            }
            return;
        }

        // STYLE_ULTRON or STYLE_CLEAN_DEEP: Full DSP Pipeline
        isSpeaking = true;
        dspExecutor.execute(new Runnable() {
            @Override
            public void run() {
                executeDspPipeline(text, gen);
            }
        });
    }

    private void executeDspPipeline(String fullText, int gen) {
        final String[] sentences = splitIntoSentences(fullText);
        if (sentences.length == 0) {
            notifySpeechCompleted();
            return;
        }

        final List<float[]> processedSentences = new ArrayList<float[]>();
        int detectedSampleRate = 22050; // Fallback default
        boolean success = true;

        for (int i = 0; i < sentences.length; i++) {
            if (gen != currentGeneration.get()) {
                // Cancelled by a newer speak() or stop()
                return;
            }

            String sentence = sentences[i].trim();
            if (sentence.isEmpty()) continue;

            File tmpFile = null;
            try {
                tmpFile = File.createTempFile("ultron_tts_s" + i + "_", ".wav", context.getCacheDir());
                activeTempFile = tmpFile;
                activeSynthLatch = new CountDownLatch(1);

                String utteranceId = "synth_" + gen + "_" + i;
                applyPitchAndRateToEngine();

                int synthRes = -1;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Bundle params = new Bundle();
                        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                        synthRes = tts.synthesizeToFile(sentence, params, tmpFile, utteranceId);
                    } else {
                        HashMap<String, String> params = new HashMap<String, String>();
                        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                        synthRes = tts.synthesizeToFile(sentence, params, tmpFile.getAbsolutePath());
                    }
                } catch (Throwable synthEx) {
                    Log.w(TAG, "Exception during synthesizeToFile: " + synthEx.getMessage(), synthEx);
                    synthRes = TextToSpeech.ERROR;
                }

                if (synthRes != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "synthesizeToFile returned code " + synthRes);
                    success = false;
                    break;
                }

                boolean latched = activeSynthLatch.await(10, TimeUnit.SECONDS);
                if (!latched || !tmpFile.exists() || tmpFile.length() < 44) {
                    Log.w(TAG, "synthesizeToFile timed out or produced empty file");
                    success = false;
                    break;
                }

                UltronWav.WavData wavData = UltronWav.read(tmpFile);
                detectedSampleRate = wavData.sampleRate;

                float[] dspAudio = UltronVoiceDsp.process(wavData.samples, detectedSampleRate, currentProfile);
                processedSentences.add(dspAudio);

            } catch (Throwable t) {
                Log.w(TAG, "DSP pipeline synthesis failed: " + t.getMessage(), t);
                success = false;
                break;
            } finally {
                if (tmpFile != null && tmpFile.exists()) {
                    tmpFile.delete();
                }
                activeTempFile = null;
                activeSynthLatch = null;
            }
        }

        if (gen != currentGeneration.get()) {
            return;
        }

        if (!success || processedSentences.isEmpty()) {
            Log.w(TAG, "Falling back to plain TTS for utterance");
            fallbackToPlainTts(fullText, gen);
            return;
        }

        // Play processed audio using AudioTrack in MODE_STREAM
        playProcessedAudio(processedSentences, detectedSampleRate, gen);
    }

    private void playProcessedAudio(List<float[]> sentences, int sampleRate, int gen) {
        AudioTrack track = null;
        boolean startedNotified = false;

        try {
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBufSize * 2, 8192);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build();
                track = new AudioTrack(attrs, format, bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } else {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM);
            }

            activeAudioTrack = track;
            track.play();

            int silenceSamplesCount = (int) (sampleRate * (currentProfile.sentenceSilenceMs / 1000.0f));
            short[] silenceChunk = new short[Math.min(silenceSamplesCount, 2048)];
            long totalWrittenFrames = 0;

            for (int sIdx = 0; sIdx < sentences.size(); sIdx++) {
                if (gen != currentGeneration.get()) break;

                float[] sentenceAudio = sentences.get(sIdx);
                short[] pcmChunk = new short[2048];
                int chunkPos = 0;

                for (int i = 0; i < sentenceAudio.length; i++) {
                    if (gen != currentGeneration.get()) break;

                    pcmChunk[chunkPos++] = (short) Math.round(sentenceAudio[i] * 32767.0f);

                    if (chunkPos >= pcmChunk.length) {
                        if (!startedNotified) {
                            startedNotified = true;
                            notifySpeechStarted();
                        }
                        int written = track.write(pcmChunk, 0, chunkPos);
                        if (written > 0) totalWrittenFrames += written;
                        chunkPos = 0;
                    }
                }

                if (chunkPos > 0 && gen == currentGeneration.get()) {
                    if (!startedNotified) {
                        startedNotified = true;
                        notifySpeechStarted();
                    }
                    int written = track.write(pcmChunk, 0, chunkPos);
                    if (written > 0) totalWrittenFrames += written;
                }

                // Write inter-sentence cadence silence
                if (sIdx < sentences.size() - 1 && silenceSamplesCount > 0 && gen == currentGeneration.get()) {
                    int remainingSilence = silenceSamplesCount;
                    while (remainingSilence > 0 && gen == currentGeneration.get()) {
                        int toWrite = Math.min(remainingSilence, silenceChunk.length);
                        int written = track.write(silenceChunk, 0, toWrite);
                        if (written > 0) totalWrittenFrames += written;
                        remainingSilence -= toWrite;
                    }
                }
            }

            // Drain AudioTrack
            if (gen == currentGeneration.get() && totalWrittenFrames > 0) {
                track.stop();
                long timeoutMs = (totalWrittenFrames * 1000L / sampleRate) + 1200L;
                long startWait = System.currentTimeMillis();

                while (System.currentTimeMillis() - startWait < timeoutMs && gen == currentGeneration.get()) {
                    int headPos = track.getPlaybackHeadPosition();
                    if (headPos >= totalWrittenFrames) {
                        break;
                    }
                    Thread.sleep(30);
                }
            }

        } catch (Throwable t) {
            Log.w(TAG, "AudioTrack playback error: " + t.getMessage(), t);
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignored) {}
            }
            activeAudioTrack = null;

            if (gen == currentGeneration.get()) {
                notifySpeechCompleted();
            }
        }
    }

    private void fallbackToPlainTts(final String text, final int gen) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (gen != currentGeneration.get()) return;
                applyPitchAndRateToEngine();
                String utteranceId = "plain_" + gen;
                if (tts != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            Bundle params = new Bundle();
                            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                        } else {
                            HashMap<String, String> params = new HashMap<String, String>();
                            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "fallbackToPlainTts failed: " + t.getMessage(), t);
                        notifySpeechCompleted();
                    }
                } else {
                    notifySpeechCompleted();
                }
            }
        });
    }

    private String[] splitIntoSentences(String text) {
        if (text == null) return new String[0];
        // Split by sentence delimiters (. ! ? newline) while retaining clean phrases
        String[] raw = text.split("(?<=[.!?\\n])\\s+");
        List<String> valid = new ArrayList<String>();
        for (String s : raw) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                valid.add(trimmed);
            }
        }
        return valid.toArray(new String[valid.size()]);
    }

    public synchronized void stop() {
        currentGeneration.incrementAndGet();
        UltronDebugLog.log(TAG, "stop requested");
        stopPlaybackInternals();
        notifySpeechCompleted();
    }

    private void stopPlaybackInternals() {
        CountDownLatch latch = activeSynthLatch;
        if (latch != null) {
            latch.countDown();
        }

        File tmp = activeTempFile;
        if (tmp != null && tmp.exists()) {
            tmp.delete();
        }

        AudioTrack track = activeAudioTrack;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
                track.release();
            } catch (Throwable ignored) {}
            activeAudioTrack = null;
        }

        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {}
        }
    }

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
        Log.i(TAG, "Voice style changed to: " + style);
    }

    public String getVoiceStyle() {
        return currentProfile != null ? currentProfile.name : UltronVoiceProfile.STYLE_ULTRON;
    }

    public void setVoiceName(String voiceName) {
        if (voiceName == null) return;
        this.selectedVoiceName = voiceName;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_VOICE_NAME, voiceName).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && tts != null) {
            try {
                if (voiceName.isEmpty() || "default".equalsIgnoreCase(voiceName)) {
                    Voice def = tts.getDefaultVoice();
                    if (def != null) {
                        try { tts.setVoice(def); } catch (Throwable ignored) {}
                    }
                    UltronDebugLog.log(TAG, "TTS voice reset to system default");
                    return;
                }

                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (v.getName().equalsIgnoreCase(voiceName)) {
                            int res = tts.setVoice(v);
                            if (res == TextToSpeech.ERROR) {
                                Log.w(TAG, "Could not set voice: ERROR returned, falling back to default");
                                Voice def = tts.getDefaultVoice();
                                if (def != null) {
                                    try { tts.setVoice(def); } catch (Throwable ignored) {}
                                }
                            } else {
                                UltronDebugLog.log(TAG, "TTS voice chosen by user: " + voiceName);
                            }
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not set voice: " + t.getMessage());
            }
        }
    }

    public String getVoiceName() {
        return selectedVoiceName;
    }

    public String getVoiceOptionsJson() {
        JSONArray arr = new JSONArray();
        try {
            for (OfflineVoiceInfo info : availableOfflineVoices) {
                JSONObject obj = new JSONObject();
                obj.put("name", info.name);
                obj.put("locale", info.locale);
                obj.put("display", info.display);
                obj.put("selected", info.name.equalsIgnoreCase(selectedVoiceName));
                arr.put(obj);
            }
        } catch (Throwable t) {
            Log.w(TAG, "JSON serialization error: " + t.getMessage());
        }
        return arr.toString();
    }

    public boolean isOfflineVoiceMissing() {
        return offlineVoiceMissing;
    }

    public boolean hasMaleVoice() {
        return hasMaleVoice;
    }

    public String getOfflineVoiceNotice() {
        if (offlineVoiceMissing || !hasMaleVoice) {
            return "For the best Ultron sound install a male English voice: Settings > Text-to-speech > Google > Install voice data";
        }
        return null;
    }

    private void notifySpeechStarted() {
        isSpeaking = true;
        UltronDebugLog.log(TAG, "Speech playback started (style=" + (currentProfile != null ? currentProfile.name : "none") + ")");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onSpeechStarted();
                }
            }
        });
    }

    private void notifySpeechCompleted() {
        isSpeaking = false;
        UltronDebugLog.log(TAG, "Speech playback completed");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onSpeechCompleted();
                }
            }
        });
    }

    public void shutdown() {
        stop();
        mainHandler.removeCallbacksAndMessages(null);
        dspExecutor.shutdownNow();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        isReady = false;
    }
}
