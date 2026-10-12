package com.ultron.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages unpacking the bundled Vosk acoustic model from APK assets to internal storage.
 * 100% Offline: Never downloads anything over the network.
 * Thread-safe singleton with progress notifications.
 */
public class UltronVoiceSetup {

    private static final String TAG = "UltronVoiceSetup";
    private static final String PREF_SETUP = "ultron_voice_setup";
    private static final String KEY_STATE = "state";
    private static final String KEY_VERSION = "installed_version";

    public static final String BUNDLED_MODEL_VERSION = "vosk-model-small-en-us-0.15";
    public static final String MODEL_DIR_NAME = "vosk-model";
    public static final String MODEL_TMP_DIR_NAME = "vosk-model-tmp";
    public static final String VERSION_MARKER_FILE = "ULTRON_MODEL_VERSION";

    public static final String STATE_NOT_STARTED = "NOT_STARTED";
    public static final String STATE_PREPARING = "PREPARING";
    public static final String STATE_READY = "READY";
    public static final String STATE_FAILED = "FAILED";

    // Approximate total uncompressed size of bundled model (~70.9 MB)
    private static final long ESTIMATED_TOTAL_BYTES = 70900000L;
    private static final long MIN_FREE_SPACE_BYTES = 250L * 1024L * 1024L; // 250 MB

    public interface Listener {
        void onStateChanged(String state, int percent, String message);
    }

    private static UltronVoiceSetup sInstance;

    private final Context appContext;
    private final Handler mainHandler;
    private final List<Listener> listeners = new ArrayList<Listener>();

    private volatile String currentState = STATE_NOT_STARTED;
    private volatile int currentPercent = 0;
    private volatile String currentMessage = "";
    private volatile boolean isWorkerRunning = false;

    private UltronVoiceSetup(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = appContext.getSharedPreferences(PREF_SETUP, Context.MODE_PRIVATE);
        this.currentState = prefs.getString(KEY_STATE, STATE_NOT_STARTED);
    }

    public static synchronized UltronVoiceSetup get(Context context) {
        if (sInstance == null) {
            sInstance = new UltronVoiceSetup(context);
        }
        return sInstance;
    }

