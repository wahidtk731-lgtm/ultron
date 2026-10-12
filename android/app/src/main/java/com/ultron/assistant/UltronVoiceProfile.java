package com.ultron.assistant;

/**
 * Ultron Voice Profile Configuration (Pure Java, Java 7 compatible, NO Android imports).
 * Contains all acoustic and DSP parameters for stylized voice synthesis.
 * Tuning constants are centralized here so they can be adjusted by ear.
 */
public class UltronVoiceProfile {

    public static final String STYLE_ULTRON = "ultron";
    public static final String STYLE_CLEAN_DEEP = "clean_deep";
    public static final String STYLE_NATURAL = "natural";

    public final String name;
    public final float enginePitch;       // TTS base pitch (clamped 0.5f - 1.0f)
    public final float engineRate;        // TTS base speech rate

    // EQ / Shelving filters
    public final float lowShelfFreq;      // Hz (e.g. 180 Hz)
    public final float lowShelfGainDb;    // dB (+4 dB boost for deep chest resonance)
    public final float highShelfFreq;     // Hz (e.g. 5000 Hz)
    public final float highShelfGainDb;   // dB (-3 dB cut for dark cinematic AI timbre)

    // Ring-modulation (metallic synthesized carrier layer)
    public final float ringModCarrierFreq;// Hz (e.g. 48 Hz robotic modulator)
    public final float ringModDepth;      // 0.0 (off) to 1.0 (full metallic modulation)

    // Comb filter (metallic chassis resonance)
    public final float combDelayMs;       // ms (e.g. 9.0 ms)
    public final float combFeedback;      // Feedback multiplier (e.g. 0.30)
    public final float combMix;           // 0.0 (dry) to 1.0 (wet)

    // Large-room Schroeder reverb
    public final float reverbPredelayMs;  // ms pre-delay before early reflections (e.g. 18 ms)
    public final float reverbDecaySec;    // Decay time in seconds (e.g. 0.9s)
    public final float reverbWet;         // Reverb wet mix (e.g. 0.14)

    // Dynamics and shaping
    public final float drive;             // Soft-clip overdrive for tanh saturation (e.g. 1.25)
    public final float peakNormalize;     // Target peak amplitude ceiling (e.g. 0.89)
    public final int sentenceSilenceMs;   // Deliberate pause between sentences (e.g. 150 ms)
    public final boolean enableDsp;       // Whether DSP processing runs

    public UltronVoiceProfile(String name,
                              float enginePitch,
                              float engineRate,
                              float lowShelfFreq,
                              float lowShelfGainDb,
                              float highShelfFreq,
                              float highShelfGainDb,
                              float ringModCarrierFreq,
                              float ringModDepth,
                              float combDelayMs,
                              float combFeedback,
                              float combMix,
                              float reverbPredelayMs,
                              float reverbDecaySec,
                              float reverbWet,
                              float drive,
                              float peakNormalize,
                              int sentenceSilenceMs,
                              boolean enableDsp) {
        this.name = name;
        this.enginePitch = clamp(enginePitch, 0.50f, 1.50f);
        this.engineRate = clamp(engineRate, 0.50f, 2.00f);
        this.lowShelfFreq = lowShelfFreq;
        this.lowShelfGainDb = lowShelfGainDb;
        this.highShelfFreq = highShelfFreq;
        this.highShelfGainDb = highShelfGainDb;
        this.ringModCarrierFreq = ringModCarrierFreq;
        this.ringModDepth = clamp(ringModDepth, 0.0f, 1.0f);
        this.combDelayMs = combDelayMs;
        this.combFeedback = clamp(combFeedback, 0.0f, 0.95f);
        this.combMix = clamp(combMix, 0.0f, 1.0f);
        this.reverbPredelayMs = reverbPredelayMs;
        this.reverbDecaySec = reverbDecaySec;
        this.reverbWet = clamp(reverbWet, 0.0f, 1.0f);
        this.drive = Math.max(0.1f, drive);
        this.peakNormalize = clamp(peakNormalize, 0.1f, 1.0f);
        this.sentenceSilenceMs = Math.max(0, sentenceSilenceMs);
        this.enableDsp = enableDsp;
    }

