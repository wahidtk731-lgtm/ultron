package com.ultron.assistant;

/**
 * Ultron Voice Digital Signal Processing Pipeline (Pure Java, Java 7, NO Android imports).
 * Transforms synthesized speech into a deep, menacing, resonant AI character:
 * - Low/High shelf biquad EQ
 * - Ring-modulation metallic layer
 * - Resonant comb filter
 * - Schroeder reverberation (predelay + 4 parallel combs + 2 series allpasses)
 * - Tanh soft-clip saturation
 * - Peak normalization
 * - Click-prevention fade-in/out
 */
public class UltronVoiceDsp {

    public static float[] process(float[] in, int sampleRate, UltronVoiceProfile profile) {
        if (in == null || in.length == 0 || sampleRate <= 0) {
            return in != null ? in : new float[0];
        }

        // If DSP is disabled (e.g. STYLE_NATURAL), return copy clamped to [-1.0, 1.0]
        if (profile == null || !profile.enableDsp) {
            float[] clean = new float[in.length];
            for (int i = 0; i < in.length; i++) {
                float s = in[i];
                if (Float.isNaN(s) || Float.isInfinite(s)) s = 0.0f;
                if (s > 1.0f) s = 1.0f;
                else if (s < -1.0f) s = -1.0f;
                clean[i] = s;
            }
            return clean;
        }

        int inLen = in.length;

        // 1. Equalizer: Low-shelf and High-shelf Biquad Filters
        float[] eqSignal = new float[inLen];
        System.arraycopy(in, 0, eqSignal, 0, inLen);

        if (profile.lowShelfGainDb != 0.0f && profile.lowShelfFreq > 20.0f) {
            applyLowShelf(eqSignal, sampleRate, profile.lowShelfFreq, profile.lowShelfGainDb);
        }
        if (profile.highShelfGainDb != 0.0f && profile.highShelfFreq > 20.0f) {
            applyHighShelf(eqSignal, sampleRate, profile.highShelfFreq, profile.highShelfGainDb);
        }

        // 2. Ring Modulation (metallic frequency layer)
        if (profile.ringModDepth > 0.001f && profile.ringModCarrierFreq > 0.0f) {
            float carrierStep = (float) (2.0 * Math.PI * profile.ringModCarrierFreq / sampleRate);
            float phase = 0.0f;
            float depth = profile.ringModDepth;
            float dry = 1.0f - depth;
            for (int i = 0; i < inLen; i++) {
                float carrier = (float) Math.sin(phase);
                phase += carrierStep;
                if (phase > 2.0f * (float) Math.PI) {
                    phase -= 2.0f * (float) Math.PI;
                }
                float x = eqSignal[i];
                eqSignal[i] = (x * dry) + (x * carrier * depth);
            }
        }

        // 3. Resonant Comb Filter (acoustic chassis coloration)
        if (profile.combMix > 0.001f && profile.combDelayMs > 0.1f) {
            applyCombFilter(eqSignal, sampleRate, profile.combDelayMs, profile.combFeedback, profile.combMix);
        }

        // 4. Schroeder Reverb (with tail addition)
        int tailSamples = 0;
        if (profile.reverbWet > 0.001f && profile.reverbDecaySec > 0.05f) {
            tailSamples = (int) (sampleRate * Math.min(profile.reverbDecaySec * 0.8f, 1.5f));
        }

        int totalLen = inLen + tailSamples;
        float[] out = new float[totalLen];

        if (tailSamples > 0 && profile.reverbWet > 0.001f) {
            applySchroederReverb(eqSignal, out, inLen, totalLen, sampleRate, profile);
        } else {
            System.arraycopy(eqSignal, 0, out, 0, inLen);
        }

        // 5. Tanh Soft-Clip Overdrive & Saturation
        float drive = profile.drive;
        for (int i = 0; i < totalLen; i++) {
            float s = out[i];
            if (Float.isNaN(s) || Float.isInfinite(s)) {
                s = 0.0f;
            }
            out[i] = (float) Math.tanh(s * drive);
        }

        // 6. Peak Normalization
        float peak = 0.0f;
        for (int i = 0; i < totalLen; i++) {
            float abs = Math.abs(out[i]);
            if (abs > peak) peak = abs;
        }

        float targetPeak = profile.peakNormalize;
        if (peak > 1e-4f) {
            float scale = targetPeak / peak;
            for (int i = 0; i < totalLen; i++) {
                out[i] *= scale;
            }
        }

        // 7. Click prevention: 5 ms Fade-In and Fade-Out
        int fadeSamples = (int) (sampleRate * 0.005f); // 5 ms
        if (fadeSamples > 1 && totalLen > fadeSamples * 2) {
            for (int i = 0; i < fadeSamples; i++) {
                float ramp = (float) i / fadeSamples;
                out[i] *= ramp;
                out[totalLen - 1 - i] *= ramp;
            }
        }

        // Final safety clamp: strictly [-1.0f, 1.0f], zero out NaN/Inf
        for (int i = 0; i < totalLen; i++) {
            float s = out[i];
            if (Float.isNaN(s) || Float.isInfinite(s)) {
                out[i] = 0.0f;
            } else if (s > 1.0f) {
                out[i] = 1.0f;
            } else if (s < -1.0f) {
                out[i] = -1.0f;
            }
        }

        return out;
    }

    private static void applyLowShelf(float[] s, int sampleRate, float f0, float gainDb) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / sampleRate;
        double cosW = Math.cos(w0);
        double sinW = Math.sin(w0);
        double alpha = sinW / 2.0 * Math.sqrt(2.0);
        double sqrtA2 = 2.0 * Math.sqrt(A) * alpha;

