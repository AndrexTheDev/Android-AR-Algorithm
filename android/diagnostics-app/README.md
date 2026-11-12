# AR-Kern Diagnose-App (ein installiertes Plan-Laufwerk)

Diese sideloadbare Host-App ist bewusst **eine einzige, dauerhaft installierte
App** für ein physisches Android-Gerät, z. B. ein Redmi Note 13. Sie soll
möglichst viele zukünftige Testvarianten abdecken, ohne dass du dafür jedes Mal
eine neue App brauchst. Version `2.0-plan` führte den versionierten
`ARKFDPLAN1`-Textinterpreter ein: Dauer, Reihenfolge, Wiederholungen und die
begrenzten Sensor-/Kamera-/Speicher-/Thermal-Parameter werden als **Textplan**
geändert. Nur eine wirklich neue Android-API oder eine fehlende
Plattformberechtigung erfordert ein Update **derselben App-ID** – keine zweite
App und kein neues Installationsobjekt. Version `2.1-highrate` ist genau so ein
Update: Ein erster host-supplied Redmi-Lauf auf Android 15 hat gezeigt, dass
`SENSOR_DELAY_FASTEST` die normale Berechtigung
`android.permission.HIGH_SAMPLING_RATE_SENSORS` braucht; sie ist jetzt im
Manifest deklariert, wird im Berechtigungskatalog mitprotokolliert und ist in
der Android-CI gegen versehentliches Entfernen abgesichert. Version
`2.2-calibration` ergänzt einen eigenen Opt-in-Bildschirm für physische
Kalibrierungs-Evidenz (`ARKCALIB1`). `2.3-adaptive` ergänzt die
geräteadaptive, systematische Kalibrierungsführung (`ARKADAPT1`).
`2.4-campaign` ergänzt den geräteübergreifenden, systematischen
Messkampagnenplan (`ARKCAMPAIGN1`). `2.5-binding` ergänzt die
fail-closed Sitzungsbindung (`ARKBIND1`) der drei Textdateien. `2.6-cross`
ergänzt den systematischen Gerätevergleich (`ARKCROSS1`) mehrerer Sitzungen.
`2.7-retention` ergänzt die echte Android-Retention-Ansicht mit
`ARKRETENT1`-Laufakte. `2.8-measure` ergänzt die geführte
Messdurchführung (`ARKMEAS1`), die deine physischen `ARKCALIB1`-Messungen pro Szene bindet. **`2.9-feed` ergänzt endlich einen echten Live-Kamerafeed** mit vier Messmarken (`ARKFEED1`) – weiterhin dieselbe App-ID, kein neues Installationsobjekt. Auf dem Hauptbildschirm steht jetzt sichtbar `Version 2.9-feed`; fehlt dieser Text, ist noch eine alte APK installiert.
**`2.10-ruler` ergänzt die automatische Zollstock-Erkennung (`ARKSCALE1`)**: die App findet die Zentimeter-Marken eines ausgeklappten Zollstocks selbst im Bild. Auf dem Hauptbildschirm steht jetzt sichtbar `Version 2.10-ruler`; fehlt dieser Text, ist noch eine alte APK installiert.
**`2.11-guide` räumt die Bedienung auf**: die Knöpfe sind in drei benannte Abschnitte gruppiert, eine ein- und ausklappbare Checkliste nennt die komplette Testreihenfolge, und der Inhalt wird nicht mehr von der Android-Navigationsleiste verdeckt. Auf dem Hauptbildschirm steht jetzt sichtbar `Version 2.11-guide`; fehlt dieser Text, ist noch eine alte APK installiert.

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
   `arena/78b73a98-android-ar-algorithm` öffnen.
2. Artefakt `arkernel-diagnostics-apk` herunterladen.
3. `arkernel-diagnostics-debug.apk` auf das Gerät kopieren, „Unbekannte Quellen“
   für die Installation erlauben und installieren. Es ist ein Debug-Build, kein
   Play-Store-Release.

Alternativ liegt die Datei dauerhaft als Release-Asset bereit:
<https://github.com/AndrexTheDev/Android-AR-Algorithm/releases/tag/diagnostics-apk-latest>
(`arkernel-diagnostics-debug.apk`). Die CI aktualisiert dieses Asset bei jedem
erfolgreichen Push auf den aktiven Branch. Ein Update ersetzt dieselbe
installierte App; `applicationId` und Dateiname bleiben stabil. Für den
Kalibrierungs-Bildschirm muss die installierte App mindestens
`2.2-calibration` sein – im Zweifel das aktuelle Asset neu installieren.
Die Vorprüfung auf dem Gerät ersetzt keine Auswertung hier im Chat.

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

### Langsame Lichtsensoren: Schwelle als Text ändern

