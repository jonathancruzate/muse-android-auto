package com.jonathancruzate.musedrive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Entry point for notification-action taps. Routes everything to
 *  SessionControl (in-app since v2; no MacroDroid). */
public class DriveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (DriveActions.ACTION_TALK.equals(a)) {
            SessionControl.talk(context);
        } else if (DriveActions.ACTION_MUTE.equals(a)) {
            SessionControl.mute(context);
        } else if (DriveActions.ACTION_UNMUTE.equals(a)) {
            SessionControl.unmute(context);
        } else if (DriveActions.ACTION_END.equals(a)) {
            SessionControl.end(context);
        }
    }
}
