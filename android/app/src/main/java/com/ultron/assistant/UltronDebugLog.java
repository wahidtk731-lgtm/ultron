package com.ultron.assistant;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics logger writing timestamped entries to /storage/emulated/0/Project/ultron_voice.log
 * Capped at ~200 KB with oldest entries truncated. Java 7 compatible.
 */
public final class UltronDebugLog {

    private static final String LOG_PATH = "/storage/emulated/0/Project/ultron_voice.log";
    private static final long MAX_FILE_SIZE_BYTES = 200 * 1024; // 200 KB
    private static final long KEEP_SIZE_BYTES = 100 * 1024;     // keep last 100 KB on trim
    private static final Object LOCK = new Object();

    private UltronDebugLog() {}

    public static void log(String tag, String msg) {
        if (tag == null) tag = "Ultron";
        if (msg == null) msg = "";

        // Standard Android logcat
        Log.i(tag, msg);

        synchronized (LOCK) {
            try {
                File file = new File(LOG_PATH);
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }

                // Truncate oldest lines if file exceeds ~200 KB
                if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                    try {
                        long skip = file.length() - KEEP_SIZE_BYTES;
                        if (skip > 0) {
                            RandomAccessFile raf = new RandomAccessFile(file, "r");
                            raf.seek(skip);
                            // Advance to the next line boundary
                            int b;
                            while ((b = raf.read()) != -1) {
                                if (b == '\n') break;
                            }
                            byte[] buffer = new byte[8192];
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            int read;
                            while ((read = raf.read(buffer)) != -1) {
                                baos.write(buffer, 0, read);
                            }
                            raf.close();

                            FileOutputStream trimFos = new FileOutputStream(file, false);
                            trimFos.write(baos.toByteArray());
                            trimFos.close();
                        }
                    } catch (Throwable trimEx) {
                        Log.w("UltronDebugLog", "Failed to trim log: " + trimEx.getMessage());
                    }
                }

                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = timestamp + " [" + tag + "] " + msg + "\n";
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable t) {
                // Best-effort: do not crash on storage failures
                Log.w("UltronDebugLog", "Failed to write voice log: " + t.getMessage());
            }
        }
    }
}