Der Standardplan erwartet bei `SENSOR_LIVE` mindestens 50 Samples in 5 s.
Ein host-supplied Redmi-Lauf mit `2.1-highrate` lieferte beim Lichtsensor 21
Samples bei etwa 300 ms Abstand; `mono.0`, `finite.0` und `gapms.300` waren
sauber, trotzdem wurde der Schritt wegen der fixen Schwelle `pass.0`. Das ist
fail-closed und kein Sensor-Defekt: `expectedMin` ist eine vom Host gewählte
Strukturerwartung, keine Gerätegarantie.

Für solch langsame Lichtsensoren genügt es, im Planfeld nur diese Zeile zu
ändern:

```text
step=SENSOR_LIVE|5000|2000|2|light
```

Ein fertiger, kanonisch gültiger Plan liegt unter
`docs/examples/ARKFDPLAN1_redmi_light_tuned.txt` (CRC-32 `0600c70b`). Ein
dritter host-supplied Redmi-Lauf hat diese Datei byte-identisch verwendet und
damit `pass.20.of.20.unverified` erreicht — **ohne neue APK**. Die Qualität
bleibt `unverified`; die Schwelle ist keine Messgenauigkeit und kein
Bodentruth.

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

## Physische Kalibrierung mit `2.2-calibration`

**Kurz vorweg:** Es gibt **keine universelle Kalibrierung**, die für alle
Handys gleich gilt. Kalibrierung ist immer an **dieses Gerät, diese Linse,
diese Szene und echte, unabhängige Messwerte** gebunden. Die App rechnet keine
Kalibrierung „aus dem Nichts" aus und stuft die Qualität nicht selbst hoch.

Der neue Knopf **5. Physische Kalibrierung öffnen (ARKCALIB1)** führt zu einem
eigenen Bildschirm mit eigenem Opt-in. So geht du vor:

1. Lege ein **real bekanntes Maß** in die Szene, z. B. ein ausgedrucktes
   Maßband, eine vermessene Strecke oder ein Referenzobjekt bekannter Länge.
   Wichtig: Es muss **unabhängig** vom AR-Kern gemessen sein (Zollstock,
   vermessenes Target, Sensor-Referenz).
2. Trage **Referenzlänge (m)**, **vom Kern gemessene Länge (m)** und die
   **Unsicherheit der Referenz (m)** ein und wähle die Herkunft
   (`scale_bar`, `survey`, `sensor_ground_truth`).
3. Lasse **Held-out** aktiv: Diese Messung darf **nicht** zum Kalibrieren
   benutzt worden sein, sonst ist sie als Beweis wertlos.
4. **1. Messung hinzufügen** – bis zu 16 Messungen. Für eine sinnvolle
   Auswertung braucht es mindestens 3 Messungen aus mindestens 2 verschiedenen
   Größenbereichen (0,25-m-Bins), und die größte Referenz muss die
   Objektausdehnung abdecken.
5. Optional: **2. Orientierungs-Probe erfassen** liest einmal echt den
   Rotationssensor des Handys. Das ist eine **Geräteorientierung**, keine
   metrische 6-DoF-Position und kein Maßstabsbeweis.
6. **3. ARKCALIB1 prüfen & speichern** baut die Textdatei, prüft sie
   fail-closed und schreibt `ARKCALIB1_<run>.txt` nach
   `Downloads/ARKernelDiagnostics`. Mit **ARKCALIB1 teilen** kannst du den
   Text direkt hier im Chat einfügen.

Die Anzeige „Vorprüfung bestanden" bedeutet **nur**, dass die eingetragenen
Zahlen die dokumentierten held-out Grobgrenzen erreichen (mittlerer Fehler
≤ 1 cm, unsicherheitsbereinigter Maximalfehler ≤ 1 cm, relativer Fehler
≤ 2 %). Sie ist **keine Qualitätsnote**. Die maßgebliche Bewertung bleibt
`assessReconstructionQuality` mit `evaluateHeldOutDimensions` und verlangt
zusätzlich kalibrierte Intrinsics/Extrinsics, unabhängigen Maßstab,
konvergierte globale Optimierung, Reprojektion ≤ 1 px, Tiefen-RMSE ≤ 0,01 m,
Maßstabsstreuung ≤ 0,5 % und Abdeckung ≥ 70 %/85 %. Erst mit **echter**
Evidenz darf `approximate` oder `centimeter-validated` vergeben werden; auf
dem Gerät bleibt alles `unverified`.

**Was ich nicht für dich tun kann:** Ich habe keinen Zugriff auf dein Handy –
kein ADB, keine Sensoren, keine Kamera. Die Messung musst du auf dem Gerät
durchführen. Schicke mir danach den `ARKCALIB1`-Text (oder die Datei); ich
werte ihn hier deterministisch gegen die Vertrags- und Qualitätsgates aus.
Keine Bilder, Pixel, Trajektorien oder dichte Geometrie, keine automatische
Kalibrierung, keine Signaturprüfung, kein Upload, keine Qualitätsaufstufung.

