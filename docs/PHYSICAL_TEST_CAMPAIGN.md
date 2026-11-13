# Physische Testkampagne: Stand und Reihenfolge

Dieses Dokument ist die Arbeitsliste dafür, den AR-Kern mit **echten,
physikalischen Messungen** fertigzubekommen. Es sagt, was die Test-App heute
messend kann, was sie nur bucht, was noch fehlt, in welcher Reihenfolge es
zusammenhängt, und was du mir jeweils schicken musst.

Stand: Version `2.23-ladder` (Knopf 1–25). Bewertung über alles:
**`unverified`**. Es wurde nichts hochgestuft und nichts behauptet.

## 1. Was ein AR-Kern physikalisch braucht

Ein visuell-inertialer Kern integriert Beschleuniger und Gyroskop zwischen
zwei Kamerabildern. Seine Genauigkeit hängt an diesen Stücken, die in dieser
Reihenfolge aufeinander aufbauen — jedes weitere ist sinnlos, wenn das
darunterliegende nicht stimmt:

| # | Stück | Woher | Stand heute |
|---|---|---|---|
| 0 | Das Gerät liefert die Sensoren, drosselt nicht, und seine Uhren halten | Telefon allein | **messbar** (`ARKDEVHW1`) |
| 1 | Sensor-Kadenz und Zeitbasis | Telefon allein | **messbar** (`ARKIMUCAL1`) |
| 2 | Beschleuniger: Bias, Maßstab, Fehlstellung | Telefon + Tisch | **messbar** (`ARKIMUCAL1`) |
| 3 | Gyroskop: Bias, Maßstab, Nebenachsen | Telefon + rechter Winkel | **messbar** (`ARKIMUCAL1`) |
| 4 | Rauschen und Bias-Stabilität | Telefon + Tisch | **messbar** (`ARKIMUCAL1`) |
| 5 | Kameratakt, Zeitbasis, Belichtung, Erfassungsstabilität | Telefon allein | **messbar** (`ARKCAMT1`) |
| 6 | Kamera-Intrinsics (fx, fy, cx, cy, Verzeichnung) | braucht Bilder | **blockiert** – siehe §4 |
| 7 | Kamera-IMU-Zeitversatz (`t_d`) und Achsenabbildung | Telefon + geführte Bewegung | **messbar** (`ARKCAMIMU1`) |
| 7b | Kamera-IMU-**Drehung** (`R`) | Telefon + geführte Drehung | **messbar** (`ARKCAMEXT1`) |
| 7c | Kamera-IMU-**Versatz als Strecke** (`t`) | braucht Bilder + Intrinsics | **fehlt** – hängt an Stück 6 |
| 8 | Maßstab über die Entfernung | Zollstock + Maßband | **messbar** (`ARKLADDER1`) |

Stück 0 ist kein Sensorwert, sondern die Voraussetzung dafür, dass überhaupt
ein Sensorwert etwas bedeutet. Es stand bis `2.21-devhw` in keiner Tabelle, und
das war die gefährlichste Lücke dieser Liste: sie verfälscht nicht das
Ergebnis einer Reihe, sie verfälscht jede Reihe, die auf diesem Gerät läuft.

Die Stücke 0–5 sind reine Sensorik. Sie brauchen kein Bild, keine Erlaubnis
und keine Kalibrierhilfe — deshalb sind sie jetzt messbar. Stück 7 ist
ebenfalls messbar, und zwar aus einem Grund, der erklärungsbedürftig ist: der
Zeitversatz wird nicht aus der *Größe* der Bildbewegung bestimmt, sondern aus
deren *Zeitpunkt*. Zwei Kurven werden gegeneinander verschoben, bis ihre Form
übereinander liegt; ein falscher Maßstab staucht beide gleich und hebt sich
heraus. Deshalb braucht `t_d` keine Brennweite und vertraut keinem
Herstellerwert.

Dasselbe galt lange für Stück 7b, und auch dort war die Schlussfolgerung zu
voreilig — aber nur zur Hälfte.

Für die **Drehung `R`** hebt sich die unbekannte Brennweite ebenfalls auf,
nur gegen eine Nebenbedingung statt gegen die Zeit: eine Zeile von `R` hat
die Länge 1, und damit ist die Länge der aus der Regression gewonnenen
Steigung genau die Brennweite. Stück 7b braucht für `R` also **keine**
Intrinsics und wartet nicht auf Stück 6.

