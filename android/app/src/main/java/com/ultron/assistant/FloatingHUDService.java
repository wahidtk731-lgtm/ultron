package com.ultron.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
 * Always-On-Top Floating Capsule Overlay for Ultron Assistant.
 * Stays on top of all apps (YouTube, Chrome, Games) with an explicit guaranteed height.
 */
public class FloatingHUDService extends Service implements TTSManager.TTSListener {

    private static final String TAG = "FloatingHUDService";
    public static final String ACTION_ASSIST_TRIGGER = "com.ultron.assistant.ACTION_ASSIST_TRIGGER";
    private static final String CHANNEL_ID = "ultron_overlay_channel";
    private static final int NOTIFICATION_ID = 2001;

    private static FloatingHUDService instance;

    private WindowManager windowManager;
    private FloatingBubbleView bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private FloatingTrashView trashView;
    private WindowManager.LayoutParams trashParams;
    private WebView webView;
    private WindowManager.LayoutParams hudParams;
    private boolean isHudExpanded = false;
    private boolean isBubbleAttached = false;
    private boolean isTrashAttached = false;
    private boolean isHudAttached = false;

    private UltronIndependentVoiceInput voiceInput;
    private UltronIntentEngine intentEngine;
    private AppLauncherAndroid appLauncher;
    private TTSManager ttsManager;
    private Handler mainHandler;

    private boolean shouldListenAfterSpeech = false;

    public static FloatingHUDService getInstance() {
        return instance;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        UltronDebugLog.init(this);
        UltronDebugLog.log("UltronApp", "LOG TEST");
        String ver = "1.0";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignored) {}
        UltronDebugLog.log("UltronApp", "FloatingHUDService onCreate - Ultron v" + ver + " on SDK " + Build.VERSION.SDK_INT);
        boolean micGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        UltronDebugLog.log("UltronPermission", "FloatingHUDService RECORD_AUDIO permission state: " + micGranted);

        mainHandler = new Handler(Looper.getMainLooper());
        intentEngine = new UltronIntentEngine();
        appLauncher = new AppLauncherAndroid(this);
        ttsManager = new TTSManager(this, this);

        startForegroundNotification();
        initVoiceInput();
        initFloatingHUDView();