## Geräteadaptive Führung mit `2.3-adaptive`

**Die ehrliche Antwort auf „geht das nicht automatisch bei der Installation?":**
Teilweise. Android bietet **keinen** verlässlichen Installations-Hook, mit dem
eine App schon beim Einrichten kalibrieren könnte. Und eine **universelle
Kalibrierung für alle Modelle gibt es nicht**: Linsen, IMU, Zeitstempel,
Rolling Shutter und Thermik sind pro Gerät verschieden. Was die App aber
automatisch kann und jetzt auch tut: beim Öffnen das Gerät erkennen und daraus
ein **festes, systematisches Profil** wählen. Übertragbar auf jedes
Android-Gerät ist also das **Verfahren**, nicht ein Kalibrierwert.

Der Knopf **6. Geräteadaptive Kalibrierung (ARKADAPT1)** öffnet einen
Bildschirm, der beim Start selbst liest: Kamera-Hardwareklasse
(`LIMITED` / `FULL` / `LEGACY` / unbekannt), Anzahl Kameras, Gyroskop,
Rotationssensor, Thermik-Verfügbarkeit, RAM-Klasse und Android-Version. Daraus
wählt `selectProfile()` **deterministisch** ein Profil mit 1–16 Schritten:
Gerätekatalog, Intrinsics-Prüfung, IMU-Stillstand, Orientierungs-Probe,
Thermik-Snapshot, Maßstäbe von 0,25 m bis 4 m (je nach Geräteklasse weniger),
Survey-Target, Kontrolllänge und Held-out-Review. Schwache oder unbekannte
Geräte bekommen **konservativ weniger** Maßstabsschritte, nie mehr.

Bedienung:

1. **6. Geräteadaptive Kalibrierung** öffnen. Oben stehen Geräte-Token
   (opaker SHA-256-Auszug aus Modellkennungen, keine Personendaten),
   Geräteklasse und die automatisch gewählte Schrittfolge.
2. Pro Schritt **1. Schritt bestätigen** nur dann anklicken, wenn du ihn
   wirklich auf diesem Gerät ausgeführt hast – oder bewusst
   **überspringen** (wird protokolliert). Die App bestätigt nichts selbst.
3. Für echte Maße **Physische Kalibrierung öffnen (ARKCALIB1)** nutzen: dort
   Zollstock-/Baustellen-Messungen eintragen und `ARKCALIB1_<run>.txt`
   erzeugen.
4. **2. ARKADAPT1 prüfen & speichern** baut die Textdatei, prüft sie
   fail-closed und schreibt `ARKADAPT1_<run>.txt` nach
   `Downloads/ARKernelDiagnostics`. Mit **ARKADAPT1 teilen** kannst du beide
   Texte hier im Chat einfügen.

Die Anzeige „Alle Schritte adressiert" ist **nur eine Vollständigkeitsprüfung**,
keine Qualitätsnote. `intrinsics_state` bleibt in der App bewusst
`untrusted` oder `unavailable`: ein vorhandenes
`LENS_INTRINSIC_CALIBRATION`-Feld ist noch kein verifiziertes, signiertes
Kalibrierprofil. Die maßgebliche Bewertung bleibt
`assessReconstructionQuality` mit `evaluateHeldOutDimensions` und echten,
unabhängigen Referenzen. Keine Bilder, Posen, Trajektorien oder dichte
Geometrie, keine automatische Kalibrierung, keine Signaturprüfung, kein
Upload, keine Qualitätsaufstufung; Status `unverified`.

## Systematische Messkampagne mit `2.4-campaign`

Der Knopf **7. Systematische Messkampagne (ARKCAMPAIGN1)** baut aus der
automatisch erkannten `ARKADAPT1`-Geräteklasse einen festen Plan. **Das
Verfahren ist auf allen Android-Geräten identisch**: neun Szenenarten
(Maßstab drinnen/draußen, rechte Winkel, Stillstand, Thermik-Wiederholung,
Tag/Nacht, Held-out, Sitzungs- und Gerätevergleich) und zehn kanonische Fragen
mit festen Wiederholungstiefen. Nur die Szenenliste wird bei `limited`,
`legacy` oder `unknown` kürzer (9 → 8 → 6 → 5); die Fragen bleiben gleich, und
fehlende Szenen werden als `unsupportedQuestionCount` ausgewiesen.

Die systematischen Fragen lauten: `Q_SCALE_SHORT`, `Q_SCALE_LONG`,
`Q_ORTHOGONALITY`, `Q_ORIENTATION_STILLNESS`, `Q_THERMAL_DRIFT`,
`Q_LIGHTING_CHANGE`, `Q_HELD_OUT_DIMENSION`, `Q_REPEATABILITY`,
`Q_CROSS_SESSION`, `Q_CROSS_DEVICE`. Jede Frage verlangt eine abgeschlossene
Szene der passenden Art mit mindestens der festgelegten Zahl Wiederholungen.
`Q_HELD_OUT_DIMENSION` erzwingt zusätzlich eine Held-out-Szene, also ein Maß,
das **nicht** zum Kalibrieren benutzt wurde.

