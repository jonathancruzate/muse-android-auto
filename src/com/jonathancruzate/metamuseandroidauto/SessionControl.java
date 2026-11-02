package com.jonathancruzate.metamuseandroidauto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;

/** The v2 control layer: everything the Auto surface, the volume
 *  keys, the notification and the test panel do to the Muse voice
 *  session — with NO MacroDroid in the path.
 *
 *    talk()   -> ACTION_ASSIST into Muse (ungated, REA-verified)
 *    toggleTalk() -> the microphone button (v2.5.2): no session
 *                live -> talk(); session live -> end(). State is
 *                SessionState.armed, persisted on the phone, so
 *                the toggle survives media-service restarts —
 *                the same toggle the Muse Voice Mode Shortcut
 *                app's button performs.
 *    mute()   -> AudioManager.setMicrophoneMute(true)
 *    unmute() -> AudioManager.setMicrophoneMute(false)
 *    end()    -> accessibility service taps Muse's end-call
 *                control (fallback: Back, then Home)
 *
 *  Tactile confirmations mirror the macro set: mute = long buzz,
 *  unmute = short blip, end = double buzz — state by feel. */
public final class SessionControl {

    private SessionControl() {}

    // v2.3.1: every method is catch-Throwable guarded + recorded.
    // These are called from Android Auto transport callbacks, the
    // accessibility service, notification broadcasts, and the test
    // panel — an uncaught throw in any of them crashed the whole
    // process (the "crashing frequently" reports).

    public static void talk(Context ctx) {
        try {
            CrashLog.install(ctx);
            SessionState.setArmed(ctx, true);
            SessionState.awaitingVoiceTap = true;
            DriveActions.startClaudeAssist(ctx);
            DriveMediaService svc = DriveMediaService.active;
            if (svc != null) svc.reflectPlaying();
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.talk", t); }
    }

    /** v2.5.2: the microphone button TOGGLES Muse voice mode
     *  (Jonathan's order 2026-11-01, from his in-car test: the mic
     *  button started a session but pressing it again did nothing,
     *  so there was no way to hang up from Android Auto). No
     *  session live -> start one (talk). Session live -> hang up
     *  (end: the accessibility end-call hunt, Back -> Home
     *  fallback — the exact path Stop / "Hang up" already used).
     *  State is SessionState.armed, persisted on the phone, so
     *  the decision stays correct even if the media service was
     *  restarted since the session started. */
    public static void toggleTalk(Context ctx) {
        try {
            CrashLog.install(ctx);
            SessionState.ensureLoaded(ctx);
            if (SessionState.armed) {
                end(ctx);
            } else {
                talk(ctx);
            }
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.toggleTalk", t); }
    }

    public static void mute(Context ctx) {
        try {
            Mic.set(ctx, true);
            buzz(ctx, 350);
            DriveMediaService svc = DriveMediaService.active;
            if (svc != null) svc.reflectPaused();
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.mute", t); }
    }

    public static void unmute(Context ctx) {
        try {
            SessionState.setArmed(ctx, true);
            Mic.set(ctx, false);
            buzz(ctx, 80);
            DriveMediaService svc = DriveMediaService.active;
            if (svc != null) svc.reflectPlaying();
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.unmute", t); }
    }

    public static void end(Context ctx) {
        try {
            buzz(ctx, 120);
            final Context appCtx = ctx.getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { buzz(appCtx, 120); }
            }, 200);
            DriveAccessibilityService a11y = DriveAccessibilityService.instance;
            if (a11y != null) {
                a11y.endCall();
            } else {
                // A Toast from a service/receiver context can itself
                // throw on some builds; it is cosmetic, never fatal.
                try {
                    Toast.makeText(ctx,
                            "Hang up needs the Muse for Android Auto accessibility service enabled",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            }
            SessionState.setArmed(ctx, false);
            SessionState.awaitingVoiceTap = false;
            DriveMediaService svc = DriveMediaService.active;
            if (svc != null) svc.reflectStopped();
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.end", t); }
    }

    public static void buzz(Context ctx, long ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }
}
