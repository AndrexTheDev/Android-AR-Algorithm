# Abdeckungsmatrix: was der Kern behauptet, und was dafür gemessen sein muss

Dieses Dokument ist die Antwort auf „stelle das alles systematisch und logisch
sicher". Es führt jede Behauptung des AR-Kerns, jeden physikalischen Test, der
sie stützen müsste, und den Knopf, der ihn durchführt — oder den Grund, warum
es ihn nicht gibt.

Stand: Version `2.22-camimu` (Knopf 1–24). Bewertung über alles:
**`unverified`**. Es wurde nichts hochgestuft und nichts behauptet.

## Wie die Matrix zu lesen ist

**Stand** hat vier Werte, und der Unterschied zwischen ihnen ist der ganze
Punkt dieses Dokuments:

| Wert | Bedeutung |
|---|---|
| **gemessen** | Die App führt eine physikalische Messreihe selbst durch und rechnet Zahlen daraus. |
| **gebucht** | Die App nimmt Zahlen auf, die **du** mit einem echten Werkzeug gemessen hast, und prüft sie auf Vollständigkeit und Widersprüche. Sie sieht dein Werkzeug nicht. |
| **fehlt** | Es gibt keinen Test. Die Behauptung dahinter ist ungestützt. |
| **blockiert** | Der Test wäre machbar, braucht aber eine Entscheidung oder eine Voraussetzung, die noch nicht erfüllt ist. |

Ein Test, der **gebucht** ist, ist nicht weniger wert als einer, der
**gemessen** ist — er ist *anders* wahr. Ein Maßband lügt nicht dadurch, dass
die App es nicht selbst hält.

## Schicht 0 — Vertrauen und Transport

Erzeugt keine Messwerte. Stellt sicher, dass das, was du mir schickst,
unverändert ist und nichts Persönliches enthält.

| Behauptung | Test | Knopf | Vertrag | Stand |
|---|---|---|---|---|
| Dieses Artefakt ist unverändert | Signatur und Prüfung | 15 | `ARKSIGN1` | **gemessen** |
| Dieses Artefakt enthält nichts Persönliches | Redaktion und Freigabe | 16 | `ARKREDRUN1` | **gemessen** |
| Diese drei Dateien gehören zu einer Sitzung | Sitzungsbindung | 8 | `ARKBIND1` | **gemessen** |
| Temporäre Scandaten werden freigegeben | Aufbewahrungs-Entscheidung | 10 | `ARKRETENT1` | **gemessen** |
| Diese Dateimenge ist vollständig | Bestand und Prüfsummen | 21 | `ARKDSMAN1` | **gemessen** |

**Reihenfolge ist zwingend: 16 vor 15.** Die Redaktion schreibt neue Dateien,
und nur diese lädst du hoch. Umgekehrt deckte die Signatur Bytes ab, die
niemand hochlädt.

## Schicht 1 — Trägheitssensorik

Reine Physik am Telefon. Braucht kein Bild, keine Erlaubnis, kein Werkzeug
außer einem Tisch und einem rechten Winkel.

| Behauptung | Test | Knopf | Vertrag | Stand |
|---|---|---|---|---|
| Die Abtastrate stimmt, und ich weiß, auf welcher Uhr sie liegt | Kadenzreihe | 20 | `ARKIMUCAL1` | **gemessen** |
| Der Beschleuniger hat keinen Bias | Sechs Lagen | 20 | `ARKIMUCAL1` | **gemessen** |
| Der Beschleuniger misst im richtigen Maßstab | Sechs Lagen | 20 | `ARKIMUCAL1` | **gemessen** |
| Die Achsen stehen rechtwinklig zueinander | Sechs Lagen | 20 | `ARKIMUCAL1` | **gemessen** |
| Die Lagen sind wiederholbar | Sechs Lagen, **zweiter Durchgang** | 20 | `ARKIMUCAL1` | **gemessen** |
| Das Gyroskop hat keinen Bias | Statikreihe | 20 | `ARKIMUCAL1` | **gemessen** |
| Das Gyroskop misst im richtigen Maßstab | Drehung gegen Lehre | 20 | `ARKIMUCAL1` | **gemessen** |
| Die Nebenachsen koppeln nicht | Drehung gegen Lehre | 20 | `ARKIMUCAL1` | **gemessen** |
| Rauschen und Bias-Stabilität sind bekannt | Statikreihe | 20 | `ARKIMUCAL1` | **gemessen** |
| Die Temperaturspanne ist abgedeckt | Thermikreihe | 18 | `ARKCOND1` | **gebucht** (Stellvertreter) |

