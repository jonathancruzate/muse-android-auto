package com.jonathancruzate.musedrive;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Phone-side face of Muse Drive v2: explains the setup, shows
 *  live status, and doubles as an off-car test panel — every button
 *  fires exactly what the car surface / volume keys fire. No
 *  MacroDroid anywhere: control is in-app since v2. */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                + "open the Muse voice line.\n"
                + "Play = mic live - Pause = mute - Stop = hang up.\n\n"
                + "Eyes-free on the phone, once a session is armed (Talk or Play):\n"
                + "hold Volume DOWN = mute - hold Volume UP = unmute - "
                + "hold BOTH volume buttons = hang up.\n\n"
                + "One-time setup:\n"
                + "1. Enable the Muse for Android Auto accessibility service (hang-up tap + volume keys).\n"
                + "2. Android Auto > Developer settings > Unknown sources ON.\n");
        body.setTextSize(15);
        root.addView(body);

        root.addView(button("Enable accessibility service", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));

        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);

        TextView test = new TextView(this);
        test.setText("\nTest panel (same actions the car sends):");
        test.setTextSize(15);
        root.addView(test);

        root.addView(button("Talk to Muse", new View.OnClickListener() {
            @Override public void onClick(View v) { SessionControl.talk(MainActivity.this); refreshStatus(); }
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

        setContentView(root);
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
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean a11y = DriveAccessibilityService.instance != null || isA11yEnabled();
        status.setText("\nStatus: accessibility " + (a11y ? "ON" : "OFF")
                + " - mic " + (Mic.isMuted(this) ? "MUTED" : "live")
                + " - session " + (SessionState.armed ? "armed" : "not armed"));
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
