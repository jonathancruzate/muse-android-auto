package com.jonathancruzate.metamuseandroidauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.List;

/** Muse for Android Auto — an Android Auto *media* app, because media is the
 *  one Auto category a sideloaded app may actually use (Google's
 *  calling/messaging car categories are closed/testing-only).
 *
 *  What Auto shows: Muse for Android Auto as a media source with ONE playable
 *  item — a silent loop. The transport controls are the point:
 *
 *    Play   = mic live   (silent loop + system mic unmute)
 *    Pause  = MUTE       (the big obvious Auto button is the mute)
 *    Stop   = hang up    (accessibility end-call tap)
 *    Custom actions: Talk to Muse (ASSIST intent), Mute, Hang up.
 *
 *  v2: NO MacroDroid anywhere in the path. All session control is
 *  in-app (SessionControl / Mic / DriveAccessibilityService) —
 *  see those classes. This service owns the media session, the
 *  silent player, and the phone notification; it reflects state
 *  changes that arrive from the other surfaces (volume keys,
 *  notification actions, test panel).
 *
 *  v2.2.0: migrated from the framework
 *  android.service.media.MediaBrowserService to
 *  androidx.media.MediaBrowserServiceCompat (+ MediaSessionCompat).
 *  Google's current Android-for-Cars docs define the media browser
 *  service contract as the MediaBrowserServiceCompat API, and every
 *  official sample uses it. The framework-only service was still
 *  reachable through Android Auto's media *resumption* path (the
 *  session reappeared when Auto started) but was not reliably
 *  enumerated into the Auto launcher / media-source list, so once
 *  the driver left the session there was no way back in from the
 *  Auto UI. The compat service serves both framework and compat
 *  MediaBrowser clients from one binder.
 *
 *  v2.2.1: launch hardening. On the S24+ (One UI 9 / Android 16)
 *  v2.2.0 would not open at all: the androidx session init in
 *  onCreate can throw on that device build, and because
 *  MainActivity started this service synchronously at the end of
 *  its own onCreate, the uncaught throw killed the whole process
 *  before the first frame. onCreate now wraps ALL initialization
 *  so any failure degrades to an inert service (logged, session
 *  left null, every entry point null-guarded) instead of crashing
 *  the process, and MainActivity defers the start until after its
 *  first frame. */
public class DriveMediaService extends MediaBrowserServiceCompat {

    private static final String TAG = "MuseDrive";
    private static final String ROOT_ID = "muse_drive_root";
    private static final String LINE_ID = "muse_voice_line";
    private static final String CHANNEL_ID = "muse_drive";
    private static final int NOTIF_ID = 42;

    public static volatile DriveMediaService active;

    /** True when onCreate init failed and the service degraded to
     *  inert (v2.2.1). The phone UI stays up either way. */
    public static volatile boolean initFailed;