Der letzte Punkt ist der schwächste der Schicht: die App liest die
**Batterietemperatur**, nicht die des Sensorchips. Das ist ein Stellvertreter
für die Gerätewärme und bewegt sich langsam.

## Schicht 2 — Kamera

Hier war bis `2.20-camtim` nichts messbar, obwohl die Bibliothek die Stücke
bereits enthielt — sie hatten nur keinen Bildschirm.

| Behauptung | Test | Knopf | Vertrag | Stand |
|---|---|---|---|---|
| Diese Kamera existiert, und ich weiß ihre Fähigkeitsstufe | Kamerainventar | 22 | `ARKCAMT1` | **gemessen** |
| Der logische Kanal bleibt bei einem physischen Sensor | Physische IDs | 22 | `ARKCAMT1` | **gemessen** |
| Die Bildrate stimmt, ohne Löcher | Taktreihe | 22 | `ARKCAMT1` | **gemessen** |
| Ich weiß, auf welcher Uhr die Bildzeitstempel liegen | Zeitbasis-Entscheidung | 22 | `ARKCAMT1` | **gemessen** |
| Die Belichtung ist kurz genug für Bewegung | Belichtungsreihe | 22 | `ARKCAMT1` | **gemessen** |
| Zuschnitt und Stabilisierungs-Modus halten während des Laufs | Erfassungs-Stabilität | 22 | `ARKCAMT1` | **gemessen**, teils nicht prüfbar |
| Der Rolling-Shutter-Versatz ist bekannt | `SENSOR_ROLLING_SHUTTER_SKEW` | 22 | `ARKCAMT1` | **gemessen**, wenn gemeldet (ab Android 13) |
| **Brennweite, Hauptpunkt, Verzeichnung stimmen** | Schachbrett-Aufnahmen | — | — | **blockiert** |
| **Einbau zwischen Kamera und IMU ist bekannt** | geführte Bewegung mit Bildern | — | — | **fehlt** |
| **Zeitversatz zwischen Kamera und IMU ist bekannt** | Korrelation Bildschub/Gyro | 24 | `ARKCAMIMU1` | **gemessen** |
| Das Bild ist scharf und nicht überbelichtet | `CameraFrameQuality` (Bibliothek) | — | — | **fehlt** |

Die beiden fett gedruckten Zeilen sind die eigentliche Grenze dieser App —
aber sie hängen **nicht** so zusammen, wie hier früher stand.

Früher stand an dieser Stelle, ohne Intrinsics habe eine geführte Bewegung
keinen Maßstab, und ohne Maßstab lasse sich der Zeitversatz nicht von einer
Fehlskalierung unterscheiden. **Das ist für den Zeitversatz falsch.** Der
Versatz wird nicht aus der Größe der Bewegung bestimmt, sondern aus deren
*Zeitpunkt*: zwei Kurven werden gegeneinander verschoben, bis ihre Form
übereinander liegt. Ein falscher Maßstab staucht beide Kurven gleich und
hebt sich beim Verschieben heraus. Deshalb ist `t_d` ohne Intrinsics
messbar, und Knopf 24 misst ihn.

Für den **Einbau** `R`/`t` bleibt die Kopplung bestehen: eine Rotation und
eine Translation zwischen Kamera und IMU sind ohne Brennweite tatsächlich
nicht von einer Fehlskalierung zu trennen. Diese Zeile bleibt **fehlt**, bis
Intrinsics vorliegen.

## Schicht 3 — Gerät und Laufzeit

Bis `2.21-devhw` war diese ganze Schicht leer. Das ist die gefährlichste
Lücke der Matrix gewesen, weil sie unsichtbar ist: sie verfälscht nicht das
Ergebnis einer Reihe, sie verfälscht *jede* Reihe, die auf diesem Gerät
läuft.