        UltronVoiceSetup.get(this).ensureReady();
        UltronVoiceSetup.get(this).addListener(new UltronVoiceSetup.Listener() {
            @Override
            public void onStateChanged(final String state, final int percent, final String message) {
                mainHandler.post(new Runnable() {
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
            channel.setDescription("Ultron Floating HUD active");
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
            .setContentTitle("Ultron Floating Assistant")
            .setContentText("Tap mic on bottom capsule or speak commands")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    public void setOverlayVisible(final boolean visible) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (bubbleView != null) {
                    bubbleView.setVisibility((visible && !isHudExpanded) ? View.VISIBLE : View.GONE);
                }
                if (webView != null && isHudExpanded) {
                    webView.setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    private void initFloatingHUDView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot initialize floating HUD: SYSTEM_ALERT_WINDOW permission not granted");
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        // Load saved bubble coordinates
        SharedPreferences prefs = getSharedPreferences("ultron_prefs", MODE_PRIVATE);
        int defaultX = screenW - dpToPx(70);
        int defaultY = screenH / 3;
        int bubbleX = prefs.getInt("bubble_x", defaultX);
        int bubbleY = prefs.getInt("bubble_y", defaultY);

        // 1. Native Draggable Floating Bubble
        bubbleView = new FloatingBubbleView(this);
        bubbleParams = new WindowManager.LayoutParams(
            dpToPx(58),
            dpToPx(58),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = bubbleX;
        bubbleParams.y = bubbleY;

        setupBubbleTouchListener();

        // 2. Trash / Close Target at Bottom Center of Screen
        trashView = new FloatingTrashView(this);
        trashParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(130),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashView.setVisibility(View.GONE);

        // 3. Assistant HUD (WebView)
        webView = new WebView(this);
        webView.setBackgroundColor(0);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);

        webView.addJavascriptInterface(new FloatingWebAppInterface(), "AndroidUltron");

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
                webView.evaluateJavascript("if(window.setOverlayMode) window.setOverlayMode(true);", null);
                webView.evaluateJavascript("if(window.updateRecentApps) window.updateRecentApps();", null);
                updateWebStatus("ready", "Ultron Ready • Tap mic to speak");
            }
        });

        webView.loadUrl("file:///android_asset/hud.html");

        // Initially collapsed: 0x0 size and NOT_TOUCHABLE so it does NOT block any screen touches!
        hudParams = new WindowManager.LayoutParams(
            0,
            0,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        );
        hudParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        webView.setVisibility(View.GONE);

        try {
            if (!isTrashAttached) {
                windowManager.addView(trashView, trashParams);
                isTrashAttached = true;
            }
            if (!isHudAttached) {
                windowManager.addView(webView, hudParams);
                isHudAttached = true;
            }
            if (!isBubbleAttached) {
                windowManager.addView(bubbleView, bubbleParams);
                isBubbleAttached = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed adding floating views: " + e.getMessage());
        }
    }

    private void setupBubbleTouchListener() {
        final int touchSlop = dpToPx(8);
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long downTime;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (windowManager == null || bubbleView == null) return false;

                DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenH = dm.heightPixels;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        downTime = SystemClock.uptimeMillis();
                        isDragging = false;
                        bubbleView.setPressedState(true);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && Math.hypot(dx, dy) > touchSlop) {
                            isDragging = true;
                            if (trashView != null) {
                                trashView.show(true);
                            }
                        }
                        if (isDragging) {
                            bubbleParams.x = (int) (initialX + dx);
                            bubbleParams.y = (int) (initialY + dy);
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams);
                            } catch (Exception ignored) {}

                            if (trashView != null) {
                                boolean overTrash = trashView.isOverTarget(event.getRawX(), event.getRawY(), screenH);
                                trashView.setHovered(overTrash);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        bubbleView.setPressedState(false);
                        if (isDragging) {
                            boolean overTrash = false;
                            if (trashView != null) {
                                overTrash = trashView.isOverTarget(event.getRawX(), event.getRawY(), screenH);
                                trashView.show(false);
                            }
                            if (overTrash) {
                                closeFloatingBubble();
                            } else {
                                clampAndSaveBubblePosition();
                            }
                        } else {
                            long duration = SystemClock.uptimeMillis() - downTime;
                            if (duration < 350) {
                                expandAssistantHUD();
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        bubbleView.setPressedState(false);
                        if (isDragging && trashView != null) {
                            trashView.show(false);
                            clampAndSaveBubblePosition();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void clampAndSaveBubblePosition() {
        if (windowManager == null || bubbleView == null || bubbleParams == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int bubbleSize = dpToPx(58);

        bubbleParams.x = Math.max(dpToPx(4), Math.min(screenW - bubbleSize - dpToPx(4), bubbleParams.x));
        bubbleParams.y = Math.max(dpToPx(24), Math.min(screenH - bubbleSize - dpToPx(24), bubbleParams.y));

        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (Exception ignored) {}

        getSharedPreferences("ultron_prefs", MODE_PRIVATE).edit()
            .putInt("bubble_x", bubbleParams.x)
            .putInt("bubble_y", bubbleParams.y)
            .apply();
    }

    public void closeFloatingBubble() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(FloatingHUDService.this, "Ultron Floating Bubble closed", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
                stopSelf();
            }
        });
    }

    public void expandAssistantHUD() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (windowManager == null || webView == null) return;
                isHudExpanded = true;
                if (bubbleView != null) {
                    bubbleView.setVisibility(View.GONE);
                }

                hudParams.width = WindowManager.LayoutParams.MATCH_PARENT;
                hudParams.height = WindowManager.LayoutParams.MATCH_PARENT;
                hudParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                  WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                webView.setVisibility(View.VISIBLE);

                try {
                    windowManager.updateViewLayout(webView, hudParams);
                } catch (Exception e) {
                    Log.w(TAG, "Failed updating HUD layout: " + e.getMessage());
                }

                webView.evaluateJavascript("if(window.setOverlayMode) window.setOverlayMode(true);", null);
                startListening();
            }
        });
    }

    public void collapseToBubble() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (windowManager == null || webView == null) return;
                isHudExpanded = false;
                webView.setVisibility(View.GONE);

                hudParams.width = 0;
                hudParams.height = 0;
                hudParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                  WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

                try {
                    windowManager.updateViewLayout(webView, hudParams);
                } catch (Exception e) {
                    Log.w(TAG, "Failed collapsing HUD layout: " + e.getMessage());
                }

