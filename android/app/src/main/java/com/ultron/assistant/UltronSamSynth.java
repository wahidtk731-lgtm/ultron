package com.ultron.assistant;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * 100% Self-Contained, Offline Software Speech Synthesizer for Ultron.
 *
 * Architecture:
 * - Primary Engine: Ultra-lightweight SAM (Software Automatic Mouth) native C engine,
 *   compiled directly into libultronsam.so for arm64-v8a and armeabi-v7a.
 * - Zero external system app dependencies: never calls or binds to android.speech.tts.TextToSpeech,
 *   Samsung SMT, or Google Speech Services.
 * - Generates 22050 Hz raw PCM audio samples in-process.
 * - Pure Java Formant Fallback: In edge cases where native code is unavailable,
 *   a pure Java formant synthesizer generates robotic speech without crashing or going mute.
 */
public class UltronSamSynth {

    private static final String TAG = "UltronSamSynth";
    public static final int SAMPLE_RATE = 22050;

    private static volatile boolean sNativeLoaded = false;

    static {
        try {
            System.loadLibrary("ultronsam");
            sNativeLoaded = true;
            Log.i(TAG, "libultronsam.so loaded successfully");
        } catch (Throwable t) {
            Log.w(TAG, "libultronsam.so not available: " + t.getMessage());
            sNativeLoaded = false;
        }
    }

    public static boolean isNativeLoaded() {
        return sNativeLoaded;
    }

    /**
     * Native SAM renderer (JNI).
     * @param text English input text (ASCII uppercase converted internally).
     * @param speed Playback speed (50-100, default ~72).
     * @param pitch Fundamental pitch (40-90, default ~64).
     * @param throat Vocal tract throat parameter (100-150, default ~128).
     * @param mouth Vocal tract mouth parameter (100-150, default ~128).
     * @return 8-bit unsigned raw PCM samples (at 22050 Hz), or null on failure.
     */
    private static native byte[] nativeRender(String text, int speed, int pitch, int throat, int mouth);

    /**
     * Synthesizes any text into a float[] PCM buffer [-1.0, 1.0] at 22050 Hz.
     */
    public static float[] render(String text, int speed, int pitch, int throat, int mouth) {
        if (text == null || text.trim().isEmpty()) {
            return new float[0];
        }

        List<String> chunks = splitIntoChunks(text.trim());
        List<float[]> renderedChunks = new ArrayList<float[]>();
        int totalSamples = 0;
        int silenceSamples = (int) (SAMPLE_RATE * 0.08); // 80ms cadence silence

        for (String chunk : chunks) {
            float[] chunkSamples = null;

            if (sNativeLoaded) {
                try {
                    byte[] raw = nativeRender(chunk, speed, pitch, throat, mouth);
                    if (raw != null && raw.length > 0) {
                        chunkSamples = new float[raw.length];
                        for (int i = 0; i < raw.length; i++) {
                            chunkSamples[i] = ((raw[i] & 0xFF) - 128) / 128.0f;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Native SAM render error on chunk: " + t.getMessage());
                }
            }

            // Fallback to pure Java formant synthesis if native failed or not loaded
            if (chunkSamples == null || chunkSamples.length == 0) {
                chunkSamples = renderPureJavaFormant(chunk, speed, pitch);
            }

            if (chunkSamples != null && chunkSamples.length > 0) {
                renderedChunks.add(chunkSamples);
                totalSamples += chunkSamples.length + silenceSamples;
            }
        }

        if (renderedChunks.isEmpty()) {
            return new float[0];
        }

        float[] result = new float[totalSamples];
        int writePos = 0;

        for (int i = 0; i < renderedChunks.size(); i++) {
            float[] c = renderedChunks.get(i);
            System.arraycopy(c, 0, result, writePos, c.length);
            writePos += c.length;

            if (i < renderedChunks.size() - 1) {
                // Insert brief natural silence between sentences
                writePos += silenceSamples;
            }
        }

        return result;
    }

    /**
     * Splits long text into manageable phrases (<= 180 chars) for SAM.
     */
    private static List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<String>();
        String[] sentences = text.split("(?<=[.!?\\n])\\s+");

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            if (sentence.length() <= 180) {
                chunks.add(sentence);
            } else {
                // Sub-split by comma or semicolon
                String[] parts = sentence.split("(?<=[,;])\\s+");
                StringBuilder cur = new StringBuilder();
                for (String p : parts) {
                    if (cur.length() + p.length() > 180 && cur.length() > 0) {
                        chunks.add(cur.toString().trim());
                        cur = new StringBuilder();
                    }
                    if (cur.length() > 0) cur.append(" ");
                    cur.append(p);
                }
                if (cur.length() > 0) {
                    chunks.add(cur.toString().trim());
                }
            }
        }

        if (chunks.isEmpty() && !text.trim().isEmpty()) {
            chunks.add(text.trim());
        }

        return chunks;
    }

