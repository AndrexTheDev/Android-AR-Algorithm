# Physische Testkampagne: Stand und Reihenfolge

Dieses Dokument ist die Arbeitsliste dafür, den AR-Kern mit **echten,
physikalischen Messungen** fertigzubekommen. Es sagt, was die Test-App heute
messend kann, was sie nur bucht, was noch fehlt, in welcher Reihenfolge es
zusammenhängt, und was du mir jeweils schicken musst.

Stand: Version `2.19-hub` (Knopf 1–21). Bewertung über alles:
**`unverified`**. Es wurde nichts hochgestuft und nichts behauptet.

## 1. Was ein AR-Kern physikalisch braucht

Ein visuell-inertialer Kern integriert Beschleuniger und Gyroskop zwischen
zwei Kamerabildern. Seine Genauigkeit hängt an sieben Stücken, die in dieser
Reihenfolge aufeinander aufbauen — jedes weitere ist sinnlos, wenn das
darunterliegende nicht stimmt:

| # | Stück | Woher | Stand heute |
|---|---|---|---|
| 1 | Sensor-Kadenz und Zeitbasis | Telefon allein | **messbar** (`ARKIMUCAL1`) |
| 2 | Beschleuniger: Bias, Maßstab, Fehlstellung | Telefon + Tisch | **messbar** (`ARKIMUCAL1`) |
| 3 | Gyroskop: Bias, Maßstab, Nebenachsen | Telefon + rechter Winkel | **messbar** (`ARKIMUCAL1`) |
| 4 | Rauschen und Bias-Stabilität | Telefon + Tisch | **messbar** (`ARKIMUCAL1`) |
| 5 | Kamera-Intrinsics (fx, fy, cx, cy, Verzeichnung) | braucht Bilder | **blockiert** – siehe §4 |
| 6 | Kamera-IMU-Einbau und Zeitversatz (`R`, `t`, `t_d`) | braucht Bilder + geführte Bewegung | **fehlt** |
| 7 | Maßstab über die Entfernung | Zollstock + bekannte Abstände | **teilweise** (`ARKVALID1`, `ARKSCALE1`) |

Die Stücke 1–4 sind reine Trägheitssensorik. Sie brauchen kein Bild, keine
Erlaubnis und keine Kamera — deshalb sind sie jetzt messbar. Stück 5–6 brauchen
Bilder, und die App nimmt bewusst keine auf.

## 2. Bestand: was die App heute kann

**Messend** — die App führt eine physikalische Messreihe selbst durch und
rechnet daraus Zahlen:

| Knopf | Vertrag | Messreihe | Was du brauchst |
|---|---|---|---|
| 20 | `ARKIMUCAL1` | Kadenz, Statik, sechs Lagen, Drehung um bekannten Winkel | Telefon, Tisch, rechter Winkel |
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
- **(B) Ein lokaler Kalibrierlauf mitTarget, dessen Bilder das Gerät nie
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

### 4.3 Maßstabsleiter (Stück 7)

`ARKVALID1` prüft eine Abweichung gegen dein Maß. Was fehlt, ist die
**Leiter**: derselbe Maßstab bei 0,5 m, 1 m, 2 m und 5 m. Erst die Leiter zeigt,
ob der Maßstabsfehler konstant ist oder mit der Entfernung wächst — das ist die
Frage, die entscheidet, ob ein Skalierungsfaktor im Kern überhaupt reicht.

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
| 1 | 20 Kadenz | 5 s | `ARKIMUCAL1_*.txt` |
| 2 | 20 Statik 60 s | 1 min | " |
| 3 | 20 Sechs Lagen, Durchgang 1 | 1 min | " |
| 4 | 20 Sechs Lagen, Durchgang 2 | 1 min | " |
| 5 | 20 Drehung +90° / −90° um x, y, z | 3 min | " |
| 6 | 20 Auswerten → speichern | — | `ARKIMUCAL1_*.txt` |
| 7 | 18 Bedingungen je Szene | 10 min | `ARKCOND1_*.txt` |
| 8 | 17 Unabhängige Targets (Maßband, mindestens eines zurückgehalten) | 20 min | `ARKTARGET1_*.txt` |
| 9 | 14 Metrische Validierung | 10 min | `ARKVALID1_*.txt` |
| 10 | 19 Korpus-Eintrag | 2 min | `ARKCORPUS1_*.txt` |
| 11 | 16 Redaktion, dann 15 Signatur | 3 min | `ARKREDRUN1_*.txt`, `ARKSIGN1_*.txt` |
| 12 | 21 Manifest binden | 10 s | `ARKDSMAN1_*.txt` |

Schritt 1–6 sind pro Gerät einmal fällig und danach nur noch nach
Firmware-Wechsel oder Sturz. Schritt 7–12 pro Messsitzung. Schritt 0 und 12
gehören zusammen: der erste sagt, was fehlt, der letzte bindet, was da ist.

**Reihenfolge 16 vor 15 ist zwingend.** Die Redaktion schreibt **neue**
Dateien (`redacted_…`), und nur diese lädst du hoch. Signierst du zuerst,
deckt die Signatur Bytes ab, die niemand hochlädt – sie wäre wertlos.

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