Bedienung:

1. **7. Systematische Messkampagne** öffnen. Oben stehen Geräte-Token,
   Geräteklasse, nächste Szene und nächste Frage.
2. Pro Szene **1. Szene als durchgeführt markieren** (nach der echten Messung
   mit Zollstock/Baustellen-Target) oder bewusst **überspringen**.
3. Pro Frage **2. Frage mit Evidenz bestätigen** (nur wenn die zugehörigen
   Wiederholungen wirklich erfolgt sind) oder **überspringen**.
4. Für echte Maße weiterhin **5. Physische Kalibrierung (ARKCALIB1)** und für
   die Geräteanpassung **6. Geräteadaptive Kalibrierung (ARKADAPT1)** nutzen.
5. **3. ARKCAMPAIGN1 prüfen & speichern** erzeugt `ARKCAMPAIGN1_<run>.txt`
   unter `Downloads/ARKernelDiagnostics`; **teilen** fügt den Text hier im
   Chat ein.

`campaignEvidenceComplete` bedeutet nur: alle Fragen vom Host bestätigt und
die passenden Szenen mit genug Wiederholungen vorhanden. Das ist **keine
Antwort**, kein Messergebnis, keine Zentimetergenauigkeit und keine
Qualitätsnote. Die maßgebliche Bewertung bleibt
`assessReconstructionQuality` mit `evaluateHeldOutDimensions` und echten,
unabhängigen Referenzen. Keine Bilder, Posen, Trajektorien, keine
automatische/universelle Kalibrierung, keine Feldmessung in der App, keine
Signaturprüfung, kein Upload, keine Qualitätsaufstufung; `unverified`.

## Sitzungsbindung mit `2.5-binding`

Der Knopf **8. Sitzungsbindung (ARKBIND1)** führt die drei vorher erzeugten
Textdateien zusammen: `ARKADAPT1` (Geräteprofil), `ARKCALIB1` (echte Maße)
und `ARKCAMPAIGN1` (systematischer Plan). Die App parst alle drei, setzt sie
auf ihre kanonische Form zurück und bildet darüber jeweils SHA-256. Dadurch
binden BOM/CRLF-Varianten derselben Datei identisch. **Bedingung:** alle drei
müssen denselben Geräte-Token und dieselbe Geräteklasse tragen – sonst wird
fail-closed abgelehnt (`DEVICE_TOKEN_MISMATCH` / `DEVICE_CLASS_MISMATCH`).

Bedienung:

1. Die drei Dateien aus den Bildschirmen 5, 6 und 7 teilen/kopieren und in die
   drei Textfelder einfügen (oder die Zwischenablage-Knöpfe nutzen).
2. **1. Drei Dateien prüfen & ARKBIND1 speichern** klicken. Die Anzeige meldet
   `Digests`, `Token`, `Zähler`, `Klasse`, `Bindung konsistent`,
   `Evidenz-Vorprüfung` und `Kampagne vollständig`.
3. `ARKBIND1_<run>.txt` liegt unter `Downloads/ARKernelDiagnostics`; mit
   **2. ARKBIND1 teilen** kannst du sie hier im Chat einfügen.

**Wichtig:** `Bindung konsistent` heißt nur: die drei Dateien gehören
digestgenau zusammen. Das ist **keine Antwort auf eine Messfrage**, kein
Messergebnis, keine Zentimetergenauigkeit und keine Qualitätsnote. Die
maßgebliche Bewertung bleibt `assessReconstructionQuality` mit
`evaluateHeldOutDimensions` und echten, unabhängigen Referenzen. Keine
Signaturprüfung, kein automatischer Upload (nur manuelles Teilen), keine
Bilder, Posen oder Trajektorien, keine automatische/universelle Kalibrierung,
keine Qualitätsaufstufung; `unverified`.

## Gerätevergleich mit `2.6-cross`

Der Knopf **9. Gerätevergleich (ARKCROSS1)** führt **1–16 gebundene
Sitzungen** aus verschiedenen Geräten zusammen. Dazu die `ARKBIND1`-Texte
untereinander einfügen und zwischen ihnen je eine eigene Zeile mit genau
`===` setzen. Die App parst jeden Umschlag, bildet über seine kanonische Form
SHA-256 und verlangt **eindeutige Geräte-Tokens** und eindeutige Digests.
Alle Zeilen tragen dieselben zehn kanonischen Fragen und dasselbe portable
Protokoll; nur Geräteklasse, Kampagne und Zähler dürfen Unterschiede zeigen.

