package com.jonathancruzate.metamuseandroidauto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/** v2.5.5: DYNAMIC Muse avatar art.
 *
 *  The album art for this app is the CURRENT USER'S Muse avatar —
 *  never a specific person's image baked into the source. Resolution
 *  order (degrade-never-crash at every step):
 *
 *    1. A user-chosen image copied into app-private storage
 *       (filesDir/muse_avatar.jpg) by MainActivity's "Set your Muse
 *       avatar" picker — any user picks THEIR own Muse's avatar
 *       (e.g. an image saved/exported from their own Muse app).
 *    2. The bundled generic placeholder (R.drawable.album_art) — a
 *       non-person "M" mark that ships in the source tree.
 *
 *  A third, agent-side lane exists at BUILD time only: build.sh's
 *  MUSE_AVATAR_FILE injection swaps the bundled art for one build
 *  and restores the generic source afterwards (see build.sh). That
 *  lane never changes what is committed.
 */
public final class MuseAvatar {

    public static final String FILE_NAME = "muse_avatar.jpg";
    private static final int SIZE = 512;

    private MuseAvatar() {}

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    public static boolean hasCustom(Context ctx) {
        try { return file(ctx).isFile() && file(ctx).length() > 0; }
        catch (Throwable t) { return false; }
    }

    /** The art to show: user-chosen avatar first, generic bundled
     *  placeholder second, null if even that fails (callers treat
     *  null as "no art", exactly as before v2.5.3). */
    public static Bitmap load(Context ctx) {
        // 1) user-chosen avatar in app-private storage
        try {
            File f = file(ctx);
            if (f.isFile() && f.length() > 0) {
                FileInputStream in = new FileInputStream(f);
                try {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) return b;
                } finally { in.close(); }
            }
        } catch (Throwable t) {
            CrashLog.record(ctx, "MuseAvatar.load(custom)", t);
        }
        // 2) bundled generic placeholder
        try {
            return BitmapFactory.decodeResource(ctx.getResources(), R.drawable.album_art);
        } catch (Throwable t) {
            CrashLog.record(ctx, "MuseAvatar.load(bundled)", t);
            return null;
        }
    }

    /** Copy the picker-chosen image into app-private storage as a
     *  center-cropped 512x512 JPEG. Returns false on any failure
     *  (previous custom art, if any, is left untouched on failure
     *  because the write goes to a temp file first). */
    public static boolean saveFromUri(Context ctx, Uri uri) {
        Bitmap src = null;
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            try { src = BitmapFactory.decodeStream(in); } finally { in.close(); }
            if (src == null) return false;

            int side = Math.min(src.getWidth(), src.getHeight());
            if (side <= 0) return false;
            int x = (src.getWidth() - side) / 2;
            int y = (src.getHeight() - side) / 2;
            Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(src, new Rect(x, y, x + side, y + side),
                    new Rect(0, 0, SIZE, SIZE), paint);

            File tmp = new File(ctx.getFilesDir(), FILE_NAME + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try { out.compress(Bitmap.CompressFormat.JPEG, 90, fos); }
            finally { fos.close(); }
            if (!tmp.renameTo(file(ctx))) {
                // Same-filesystem rename should not fail; fall back to it existing check.
                if (!file(ctx).exists()) return false;
            }
            return true;
        } catch (Throwable t) {
            CrashLog.record(ctx, "MuseAvatar.saveFromUri", t);
            return false;
        }
    }

    /** Remove the user-chosen avatar; art falls back to the generic
     *  bundled placeholder. */
    public static boolean clear(Context ctx) {
        try {
            File f = file(ctx);
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            CrashLog.record(ctx, "MuseAvatar.clear", t);
            return false;
        }
    }
}
