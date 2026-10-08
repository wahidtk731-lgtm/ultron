package com.ultron.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Invisible bridge Activity enabling FloatingHUDService to launch
 * Android's System Voice Recognition Dialog with 100% reliability.
 */
public class VoiceInputBridgeActivity extends Activity {
    private static final String TAG = "VoiceInputBridge";
    public static final int REQ_CODE_VOICE = 104;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Ultron Assistant");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        try {
            startActivityForResult(intent, REQ_CODE_VOICE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch voice intent: " + e.getMessage());
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CODE_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String text = matches.get(0);
                if (FloatingHUDService.getInstance() != null) {
                    FloatingHUDService.getInstance().processCommandFromBridge(text);
                }
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