Die Anzeige meldet `Sitzungen`, `verschiedene Geräte`, `Geräteklassen`,
`Kampagnen`, Szenen-/Beobachtungsgrenzen, `Konsistent`, `Quellen erneut
geprüft: false` und `portables Protokoll`. Ausgabe:
`ARKCROSS1_<run>.txt` unter `Downloads/ARKernelDiagnostics`.

**Wichtig:** `ARKCROSS1` bindet nur **deklarierte** `ARKBIND1`-Umschläge –
es rechnet die drei Quelltexte dahinter **nicht** erneut nach
(`sourceReverificationPerformed=false`). Dafür bleibt `ARKBIND1.verify()`
mit den Originaldateien nötig. `Konsistent` ist keine Antwort, kein
Messergebnis, keine Zentimetergenauigkeit und keine Qualitätsnote. Keine
Signaturprüfung, kein automatischer Upload, keine Bilder/Posen/Trajektorien,
keine automatische/universelle Kalibrierung, keine Qualitätsaufstufung;
`unverified`.

## Retention-UX mit `2.7-retention`

Der Knopf **10. Retention-UX (ARKRETENT1)** öffnet einen echten
Android-Bildschirm um die bereits vorhandene, UI-neutrale
`ArKernelRetentionUiComposition`: ein lokalisierter `AlertDialog`, explizite
Vorschauen für „Publikation erfolgreich" und „explizite Freigabe", ein
**Trockenlauf-Sink** (löscht nichts), Lifecycle-Wiederherstellung, Navigation
zurück zur Übersicht sowie `contentDescription`-Texte und
`announceForAccessibility`-Ansagen.

Bedienung:

1. **10. Retention-UX** öffnen, dann **1. Lokalisierten Dialog öffnen** und
   einen der drei Modi (`KEEP`, `DELETE_AFTER_PUBLICATION`,
   `DELETE_ON_EXPLICIT_RELEASE`) explizit bestätigen.
2. **2.** oder **3.** erzeugt nur eine Vorschau-Aktion; **4.** wendet sie
   ausschließlich auf den Trockenlauf-Sink an. Es werden **keine** Frames,
   Schlüssel oder Speicher angefasst.
3. **5. ARKRETENT1 prüfen & speichern** schreibt `ARKRETENT1_<run>.txt` nach
   `Downloads/ARKernelDiagnostics`; **6.** teilt den Text hier im Chat.

Die Laufakte enthält nur Strukturangaben: Locale, Modus, Schlüsselverfügbarkeit,
Dialog geöffnet, Aktionsart, trocken angewendet. Die drei Invarianten
`explicit_confirmation_required`, `dry_run` und
`physical_validation_disabled` sind verbindlich aktiv. **Physische Validierung
des verschlüsselten Keyframe-Lebenszyklus auf echten Geräten bleibt offen.**
Keine Signaturprüfung, kein automatischer Upload, keine Bilder/Posen/
Trajektorien, keine automatische/universelle Kalibrierung, keine
Qualitätsaufstufung; `unverified`.

## Geführte Messdurchführung mit `2.8-measure`

Der Knopf **11. Geführte Messdurchführung (ARKMEAS1)** führt dich durch die
neun festen Szenen des portablen Protokolls: kurzes Innenmaß, langes Außenmaß,
rechter Winkel, Stillstand, Thermik-Wiederholung, Tag/Nacht, Held-out,
Sitzungsvergleich und Gerätevergleich. Du misst **selbst** mit unabhängiger
Referenz (Zollstock, Baustelle) und fügst für jede Szene den zugehörigen
`ARKCALIB1`-Text ein – mehrere Texte werden durch eine Zeile `===` getrennt.
Maximal neun Szenen in fester Reihenfolge.

Bedienung:

1. Für jede Szene in Knopf **5** ein `ARKCALIB1_<run>.txt` erzeugen (mit
   `capture_opt_in`, Referenzwert, gemessenem Wert, Unsicherheit, Held-out).
2. In Knopf **11** die Texte in derselben Reihenfolge einfügen, Kampagnen-ID
   und Geräteklasse wählen, **„Ich habe alle Szenen selbst … bestätige sie"**
   anhaken und mindestens eine **Held-out**-Messung markieren.
3. **ARKMEAS1 prüfen & speichern** schreibt `ARKMEAS1_<run>.txt` nach
   `Downloads/ARKernelDiagnostics`; **ARKMEAS1 teilen** gibt den Text hier
   im Chat weiter.

Jeder `ARKCALIB1`-Text wird kanonisch neu kodiert und mit SHA-256 gebunden;
`create()` verlangt eindeutige Digests, eindeutige Szenen-IDs, ausdrückliche
Bestätigung **jeder** Szene und mindestens eine Held-out-Szene. Die Vorschau
meldet nur Strukturzahlen (Szenen, bestätigte, Held-out, Digests) und
`evidenceComplete` als **Strukturvollständigkeit** – kein Genauigkeitsurteil.
`verify()` prüft nur die `ARKMEAS1`-Struktur, nicht die Quelltexte erneut
(`sourceReverificationPerformed=false`).

