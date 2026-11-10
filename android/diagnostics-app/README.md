# AR-Kern Diagnose-App (ein installiertes Plan-Laufwerk)

Diese sideloadbare Host-App ist bewusst **eine einzige, dauerhaft installierte
App** für ein physisches Android-Gerät, z. B. ein Redmi Note 13. Sie soll
möglichst viele zukünftige Testvarianten abdecken, ohne dass du dafür jedes Mal
eine neue APK installieren musst. Version `2.0-plan` enthält dazu einen
versionierten `ARKFDPLAN1`-Textinterpreter: Dauer, Reihenfolge, Wiederholungen
und die begrenzten Sensor-/Kamera-/Speicher-/Thermal-Parameter werden als
**Textplan** geändert. Nur eine wirklich neue Android-API würde ein Update
**derselben App** erfordern, keine neue App und keinen neuen Installationszyklus.

Nach **explizitem Opt-in** schreibt die App drei reine Textdateien:

- `ARKFDPOL1_<run>.txt` – die `ARKFDPOL1`-Richtlinie mit `collection_opt_in=true`.
- `ARKFDPLAN1_<run>.txt` – der kanonische, fail-closed geparste `ARKFDPLAN1`-Plan.
- `ARKFDLOG1_<run>.txt` – der kanonische `ARKFDLOG1`-Lauf-Log mit opaken
  Geräte-, Plan-, Capability-, Thermal-, Speicher- und Timing-Tokens.

Für die Auswertung hier im Chat bitte **sowohl `ARKFDLOG1_...txt` als auch
`ARKFDPLAN1_...txt`** hochladen: Der Log enthält eine CRC-32 des Plans sowie
`PLAN_METADATA`-, `PLAN_STEP`- und `PLAN_RESULT`-Events, damit ein Lauf
eindeutig dem verwendeten Plan zugeordnet bleibt.

## APK bekommen

1. Auf GitHub den Actions-Lauf des Branches
   `arena/4033116d-android-ar-algorithm` öffnen.
2. Artefakt `arkernel-diagnostics-apk` herunterladen.
3. `arkernel-diagnostics-debug.apk` auf das Gerät kopieren, „Unbekannte Quellen“
   für die Installation erlauben und installieren. Es ist ein Debug-Build, kein
   Play-Store-Release.

Alternativ liegt die Datei dauerhaft als Release-Asset bereit:
<https://github.com/AndrexTheDev/Android-AR-Algorithm/releases/tag/diagnostics-apk-latest>
(`arkernel-diagnostics-debug.apk`). Die CI aktualisiert dieses Asset bei jedem
Push auf den aktiven Branch. Ein Update ersetzt dieselbe installierte App;
`applicationId` und Dateiname bleiben stabil.

## Bedienung auf dem Gerät

1. App öffnen; ggf. Kamera-Berechtigung erteilen (nur für Camera2-Metadaten
   und Lifecycle-/Preview-Tests, **keine** Bildaufnahme).
2. Opt-in anhaken.
3. Im Feld **ARKFDPLAN1** den Standardplan belassen oder einen eigenen Plan
   einfügen. **Standard-ARKFDPLAN1 wiederherstellen** setzt den mitgelieferten
   20-Schritt-Plan zurück.
4. **1. Kurzen Kapabilitäts-Log erzeugen** – schneller Überblick, oder
   **2. Ultimativen Planlauf starten** – führt alle Schritte des aktuellen Plans
   mit deiner Hilfe aus.
5. **3. Dateien speichern** – schreibt Richtlinie, Plan und Log unter
   `Downloads/ARKernelDiagnostics` (ab Android 10 über MediaStore; darunter in
   den app-eigenen Dateibereich).
6. **`ARKFDLOG1_...txt` und `ARKFDPLAN1_...txt`** hier hochladen. Alternativ
   **4. Log teilen**, **ARKFDPLAN1 teilen** oder
   **Log in Zwischenablage kopieren**.

## `ARKFDPLAN1`: zukünftige Tests ohne neue Installation

Der Plan ist ein reiner Text mit fester Schlüsselreihenfolge und `END`. Jede
Zeile `step=<OP>|<duration_ms>|<count>|<expected_min>|<label>` wählt einen
bereits implementierten Operator. `duration_ms` ist ein Timeout oder
Sample-Intervall, `count` ein op-spezifisches Budget (Frames, Samples, Bytes,
Wiederholungen oder Kataloggrenze), `expected_min` die strukturelle
Pass-Schwelle und `label` eine bekannte Variante, z. B. `gyroscope` oder
`rotation.vector`.

Beispiel für eine Zeile:

```
step=IMU_STILLNESS|15000|4000|150|accelerometer.gyroscope
```

