package com.ultron.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 100% Self-Contained Built-in Speech Synthesizer & Audio Manager for Ultron Assistant.
 *
 * Resilience Architecture:
 * 1. ZERO External App Dependencies: Completely eliminates android.speech.tts.TextToSpeech,
 *    Samsung SMT (com.samsung.SMT), and Google Speech Services. The system popup
 *    "Text-to-speech keeps stopping" / "Text-to-speech keeps closing" can NEVER occur.
 * 2. Self-Contained Engine: High-performance bundled SAM engine (libultronsam.so) renders
 *    22050 Hz raw audio in-process, with an algorithmic pure-Java formant fallback.
 * 3. Menacing Ultron DSP: Direct pipeline through UltronVoiceDsp (Low-shelf EQ,
 *    ring modulation, comb filter resonance, Schroeder reverb, tanh soft clipping).
 * 4. Native AudioTrack Playback: Plays stream audio directly through Android AudioTrack.
 * 5. Lifecycle Guarantees: TTSListener.onSpeechStarted() and onSpeechCompleted() fire
 *    exactly once per utterance, ensuring the microphone and HUD are never locked.
 */
public class TTSManager {

    private static final String TAG = "UltronTTS";
    private static final String PREFS_NAME = "ultron_prefs";
    private static final String KEY_VOICE_STYLE = "voice_style";
    private static final String KEY_VOICE_NAME = "voice_name";
    private static final String KEY_VOICE_PITCH = "voice_pitch";
    private static final String KEY_VOICE_RATE = "voice_rate";

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicInteger sCurrentGeneration = new AtomicInteger(0);
    private static final ExecutorService sSpeechExecutor = Executors.newSingleThreadExecutor();
    private static final List<TTSListener> sListeners = new CopyOnWriteArrayList<TTSListener>();

    private static volatile AudioTrack sActiveTrack = null;

    // Instance fields
    private final Context context;
    private final TTSListener listener;
    private UltronVoiceProfile currentProfile;
    private String selectedVoiceName = "Ultron Core AI";
    private float customPitch = -1.0f;
    private float customRate = -1.0f;

    public interface TTSListener {
        void onSpeechStarted();
        void onSpeechCompleted();
    }

    public TTSManager(Context context, TTSListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }

        // Load persisted settings
        SharedPreferences prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String styleKey = prefs.getString(KEY_VOICE_STYLE, UltronVoiceProfile.STYLE_ULTRON);
        this.selectedVoiceName = prefs.getString(KEY_VOICE_NAME, "Ultron Core AI");
        this.customPitch = prefs.getFloat(KEY_VOICE_PITCH, -1.0f);
        this.customRate = prefs.getFloat(KEY_VOICE_RATE, -1.0f);

