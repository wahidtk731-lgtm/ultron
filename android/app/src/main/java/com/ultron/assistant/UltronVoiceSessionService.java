package com.ultron.assistant;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * Service that creates active voice interaction sessions when the user
 * triggers assistant from the system (e.g. Home button or Assist gesture).
 */
public class UltronVoiceSessionService extends VoiceInteractionSessionService {

    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new UltronVoiceSession(this);
    }
}
