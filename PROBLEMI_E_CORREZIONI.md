# Problemi e bug trovati: correzioni

Analisi completa del progetto (commit `66e7cb9`), con correzioni implementate e verificate sul dispositivo:
Redmi Note 9 Pro, LineageOS, Android 13 (API 33), root Magisk.

Legenda gravità: 🔴 alta · 🟠 media · 🟡 bassa

| # | Gravità | Problema | Stato |
|---|---|---|---|
| 1 | 🔴 | Il boost si riattiva da solo mentre è spento | ✅ Corretto, verificato sul dispositivo |
| 2 | 🔴 | Crash su Android 8–11: CheckBox non consentito nelle RemoteViews | ✅ Corretto |
| 3 | 🔴 | Se la sessione 0 viene rifiutata, il boost si spegne del tutto | ✅ Corretto |
| 4 | 🟠 | Il parser AudioFlinger ignora le fast track e le static track | ✅ Corretto, test con dati reali |
| 5 | 🟠 | Un nuovo processo `su` ogni 2 s (toast e log di Magisk) | ✅ Corretto |
| 6 | 🟠 | Un errore di `su` sgancia tutti gli enhancer (calo di volume) | ✅ Corretto |
| 7 | 🟠 | Aprire l'app chiude i controlli in notifica | ✅ Corretto, verificato sul dispositivo |
| 8 | 🟠 | Lo slider del "boost massimo" ferma il servizio o lo avvia a raffica | ✅ Corretto, verificato sul dispositivo |
| 9 | 🟠 | Crash possibile al riavvio sticky in background (Android 12+) | ✅ Corretto |
| 10 | 🟡 | Metadati della MediaSession non tradotti | ✅ Corretto, verificato sul dispositivo |
| 11 | 🟡 | Il servizio viene avviato senza motivo a ogni apertura o chiusura dell'app | ✅ Corretto, verificato sul dispositivo |
| 12 | 🟡 | Il polling continua ogni 2 s anche senza root | ✅ Corretto |
| 13 | 🟡 | `discoveryActive` letto da un altro thread senza `@Volatile` | ✅ Corretto |
| 14 | 🟡 | Flag `pendingPublishNotification` lasciato "appeso" | ✅ Corretto |
| 15 | 🟡 | Finestra delle impostazioni trapelata alla rotazione; animator non annullato | ✅ Corretto |
| 16 | 🟡 | Notifica "16 dB" invece di "+16 dB" | ✅ Corretto |

---

## 1. 🔴 Il boost si riattiva da solo mentre è spento

**File:** `audio/VolumeBoostManager.kt`

**Problema:** `release()` liberava gli effetti ma lasciava `enabled = true`. Quando il boost viene spento
dalla notifica il servizio resta attivo (per mostrare i controlli) e il receiver dei broadcast
`OPEN_AUDIO_EFFECT_CONTROL_SESSION` resta registrato. Così il primo player che annunciava una sessione
riceveva un `LoudnessEnhancer` **attivo**, con l'interfaccia che diceva "Boost off". Lo stesso succedeva
con una scansione root in corso.

**Riprodotto sul telefono (codice originale):** boost spento dalla notifica, poi
`am broadcast -a android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION --ei android.media.extra.AUDIO_SESSION 153`.
In `dumpsys media.audio_flinger` compariva `Loudness Enhancer` sulla sessione 153, `Enabled = y`, posseduto dal PID dell'app.

**Correzione:** `release()` ora imposta anche `enabled = false`, quindi `attachSession` e
`syncActiveSessions` non fanno nulla finché il boost non viene riattivato.

**Verifica:** stesso scenario con il codice corretto → nessun effetto agganciato.

## 2. 🔴 Crash su Android 8–11: CheckBox nelle RemoteViews

**File:** `res/layout/notification_boost.xml`, nuovo `res/layout-v31/notification_boost.xml`, `VolumeBoostService.kt`

**Problema:** la notifica classica (attiva di default) usava un `CheckBox`. Le RemoteViews accettano
`CheckBox` solo da Android 12 (API 31), ma `minSdk` è 26. Su Android 8–11 la notifica non si
espande ("Bad notification posted") e l'app va in crash appena parte il servizio in foreground. Anche
`setBoolean(..., "setChecked", ...)` non è chiamabile da remoto prima di API 31.