                if (bubbleView != null) {
                    bubbleView.setVisibility(View.VISIBLE);
                    bubbleView.setAssistantState("ready");
                }
            }
        });
    }

    public void toggleDockPosition() {
        // Overlay comfortably occupies bottom position with safe area padding
    }

    private void expandOverlay(final boolean expand) {
        // Managed dynamically by MATCH_PARENT overlay window
    }

    public void setMiniBubbleMode(final boolean mini) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mini) {
                    collapseToBubble();
                } else {
                    expandAssistantHUD();
                }
            }
        });
    }

    private void initVoiceInput() {
        if (voiceInput != null) {
            voiceInput.destroy();
        }

        voiceInput = new UltronIndependentVoiceInput(this, new UltronIndependentVoiceInput.VoiceInputListener() {
            @Override
            public void onReady() {
                expandOverlay(true);
                updateWebStatus("listening", "Listening for command...");
            }

            @Override
            public void onBeginningOfSpeech() {
                expandOverlay(true);
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
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        expandOverlay(false);
                    }
                }, 2000);
            }
        });
    }

    public void startListening() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    updateWebStatus("ready", "Mic permission required");
                    expandOverlay(true);
                    return;
                }
                expandOverlay(true);
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
                updateWebStatus("ready", "Tap mic or type command");
            }
        });
    }

    public void wakeUpFromAssist() {
        expandAssistantHUD();
    }

    public void processCommandFromBridge(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                processCommandInternal(text);
            }
        });
    }

    private void processCommandInternal(String rawText) {
        expandOverlay(true);
        updateWebStatus("processing", "Thinking...");

        String normalized = intentEngine.normalizeSpeech(rawText);
        String commandBody = intentEngine.stripWakeWords(normalized);
        String displayQuery = (normalized != null && !normalized.trim().isEmpty()) ? normalized.trim() : rawText;

        if (commandBody.isEmpty()) {
            shouldListenAfterSpeech = true;
            speakReply("Yes, I'm listening. What can I do for you?", "Listening...");
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
                        expandOverlay(true);
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
                        if (isHudExpanded) {
                            collapseToBubble();
                        } else {
                            expandAssistantHUD();
                        }
                    }
                });
                return "Toggled floating bubble mode";

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
                        stopSelf();
                    }
                }, 1200);
                return "Closing floating overlay. Goodbye!";

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
                if (bubbleView != null) {
                    bubbleView.setAssistantState(state);
                }
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
        updateWebStatus("ready", "Ultron Ready");
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isHudExpanded) {
                    collapseToBubble();
                }
            }
        }, 3500);
    }

    public void setFocusable(final boolean focusable) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (windowManager != null && webView != null && isHudExpanded && isHudAttached) {
                    if (focusable) {
                        hudParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    } else {
                        hudParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    }
                    try {
                        windowManager.updateViewLayout(webView, hudParams);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed updating focus layout: " + e.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (windowManager != null) {
            if (bubbleView != null && isBubbleAttached) {
                try {
                    windowManager.removeView(bubbleView);
                } catch (Exception ignored) {}
                isBubbleAttached = false;
            }
            if (trashView != null && isTrashAttached) {
                try {
                    windowManager.removeView(trashView);
                } catch (Exception ignored) {}
                isTrashAttached = false;
            }
            if (webView != null && isHudAttached) {
                try {
                    windowManager.removeView(webView);
                } catch (Exception ignored) {}
                isHudAttached = false;
            }
        }

        if (webView != null) {
            try {
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        bubbleView = null;
        trashView = null;

        if (voiceInput != null) {
            voiceInput.destroy();
            voiceInput = null;
        }
        if (ttsManager != null) {
            ttsManager.shutdown();
            ttsManager = null;
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
                    startListening();
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
                    collapseToBubble();
                }
            });
        }

        @JavascriptInterface
        public void toggleFloatingOverlay() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isHudExpanded) {
                        collapseToBubble();
                    } else {
                        expandAssistantHUD();
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestInputFocus(boolean focus) {
            setFocusable(focus);
        }

        @JavascriptInterface
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

        @JavascriptInterface
        public void openVoiceInputSettings() {
            openDefaultAssistantSettings();
        }

        @JavascriptInterface
        public void openOfflineSpeechSettings() {
            UltronIndependentVoiceInput.openOfflineSpeechSettings(FloatingHUDService.this);
        }

        @JavascriptInterface
        public void saveVoiceSettings(float pitch, float rate) {
            if (ttsManager != null) {
                ttsManager.setPitch(pitch);
                ttsManager.setSpeechRate(rate);
            }
        }

        @JavascriptInterface
        public void setWindowHeight(final int dp) {
            // MATCH_PARENT layout accommodates all content height dynamically
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
            UltronVoiceSetup setup = UltronVoiceSetup.get(FloatingHUDService.this);
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
                .getString("voice_engine_mode", "offline_only");
        }

        @JavascriptInterface
        public void setVoiceEngineMode(String mode) {
            if (mode != null && (mode.equals("auto") || mode.equals("offline_only"))) {
                getSharedPreferences("ultron_prefs", MODE_PRIVATE).edit()
                    .putString("voice_engine_mode", mode).apply();
                UltronDebugLog.log("FloatingBridge", "Voice engine mode set to: " + mode);
            }
        }

        @JavascriptInterface
        public void retryVoiceSetup() {
            UltronVoiceSetup.get(FloatingHUDService.this).retry();
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
                            android.widget.Toast.makeText(FloatingHUDService.this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
                            result[0] = true;
                        }
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "Failed to copy to clipboard: " + t.getMessage());
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
                        android.util.Log.w(TAG, "Failed to share text: " + t.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void closeFloatingOverlay() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
        }

        @JavascriptInterface
        public void toggleDockPosition() {
            FloatingHUDService.this.toggleDockPosition();
        }

        @JavascriptInterface
        public void minimizeToBubble() {
            collapseToBubble();
        }

        @JavascriptInterface
        public void expandFromBubble() {
            expandAssistantHUD();
        }

        @JavascriptInterface
        public void toggleBubbleMode() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isHudExpanded) {
                        collapseToBubble();
                    } else {
                        expandAssistantHUD();
                    }
                }
            });
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
        public void refreshInstalledApps() {
            if (appLauncher != null) {
                appLauncher.trainInstalledApps();
            }
        }
    }
}
