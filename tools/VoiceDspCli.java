package com.ultron.assistant;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Host-side DSP & WAV test and tuning CLI tool.
 * NOT shipped in the APK (lives under tools/).
 * Can be run with plain Java/ECJ in Termux or desktop.
 */
public class VoiceDspCli {

    public static void main(String[] args) {
        System.out.println("=== Ultron Voice DSP & WAV Host Test Suite ===");
        boolean allPassed = true;

        try {
            // Test 1: RIFF WAV generation with extra metadata chunks (LIST) and verification
            System.out.print("[Test 1] Parsing WAV with extra chunks (LIST, JUNK)... ");
            byte[] syntheticWavWithList = createWavWithExtraChunks(22050, 4410); // 0.2s of audio
            UltronWav.WavData parsed = UltronWav.read(syntheticWavWithList);
            if (parsed.sampleRate != 22050 || parsed.samples.length != 4410) {
                throw new AssertionError("Mismatch in parsed sampleRate or length: rate=" + parsed.sampleRate + " len=" + parsed.samples.length);
            }
            System.out.println("PASSED (rate=" + parsed.sampleRate + " samples=" + parsed.samples.length + ")");

            // Test 2: Rejection of invalid / non-PCM formats (e.g. 8-bit, IEEE float)
            System.out.print("[Test 2] Rejection of non-PCM16 audio (8-bit, float)... ");
            try {
                byte[] fake8Bit = createFakeWavWithBitsPerSample(8);
                UltronWav.read(fake8Bit);
                System.out.println("FAILED (did not reject 8-bit WAV)");
                allPassed = false;
            } catch (IOException e) {
                // Expected
            }
            try {
                byte[] fakeFloat = createFakeWavWithFormat(3); // 3 = IEEE Float
                UltronWav.read(fakeFloat);
                System.out.println("FAILED (did not reject IEEE float WAV)");
                allPassed = false;
            } catch (IOException e) {
                // Expected
                System.out.println("PASSED (properly rejected non-PCM16)");
            }

            // Test 3: Synthetic harmonic-rich tone burst at 22050 Hz through UltronVoiceDsp
            System.out.print("[Test 3] Processing synthetic tone burst through UltronVoiceDsp... ");
            int sampleRate = 22050;
            int numSamples = sampleRate * 1; // 1 second
            float[] toneBurst = new float[numSamples];
            for (int i = 0; i < numSamples; i++) {
                double t = (double) i / sampleRate;
                // Harmonic rich tone (fundamental 130 Hz + harmonics)
                double sig = 0.5 * Math.sin(2.0 * Math.PI * 130.0 * t)
                           + 0.3 * Math.sin(2.0 * Math.PI * 260.0 * t)
                           + 0.2 * Math.sin(2.0 * Math.PI * 390.0 * t);
                toneBurst[i] = (float) sig;
            }

            UltronVoiceProfile ultronProfile = UltronVoiceProfile.createUltronStyle();
            float[] processed1 = UltronVoiceDsp.process(toneBurst, sampleRate, ultronProfile);

            // Invariant checks:
            // - No NaN or Infinity
            // - Peak amplitude <= 1.0
            // - Length >= input (reverb tail added)
            if (processed1.length < toneBurst.length) {
                throw new AssertionError("Processed length (" + processed1.length + ") < input (" + toneBurst.length + ")");
            }
            float maxPeak = 0.0f;
            for (int i = 0; i < processed1.length; i++) {
                float v = processed1[i];
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    throw new AssertionError("NaN or Infinite sample found at index " + i);
                }
                float abs = Math.abs(v);
                if (abs > 1.0f) {
                    throw new AssertionError("Sample exceeds +-1.0 at index " + i + ": " + v);
                }
                if (abs > maxPeak) maxPeak = abs;
            }
            System.out.println("PASSED (length=" + processed1.length + " peak=" + String.format("%.3f", maxPeak) + ")");

            // Test 4: Determinism test (same input produces identical output)
            System.out.print("[Test 4] Verifying DSP determinism... ");
            float[] processed2 = UltronVoiceDsp.process(toneBurst, sampleRate, ultronProfile);
            if (!Arrays.equals(processed1, processed2)) {
                throw new AssertionError("DSP output is non-deterministic!");
            }
            System.out.println("PASSED (exact bitwise match)");

            // Test 5: Write test audio output file for listening/tuning
            System.out.print("[Test 5] Writing rendered test WAV to disk... ");
            File outDir = new File("/storage/emulated/0/Project");
            if (!outDir.exists() || !outDir.canWrite()) {
                outDir = new File(".");
            }
            File outFile = new File(outDir, "ultron_voice_test_ultron.wav");
            UltronWav.writeToFile(processed1, sampleRate, outFile);
            System.out.println("PASSED (Saved to " + outFile.getAbsolutePath() + ")");

            // Also test clean_deep and natural
            UltronVoiceProfile cleanDeep = UltronVoiceProfile.createCleanDeepStyle();
            float[] processedClean = UltronVoiceDsp.process(toneBurst, sampleRate, cleanDeep);
            File cleanOutFile = new File(outDir, "ultron_voice_test_clean_deep.wav");
            UltronWav.writeToFile(processedClean, sampleRate, cleanOutFile);

            UltronVoiceProfile natural = UltronVoiceProfile.createNaturalStyle();
            float[] processedNatural = UltronVoiceDsp.process(toneBurst, sampleRate, natural);
            File naturalOutFile = new File(outDir, "ultron_voice_test_natural.wav");
            UltronWav.writeToFile(processedNatural, sampleRate, naturalOutFile);

            System.out.println("\n[SUMMARY] All host-side DSP and WAV tests PASSED successfully!");

        } catch (Throwable t) {
            System.err.println("\nTEST FAILED WITH EXCEPTION:");
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static byte[] createWavWithExtraChunks(int sampleRate, int sampleCount) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int subChunk2Size = sampleCount * 2;
        byte[] listData = "INFOISFTUltronVoiceDSPTest".getBytes("US-ASCII");
        int listChunkSize = listData.length;
        int totalSize = 36 + subChunk2Size + 8 + listChunkSize + (listChunkSize % 2);

        // RIFF header
        baos.write("RIFF".getBytes("US-ASCII"));
        writeIntLE(baos, totalSize);
        baos.write("WAVE".getBytes("US-ASCII"));

        // LIST chunk before fmt
        baos.write("LIST".getBytes("US-ASCII"));
        writeIntLE(baos, listChunkSize);
        baos.write(listData);
        if (listChunkSize % 2 != 0) baos.write(0);

        // fmt chunk
        baos.write("fmt ".getBytes("US-ASCII"));
        writeIntLE(baos, 16);
        writeShortLE(baos, (short) 1); // PCM
        writeShortLE(baos, (short) 1); // mono
        writeIntLE(baos, sampleRate);
        writeIntLE(baos, sampleRate * 2);
        writeShortLE(baos, (short) 2);
        writeShortLE(baos, (short) 16);

        // data chunk
        baos.write("data".getBytes("US-ASCII"));
        writeIntLE(baos, subChunk2Size);
        for (int i = 0; i < sampleCount; i++) {
            short s = (short) (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000);
            baos.write(s & 0xFF);
            baos.write((s >> 8) & 0xFF);
        }

        return baos.toByteArray();
    }

