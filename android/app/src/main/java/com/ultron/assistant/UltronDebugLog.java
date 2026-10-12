package com.ultron.assistant;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Diagnostics logger for Ultron Assistant (Java 7 compatible).
 * - ALWAYS writes to the app's private files dir: getFilesDir()/ultron_voice.log (capped at ~200 KB).
 * - Best-effort mirror to /storage/emulated/0/Project/ultron_voice.log inside try/catch.
 * - Exposes getDebugLog(maxLines) and clearDebugLog() for the in-app HUD bridge.
 */
public final class UltronDebugLog {

    private static final String TAG = "UltronDebugLog";
    private static final String EXTERNAL_LOG_PATH = "/storage/emulated/0/Project/ultron_voice.log";
    private static final long MAX_FILE_SIZE_BYTES = 200 * 1024; // 200 KB
    private static final long KEEP_SIZE_BYTES = 100 * 1024;     // Keep last 100 KB on trim
    private static final Object LOCK = new Object();

    private static volatile File privateLogFile = null;

    private UltronDebugLog() {}

    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                Context appCtx = context.getApplicationContext();
                File filesDir = appCtx != null ? appCtx.getFilesDir() : context.getFilesDir();
                if (filesDir != null) {
                    if (!filesDir.exists()) {
                        filesDir.mkdirs();
                    }
                    privateLogFile = new File(filesDir, "ultron_voice.log");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to resolve private files dir: " + t.getMessage());
            }
        }
    }

    private static File getPrivateLogFile() {
        if (privateLogFile != null) {
            return privateLogFile;
        }
        // Fallback default path if init(context) was not yet called
        File fallbackDir = new File("/data/data/com.ultron.assistant/files");
        if (fallbackDir.exists() || fallbackDir.mkdirs()) {
            privateLogFile = new File(fallbackDir, "ultron_voice.log");
        }
        return privateLogFile;
    }

    public static void log(String tag, String msg) {
        if (tag == null) tag = "Ultron";
        if (msg == null) msg = "";

        // Standard Android logcat
        Log.i(tag, msg);

        synchronized (LOCK) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String formattedLine = timestamp + " [" + tag + "] " + msg + "\n";
            byte[] lineBytes;
            try {
                lineBytes = formattedLine.getBytes("UTF-8");
            } catch (Throwable t) {
                lineBytes = formattedLine.getBytes();
            }

            // 1. Primary: ALWAYS write to app's private files dir
            try {
                File pFile = getPrivateLogFile();
                if (pFile != null) {
                    trimFileIfNeeded(pFile);
                    FileOutputStream fos = new FileOutputStream(pFile, true);
                    fos.write(lineBytes);
                    fos.flush();
                    fos.close();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed writing to private log: " + t.getMessage());
            }

            // 2. Secondary: Best-effort mirror to /storage/emulated/0/Project/ultron_voice.log
            try {
                File extFile = new File(EXTERNAL_LOG_PATH);
                File extParent = extFile.getParentFile();
                if (extParent != null && (extParent.exists() || extParent.mkdirs())) {
                    trimFileIfNeeded(extFile);
                    FileOutputStream extFos = new FileOutputStream(extFile, true);
                    extFos.write(lineBytes);
                    extFos.flush();
                    extFos.close();
                }
            } catch (Throwable ignored) {
                // Best-effort: ignore external storage failures
            }
        }
    }

    private static void trimFileIfNeeded(File file) {
        if (file == null || !file.exists() || file.length() <= MAX_FILE_SIZE_BYTES) {
            return;
        }
        try {
            long skip = file.length() - KEEP_SIZE_BYTES;
            if (skip > 0) {
                RandomAccessFile raf = new RandomAccessFile(file, "r");
                raf.seek(skip);
                // Advance to next newline boundary
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
                trimFos.flush();
                trimFos.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to trim log file: " + t.getMessage());
        }
    }

    public static String getLastLines(int maxLines) {
        synchronized (LOCK) {
            File target = getPrivateLogFile();
            if (target == null || !target.exists() || target.length() == 0) {
                target = new File(EXTERNAL_LOG_PATH);
            }
            if (target == null || !target.exists() || target.length() == 0) {
                return "No log entries recorded yet.";
            }

            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(target), "UTF-8"));
                LinkedList<String> lines = new LinkedList<String>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() > maxLines) {
                        lines.removeFirst();
                    }
                }

                StringBuilder sb = new StringBuilder();
                for (String l : lines) {
                    sb.append(l).append("\n");
                }
                return sb.toString();
            } catch (Throwable t) {
                return "Failed reading log: " + t.getMessage();
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            try {
                File pFile = getPrivateLogFile();
                if (pFile != null && pFile.exists()) {
                    pFile.delete();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to delete private log: " + t.getMessage());
            }

            try {
                File extFile = new File(EXTERNAL_LOG_PATH);
                if (extFile.exists()) {
                    extFile.delete();
                }
            } catch (Throwable ignored) {}
        }
        log("UltronDebugLog", "Debug log cleared by user request.");
    }
}