Für die **Translation `t`** bleibt die Kopplung dagegen echt bestehen. Eine
Strecke zwischen Kamera und IMU zeigt sich nur darin, wo der Bildinhalt bei
*verschiedenen* Entfernungen sitzt; das braucht eine Position, eine Position
braucht einen metrischen Maßstab, und den liefert hier nichts. Deshalb bleibt
die Translation als eigenes Stück 7c offen und wartet weiter auf Stück 6.
Für Stück 7 und für `R` in 7b gilt diese Kopplung nicht.

Kein Stück behält ein Bild. Die App reduziert jedes Bild im Arbeitsspeicher
auf ein Raster von Helligkeitswerten und verwirft es sofort.

Die vollständige Zuordnung von Kern-Behauptung zu nötigem Test steht in
`docs/DIAGNOSTIC_COVERAGE.md`.

## 2. Bestand: was die App heute kann

**Messend** — die App führt eine physikalische Messreihe selbst durch und
rechnet daraus Zahlen:

| Knopf | Vertrag | Messreihe | Was du brauchst |
|---|---|---|---|
| 23 | `ARKDEVHW1` | Sensorinventar, Speicher und Platz, **Drosselungsprobe** unter Dauerlast, Uhren-Drift und Rückschritte | Telefon, absichtliche Dauerlast (10/30/60 s) |
| 20 | `ARKIMUCAL1` | Kadenz, Statik, sechs Lagen, Drehung um bekannten Winkel | Telefon, Tisch, rechter Winkel |
| 22 | `ARKCAMT1` | Bildrate und Löcher, **auf welcher Uhr die Bildzeitstempel liegen**, Belichtung und Schmierung, Erfassungsstabilität | Telefon, kein Bild wird behalten |
| 13 | `ARKSCALE1` | Zollstock-Marken automatisch im Bild erkennen | ausgeklappter Zollstock |
| 12 | `ARKFEED1` | Live-Kamerafeed mit vier Messmarken | Kamera-Opt-in |
| 18 | `ARKCOND1` | Helligkeit und Thermikstufe **vom Gerät gelesen** | Telefon |

**Buchend** — die App nimmt Zahlen auf, die **du** mit einem echten Werkzeug
gemessen hast, und prüft sie auf Vollständigkeit und Widersprüche:

| Knopf | Vertrag | Was du einträgst |
|---|---|---|
| 5 | `ARKCALIB1` | Maßbeobachtungen an gehaltenen Targets |
| 14 | `ARKVALID1` | Abweichung in Millimetern gegen dein Maß |
| 17 | `ARKTARGET1` | unabhängige Targets: Sollmaß, Quelle, Rolle (Maßstab / zurückgehalten) |
| 7 | `ARKCAMPAIGN1` | Szenenliste und die zehn kanonischen Fragen |
| 11 | `ARKMEAS1` | geführte Messdurchführung pro Szene |
| 19 | `ARKCORPUS1` | welcher Korpus liegt auf welchem Gerät in welcher Zelle |
| 9 | `ARKCROSS1` | Gerätevergleich mehrerer Sitzungen |

**Vertrauen und Transport** — 15 `ARKSIGN1`, 16 `ARKREDRUN1`, 8 `ARKBIND1`.
Sie erzeugen keine Messwerte, sondern stellen sicher, dass das, was du mir
schickst, unverändert ist und nichts Persönliches enthält.

**Überblick** — 21 `ARKDSMAN1`. Erzeugt ebenfalls keine Messwerte, sondern
beantwortet die Frage, die keiner der zwanzig Bildschirme beantworten konnte:
was liegt eigentlich schon vor. Details in §2a.

### 2a. Knopf 21: der Bestand (`ARKDSMAN1`)

Vor `2.19-hub` erzeugte jeder der zwanzig Bildschirme eine Datei und vergaß
sie. Die App sagte das selbst, in ihrer eigenen Checkliste: *„Die App prüft
NICHT selbst, was schon vorliegt."* Knopf 21 tut genau das.

Er listet die Dateien in `Downloads/ARKernelDiagnostics`, rechnet zu jeder den
SHA-256, und – das ist der eigentliche Punkt – **gibt jede Datei ihrem eigenen
Vertrag zum Prüfen**. Eine abgeschnittene oder nachträglich bearbeitete Datei
wird damit als unlesbar gemeldet und nicht als vorhanden gezählt. Dazu nennt
er die Urteile, die jede Datei von sich selbst als `false` meldet, und sagt,
welcher Vertrag als Nächstes fehlt.

