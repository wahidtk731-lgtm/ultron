package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.*;

/**
 * Android Device-First Application Discovery & Execution Engine.
 * Replaces Linux .desktop discovery with native Android PackageManager.
 */
public class AppLauncherAndroid {

    private static final String TAG = "UltronAppLauncher";
    private final Context context;
    private final PackageManager pm;

    public AppLauncherAndroid(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    /**
     * Finds and launches an installed Android app by friendly name.
     */
    public boolean launchApp(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        String query = target.toLowerCase().trim();

        // 1. Direct package search / common alias
        String directPkg = getCommonPackage(query);
        if (directPkg != null) {
            Intent intent = pm.getLaunchIntentForPackage(directPkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        }

        // 2. Scan all launchable apps
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> appList = pm.queryIntentActivities(mainIntent, 0);

        ResolveInfo bestMatch = null;
        int bestScore = 0;

        for (ResolveInfo info : appList) {
            String label = info.loadLabel(pm).toString().toLowerCase();
            String pkg = info.activityInfo.packageName.toLowerCase();

            if (label.equals(query) || pkg.endsWith("." + query)) {
                bestMatch = info;
                break;
            }

            if (label.contains(query) || query.contains(label)) {
                int score = 50 + (label.length() == query.length() ? 30 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = info;
                }
            }
        }

        if (bestMatch != null) {
            Intent launch = pm.getLaunchIntentForPackage(bestMatch.activityInfo.packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                return true;
            }
        }

        // 3. Fallback: try web search or browser
        return searchWeb(target);
    }

    /**
     * Performs a web search on Google.
     */
    public boolean searchWeb(String query) {
        try {
            Intent searchIntent = new Intent(Intent.ACTION_VIEW, 
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(searchIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Search failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes/appends text note to local file.
     */
    public boolean writeNote(String content) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File noteFile = new File(dir, "ultron_notes.txt");
            FileWriter writer = new FileWriter(noteFile, true);
            writer.write(new Date() + ": " + content + "\n");
            writer.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Write note failed: " + e.getMessage());
            return false;
        }
    }

    private String getCommonPackage(String name) {
        switch (name) {
            case "chrome":
            case "google chrome":
            case "browser":
                return "com.android.chrome";
            case "youtube":
                return "com.google.android.youtube";
            case "settings":
            case "system settings":
                return "com.android.settings";
            case "camera":
                return "com.google.android.GoogleCamera";
            case "calculator":
            case "calc":
                return "com.google.android.calculator";
            case "maps":
            case "google maps":
                return "com.google.android.apps.maps";
            case "gmail":
            case "email":
            case "mail":
                return "com.google.android.gm";
            case "files":
            case "file manager":
                return "com.google.android.apps.nbu.files";
            default:
                return null;
        }
    }
}
