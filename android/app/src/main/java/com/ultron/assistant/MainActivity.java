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
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private UltronIntentEngine intentEngine;
    private AppLauncherAndroid appLauncher;
    private TTSManager ttsManager;
    private Handler mainHandler;
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());
        intentEngine = new UltronIntentEngine();
        appLauncher = new AppLauncherAndroid(this);
        ttsManager = new TTSManager(this, this);

        configureEdgeToEdgeWindow();
        setupWebView();
        checkPermissionsAndInit();
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
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
                mainHandler.postDelayed(() -> {
                    boolean isDefault = isDefaultAssistant();
                    webView.evaluateJavascript(String.format("if(window.onAssistantCheck) window.onAssistantCheck(%b);", isDefault), null);
                    webView.evaluateJavascript("if(window.updateRecentApps) window.updateRecentApps();", null);

                    if (hasAudioPermission()) {
                        startListening();
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

            // 3. Android 10+ RoleManager check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.app.role.RoleManager roleManager = getSystemService(android.app.role.RoleManager.class);
                if (roleManager != null && roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                    prefs.edit().putBoolean("is_default_assistant", true).apply();
                    return true;
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
        } catch (Exception ignored) {}
        return false;
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
            initSpeechRecognizer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initSpeechRecognizer();
                startListening();
            } else {
                updateWebStatus("ready", "Microphone access denied. Tap mic or type below.");
            }
        }
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Direct SpeechRecognizer not available. Will use Recognizer Intent fallback.");
            return;
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    updateWebStatus("listening", "Listening... Speak your command");
                }

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    isListening = false;
                    updateWebStatus("processing", "Thinking...");
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    String message = "Tap mic to speak";

                    switch (error) {
                        case SpeechRecognizer.ERROR_AUDIO:
                            message = "Audio recording error. Tap mic to retry.";
                            break;
                        case SpeechRecognizer.ERROR_CLIENT:
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                            initSpeechRecognizer();
                            message = "Ready • Tap mic to speak";
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            message = "Microphone permission needed";
                            checkPermissionsAndInit();
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            message = "Network error. Tap mic to retry.";
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            message = "Didn't hear that. Tap mic to retry.";
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            message = "Listening timed out. Tap mic to speak.";
                            break;
                    }
                    updateWebStatus("ready", message);
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                    if (matches != null && !matches.isEmpty()) {
                        String recognizedText = matches.get(0);
                        processCommandInternal(recognizedText);
                    } else {
                        updateWebStatus("ready", "Didn't catch that. Tap mic to retry.");
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial = partialResults != null ? partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                    if (partial != null && !partial.isEmpty()) {
                        updateWebStatus("listening", "\"" + partial.get(0) + "...\"");
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "SpeechRecognizer initialization failed: " + e.getMessage());
        }
    }

    public void startListening() {
        mainHandler.post(() -> {
            if (!hasAudioPermission()) {
                checkPermissionsAndInit();
                return;
            }

            if (speechRecognizer == null) {
                initSpeechRecognizer();
            }

            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                    speechRecognizer.startListening(recognizerIntent);
                } catch (Exception e) {
                    initSpeechRecognizer();
                    try {
                        speechRecognizer.startListening(recognizerIntent);
                    } catch (Exception ex) {
                        updateWebStatus("ready", "Mic reset. Tap to speak.");
                    }
                }
            } else {
                updateWebStatus("ready", "Ultron mic ready. Tap to speak.");
            }
        });
    }

    public void stopListening() {
        mainHandler.post(() -> {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.stopListening();
                } catch (Exception ignored) {}
            }
        });
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
        String reply1 = executeAction(action1);

        String preview = "Step 1: " + reply1 + " • Next: " + subCommands.get(1);
        sendWebReply(rawText, preview);
        updateWebStatus("processing", preview);

        // Step 2: Execute second action after 1.3 seconds delay for genuine multitasking
        mainHandler.postDelayed(() -> {
            UltronIntentEngine.ActionCommand action2 = actions.get(1);
            String reply2 = executeAction(action2);

            if (actions.size() > 2) {
                mainHandler.postDelayed(() -> {
                    UltronIntentEngine.ActionCommand action3 = actions.get(2);
                    String reply3 = executeAction(action3);
                    String finalCombined = "Step 1: " + reply1 + ", then " + reply2 + ", and " + reply3;
                    sendWebReply(rawText, finalCombined);
                    speakReply(finalCombined, rawText);
                }, 1300);
            } else {
                String finalCombined = reply1 + ", then " + reply2;
                sendWebReply(rawText, finalCombined);
                speakReply(finalCombined, rawText);
            }
        }, 1300);
    }

    private String executeAction(UltronIntentEngine.ActionCommand action) {
        switch (action.intent) {
            case "open_settings":
                mainHandler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("openSettingsModal();", null);
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
                mainHandler.post(this::openFloatingMode);
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
                mainHandler.postDelayed(this::finish, 1200);
                return "Going offline. Goodbye!";

            default:
                boolean ok = appLauncher.launchApp(action.raw);
                return ok ? "Opening " + action.raw : "I could not find " + action.raw + " installed on your device.";
        }
    }

    private void speakReply(String text, String query) {
        updateWebStatus("speaking", "Speaking response...");
        ttsManager.speak(text);
    }

    private void updateWebStatus(String state, String message) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(String.format("window.onStatusUpdate('%s', '%s');", state, message), null);
            }
        });
    }

    private void sendWebReply(String query, String reply) {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(String.format("window.onAssistantReply('%s', '%s');", 
                    query.replace("'", "\\'"), reply.replace("'", "\\'")), null);
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
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
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
        public void processCommand(String text) {
            processCommandInternal(text);
        }

        @JavascriptInterface
        public void dismiss() {
            mainHandler.post(() -> finish());
        }

        @JavascriptInterface
        public void openDefaultAssistantSettings() {
            MainActivity.this.openDefaultAssistantSettings();
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
