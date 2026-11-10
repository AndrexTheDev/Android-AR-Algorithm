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

Alternativ liegt die Datei dauerhaft als Release-Asset bereit:
<https://github.com/AndrexTheDev/Android-AR-Algorithm/releases/tag/diagnostics-apk-latest>
(`arkernel-diagnostics-debug.apk`). Die CI aktualisiert dieses Asset bei jedem
Push auf den aktiven Branch.

## Bedienung auf dem Gerät

1. App öffnen; ggf. Kamera-Berechtigung erteilen (nur für Camera2-Metadaten
   und den Lifecycle-Test, **keine** Bildaufnahme).
2. Opt-in anhaken.
3. **1. Kurzen Kapabilitäts-Log erzeugen** – schneller Überblick, oder
   **2. Physische Selbsttests starten** – geführter Lauf mit deiner Hilfe.
4. **3. Dateien speichern** – schreibt beide Dateien unter
   `Downloads/ARKernelDiagnostics` (ab Android 10 über MediaStore; darunter in
   den app-eigenen Dateibereich).
5. **ARKFDLOG1_...txt** hier hochladen. Alternativ **4. Log teilen** oder
   **Log in Zwischenablage kopieren**.

## Geführte physische Selbsttests

Der zweite Modus fragt dich drei Mal „Bereit?“ und misst dann nur
**modellunabhängige Strukturinvarianten** auf dem Gerät selbst:

- Sensor- und Kamera2-Katalog (Typen, Auflösungen, Hardware-Level, Capabilities);
- 10 s IMU-Stillstand: Sample-Zahl, streng monoton steigende Zeitstempel,
  Lücken, Endlichkeit und Varianz von Accelerometer/Gyroskop;
- 30 wiederholte Camera2-Preview-Ergebnisse: Open/Session/Close, Zeitstempel,
  Exposure, ISO, Fokusdistanz, Fehler-/Disconnect-Zähler; Bilder werden sofort
  verworfen, **keine Pixel im Log**;
- byte-exakter 64-KiB-Storage-Roundtrip mit CRC-32;
- deterministischer `ARKFDLOG1`/`ARKFDPOL1`-Encode-/Parse-Selbsttest;
- 20-s-Thermal-Zeitreihe aus `PowerManager`-Status/Headroom mit begrenzter
  CPU-Last, **keine** Aussage über thermische Ausdauer.

Alle Prüfungen scheitern fail-closed an strukturellen Invarianten, nicht an
gerätespezifischen Erwartungswerten. Deine „Bereit“-Bestätigungen werden
ausdrücklich als `host.reported.not.verified` protokolliert. Der Lauf schreibt
nur opake Skalare in `ARKFDLOG1`; `SESSION_TERMINAL` enthält `pass.X.of.Y`.
Qualität bleibt `unverified`.

## Grenzen

- Keine Bilder, Posen, Trajektorien, Kalibrierarrays, Pixel oder dichte Geometrie.
- Kein automatischer Upload, keine Redaktion, keine Signaturverifikation,
  keine unabhängigen physischen Targets, keine Messung von Zentimetern.
- Die geführten Selbsttests prüfen nur modellunabhängige Strukturinvarianten;
  sie beweisen keine Linsen-/IMU-Kalibrierung, keine optische Qualität, keine
  Zentimetergenauigkeit und keine thermische Ausdauer.
- Qualitätsgrad bleibt `unverified`; das Log liefert begrenzte Laufzeitfakten,
  keinen physischen Bodentruth, keinen Rig-Beweis, keine optische oder thermische
  Ausdauerbewertung und keinen ARCore-Äquivalenznachweis.
- Die App schreibt Dateien; der AR-Kern selbst betreibt weiterhin keine I/O.
