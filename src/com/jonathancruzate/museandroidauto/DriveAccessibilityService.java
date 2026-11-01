package com.jonathancruzate.museandroidauto;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/** Muse for Android Auto's own accessibility service — the two things only
 *  an accessibility service can do for a normal app, and the whole
 *  reason v2 needs no MacroDroid:
 *
 *  1. Volume-key long-presses (key-event filtering). Active ONLY
 *     while a session is armed (SessionState.armed), so everyday
 *     volume behavior is untouched:
 *       hold Volume DOWN          -> mute
 *       hold Volume UP            -> unmute
 *       hold BOTH volume buttons  -> hang up
 *     A short press still changes the volume (re-applied manually,
 *     since the press is consumed while armed).
 *
 *  2. endCall(): find Muse's in-call end control in the active
 *     window and click it — view-id candidates from the REA pass
 *     (end_voice_button) plus text/description candidates; fallback
 *     Back, then Home (the same chain as the retired macro).
 *
 *  3. startVoice() (v2.1): the URI deep link into Muse voice
 *     (hatch://voice) is TrustedCaller-gated to Meta-signed apps,
 *     so no outside app can fire it. Instead talk() opens Muse
 *     via ACTION_ASSIST and this service taps Muse's own voice
 *     button when the Muse window appears (SessionState
 *     .awaitingVoiceTap), which starts the same session the deep
 *     link would have.
 */
public class DriveAccessibilityService extends AccessibilityService {

    public static volatile DriveAccessibilityService instance;

    private static final long LONG_PRESS_MS = 550;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean upHeld = false;
    private boolean downHeld = false;
    private boolean gestureFired = false;
    private Runnable pendingGesture;

    @Override
    protected void onServiceConnected() {
        instance = this;
        CrashLog.install(this);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // v2.3.1 guarded: this service runs constantly, so an
        // uncaught throw here is a background crash loop — the
        // exact "crashing frequently" pattern Device Care reports.
        try {
            // Window content is read on demand in endCall(); events only
            // wake the voice-button hunt while a Talk is pending.
            if (SessionState.awaitingVoiceTap && event != null
                    && event.getPackageName() != null
                    && "com.facebook.aura".contentEquals(event.getPackageName())) {
                scheduleVoiceHunt();
            }
        } catch (Throwable t) { CrashLog.record(this, "onAccessibilityEvent", t); }
    }

    // ---- start voice (v2.1: tap Muse's own voice button) ----

    private int voiceHuntAttempts = 0;
    private boolean voiceHuntRunning = false;
    private final Runnable voiceHuntStep = new Runnable() {
        @Override
        public void run() {
            if (!SessionState.awaitingVoiceTap) {
                voiceHuntRunning = false;
                return;
            }
            if (tryTapVoiceButton()) {
                SessionState.awaitingVoiceTap = false;
                voiceHuntRunning = false;
                SessionControl.buzz(DriveAccessibilityService.this, 80);
                return;
            }
            voiceHuntAttempts++;
            if (voiceHuntAttempts >= 8) { // ~7s of retries, then stop hunting
                SessionState.awaitingVoiceTap = false;
                voiceHuntRunning = false;
                return;
            }
            handler.postDelayed(this, 900);
        }
    };

    private void scheduleVoiceHunt() {
        if (voiceHuntRunning) return;
        voiceHuntRunning = true;
        voiceHuntAttempts = 0;
        handler.postDelayed(voiceHuntStep, 400);
    }

    private boolean tryTapVoiceButton() {
        try { return tryTapVoiceButtonInner(); }
        catch (Throwable t) { CrashLog.record(this, "tryTapVoiceButton", t); return false; }
    }

