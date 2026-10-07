# Volume Boost App

Android app that amplifies media volume beyond 100% using `LoudnessEnhancer`.

## How the boost works

An `AudioEffect` such as `LoudnessEnhancer` is created against an **audio session id**. The boost level is chosen in **decibel** (slider from 0 up to a user-configurable maximum, default **20 dB**, up to **75 dB**). Gain is applied as millibels (`dB × 100`) via `LoudnessEnhancer.setTargetGain`.

While boost is on, the foreground service attaches a `LoudnessEnhancer` from up to **three sources** and combines them so that every player is boosted **exactly once**. `VolumeBoostManager` keeps a `session id → LoudnessEnhancer` map and re-applies the slider level to every attached session.

In **Settings** you can choose which shade notifications to show (either, both, or neither):

- **Classic controls** (default **on**) — RemoteViews with enable checkbox, progress bar, and −1 / +1 dB buttons (`notification_boost.xml`). Android RemoteViews cannot host an interactive SeekBar.
- **Media seek bar** (default **off**) — MediaStyle scrubber like YouTube/Spotify; position maps to boost dB.

If boost is on and both are off, Android still requires a minimal ongoing foreground notification.

### The three sources

#### 1. Global session `0` — immediate

Session id `0` historically meant “the global output mix”. As soon as boost is turned on, a `LoudnessEnhancer` is attached to it, so on builds that still honour it **any audio that starts later is already boosted**, with no delay. The API is deprecated and support varies:

- **MIUI 14** honours it: the audio policy moves global effects to the output that is playing music (`moveEffects session 0` in the log), so YouTube is boosted at once. Players on another output (VLC) are not reached.
- **LineageOS 20** ignores it on its own: measured **0 dB**.

That is why session 0 is only the first layer: sources 2 and 3 find the players it misses.

#### 2. Player broadcasts — players that announce themselves

Compliant players (VLC, some music players) announce the session they are about to use with `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and release it with `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`. `VolumeBoostService` listens with an exported runtime `BroadcastReceiver` (`RECEIVER_EXPORTED`, required on Android 13+) and attaches an enhancer to `EXTRA_AUDIO_SESSION`. No root and no permission needed.

#### 3. Session discovery — every other player

YouTube, Deezer, browsers and many others never send that broadcast, and Android hides other apps' session ids (`AudioPlaybackConfiguration` reports `0`). But any app can attach an effect to another app's session if it knows the id, so the app only has to find the ids. **Settings → Session detection** shows which method is active:

1. **Root.** `AudioSessionDiscovery` runs `dumpsys media.audio_flinger` in a single long-lived `su` shell (no Magisk toast every few seconds) and keeps output tracks with usage **MEDIA**. It rescans when playback starts/stops and every 2 s. Exact: it knows which tracks are media.
2. **Audio detection — no root, one tap.** Needs the `RECORD_AUDIO` runtime permission; the app explains why and recommends **“While using the app”** (an “Only this time” grant is revoked when the app closes). The microphone is never opened and Android shows no privacy indicator. `VisualizerSessionScanner`:
   - knows where to look: AudioFlinger allocates session ids from one sequential counter (9, 17, 25, … step 8) and `AudioManager.generateAudioSessionId()` returns the next one, so every existing session is below it;
   - meters candidates with a `Visualizer` each, in batches of up to 64 for 250 ms: only sessions with audio flowing report a peak level. Order: sessions already attached, the 128 newest ids (a player that just started), then the next 384 ids of a deep pass over older ids (a player created long ago, e.g. resumed), continued by the following scans;
   - runs when playback changes (300 ms after), not on a timer, and retries with backoff (1.5 s … 30 s) while a media player is still not found. It waits while a ringtone, alarm or notification plays, so they are not boosted by mistake;
   - copes with builds that refuse to enable some meters (MIUI): they are retried once in smaller batches, and no metering error can stop the service.

   Silent sessions stay attached (they may only be paused); the 8 most recently heard are kept.

### Combining them: one boost per player

A player must not get both the global enhancer and its own: measured on LineageOS, once a track goes through a session effect chain its audio also passes the session 0 chain, so **+10 dB became +20 dB**. With a maximum of 75 dB that would be dangerous for ears and speakers. How session 0 is combined with real sessions depends on what the app can measure (`VolumeBoostManager.GlobalMode`):

| Session detection | Session 0 | Each real session |
|---|---|---|
| Root | Off | Full boost (root finds every media track) |
| Audio detection | **Always on** | **Measured:** 0 dB if session 0 already boosts it, full boost if not |
| None (no root, no permission) | Only while no real session is attached | Full boost |

The measurement uses the same scan. With each batch the scanner also meters the **output mix** (a `Visualizer` on session 0, created after the global enhancer, so it reads the boosted mix) and compares it with each session it hears:

- mix louder than the session by **at least half the boost** (capped at 3 dB, so a limiter squeezing loud audio still counts) → session 0 already boosts it → its own enhancer stays at **0 dB**;
- otherwise (same level, or far quieter because the player is on another output) → full boost;
- mix not measurable → treated as already boosted. A missing boost is safer than a doubled one.

A covered session keeps an **enabled enhancer at 0 dB** rather than none: on LineageOS session 0 reaches a track only while that track has a session effect chain. A session announced by broadcast also starts at 0 dB and gets its gain at the next scan, a fraction of a second after it starts playing. Every scan measures attached sessions again, because coverage can change: MIUI moves the global effect to whichever output is playing music.

### What happens when you press play

| Phone | Player | Result |
|---|---|---|
| MIUI 14 | YouTube (deep-buffer output) | Boosted **immediately** by session 0; the scan then measures it as covered and leaves its enhancer at 0 dB |
| MIUI 14 | VLC (another output) | Session 0 does not reach it; the scan measures it as not covered and gives it the full boost ~0.5 s after it starts |
| LineageOS 20 | Any | Session 0 alone does nothing; the player is found in ~1 s (also with the screen off) and boosted once |
| Rooted | Any | Found by the AudioFlinger dump within ~2 s; session 0 not used |

> Measured on MIUI 14 (Xiaomi Redmi Note 12, boost +33 dB): YouTube sessions read **+3.8 … +17.7 dB** louder in the mix than in the session → covered; a session on another output read **−41 dB** → full boost. On LineageOS 20 (Redmi Note 9 Pro): a +10 dB setting measured exactly **+10.0 dB**, and a player whose session was ~1200 ids old was found by the third scan, ~25 s after it resumed.

## Features
- Boost slider in dB (0 … max), max configurable 20–75 dB (default 20)
- Display as `+15 dB` (not percent)
- Attach strategies: global session 0 (immediate where honoured), player broadcasts, session discovery via root or audio detection (works without root and without a computer); each player is boosted once
- Boost can be enabled before or during playback
- Notifications (Settings): classic controls and/or media seek bar — defaults **classic on**, **media off**
- Theme (Settings): light / dark — default **dark**
- Language (Settings): English / Italian — default **English**
- Foreground service keeps boost active in background

## Build
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```


---

## License

Copyright (C) 2026 Luigi De Paola

Volume Boost App is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License v3.0 (GPL-3.0).


