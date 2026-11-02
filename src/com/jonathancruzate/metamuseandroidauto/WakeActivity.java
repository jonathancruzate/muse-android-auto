package com.jonathancruzate.metamuseandroidauto;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/** v2.5.2 trampoline for screen-off / locked operation.
 *
 *  Why this exists (Jonathan's in-car test, 2026-11-01): the
 *  microphone button did nothing with the phone screen OFF. The
 *  Android Auto buttons themselves fire fine — the failure is
 *  one layer down. The Talk path fires ACTION_ASSIST and then
 *  has the accessibility service tap Muse's voice button in
 *  the active window; with the keyguard up, Muse's window is
 *  behind the lock screen, where Android blocks accessibility
 *  interaction with app windows — getRootInActiveWindow() sees
 *  the keyguard, not Muse, so every hunt attempt misses and
 *  the start silently goes nowhere. The end-call hunt has the
 *  identical problem in reverse.
 *
 *  This activity is declared showWhenLocked + turnScreenOn:
 *  launching it wakes the phone and puts it over the lock
 *  screen. It then asks the system to dismiss the keyguard
 *  (KeyguardManager.requestDismissKeyguard):
 *    - No secure lock (swipe / none), or Smart Lock / Extend
 *      Unlock already holding the phone open (e.g. the car's
 *      Bluetooth as a trusted device): dismissal succeeds at
 *      once and the pending op runs fully hands-free.
 *    - Secure lock (PIN / pattern / password / biometric):
 *      the system shows its own credential prompt and the op
 *      runs only after the driver authenticates. Android gives
 *      no app a way around a secure keyguard, and this app does
 *      not try — that is a stated limit, not a bug.
 *  On cancel: a START is aborted (nothing armed — arming a
 *  session that never started would invert the mic toggle's
 *  state — and the car UI is reset). On dismiss error a START
 *  also aborts, while an END still makes its best-effort
 *  attempt, matching end()'s degrade-gracefully contract.
 *  Translucent, excluded from recents, finishes as soon as the
 *  op is handed off. */
public class WakeActivity extends Activity {

    public static final String EXTRA_OP = "op";
    public static final String OP_START = "start";
    public static final String OP_END = "end";

    private String op = OP_START;
    private boolean proceeded = false;

    public static void launch(Context ctx, String op) {
        try {
            Intent i = new Intent(ctx, WakeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(EXTRA_OP, op);
            ctx.startActivity(i);
        } catch (Throwable t) {
            CrashLog.record(ctx, "WakeActivity.launch", t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        try {
            if (getIntent() != null && getIntent().getStringExtra(EXTRA_OP) != null) {
                op = getIntent().getStringExtra(EXTRA_OP);
            }
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) {
                proceed();
            } else {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { proceed(); }
                    @Override public void onDismissError() { bestEffort(); }
                    @Override public void onDismissCancelled() { abort(); }
                });
            }
        } catch (Throwable t) {
            CrashLog.record(this, "WakeActivity.onCreate", t);
            finish();
        }
    }

    private void proceed() {
        if (proceeded) return;
        proceeded = true;
        try {
            if (OP_END.equals(op)) {
                SessionControl.endCallNow(this);
            } else {
                SessionControl.doTalk(this);
            }
        } catch (Throwable t) {
            CrashLog.record(this, "WakeActivity.proceed", t);
        }
        finish();
    }

    private void bestEffort() {
        // Dismissal errored: an END still tries its hunt (its own
        // fallback chain degrades harmlessly against the keyguard);
        // a START does not.
        if (OP_END.equals(op)) {
            proceed();
        } else {
            abort();
        }
    }

    private void abort() {
        if (proceeded) return;
        proceeded = true;
        try {
            if (!OP_END.equals(op)) {
                DriveMediaService svc = DriveMediaService.active;
                if (svc != null) svc.reflectStopped();
            }
        } catch (Throwable ignored) {
        }
        finish();
    }
}