    /**
     * Compact, pure-Java algorithmic formant speech synthesizer fallback.
     * Generates vowel/consonant resonance pulses in case native libraries are missing.
     */
    private static float[] renderPureJavaFormant(String text, int speed, int pitch) {
        if (text == null || text.isEmpty()) return new float[0];

        // Base fundamental frequency (F0) from pitch parameter
        float f0 = 90.0f + (pitch > 0 ? (pitch - 64) * 0.8f : 0.0f);
        if (f0 < 60.0f) f0 = 60.0f;
        if (f0 > 160.0f) f0 = 160.0f;

        float charDuration = 0.075f * (speed > 0 ? (72.0f / speed) : 1.0f);
        int totalLen = (int) (text.length() * charDuration * SAMPLE_RATE);
        float[] out = new float[totalLen];

        float phase = 0.0f;
        int outIdx = 0;

        for (int c = 0; c < text.length(); c++) {
            char ch = Character.toLowerCase(text.charAt(c));
            int charSamples = (int) (charDuration * SAMPLE_RATE);

            // Formant frequencies for vowels and approximations for consonants
            float f1 = 500.0f, f2 = 1500.0f, f3 = 2500.0f;
            boolean isVoiced = true;
            float amp = 0.45f;

            switch (ch) {
                case 'a': f1 = 730; f2 = 1090; f3 = 2440; break;
                case 'e': f1 = 530; f2 = 1840; f3 = 2480; break;
                case 'i': f1 = 270; f2 = 2290; f3 = 3010; break;
                case 'o': f1 = 570; f2 = 840;  f3 = 2410; break;
                case 'u': f1 = 300; f2 = 870;  f3 = 2240; break;
                case 's': case 'f': case 't': case 'k': case 'p':
                    isVoiced = false; amp = 0.25f; break;
                case ' ': case '.': case ',': case '!': case '?':
                    amp = 0.0f; break;
                default:
                    f1 = 450; f2 = 1400; f3 = 2300; break;
            }

            for (int s = 0; s < charSamples && outIdx < out.length; s++) {
                float sample = 0.0f;
                if (amp > 0.001f) {
                    if (isVoiced) {
                        float glottal = (float) Math.sin(phase) + 0.35f * (float) Math.sin(2.0 * phase);
                        float res1 = (float) Math.sin(2.0 * Math.PI * f1 * s / SAMPLE_RATE);
                        float res2 = (float) Math.sin(2.0 * Math.PI * f2 * s / SAMPLE_RATE);
                        float res3 = (float) Math.sin(2.0 * Math.PI * f3 * s / SAMPLE_RATE);
                        sample = amp * (0.55f * glottal + 0.22f * res1 + 0.15f * res2 + 0.08f * res3);
                        phase += (float) (2.0 * Math.PI * f0 / SAMPLE_RATE);
                        if (phase > 2.0f * Math.PI) phase -= 2.0f * Math.PI;
                    } else {
                        // Friction noise for unvoiced consonants
                        sample = amp * ((float) (Math.random() * 2.0 - 1.0));
                    }
                }
                out[outIdx++] = sample;
            }
        }

        return out;
    }
}
