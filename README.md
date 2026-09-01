# Volume Boost App

Android app that amplifies media volume beyond 100% using `LoudnessEnhancer`.

## How the boost works

An `AudioEffect` such as `LoudnessEnhancer` is created against an **audio session id**. The boost level is chosen in **decibel** (slider from 0 up to a user-configurable maximum, default **20 dB**, up to **75 dB**). Gain is applied as millibels (`dB × 100`) via `LoudnessEnhancer.setTargetGain`.

While boost is on, the foreground service tries **three paths in parallel** to find which session to attach — whichever succeeds is enough to hear the boost. `VolumeBoostManager` keeps a `session id → LoudnessEnhancer` map and re-applies the slider level to every attached session.

In **Settings** you can choose which shade notifications to show (either, both, or neither):

- **Classic controls** (default **on**) — RemoteViews with enable checkbox, progress bar, and −1 / +1 dB buttons (`notification_boost.xml`). Android RemoteViews cannot host an interactive SeekBar.
- **Media seek bar** (default **off**) — MediaStyle scrubber like YouTube/Spotify; position maps to boost dB.

If boost is on and both are off, Android still requires a minimal ongoing foreground notification.

### Path 1 — Global session `0` (best-effort, any device)

Session id `0` historically meant “the global output mix”: one effect would process all audio on the device. That API is **deprecated**. Many modern Android builds still accept attaching a `LoudnessEnhancer` to session `0` and report it as enabled, but silently ignore it for real playback. Some OEM builds (certain phones/tablets) still honour it.

On every enable, `VolumeBoostManager.setEnabled` always calls `attach(GLOBAL_AUDIO_SESSION)` with session `0`. No root required. If this path works on the device, media can sound boosted even when paths 2 and 3 find nothing.

### Path 2 — AudioEffect control-session broadcasts (any device)

Compliant media players announce the session they are about to use by broadcasting:

- `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` (start)
- `AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` (stop)

`VolumeBoostService` registers an exported runtime `BroadcastReceiver` for those actions (`RECEIVER_EXPORTED`, required on Android 13+ because the broadcasts come from other apps). On **open** it attaches a `LoudnessEnhancer` to `EXTRA_AUDIO_SESSION`; on **close** it releases that effect.

No root required. Works only for players that emit the broadcast. If boost is enabled **after** playback already started, the `OPEN` event may have been missed — then this path alone will not attach until playback is restarted (or path 1 / path 3 covers it).

### Path 3 — Root AudioFlinger discovery (rooted devices)

Many apps (YouTube, Deezer, some browsers, …) never emit the path-2 broadcasts, so a broadcast-only booster cannot reach them. A normal app also cannot read other apps’ session IDs from `AudioPlaybackConfiguration` (the framework anonymizes them).

With root, `AudioSessionDiscovery` runs `dumpsys media.audio_flinger`, parses active output tracks, keeps those with usage **MEDIA**, and `VolumeBoostManager.syncActiveSessions` attaches/detaches enhancers accordingly. The service rescans when playback starts/stops and every few seconds.

Grant root (Magisk / similar) to `com.volumeboost.app` once. This path is what makes boost reliable for YouTube/Deezer and for enabling boost **while** something is already playing. Without root, paths 1 and 2 still run; path 3 is skipped.

## Features
- Boost slider in dB (0 … max), max configurable 20–75 dB (default 20)
- Display as `+15 dB` (not percent)
- Three attach strategies (session 0, broadcasts, root AudioFlinger)
- Boost can be enabled before or during playback (path 3; path 1 on some OEMs)
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

Volume Power App is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License v3.0 (GPL-3.0).