    private MediaSessionCompat session;
    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        active = this;
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            session = new MediaSessionCompat(this, "MuseDrive");
            session.setCallback(new SessionCallback());
            session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Muse voice line")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Muse for Android Auto")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Play = mic live, Pause = mute, Stop = hang up")
                    .build());
            setSessionToken(session.getSessionToken());
            updateState(PlaybackStateCompat.STATE_NONE);
            session.setActive(true);

            player = newPlayer();
        } catch (Throwable t) {
            // Degrade, never crash: an uncaught throw here kills the
            // whole process (including MainActivity's first frame).
            initFailed = true;
            Log.e(TAG, "DriveMediaService init failed; service inert", t);
            if (session != null) {
                try { session.release(); } catch (Throwable ignored) {}
                session = null;
            }
            player = null;
        }
        try {
            createChannel();
        } catch (Throwable t) {
            Log.e(TAG, "Notification channel creation failed", t);
        }
    }

    private MediaPlayer newPlayer() {
        try {
            MediaPlayer p = MediaPlayer.create(this, R.raw.silence);
            if (p != null) {
                p.setLooping(true);
                p.setVolume(0f, 0f);
                // An unhandled player error leaves the player in the
                // error state where every later call throws; consume
                // it and let the reflect* guards recreate the player.
                p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                        return true;
                    }
                });
            }
            return p;
        } catch (Throwable t) {
            Log.e(TAG, "MediaPlayer.create failed", t);
            return null;
        }
    }

    // ---- MediaBrowserServiceCompat ----

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        // v2.3.1: guarded — a throw here is called by Android Auto /
        // the system browser client, and an uncaught throw reads to
        // the user as the app "crashing" in the car.
        try {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            if (ROOT_ID.equals(parentId)) {
                MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                        .setMediaId(LINE_ID)
                        .setTitle("Muse voice line")
                        .setSubtitle("Play = mic live - Pause = mute - Stop = hang up")
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
            result.sendResult(items);
        } catch (Throwable t) {
            CrashLog.record(this, "onLoadChildren", t);
            try { result.sendResult(new ArrayList<MediaBrowserCompat.MediaItem>()); }
            catch (Throwable ignored) {}
        }
    }

    // ---- transport -> SessionControl ----

    private class SessionCallback extends MediaSessionCompat.Callback {
        @Override public void onPlay() { SessionControl.unmute(DriveMediaService.this); }
        @Override public void onPause() { SessionControl.mute(DriveMediaService.this); }
        @Override public void onStop() { SessionControl.end(DriveMediaService.this); }
        @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
            SessionControl.unmute(DriveMediaService.this);
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            if (DriveActions.ACTION_TALK.equals(action)) {
                SessionControl.talk(DriveMediaService.this);
            } else if (DriveActions.ACTION_MUTE.equals(action)) {
                SessionControl.mute(DriveMediaService.this);
            } else if (DriveActions.ACTION_UNMUTE.equals(action)) {
                SessionControl.unmute(DriveMediaService.this);
            } else if (DriveActions.ACTION_END.equals(action)) {
                SessionControl.end(DriveMediaService.this);
            }
        }
    }

    // ---- state reflection (called by SessionControl) ----

    // v2.3.1: every reflect* entry point is catch-Throwable guarded
    // and every MediaPlayer call goes through a state-safe helper.
    // Pre-2.3.1 these ran unguarded: MediaPlayer throws
    // IllegalStateException when a call lands in the wrong state
    // (start/pause/stop/isPlaying after release, stop before
    // prepare, a focus-change pause racing a Stop, ...), and the
    // callers are Android Auto transport callbacks, the
    // accessibility service, and broadcast receivers — so one bad
    // state killed the whole process, repeatedly, which is exactly
    // Samsung Device Care's "crashing frequently" signature.

    private boolean playerPlaying() {
        try { return player != null && player.isPlaying(); }
        catch (Throwable t) { CrashLog.record(this, "player.isPlaying", t); return false; }
    }

    private void playerStart() {
        try { if (player != null && !player.isPlaying()) player.start(); }
        catch (Throwable t) {
            CrashLog.record(this, "player.start", t);
            recreatePlayer();
            try { if (player != null) player.start(); } catch (Throwable ignored) {}
        }
    }

    private void playerPause() {
        try { if (player != null && player.isPlaying()) player.pause(); }
        catch (Throwable t) { CrashLog.record(this, "player.pause", t); }
    }

    private void recreatePlayer() {
        try { if (player != null) player.release(); } catch (Throwable ignored) {}
        player = newPlayer();
    }

    public void reflectPlaying() {
        try {
            requestFocus();
            playerStart();
            updateState(PlaybackStateCompat.STATE_PLAYING);
            postNotification(true);
        } catch (Throwable t) { CrashLog.record(this, "reflectPlaying", t); }
    }

    public void reflectPaused() {
        try {
            playerPause();
            updateState(PlaybackStateCompat.STATE_PAUSED);
            postNotification(false);
        } catch (Throwable t) { CrashLog.record(this, "reflectPaused", t); }
    }

    public void reflectStopped() {
        try {
            // Never stop() blind: after a pause/focus-loss the player
            // may not be in a stoppable state. Pause + recreate is
            // state-safe in every state the player can be in.
            playerPause();
            updateState(PlaybackStateCompat.STATE_STOPPED);
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIF_ID);
            } catch (Throwable ignored) {}
            abandonFocus();
            recreatePlayer();
        } catch (Throwable t) { CrashLog.record(this, "reflectStopped", t); }
    }

    private void updateState(int state) {
        if (session == null) return; // inert after failed init (v2.2.1)
        try { updateStateInner(state); }
        catch (Throwable t) { CrashLog.record(this, "updateState", t); }
    }

    private void updateStateInner(int state) {
        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP)
                .addCustomAction(DriveActions.ACTION_TALK, "Talk to Muse",
                        android.R.drawable.ic_btn_speak_now)
                .addCustomAction(DriveActions.ACTION_MUTE, "Mute",
                        android.R.drawable.ic_lock_silent_mode)
                .addCustomAction(DriveActions.ACTION_END, "Hang up",
                        android.R.drawable.ic_menu_close_clear_cancel)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        session.setPlaybackState(b.build());
    }

    // ---- audio focus (gentle: never fight the Muse session) ----

    private void requestFocus() {
        try {
            if (audioManager == null) return;
            if (focusRequest != null) return; // already held; don't stack requests
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) playerPause();
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } catch (Throwable t) { CrashLog.record(this, "requestFocus", t); }
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
        } catch (Throwable t) { CrashLog.record(this, "abandonFocus", t); }
    }

    // ---- phone-side notification (same controls, off-car) ----

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Muse for Android Auto", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(action);
        i.setClass(this, DriveReceiver.class);
        return PendingIntent.getBroadcast(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void postNotification(boolean live) {
        if (session == null) return; // inert after failed init (v2.2.1)
        try { postNotificationInner(live); }
        catch (Throwable t) { CrashLog.record(this, "postNotification", t); }
    }

    private void postNotificationInner(boolean live) {
        // Notification.MediaStyle needs the framework token; the compat
        // token wraps it (API 21+ getToken() returns the framework token).
        // v2.3.1: the cast is no longer blind — a null / non-framework
        // token degrades to a notification without MediaStyle instead
        // of throwing ClassCastException/NullPointerException.
        MediaSession.Token fwToken = null;
        try {
            Object tok = session.getSessionToken().getToken();
            if (tok instanceof MediaSession.Token) fwToken = (MediaSession.Token) tok;
        } catch (Throwable ignored) {}
        Notification.Builder nb = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Muse for Android Auto")
                .setContentText(live ? "Mic live" : "Muted");
        if (fwToken != null) {
            nb.setStyle(new Notification.MediaStyle().setMediaSession(fwToken));
        }
        nb.addAction(android.R.drawable.ic_btn_speak_now, "Talk",
                        actionIntent(DriveActions.ACTION_TALK, 1))
                .addAction(android.R.drawable.ic_lock_silent_mode, "Mute",
                        actionIntent(DriveActions.ACTION_MUTE, 2))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up",
                        actionIntent(DriveActions.ACTION_END, 3))
                .setOngoing(live);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, nb.build());
    }

    @Override
    public void onDestroy() {
        if (active == this) active = null;
        try { if (player != null) player.release(); } catch (Throwable ignored) {}
        player = null;
        try { if (session != null) session.release(); } catch (Throwable ignored) {}
        session = null;
        super.onDestroy();
    }
}