Drei Grenzen, die der Bildschirm auch so anzeigt:

- **Der Bestand ist kein Urteil.** Eine vorhandene und lesbare Datei ist
  *gesammelt*, nicht *gut*. Keine dieser Zeilen stuft die Qualitätsnote hoch.
- **Die Urteilssuche liest nur Text.** Sie findet Zeilen der Form
  `schlüssel=false` und kann nicht unterscheiden, ob eine Prüfung fehlgeschlagen
  ist oder die Arbeit schlicht noch nicht getan wurde. `accel_repeat_checked=false`
  heißt „nicht wiederholt“, nicht „falsch“.
- **Das Manifest bindet nur Prüfsummen.** Es merkt, wenn sich eine Datei
  danach ändert. Wer sie geschrieben hat, beweist erst die Signatur (Knopf 15).

Schlüsselexporte (`ARKSIGN1_publickey.txt`) tragen denselben Namensanfang wie
die Signatur-Manifeste, sind aber Schlüssel und keine Manifeste. Sie werden
daher aufgelistet und absichtlich **nicht** geprüft – sonst stünde auf jedem
Gerät dauerhaft „unlesbar" neben der Signatur.

### 2b. Knopf 22: der Kameratakt (`ARKCAMT1`)

Drei Dinge über eine Kamera entscheiden, ob ein Kern ihre Bilder überhaupt
mit den Trägheitsdaten verschmelzen kann — und keins davon braucht ein
aufbewahrtes Bild:

- **Bildrate und Löcher.** Ein Loch ist eine Strecke, die der Kern nicht
  sieht, sondern integrieren muss. Größer als drei mittlere Bildabstände,
  und er rechnet statt zu sehen.
- **Zeitbasis.** Liegen die Bildzeitstempel in `uptimeMillis` und die
  Trägheitsproben in `elapsedRealtime`, unterscheiden sie sich um jede
  Sekunde, die das Gerät im Tiefschlaf verbracht hat — Größenordnungen mehr
  als eine Belichtung. Die App **entscheidet** die Zeitbasis durch Beobachtung
  statt sie anzunehmen, denn falsch angenommen ist sie ein plausibel
  aussehender, aber still falscher Fehler.
- **Belichtung.** Bei 90°/s Schwenk verschmiert eine Belichtung von 33 ms das
  ganze Bild um drei Grad.

Dazu kommt die **Erfassungsstabilität**: ändert sich der Bildzuschnitt oder
der Stabilisierungs-Modus mitten im Lauf, beschreibt jede in diesem Lauf
gesammelte Zahl eine Kamera, die es nicht mehr gibt. Meldet die Kamera ihren
Stabilisierungs-Modus gar nicht, sagt die App das, statt Stabilität zu
behaupten, die sie nicht prüfen konnte.

Die Kamera läuft dabei, aber **kein Bild wird behalten**: die Vorschaufläche
existiert nur, weil Camera2 ein Ziel verlangt. Es werden keine Intrinsics
gerechnet, und die Brennweite des Herstellers wird als gemeldeter Wert
notiert, nicht geglaubt (`manufacturer_intrinsics_trusted=false`).

### 2c. Knopf 23: das Gerät darunter (`ARKDEVHW1`)

Vier Fragen, die jede lange Messreihe auf diesem Gerät beantworten muss,
bevor sie etwas aussagt:

- **Ist überhaupt da, was der Kern braucht?** Die App listet jeden Sensor auf,
  den Android meldet, mit Bereich, Auflösung, Verzögerungsgrenzen, Leistung
  und Meldeweise. Der Kern integriert Beschleuniger und Gyroskop; fehlt eines,
  kann er auf diesem Gerät nicht laufen — und das ist eine Tatsache, die man
  vor einer Kampagne wissen will, nicht nachher.
- **Reicht der Platz?** Arbeitsspeicher und freier Massenspeicher werden
  gelesen. Eine lange Erfassung, die bei achtzig Prozent stirbt, weil die
  Platte voll lief, ist kein Rekonstruktionsfehler, sondern ein
  Buchhaltungsfehler.
- **Drosselt das Gerät?** Die App rechnet zehn bis sechzig Sekunden
  ununterbrochen und vergleicht die Arbeitsrate im ersten Viertel des Laufs mit
  der im letzten. Bricht sie ein, ist das Gerät für lange Erfassungen
  unbrauchbar.
