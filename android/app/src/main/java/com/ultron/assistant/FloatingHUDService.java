package com.ultron.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Always-On-Top Floating Capsule Overlay Service for Ultron Assistant.
 * Runs on top of all Android apps (Chrome, YouTube, etc.) matching floating_widget.py.
 */
public class FloatingHUDService extends Service implements TTSManager.TTSListener {

    private static final String TAG = "FloatingHUDService";
    private static final String CHANNEL_ID = "ultron_overlay_channel";
    private static final int NOTIFICATION_ID = 2001;

    private WindowManager windowManager;
    private View floatingView;
    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private UltronIntentEngine intentEngine;
    private AppLauncherAndroid appLauncher;
    private TTSManager ttsManager;
    private Handler mainHandler;
    private boolean isListening = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());
        intentEngine = new UltronIntentEngine();
        appLauncher = new AppLauncherAndroid(this);
        ttsManager = new TTSManager(this, this);

        startForegroundNotification();
        initSpeechRecognizer();
        initFloatingHUDView();
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Ultron Floating Assistant",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ultron Assistant Floating Capsule Overlay");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
            .setContentTitle("Ultron Assistant Active")
            .setContentText("Floating HUD capsule is running over apps")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void initFloatingHUDView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.BOTTOM;

        webView = new WebView(this);
        webView.setBackgroundColor(0);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new FloatingWebAppInterface(), "AndroidUltron");
        webView.loadUrl("file:///android_asset/hud.html");
        floatingView = webView;

        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating view: " + e.getMessage());
        }
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return;
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
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
                    updateWebStatus("ready", "Tap mic or type command");
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        processCommandInternal(matches.get(0));
                    } else {
                        updateWebStatus("ready", "Didn't catch that. Tap mic to retry.");
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "SpeechRecognizer creation failed: " + e.getMessage());
        }
    }

    private void startListening() {
        mainHandler.post(() -> {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                    speechRecognizer.startListening(recognizerIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Error starting speech: " + e.getMessage());
                }
            }
        });
    }

    private void stopListening() {
        mainHandler.post(() -> {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.stopListening();
                } catch (Exception ignored) {}
            }
        });
    }

    private void processCommandInternal(String rawText) {
        String normalized = intentEngine.normalizeSpeech(rawText);
        String commandBody = intentEngine.stripWakeWords(normalized);

        if (commandBody.isEmpty()) {
            speakReply("Yes, I'm listening. What would you like me to do?", "Listening...");
            return;
        }

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
                return "I am Ultron, your local AI assistant.";

            case "open_app":
                if (action.entity.isEmpty()) return "Which app would you like me to open?";
                boolean launched = appLauncher.launchApp(action.entity);
                return launched ? "Opening " + action.entity : "Could not find " + action.entity;

            case "search_web":
                if (action.entity.isEmpty()) return "What would you like me to search for?";
                appLauncher.searchWeb(action.entity);
                return "Searching Google for " + action.entity;

            case "exit":
                mainHandler.postDelayed(this::stopSelf, 1200);
                return "Closing floating overlay. Goodbye!";

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

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
        }
    }

    public class FloatingWebAppInterface {
        @JavascriptInterface
        public void startListening() {
            FloatingHUDService.this.startListening();
        }

        @JavascriptInterface
        public void stopListening() {
            FloatingHUDService.this.stopListening();
        }

        @JavascriptInterface
        public void processCommand(String text) {
            processCommandInternal(text);
        }

        @JavascriptInterface
        public void dismiss() {
            mainHandler.post(() -> stopSelf());
        }

        @JavascriptInterface
        public void toggleFloatingOverlay() {
            mainHandler.post(() -> stopSelf());
        }
    }
}