**Kein echtes KI-Modell, keine Inferenz, keine Gewichte, kein Netz.** Ein
Modell könnte hier höchstens als versionierter Konformitätstest dienen, nie als
Ersatz für deine physische Referenz. Keine Bilder, Posen oder Trajektorien,
keine automatische/universelle Kalibrierung, keine Signaturprüfung, kein
automatischer Upload, keine Zentimetervalidierung, keine
Qualitätsaufstufung; `unverified`.

## Live-Kamerafeed mit `2.9-feed`

Der Knopf **12. Live-Kamerafeed & Messung (ARKFEED1)** öffnet nach
ausdrücklichem Opt-in eine echte Vorschau der Rückkamera (Camera2,
`TextureView`). So geht es:

1. **Opt-in** anhaken, **Kamera starten**. Die App zählt Vorschaubilder,
   speichert aber **kein einziges Bild**.
2. Zwei Marken auf eine bekannte Referenzstrecke tippen (z. B. 30 cm
   Zollstock), dann zwei Marken auf das Ziel. Grün = Referenz, Blau = Ziel.
3. **Referenzlänge in m** und Unsicherheit eintragen, Szenenart und
   Bedingung wählen, **„Ich habe die Referenz selbst … bestätige diese
   Aufnahme"** anhaken.
4. **ARKFEED1-Messung aufnehmen** schreibt
   `ARKFEED1_<run>_<index>.txt` nach `Downloads/ARKernelDiagnostics`;
   **ARKFEED1 teilen** gibt den Text hier im Chat weiter.

Der `ARKFEED1`-Vertrag hat 27 feste Schlüssel, 11 verbindliche
Fail-closed-Invarianten, vier geordnete Markerzeilen und eine
konsistente Bildverhältnis-Geometrie. `frames_exported` ist immer `0`;
`frames_observed` zählt nur. `metric_accuracy_claimed` ist immer `false`:
`estimated_target_m` ist eine **Bildverhältnis-Schätzung** unter der
Annahme einer frontoparallelen Ebene gleicher Tiefe – **keine metrische
Messung**, keine Zentimetergenauigkeit, kein ARCore-Tiefenersatz. Marken
liegen in Bildschirm-Pixeln, nicht in Metern. `unverified`.

**Kein echtes KI-Modell, keine Inferenz, keine Gewichte, kein Netz.** Ein
Modell könnte auch hier höchstens als versionierter Konformitätstest
dienen, nie als Ersatz für deine unabhängige physische Referenz. Keine
automatische/universelle Kalibrierung, keine Signaturprüfung, kein
automatischer Upload, keine Qualitätsaufstufung.

## Zollstock automatisch erkennen mit `2.10-ruler`

Der Knopf **13. Zollstock automatisch erkennen (ARKSCALE1)** nimmt dir das
genaue Tippen ab. Du klappst den Zollstock auf eine bekannte Länge aus
(**1 m** oder **2 m**), hältst ihn ins Bild und tippst nur noch **grob** die
beiden Enden des Zollstocks und die beiden Enden des Ziels an. **„Zollstock
erkennen"** sucht dann selbst die Zentimeter-Marken.

So geht es:

1. Zollstock ausklappen, flach und möglichst parallel zur Zielfläche halten.
2. Opt-in anhaken, **Kamera starten**, vier Marken grob setzen
   (1–2 Zollstock, 3–4 Ziel).
3. **Ausgeklappte Länge** eintragen (Knopf **1 m** / **2 m** hilft dabei).
4. **Zollstock erkennen** drücken. Die App meldet, wie viele Marken sie
   gefunden hat, wie groß der Abstand und wie **gleichmäßig** sie sind, und
   zeichnet alle erkannten Marken gelb ein.
5. **„Erkannte Marken geprüft"** anhaken, dann **ARKSCALE1-Messung
   aufnehmen** und die Datei hier im Chat hochladen.

**Die Plausibilitätsprüfung ist der eigentliche Nutzen:** Aus der eingetragenen
Länge erwartet die App 100 cm-Marken pro Meter. Findet sie deutlich mehr oder
weniger, steht im Protokoll `tick_count_consistent=false` – du siehst sofort,
ob der falsche Maßstab erkannt, der Zollstock schlecht ausgeleuchtet oder die
Länge falsch eingetragen wurde.

Steht das Bild auf dem Kopf, drehe es mit **„Bild um 180° drehen"**. Die
Messung bleibt dabei korrekt, weil Marken und Bild dieselbe Drehung teilen.