- **Halten die Uhren?** `elapsedRealtime` und `uptimeMillis` unterscheiden
  sich um alles, was das Gerät verschlafen hat — erwartbar und harmlos. Was
  nicht harmlos ist: der Abstand **wächst während eines Laufs**, weil das Gerät
  dann mitten in der Erfassung geschlafen hat. Und eine Uhr, die zurückgeht,
  verdirbt die Fusion still. Beides wird in jedem Arbeitsschritt der Probe
  gelesen.

**Die Probe drosselt mit Absicht.** Sie rechnet ununterbrochen, damit
sichtbar wird, ob der Takt einbricht — das ist der Punkt der Messung, kein
Fehler. Nichts passiert, solange du nicht startest, und der Start verlangt ein
Opt-in. Kein Sensorwert, kein Bild und kein Pixel wird behalten.

## 3. Neu in `2.18-imucal`: die vier IMU-Reihen

Knopf 20, Vertrag `ARKIMUCAL1`, Dokumentation in
[`docs/IMU_CALIBRATION.md`](IMU_CALIBRATION.md).

Die Reihenfolge ist verbindlich: die Drehungsreihe zieht den Bias ab, den die
Statikreihe gemessen hat, und erst die sechs Lagen machen das
Beschleuniger-Modell überhaupt bestimmbar.

1. **Kadenz prüfen** (3 s) — liefert die tatsächliche Abtastrate, Lücken,
   doppelte Zeitstempel und vor allem: auf welcher Uhr die Zeitstempel liegen.
2. **Statik aufnehmen** (10/30/60 s, Gerät liegt still) — liefert
   Gyroskop-Bias, Rauschen beider Triaden und Bias-Stabilität.
3. **Sechs Lagen** (jede Achse einmal nach oben, einmal nach unten, **zweimal
   durchlaufen**) — liefert Beschleuniger-Bias, Maßstab, Fehlstellung und die
   Wiederholstreuung.
4. **Drehung** (gegen eine Lehre, z. B. 90° an einem rechtwinkligen Karton) —
   liefert den Maßstabsfehler des Gyroskops je Achse und die Nebenachsen.

Die zwei Zahlen, die am Ende entscheiden, was der Kern überhaupt versuchen
darf, stehen unten im Bericht: **Lagedrift in °/min** und
**Positionsdrift in m/min** bei freier Integration ohne jede Korrektur.

## 4. Was fehlt — und warum

### 4.1 Kamera-Intrinsics (Stück 5) — blockiert, Entscheidung nötig

Die App darf keine Bilder aufnehmen (`images_disabled=true`). Ohne Bilder
lassen sich Brennweite, Hauptpunkt und Verzeichnung **nicht messen**, sondern
nur vom Hersteller glauben. Das ist der größte offene Posten der Kampagne, und
er lässt sich auf zwei Arten schließen:

- **(A) Bilder bleiben aus.** Dann kommen die Intrinsics aus den
  Geräteangaben (`CameraCharacteristics`), und ich kann sie höchstens auf
  Selbstwiderspruch prüfen — niemals auf Richtigkeit. Der Maßstab bleibt dort,
  wo er heute ist: bei deinem Zollstock.
- **(B) Ein lokaler Kalibrierlauf mit Target, dessen Bilder das Gerät nie
  verlassen.** Die App nimmt N Aufnahmen eines ausgedruckten
  Schachbrettmusters auf, rechnet daraus fx, fy, cx, cy und die
  Verzeichnungskoeffizienten, speichert **nur die Zahlen** und verwirft jedes
  Bild im selben Aufruf. Kein Export, kein Upload, kein Bild in einer Datei.

(B) ist die einzige Möglichkeit, Stück 5 und damit Stück 6 (Einbau und
Zeitversatz) tatsächlich zu messen. Es ist eine bewusste Änderung an einer
bestehenden Sicherheitsinvariante und deshalb **deine** Entscheidung, nicht
meine.

### 4.2 Kamera-IMU-Einbau und Zeitversatz (Stück 6)

Baumseitig vorbereitet (`GuidedRotationObservability`,
`GuidedTimeOffsetObservability`, `GuidedTimeOffsetDrift`): die mathematischen
Gatter für „war die Bewegung überhaupt beobachtbar“ existieren. Es fehlt die
Host-Aufnahme, die Gyroskop-Folge und Bildfolge **paarweise** ablegt. Setzt
4.1(B) voraus.