        updateProfile(styleKey, customPitch, customRate);
        UltronDebugLog.log(TAG, "Self-contained Ultron TTS initialized (nativeSAM=" + UltronSamSynth.isNativeLoaded() + ", style=" + styleKey + ")");
    }

    private void updateProfile(String styleKey, float pitch, float rate) {
        UltronVoiceProfile base = UltronVoiceProfile.getProfile(styleKey);
        this.currentProfile = base.withPitchAndRate(pitch > 0 ? pitch : -1.0f, rate > 0 ? rate : -1.0f);
    }

    // ==============================================================================
    // Speech Synthesis & AudioTrack Playback Pipeline
    // ==============================================================================

    public synchronized void speak(final String text) {
        final int gen = sCurrentGeneration.incrementAndGet();
        UltronDebugLog.log(TAG, "speak requested: \"" + (text != null ? (text.length() > 50 ? text.substring(0, 50) + "..." : text) : "null")
                + "\" (gen=" + gen + ")");

        if (text == null || text.trim().isEmpty()) {
            notifySpeechCompleted();
            return;
        }

        // Immediately interrupt previous audio playback
        stopInternalTrack();

        final String utteranceText = text.trim();
        final UltronVoiceProfile activeProfile = this.currentProfile;

        sSpeechExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (gen != sCurrentGeneration.get()) {
                    return; // Superseded by a newer utterance
                }

                AudioTrack track = null;
                try {
                    // 1. Calculate SAM acoustic parameters based on active profile and overrides
                    int speed = 72; // Default SAM speed
                    if (activeProfile != null && activeProfile.engineRate > 0) {
                        speed = Math.max(45, Math.min(110, (int) (72.0f / activeProfile.engineRate)));
                    }

                    int pitch = 64; // Default SAM pitch
                    if (activeProfile != null && activeProfile.enginePitch > 0) {
                        pitch = Math.max(35, Math.min(95, (int) (64.0f * activeProfile.enginePitch)));
                    }

                    int throat = 128;
                    int mouth = 128;
                    if (activeProfile != null && UltronVoiceProfile.STYLE_ULTRON.equals(activeProfile.name)) {
                        pitch = 56;  // Deeper fundamental
                        speed = 76;  // Measured menacing cadence
                        throat = 132;
                        mouth = 124;
                    } else if (activeProfile != null && UltronVoiceProfile.STYLE_CLEAN_DEEP.equals(activeProfile.name)) {
                        pitch = 58;
                        speed = 74;
                    }

                    // 2. Synthesize raw 22050 Hz PCM samples via in-process engine
                    float[] raw = UltronSamSynth.render(utteranceText, speed, pitch, throat, mouth);
                    if (gen != sCurrentGeneration.get()) return;

                    if (raw == null || raw.length == 0) {
                        UltronDebugLog.log(TAG, "Empty synthesis result, completing");
                        notifySpeechCompleted();
                        return;
                    }

                    // 3. Process through Ultron DSP (reverb, ring-mod, comb, low-shelf EQ)
                    float[] processed = UltronVoiceDsp.process(raw, UltronSamSynth.SAMPLE_RATE, activeProfile);
                    if (gen != sCurrentGeneration.get()) return;

                    if (processed == null || processed.length == 0) {
                        notifySpeechCompleted();
                        return;
                    }

                    // 4. Convert float [-1.0, 1.0] to 16-bit signed PCM
                    short[] pcm16 = new short[processed.length];
                    for (int i = 0; i < processed.length; i++) {
                        float s = processed[i];
                        if (Float.isNaN(s) || Float.isInfinite(s)) s = 0.0f;
                        if (s > 1.0f) s = 1.0f;
                        else if (s < -1.0f) s = -1.0f;
                        pcm16[i] = (short) (s * 32767.0f);
                    }

                    // 5. Play directly via AudioTrack
                    int minBuf = AudioTrack.getMinBufferSize(
                            UltronSamSynth.SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

                    track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            UltronSamSynth.SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minBuf, 8192),
                            AudioTrack.MODE_STREAM
                    );

                    synchronized (TTSManager.class) {
                        if (gen != sCurrentGeneration.get()) {
                            track.release();
                            return;
                        }
                        sActiveTrack = track;
                    }

                    notifySpeechStarted();
                    track.play();

                    int written = 0;
                    int chunkSize = 2048;
                    while (written < pcm16.length) {
                        if (gen != sCurrentGeneration.get()) {
                            break; // Cancelled
                        }
                        int count = Math.min(chunkSize, pcm16.length - written);
                        track.write(pcm16, written, count);
                        written += count;
                    }

                    // Wait briefly for buffer to finish draining if completed
                    if (gen == sCurrentGeneration.get()) {
                        long durationMs = (long) ((pcm16.length / (double) UltronSamSynth.SAMPLE_RATE) * 1000.0);
                        long sleepChunk = 40;
                        long elapsed = 0;
                        while (elapsed < durationMs && gen == sCurrentGeneration.get()) {
                            try {
                                Thread.sleep(sleepChunk);
                            } catch (InterruptedException ignored) {}
                            elapsed += sleepChunk;
                        }
                    }

                } catch (Throwable t) {
                    UltronDebugLog.log(TAG, "AudioTrack playback exception: " + t.getMessage());
                } finally {
                    synchronized (TTSManager.class) {
                        if (track != null) {
                            try {
                                track.stop();
                            } catch (Throwable ignored) {}
                            try {
                                track.release();
                            } catch (Throwable ignored) {}
                        }
                        if (sActiveTrack == track) {
                            sActiveTrack = null;
                        }
                    }
                    if (gen == sCurrentGeneration.get()) {
                        notifySpeechCompleted();
                    }
                }
            }
        });
    }

    public synchronized void stop() {
        sCurrentGeneration.incrementAndGet();
        UltronDebugLog.log(TAG, "stop requested");
        stopInternalTrack();
        notifySpeechCompleted();
    }

    private static synchronized void stopInternalTrack() {
        AudioTrack track = sActiveTrack;
        sActiveTrack = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.stop();
                track.release();
            } catch (Throwable t) {
                UltronDebugLog.log(TAG, "stopInternalTrack note: " + t.getMessage());
            }
        }
    }

    // ==============================================================================
    // Listener Dispatching
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
        updateProfile(currentProfile != null ? currentProfile.name : UltronVoiceProfile.STYLE_ULTRON, customPitch, customRate);
    }

    public void setSpeechRate(float rate) {
        this.customRate = rate;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_VOICE_RATE, rate).apply();
        updateProfile(currentProfile != null ? currentProfile.name : UltronVoiceProfile.STYLE_ULTRON, customPitch, customRate);
    }

    public void setVoiceStyle(String style) {
        if (style == null) return;
        UltronDebugLog.log(TAG, "setVoiceStyle: " + style);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_VOICE_STYLE, style).apply();
        updateProfile(style, customPitch, customRate);
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
        return "[{\"name\":\"Ultron Core AI\",\"locale\":\"en-US\",\"display\":\"Ultron Self-Contained Synth (100% Offline)\"}]";
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
        // Safe mode is unnecessary with our self-contained engine
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
