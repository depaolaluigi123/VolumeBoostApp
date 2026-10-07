# Volume Boost App

App Android che amplifica il volume multimediale oltre il 100% utilizzando `LoudnessEnhancer`.

## Come funziona l'amplificazione

Un `AudioEffect` come `LoudnessEnhancer` viene creato su un **audio session id**. Il livello di boost è espresso in **decibel** (slider da 0 fino a un massimo configurabile dall'utente, predefinito **20 dB**, fino a **75 dB**). Il guadagno viene applicato in millibel (`dB × 100`) tramite `LoudnessEnhancer.setTargetGain`.

Mentre il boost è attivo, il servizio in foreground collega un `LoudnessEnhancer` da un massimo di **tre fonti** e le combina in modo che ogni player venga amplificato **una sola volta**. `VolumeBoostManager` mantiene una mappa `session id → LoudnessEnhancer` e riapplica il livello dello slider a ogni sessione collegata.

In **Impostazioni** puoi scegliere quali tipi di notifica ombreggiata mostrare (una, entrambe o nessuna):

- **Controlli classici** (predefinito **attivo**) — RemoteViews con casella di abilitazione, barra di avanzamento e pulsanti −1 / +1 dB (`notification_boost.xml`). Le RemoteViews di Android non possono ospitare una SeekBar interattiva.
- **Barra di avanzamento multimediale** (predefinito **disattivata**) — Cursore in stile MediaStyle come YouTube/Spotify; la posizione corrisponde ai dB di boost.

Se il boost è attivo ed entrambe sono disattivate, Android richiede comunque una notifica foreground minima.

### Le tre fonti

#### 1. Sessione globale `0` — immediata

L'id di sessione `0` storicamente indicava "il mix di output globale". Appena si attiva il boost, le viene collegato un `LoudnessEnhancer`: sulle build che la rispettano ancora, **qualunque audio parta dopo è già amplificato**, senza ritardo. L'API è deprecata e il supporto varia:

- **MIUI 14** la rispetta: l'audio policy sposta gli effetti globali sull'uscita che sta riproducendo musica (`moveEffects session 0` nel log), quindi YouTube viene amplificato subito. I player su un'altra uscita (VLC) non vengono raggiunti.
- **LineageOS 20** da sola la ignora: misurati **0 dB**.

Per questo la sessione 0 è solo il primo livello: le fonti 2 e 3 trovano i player che non raggiunge.

#### 2. Broadcast dei player — i player che si annunciano

I player conformi (VLC, alcuni lettori musicali) annunciano la sessione che stanno per usare con `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` e la rilasciano con `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`. `VolumeBoostService` li ascolta con un `BroadcastReceiver` registrato a runtime ed esportato (`RECEIVER_EXPORTED`, richiesto da Android 13+) e collega un enhancer a `EXTRA_AUDIO_SESSION`. Non servono root né permessi.

#### 3. Individuazione delle sessioni — tutti gli altri player

YouTube, Deezer, i browser e molte altre app non inviano mai quel broadcast, e Android nasconde gli id di sessione delle altre app (`AudioPlaybackConfiguration` riporta `0`). Però qualunque app può collegare un effetto alla sessione di un'altra app se ne conosce l'id, quindi all'app basta trovare gli id. **Impostazioni → Rilevamento sessioni** mostra quale metodo è attivo:

1. **Root.** `AudioSessionDiscovery` esegue `dumpsys media.audio_flinger` in un'unica shell `su` persistente (niente toast di Magisk ogni pochi secondi) e tiene le tracce di output con uso **MEDIA**. Riesegue la scansione all'avvio/arresto della riproduzione e ogni 2 s. È esatto: sa quali tracce sono multimediali.
2. **Rilevamento audio — senza root, con un tocco.** Richiede il permesso runtime `RECORD_AUDIO`; l'app spiega il perché e consiglia **"Mentre usi l'app"** ("Solo questa volta" viene revocato quando l'app si chiude). Il microfono non viene mai aperto e Android non mostra l'indicatore della privacy. `VisualizerSessionScanner`:
   - sa dove cercare: AudioFlinger assegna gli id di sessione da un unico contatore sequenziale (9, 17, 25, … passo 8) e `AudioManager.generateAudioSessionId()` restituisce il successivo, quindi tutte le sessioni esistenti sono sotto quel valore;
   - misura i candidati con un `Visualizer` ciascuno, a lotti di massimo 64 per 250 ms: solo le sessioni in cui passa audio riportano un livello di picco. Ordine: sessioni già collegate, i 128 id più recenti (un player appena avviato), poi i successivi 384 id di una ricerca profonda sugli id più vecchi (un player creato molto tempo prima, ad es. ripreso dalla pausa), proseguita dalle scansioni seguenti;
   - parte quando cambia la riproduzione (dopo 300 ms), non a intervalli fissi, e ritenta con attese crescenti (1,5 s … 30 s) finché un player multimediale non viene trovato. Aspetta mentre suonano suoneria, sveglia o notifiche, per non amplificarle per errore;
   - regge le build che rifiutano di attivare alcuni misuratori (MIUI): vengono ritentati una volta in lotti più piccoli, e nessun errore di misura può fermare il servizio.

   Le sessioni silenziose restano collegate (potrebbero essere solo in pausa); vengono tenute le 8 sentite più di recente.

### Come si combinano: un solo boost per player

Un player non deve ricevere sia l'enhancer globale sia il proprio: misurato su LineageOS, quando una traccia passa per una catena di effetti di sessione il suo audio attraversa anche la catena della sessione 0, e **+10 dB diventavano +20 dB**. Con un massimo di 75 dB sarebbe pericoloso per l'udito e per gli altoparlanti. Il modo in cui la sessione 0 si combina con le sessioni reali dipende da cosa l'app può misurare (`VolumeBoostManager.GlobalMode`):

| Rilevamento sessioni | Sessione 0 | Ogni sessione reale |
|---|---|---|
| Root | Spenta | Boost pieno (il root trova ogni traccia multimediale) |
| Rilevamento audio | **Sempre attiva** | **Misurata:** 0 dB se la sessione 0 la amplifica già, boost pieno se no |
| Nessuno (niente root, nessun permesso) | Solo finché non è collegata nessuna sessione reale | Boost pieno |

La misura sfrutta la stessa scansione. A ogni lotto lo scanner misura anche il **mix in uscita** (un `Visualizer` sulla sessione 0, creato dopo l'enhancer globale, quindi legge il mix già amplificato) e lo confronta con ogni sessione che sente:

- mix più forte della sessione di **almeno metà del boost** (al massimo 3 dB, così conta anche quando un limitatore comprime l'audio forte) → la sessione 0 la amplifica già → il suo enhancer resta a **0 dB**;
- altrimenti (stesso livello, o molto più basso perché il player è su un'altra uscita) → boost pieno;
- mix non misurabile → considerata già amplificata. Meglio un boost mancante che uno doppio.

Una sessione coperta mantiene un **enhancer attivo a 0 dB** invece di nessuno: su LineageOS la sessione 0 raggiunge una traccia solo finché quella traccia ha una catena di effetti di sessione. Anche una sessione annunciata via broadcast parte a 0 dB e riceve il suo guadagno alla scansione successiva, una frazione di secondo dopo l'avvio della riproduzione. Ogni scansione rimisura le sessioni collegate, perché la copertura può cambiare: MIUI sposta l'effetto globale sull'uscita che sta riproducendo musica.

### Cosa succede quando premi play

| Telefono | Player | Risultato |
|---|---|---|
| MIUI 14 | YouTube (uscita deep buffer) | Amplificato **subito** dalla sessione 0; la scansione lo misura poi come coperto e lascia il suo enhancer a 0 dB |
| MIUI 14 | VLC (un'altra uscita) | La sessione 0 non lo raggiunge; la scansione lo misura come non coperto e gli dà il boost pieno ~0,5 s dopo l'avvio |
| LineageOS 20 | Qualsiasi | La sessione 0 da sola non fa nulla; il player viene trovato in ~1 s (anche a schermo spento) e amplificato una volta |
| Con root | Qualsiasi | Trovato dal dump di AudioFlinger entro ~2 s; la sessione 0 non viene usata |

> Misurato su MIUI 14 (Xiaomi Redmi Note 12, boost +33 dB): le sessioni di YouTube risultavano **+3,8 … +17,7 dB** più forti nel mix che nella sessione → coperte; una sessione su un'altra uscita risultava a **−41 dB** → boost pieno. Su LineageOS 20 (Redmi Note 9 Pro): un'impostazione di +10 dB misurava esattamente **+10,0 dB**, e un player con una sessione vecchia di ~1200 id è stato trovato alla terza scansione, ~25 s dopo la ripresa.

## Funzionalità
- Slider del boost in dB (0 … max), max configurabile 20–75 dB (predefinito 20)
- Visualizzazione come `+15 dB` (non in percentuale)
- Strategie di collegamento: sessione globale 0 (immediata dove rispettata), broadcast dei player, individuazione delle sessioni tramite root o rilevamento audio (funziona senza root e senza computer); ogni player viene amplificato una sola volta
- Il boost può essere attivato prima o durante la riproduzione
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

