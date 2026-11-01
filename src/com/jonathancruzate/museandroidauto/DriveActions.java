package com.jonathancruzate.museandroidauto;

import android.content.Context;
import android.content.Intent;

/** Shared action constants + the one external intent Muse for Android Auto
 *  fires: ACTION_ASSIST into Muse.
 *
 *  v2 note: v1 forwarded MUTE/UNMUTE/END to MacroDroid by broadcast.
 *  That dependency is gone — SessionControl performs them in-app.
 *  These constants now only route between Muse for Android Auto's own
 *  surfaces (Auto player custom actions, notification, receiver). */
public final class DriveActions {
    public static final String PKG = "com.jonathancruzate.museandroidauto";

    public static final String ACTION_TALK   = PKG + ".TALK";
    public static final String ACTION_MUTE   = PKG + ".MUTE";
    public static final String ACTION_UNMUTE = PKG + ".UNMUTE";
    public static final String ACTION_END    = PKG + ".END";

    private DriveActions() {}

    /** Start Muse via the one ungated external surface REA verified:
     *  ACTION_ASSIST into AuraMainActivity. Explicit component first,
     *  implicit ACTION_ASSIST as fallback. */
    public static void startClaudeAssist(Context ctx) {
        Intent explicit = new Intent(Intent.ACTION_ASSIST);
        explicit.setClassName("com.facebook.aura", "com.facebook.aura.main.AuraMainActivity");
        explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(explicit);
            return;
        } catch (Exception ignored) {
            // fall through to the implicit assist intent
        }
        Intent implicit = new Intent(Intent.ACTION_ASSIST);
        implicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(implicit);
        } catch (Exception ignored) {
            // Muse not installed / not resolvable: nothing sane to do
        }
    }
}