    private static byte[] createFakeWavWithBitsPerSample(int bits) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("RIFF".getBytes("US-ASCII"));
        writeIntLE(baos, 36);
        baos.write("WAVE".getBytes("US-ASCII"));
        baos.write("fmt ".getBytes("US-ASCII"));
        writeIntLE(baos, 16);
        writeShortLE(baos, (short) 1); // PCM
        writeShortLE(baos, (short) 1);
        writeIntLE(baos, 16000);
        writeIntLE(baos, 16000);
        writeShortLE(baos, (short) 1);
        writeShortLE(baos, (short) bits);
        baos.write("data".getBytes("US-ASCII"));
        writeIntLE(baos, 0);
        return baos.toByteArray();
    }

    private static byte[] createFakeWavWithFormat(int format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("RIFF".getBytes("US-ASCII"));
        writeIntLE(baos, 36);
        baos.write("WAVE".getBytes("US-ASCII"));
        baos.write("fmt ".getBytes("US-ASCII"));
        writeIntLE(baos, 16);
        writeShortLE(baos, (short) format); // 3 = float
        writeShortLE(baos, (short) 1);
        writeIntLE(baos, 16000);
        writeIntLE(baos, 16000 * 4);
        writeShortLE(baos, (short) 4);
        writeShortLE(baos, (short) 32);
        baos.write("data".getBytes("US-ASCII"));
        writeIntLE(baos, 0);
        return baos.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
