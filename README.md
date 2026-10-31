# Muse Drive — Android Auto media app (v2.0.0, built 2026-10-31)

An Android Auto app for the driving voice project, built at
Jonathan's direct ask ("make an app for Android Auto") after the
route comparison showed a Muse *voice* app can't exist in Auto:
Google's calling/messaging car categories are closed/testing-only
and Meta Muse declares no car support at all. **Media is the one
Auto category a sideloaded app may use** — so Muse Drive is a
media app whose playback controls are repurposed as the voice
session's eyes-free controls.

## What it is

- Package `com.jonathancruzate.musedrive`, framework-only (no
  androidx): `MediaBrowserService` + framework `MediaSession`,
  `automotive_app_desc.xml` declaring `<uses name="media"/>`.
- In Auto it appears as a media source with one playable item,
  "Muse voice line" — a silent loop (res/raw/silence.wav). The
  transport controls are the product:
  - **Play = mic live** (starts loop + UNMUTE broadcast)
  - **Pause = mute** (the big Auto play/pause button IS the mute)
  - **Stop = hang up** (END broadcast)
  - Custom player actions: **Talk to Muse** (fires the REA-verified
    ungated ACTION_ASSIST intent into com.facebook.aura), **Mute**,
    **Hang up**.
- Mute/unmute/end are explicit broadcasts addressed to MacroDroid
  (`com.jonathancruzate.musedrive.MUTE/.UNMUTE/.END`); the driving
  macros carry matching IntentReceivedTriggers and do the real work
  (system mic mute via MuteMicrophoneAction; hang-up macro). No app
  can reach into Meta Muse's own session — this is the control
  surface, not the call.
- Phone-side: a media notification with Talk/Mute/Hang up actions,
  and MainActivity as an off-car test panel firing the same actions.

## Build

`./build.sh` — javac/d8/aapt2 via the metamuse-widget SDK
(read-only toolchain use; no emulator touched), own keystore in
`keystore/` (password file alongside, 0600). Output:
`Muse-Drive-1.0.0.apk`, signed, apksigner-verified; manifest dump
confirms the `com.google.android.gms.car.application` meta-data,
the exported MediaBrowserService, and all four action strings in
the DEX.

## Parked on-device test (Jonathan's hands, when not driving)

1. Install the APK; import the four driving macros.
2. Android Auto → Settings → Developer settings → **Unknown
   sources** ON (required for any sideloaded Auto app to appear).
3. In the car: select Muse Drive as the media source, open the
   Muse voice line. Checks: (a) it appears at all (framework
   MediaBrowserService acceptance is the big unknown — if Auto
   ignores it, v2 swaps in androidx.media's
   MediaBrowserServiceCompat); (b) Talk opens Muse on the phone;
   (c) Pause mutes the mic mid-session (MacroDroid buzz confirms);
   (d) Play unmutes; (e) Stop runs the hang-up macro; (f) whether
   the Auto player stays reachable while Muse voice holds the
   car's SCO channel, or the car treats the session as a call and
   swaps screens (in which case the volume-button macros remain
   the mute path and this app is the pre-drive launcher).

## v2.0.0 — no MacroDroid (same day, his order)

v1 forwarded mute/unmute/end to MacroDroid macros. He asked for
the same thing with **just an app** — v2 removes MacroDroid from
the path entirely (zero MacroDroid references in the DEX, verified):

- **Mute/unmute in-app**: `AudioManager.setMicrophoneMute()` —
  public SDK API (verified in android-35 android.jar), the same
  call MacroDroid's action wraps. State read back via
  `isMicrophoneMute()` and shown in the app.
- **Hang-up + volume keys via Muse Drive's own accessibility
  service** (one-time enable in Android Settings): key-event
  filtering gives the same gestures as the macros — hold Vol DOWN
  mute, hold Vol UP unmute, hold BOTH hang up — but only while a
  session is *armed* (after Talk/Play), so normal volume behavior
  is untouched otherwise; short presses still change volume.
  Hang-up hunts Muse's end-call control (view-id candidates from
  the REA pass, incl. `end_voice_button`, then text/description),
  fallback Back → Home.
- The four driving macros remain on disk as a v1 fallback but are
  NOT needed with v2.
- New parked-test checks: the accessibility end-tap landing on the
  real Muse voice screen (the candidates are REA-derived
  names/labels, unproven until seen on-device), and
  setMicrophoneMute starving the live session cleanly.
- Build note: he pointed at the Android SDK on the VPS; SSH to the
  box is currently refused for this VM's key (publickey denied for
  muse and root, direct and via tailnet), so v2 was built with the
  local read-only SDK exactly like v1. Box access is a main-chat
  VPS-migration item, not a build blocker.

## v2.1.0 — start the session without deep links (same day)

His on-device finding: the session can't be started through URI
deep links — `hatch://voice` is TrustedCaller-gated to Meta-signed
apps (the REA verdict), so Talk's ACTION_ASSIST landed in Muse's
text composer, not a live voice session.

Fix: Talk still opens Muse via ACTION_ASSIST, but now arms a
voice-button hunt — Muse Drive's accessibility service watches
for the Muse window, finds Muse's own voice button (view-id
candidates from the REA pass incl. `hatch_voice_button`, then
exact text/description labels) and taps it, starting the same
session the deep link would have. Retries for ~7s while the UI
renders; a short buzz confirms the tap landed. No URI is fired.

Built on the VPS (muse@167.233.41.12:~/muse-drive) with the
box's own Android SDK — the first build after the 2026-10-31 SSH
repair; same signing key, upgrades in place.
