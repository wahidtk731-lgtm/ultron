package com.ultron.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main Activity for Ultron Assistant on Android.
 * Renders the Pixel-perfect Google Gemini Stadium Capsule HUD
 * as an edge-to-edge translucent bottom popup.
 * Wakes up instantly on center button long-press or app open.
 */
public class MainActivity extends Activity implements TTSManager.TTSListener {

    private static final int PERMISSION_REQ_RECORD_AUDIO = 101;
    private static final int REQ_CODE_VOICE_INTENT = 102;
    private static final int REQ_CODE_OVERLAY_PERM = 103;
    private static final boolean AUTO_LISTEN_ON_OPEN = true;

    private WebView webView;
    private UltronIndependentVoiceInput voiceInput;
    private UltronIntentEngine intentEngine;
    private AppLauncherAndroid appLauncher;
    private TTSManager ttsManager;
    private Handler mainHandler;
    private boolean shouldListenAfterSpeech = false;
    private boolean autoListenStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    android.util.Log.e("ULTRON_CRASH", "FATAL CRASH:", throwable);
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    throwable.printStackTrace(pw);
                    String stack = sw.toString();
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    java.io.File crashFile = new java.io.File(dir, "ultron_crash.log");
                    java.io.FileWriter fw = new java.io.FileWriter(crashFile);
                    fw.write("UNCAUGHT_EXCEPTION:\n" + stack);
                    fw.close();
                } catch (Throwable t) {
                    android.util.Log.w("ULTRON_CRASH", "Failed writing crash log: " + t.getMessage());
                } finally {
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, throwable);
                    }
                }
            }
        });

        super.onCreate(savedInstanceState);

        try {
            UltronDebugLog.init(this);
            UltronDebugLog.log("UltronApp", "LOG TEST");
            String ver = "1.0";
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                ver = pi.versionName + " (" + pi.versionCode + ")";
            } catch (Throwable ignored) {}
            UltronDebugLog.log("UltronApp", "MainActivity onCreate - Ultron v" + ver + " on SDK " + Build.VERSION.SDK_INT);
            UltronDebugLog.log("UltronPermission", "RECORD_AUDIO permission state: " + hasAudioPermission());

            mainHandler = new Handler(Looper.getMainLooper());
            intentEngine = new UltronIntentEngine();
            appLauncher = new AppLauncherAndroid(this);
            ttsManager = new TTSManager(this, this);

            configureEdgeToEdgeWindow();
            setupWebView();
            checkPermissionsAndInit();

            UltronVoiceSetup.get(this).ensureReady();
            UltronVoiceSetup.get(this).addListener(new UltronVoiceSetup.Listener() {
                @Override
                public void onStateChanged(final String state, final int percent, final String message) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                String js = String.format(Locale.US,
                                    "if (window.onVoiceSetupUpdate) window.onVoiceSetupUpdate(%s, %d, %s);",
                                    JSONObject.quote(state), percent, JSONObject.quote(message));
                                webView.evaluateJavascript(js, null);
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                t.printStackTrace(pw);
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                java.io.File crashFile = new java.io.File(dir, "ultron_crash.log");
                java.io.FileWriter fw = new java.io.FileWriter(crashFile);
                fw.write("ON_CREATE_CRASH:\n" + sw.toString());
                fw.close();
            } catch (Throwable logEx) {
                android.util.Log.w("ULTRON_CRASH", "Failed writing onCreate crash log: " + logEx.getMessage());
            }
            Toast.makeText(this, "Ultron Init: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Center button held again while in memory -> immediately listen!
        startListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (FloatingHUDService.getInstance() != null) {
            FloatingHUDService.getInstance().setOverlayVisible(false);
        }
        if (webView != null) {
            boolean isDefault = isDefaultAssistant();
            webView.evaluateJavascript(String.format("if(window.onAssistantCheck) window.onAssistantCheck(%b);", isDefault), null);
            webView.evaluateJavascript("if(window.updateRecentApps) window.updateRecentApps();", null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (FloatingHUDService.getInstance() != null) {
            FloatingHUDService.getInstance().setOverlayVisible(true);
        }
    }

    private void configureEdgeToEdgeWindow() {
        Window window = getWindow();
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Window.class.getMethod("setDecorFitsSystemWindows", boolean.class).invoke(window, false);
            } catch (Throwable t) {
                android.util.Log.d("UltronMainActivity", "setDecorFitsSystemWindows unavailable: " + t.getMessage());
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }
    }

    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);

        webView.addJavascriptInterface(new UltronWebAppInterface(), "AndroidUltron");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("file:///android_asset/hud.html")) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        boolean isDefault = isDefaultAssistant();
                        webView.evaluateJavascript(String.format("if(window.onAssistantCheck) window.onAssistantCheck(%b);", isDefault), null);
                        webView.evaluateJavascript("if(window.updateRecentApps) window.updateRecentApps();", null);

                        if ((isAssistIntent(getIntent()) || AUTO_LISTEN_ON_OPEN) && hasAudioPermission()) {
                            if (!autoListenStarted) {
                                autoListenStarted = true;
                                startListening();
                            }
                        } else {
                            updateWebStatus("ready", "Ultron Ready • Tap mic to speak");
                        }
                    }
                }, 350);
            }
        });

        webView.loadUrl("file:///android_asset/hud.html");
        setContentView(webView);
    }

    public boolean isDefaultAssistant() {
        try {
            // 1. Check persistent confirmation
            android.content.SharedPreferences prefs = getSharedPreferences("ultron_prefs", MODE_PRIVATE);
            if (prefs.getBoolean("is_default_assistant", false) || prefs.getBoolean("assistant_prompt_dismissed", false)) {
                return true;
            }

            // 2. Check if triggered via Assist intent
            Intent intent = getIntent();
            if (intent != null) {
                String action = intent.getAction();
                if (Intent.ACTION_ASSIST.equals(action) || "android.intent.action.VOICE_ASSIST".equals(action)) {
                    prefs.edit().putBoolean("is_default_assistant", true).apply();
                    return true;
                }
            }

            // 3. Android 10+ RoleManager check (safely via reflection for Android 9)
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    Class<?> roleClass = Class.forName("android.app.role.RoleManager");
                    Object roleManager = getSystemService(roleClass);
                    if (roleManager != null) {
                        java.lang.reflect.Method isRoleHeld = roleClass.getMethod("isRoleHeld", String.class);
                        java.lang.reflect.Field roleAssistField = roleClass.getField("ROLE_ASSISTANT");
                        String roleAssistant = (String) roleAssistField.get(null);
                        boolean held = (Boolean) isRoleHeld.invoke(roleManager, roleAssistant);
                        if (held) {
                            prefs.edit().putBoolean("is_default_assistant", true).apply();
                            return true;
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.d("UltronMainActivity", "RoleManager check unavailable: " + t.getMessage());
                }
            }

            // 4. Secure settings query
            String defaultAssist = Settings.Secure.getString(getContentResolver(), "voice_interaction_service");
            if (defaultAssist != null && defaultAssist.toLowerCase().contains(getPackageName().toLowerCase())) {
                prefs.edit().putBoolean("is_default_assistant", true).apply();
                return true;
            }
            String assist = Settings.Secure.getString(getContentResolver(), "assistant");
            if (assist != null && assist.toLowerCase().contains(getPackageName().toLowerCase())) {
                prefs.edit().putBoolean("is_default_assistant", true).apply();
                return true;
            }
        } catch (Exception e) {
            android.util.Log.d("UltronMainActivity", "Secure settings assistant query failed: " + e.getMessage());
        }
        return false;
    }

    public boolean isAssistIntent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        return Intent.ACTION_ASSIST.equals(action) ||
               "android.intent.action.VOICE_ASSIST".equals(action) ||
               RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE.equals(action);
    }

    public void openDefaultAssistantSettings() {
        Intent intent = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Exception ex) {
                Intent appSettings = new Intent(Settings.ACTION_SETTINGS);
                appSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appSettings);
            }
        }
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
               checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void checkPermissionsAndInit() {
        if (!hasAudioPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_RECORD_AUDIO);
            }
        } else {
            initVoiceInput();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            UltronDebugLog.log("UltronPermission", "RECORD_AUDIO request result: granted=" + granted);
            if (granted) {
                initVoiceInput();
                startListening();
            } else {
                updateWebStatus("ready", "Microphone access denied. Tap mic or type below.");
            }
        }
    }

    private void initVoiceInput() {
        if (voiceInput != null) {
            voiceInput.destroy();
        }

        voiceInput = new UltronIndependentVoiceInput(this, new UltronIndependentVoiceInput.VoiceInputListener() {
            @Override
            public void onReady() {
                updateWebStatus("listening", "Listening... Speak your command");
            }

            @Override
            public void onBeginningOfSpeech() {
                updateWebStatus("listening", "Listening to voice...");
            }

            @Override
            public void onRmsChanged(float rmsDb) {
                if (webView != null) {
                    webView.evaluateJavascript(String.format(Locale.US, "if(window.onRmsUpdate) window.onRmsUpdate(%.1f);", rmsDb), null);
                }
            }

            @Override
            public void onPartialResult(String partialText) {
                if (webView != null && partialText != null) {
                    webView.evaluateJavascript("if(window.onPartialSpeech) window.onPartialSpeech(" + JSONObject.quote(partialText) + ");", null);
                }
            }

            @Override
            public void onEndOfSpeech() {
                updateWebStatus("processing", "Thinking...");
            }

            @Override
            public void onResult(String recognizedText) {
                processCommandInternal(recognizedText);
            }

            @Override
            public void onError(String errorMessage) {
                updateWebStatus("ready", errorMessage);
            }
        });
    }

    public void startListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!hasAudioPermission()) {
                    checkPermissionsAndInit();
                    return;
                }

                if (voiceInput == null) {
                    initVoiceInput();
                }

                if (voiceInput != null) {
                    voiceInput.startListening();
                }
            }
        });
    }

    public void stopListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (voiceInput != null) {
                    voiceInput.stopListening();
                }
                updateWebStatus("ready", "Ultron Ready");
            }
        });
    }

    public void openSystemVoiceSheet() {
        startListening();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_VOICE_INTENT && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                processCommandInternal(matches.get(0));
            }
        } else if (requestCode == REQ_CODE_OVERLAY_PERM) {
            if (canDrawOverlays()) {
                startFloatingService();
                Toast.makeText(this, "Ultron Floating Mode activated!", Toast.LENGTH_SHORT).show();
                finish(); // Close MainActivity so two layouts never appear simultaneously
            }
        }
    }

    private void processCommandInternal(String rawText) {
        String normalized = intentEngine.normalizeSpeech(rawText);
        String commandBody = intentEngine.stripWakeWords(normalized);
        String displayQuery = (normalized != null && !normalized.trim().isEmpty()) ? normalized.trim() : rawText;

        if (commandBody.isEmpty()) {
            shouldListenAfterSpeech = true;
            speakReply("Yes, I'm listening. What would you like me to do?", "Listening...");
            return;
        }

        List<String> subCommands = intentEngine.splitCompoundCommands(normalized);
        if (subCommands.size() <= 1) {
            UltronIntentEngine.ActionCommand action = intentEngine.classifyCommand(commandBody);
            String reply = executeAction(action);
            sendWebReply(displayQuery, reply);
            speakReply(reply, displayQuery);
        } else {
            executeCompoundCommandsStepByStep(subCommands, displayQuery);
        }
    }

    private void executeCompoundCommandsStepByStep(List<String> subCommands, String rawText) {
        List<UltronIntentEngine.ActionCommand> actions = new ArrayList<>();
        for (String sub : subCommands) {
            actions.add(intentEngine.classifyCommand(sub));
        }

        // Step 1: Execute first action immediately
        UltronIntentEngine.ActionCommand action1 = actions.get(0);
        final String reply1 = executeAction(action1);

        String preview = "Step 1: " + reply1 + " • Next: " + subCommands.get(1);
        sendWebReply(rawText, preview);
        updateWebStatus("processing", preview);

        final String fRawText = rawText;
        final List<UltronIntentEngine.ActionCommand> fActions = actions;

        // Step 2: Execute second action after 1.3 seconds delay for genuine multitasking
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                UltronIntentEngine.ActionCommand action2 = fActions.get(1);
                final String reply2 = executeAction(action2);

                if (fActions.size() > 2) {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            UltronIntentEngine.ActionCommand action3 = fActions.get(2);
                            String reply3 = executeAction(action3);
                            String finalCombined = "Step 1: " + reply1 + ", then " + reply2 + ", and " + reply3;
                            sendWebReply(fRawText, finalCombined);
                            speakReply(finalCombined, fRawText);
                        }
                    }, 1300);
                } else {
                    String finalCombined = reply1 + ", then " + reply2;
                    sendWebReply(fRawText, finalCombined);
                    speakReply(finalCombined, fRawText);
                }
            }
        }, 1300);
    }

    private String executeAction(UltronIntentEngine.ActionCommand action) {
        switch (action.intent) {
            case "open_settings":
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("openSettingsModal();", null);
                        }
                    }
                });
                return "Opening Ultron settings";

            case "open_system_settings":
                try {
                    Intent settingsIntent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settingsIntent);
                    return "Opening device settings";
                } catch (Exception e) {
                    return "Could not open device settings";
                }

            case "greet":
                return "Hello! Ultron is online and ready for your commands.";

            case "query_time":
                String time = new SimpleDateFormat("hh:mm a, EEEE, MMMM d", Locale.US).format(new Date());
                return "The current time is " + time;

            case "query_capabilities":
                return "I am Ultron, your local AI assistant. I can open applications, search the web, tell time, and take notes.";

            case "open_app":
                if (action.entity.isEmpty()) return "Which app would you like me to open?";
                boolean launched = appLauncher.launchApp(action.entity);
                return launched ? "Opening " + action.entity : "I could not find " + action.entity + " installed on your device.";

            case "close_app":
                boolean closed = appLauncher.closeApp(action.entity);
                return closed ? ("Closed " + (action.entity.isEmpty() ? "app" : action.entity)) : "Returning to home screen";

            case "wifi_on":
                appLauncher.setWifi(true);
                return "Turning on Wi-Fi";

            case "wifi_off":
                appLauncher.setWifi(false);
                return "Turning off Wi-Fi";

            case "bluetooth_on":
                appLauncher.setBluetooth(true);
                return "Turning on Bluetooth";

            case "bluetooth_off":
                appLauncher.setBluetooth(false);
                return "Turning off Bluetooth";

            case "flashlight_on":
                appLauncher.setFlashlight(true);
                return "Turning on flashlight";

            case "flashlight_off":
                appLauncher.setFlashlight(false);
                return "Turning off flashlight";

            case "volume_up":
                appLauncher.adjustVolume(1);
                return "Increasing volume";

            case "volume_down":
                appLauncher.adjustVolume(-1);
                return "Decreasing volume";

            case "volume_mute":
                appLauncher.adjustVolume(0);
                return "Muting volume";

            case "screenshot":
                appLauncher.takeScreenshot();
                return "Taking screenshot";

            case "media_play":
                appLauncher.sendMediaKey(126); // KEYCODE_MEDIA_PLAY
                return "Playing music";

            case "media_pause":
                appLauncher.sendMediaKey(127); // KEYCODE_MEDIA_PAUSE
                return "Pausing music";

            case "media_next":
                appLauncher.sendMediaKey(87);
                return "Playing next track";

            case "media_prev":
                appLauncher.sendMediaKey(88);
                return "Playing previous track";

            case "clear_notifications":
                appLauncher.clearNotifications();
                return "Cleared all notifications";

            case "status_bar":
                appLauncher.openStatusBar();
                return "Opening status bar notifications";

            case "quick_settings":
                appLauncher.openQuickSettings();
                return "Opening quick settings";

            case "floating_mode":
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        openFloatingMode();
                    }
                });
                return "Switching to floating bubble mode";

            case "recent_apps":
                boolean recOk = appLauncher.openRecentApp();
                return recOk ? "Opening recent apps" : "Showing recent apps";

            case "app_section":
                boolean secOk = appLauncher.openAppSection(action.entity);
                if (action.entity.contains("reels")) {
                    return secOk ? "Going to Reels section" : "Opening Instagram";
                } else if (action.entity.contains("shorts")) {
                    return secOk ? "Going to YouTube Shorts" : "Opening YouTube";
                }
                return secOk ? ("Navigating to " + action.entity) : ("Could not open " + action.entity);

            case "create_file":
                if (action.entity == null || action.entity.trim().isEmpty()) {
                    return "What should I name the file?";
                }
                boolean crOk = appLauncher.createFile(action.entity.trim());
                return crOk ? ("Created file " + action.entity.trim()) : "Could not create file";

            case "edit_file":
                if (action.entity == null || action.entity.trim().isEmpty()) {
                    return "Which file would you like me to open?";
                }
                boolean edOk = appLauncher.openFile(action.entity.trim());
                return edOk ? ("Opening file " + action.entity.trim()) : "Could not open file";

            case "search_web":
                if (action.entity == null || action.entity.trim().isEmpty()) return "What would you like me to search for?";
                if (!UltronIndependentVoiceInput.isNetworkConnected(this)) {
                    return "No internet connection.";
                }
                appLauncher.searchWeb(action.entity.trim());
                return "Searching web for " + action.entity.trim();

            case "write_file":
                if (action.entity == null || action.entity.trim().isEmpty()) return "What should I write down?";
                appLauncher.writeNote(action.entity.trim());
                return "Saved note: " + action.entity.trim();

            case "exit":
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }, 1200);
                return "Going offline. Goodbye!";

            default:
                if (action.raw != null && !action.raw.trim().isEmpty()) {
                    boolean ok = appLauncher.launchApp(action.raw.trim());
                    if (ok) return "Opening " + action.raw.trim();
                }
                return "I didn't quite catch that. Try asking to open an app, adjust settings, or search.";
        }
    }

    private void speakReply(String text, String query) {
        updateWebStatus("speaking", "Speaking response...");
        ttsManager.speak(text);
    }

    private void updateWebStatus(final String state, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    String s = state != null ? state : "";
                    String m = message != null ? message : "";
                    webView.evaluateJavascript("window.onStatusUpdate(" + JSONObject.quote(s) + ", " + JSONObject.quote(m) + ");", null);
                }
            }
        });
    }

    private void sendWebReply(final String query, final String reply) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    String q = query != null ? query : "";
                    String r = reply != null ? reply : "";
                    webView.evaluateJavascript("window.onAssistantReply(" + JSONObject.quote(q) + ", " + JSONObject.quote(r) + ");", null);
                }
            }
        });
    }

    @Override
    public void onSpeechStarted() {
        updateWebStatus("speaking", "Speaking...");
    }

    @Override
    public void onSpeechCompleted() {
        if (shouldListenAfterSpeech) {
            shouldListenAfterSpeech = false;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    startListening();
                }
            });
            return;
        }
        updateWebStatus("ready", "Ready");
    }

    public void startFloatingService() {
        Intent serviceIntent = new Intent(this, FloatingHUDService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    public void openFloatingMode() {
        if (!canDrawOverlays()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_CODE_OVERLAY_PERM);
            }
        } else {
            startFloatingService();
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (voiceInput != null) {
            voiceInput.destroy();
            voiceInput = null;
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
            ttsManager = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    public class UltronWebAppInterface {
        @JavascriptInterface
        public void startListening() {
            MainActivity.this.startListening();
        }

        @JavascriptInterface
        public void stopListening() {
            MainActivity.this.stopListening();
        }

        @JavascriptInterface
        public void setVoiceEngine(String engine) {
            if (voiceInput != null) {
                voiceInput.setEngine(engine);
            }
        }

        @JavascriptInterface
        public String getVoiceEngine() {
            return voiceInput != null ? voiceInput.getCurrentEngine() : "independent";
        }

        @JavascriptInterface
        public void launchSystemVoiceSheet() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    openSystemVoiceSheet();
                }
            });
        }

        @JavascriptInterface
        public void processCommand(String text) {
            processCommandInternal(text);
        }

        @JavascriptInterface
        public void dismiss() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void openDefaultAssistantSettings() {
            MainActivity.this.openDefaultAssistantSettings();
        }

        @JavascriptInterface
        public void openVoiceInputSettings() {
            MainActivity.this.openDefaultAssistantSettings();
        }

        @JavascriptInterface
        public void openOfflineSpeechSettings() {
            UltronIndependentVoiceInput.openOfflineSpeechSettings(MainActivity.this);
        }

        @JavascriptInterface
        public void saveVoiceSettings(float pitch, float rate) {
            if (ttsManager != null) {
                ttsManager.setPitch(pitch);
                ttsManager.setSpeechRate(rate);
            }
        }

        @JavascriptInterface
        public void setDefaultAssistantConfirmed() {
            getSharedPreferences("ultron_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("is_default_assistant", true)
                .putBoolean("assistant_prompt_dismissed", true)
                .apply();
        }

        @JavascriptInterface
        public void testVoice(String text) {
            if (ttsManager != null) {
                String phrase = (text != null && !text.trim().isEmpty()) ? text : "Systems online. I am listening.";
                ttsManager.speak(phrase);
            }
        }

        @JavascriptInterface
        public String getVoiceOptions() {
            return ttsManager != null ? ttsManager.getVoiceOptionsJson() : "[]";
        }

        @JavascriptInterface
        public void setVoiceName(String name) {
            if (ttsManager != null) {
                ttsManager.setVoiceName(name);
            }
        }

        @JavascriptInterface
        public void setVoiceStyle(String style) {
            if (ttsManager != null) {
                ttsManager.setVoiceStyle(style);
            }
        }

        @JavascriptInterface
        public String getVoiceStyle() {
            return ttsManager != null ? ttsManager.getVoiceStyle() : UltronVoiceProfile.STYLE_ULTRON;
        }

        @JavascriptInterface
        public String getVoiceStatusNote() {
            return ttsManager != null ? ttsManager.getVoiceStatusNote() : null;
        }

        @JavascriptInterface
        public void setTtsSafeMode(boolean enabled) {
            if (ttsManager != null) {
                ttsManager.setSafeMode(enabled);
            }
        }

        @JavascriptInterface
        public boolean isTtsSafeMode() {
            return ttsManager != null && ttsManager.isSafeMode();
        }

        @JavascriptInterface
        public String getVoiceSetupStatus() {
            UltronVoiceSetup setup = UltronVoiceSetup.get(MainActivity.this);
            try {
                JSONObject obj = new JSONObject();
                obj.put("state", setup.getState());
                obj.put("percent", setup.getPercent());
                obj.put("message", setup.getMessage());
                return obj.toString();
            } catch (Exception e) {
                return "{\"state\":\"NOT_STARTED\",\"percent\":0,\"message\":\"\"}";
            }
        }

        @JavascriptInterface
        public String getVoiceEngineMode() {
            return getSharedPreferences("ultron_prefs", MODE_PRIVATE)
                .getString("voice_engine_mode", "auto");
        }

        @JavascriptInterface
        public void setVoiceEngineMode(String mode) {
            if (mode != null && (mode.equals("auto") || mode.equals("offline_only"))) {
                getSharedPreferences("ultron_prefs", MODE_PRIVATE).edit()
                    .putString("voice_engine_mode", mode).apply();
                UltronDebugLog.log("UltronBridge", "Voice engine mode set to: " + mode);
            }
        }

        @JavascriptInterface
        public void retryVoiceSetup() {
            UltronVoiceSetup.get(MainActivity.this).retry();
        }

        @JavascriptInterface
        public String getDebugLog() {
            return UltronDebugLog.getLastLines(80);
        }

        @JavascriptInterface
        public void clearDebugLog() {
            UltronDebugLog.clear();
        }

        @JavascriptInterface
        public boolean copyToClipboard(final String text) {
            final boolean[] result = new boolean[]{false};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            android.content.ClipData clip = android.content.ClipData.newPlainText("Ultron Debug Log", text != null ? text : "");
                            clipboard.setPrimaryClip(clip);
                            android.widget.Toast.makeText(MainActivity.this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
                            result[0] = true;
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("UltronBridge", "Failed to copy to clipboard: " + t.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });
            try {
                latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
            return result[0];
        }

        @JavascriptInterface
        public void shareText(final String text) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent sendIntent = new Intent(Intent.ACTION_SEND);
                        sendIntent.setType("text/plain");
                        sendIntent.putExtra(Intent.EXTRA_TEXT, text != null ? text : "");
                        Intent chooser = Intent.createChooser(sendIntent, "Share Ultron Log");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(chooser);
                    } catch (Throwable t) {
                        android.util.Log.w("UltronBridge", "Failed to share text: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void toggleDockPosition() {
            // No-op in full screen activity mode
        }

        @JavascriptInterface
        public String getRecentAppName() {
            return appLauncher != null ? appLauncher.getMostRecentAppName() : "Recent App";
        }

        @JavascriptInterface
        public void openRecentApp() {
            if (appLauncher != null) {
                appLauncher.openRecentApp();
            }
        }

        @JavascriptInterface
        public void closeActiveApp() {
            if (appLauncher != null) {
                appLauncher.closeApp("");
            }
        }

        @JavascriptInterface
        public void refreshInstalledApps() {
            if (appLauncher != null) {
                appLauncher.trainInstalledApps();
            }
        }

        @JavascriptInterface
        public String getRecentAppsJson() {
            return appLauncher != null ? appLauncher.getRecentAppsJson() : "[]";
        }

        @JavascriptInterface
        public void launchAppByPackage(String packageName) {
            if (appLauncher != null) {
                appLauncher.launchPackage(packageName);
            }
        }

        @JavascriptInterface
        public void toggleWifi(boolean enable) {
            if (appLauncher != null) {
                appLauncher.setWifi(enable);
            }
        }

        @JavascriptInterface
        public void toggleBluetooth(boolean enable) {
            if (appLauncher != null) {
                appLauncher.setBluetooth(enable);
            }
        }

        @JavascriptInterface
        public void clearNotifications() {
            if (appLauncher != null) {
                appLauncher.clearNotifications();
            }
        }

        @JavascriptInterface
        public void toggleFloatingOverlay() {
            openFloatingMode();
        }

        @JavascriptInterface
        public void closeFloatingOverlay() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void minimizeToBubble() {
            openFloatingMode();
        }

        @JavascriptInterface
        public void expandFromBubble() {
            // No-op in activity mode
        }

        @JavascriptInterface
        public void toggleBubbleMode() {
            openFloatingMode();
        }

        @JavascriptInterface
        public void setWindowHeight(int dp) {
            // No-op in activity mode
        }

        @JavascriptInterface
        public void requestInputFocus(boolean focus) {
            // No-op in activity mode
        }

        @JavascriptInterface
        public void requestInputFocus() {
            // No-op in activity mode
        }
    }
}