### 4.3 Maßstabsleiter (Stück 8) — gemessen ab `2.23-ladder`

Knopf 25 misst die Leiter, und zwar ohne ein einziges Bild zu behalten. Der
Zollstock wird bei mehreren Abständen ins Bild gehalten; die App erkennt seine
Zentimeter-Marken und bestimmt, wie viele Bildpunkte ein Meter bei diesem
Abstand einnimmt.

Der Punkt, der das ohne Intrinsics möglich macht: die **Zentimeter-Marken sind
selbst die Wahrheit**. Der Maßstab braucht keine bekannte Länge, keine
Brennweite und kein separates Zielobjekt — nur den Abstand, und den misst du
mit dem Maßband.

Bei einer Lochkamera gilt: Bildpunkte pro Meter mal Abstand ist die Brennweite
in Bildpunkten, und **dieselbe Zahl muss bei jeder Sprosse herauskommen**. Tut
sie das, gehorcht der Maßstab dem Modell und ein einziger Faktor genügt. Driftet
sie mit dem Abstand, wächst der Maßstabsfehler mit der Entfernung und kein
einzelner Faktor kann ihn korrigieren. Die App zeigt die abgeleitete Brennweite
nach jeder Sprosse an, damit eine driftende Leiter sichtbar wird, während sie
entsteht — nicht erst nach der Auswertung.

Der absolute Wert dieser Brennweite ist **keine gemessene Intrinsic**. Der
Zollstock steht selten exakt parallel, die Verzeichnung wächst zum Bildrand,
und das Maßband misst bis zu der Ebene, die du dafür hältst. Diese Einflüsse
wirken bei jedem Abstand ähnlich, deshalb ist der **Trend über die Sprossen**
verlässlich und der absolute Wert nicht.

Eine Gegenprobe verhindert den gefährlichsten Fehler dieser Messung: fände die
Erkennung nur jede zweite Zentimeter-Marke, wäre der Maßstab stillschweigend
um den Faktor zwei falsch — und die Gleichmäßigkeit der Abstände sähe genauso
gut aus. Deshalb wird die erkannte Markenzahl gegen das angegebene Zollstock-
stück geprüft und die Sprosse bei Widerspruch abgelehnt.

### 4.3b Kamera-IMU-Drehung (Stück 7b) — gemessen ab `2.24-extrinsics`

Knopf 26 bestimmt, wie die Kamera gegenüber dem Gyroskop gedreht ist. Das geht
ohne Brennweite, obwohl ein Bildschub erst durch eine Brennweite zum Winkel
wird — und der Grund ist derselbe wie beim Zeitversatz: die Unbekannte hebt
sich auf.

Nahe der optischen Achse gilt `x-punkt = -f · w_y` und `y-punkt = +f · w_x`.
Trägt man die Bildrate gegen die drei Gyroskop-Achsen auf, kommt `-f` mal eine
Zeile von `R` heraus, und bei der senkrechten Bewegung `+f` mal eine andere.
Jede Zeile einer Drehmatrix hat die Länge 1, also **ist** die Länge des
gefundenen Koeffizienten die Brennweite. Teilen liefert die Zeile. Der Maßstab
ist keine freie Unbekannte — die Orthonormalität legt ihn fest.

Daraus folgen zwei Prüfsteine, die man sonst bezahlen müsste. Die beiden
Richtungen liefern **zwei unabhängige Schätzungen derselben Brennweite**; wie
weit sie auseinanderliegen, entscheidet über die Annahme, denn sie teilen
keinerlei Arithmetik. Und dieser Wert ist eine unabhängige Gegenprobe zur
Maßstabsleiter aus 4.3, die denselben Betrag über den Zollstock ermittelt.

Was **nicht** dabei herauskommt: der Versatz zwischen Kamera und IMU als
Strecke. Er zeigt sich nur darin, wo der Bildinhalt bei *verschiedenen*
Entfernungen sitzt — das braucht eine Position, und eine Position einen
metrischen Maßstab.

