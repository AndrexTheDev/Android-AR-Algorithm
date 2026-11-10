# AR-Kern Diagnose-App (sideloadbare APK)

Eine kleine, bewusst begrenzte Host-App für ein physisches Android-Gerät, z. B.
ein Redmi Note 13. Sie erzeugt nach **explizitem Opt-in** zwei reine Textdateien:

- `ARKFDPOL1_<run>.txt` – die `ARKFDPOL1`-Richtlinie mit `collection_opt_in=true`.
- `ARKFDLOG1_<run>.txt` – den kanonischen `ARKFDLOG1`-Lauf-Log mit opaken
  Geräte-, Capability-, Thermal-, Speicher- und Timing-Tokens.

Die `ARKFDLOG1`-Datei ist die Datei, die du hier im Chat hochladen kannst.

## APK bekommen

1. Auf GitHub den Actions-Lauf des Branches
   `arena/4033116d-android-ar-algorithm` öffnen.
2. Artefakt `arkernel-diagnostics-apk` herunterladen.
3. `app-debug.apk` auf das Gerät kopieren, „Unbekannte Quellen“ für die
   Installation erlauben und installieren. Es ist ein Debug-Build, kein
   Play-Store-Release.

Alternativ liegt die Datei als Release-Asset `arkernel-diagnostics-debug.apk`
bereit, wenn ein Release für diesen Stand erzeugt wurde.

## Bedienung auf dem Gerät

1. App öffnen; ggf. Kamera-Berechtigung erteilen (nur für Camera2-Metadaten,
   **keine** Bildaufnahme).
2. Opt-in anhaken.
3. **1. Log erzeugen / aktualisieren** – Vorschau zeigt den exakten Text und
   bestätigt, dass der Parser ihn akzeptiert.
4. **2. Dateien speichern** – schreibt beide Dateien unter
   `Downloads/ARKernelDiagnostics` (ab Android 10 über MediaStore; darunter in
   den app-eigenen Dateibereich).
5. **ARKFDLOG1_...txt** hier hochladen. Alternativ **3. Log teilen** oder
   **Log in Zwischenablage kopieren**.

## Grenzen

- Keine Bilder, Posen, Trajektorien, Kalibrierarrays, Pixel oder dichte Geometrie.
- Kein automatischer Upload, keine Redaktion, keine Signaturverifikation,
  keine unabhängigen physischen Targets, keine Messung von Zentimetern.
- Qualitätsgrad bleibt `unverified`; das Log liefert begrenzte Laufzeitfakten,
  keinen physischen Bodentruth, keinen Rig-Beweis, keine optische oder thermische
  Ausdauerbewertung und keinen ARCore-Äquivalenznachweis.
- Die App schreibt Dateien; der AR-Kern selbst betreibt weiterhin keine I/O.
