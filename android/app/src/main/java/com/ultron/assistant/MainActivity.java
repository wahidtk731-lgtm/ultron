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
 * as an edge-to-edge translucent bottom popup and bridges Native
 * Speech Recognition, Intent Routing, and TTS.
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

        configureEdgeToEdgeWindow();

        mainHandler = new Handler(Looper.getMainLooper());
        intentEngine = new UltronIntentEngine();
        appLauncher = new AppLauncherAndroid(this);
        ttsManager = new TTSManager(this, this);

        setupWebView();
        checkPermissionsAndInit();
    }

    /**
     * Configures the window to be a full-screen transparent edge-to-edge overlay.
     * The HTML/CSS will draw the dimmed translucent backdrop and bottom capsule.
     */
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
                // Auto-listen on open (like Gemini on Android) if microphone permission is already granted
                mainHandler.postDelayed(() -> {
                    if (hasAudioPermission()) {
                        startListening();
                    }
                }, 350);
            }
        });

        webView.loadUrl("file:///android_asset/hud.html");
        setContentView(webView);
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
               checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
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
                updateWebStatus("ready", "Microphone access denied. Tap mic or type command.");
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
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    updateWebStatus("listening", "Listening for command...");
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
                    String message = "Tap mic or type command";

                    switch (error) {
                        case SpeechRecognizer.ERROR_AUDIO:
                            message = "Audio recording error. Check mic.";
                            break;
                        case SpeechRecognizer.ERROR_CLIENT:
                            // Fallback to Google Voice dialog
                            startVoiceRecognitionFallback();
                            return;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            message = "Microphone permission required";
                            checkPermissionsAndInit();
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            message = "Network speech timeout. Tap mic to retry.";
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            message = "Didn't hear that. Tap mic to retry.";
                            break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                            initSpeechRecognizer();
                            message = "Ready";
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            message = "No speech detected. Tap mic to speak.";
                            break;
                    }
                    updateWebStatus("ready", message);
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String recognizedText = matches.get(0);
                        processCommandInternal(recognizedText);
                    } else {
                        updateWebStatus("ready", "Didn't catch that. Tap mic to retry.");
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) {
                        updateWebStatus("listening", "\"" + partial.get(0) + "...\"");
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer: " + e.getMessage());
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
                    Log.e(TAG, "Error starting SpeechRecognizer: " + e.getMessage());
                    startVoiceRecognitionFallback();
                }
            } else {
                startVoiceRecognitionFallback();
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

    /**
     * Fallback to System Voice Input Activity (works across 100% of Android devices)
     */
    private void startVoiceRecognitionFallback() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Ultron Assistant...");
            startActivityForResult(intent, REQ_CODE_VOICE_INTENT);
        } catch (Exception e) {
            updateWebStatus("ready", "Voice recognizer unavailable. Type command below.");
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingService();
            } else {
                Toast.makeText(this, "Overlay permission is needed for Floating HUD mode", Toast.LENGTH_SHORT).show();
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

        // Split multifunction / compound commands
        List<String> subCommands = intentEngine.splitCompoundCommands(normalized);
        StringBuilder fullReply = new StringBuilder();

        for (String sub : subCommands) {
            UltronIntentEngine.ActionCommand action = intentEngine.classifyCommand(sub);
            String reply = executeAction(action);
            if (fullReply.length() > 0) fullReply.append(" and ");
            fullReply.append(reply);
        }

        String finalReply = fullReply.toString();
        sendWebReply(rawText, finalReply);
        speakReply(finalReply, rawText);
    }

    private String executeAction(UltronIntentEngine.ActionCommand action) {
        switch (action.intent) {
            case "greet":
                return "Hello! Ultron is online and ready for your commands.";

            case "query_time":
                String time = new SimpleDateFormat("hh:mm a, EEEE, MMMM d", Locale.US).format(new Date());
                return "The current time is " + time;

            case "query_capabilities":
                return "I am Ultron, your local AI assistant. I can open applications, search the web, tell the time, and write notes.";

            case "open_app":
                if (action.entity.isEmpty()) return "Which app would you like me to open?";
                boolean launched = appLauncher.launchApp(action.entity);
                return launched ? "Opening " + action.entity : "Could not find " + action.entity;

            case "close_app":
                return "Returning to home screen";

            case "search_web":
                if (action.entity.isEmpty()) return "What would you like me to search for?";
                appLauncher.searchWeb(action.entity);
                return "Searching Google for " + action.entity;

            case "write_file":
                if (action.entity.isEmpty()) return "What should I write down?";
                appLauncher.writeNote(action.entity);
                return "Saved note: " + action.entity;

            case "exit":
                mainHandler.postDelayed(this::finish, 1200);
                return "Going offline. Goodbye!";

            default:
                boolean ok = appLauncher.launchApp(action.raw);
                return ok ? "Opening " + action.raw : "Command executed: " + action.raw;
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

    public void toggleFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_CODE_OVERLAY_PERM);
                return;
            }
        }
        startFloatingService();
    }

    private void startFloatingService() {
        Intent serviceIntent = new Intent(this, FloatingHUDService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        finish();
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

    /**
     * JavaScript Bridge Interface
     */
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
        public void toggleFloatingOverlay() {
            mainHandler.post(() -> MainActivity.this.toggleFloatingOverlay());
        }
    }
}
