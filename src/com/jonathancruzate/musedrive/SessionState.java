package com.jonathancruzate.musedrive;

/** Process-wide session state for Muse Drive v2.
 *
 *  armed: a Muse voice session is (believed) live — set by Talk /
 *  Play, cleared by End / Stop. The accessibility service only
 *  intercepts volume keys while armed, so normal volume behavior
 *  is untouched the rest of the day.
 *  micMuted: last known system microphone mute state. */
public final class SessionState {
    public static volatile boolean armed = false;
    public static volatile boolean micMuted = false;
    /** v2.1: set by talk() — the accessibility service is hunting
     *  Muse's in-app voice button to tap it (URI deep links into
     *  Muse voice are TrustedCaller-gated, so the session is
     *  started by opening Muse and tapping its own button). */
    public static volatile boolean awaitingVoiceTap = false;

    private SessionState() {}
}