    public static UltronVoiceProfile createUltronStyle() {
        return new UltronVoiceProfile(
                STYLE_ULTRON,
                0.62f,  // enginePitch: deep villainous fundamental
                0.90f,  // engineRate: measured cadence
                180.0f, // lowShelfFreq: warm low-end boost
                4.0f,   // lowShelfGainDb: +4 dB
                5000.0f,// highShelfFreq: roll off harsh highs
                -3.0f,  // highShelfGainDb: -3 dB
                48.0f,  // ringModCarrierFreq: 48 Hz sinister metallic flutter
                0.18f,  // ringModDepth: subtle metallic edge
                9.0f,   // combDelayMs: 9 ms acoustic metallic chamber
                0.30f,  // combFeedback: 0.30 resonance
                0.35f,  // combMix: 0.35 wet
                18.0f,  // reverbPredelayMs: 18 ms
                0.90f,  // reverbDecaySec: 0.9 s spacious lair reverberation
                0.14f,  // reverbWet: 0.14 wet
                1.25f,  // drive: 1.25 tanh soft-clip warm saturation
                0.89f,  // peakNormalize: 0.89 peak ceiling
                150,    // sentenceSilenceMs: 150 ms cadence pause
                true    // enableDsp
        );
    }

    public static UltronVoiceProfile createCleanDeepStyle() {
        return new UltronVoiceProfile(
                STYLE_CLEAN_DEEP,
                0.75f,  // enginePitch
                0.95f,  // engineRate
                200.0f, // lowShelfFreq
                2.5f,   // lowShelfGainDb
                4500.0f,// highShelfFreq
                -1.5f,  // highShelfGainDb
                0.0f,   // ringModCarrierFreq (off)
                0.0f,   // ringModDepth (off)
                0.0f,   // combDelayMs (off)
                0.0f,   // combFeedback (off)
                0.0f,   // combMix (off)
                12.0f,  // reverbPredelayMs
                0.55f,  // reverbDecaySec: light ambiance
                0.08f,  // reverbWet: light wet
                1.10f,  // drive
                0.92f,  // peakNormalize
                100,    // sentenceSilenceMs
                true    // enableDsp
        );
    }

    public static UltronVoiceProfile createNaturalStyle() {
        return new UltronVoiceProfile(
                STYLE_NATURAL,
                0.95f,  // enginePitch
                1.05f,  // engineRate
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f,
                1.00f, 0.95f, 0,
                false   // enableDsp = false (direct speech)
        );
    }

    public static UltronVoiceProfile getProfile(String styleKey) {
        if (STYLE_CLEAN_DEEP.equalsIgnoreCase(styleKey)) {
            return createCleanDeepStyle();
        } else if (STYLE_NATURAL.equalsIgnoreCase(styleKey)) {
            return createNaturalStyle();
        }
        return createUltronStyle();
    }

    public UltronVoiceProfile withPitchAndRate(float pitch, float rate) {
        return new UltronVoiceProfile(
                this.name,
                pitch > 0 ? pitch : this.enginePitch,
                rate > 0 ? rate : this.engineRate,
                this.lowShelfFreq,
                this.lowShelfGainDb,
                this.highShelfFreq,
                this.highShelfGainDb,
                this.ringModCarrierFreq,
                this.ringModDepth,
                this.combDelayMs,
                this.combFeedback,
                this.combMix,
                this.reverbPredelayMs,
                this.reverbDecaySec,
                this.reverbWet,
                this.drive,
                this.peakNormalize,
                this.sentenceSilenceMs,
                this.enableDsp
        );
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