Die Durchführung ist anspruchsvoller als bei Knopf 24, und zwar aus einem
Grund, der in der Regression liegt und nicht in der Szene: eine Bewegung, die
nur eine Achse anregt, lässt die Komponenten der beiden anderen schlicht
unsichtbar, ganz gleich wie sauber die Daten sind. Deshalb musst du **alle
drei Achsen bewegen** — kippen, neigen, schwenken — und die Anzeige nennt
ausdrücklich die *schwächste* Achse, nicht die stärkste. Nicht dabei gehen:
Gehen ist eine Verschiebung, und eine Verschiebung wird hier nicht
modelliert.

Die so gewonnene Brennweite ist **keine gemessene Intrinsic**, sondern die
Länge, die eine Einheitsbedingung erzwingt — mit allen Verzerrungen des
Verfahrens. Sie ist eine plausible Größenordnung und eine Kreuzprobe, sonst
nichts.

### 4.4 Thermikreihe mit echter Korrelation

`ARKCOND1` dokumentiert Thermikstufe und Helligkeit je Aufnahme. Was fehlt, ist
die Auswertung: wie sich Bias und Maßstab über die Temperatur verschieben,
gemessen als Wiederholung von Reihe 2 und 3 in zwei thermischen Zuständen
(kalt, warm gelaufen). `ARKIMUCAL1` trägt die Temperaturspanne bereits mit;
die Wiederholung fehlt.

### 4.5 Wiederhol- und Revisit-Genauigkeit

Wie nah kommt der Kern an dieselbe Stelle zurück, wenn du dieselbe Strecke
zweimal gehst. Dafür gibt es heute keinen Vertrag.

## 5. Reihenfolge der Kampagne

Pro Gerät, in dieser Reihenfolge. Jede Zeile endet mit einer Datei, die du mir
schickst.

| Schritt | Knopf | Dauer | Ergebnisdatei |
|---|---|---|---|
| 0 | 21 Bestand: was liegt schon vor? | 10 s | — |
| 1 | 23 Gerät & Laufzeit (Dauerlast 30 s) | 1 min | `ARKDEVHW1_*.txt` |
| 2 | 20 Kadenz | 5 s | `ARKIMUCAL1_*.txt` |
| 3 | 20 Statik 60 s | 1 min | " |
| 4 | 20 Sechs Lagen, Durchgang 1 | 1 min | " |
| 5 | 20 Sechs Lagen, Durchgang 2 | 1 min | " |
| 6 | 20 Drehung +90° / −90° um x, y, z | 3 min | " |
| 7 | 20 Auswerten → speichern | — | `ARKIMUCAL1_*.txt` |
| 8 | 22 Kameratakt | 1 min | `ARKCAMT1_*.txt` |
| 9 | 24 Kamera-IMU-Zeitversatz `t_d` | 1 min | `ARKCAMIMU1_*.txt` |
| 10 | 26 Kamera-IMU-Drehung `R` | 1 min | `ARKCAMEXT1_*.txt` |
| 11 | 18 Bedingungen je Szene | 10 min | `ARKCOND1_*.txt` |
| 12 | 17 Unabhängige Targets (Maßband, mindestens eines zurückgehalten) | 20 min | `ARKTARGET1_*.txt` |
| 13 | 25 Maßstabsleiter über die Entfernung | 10 min | `ARKLADDER1_*.txt` |
| 14 | 14 Metrische Validierung | 10 min | `ARKVALID1_*.txt` |
| 15 | 19 Korpus-Eintrag | 2 min | `ARKCORPUS1_*.txt` |
| 16 | 16 Redaktion, dann 15 Signatur | 3 min | `ARKREDRUN1_*.txt`, `ARKSIGN1_*.txt` |
| 17 | 21 Manifest binden | 10 s | `ARKDSMAN1_*.txt` |

**Schritt 9 steht aus einem physikalischen Grund vor Schritt 10.** Die Drehung
`R` wird bestimmt, indem das Gyroskop über das Intervall gemittelt wird, das um
jeden Bild-Zeitstempel liegt. Steckt in diesen Zeitstempeln noch ein Rest-
Versatz `t_d`, liegt dieses Fenster daneben: die Anpassung wird flacher, und
die gefundene Brennweite — die *Länge* des Koeffizientenvektors — kommt zu
klein heraus. Erst `t_d` messen, dann `R`.

**Schritt 13 steht vor Schritt 14**, weil sie unterschiedliche Fragen beantworten.
Die Leiter sagt, ob *ein* Skalierungsfaktor über die Entfernung überhaupt
reicht. Die Validierung sagt, ob der Maßstab stimmt. Fragt man die zweite vor
der ersten, steht da eine Zahl ohne Deutung: sie könnte auch von einem
Skalierungsfehler kommen, der mit der Entfernung wächst.

