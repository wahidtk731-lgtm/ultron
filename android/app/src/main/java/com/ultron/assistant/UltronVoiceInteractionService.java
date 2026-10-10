package com.ultron.assistant;

import android.service.voice.VoiceInteractionService;

/**
 * Native Android VoiceInteractionService for Ultron Assistant.
 * Exposes Ultron directly in System Settings -> Apps -> Default apps -> Assist app & Voice input.
 */
public class UltronVoiceInteractionService extends VoiceInteractionService {

    @Override
    public void onReady() {
        super.onReady();
    }

    @Override
    public void onShutdown() {
        super.onShutdown();
    }
}