**Correzione:**
- `layout-v31/notification_boost.xml`: il layout con il `CheckBox` (Android 12+), invariato.
- `layout/notification_boost.xml` (Android 8–11): un `Button` la cui etichetta è l'azione da eseguire
  ("Enable boost" / "Disable boost"), impostata con `setTextViewText`.
- Nuova stringa `disable_boost` (EN/IT).

**Verifica:** `aapt2` conferma che l'APK contiene entrambe le varianti, e che quella base usa solo
classi consentite (LinearLayout, TextView, Button, ProgressBar). Non è stato possibile provarla a runtime:
il telefono ha Android 13 e non c'è un emulatore installato.

## 3. 🔴 Se la sessione 0 viene rifiutata, il boost si spegne del tutto

**File:** `audio/VolumeBoostManager.kt`, `service/VolumeBoostService.kt`

**Problema:** `setEnabled(true)` restituiva `false` se `LoudnessEnhancer(0)` falliva (o se falliva una
qualunque sessione già agganciata). Il servizio allora rimetteva il boost su "spento". Il README
descrive la sessione 0 come *best-effort*, ma in pratica un suo fallimento bloccava anche i percorsi 2 e 3.

**Correzione:** il percorso 1 ora è davvero best-effort. Un fallimento viene scritto nel log e il boost
resta attivo, così broadcast e scansione root si agganciano comunque. Ho rimosso il ramo del servizio
che spegneva il boost.

## 4. 🟠 Il parser AudioFlinger ignora le fast track e le static track

**File:** `audio/AudioSessionDiscovery.kt`

**Problema:** dal dump reale del telefono:
```
Type     Id Active Client Session Port Id S  Flags ...  SRate ST Usg CT
                62    yes  18353     153      43 A  0x000 ... 44100  3   1  0
```
La prima colonna (`Type`) è vuota per le tracce normali, vale `S` per quelle statiche e ha il prefisso
`F<n>` per le fast track (bassa latenza). La regex `^\s*(\d+)\s+(yes|no)…` riconosceva solo le tracce
normali, quindi le sessioni dei player che usano fast track non venivano mai amplificate.

**Correzione:** la regex accetta il prefisso facoltativo `F<n>` e il tipo `S`. Le patch track (`P`,
routing interno) restano escluse, come le righe del "Local log" (iniziano con un timestamp).
Il parsing è separato in `parseMediaSessions()`, una funzione pura testabile.

**Verifica:** nuovi test JVM (`AudioSessionDiscoveryTest`) con righe catturate dal telefono, più le
varianti fast/static/patch.

## 5. 🟠 Un nuovo processo `su` ogni 2 secondi

**File:** `audio/AudioSessionDiscovery.kt`, `service/VolumeBoostService.kt`

**Problema:** ogni scansione (ogni 2 s, più a ogni cambio di riproduzione) avviava `su -c dumpsys …`.
Con le impostazioni predefinite Magisk mostra un toast e scrive una voce nel log superuser per **ogni**
richiesta, quindi decine di toast al minuto e un log che cresce senza limiti. In più c'era il costo di
creare un processo ogni volta, e `waitFor()` non aveva timeout.

**Correzione:** nuova classe `ShellSession` con una shell `su` persistente. Ogni comando viene scritto su
stdin seguito da un marcatore di fine, e l'output si legge fino al marcatore. La shell viene riaperta
automaticamente se muore e chiusa (`closeShell()`) quando il boost si spegne o il servizio termina.
`closeShell()` non prende il lock, quindi non blocca il main thread durante una scansione in corso.

**Verifica:** test JVM con `sh`: più comandi nello stesso processo (stesso PID), stderr catturato,
output senza newline finale, shell terminata o chiusa → `null`.

## 6. 🟠 Un errore di `su` sgancia tutti gli enhancer

**File:** `audio/AudioSessionDiscovery.kt`, `service/VolumeBoostService.kt`