Der Bildinhalt wird **nur im Arbeitsspeicher** ausgewertet und sofort
verworfen: kein Bild, Frame, Pixel oder Pose wird gespeichert oder geteilt,
`frames_exported=0` und `frame_persistence_disabled=true`. Die Erkennung macht
Endpunkte **wiederholgenau**, nicht **metrisch richtig**: Tiefe, Perspektive,
Rolling-Shutter und Verzeichnung bleiben unberücksichtigt, daher ist
`metric_accuracy_claimed=false`. **Kein KI-Modell, keine Inferenz, keine
Gewichte, kein Netz** – nur feste, deterministische Luminanzschwellen.
`unverified`.

## Bedienung mit `2.11-guide`

### Der untere Rand ist wieder frei

Android 15 erzwingt für Apps mit `targetSdk 35` die randlose Darstellung. Ohne
Gegenmaßnahme liegt die **Android-Navigationsleiste** (Home / Zurück /
Übersicht) über dem letzten Knopf, und die Statusleiste über dem ersten. Alle
zehn Bildschirme erben jetzt von `DiagnosticsActivity`: diese Basis zeichnet
bewusst unter den Systemleisten und addiert deren Höhen **einmalig** als
Rand. Beim Drehen, bei Tastaturwechsel oder beim Umschalten zwischen
Drei-Knopf- und Gestensteuerung wird der Rand neu berechnet, nicht addiert.

### Drei Abschnitte statt einer langen Liste

- **SCHRITT 1 · Log & Plan** – Knöpfe 1–4 plus Plan-Text und Teilen
- **SCHRITT 2 · Kalibrierung & Sitzungen** – Knöpfe 5–10
- **SCHRITT 3 · Messung mit Kamera & Zollstock** – Knöpfe 11–13

### Checkliste: alles testen

Der Knopf **„Checkliste: alles testen (anklopfen)"** blendet die komplette
Reihenfolge ein – von `ARKFDPOL1/ARKFDPLAN1/ARKFDLOG1` über `ARKADAPT1`,
`ARKCAMPAIGN1` und `ARKCALIB1` bis zu `ARKSCALE1`, `ARKFEED1`, `ARKMEAS1`,
`ARKBIND1`, `ARKRETENT1` und `ARKCROSS1`. Damit ist die App so vorbereitet,
dass du das geforderte Programm ohne Rückfragen durchlaufen kannst.

**Die Checkliste ist eine manuelle Anleitung, keine Verifikation.** Die App
prüft nicht selbst, welche Dateien schon vorliegen, wertet keine Messwerte aus
und vergibt keine Freigabe. Alle Dateien landen in
`Downloads/ARKernelDiagnostics` und werden von dir hier im Chat hochgeladen.
`unverified`.

## Bedienung mit `2.12-valid`

Neu ist Knopf **14. Metrische Validierung in mm (ARKVALID1)** im Abschnitt
**SCHRITT 3**. Damit kannst du erstmals ablesen, **wie weit die App daneben
liegt** – statt nur zu hören, dass sie keine Genauigkeit behauptet.

So geht es pro Szene:

1. Mit Knopf 12 (`ARKFEED1`) oder Knopf 13 (`ARKSCALE1`) messen und das
   Artefakt teilen.
2. In Knopf 14 den Text einfügen und „Artefakt lesen“ drücken. Die App
   liest `estimated_target_m` daraus, zeigt die Schätzung in Millimetern und
   füllt Szenenart, Bedingung und die Zurückgehalten-Kennzeichnung.
3. Dasselbe Ziel **noch einmal mit dem Maßband** messen und in Millimetern
   eintragen (z. B. `1500` für 1,50 m).
4. **„Szene übernehmen“** – mindestens eine Szene muss als *zurückgehalten*
   markiert sein.
5. **„Validierung berechnen“** liefert mittlere absolute Abweichung,
   systematische Abweichung (zu kurz/zu lang), größte Abweichung,
   95-Prozent-Abweichung und die Zahl der Szenen innerhalb deiner Toleranz
   (Standard 10 mm).
6. **„ARKVALID1 speichern“** schreibt `ARKVALID1_<Zeitstempel>.txt` nach
   `Downloads/ARKernelDiagnostics`; bitte hier im Chat hochladen.

Jede Szene ist an den SHA-256-Digest des eingefügten Artefakt-Textes gebunden,
damit dieselbe Messung nicht doppelt zählt. Die Datei enthält alle Zahlen,
die Toleranz und die Statistik; beim Einlesen wird die Statistik nachgerechnet
und eine Abweichung zurückgewiesen.

**Das ist die gemessene Abweichung, kein Genauigkeitsversprechen.** Dein
Referenzmaß gibst du selbst ein, die App prüft es nicht nach; Tiefe,
Perspektive, Rolling-Shutter und Verzeichnung bleiben unberücksichtigt.
`metric_accuracy_claimed=false` ist eine harte Invariante, der Status bleibt
`unverified`.

## Bedienung mit `2.13-sign`

Neu ist Knopf **15. Signatur & Prüfung (ARKSIGN1)** im neuen Abschnitt
**SCHRITT 4 · Vertrauen & Upload**.

