package com.jonathancruzate.metamuseandroidauto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Phone-side face of Muse for Android Auto v2: explains the setup, shows
 *  live status, and doubles as an off-car test panel — every button
 *  fires exactly what the car surface / volume keys fire. No
 *  MacroDroid anywhere: control is in-app since v2. */
public class MainActivity extends Activity {

    private static final int REQ_PICK_AVATAR = 4711;

    private TextView status;
    private TextView crashView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        SessionState.load(this); // v2.5.2: persisted mic-toggle state

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Muse for Android Auto");
        title.setTextSize(24);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("\nIn the car: open Android Auto, pick Muse for Android Auto as the media source, "
                + "open Muse Voice Mode. The car shows four controls:\n"
                + "- Microphone button (Talk to Muse) = the voice toggle: press once to start "
                + "Muse Voice Mode (opens Muse and starts a voice session); press it again "
                + "to hang up and end the session.\n"
                + "- Play / Pause = your microphone only, once a session is running: "
                + "Play = mic live, Pause = mic muted. It does not start or end the session.\n"
                + "- Mute button = the same mute as Pause, in one tap.\n"
                + "- Stop / Hang up = end the voice session.\n\n"
                + "Eyes-free on the phone, once a session is armed (Talk or Play):\n"
                + "hold Volume DOWN = mute - hold Volume UP = unmute - "
                + "hold BOTH volume buttons = hang up.\n\n"
                + "One-time setup:\n"
                + "1. Enable the Muse for Android Auto accessibility service (hang-up tap + volume keys).\n"
                + "2. Android Auto > Developer settings > Unknown sources ON.\n"
                + "3. Screen off is OK: Talk wakes the phone first. If your lock screen is secure "
                + "(fingerprint / PIN), Android shows its unlock prompt and the session starts "
                + "right after you unlock. No app can skip a secure lock; for fully hands-free "
                + "starts, set Extend Unlock (Smart Lock) to keep the phone unlocked on your "
                + "car's Bluetooth.\n");
        body.setTextSize(15);
        root.addView(body);

        root.addView(button("Enable accessibility service", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));

        // v2.5.5: DYNAMIC avatar art. The picture Android Auto shows
        // for this app is YOUR Muse's avatar — pick an image of it
        // here (e.g. one saved/exported from your own Muse app) and
        // it is copied into this app's private storage and used as
        // the album art. Nothing personal is baked into the app:
        // until you pick one, a generic placeholder is shown.
        TextView avatarText = new TextView(this);
        avatarText.setText("\nYour Muse avatar (shown in the car): pick an image of your own "
                + "Muse's avatar and this app will use it as its picture in Android Auto. "
                + "If the media service is already running the new picture applies right away; "
                + "otherwise it applies the next time the service starts.");
        avatarText.setTextSize(15);
        root.addView(avatarText);

        root.addView(button("Set your Muse avatar", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    startActivityForResult(pick, REQ_PICK_AVATAR);
                } catch (Throwable t) {
                    CrashLog.record(MainActivity.this, "pickAvatar", t);
                    Toast.makeText(MainActivity.this, "No image picker available", Toast.LENGTH_SHORT).show();
                }
            }
        }));
        root.addView(button("Use generic avatar (clear mine)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean ok = MuseAvatar.clear(MainActivity.this);
                if (DriveMediaService.active != null) DriveMediaService.active.refreshAlbumArt();
                Toast.makeText(MainActivity.this, ok ? "Back to the generic avatar" : "Nothing to clear", Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        }));

        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);

        TextView test = new TextView(this);
        test.setText("\nTest panel (same actions the car sends):");
        test.setTextSize(15);
        root.addView(test);

        root.addView(button("Talk to Muse / hang up (mic toggle)", new View.OnClickListener() {
            @Override public void onClick(View v) { SessionControl.toggleTalk(MainActivity.this); refreshStatus(); }
        }));
        root.addView(button("Unmute (mic live)", new View.OnClickListener() {
            @Override public void onClick(View v) { SessionControl.unmute(MainActivity.this); refreshStatus(); }
        }));
        root.addView(button("Mute", new View.OnClickListener() {
            @Override public void onClick(View v) { SessionControl.mute(MainActivity.this); refreshStatus(); }
        }));
        root.addView(button("Hang up", new View.OnClickListener() {
            @Override public void onClick(View v) { SessionControl.end(MainActivity.this); refreshStatus(); }
        }));

        // v2.3.1 diagnostics: if the app crashed / a guarded path
        // failed since last install, the trace is shown here so it
        // can be screenshotted back to Muse (no logcat access from
        // Muse's side — this panel IS the crash report).
        crashView = new TextView(this);
        crashView.setTextSize(11);
        root.addView(crashView);
        root.addView(button("Clear crash log", new View.OnClickListener() {
            @Override public void onClick(View v) { CrashLog.clear(MainActivity.this); refreshStatus(); }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        // v2.2.1: start the media service AFTER the first frame, off
        // onCreate's critical path. A synchronous startService here
        // runs DriveMediaService.onCreate inline; if that init ever
        // throws on a given device build, the process dies before
        // this UI draws (the v2.2.0 "won't even open" failure).
        root.post(new Runnable() {
            @Override public void run() {
                try {
                    startService(new Intent(MainActivity.this, DriveMediaService.class));
                } catch (Exception e) {
                    // Degrade: the test panel still works without the service.
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_AVATAR) return;
        try {
            Uri uri = (data != null) ? data.getData() : null;
            if (resultCode != RESULT_OK || uri == null) return; // picker cancelled: nothing changes
            boolean ok = MuseAvatar.saveFromUri(this, uri);
            if (ok && DriveMediaService.active != null) DriveMediaService.active.refreshAlbumArt();
            Toast.makeText(this, ok ? "Your Muse avatar is set" : "That image could not be used", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            CrashLog.record(this, "onActivityResult(avatar)", t);
        }
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        try {
            boolean a11y = DriveAccessibilityService.instance != null || isA11yEnabled();
            status.setText("\nStatus: accessibility " + (a11y ? "ON" : "OFF")
                    + " - mic " + (Mic.isMuted(this) ? "MUTED" : "live")
                    + " - session " + (SessionState.armed ? "armed" : "not armed")
                    + " - avatar " + (MuseAvatar.hasCustom(this) ? "yours (custom)" : "generic")
                    + (DriveMediaService.initFailed ? " - media service INIT FAILED" : ""));
        } catch (Throwable t) {
            CrashLog.record(this, "refreshStatus", t);
        }
        if (crashView != null) {
            String crash = CrashLog.read(this);
            crashView.setText(crash.length() == 0
                    ? "\nCrash log: empty (no crash recorded by v2.3.1+)."
                    : "\nLast recorded crash / failure — screenshot this for Muse:\n\n" + crash);
        }
    }

    private boolean isA11yEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String mine = new ComponentName(this, DriveAccessibilityService.class).flattenToString();
            return enabled.contains(mine);
        } catch (Exception e) {
            return false;
        }
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(onClick);
        return b;
    }
}
