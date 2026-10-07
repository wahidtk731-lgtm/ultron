package com.ultron.assistant;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Intelligent Installed App Training & Launch Engine for Ultron Android.
 * Trains itself on every installed app on the user's phone at startup,
 * learning phonetic sound-alikes, aliases, and package identifiers.
 */
public class AppLauncherAndroid {

    private static final String TAG = "UltronAppLauncher";
    private final Context context;
    private final PackageManager pm;
    private final List<AppEntry> recentApps = new ArrayList<>();

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
            loadRecentApps();
            Log.i(TAG, "Successfully trained " + trainedApps.size() + " installed apps. Recent: " + recentApps.size());
        } catch (Exception e) {
            Log.e(TAG, "Error training installed apps: " + e.getMessage());
        }
    }

    private boolean isPackageInstalledAndValid(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return ai.enabled && pm.getLaunchIntentForPackage(packageName) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
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

        // Iterate through trained apps and purge any that were uninstalled
        Iterator<AppEntry> iterator = trainedApps.iterator();
        while (iterator.hasNext()) {
            AppEntry app = iterator.next();
            if (!isPackageInstalledAndValid(app.packageName)) {
                iterator.remove(); // REMOVE uninstalled app!
                continue;
            }

            int score = calculateMatchScore(app, query, queryNoSpace);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = app;
                if (score >= 100) break; // Exact match
            }
        }

        // If match found with high confidence
        if (bestMatch != null && bestScore >= 55) {
            if (!isPackageInstalledAndValid(bestMatch.packageName)) {
                trainedApps.remove(bestMatch);
                return false;
            }
            try {
                Log.i(TAG, "Launching trained app: " + bestMatch.label + " (" + bestMatch.packageName + ") score: " + bestScore);
                context.startActivity(bestMatch.launchIntent);
                recordRecentApp(bestMatch);
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch " + bestMatch.packageName + ": " + e.getMessage());
                trainedApps.remove(bestMatch);
            }
        }

        // Direct package lookup fallback
        String directPkg = getCommonPackage(query);
        if (directPkg != null) {
            Intent intent = pm.getLaunchIntentForPackage(directPkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                recordRecentApp(new AppEntry(query, directPkg, intent));
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

    private static final String PREF_NAME = "ultron_recent_apps";
    private static final String KEY_RECENT = "recent_packages";

    public void loadRecentApps() {
        synchronized (recentApps) {
            recentApps.clear();
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_RECENT, "");
            if (saved != null && !saved.trim().isEmpty()) {
                String[] pkgs = saved.split(",");
                for (String pkg : pkgs) {
                    pkg = pkg.trim();
                    if (pkg.isEmpty()) continue;
                    AppEntry entry = findAppByPackage(pkg);
                    if (entry != null && !recentApps.contains(entry)) {
                        recentApps.add(entry);
                    }
                }
            }

            // Seed with top essential everyday apps if fewer than 4 recent apps
            if (recentApps.size() < 4) {
                String[] commonKeywords = {"chrome", "browser", "youtube", "camera", "files", "documents", "gallery", "settings", "messages", "whatsapp"};
                for (String kw : commonKeywords) {
                    for (AppEntry app : trainedApps) {
                        if ((app.cleanLabel.contains(kw) || app.packageName.contains(kw)) && !recentApps.contains(app)) {
                            recentApps.add(app);
                            if (recentApps.size() >= 5) break;
                        }
                    }
                    if (recentApps.size() >= 5) break;
                }
            }

            // Still fewer than 4? Add first available user applications
            if (recentApps.size() < 4) {
                for (AppEntry app : trainedApps) {
                    if (!recentApps.contains(app)) {
                        recentApps.add(app);
                        if (recentApps.size() >= 4) break;
                    }
                }
            }
        }
    }

    private void saveRecentApps() {
        synchronized (recentApps) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < recentApps.size() && i < 10; i++) {
                if (sb.length() > 0) sb.append(",");
                sb.append(recentApps.get(i).packageName);
            }
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_RECENT, sb.toString()).apply();
        }
    }

    public AppEntry findAppByPackage(String packageName) {
        if (packageName == null) return null;
        for (AppEntry app : trainedApps) {
            if (app.packageName.equalsIgnoreCase(packageName)) {
                return app;
            }
        }
        return null;
    }

    public void recordRecentApp(AppEntry app) {
        if (app == null) return;
        synchronized (recentApps) {
            recentApps.removeIf(a -> a.packageName.equalsIgnoreCase(app.packageName));
            recentApps.add(0, app);
            if (recentApps.size() > 10) {
                recentApps.remove(recentApps.size() - 1);
            }
            saveRecentApps();
        }
    }

    public boolean launchPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            Intent launch = pm.getLaunchIntentForPackage(packageName.trim());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                AppEntry entry = findAppByPackage(packageName.trim());
                if (entry != null) {
                    recordRecentApp(entry);
                } else {
                    recordRecentApp(new AppEntry(packageName, packageName, launch));
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Launch package failed: " + e.getMessage());
        }
        return false;
    }

    public String getRecentAppsJson() {
        synchronized (recentApps) {
            if (recentApps.isEmpty()) {
                loadRecentApps();
            }
            JSONArray arr = new JSONArray();
            int count = 0;
            for (AppEntry app : recentApps) {
                if (count >= 6) break;
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", app.label);
                    obj.put("cleanName", app.primaryName);
                    obj.put("packageName", app.packageName);
                    arr.put(obj);
                    count++;
                } catch (Exception ignored) {}
            }
            return arr.toString();
        }
    }

    public boolean openRecentApp() {
        synchronized (recentApps) {
            if (recentApps.isEmpty()) {
                loadRecentApps();
            }
            if (!recentApps.isEmpty()) {
                AppEntry recent = recentApps.get(0);
                if (isPackageInstalledAndValid(recent.packageName)) {
                    try {
                        context.startActivity(recent.launchIntent);
                        return true;
                    } catch (Exception ignored) {}
                }
            }
        }
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(homeIntent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getMostRecentAppName() {
        synchronized (recentApps) {
            if (recentApps.isEmpty()) {
                loadRecentApps();
            }
            if (!recentApps.isEmpty()) {
                return recentApps.get(0).label;
            }
        }
        return "Recent App";
    }

    public boolean closeApp(String target) {
        String query = cleanQuery(target == null ? "" : target);
        if (query.isEmpty() || query.equals("app") || query.equals("this app") || query.equals("current app")) {
            try {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        AppEntry match = null;
        for (AppEntry app : trainedApps) {
            if (app.cleanLabel.equalsIgnoreCase(query) || app.aliases.contains(query)) {
                match = app;
                break;
            }
        }

        String pkg = match != null ? match.packageName : getCommonPackage(query);
        if (pkg != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(pkg);
                }
            } catch (Exception ignored) {}
        }

        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(homeIntent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setWifi(boolean enable) {
        // 1. Direct WifiManager call
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                boolean ok = wm.setWifiEnabled(enable);
                if (ok) return true;
            }
        } catch (Exception ignored) {}

        // 2. Shell/CMD fallback
        try {
            Process p1 = Runtime.getRuntime().exec(new String[]{"cmd", "wifi", "set-wifi-enabled", enable ? "enabled" : "disabled"});
            if (p1.waitFor() == 0) return true;
        } catch (Exception ignored) {}

        try {
            Process p2 = Runtime.getRuntime().exec(new String[]{"svc", "wifi", enable ? "enable" : "disable"});
            if (p2.waitFor() == 0) return true;
        } catch (Exception ignored) {}

        // 3. System UI Panel / Settings fallback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(panelIntent);
                return true;
            }
        } catch (Exception ignored) {}

        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {}

        return false;
    }

    public boolean setBluetooth(boolean enable) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                if (enable && !adapter.isEnabled()) {
                    boolean ok = adapter.enable();
                    if (ok) return true;
                } else if (!enable && adapter.isEnabled()) {
                    boolean ok = adapter.disable();
                    if (ok) return true;
                }
            }
        } catch (Exception ignored) {}

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cmd", "bluetooth_manager", enable ? "enable" : "disable"});
            if (p.waitFor() == 0) return true;
        } catch (Exception ignored) {}

        try {
            Process p2 = Runtime.getRuntime().exec(new String[]{"svc", "bluetooth", enable ? "enable" : "disable"});
            if (p2.waitFor() == 0) return true;
        } catch (Exception ignored) {}

        try {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean clearNotifications() {
        boolean cleared = UltronNotificationService.clearAll();
        if (cleared) return true;

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cmd", "notification", "cancel_all"});
            if (p.waitFor() == 0) return true;
        } catch (Exception ignored) {}

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancelAll();
            }
        } catch (Exception ignored) {}

        if (!UltronNotificationService.isPermissionGranted(context)) {
            try {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (Exception ignored) {}
        }
        return cleared;
    }

    public boolean openStatusBar() {
        try {
            Object sbservice = context.getSystemService("statusbar");
            Class<?> statusbarManager = Class.forName("android.app.StatusBarManager");
            Method showsb = statusbarManager.getMethod("expandNotificationsPanel");
            showsb.invoke(sbservice);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean openQuickSettings() {
        try {
            Object sbservice = context.getSystemService("statusbar");
            Class<?> statusbarManager = Class.forName("android.app.StatusBarManager");
            Method showsb = statusbarManager.getMethod("expandSettingsPanel");
            showsb.invoke(sbservice);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean createFile(String filename) {
        try {
            String name = (filename == null || filename.trim().isEmpty()) ? "ultron_file.txt" : filename.trim();
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, name);
            if (!file.exists()) {
                file.createNewFile();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Create file failed: " + e.getMessage());
            return false;
        }
    }

    public boolean openFile(String filename) {
        try {
            String name = (filename == null || filename.trim().isEmpty()) ? "ultron_notes.txt" : filename.trim();
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, name);
            if (!file.exists()) {
                file.createNewFile();
            }
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(Uri.fromFile(file), "text/plain");
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(viewIntent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Open file failed: " + e.getMessage());
            return false;
        }
    }

    public boolean writeFile(String filename, String content) {
        try {
            String name = (filename == null || filename.trim().isEmpty()) ? "ultron_notes.txt" : filename.trim();
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File file = new File(dir, name);
            FileWriter writer = new FileWriter(file, true);
            writer.write(content + "\n");
            writer.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Write file failed: " + e.getMessage());
            return false;
        }
    }

    public boolean writeNote(String content) {
        return writeFile("ultron_notes.txt", new Date() + ": " + content);
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