**Problema:** se `dumpsys` falliva, `discoverMediaSessions` restituiva un set vuoto e
`syncActiveSessions` rilasciava tutte le sessioni. Risultato: il volume calava di colpo per ~2 s, fino
alla scansione successiva.

**Correzione:** in caso di errore `discoverMediaSessions` restituisce `null` e il servizio salta la
sincronizzazione, mantenendo gli enhancer attuali.

## 7. 🟠 Aprire l'app chiude i controlli in notifica

**File:** `ui/MainActivity.kt`

**Problema:** `onCreate` chiamava sempre `VolumeBoostService.sync()`. Con il boost spento dalla notifica
questo inviava `ACTION_STOP`, che distruggeva servizio e notifica: bastava aprire l'app per perdere i
controlli.

**Riprodotto sul telefono:** notifica presente → apertura a freddo dell'app → notifica e servizio spariti.

**Correzione:** in `onCreate` si sincronizza solo se il boost è attivo (per ripristinarlo dopo la morte
del processo). Lo spegnimento completo avviene solo dallo switch dell'app, come previsto dal design.

**Verifica:** stesso scenario → la notifica resta, e riattivandola dalla notifica si aggiorna anche lo
switch nell'app.

## 8. 🟠 Lo slider del "boost massimo" ferma il servizio o lo avvia a raffica

**File:** `ui/SettingsBottomSheet.kt`, `ui/MainActivity.kt`

**Problema:** a ogni tick dello slider veniva chiamato `VolumeBoostService.sync()`:
- con il boost spento → `ACTION_STOP`, che chiudeva i controlli in notifica (o creava un servizio solo per fermarlo);
- con il boost acceso → un `startForegroundService` per ogni tick.

**Correzione:** lo store viene aggiornato in tempo reale, quindi il servizio segue già il nuovo valore.
La notifica viene ripubblicata una sola volta, al rilascio del dito (`onStopTrackingTouch` →
`publishNotification`).

**Verifica:** trascinamento 20 → 58 dB: zero avvii del servizio durante il trascinamento, e
sottotitolo della notifica aggiornato a "0…58 dB".

## 9. 🟠 Crash possibile al riavvio sticky in background

**File:** `service/VolumeBoostService.kt`

**Problema:** il servizio è `START_STICKY`. Da Android 12, se il sistema lo riavvia mentre l'app è in
background, `startForeground()` può lanciare `ForegroundServiceStartNotAllowedException`, che porta a
crash e a un ciclo di riavvii.

**Correzione:** `startForegroundNotification()` cattura l'eccezione (`IllegalStateException`) e
restituisce `false`. In quel caso `publishControls()` chiude il servizio in modo pulito. Il boost viene
ripristinato alla prossima apertura dell'app.

**Verifica:** processo ucciso (`kill -9`) con boost attivo e app in background → il servizio riparte,
nessun crash, effetto e notifiche ripristinati. Su questo telefono Android ha permesso il riavvio: la
protezione resta come rete di sicurezza.

## 10. 🟡 Metadati della MediaSession non tradotti

**File:** `service/VolumeBoostService.kt` (`syncMediaSession`)

**Problema:** titolo, artista e sottotitolo usavano `getString()` del servizio (lingua di sistema)
invece della lingua scelta nell'app. Da Android 13 la notifica multimediale prende proprio questi testi.

**Correzione:** si usa `LocaleManager.wrapContext(this, prefs)`, come per le altre notifiche.

**Verifica:** con la lingua italiana, la MediaSession mostra "Boost volume" e il sottotitolo "Trascina la barra · 0…20 dB".

## 11. 🟡 Il servizio viene avviato senza motivo

**File:** `service/VolumeBoostService.kt` (companion), `ui/MainActivity.kt`

**Problema:** `onStop()` (`publishNotification`) e `onCreate()` (`sync` con `ACTION_STOP`) avviavano il
servizio anche con il boost spento, solo per farlo terminare subito.

**Correzione:** nuovo flag `VolumeBoostService.isRunning`. `sync()` invia `ACTION_STOP` solo se il
servizio è attivo. `publishNotification()` non fa nulla se il servizio non è attivo e il boost è spento.

**Verifica:** con il boost spento, aprire e chiudere l'app non crea nessun `ServiceRecord`.

