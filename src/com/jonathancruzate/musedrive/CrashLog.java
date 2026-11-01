package com.jonathancruzate.musedrive;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** v2.3.1 crash capture. The v2.2.0 "won't open" crash and the
 *  v2.3.0 "crashing frequently" reports both reached us with NO
 *  stack trace — the adb lane is retired/unreliable and Android
 *  does not expose another app's logcat, so every diagnosis was a
 *  theory. This class closes that gap:
 *
 *  - Every guarded entry point records caught throwables here
 *    (record()).
 *  - A default uncaught-exception handler records the trace
 *    BEFORE delegating to the system handler, so even a fatal
 *    crash leaves its trace on disk.
 *  - MainActivity shows the latest recorded trace on screen, so
 *    Jonathan can screenshot it back to Muse — a screenshot is
 *    the evidence path that was already identified on 2026-10-31.
 *
 *  Storage: app-private files dir (last_crash.txt, newest first
 *  entry wins; kept small). No network, no external sharing. */
public final class CrashLog {

    private static final String TAG = "MuseDrive";
    private static final String FILE = "last_crash.txt";
    private static volatile boolean installed = false;

    private CrashLog() {}

    public static void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    write(app, "UNCAUGHT on " + t.getName(), e);
                } catch (Throwable ignored) {}
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    public static void record(Context ctx, String where, Throwable e) {
        try {
            Log.e(TAG, "guarded failure in " + where, e);
            if (ctx != null) write(ctx.getApplicationContext(), where, e);
        } catch (Throwable ignored) {}
    }

    private static void write(Context app, String where, Throwable e) throws Exception {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date());
        String text = stamp + " — " + where + "\n" + sw.toString();
        File f = new File(app.getFilesDir(), FILE);
        FileOutputStream out = new FileOutputStream(f, false);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }

    /** Latest recorded trace, or "" when none. */
    public static String read(Context ctx) {
        try {
            File f = new File(ctx.getApplicationContext().getFilesDir(), FILE);
            if (!f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void clear(Context ctx) {
        try {
            new File(ctx.getApplicationContext().getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {}
    }
}