| Behauptung | Test | Knopf | Vertrag | Stand |
|---|---|---|---|---|
| Das Gerät liefert die Sensoren, die der Kern braucht | Sensorinventar | 23 | `ARKDEVHW1` | **gemessen** |
| Die gemeldeten Sensorwerte sind plausibel | Bereich, Auflösung, Verzug, Leistung je Sensor | 23 | `ARKDEVHW1` | **gemessen** (gemeldet, nicht gemessen) |
| Genug Arbeitsspeicher für einen Lauf | Speicherreserve | 23 | `ARKDEVHW1` | **gemessen** |
| Genug Platz für einen langen Lauf | Massenspeicher | 23 | `ARKDEVHW1` | **gemessen** |
| Das Gerät drosselt nicht mitten in einer langen Messung | Drosselungsprobe (10/30/60 s Dauerlast) | 23 | `ARKDEVHW1` | **gemessen** |
| Das Gerät wird thermisch nicht kritisch | Thermikstatus vor und nach der Last | 23 | `ARKDEVHW1` | **gemessen**, wenn gemeldet (ab Android 10) |
| Die beiden Uhren driften über einen Lauf nicht auseinander | Uhrenreihe über dieselbe Last | 23 | `ARKDEVHW1` | **gemessen** |
| Beide Uhren laufen monoton | Rückschritte zählen | 23 | `ARKDEVHW1` | **gemessen** |
| Der Akku ist nicht fast leer | Ladestand | 23 | `ARKDEVHW1` | **gemessen** |

Eine 60-Sekunden-Statikmessung auf einem drosselnden Telefon misst das
Drosseln, nicht das Gyroskop. Diese Schicht ist keine Nebensache.

**Was die Probe wirklich tut.** Sie rechnet zehn bis sechzig Sekunden
ununterbrochen und vergleicht die Arbeitsrate im ersten Viertel des Laufs mit
der im letzten. Ein Gerät, das im letzten Viertel einbricht, wird für jede
lange Erfassung auf ihm unbrauchbar — und nichts in einem Trägheitslog
unterscheidet hinterher zwischen einem schlechten Gyroskop und einem
gedrosselten Takt. Deshalb gehört Knopf 23 **vor** Knopf 20 und 22.

**Was die Probe nicht tut.** Sie misst nicht, wie schnell das Gerät ist. Die
Raten sind untereinander vergleichbar, aber sie sind keine Leistungszahl und
kein Vergleich zwischen zwei Geräten: verschiedene Kerne, verschiedene
Lastlage, verschiedener Akkustand. Was zählt, ist das Verhältnis, nicht der
Wert.

**Warum die Uhren in derselben Probe stecken.** `elapsedRealtime` und
`uptimeMillis` unterscheiden sich um alles, was das Gerät verschlafen hat —
das ist erwartbar und harmlos, solange der Abstand *konstant* bleibt. Wächst
er während eines Laufs, hat das Gerät mitten in der Erfassung geschlafen.
Beide Uhren werden deshalb in jedem Arbeitsschritt der Probe gelesen; die
Drift ist die Änderung des Abstands in ppm, und jeder Rückschritt wird
gezählt. Die Fusion nimmt Monotonie an und verdirbt still, wenn sie nicht
gilt.

Übrigens **blockiert**: die Batterietemperatur bleibt ein Stellvertreter für
die Sensortemperatur (Schicht 1). Die App liest, was Android meldet; einen
Temperaturfühler am IMU-Chip hat kein Telefon, das ich ansprechen könnte.

## Schicht 4 — Geometrie und Maßstab

Hier bist **du** das Messgerät. Die App führt Buch und prüft auf
Widersprüche; sie sieht dein Werkzeug nicht.