## 12. 🟡 Polling ogni 2 secondi anche senza root

**File:** `service/VolumeBoostService.kt` (`discoveryRunnable`)

**Problema:** senza root il runnable si riprogrammava ogni 2 s senza fare niente (risvegli inutili).

**Correzione:** se il root non è disponibile, il ciclo si ferma.

## 13. 🟡 `discoveryActive` senza `@Volatile`

Il flag viene scritto sul main thread e letto sul thread `boost-discovery`. Ora è `@Volatile`.

## 14. 🟡 `pendingPublishNotification` lasciato "appeso"

**Problema:** in `ACTION_SET_ENABLED` / `ACTION_ADJUST_DB` il flag veniva impostato a mano prima di
aggiornare lo store. Se il valore non cambiava (per esempio +1 al massimo), il flag restava attivo fino
a un aggiornamento successivo non correlato, e la notifica non veniva ridisegnata.

**Correzione:** dopo l'aggiornamento dello store si chiama sempre
`scheduleApply(hardStopWhenDisabled = false, publishNotification = true)`.

## 15. 🟡 Finestra trapelata alla rotazione; animator non annullato

- `MainActivity` tiene un riferimento al `BottomSheetDialog` e lo chiude in `onDestroy()`. Evita anche di aprirne due.
- `BoostGaugeView` annulla l'animazione in `onDetachedFromWindow()`.

**Verifica:** rotazione con le impostazioni aperte → nessun "leaked window" nel logcat. La rotazione
del telefono è stata ripristinata ai valori originali.

## 16. 🟡 Formato dB incoerente nella notifica

`notification_text` era `%1$d dB`, mentre l'app e il README usano `+%1$d dB`. Ora è `+%1$d dB` in EN e IT.

---

## Test eseguiti

**Unit test JVM:** `./gradlew testDebugUnitTest` → 8/8 passati (nuova dipendenza `junit:junit:4.13.2`).

**Build e lint:** `assembleDebug` ok. `lintDebug` non segnala errori; restano solo avvisi
preesistenti (risorse inutilizzate, versioni delle dipendenze).

**Sul telefono:** pacchetto temporaneo `com.volumeboost.app.bugfixtest` installato accanto all'app
esistente, poi rimosso. Per gli effetti ho usato `dumpsys media.audio_flinger`.

| Scenario | Esito |
|---|---|
| Apertura e chiusura dell'app con il boost spento: nessun servizio avviato | ✅ |
| Attivazione dall'app: `Loudness Enhancer` sulla sessione 0, attivo | ✅ |
| Broadcast OPEN/CLOSE con boost attivo: aggancio e sgancio della sessione 153 | ✅ |
| Spento dalla notifica + broadcast OPEN: nessun effetto (bug 1) | ✅ |
| Apertura a freddo con i controlli in notifica: la notifica resta (bug 7) | ✅ |
| Riattivazione dalla checkbox in notifica: effetto e switch dell'app sincronizzati | ✅ |
| Slider a +15 dB; pulsanti +1 +1 −1 in notifica → +16 dB | ✅ |
| Notifica multimediale + lingua italiana: metadati tradotti | ✅ |
| Slider del boost massimo 20 → 58: sottotitolo aggiornato, nessun avvio a raffica | ✅ |
| Rotazione con le impostazioni aperte: nessuna finestra trapelata | ✅ |
| Tema chiaro e ritorno all'inglese | ✅ |
| Spento dall'app: servizio, notifiche ed effetti rimossi | ✅ |
| `kill -9` con boost attivo: riavvio sticky senza crash | ✅ |

**Non verificabili sul telefono:**
- **Percorso 3 (root):** Magisk ha negato il root al pacchetto di test, perché non è mai stato
  autorizzato e non ho modificato le policy di Magisk. Il parser e la shell persistente sono coperti
  dai test JVM. Il formato del dump è quello reale del telefono.
- **Layout di Android 8–11:** nessun emulatore disponibile; verifica statica con `aapt2`.

## Note e limiti noti (non modificati)