Schritt 1–10 sind pro Gerät einmal fällig und danach nur noch nach
Firmware-Wechsel oder Sturz. Schritt 11–17 pro Messsitzung. Schritt 0 und 17
gehören zusammen: der erste sagt, was fehlt, der letzte bindet, was da ist.

**Schritt 1 steht aus einem Grund vor Schritt 2.** Eine 60-Sekunden-Statik-
messung auf einem drosselnden Telefon misst das Drosseln, nicht das Gyroskop,
und nichts im fertigen Log unterscheidet die beiden hinterher. Fällt Schritt 1
durch, behebe die Ursache zuerst (Gerät kühlen, Hülle ab, nicht laden, andere
Apps schließen) und wiederhole ihn — die IMU- und Kamerareihen danach wären
sonst Zahlen über ein Gerät, das es während der Messung nicht mehr gab.

**Reihenfolge 16 vor 15 ist zwingend.** Die Redaktion schreibt **neue**
Dateien (`redacted_…`), und nur diese lädst du hoch. Signierst du zuerst,
deckt die Signatur Bytes ab, die niemand hochlädt – sie wäre wertlos.

### 5b. Welche Frage welcher Knopf beantwortet

Der Kampagnenplan (Knopf 7) führt zehn kanonische Fragen. Lange stand nirgends,
welche Messung welche Frage beantwortet — man konnte eine Frage als belegt
bestätigen, ohne zu wissen, was man dafür ausführen soll. Diese Zuordnung steht
jetzt im Kern (`ArKernelCampaignRoute`), wird in Knopf 7 für die jeweils
aktuelle Frage angezeigt und ist hier noch einmal lesbar.

Alle Fragen setzen dieselbe Reihenfolge voraus: **23 → 20 → 22 → 24 → 26**.

| Frage | Knöpfe |
|---|---|
| `Q_SCALE_SHORT` / `Q_SCALE_LONG` | 13 → 25 → 14 |
| `Q_ORTHOGONALITY` | 14 → 17 |
| `Q_ORIENTATION_STILLNESS` | 20 |
| `Q_THERMAL_DRIFT` | 18 → 23 → 20 → 14 |
| `Q_LIGHTING_CHANGE` | 18 → 14 |
| `Q_HELD_OUT_DIMENSION` | 17 → 14 |
| `Q_REPEATABILITY` | 13 → 14 |
| `Q_CROSS_SESSION` | 8 → 14 |
| `Q_CROSS_DEVICE` | 9 |

"Beantwortbar" heißt hier nur: die nötigen Läufe liegen vor. Es heißt nicht,
dass die Antwort richtig ist, und diese Tabelle enthält aus demselben Grund
keine Spalte für ein Urteil.

## 6. Was ich von dir brauche

Zu jedem Lauf drei Dinge:

1. **Die Textdatei** aus `Downloads/ARKernelDiagnostics` — unverändert, so wie
   die App sie geschrieben hat.
2. **Die Rohwerte, wenn du sie hast** — also was du mit dem Maßband, der
   Wasserwaage oder der Lehre tatsächlich abgelesen hast. Die App kann
   Buch führen, aber sie sieht dein Werkzeug nicht.
3. **Den Kontext**: welches Gerät, welche Firmware, welche Temperatur ungefähr,
   welches Rig, und was dir dabei aufgefallen ist.

Und wenn du willst, ein `logcat` der Sitzung. Alles davon ist freiwillig und
geht nur über dich — die App lädt nichts hoch.

## 7. Grenzen

- Keine dieser Reihen stuft die Qualitätsnote hoch. Die autoritative
  Einstufung bleibt `assessReconstructionQuality` mit
  `evaluateHeldOutDimensions`, und die steht auf `unverified`.
- Eine Trägheitskalibrierung gilt für **dieses** Gerät. Es gibt keine
  universelle Kalibrierung, und nichts davon überträgt sich auf ein anderes
  Telefon.
- Eine gute IMU-Kalibrierung ist eine **Voraussetzung**, kein Nachweis. Ein
  Gerät kann einen hervorragenden Beschleuniger haben und trotzdem einen Raum
  in der falschen Größe rekonstruieren, weil der Fehler in Kamera und
  Zeitversatz sitzt — und die sieht dieser Vertrag nicht.