1. **„Schlüssel erzeugen“** – ein EC-P-256-Schlüssel. Liegt er im
   Android-Keystore, ist er nicht exportierbar; nur wenn das Gerät das nicht
   hergibt, wird ein Software-Schlüssel im app-privaten Speicher erzeugt.
   **Der Ursprung steht in jeder Datei** (`key_origin`).
2. **„Öffentlichen Schlüssel speichern“** schreibt
   `ARKSIGN1_pubkey_<Fingerprint>.txt` nach `Downloads/ARKernelDiagnostics`.
   **Bitte einmal hier im Chat hochladen** – nur damit kann später jemand die
   Signatur nachrechnen.
3. Pro Artefakt einen Namen eintragen, den `ARK…`-Text einfügen und
   **„Eintrag hinzufügen“** (bis zu 32 Einträge, Namen und Texte eindeutig).
4. **„Manifest signieren“** – die App prüft das Ergebnis sofort selbst, bevor
   sie dich speichern lässt.
5. **„ARKSIGN1 speichern“** bzw. **„ARKSIGN1 teilen“**.
6. Zum Prüfen: ein `ARKSIGN1`-Manifest einfügen und **„Manifest prüfen“**
   drücken. Ohne eingefügten Fremdschlüssel wird gegen den hier verankerten
   Schlüssel geprüft.

**Was die Signatur beweist:** die gebundenen Einträge wurden nicht verändert
und derselbe Schlüssel hat signiert.

**Was sie nicht beweist:** wer den Schlüssel besitzt. Es gibt keine
Zertifizierungsstelle, keine Geräte-Attestierung und keine Vertrauenskette –
ein gerootetes Gerät oder eine kopierte Software-Schlüsseldatei ergibt eine
formal gültige Signatur. Ebenso wenig sagt eine verifizierte Signatur etwas
über die Qualität der Messung: `unverified` bleibt `unverified`.

## Grenzen

- Ein Plan kann **keine neue Android-API**, **keine fehlende Plattformberechtigung**
  und **keinen neuen Low-Level-Sensor** hinzufügen; dafür wäre ein Update
  derselben App-ID nötig. Er kann auch keine externen Targets, Rigs oder
  physikalische Bodentruth erzeugen.
- Im Lauf-Log: keine Bilder, Posen, Trajektorien, Kalibrierarrays, Pixel oder
  dichte Geometrie. Der separate Kalibrierungs-Bildschirm erfasst mit eigenem
  Opt-in nur Maßstabszahlen und Orientierungsproben, keine metrische Position,
  keine Bilder und keine Kalibrierarrays.
- `WRITE_EXTERNAL_STORAGE` ist bewusst auf `maxSdk=28` begrenzt; auf Android 15
  erscheint sie im Berechtigungskatalog daher als `0`, ohne dass dies ein
  Laufzeitfehler ist. `HIGH_SAMPLING_RATE_SENSORS` ist eine normale Berechtigung
  ohne zusätzlichen Laufzeitdialog; mit erteilter Kamera-Berechtigung meldet der
  Katalog auf Android 15 typischerweise `granted.3.of.4`.
- Kein automatischer Upload, keine automatische oder universelle Kalibrierung,
  keine Kalibrierung bei der Installation, keine Redaktion, keine
  Redaktion. Automatisch sind nur
  Geräteerkennung, Profilwahl, Planerzeugung, digestgenaue Bindung, der
  kanonische Vergleich deklarierter Sitzungen, die trockene
  Retention-Vorschau, die kanonische Digest-Bindung deiner
  ARKCALIB1-Messungen, die Live-Vorschau mit Zählung der
  Frames, die Erkennung der Zollstock-Marken im
  Arbeitsspeicher und das Nachrechnen der Abweichungsstatistik gegen dein
  eingetragenes Maßband-Referenzmaß. Unabhängige physische Targets musst du
  selbst messen und eintragen; die App misst die Abweichung gegen deine
  Referenz, aber nicht die Referenz selbst. Das Signieren und Prüfen mit
  Knopf 15 beweist Unversehrtheit und Schlüsselkontinuität, aber keine
  Identität des Schlüsselbesitzers und keine Messqualität.
- Die Selbsttests prüfen nur modellunabhängige Strukturinvarianten; sie
  beweisen keine Linsen-/IMU-Kalibrierung, keine optische Qualität, keine
  Zentimetergenauigkeit, keine thermische Ausdauer und keine
  ARCore-Äquivalenz.
- Qualitätsgrad bleibt `unverified`; das Log liefert begrenzte Laufzeitfakten,
  keinen physischen Bodentruth, keinen Rig-Beweis, keine optische oder
  thermische Ausdauerbewertung.
- Die App schreibt Dateien; der AR-Kern selbst betreibt weiterhin keine I/O.