- **Doppio boost sui dispositivi che rispettano la sessione 0.** ~~Una sessione agganciata anche dai
  percorsi 2 e 3 viene amplificata due volte.~~ Risolto nella Parte 3: il doppio boost si verificava anche
  su questo telefono, e ora la sessione 0 viene usata solo quando non c'è nessuna sessione reale.
- **Esito negativo del root memorizzato.** Se il root viene negato, il risultato resta in memoria fino
  al riavvio del processo dell'app. È voluto: evita una richiesta Magisk ogni pochi secondi.
- **Risorse inutilizzate** (`status_error`, `apply`, `close`, `*_danger`, `*_switch_track`): solo avvisi di lint.
- **Installazione sul telefono.** L'APK in `Releases/` è firmato con una chiave debug diversa da quella
  di questa macchina. Per installare una nuova build compilata qui bisogna prima disinstallare l'app
  (si perdono le impostazioni) e poi concedere di nuovo il root in Magisk.

---

# Parte 2: boost senza root

**Segnalazione:** con il root concesso il boost funziona, senza root no.

## Diagnosi (misurata sul telefono)

Ho misurato il livello reale dell'uscita audio con un `Visualizer` sul mix (tono di prova a −21 dBFS
da un player che non invia broadcast, come YouTube):

| Effetto applicato | Livello d'uscita |
|---|---|
| Nessuno | −37,78 dB |
| `LoudnessEnhancer` +10 dB sulla **sessione 0** (percorso 1) | −37,78 dB → **nessun effetto** |
| `DynamicsProcessing` +10 dB sulla sessione 0 | −37,78 dB → nessun effetto |
| `LoudnessEnhancer` +10 dB sulla **sessione reale** del player | **−27,77 dB (+10 dB)** |

Su questo telefono (LineageOS 20 / Android 13) Android ignora gli effetti sul mix globale, anche quando
sono sullo stesso thread di output della musica. Il boost funziona **solo** sull'ID di sessione reale
del player. Senza root un'app normale non può conoscerlo: Android anonimizza
`AudioPlaybackConfiguration`, e YouTube, Spotify e simili non inviano il broadcast del percorso 2.

L'unico modo pubblico e senza root per leggere gli ID è il dump di AudioFlinger, che richiede
`android.permission.DUMP`. Ha livello di protezione `signature|privileged|development`: la parte
*development* permette di concederlo a qualunque app con `adb shell pm grant`, senza root.

## Soluzione implementata

1. **Percorso 3 con permesso ADB (`DUMP`).** Il manifest dichiara `DUMP`. Se è concesso,
   `AudioSessionDiscovery` esegue `/system/bin/dumpsys media.audio_flinger` come app, senza `su` e senza
   prompt di Magisk. Ordine di priorità: permesso ADB → root → nessuno.
2. **Strategia 4: Shizuku (senza root e senza PC).** Il pulsante "Concedi con Shizuku" esegue
   `pm grant … DUMP` tramite Shizuku (Android 11+, avviato con il debug wireless). Da quel momento vale il
   punto 1 in modo permanente, anche se Shizuku viene chiuso. Il risultato dell'autorizzazione è gestito
   a livello di applicazione: sul telefono la rotazione ricreava l'activity e il risultato andava perso
   finché era gestito dallo sheet.
3. **Impostazioni → Rilevamento sessioni:** mostra la modalità attiva (Completo: permesso ADB / Completo:
   root / Limitato) e, in modalità limitata, il comando da copiare e il pulsante Shizuku.
4. **Rilevamento a caldo:** senza accesso il servizio ricontrolla ogni 10 s, invece di fermarsi. Un
   permesso concesso con il boost già attivo viene rilevato senza spegnerlo e riaccenderlo.