Damit werden 15 s statt 10 s IMU-Stillstand geprüft, ohne die App neu zu
installieren. Weitere Schritte lassen sich umsortieren, wiederholen oder
entfernen; `step_count` muss dann angepasst werden. Der Standardplan
`arkernel.ultimate.v2` deckt alle 17 Operatoren in 20 Schritten ab:

- `CAPABILITY_CATALOG`, `SENSOR_CATALOG`, `CAMERA_CATALOG`,
  `PERMISSION_CATALOG`, `PACKAGE_CATALOG`, `MEMORY_SNAPSHOT`,
  `DISPLAY_SNAPSHOT`, `AUDIO_CATALOG`, `CONNECTIVITY_CATALOG`,
  `BATTERY_SNAPSHOT`;
- `IMU_STILLNESS`, `SENSOR_LIVE`, `CAMERA_PREVIEW`,
  `CAMERA_LIFECYCLE_REPEAT`, `STORAGE_ROUNDTRIP`, `COMPUTE_SELFTEST`,
  `THERMAL_SOAK`.

Der Parser ist absichtlich streng: maximal 4096 Zeichen, 256 Zeichen pro
Zeile, 1–24 Schritte, `max_events` 16–128, Dauer maximal 120000 ms, `count`
und `expected_min` maximal 262144, `revision` maximal 1000000,
`quality_grade=unverified`, alle fünf Sicherheitsflags auf `true`, nur
ASCII-Tokens ohne Leerzeichen und nur bekannte Operatoren. Unbekannte
Operatoren, falsche Zeilenzahl, geänderte Sicherheit, Qualitätsansprüche oder
BOM/CRLF-Überschreitungen werden fail-closed abgelehnt; die App startet dann
keinen Lauf. `max_events` begrenzt die zusätzlichen Plan-Events im
`ARKFDLOG1`, nicht die Zahl der physischen Messungen.

## Was der ultimative Planlauf prüft

Der geführte Lauf bestätigt einmal den Start und misst nur
**modellunabhängige Strukturinvarianten** auf dem Gerät selbst:

- Geräte-, Sensor-, Kamera-, Berechtigungs-, Paket-, Speicher-, Display-,
  Audio-, Netzwerk- und Batterie-Kataloge bzw. Snapshots;
- IMU-Stillstand und Live-Sensoren: Sample-Zahl, streng monoton steigende
  Zeitstempel, Lücken, Endlichkeit, Mittelwert und Standardabweichung;
- wiederholte Camera2-Preview-Ergebnisse und Open/Close-Zyklen:
  Open/Session/Close, Zeitstempel, Exposure, ISO, Fokusdistanz,
  Fehler-/Disconnect-Zähler; Bilder werden sofort verworfen, **keine Pixel im
  Log**;
- byte-exakte Storage-Roundtrips mit CRC-32;
- deterministischen `ARKFDLOG1`/`ARKFDPOL1`/`ARKFDPLAN1`-Kern-Selbsttest;
- Thermal-Zeitreihe aus `PowerManager`-Status/Headroom mit begrenzter CPU-Last,
  **keine** Aussage über thermische Ausdauer.

Alle Prüfungen scheitern fail-closed an strukturellen Invarianten, nicht an
gerätespezifischen Erwartungswerten. Deine „Bereit“-Bestätigung wird
ausdrücklich als `host.reported.not.verified` protokolliert. Der Lauf schreibt
nur opake Skalare in `ARKFDLOG1`; `SESSION_TERMINAL` enthält
`pass.X.of.Y.unverified`. Qualität bleibt `unverified`.

## Grenzen

- Ein Plan kann **keine neue Android-API** und **keinen neuen Low-Level-Sensor**
  hinzufügen; dafür wäre ein Update derselben App nötig. Er kann auch keine
  externen Targets, Rigs oder physikalische Bodentruth erzeugen.
- Keine Bilder, Posen, Trajektorien, Kalibrierarrays, Pixel oder dichte Geometrie.
- Kein automatischer Upload, keine Redaktion, keine Signaturverifikation,
  keine unabhängigen physischen Targets, keine Messung von Zentimetern.
- Die Selbsttests prüfen nur modellunabhängige Strukturinvarianten; sie
  beweisen keine Linsen-/IMU-Kalibrierung, keine optische Qualität, keine
  Zentimetergenauigkeit, keine thermische Ausdauer und keine
  ARCore-Äquivalenz.
- Qualitätsgrad bleibt `unverified`; das Log liefert begrenzte Laufzeitfakten,
  keinen physischen Bodentruth, keinen Rig-Beweis, keine optische oder
  thermische Ausdauerbewertung.
- Die App schreibt Dateien; der AR-Kern selbst betreibt weiterhin keine I/O.