    public synchronized void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
            l.onStateChanged(currentState, currentPercent, currentMessage);
        }
    }

    public synchronized void removeListener(Listener l) {
        if (l != null) {
            listeners.remove(l);
        }
    }

    public String getState() {
        return currentState;
    }

    public int getPercent() {
        return currentPercent;
    }

    public String getMessage() {
        return currentMessage;
    }

    public File getModelDir() {
        return new File(appContext.getFilesDir(), MODEL_DIR_NAME);
    }

    public boolean isReady() {
        return STATE_READY.equals(currentState) && verifyModelFiles(getModelDir(), BUNDLED_MODEL_VERSION);
    }

    /**
     * Checks if the model is ready. If not, kicks off worker thread to unpack from APK assets.
     */
    public synchronized void ensureReady() {
        File modelDir = getModelDir();
        if (verifyModelFiles(modelDir, BUNDLED_MODEL_VERSION)) {
            setState(STATE_READY, 100, "Offline voice ready");
            UltronVoskEngine.preloadModel(appContext);
            return;
        }

        if (STATE_PREPARING.equals(currentState) || isWorkerRunning) {
            return;
        }

        startUnpackWorker();
    }

    public synchronized void retry() {
        if (isWorkerRunning) {
            return;
        }
        setState(STATE_NOT_STARTED, 0, "Retrying offline voice setup...");
        startUnpackWorker();
    }

    private synchronized void startUnpackWorker() {
        if (isWorkerRunning) {
            return;
        }
        isWorkerRunning = true;
        setState(STATE_PREPARING, 0, "Preparing offline voice engine (0%)...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doUnpack();
                } finally {
                    synchronized (UltronVoiceSetup.this) {
                        isWorkerRunning = false;
                    }
                }
            }
        }, "UltronVoiceSetupWorker").start();
    }

    private void doUnpack() {
        UltronDebugLog.log(TAG, "Starting offline voice model unpack from APK assets");

        // 1. Check free storage
        File filesDir = appContext.getFilesDir();
        long usable = filesDir.getUsableSpace();
        if (usable < MIN_FREE_SPACE_BYTES) {
            String err = "Free up storage (requires at least 250 MB free, found " + (usable / (1024 * 1024)) + " MB)";
            UltronDebugLog.log(TAG, err);
            setState(STATE_FAILED, 0, err);
            return;
        }

        File tmpDir = new File(filesDir, MODEL_TMP_DIR_NAME);
        File finalDir = new File(filesDir, MODEL_DIR_NAME);

        // 2. Clean up stale tmpDir
        if (tmpDir.exists()) {
            deleteRecursively(tmpDir);
        }
        if (!tmpDir.mkdirs()) {
            String err = "Failed to create temporary directory: " + tmpDir.getAbsolutePath();
            UltronDebugLog.log(TAG, err);
            setState(STATE_FAILED, 0, err);
            return;
        }

        try {
            // 3. Recursively copy assets/vosk-model to tmpDir
            AssetManager am = appContext.getAssets();
            long[] bytesCopied = new long[]{0L};
            copyAssetFolder(am, MODEL_DIR_NAME, tmpDir, bytesCopied);

            // 4. Ensure marker file exists in tmpDir
            File markerFile = new File(tmpDir, VERSION_MARKER_FILE);
            if (!markerFile.exists()) {
                writeStringToFile(markerFile, BUNDLED_MODEL_VERSION);
            }

            // 5. Verify copied files before renaming
            if (!verifyModelFiles(tmpDir, BUNDLED_MODEL_VERSION)) {
                throw new IOException("Model verification failed on temporary directory");
            }

            // 6. Atomic swap: delete old finalDir if present, then rename tmpDir -> finalDir
            if (finalDir.exists()) {
                deleteRecursively(finalDir);
            }

            boolean renamed = tmpDir.renameTo(finalDir);
            if (!renamed) {
                // If renameTo fails (across mount points or file locks), fall back to copy
                UltronDebugLog.log(TAG, "renameTo failed; attempting fallback directory move");
                copyDirectory(tmpDir, finalDir);
                deleteRecursively(tmpDir);
            }

            // 7. Verify final installation
            if (verifyModelFiles(finalDir, BUNDLED_MODEL_VERSION)) {
                UltronDebugLog.log(TAG, "Offline voice model successfully installed to " + finalDir.getAbsolutePath());
                setState(STATE_READY, 100, "Offline voice ready");
                UltronVoskEngine.preloadModel(appContext);
            } else {
                throw new IOException("Verification failed after installing to " + finalDir.getAbsolutePath());
            }

        } catch (Throwable t) {
            UltronDebugLog.log(TAG, "Model unpack failed: " + t.getMessage());
            deleteRecursively(tmpDir);
            setState(STATE_FAILED, 0, "Installation failed: " + (t.getMessage() != null ? t.getMessage() : "Unknown error"));
        }
    }

    private void copyAssetFolder(AssetManager am, String assetSubPath, File targetDir, long[] bytesCopied) throws IOException {
        if (!isValidRelativePath(assetSubPath)) {
            throw new IllegalArgumentException("Invalid asset path traversal detected: " + assetSubPath);
        }

        String[] list = am.list(assetSubPath);
        if (list != null && list.length > 0) {
            // It's a directory
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw new IOException("Could not create directory: " + targetDir.getAbsolutePath());
            }
            for (int i = 0; i < list.length; i++) {
                String child = list[i];
                if (!isValidRelativePath(child)) {
                    continue;
                }
                String childAsset = assetSubPath.isEmpty() ? child : assetSubPath + "/" + child;
                File childTarget = new File(targetDir, child);
                copyAssetFolder(am, childAsset, childTarget, bytesCopied);
            }
        } else {
            // It's a file
            File parent = targetDir.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            InputStream in = null;
            OutputStream out = null;
            try {
                in = am.open(assetSubPath);
                out = new FileOutputStream(targetDir);
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    bytesCopied[0] += read;
                    int pct = Math.min(99, (int) ((bytesCopied[0] * 100L) / ESTIMATED_TOTAL_BYTES));
                    if (pct > currentPercent) {
                        setState(STATE_PREPARING, pct, "Preparing offline voice engine (" + pct + "%)...");
                    }
                }
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) {}
                }
                if (out != null) {
                    try { out.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private synchronized void setState(final String state, final int percent, final String message) {
        this.currentState = state;
        this.currentPercent = percent;
        this.currentMessage = message;

        if (STATE_READY.equals(state) || STATE_FAILED.equals(state)) {
            SharedPreferences.Editor ed = appContext.getSharedPreferences(PREF_SETUP, Context.MODE_PRIVATE).edit();
            ed.putString(KEY_STATE, state);
            if (STATE_READY.equals(state)) {
                ed.putString(KEY_VERSION, BUNDLED_MODEL_VERSION);
            }
            ed.apply();
        }

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).onStateChanged(state, percent, message);
                }
            }
        });
    }

    // =========================================================================
    // PURE JAVA STATIC HELPERS (No Android dependencies, safely testable)
    // =========================================================================

    /**
     * Validates that path contains no directory traversal (".."), does not start with "/",
     * and contains no null bytes.
     */
    public static boolean isValidRelativePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            return false;
        }
        if (path.indexOf('\0') != -1) {
            return false;
        }
        String[] parts = path.split("[/\\\\]");
        for (int i = 0; i < parts.length; i++) {
            if ("..".equals(parts[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Recursively deletes a file or directory.
     */
    public static boolean deleteRecursively(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) {
            return true;
        }
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursively(children[i]);
                }
            }
        }
        return fileOrDir.delete();
    }

    /**
     * Verifies that the model directory exists, matches the expected version marker,
     * and contains essential acoustic model files.
     */
    public static boolean verifyModelFiles(File modelDir, String expectedVersion) {
        if (modelDir == null || !modelDir.isDirectory()) {
            return false;
        }

        // Check version marker
        File marker = new File(modelDir, VERSION_MARKER_FILE);
        if (!marker.isFile() || marker.length() == 0) {
            return false;
        }
        String ver = readFirstLine(marker);
        if (ver == null || !ver.trim().startsWith(expectedVersion)) {
            return false;
        }

        // Check key Vosk model files
        String[] required = new String[]{
            "am/final.mdl",
            "graph/Gr.fst",
            "graph/HCLr.fst",
            "ivector/final.ie"
        };
        for (int i = 0; i < required.length; i++) {
            File f = new File(modelDir, required[i]);
            if (!f.isFile() || f.length() == 0) {
                return false;
            }
        }
        return true;
    }

    private static String readFirstLine(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            return reader.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void writeStringToFile(File file, String content) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(content.getBytes("UTF-8"));
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("Cannot create dir " + dst.getAbsolutePath());
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    copyDirectory(children[i], new File(dst, children[i].getName()));
                }
            }
        } else {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dst);
                byte[] buf = new byte[16384];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
    }
}