5. **Cache di audioserver.** audioserver memorizza per uid anche i *rifiuti* del controllo `DUMP`. Se il
   dump è stato tentato prima della concessione, resta rifiutato fino al riavvio di audioserver. L'app
   non tenta mai il dump prima della concessione. Se succede comunque, usa il root come ripiego (se c'è),
   riprova ogni 60 s e le Impostazioni dicono di riavviare il telefono.
6. **Senza accesso, `discoverMediaSessions` restituisce `null`** (prima un set vuoto). Così la
   sincronizzazione non sgancia le sessioni ottenute dal percorso 2.

## Verifiche sul telefono (strumenti di misura senza root, utente shell)

| Scenario | Risultato |
|---|---|
| Permesso ADB, boost +10 dB, tono senza broadcast | −37,78 → **−27,77 dB** ✅ |
| Concessione tramite Shizuku (pulsante), poi stessa misura | `DUMP` concesso, **−27,77 dB**; boost spento → −37,78 dB ✅ |
| Shizuku: activity ricreata (rotazione) durante il dialogo | Permesso concesso comunque ✅ |
| Sezione Impostazioni: limitato → completo dopo la concessione | Aggiornata in tempo reale ✅ |
| Percorso 2, modalità limitata: broadcast OPEN / CLOSE | +10 dB / ritorno al livello base ✅ |
| Percorso 1 (sessione 0) | Si aggancia; su questo telefono non ha effetto udibile (limite di Android) |
| Cache di audioserver sull'app principale | Ripiego sul root e messaggio "riavvia il telefono" ✅ |
| Percorso 3 via root | Non verificabile: il root non è concesso alle app di test; codice invariato, coperto dai test JVM |

**Nota sul telefono di prova:** l'app principale ha il permesso `DUMP`, ma audioserver ricorda ancora
il rifiuto causato dal mio test manuale con `run-as`. **Basta riavviare il telefono una volta.** Shizuku
e i pacchetti di test sono stati rimossi; volume e rotazione sono stati ripristinati.

---

# Parte 3: boost senza root e senza ADB

**Segnalazione:** il boost funziona solo se è concesso il root; deve funzionare anche sui telefoni senza root.

## Diagnosi (misurata sul telefono)

La Parte 2 funzionava senza root, ma richiedeva comunque un passaggio tecnico: il comando ADB da un
computer, oppure Shizuku con il debug wireless. Su un telefono normale, senza nessuno dei due, restavano
solo la sessione 0 e i broadcast, quindi YouTube, Spotify e i browser non venivano amplificati.

Nuove misure (tono a −21 dBFS, volume 2/15, livello del mix letto con un `Visualizer` creato **dopo**
l'effetto, così da misurare l'uscita effettiva):

| Effetto | Livello d'uscita |
|---|---|
| Nessuno | −70,64 dB |
| `LoudnessEnhancer` +10 dB sulla sessione 0 | −70,64 dB → nessun effetto (confermato) |
| `LoudnessEnhancer` +10 dB sulla sessione reale, creato da **un'altra app** | −60,64 dB (+10,0 dB) |

Cosa vede un'app normale, senza permessi:
- `AudioPlaybackConfiguration` riporta `sessionId:0` (anonimizzato), quindi nessun ID dalle API pubbliche.
- Gli ID di sessione di AudioFlinger sono **sequenziali con passo 8** (121 → 129 → 137…), e
  `AudioManager.generateAudioSessionId()` restituisce il prossimo. Tutte le sessioni esistenti stanno quindi sotto quel valore.
- Un `Visualizer` si può agganciare a **qualunque** ID di sessione e misura il livello di picco solo se lì
  passa audio. Serve solo `RECORD_AUDIO`, un permesso standard concesso con un tocco. Il microfono non viene
  aperto: nessun accesso registrato in `appops`, nessun indicatore verde.

Prova: VLC (un'altra app) suonava sulla sessione 81, allocata all'avvio di VLC e non recente. Una scansione
dei 19 ID candidati l'ha trovata esattamente, in 0,63 s.

## Soluzione implementata

1. **Nuova modalità di rilevamento `Access.VISUALIZER`** (`audio/VisualizerSessionScanner.kt`), usata quando
   mancano sia il permesso DUMP sia il root. Ordine: permesso ADB → root → rilevamento audio → nessuno.
2. **Scansione a lotti** di 64 Visualizer (ascolto di 250 ms). L'audio policy ha un tetto di memoria globale
   per gli effetti: con 512 Visualizer insieme, 132 sono stati rifiutati. Ordine dei candidati: sessioni già
   trovate, 128 ID più recenti, poi una **ricerca profonda a tranche di 384 ID** che riparte da dove si era
   fermata. Così una singola scansione resta sotto ~8 s anche dopo giorni di accensione, mentre una scansione
   completa di 960 ID richiedeva ~17 s, per lo più per creare i Visualizer (~10 ms l'uno).
3. **Quando scansionare:** al cambio di riproduzione (`AudioPlaybackCallback`, attesa di 300 ms), non ogni
   2 s. Se un player multimediale risulta in riproduzione ma non è stato trovato (intro silenziosa, volume a
   0…), si ritenta dopo 1,5 → 3 → 6 … 30 s. Quando la ricerca profonda arriva all'ID più vecchio senza
   trovarlo, ricomincia solo al successivo cambio di riproduzione.
4. **Niente notifiche amplificate per errore:** il Visualizer non conosce l'uso della traccia, quindi la
   scansione aspetta mentre suonano suoneria, sveglia, notifiche, chiamate o navigazione.
5. **Sessioni silenziose:** restano amplificate, perché potrebbero essere in pausa (alla ripresa il volume
   non deve "saltare"). Visto che gli ID non vengono mai riutilizzati, si tengono le 8 più recenti.
6. **Doppio boost corretto (`VolumeBoostManager.updateGlobalSession`).** Riprodotto sul telefono: con +10 dB
   il livello saliva di **+20 dB**. Il dump mostrava il nostro enhancer sia sulla sessione del tono sia sulla
   sessione 0. Da sola la sessione 0 non fa nulla, ma una traccia che passa per una catena di effetti di
   sessione attraversa anche la catena della sessione 0. Ora la sessione 0 si usa **solo finché non c'è
   nessuna sessione reale** agganciata. Vale per tutti i percorsi, compresi ADB e root.
7. **Interfaccia:** attivando il boost senza nessun accesso, l'app spiega (una volta sola, in automatico)
   perché serve il permesso "registra audio" e che il microfono non viene usato, poi mostra il dialogo di
   Android. In *Impostazioni → Rilevamento sessioni*: nuovo stato "Completo: rilevamento audio (senza root)";
   in modalità limitata il pulsante "Consenti rilevamento audio". Se Android non mostra più il dialogo (due
   rifiuti), il pulsante apre le impostazioni dell'app. Comando ADB e Shizuku restano come alternativa
   avanzata. Il permesso concesso viene usato subito (`ACTION_RESCAN`), anche dalle impostazioni di sistema.

## Verifiche sul telefono

Variante di test `com.volumeboost.app.noroot`: niente DUMP, root **negato** al prompt di Magisk.
Player: VLC (un'altra app) e un tono di un'app di prova. Entrambi rimossi alla fine.

| Scenario | Risultato |
|---|---|
| Boost attivo senza permessi | −69,59 dB (nessun effetto, come atteso) |
| Concessione dal dialogo dell'app | VLC trovato subito: −69,59 → **−59,59 dB** ✅ |
| App in background, nuovo player avviato dopo | Trovato in ~1 s: −70,64 → **−60,64 dB** ✅ |
| Schermo spento (doze), nuovo player | Trovato in 1,3 s, **+10,0 dB** ✅ |
| Contatore a ~9800, VLC sulla sessione 81 (~1200 ID più vecchia) | Trovata alla 3ª scansione, ~25 s ✅ |
| Player avviato con volume 0, poi alzato | Primo tentativo silenzioso, il secondo lo trova, +10 dB ✅ |
| Doppio boost sessione 0 + sessione reale | Prima +20 dB ❌, dopo la correzione **+10 dB** ✅ |
| Boost spento dall'app | Nessun enhancer rimasto, servizio terminato ✅ |
| Impostazioni: limitato → pulsante → concesso | Diventa "Completo: rilevamento audio" senza riaprire ✅ |
| App principale (permesso ADB), boost 25 dB | −70,64 → **−45,64 dB**, nessuna catena sulla sessione 0 ✅ |

**Test e build:** `testDebugUnitTest` → 12/12 (4 nuovi test sulla scelta dei candidati e sulla ricerca
profonda). `lintDebug`: 0 errori, nessun avviso nuovo.

**Stato del telefono dopo i test:** app principale aggiornata con questa versione (impostazioni e permesso
DUMP conservati, boost lasciato spento come prima). Rimossi variante di test, app di prova e file audio.
Volume multimediale (0) e "rimani acceso" ripristinati.

## Limiti noti

- **Permesso "registra audio".** È l'unica via senza root e senza PC, ma il nome del permesso può spaventare.
  L'app lo spiega prima del dialogo di Android.
- **Player creati molto tempo prima** (telefono acceso da giorni, player avviato e lasciato in pausa) possono
  richiedere qualche scansione profonda, ~25 s nel test con ~1200 ID. I player appena avviati vengono trovati in ~1 s.
- **Uso della traccia non noto:** un suono non multimediale (un gioco, un tono di sistema) che suona proprio
  durante una scansione può essere amplificato.
- **Possibile click:** a ogni scansione un Visualizer viene agganciato per 250 ms anche alle sessioni già
  amplificate. Nelle misure non si vede alcun effetto, ma all'ascolto non l'ho verificato.


---

# Parte 4: rimozione delle strategie non funzionanti o con ADB

**Richiesta:** togliere le strategie che non funzionano o che richiedono ADB e simili, tenendo il root.

## Cosa è stato rimosso

| Strategia | Motivo | Cosa è stato tolto |
|---|---|---|
| Sessione globale 0 | 0 dB misurati su questo telefono, e +20 dB invece di +10 accanto a una sessione reale | `updateGlobalSession` e `GLOBAL_AUDIO_SESSION` in `VolumeBoostManager`; ora `attach` rifiuta ogni id ≤ 0 |
| Permesso DUMP via ADB | Richiede un computer | `Access.ADB_PERMISSION`, `runDumpsys`, la gestione del rifiuto di audioserver, il permesso nel manifest |
| Shizuku | Richiede il debug wireless | `ShizukuDumpGrant.kt`, il provider nel manifest, le dipendenze `dev.rikka.shizuku`, l'inizializzazione nell'Application |
| Interfaccia relativa | — | Comando ADB, pulsante "Copia", pulsante Shizuku, stato "permesso ADB non ancora attivo" e 13 stringhe EN/IT |

**Restano:** i broadcast dei player (percorso 1: funzionano senza permessi, es. VLC) e l'individuazione
delle sessioni (percorso 2) tramite **root**, se disponibile, oppure **rilevamento audio**. L'APK ora
dichiara solo `RECORD_AUDIO` oltre ai permessi di base (notifiche, servizio in foreground, impostazioni audio).

## Correzione trovata durante la verifica

Sul telefono l'app era stata concessa con **"Solo questa volta"**. Android revoca questi permessi quando
il processo termina, e da quel momento il boost non trovava più i player. Inoltre il pulsante "Consenti
rilevamento audio" apriva le impostazioni di sistema invece del dialogo, perché dopo un permesso "una
tantum" scaduto Android non segnala la *rationale*, esattamente come dopo due rifiuti.

- L'app ricorda se l'ultima risposta è stata un rifiuto (`audioDetectionDenied`) e apre le impostazioni
  di sistema solo in quel caso.
- La spiegazione ora consiglia "Mentre usi l'app", e compare anche quando si usa il pulsante nelle Impostazioni.

## Verifiche sul telefono (app principale, root negato da Magisk)

| Scenario | Risultato |
|---|---|
| Permesso "una tantum" scaduto → pulsante nelle Impostazioni | Spiegazione, poi dialogo di Android (non più le impostazioni di sistema) ✅ |
| "Mentre usi l'app" | Concesso senza `ONE_TIME`; la sezione diventa "Completo: rilevamento audio" ✅ |
| Boost +6 dB, app in background, player già attivo | −70,64 → **−64,64 dB** ✅ |
| Nuovo player avviato in background | Trovato in ~1 s, **+6 dB**; nessuna catena sulla sessione 0 ✅ |
| Boost spento dall'app | Servizio terminato, nessun enhancer rimasto ✅ |

Build, `testDebugUnitTest` (12/12) e `lintDebug` (0 errori, nessun avviso nuovo) superati. Boost lasciato
spento, app di prova rimossa, volume e "rimani acceso" ripristinati.
