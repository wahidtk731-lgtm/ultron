package com.ultron.assistant;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * WAV RIFF Chunk Parser and Writer (Pure Java, Java 7 compatible, NO Android imports).
 * Walks RIFF chunks to safely parse arbitrary WAV structures with metadata chunks (LIST, INFO, etc.),
 * supports PCM16 mono/stereo (downmixed to mono) at any sample rate, and rejects non-PCM formats.
 */
public class UltronWav {

    public static class WavData {
        public final float[] samples;
        public final int sampleRate;

        public WavData(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    public static WavData read(File file) throws IOException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int r = fis.read(bytes, read, bytes.length - read);
                if (r < 0) break;
                read += r;
            }
            return read(bytes);
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static WavData read(byte[] data) throws IOException {
        if (data == null || data.length < 12) {
            throw new IOException("Invalid WAV: byte array too short (" + (data != null ? data.length : 0) + " bytes)");
        }

        // Header check: "RIFF" .... "WAVE"
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') {
            throw new IOException("Not a valid RIFF file");
        }
        if (data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            throw new IOException("Not a valid WAVE container");
        }

        int offset = 12;
        int audioFormat = -1;
        int numChannels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        float[] samples = null;

        while (offset + 8 <= data.length) {
            String chunkId = new String(data, offset, 4, "US-ASCII");
            long chunkSize = readUnsignedIntLE(data, offset + 4);
            int chunkDataOffset = offset + 8;

            if (chunkDataOffset + chunkSize > data.length) {
                // Truncated chunk or file ending
                chunkSize = data.length - chunkDataOffset;
            }

            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16) {
                    throw new IOException("Malformed 'fmt ' chunk: size " + chunkSize);
                }
                audioFormat = readUnsignedShortLE(data, chunkDataOffset);
                numChannels = readUnsignedShortLE(data, chunkDataOffset + 2);
                sampleRate = (int) readUnsignedIntLE(data, chunkDataOffset + 4);
                // byteRate = readUnsignedIntLE(data, chunkDataOffset + 8);
                // blockAlign = readUnsignedShortLE(data, chunkDataOffset + 12);
                bitsPerSample = readUnsignedShortLE(data, chunkDataOffset + 14);

                if (audioFormat != 1) { // 1 = WAVE_FORMAT_PCM
                    throw new IOException("Unsupported WAV audio format: " + audioFormat + " (only PCM16 supported)");
                }
                if (bitsPerSample != 16) {
                    throw new IOException("Unsupported WAV bit depth: " + bitsPerSample + " bits (only 16-bit supported)");
                }
                if (numChannels != 1 && numChannels != 2) {
                    throw new IOException("Unsupported WAV channels: " + numChannels + " (only mono or stereo supported)");
                }
            } else if ("data".equals(chunkId)) {
                if (audioFormat == -1 || numChannels == -1 || sampleRate == -1 || bitsPerSample == -1) {
                    throw new IOException("WAV 'data' chunk appeared before valid 'fmt ' chunk");
                }

                int bytesPerFrame = numChannels * 2;
                int totalFrames = (int) (chunkSize / bytesPerFrame);
                samples = new float[totalFrames];

                int p = chunkDataOffset;
                for (int i = 0; i < totalFrames; i++) {
                    short ch1 = (short) ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8));
                    p += 2;
                    if (numChannels == 2) {
                        short ch2 = (short) ((data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8));
                        p += 2;
                        // Downmix stereo to mono
                        samples[i] = ((ch1 / 32768.0f) + (ch2 / 32768.0f)) * 0.5f;
                    } else {
                        samples[i] = ch1 / 32768.0f;
                    }
                }
            }

            // RIFF chunk alignment: Chunks must be word-aligned (even length)
            long advance = 8 + chunkSize;
            if ((chunkSize % 2) != 0) {
                advance += 1;
            }
            offset += (int) advance;
        }

        if (samples == null) {
            throw new IOException("No PCM data chunk found in WAV");
        }

        return new WavData(samples, sampleRate);
    }

    public static byte[] writeToByteArray(float[] samples, int sampleRate) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        write(samples, sampleRate, baos);
        return baos.toByteArray();
    }

    public static void writeToFile(float[] samples, int sampleRate, File file) throws IOException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            write(samples, sampleRate, fos);
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void write(float[] samples, int sampleRate, OutputStream out) throws IOException {
        int numChannels = 1; // Mono
        int bitsPerSample = 16;
        int bytesPerSample = bitsPerSample / 8;
        int subChunk2Size = samples.length * numChannels * bytesPerSample;
        int chunkSize = 36 + subChunk2Size;
        int byteRate = sampleRate * numChannels * bytesPerSample;
        int blockAlign = numChannels * bytesPerSample;

        // RIFF header
        out.write(new byte[]{'R', 'I', 'F', 'F'});
        writeIntLE(out, chunkSize);
        out.write(new byte[]{'W', 'A', 'V', 'E'});

        // fmt subchunk
        out.write(new byte[]{'f', 'm', 't', ' '});
        writeIntLE(out, 16); // Subchunk1Size for PCM
        writeShortLE(out, (short) 1); // AudioFormat: 1 = PCM
        writeShortLE(out, (short) numChannels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, (short) blockAlign);
        writeShortLE(out, (short) bitsPerSample);

        // data subchunk
        out.write(new byte[]{'d', 'a', 't', 'a'});
        writeIntLE(out, subChunk2Size);

        byte[] buffer = new byte[4096];
        int bufIdx = 0;
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            if (Float.isNaN(s) || Float.isInfinite(s)) {
                s = 0.0f;
            }
            if (s > 1.0f) s = 1.0f;
            if (s < -1.0f) s = -1.0f;
            short pcm = (short) Math.round(s * 32767.0f);

            buffer[bufIdx++] = (byte) (pcm & 0xFF);
            buffer[bufIdx++] = (byte) ((pcm >> 8) & 0xFF);

            if (bufIdx >= buffer.length) {
                out.write(buffer, 0, bufIdx);
                bufIdx = 0;
            }
        }
        if (bufIdx > 0) {
            out.write(buffer, 0, bufIdx);
        }
        out.flush();
    }

    private static long readUnsignedIntLE(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | (((long) (data[offset + 1] & 0xFF)) << 8)
                | (((long) (data[offset + 2] & 0xFF)) << 16)
                | (((long) (data[offset + 3] & 0xFF)) << 24);
    }

    private static int readUnsignedShortLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(OutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