| Behauptung | Test | Knopf | Vertrag | Stand |
|---|---|---|---|---|
| Ein Target hat das Maß, das es haben soll | Maßbeobachtungen | 5 | `ARKCALIB1` | **gebucht** |
| Ein Zollstock wird im Bild erkannt | Markenerkennung | 13 | `ARKSCALE1` | **gemessen** |
| Die Abweichung gegen dein Maß ist bekannt | metrische Validierung in mm | 14 | `ARKVALID1` | **gebucht** |
| Mindestens ein Target war zurückgehalten | unabhängige Targets | 17 | `ARKTARGET1` | **gebucht** |
| Die Messmarken im Live-Bild sind gesetzt | Kamerafeed | 12 | `ARKFEED1` | **gemessen** |
| Der Maßstab gilt auch über die Entfernung | Maßstabsleiter | 14 | `ARKVALID1` | **fehlt** |
| Tag und Nacht wurden wirklich gemessen | Bedingungen | 18 | `ARKCOND1` | **gemessen** (Helligkeit, Thermik) |

Der vorletzte Punkt ist der wichtigste offene: `ARKVALID1` prüft den Maßstab
in einer Szene. Ein Kern, der in einem Meter stimmt und in fünf Metern nicht,
hat kein Maßstabsproblem, sondern ein Skalierungsproblem — und dafür gibt es
noch keine Reihe.

## Schicht 5 — Systembehauptungen des Kerns

Das sind Behauptungen über das fertige System, nicht über ein Gerät. Die App
kann sie nicht prüfen, weil sie ein fertiges System voraussetzen.

| Behauptung | Stand |
|---|---|
| Visuelle Relokalisierung findet einen alten Weltframe wieder | **fehlt** — `ARKLOOP1` führt Kandidaten Buch, prüft aber keine echte Wiedererkennung |
| Loop Closure schließt eine Schleife richtig | **fehlt** — Vertrag vorhanden, keine physische Validierung |
| Metrischer 3D–3D-RANSAC liefert die richtige Transformation | **fehlt** — implementiert, gegen echte Daten nicht validiert |
| Dichte Rekonstruktion ist maßhaltig | **fehlt** — mit dieser App nicht messbar |
| Semantik und Detektion erkennen, was sie sollen | **fehlt** — braucht Modelle, nicht in diesem Rahmen |
| Ein Korpus trägt einen Gerätevergleich | **gebucht** — `ARKCORPUS1` prüft Abdeckung und Dominanz, nicht Vergleichbarkeit |
| Zentimetervalidierung | **fehlt** — hängt an unabhängigen Targets und echter Messung |

## Reihenfolge

Die Schichten bauen aufeinander auf. Eine Messung in Schicht 4 ist sinnlos,
wenn Schicht 1 nicht stimmt, weil der Kern dann zwischen zwei Kamerabildern
falsch integriert, bevor er irgendeinen Maßstab prüft.

1. **Schicht 1** — Trägheitssensorik (Knopf 20). Pro Gerät einmal, danach
   nur nach Firmware-Wechsel oder Sturz.
2. **Schicht 2** — Kameratakt (Knopf 22) und Kamera-IMU-Versatz
   (Knopf 24). Pro Gerät einmal. **Intrinsics blockiert**, und damit auch
   der Einbau `R`/`t`. Der Zeitversatz `t_d` braucht keine Intrinsics und
   kann sofort gemessen werden.
3. **Schicht 3** — Gerät und Laufzeit (Knopf 23). Pro Gerät **vor**
   Schicht 1 und 2, und erneut nach jedem Firmware-Wechsel: erst wenn das
   Gerät nicht drosselt und die Uhren halten, bedeuten die IMU- und
   Kameramessungen überhaupt etwas.
4. **Schicht 4** — Geometrie und Maßstab. Pro Messsitzung.
5. **Schicht 0** — Vertrauen. Am Ende: erst redigieren, dann signieren.
6. **Schicht 5** — Systembehauptungen. Erst wenn 1–4 stehen.

## Was dieses Dokument nicht ist

Es ist eine Bestandsaufnahme, kein Urteil. Eine Zeile mit **gemessen** sagt,
dass die Reihe existiert und Zahlen liefert — nicht, dass diese Zahlen gut
sind. Die autoritative Einstufung bleibt `assessReconstructionQuality` mit
`evaluateHeldOutDimensions`, und die steht auf `unverified`.
