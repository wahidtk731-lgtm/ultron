package com.ultron.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
import android.webkit.WebViewClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Always-On-Top Floating Overlay & 24/7 "Hey Ultron" Wake-Word Service.
 * - Stays floating over all apps (YouTube, Chrome, games) matching floating_widget.py.
 * - Never disappears or goes under other apps.
 * - Continuously listens in the background for "Hey Ultron".
 */
public class FloatingHUDService extends Service implements TTSManager.TTSListener {

    private static final String TAG = "FloatingHUDService";
    public static final String ACTION_ASSIST_TRIGGER = "com.ultron.assistant.ACTION_ASSIST_TRIGGER";
    private static final String CHANNEL_ID = "ultron_overlay_channel";
    private static final int NOTIFICATION_ID = 2001;

    private static FloatingHUDService instance;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams windowParams;
    private WebView webView;
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private UltronIntentEngine intentEngine;
    private AppLauncherAndroid appLauncher;
    private TTSManager ttsManager;
    private Handler mainHandler;

    private boolean isContinuousListeningActive = true;
    private boolean isProcessingCommand = false;
    private boolean isListening = false;

    public static FloatingHUDService getInstance() {
        return instance;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        mainHandler = new Handler(Looper.getMainLooper());
        intentEngine = new UltronIntentEngine();
        appLauncher = new AppLauncherAndroid(this);
        ttsManager = new TTSManager(this, this);

        startForegroundNotification();
        initSpeechRecognizer();
        initFloatingHUDView();

        // Start continuous background listening for "Hey Ultron"
        scheduleContinuousListening(600);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_ASSIST_TRIGGER.equals(intent.getAction())) {
            wakeUpFromAssist();
        }
        return START_STICKY;
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Ultron Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ultron is awake and listening for 'Hey Ultron'");
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
            .setContentTitle("Ultron AI Assistant Active")
            .setContentText("Say 'Hey Ultron' anytime • Stays on top of all apps")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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

        windowParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );

        windowParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        webView = new WebView(this);
        webView.setBackgroundColor(0);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);

        webView.addJavascriptInterface(new FloatingWebAppInterface(), "AndroidUltron");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webView.evaluateJavascript("if(window.setOverlayMode) window.setOverlayMode(true);", null);
                updateWebStatus("ready", "Awake • Say 'Hey Ultron' anytime");
            }
        });

        webView.loadUrl("file:///android_asset/hud.html");
        floatingView = webView;

        try {
            windowManager.addView(floatingView, windowParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating view: " + e.getMessage());
        }
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available on this device.");
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
                    if (!isProcessingCommand) {
                        updateWebStatus("ready", "Awake • Say 'Hey Ultron' anytime");
                    }
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
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    // In continuous listening, silence timeout (ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT)
                    // is completely normal. Silently loop back to listening.
                    if (!isProcessingCommand && isContinuousListeningActive) {
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            scheduleContinuousListening(500);
                        } else {
                            scheduleContinuousListening(350);
                        }
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> matches = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        Log.d(TAG, "Recognized: " + text);

                        if (intentEngine.isWakeWord(text)) {
                            // "Hey Ultron" detected! Wake up!
                            handleWakeWordTriggered(text);
                            return;
                        }
                    }

                    // No wake word in this utterance; seamlessly resume listening
                    if (!isProcessingCommand && isContinuousListeningActive) {
                        scheduleContinuousListening(300);
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial = partialResults != null ? partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                    if (partial != null && !partial.isEmpty()) {
                        String partialText = partial.get(0);
                        if (intentEngine.isWakeWord(partialText)) {
                            updateWebStatus("listening", "\"" + partialText + "...\"");
                        }
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "SpeechRecognizer creation failed: " + e.getMessage());
        }
    }

    private void scheduleContinuousListening(long delayMillis) {
        mainHandler.postDelayed(() -> {
            if (isContinuousListeningActive && !isProcessingCommand) {
                startListeningInternal();
            }
        }, delayMillis);
    }

    private void startListeningInternal() {
        if (speechRecognizer == null) {
            initSpeechRecognizer();
        }
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting speech recognition: " + e.getMessage());
                scheduleContinuousListening(800);
            }
        }
    }

    public void wakeUpFromAssist() {
        mainHandler.post(() -> {
            isProcessingCommand = true;
            updateWebStatus("listening", "Listening for command...");
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.cancel();
                    speechRecognizer.startListening(recognizerIntent);
                } catch (Exception ignored) {}
            }
        });
    }

    private void handleWakeWordTriggered(String rawText) {
        isProcessingCommand = true;
        updateWebStatus("processing", "Thinking...");

        String normalized = intentEngine.normalizeSpeech(rawText);
        String commandBody = intentEngine.stripWakeWords(normalized);

        if (commandBody.isEmpty()) {
            // User just said "Hey Ultron" without a command
            speakReply("Yes, I'm listening. What can I do for you?", "Hey Ultron");
            return;
        }

        // Execute command
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
                return "I am Ultron, your always-on AI assistant.";

            case "open_app":
                if (action.entity.isEmpty()) return "Which app would you like me to open?";
                boolean launched = appLauncher.launchApp(action.entity);
                return launched ? "Opening " + action.entity : "Could not find " + action.entity;

            case "search_web":
                if (action.entity.isEmpty()) return "What would you like me to search for?";
                appLauncher.searchWeb(action.entity);
                return "Searching Google for " + action.entity;

            case "write_file":
                if (action.entity.isEmpty()) return "What should I write down?";
                appLauncher.writeNote(action.entity);
                return "Saved note: " + action.entity;

            case "exit":
                mainHandler.postDelayed(this::stopSelf, 1200);
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
        // Response finished; resume continuous background wake-word listening
        isProcessingCommand = false;
        updateWebStatus("ready", "Awake • Say 'Hey Ultron' anytime");
        scheduleContinuousListening(500);
    }

    public void setFocusable(boolean focusable) {
        mainHandler.post(() -> {
            if (windowManager != null && floatingView != null) {
                if (focusable) {
                    windowParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                } else {
                    windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                }
                try {
                    windowManager.updateViewLayout(floatingView, windowParams);
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        isContinuousListeningActive = false;

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
            wakeUpFromAssist();
        }

        @JavascriptInterface
        public void stopListening() {
            if (speechRecognizer != null) {
                mainHandler.post(() -> {
                    try {
                        speechRecognizer.stopListening();
                    } catch (Exception ignored) {}
                });
            }
        }

        @JavascriptInterface
        public void processCommand(String text) {
            handleWakeWordTriggered(text);
        }

        @JavascriptInterface
        public void dismiss() {
            mainHandler.post(() -> stopSelf());
        }

        @JavascriptInterface
        public void toggleFloatingOverlay() {
            mainHandler.post(() -> stopSelf());
        }

        @JavascriptInterface
        public void requestInputFocus(boolean focus) {
            setFocusable(focus);
        }
    }
}