    private boolean tryTapVoiceButtonInner() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"com.facebook.aura".contentEquals(root.getPackageName())) {
            return false;
        }
        // 1) view-id candidates (resource names from the REA DEX pass)
        String[] ids = {
                "com.facebook.aura:id/hatch_voice_button",
                "com.facebook.aura:id/voice_button",
                "com.facebook.aura:id/voice_call_button",
                "com.facebook.aura:id/start_voice_button",
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty() && clickNode(nodes.get(0))) return true;
        }
        // 2) exact text / description candidates
        String[] labels = {"Voice call", "Start voice call", "Voice mode", "Voice", "Talk"};
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    CharSequence t = n.getText();
                    CharSequence d = n.getContentDescription();
                    if ((t != null && label.equalsIgnoreCase(t.toString()))
                            || (d != null && label.equalsIgnoreCase(d.toString()))) {
                        if (clickNode(n)) return true;
                    }
                }
            }
            if (searchByDescription(root, label)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    // ---- volume keys ----

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try { return onKeyEventInner(event); }
        catch (Throwable t) { CrashLog.record(this, "onKeyEvent", t); return false; }
    }

    private boolean onKeyEventInner(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (!SessionState.armed) {
            return false; // not our session: leave volume keys alone
        }
        boolean isUp = code == KeyEvent.KEYCODE_VOLUME_UP;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return true; // consume repeats
            if (isUp) upHeld = true; else downHeld = true;
            scheduleGesture();
            return true; // consume while armed; short presses re-applied on release
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean wasHeld = isUp ? upHeld : downHeld;
            if (isUp) upHeld = false; else downHeld = false;
            if (!gestureFired && wasHeld) {
                // short press: give the volume change back
                android.media.AudioManager am =
                        (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL,
                            isUp ? android.media.AudioManager.ADJUST_RAISE
                                 : android.media.AudioManager.ADJUST_LOWER, 0);
                }
            }
            if (!upHeld && !downHeld) {
                cancelGesture();
                gestureFired = false;
            }
            return true;
        }
        return true;
    }

    private void scheduleGesture() {
        cancelGesture();
        pendingGesture = new Runnable() {
            @Override
            public void run() {
                gestureFired = true;
                if (upHeld && downHeld) {
                    SessionControl.end(DriveAccessibilityService.this);
                } else if (downHeld) {
                    SessionControl.mute(DriveAccessibilityService.this);
                } else if (upHeld) {
                    SessionControl.unmute(DriveAccessibilityService.this);
                }
            }
        };
        handler.postDelayed(pendingGesture, LONG_PRESS_MS);
    }

    private void cancelGesture() {
        if (pendingGesture != null) {
            handler.removeCallbacks(pendingGesture);
            pendingGesture = null;
        }
    }

    // ---- hang up ----

    public void endCall() {
        try { endCallInner(); }
        catch (Throwable t) { CrashLog.record(this, "endCall", t); }
    }

    private void endCallInner() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            // 1) view-id candidates (names from the REA DEX pass)
            String[] ids = {
                    "com.facebook.aura:id/end_voice_button",
                    "com.facebook.aura:id/end_call_button",
                    "com.facebook.aura:id/voice_end_call",
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty() && clickNode(nodes.get(0))) return;
            }
            // 2) text / content-description candidates
            String[] labels = {"End call", "End voice call", "Hang up", "Leave call", "End"};
            for (String label : labels) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
                if (nodes != null) {
                    for (AccessibilityNodeInfo n : nodes) {
                        if (clickNode(n)) return;
                    }
                }
                if (searchByDescription(root, label)) return;
            }
        }
        // 3) fallback chain (was the END macro's chain)
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(new Runnable() {
            @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); }
        }, 600);
    }

    private boolean searchByDescription(AccessibilityNodeInfo node, String label) {
        // Stale nodes (window changed mid-walk) throw on access;
        // treat that as "not found", never as a crash.
        try {
            if (node == null) return false;
            CharSequence desc = node.getContentDescription();
            if (desc != null && label.equalsIgnoreCase(desc.toString()) && clickNode(node)) {
                return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                if (searchByDescription(node.getChild(i), label)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        try {
            AccessibilityNodeInfo n = node;
            while (n != null) {
                if (n.isClickable()) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                n = n.getParent();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
