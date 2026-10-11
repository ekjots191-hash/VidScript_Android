# VidScript per Android — 0.9 Beta

Stessa idea della versione Windows: **Registra** (audio o video, con trascrizione in tempo reale) e **Trascrivi file**,
tutto in locale con Whisper (whisper.cpp), nessun account, nessun invio online. Una sola interfaccia che si adatta a
**telefono** (colonna singola) e **tablet / orizzontale** (due colonne: comandi a sinistra, testo a destra).

## Come ottenere l'APK (senza installare nulla): GitHub

1. Crea un repository su GitHub (anche privato) e carica **tutto il contenuto di questa cartella**
   (la cartella nascosta `.github` deve esserci).
2. Vai su **Actions → Build APK → Run workflow**.
3. Dopo 8-15 minuti scarica l'artefatto **VidScript-debug-apk** (uno zip con `app-debug.apk`).
4. Copia l'APK sul telefono/tablet, aprilo e consenti l'installazione da fonti sconosciute.

## Oppure con Android Studio
1. Esegui `scripts/setup.ps1` (Windows) o `scripts/setup.sh` per scaricare whisper.cpp.
2. Apri la cartella in Android Studio (Ladybug o successivo), lascia installare SDK e NDK quando richiesto, premi Run.

## Cosa fa
- **Registra**: solo audio (.m4a) o video + audio (.mp4); sorgenti: audio delle app (Android 10+, solo app che lo consentono)
  e/o microfono, anche insieme; trascrizione in tempo reale con .txt salvato accanto.
- **Trascrivi file**: audio/video del dispositivo → un .txt per file nella cartella scelta; prosegue in background con notifica.
- **Modelli** Veloce / Accurato / Superiore (versioni compresse di small / medium / large-v3-turbo), scaricati la prima volta
  e gestibili da «Gestisci modelli»; guida iniziale con scelta del modello.

## Differenze rispetto a Windows (limiti di Android)
- Il formato audio è .m4a (Android non ha un encoder MP3 integrato).
- «Audio delle app» funziona solo con le app che permettono la cattura audio (molte app di streaming/DRM la bloccano) e
  chiede il permesso di acquisizione dello schermo.
- I modelli sono quantizzati per stare su telefono: precisione leggermente inferiore ai modelli del PC.
- Il modello Superiore è consigliato con almeno 6 GB di RAM; la trascrizione in tempo reale va bene solo con «Veloce».

## NON ancora provato
Questo codice è stato scritto senza poterlo compilare né eseguire (l'ambiente non ha l'SDK Android). Al primo giro
possono esserci errori di compilazione da correggere e comportamenti da affinare sul dispositivo reale
(registrazione video, mix microfono + audio app, consumo di batteria). Mandami l'eventuale errore del workflow e lo sistemo.

## Per il Play Store (più avanti)
Serve un account sviluppatore Google Play (una tantum), una chiave di firma, il file .aab (`gradle bundleRelease`) e
un'informativa privacy (puoi riusare quella di Windows, aggiungendo microfono/registrazione schermo).
Licenze di whisper.cpp e dei modelli Whisper: MIT.
