package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.*;

/**
 * Intelligent Installed App Training & Launch Engine for Ultron Android.
 * Trains itself on every installed app on the user's phone at startup
 * (e.g. AJIO, WhatsApp, Flipkart, YouTube, PhonePe, Zomato, etc.),
 * learning phonetic sound-alikes, aliases, and package identifiers.
 */
public class AppLauncherAndroid {

    private static final String TAG = "UltronAppLauncher";
    private final Context context;
    private final PackageManager pm;

    public static class AppEntry {
        public String label;             // e.g. "AJIO"
        public String cleanLabel;        // e.g. "ajio"
        public String primaryName;       // e.g. "ajio"
        public String packageName;       // e.g. "com.ril.ajio"
        public Set<String> aliases = new HashSet<>();
        public Intent launchIntent;

        public AppEntry(String label, String packageName, Intent launchIntent) {
            this.label = label;
            this.packageName = packageName;
            this.launchIntent = launchIntent;
            this.cleanLabel = sanitize(label);

            String[] words = this.cleanLabel.split("\\s+");
            this.primaryName = words.length > 0 ? words[0] : this.cleanLabel;

            aliases.add(this.cleanLabel);
            aliases.add(this.cleanLabel.replace(" ", ""));
            aliases.add(this.primaryName);

            // Package tokens: com.ril.ajio -> "ril", "ajio"
            String[] pkgParts = packageName.toLowerCase().split("\\.");
            for (String part : pkgParts) {
                if (part.length() >= 3 && !part.equals("com") && !part.equals("android") && !part.equals("app")) {
                    aliases.add(part);
                }
            }

            // Word tokens: "AJIO Online Shopping" -> "ajio", "shopping"
            for (String w : words) {
                if (w.length() >= 3) {
                    aliases.add(w);
                }
            }

            // Phonetic & Acoustic sound-alikes
            addPhoneticAliases(this.primaryName, aliases);
        }

        private static void addPhoneticAliases(String name, Set<String> set) {
            if (name.contains("ajio") || name.contains("jio")) {
                set.add("ajio");
                set.add("a jio");
                set.add("agio");
                set.add("ajiyo");
                set.add("azio");
                set.add("all jio");
            }
            if (name.contains("whatsapp")) {
                set.add("whats app");
                set.add("what's app");
                set.add("watsapp");
                set.add("wa");
            }
            if (name.contains("flipkart")) {
                set.add("flip kart");
                set.add("flipcard");
            }
            if (name.contains("youtube")) {
                set.add("you tube");
                set.add("u tube");
                set.add("yt");
            }
            if (name.contains("instagram")) {
                set.add("insta");
                set.add("ig");
            }
            if (name.contains("paytm")) {
                set.add("pay tm");
                set.add("pay time");
            }
            if (name.contains("phonepe")) {
                set.add("phone pe");
                set.add("phone pay");
            }
            if (name.contains("zomato")) {
                set.add("zomatto");
            }
            if (name.contains("swiggy")) {
                set.add("swigi");
            }
            if (name.contains("hotstar")) {
                set.add("hot star");
                set.add("disney");
            }
            if (name.contains("calculator")) {
                set.add("calc");
            }
            if (name.contains("camera")) {
                set.add("cam");
                set.add("photo");
            }
            if (name.contains("settings")) {
                set.add("setting");
                set.add("system");
            }
            if (name.contains("chrome")) {
                set.add("browser");
                set.add("google chrome");
            }
        }

        private static String sanitize(String input) {
            if (input == null) return "";
            return input.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        }
    }

    private final List<AppEntry> trainedApps = new ArrayList<>();
    private boolean isTrained = false;

    public AppLauncherAndroid(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
        trainInstalledApps();
    }

