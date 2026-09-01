# Volume Boost App

App Android che amplifica il volume multimediale oltre il 100% utilizzando `LoudnessEnhancer`.

## Come funziona l'amplificazione

Un `AudioEffect` come `LoudnessEnhancer` viene creato su un **audio session id**. Il livello di boost è espresso in **decibel** (slider da 0 fino a un massimo configurabile dall'utente, predefinito **20 dB**, fino a **75 dB**). Il guadagno viene applicato in millibel (`dB × 100`) tramite `LoudnessEnhancer.setTargetGain`.

Mentre il boost è attivo, il servizio in foreground prova **tre percorsi in parallelo** per trovare la sessione a cui collegarsi: qualunque abbia successo è sufficiente per sentire l'amplificazione. `VolumeBoostManager` mantiene una mappa `session id → LoudnessEnhancer` e riapplica il livello dello slider a ogni sessione collegata.

In **Impostazioni** puoi scegliere quali tipi di notifica ombreggiata mostrare (una, entrambe o nessuna):

- **Controlli classici** (predefinito **attivo**) — RemoteViews con casella di abilitazione, barra di avanzamento e pulsanti −1 / +1 dB (`notification_boost.xml`). Le RemoteViews di Android non possono ospitare una SeekBar interattiva.
- **Barra di avanzamento multimediale** (predefinito **disattivata**) — Cursore in stile MediaStyle come YouTube/Spotify; la posizione corrisponde ai dB di boost.

Se il boost è attivo ed entrambe sono disattivate, Android richiede comunque una notifica foreground minima.

### Percorso 1 — Sessione globale `0` (best-effort, qualsiasi dispositivo)

L'id di sessione `0` storicamente indicava "il mix di output globale": un singolo effetto avrebbe elaborato tutto l'audio del dispositivo. Questa API è **deprecata**. Molte build Android moderne accettano ancora il collegamento di un `LoudnessEnhancer` alla sessione `0` e lo segnalano come abilitato, ma lo ignorano silenziosamente nella riproduzione reale. Alcune build OEM (determinati telefoni/tablet) lo rispettano ancora.

A ogni attivazione, `VolumeBoostManager.setEnabled` chiama sempre `attach(GLOBAL_AUDIO_SESSION)` con sessione `0`. Nessun root richiesto. Se questo percorso funziona sul dispositivo, l'audio multimediale può risultare amplificato anche quando i percorsi 2 e 3 non trovano nulla.

### Percorso 2 — Broadcast AudioEffect control-session (qualsiasi dispositivo)

I lettori multimediali conformi annunciano la sessione che stanno per utilizzare trasmettendo:

- `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` (avvio)
- `AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` (arresto)

`VolumeBoostService` registra un `BroadcastReceiver` a runtime esportato per queste azioni (`RECEIVER_EXPORTED`, richiesto da Android 13+ perché i broadcast provengono da altre app). All'evento **open** collega un `LoudnessEnhancer` a `EXTRA_AUDIO_SESSION`; all'evento **close** rilascia quell'effetto.

Nessun root richiesto. Funziona solo con i lettori che emettono il broadcast. Se il boost viene attivato **dopo** che la riproduzione è già iniziata, l'evento `OPEN` potrebbe essere stato perso: in tal caso questo percorso da solo non riuscirà a collegarsi finché la riproduzione non viene riavviata (oppure finché non intervengono il percorso 1 o 3).

### Percorso 3 — Individuazione AudioFlinger tramite root (dispositivi rootati)

Molte app (YouTube, Deezer, alcuni browser, …) non emettono mai i broadcast del percorso 2, quindi un booster basato solo sui broadcast non può raggiungerle. Un'app normale inoltre non può leggere gli ID di sessione delle altre app da `AudioPlaybackConfiguration` (il framework li rende anonimi).

Con root, `AudioSessionDiscovery` esegue `dumpsys media.audio_flinger`, analizza le tracce di output attive, mantiene quelle con uso **MEDIA**, e `VolumeBoostManager.syncActiveSessions` collega/scollega gli enhancer di conseguenza. Il servizio esegue una nuova scansione all'avvio/arresto della riproduzione e ogni pochi secondi.

Concedi i permessi di root (Magisk / simili) a `com.volumeboost.app` una volta. Questo percorso è ciò che rende il boost affidabile per YouTube/Deezer e per l'attivazione del boost **mentre** qualcosa è già in riproduzione. Senza root, i percorsi 1 e 2 vengono comunque eseguiti; il percorso 3 viene saltato.

## Funzionalità
- Slider del boost in dB (0 … max), max configurabile 20–75 dB (predefinito 20)
- Visualizzazione come `+15 dB` (non in percentuale)
- Tre strategie di collegamento (sessione 0, broadcast, root AudioFlinger)
- Il boost può essere attivato prima o durante la riproduzione (percorso 3; percorso 1 su alcuni OEM)
- Notifiche (Impostazioni): controlli classici e/o barra multimediale — predefiniti **classici attivi**, **multimediale disattivata**
- Tema (Impostazioni): chiaro / scuro — predefinito **scuro**
- Lingua (Impostazioni): inglese / italiano — predefinito **inglese**
- Servizio in foreground che mantiene il boost attivo in background

## Build
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```


---

## Licenza

Copyright (C) 2026 Luigi De Paola

Volume Boost App è software libero: puoi redistribuirlo e/o modificarlo secondo i termini della GNU General Public License v3.0 (GPL-3.0).