        double b0 = A * ((A + 1.0) - (A - 1.0) * cosW + sqrtA2);
        double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW);
        double b2 = A * ((A + 1.0) - (A - 1.0) * cosW - sqrtA2);
        double a0 = (A + 1.0) + (A - 1.0) * cosW + sqrtA2;
        double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW);
        double a2 = (A + 1.0) + (A - 1.0) * cosW - sqrtA2;

        runBiquad(s, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static void applyHighShelf(float[] s, int sampleRate, float f0, float gainDb) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / sampleRate;
        double cosW = Math.cos(w0);
        double sinW = Math.sin(w0);
        double alpha = sinW / 2.0 * Math.sqrt(2.0);
        double sqrtA2 = 2.0 * Math.sqrt(A) * alpha;

        double b0 = A * ((A + 1.0) + (A - 1.0) * cosW + sqrtA2);
        double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW);
        double b2 = A * ((A + 1.0) + (A - 1.0) * cosW - sqrtA2);
        double a0 = (A + 1.0) - (A - 1.0) * cosW + sqrtA2;
        double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW);
        double a2 = (A + 1.0) - (A - 1.0) * cosW - sqrtA2;

        runBiquad(s, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static void runBiquad(float[] s, double b0, double b1, double b2, double a1, double a2) {
        double x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0;
        for (int i = 0; i < s.length; i++) {
            double x0 = s[i];
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            s[i] = (float) y0;
        }
    }

    private static void applyCombFilter(float[] s, int sampleRate, float delayMs, float feedback, float mix) {
        int delay = Math.max(1, (int) Math.round(sampleRate * delayMs / 1000.0));
        float[] buffer = new float[delay];
        int bufIdx = 0;
        float dry = 1.0f - mix;
        float fb = Math.min(0.85f, Math.max(0.0f, feedback));

        for (int i = 0; i < s.length; i++) {
            float x = s[i];
            float delayed = buffer[bufIdx];
            float y = x + fb * delayed;
            buffer[bufIdx] = y;
            bufIdx = (bufIdx + 1) % delay;

            s[i] = (dry * x) + (mix * y);
        }
    }

    private static void applySchroederReverb(float[] in, float[] out, int inLen, int totalLen, int sampleRate, UltronVoiceProfile profile) {
        // Predelay buffer
        int predelay = Math.max(1, (int) Math.round(sampleRate * profile.reverbPredelayMs / 1000.0));
        float[] preBuf = new float[predelay];
        int preIdx = 0;

        // 4 Parallel Feedback Comb Filters (standard Schroeder prime ratios)
        float[] combMs = new float[]{29.7f, 37.1f, 41.1f, 43.7f};
        int numCombs = combMs.length;
        int[] combDelays = new int[numCombs];
        float[][] combBuffers = new float[numCombs][];
        int[] combIndices = new int[numCombs];
        float[] combFeedback = new float[numCombs];

        float t60 = Math.max(0.1f, profile.reverbDecaySec);
        for (int c = 0; c < numCombs; c++) {
            int d = Math.max(1, (int) Math.round(sampleRate * combMs[c] / 1000.0));
            combDelays[c] = d;
            combBuffers[c] = new float[d];
            combIndices[c] = 0;
            // Decay formula: g = 10 ^ (-3 * Delay / (T60 * SampleRate))
            double g = Math.pow(10.0, -3.0 * d / (t60 * sampleRate));
            combFeedback[c] = (float) Math.min(0.92, Math.max(0.1, g));
        }

        // 2 Series All-pass filters
        float[] apMs = new float[]{5.0f, 1.7f};
        int numAp = apMs.length;
        int[] apDelays = new int[numAp];
        float[][] apBuffers = new float[numAp][];
        int[] apIndices = new int[numAp];
        float apGain = 0.5f;

        for (int a = 0; a < numAp; a++) {
            int d = Math.max(1, (int) Math.round(sampleRate * apMs[a] / 1000.0));
            apDelays[a] = d;
            apBuffers[a] = new float[d];
            apIndices[a] = 0;
        }

        float wet = profile.reverbWet;
        float dry = 1.0f - wet;

        for (int n = 0; n < totalLen; n++) {
            float drySample = (n < inLen) ? in[n] : 0.0f;

            // Predelay
            float preDelayed = preBuf[preIdx];
            preBuf[preIdx] = drySample;
            preIdx = (preIdx + 1) % predelay;

            // 4 Parallel combs
            float combSum = 0.0f;
            for (int c = 0; c < numCombs; c++) {
                int idx = combIndices[c];
                float delayed = combBuffers[c][idx];
                float combOut = delayed;
                combBuffers[c][idx] = preDelayed + (delayed * combFeedback[c]);
                combIndices[c] = (idx + 1) % combDelays[c];
                combSum += combOut;
            }
            float apIn = combSum * 0.25f;

            // 2 Series allpass filters: y[n] = -g*x[n] + v[n-D], where v[n] = x[n] + g*v[n-D]
            for (int a = 0; a < numAp; a++) {
                int idx = apIndices[a];
                float delayed = apBuffers[a][idx];
                float v = apIn + (apGain * delayed);
                float apOut = -apGain * v + delayed;
                apBuffers[a][idx] = v;
                apIndices[a] = (idx + 1) % apDelays[a];
                apIn = apOut;
            }

            float reverbSample = apIn;
            out[n] = (drySample * dry) + (reverbSample * wet);
        }
    }
}
