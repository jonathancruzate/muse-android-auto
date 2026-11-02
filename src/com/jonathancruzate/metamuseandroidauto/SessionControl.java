package com.jonathancruzate.metamuseandroidauto;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
 *  Screen-off (v2.5.2): both accessibility hunts (voice-button
 *  tap on start, end-call tap on end) are blind while the
 *  keyguard is up — Android blocks accessibility interaction
 *  with app windows behind the lock screen. talk() and end()
 *  therefore check screenReady() first; when the screen is off
 *  or locked they wake the phone and route through WakeActivity
 *  (showWhenLocked + turnScreenOn + requestDismissKeyguard),
 *  which runs the hunt only once Muse's window is reachable.
 *  A SECURE keyguard (PIN / pattern / password / biometric)
 *  can only be dismissed by the user authenticating — see
 *  WakeActivity's doc; that limit is stated, not worked around.
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
            if (screenReady(ctx)) {
                doTalk(ctx);
            } else {
                // v2.5.2 screen-off fix: with the screen off /
                // keyguard up, Muse's activity is held behind the
                // lock screen where the accessibility voice-button
                // hunt cannot see it — the start went nowhere
                // (Jonathan's in-car test, 2026-11-01). Wake the
                // phone and let WakeActivity (showWhenLocked +
                // turnScreenOn) dismiss the keyguard, then run
                // doTalk from there.
                wakeScreen(ctx);
                WakeActivity.launch(ctx, WakeActivity.OP_START);
            }
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.talk", t); }
    }

    /** The start sequence itself (talk()'s body before the
     *  v2.5.2 screen-off trampoline): arm, fire ACTION_ASSIST, and
     *  let the accessibility service tap Muse's own voice button.
     *  Called directly when the screen is on + unlocked, and by
     *  WakeActivity once the phone is awake and any keyguard has
     *  been dismissed. */
    public static void doTalk(Context ctx) {
        try {
            CrashLog.install(ctx);
            SessionState.setArmed(ctx, true);
            SessionState.awaitingVoiceTap = true;
            DriveActions.startClaudeAssist(ctx);
            DriveMediaService svc = DriveMediaService.active;
            if (svc != null) svc.reflectPlaying();
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.doTalk", t); }
    }

    /** True when the accessibility hunts can actually see Muse's
     *  window: screen on AND no keyguard. Unknown state degrades
     *  to the pre-v2.5.2 direct path. */
    private static boolean screenReady(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return false;
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Hold the screen on briefly so the wake trampoline, the
     *  keyguard prompt, and the voice-button hunt can complete
     *  without the display re-sleeping mid-sequence. */
    private static void wakeScreen(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "museauto:wake");
                wl.setReferenceCounted(false);
                wl.acquire(15000);
            }
        } catch (Throwable ignored) {
        }
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
            if (screenReady(ctx)) {
                endCallNow(ctx);
            } else {
                // v2.5.2: same screen-off problem as talk(), in
                // reverse — the end-call hunt cannot see Muse's
                // window behind the keyguard. Wake first; the
                // trampoline runs the hunt after dismissal. State
                // is still cleared below, immediately: from the
                // driver's seat the session is over either way,
                // and endCall's own fallback chain degrades
                // harmlessly if the hunt still can't land.
                wakeScreen(ctx);
                WakeActivity.launch(ctx, WakeActivity.OP_END);
            }
            SessionState.setArmed(ctx, false);
            SessionState.awaitingVoiceTap = false;
            DriveMediaService svc = DriveMediaService.active;
            if (svc != null) svc.reflectStopped();
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.end", t); }
    }

    /** Run the accessibility end-call hunt right now (the
     *  pre-v2.5.2 end() behavior), or explain why it cannot run.
     *  Called by end() when the screen is usable, and by
     *  WakeActivity after a wake + keyguard dismissal. */
    public static void endCallNow(Context ctx) {
        try {
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
        } catch (Throwable t) { CrashLog.record(ctx, "SessionControl.endCallNow", t); }
    }

    public static void buzz(Context ctx, long ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }
}