    /**
     * Scans and trains on every installed launchable application on the user's device.
     */
    public synchronized void trainInstalledApps() {
        trainedApps.clear();
        Set<String> processedPackages = new HashSet<>();

        try {
            // 1. Scan launchable intents from launcher category
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolveList = pm.queryIntentActivities(mainIntent, 0);

            if (resolveList != null) {
                for (ResolveInfo info : resolveList) {
                    if (info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (processedPackages.contains(pkg)) continue;

                    String label = info.loadLabel(pm).toString();
                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        trainedApps.add(new AppEntry(label, pkg, launch));
                        processedPackages.add(pkg);
                    }
                }
            }

            // 2. Scan all installed applications for any additional apps (e.g. system tools, store apps)
            List<ApplicationInfo> appList = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (appList != null) {
                for (ApplicationInfo appInfo : appList) {
                    String pkg = appInfo.packageName;
                    if (processedPackages.contains(pkg)) continue;

                    Intent launch = pm.getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        String label = appInfo.loadLabel(pm).toString();
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        trainedApps.add(new AppEntry(label, pkg, launch));
                        processedPackages.add(pkg);
                    }
                }
            }

            isTrained = true;
            Log.i(TAG, "Successfully trained " + trainedApps.size() + " installed apps on device.");
        } catch (Exception e) {
            Log.e(TAG, "Error training installed apps: " + e.getMessage());
        }
    }

    /**
     * Finds and launches an installed Android app by friendly voice target.
     * Guaranteed NEVER to fall back to Google Search when user asks to open an app.
     */
    public boolean launchApp(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        if (!isTrained || trainedApps.isEmpty()) {
            trainInstalledApps();
        }

        String query = cleanQuery(target);
        String queryNoSpace = query.replace(" ", "");

        AppEntry bestMatch = null;
        int bestScore = 0;

        for (AppEntry app : trainedApps) {
            int score = calculateMatchScore(app, query, queryNoSpace);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = app;
                if (score >= 100) break; // Exact match
            }
        }

        // If match found with high confidence
        if (bestMatch != null && bestScore >= 55) {
            try {
                Log.i(TAG, "Launching trained app: " + bestMatch.label + " (" + bestMatch.packageName + ") score: " + bestScore);
                context.startActivity(bestMatch.launchIntent);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch " + bestMatch.packageName + ": " + e.getMessage());
            }
        }

        // Direct package lookup fallback
        String directPkg = getCommonPackage(query);
        if (directPkg != null) {
            Intent intent = pm.getLaunchIntentForPackage(directPkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        }

        Log.w(TAG, "No installed app matched query: '" + target + "'. Best score: " + bestScore);
        return false;
    }

    private int calculateMatchScore(AppEntry app, String query, String queryNoSpace) {
        // 1. Exact clean label match (e.g. "ajio" == "ajio")
        if (app.cleanLabel.equals(query)) return 100;

        // 2. Exact primary word match (e.g. "ajio" in "ajio online shopping")
        if (app.primaryName.equals(query)) return 98;

        // 3. Exact match with spaces stripped (e.g. user said "a jio", app is "ajio")
        if (app.cleanLabel.replace(" ", "").equals(queryNoSpace)) return 95;
        if (app.primaryName.equals(queryNoSpace)) return 95;

        // 4. Aliases and phonetic matches
        if (app.aliases.contains(query) || app.aliases.contains(queryNoSpace)) return 92;

        // 5. Package name contains query (e.g. com.ril.ajio contains "ajio")
        if (app.packageName.toLowerCase().endsWith("." + query) || 
            app.packageName.toLowerCase().contains("." + query + ".") ||
            app.packageName.toLowerCase().contains(queryNoSpace)) {
            return 90;
        }

        // 6. Label starts with query (e.g. "ajio" starts "ajio online shopping")
        if (app.cleanLabel.startsWith(query) || query.startsWith(app.cleanLabel)) return 85;

        // 7. Token word match
        for (String w : app.cleanLabel.split("\\s+")) {
            if (w.equals(query)) return 80;
        }

        // 8. Substring contains
        if (app.cleanLabel.contains(query)) return 70;

        // 9. Fuzzy Levenshtein similarity
        if (query.length() >= 4 && app.primaryName.length() >= 4) {
            double sim = calculateSimilarity(app.primaryName, query);
            if (sim >= 0.75) return (int) (sim * 80);
        }

        return 0;
    }

    private String cleanQuery(String target) {
        return target.toLowerCase()
            .replaceAll("\\b(open|launch|start|run|play|app|the|an|a|please|can you|could you)\\b", " ")
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private double calculateSimilarity(String s1, String s2) {
        int longer = Math.max(s1.length(), s2.length());
        if (longer == 0) return 1.0;
        int distance = levenshtein(s1, s2);
        return (longer - distance) / (double) longer;
    }

    private int levenshtein(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                    a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    /**
     * Performs an explicit web search on Google (only when requested).
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
     * Writes text note to local file.
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
