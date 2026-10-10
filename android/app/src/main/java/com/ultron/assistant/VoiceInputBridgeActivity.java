package com.ultron.assistant;

import android.app.Activity;
import android.os.Bundle;

/**
 * Deprecated bridge activity - in-app SpeechRecognizer is now used directly.
 */
public class VoiceInputBridgeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
