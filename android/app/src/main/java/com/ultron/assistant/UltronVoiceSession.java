package com.ultron.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/**
 * Handles incoming voice interaction session from Android system settings or gestures.
 */
public class UltronVoiceSession extends VoiceInteractionSession {

    public UltronVoiceSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        try {
            Intent intent = new Intent(getContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            getContext().startActivity(intent);
        } catch (Exception ignored) {}
        hide();
    }
}
