package com.jonathancruzate.metamuseandroidauto;

import android.content.Context;
import android.content.SharedPreferences;

/** Process-wide session state for Muse for Android Auto v2.
 *
 *  armed: a Muse voice session is (believed) live — set by Talk /
 *  Play, cleared by End / Stop. The accessibility service only
 *  intercepts volume keys while armed, so normal volume behavior
 *  is untouched the rest of the day.
 *  micMuted: last known system microphone mute state.
 *
 *  v2.5.2: `armed` is PERSISTED (SharedPreferences) as well as held
 *  in the volatile below. The persisted copy is the toggle state
 *  for the microphone button (SessionControl.toggleTalk): the
 *  media service / process can be killed and restarted by Android
 *  Auto at any time, and a purely in-memory flag would reset to
 *  "no session" while a voice session is actually live — the
 *  second microphone press would then re-start instead of hanging
 *  up. Same persisted-toggle model as the Muse Voice Mode
 *  Shortcut app. The volatile remains the in-process source of
 *  truth (all components share one process); load() refreshes it
 *  from disk, setArmed() writes both. */
public final class SessionState {
    public static volatile boolean armed = false;
    public static volatile boolean micMuted = false;
    /** v2.1: set by talk() — the accessibility service is hunting
     *  Muse's in-app voice button to tap it (URI deep links into
     *  Muse voice are TrustedCaller-gated, so the session is
     *  started by opening Muse and tapping its own button). */
    public static volatile boolean awaitingVoiceTap = false;

    private static final String PREFS = "muse_drive_session";
    private static final String KEY_ARMED = "armed";
    private static volatile boolean loaded = false;

    private SessionState() {}

    /** Refresh the in-memory flag from disk. Cheap; safe to call
     *  from any component's entry point. */
    public static void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            armed = p.getBoolean(KEY_ARMED, false);
            loaded = true;
        } catch (Throwable ignored) {
            // Persistence is a robustness layer, never a crash path.
        }
    }

    /** Load once per process if no component has loaded yet. */
    public static void ensureLoaded(Context ctx) {
        if (!loaded) load(ctx);
    }

    /** Set the session-active flag in memory AND on disk. */
    public static void setArmed(Context ctx, boolean value) {
        armed = value;
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ARMED, value).apply();
            loaded = true;
        } catch (Throwable ignored) {
        }
    }
}
