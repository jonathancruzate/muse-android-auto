package com.jonathancruzate.museandroidauto;

import android.content.Context;
import android.media.AudioManager;

/** System microphone mute, in-app — the same AudioManager call
 *  MacroDroid's MuteMicrophoneAction makes (public SDK API,
 *  verified against android-35 android.jar). Documented effective
 *  for calls and VoIP communication sessions, which is how Muse
 *  voice runs (MODE_IN_COMMUNICATION + Bluetooth SCO). */
public final class Mic {

    private Mic() {}

    public static boolean set(Context ctx, boolean mute) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            am.setMicrophoneMute(mute);
        } catch (Exception ignored) {
            return false;
        }
        boolean now = isMuted(ctx);
        SessionState.micMuted = now;
        return now == mute;
    }

    public static boolean isMuted(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return SessionState.micMuted;
        try {
            return am.isMicrophoneMute();
        } catch (Exception ignored) {
            return SessionState.micMuted;
        }
    }
}
