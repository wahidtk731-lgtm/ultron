package com.ultron.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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

    private static final String TAG = "UltronMainActivity";
    private static final int PERMISSION_REQ_RECORD_AUDIO = 101;
    private static final int REQ_CODE_VOICE_INTENT = 102;
    private static final int REQ_CODE_OVERLAY_PERM = 103;

    private WebView webView;
    private UltronIndependentVoiceInput voiceInput;
    private UltronIntentEngine intentEngine;
    private AppLauncherAndroid appLauncher;
    private TTSManager ttsManager;
    private Handler mainHandler;
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    android.util.Log.e("ULTRON_CRASH", "FATAL CRASH:", throwable);
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    throwable.printStackTrace(pw);
                    String stack = sw.toString();
                    java.io.File crashFile = new java.io.File("/storage/emulated/0/Project/ultron_crash.log");
                    java.io.FileWriter fw = new java.io.FileWriter(crashFile);
                    fw.write("UNCAUGHT_EXCEPTION:\n" + stack);
                    fw.close();
                } catch (Throwable ignored) {}
            }
        });

        super.onCreate(savedInstanceState);

        try {
            mainHandler = new Handler(Looper.getMainLooper());
            intentEngine = new UltronIntentEngine();
            appLauncher = new AppLauncherAndroid(this);
            ttsManager = new TTSManager(this, this);

            configureEdgeToEdgeWindow();
            setupWebView();
            checkPermissionsAndInit();
        } catch (Throwable t) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                t.printStackTrace(pw);
                java.io.File crashFile = new java.io.File("/storage/emulated/0/Project/ultron_crash.log");
                java.io.FileWriter fw = new java.io.FileWriter(crashFile);
                fw.write("ON_CREATE_CRASH:\n" + sw.toString());
                fw.close();
            } catch (Throwable ignored) {}
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
            } catch (Throwable ignored) {}
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
        settings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new UltronWebAppInterface(), "AndroidUltron");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        boolean isDefault = isDefaultAssistant();
                        webView.evaluateJavascript(String.format("if(window.onAssistantCheck) window.onAssistantCheck(%b);", isDefault), null);
                        webView.evaluateJavascript("if(window.updateRecentApps) window.updateRecentApps();", null);

                        if (isAssistIntent(getIntent()) && hasAudioPermission()) {
                            startListening();
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
                } catch (Throwable ignored) {}
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
        } catch (Exception ignored) {}
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
                isListening = true;
                updateWebStatus("listening", "Listening... Speak your command");
            }

            @Override
            public void onBeginningOfSpeech() {
                isListening = true;
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
                    String safe = partialText.replace("'", "\\'");
                    webView.evaluateJavascript(String.format("if(window.onPartialSpeech) window.onPartialSpeech('%s');", safe), null);
                }
            }

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                updateWebStatus("processing", "Thinking...");
            }

            @Override
            public void onResult(String recognizedText) {
                isListening = false;
                processCommandInternal(recognizedText);
            }

            @Override
            public void onError(String errorMessage) {
                isListening = false;
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

        if (commandBody.isEmpty()) {
            speakReply("Yes, I'm listening. What would you like me to do?", "Listening...");
            return;
        }

        List<String> subCommands = intentEngine.splitCompoundCommands(normalized);
        if (subCommands.size() <= 1) {
            UltronIntentEngine.ActionCommand action = intentEngine.classifyCommand(commandBody);
            String reply = executeAction(action);
            sendWebReply(rawText, reply);
            speakReply(reply, rawText);
        } else {
            executeCompoundCommandsStepByStep(subCommands, rawText);
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
                appLauncher.sendMediaKey(85);
                return "Playing music";

            case "media_pause":
                appLauncher.sendMediaKey(85);
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
                boolean crOk = appLauncher.createFile(action.entity);
                return crOk ? ("Created file " + action.entity) : "Could not create file";

            case "edit_file":
                boolean edOk = appLauncher.openFile(action.entity);
                return edOk ? ("Opening file " + action.entity) : "Could not open file";

            case "search_web":
                if (action.entity.isEmpty()) return "What would you like me to search for?";
                appLauncher.searchWeb(action.entity);
                return "Searching web for " + action.entity;

            case "write_file":
                if (action.entity.isEmpty()) return "What should I write down?";
                appLauncher.writeNote(action.entity);
                return "Saved note: " + action.entity;

            case "exit":
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }, 1200);
                return "Going offline. Goodbye!";

            default:
                boolean ok = appLauncher.launchApp(action.raw);
                if (!ok) {
                    appLauncher.searchWeb(action.raw);
                    return "Searching web for " + action.raw;
                }
                return "Opening " + action.raw;
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
                    webView.evaluateJavascript(String.format("window.onStatusUpdate('%s', '%s');", state, message), null);
                }
            }
        });
    }

    private void sendWebReply(final String query, final String reply) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(String.format("window.onAssistantReply('%s', '%s');", 
                        query.replace("'", "\\'"), reply.replace("'", "\\'")), null);
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
        if (voiceInput != null) {
            voiceInput.destroy();
            voiceInput = null;
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
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
                ttsManager.speak(text != null && !text.isEmpty() ? text : "Hello! I am Ultron, your personal assistant.");
            }
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
    }
}
