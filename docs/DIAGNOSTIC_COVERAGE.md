# Abdeckungsmatrix: was der Kern behauptet, und was dafür gemessen sein muss

Dieses Dokument ist die Antwort auf „stelle das alles systematisch und logisch
sicher". Es führt jede Behauptung des AR-Kerns, jeden physikalischen Test, der
sie stützen müsste, und den Knopf, der ihn durchführt — oder den Grund, warum
es ihn nicht gibt.

Stand: Version `2.20-camtim` (Knopf 1–22). Bewertung über alles:
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
| **Zeitversatz zwischen Kamera und IMU ist bekannt** | Korrelation Bildrotation/Gyro | — | — | **fehlt** |
| Das Bild ist scharf und nicht überbelichtet | `CameraFrameQuality` (Bibliothek) | — | — | **fehlt** |

Die drei fett gedruckten Zeilen sind die eigentliche Grenze dieser App, und
sie hängen zusammen: ohne Intrinsics hat eine geführte Bewegung keinen
Maßstab, und ohne Maßstab lässt sich der Zeitversatz nicht von einer
Fehlskalierung unterscheiden.

## Schicht 3 — Gerät und Laufzeit

| Behauptung | Test | Knopf | Vertrag | Stand |
|---|---|---|---|---|
| Das Gerät liefert die Sensoren, die der Kern braucht | Sensorinventar | 1 | `ARKFDPOL1` | **gebucht** (teilweise) |
| Genug Speicher und Arbeitsspeicher für einen Lauf | Laufzeit-Reserven | — | — | **fehlt** |
| Das Gerät drosselt nicht mitten in einer 60-Sekunden-Messung | Drosselungsprobe | — | — | **fehlt** |
| Die beiden Uhren driften über einen langen Lauf nicht auseinander | `ArKernelClockAligner` (Bibliothek) | — | — | **fehlt** |

Eine 60-Sekunden-Statikmessung auf einem drosselnden Telefon misst das
Drosseln, nicht das Gyroskop. Diese Schicht ist keine Nebensache.

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

1. **Schicht 1** — Trägheitssensorik. Pro Gerät einmal, danach nur nach
   Firmware-Wechsel oder Sturz.
2. **Schicht 2** — Kameratakt. Pro Gerät einmal. **Intrinsics blockiert.**
3. **Schicht 3** — Gerät und Laufzeit. Vor jeder langen Messreihe.
4. **Schicht 4** — Geometrie und Maßstab. Pro Messsitzung.
5. **Schicht 0** — Vertrauen. Am Ende: erst redigieren, dann signieren.
6. **Schicht 5** — Systembehauptungen. Erst wenn 1–4 stehen.

## Was dieses Dokument nicht ist

Es ist eine Bestandsaufnahme, kein Urteil. Eine Zeile mit **gemessen** sagt,
dass die Reihe existiert und Zahlen liefert — nicht, dass diese Zahlen gut
sind. Die autoritative Einstufung bleibt `assessReconstructionQuality` mit
`evaluateHeldOutDimensions`, und die steht auf `unverified`.
