# 📱 Systematischer Betatest – Android-AR-Algorithm Diagnose-App

Dieser Testplan führt dich durch alle Screens der Diagnose-App. Ziel ist es,
Feedback für die AR-Kernel-Finalisierung zu sammeln. Alle erzeugten Logs und
Antworten solltest du mir zur Verfügung stellen.

## Voraussetzungen

- Android-Handy (API 26+, empfohlen: API 35 / Android 15)
- App installiert (APK über GitHub Actions CI als Release-Asset)
- Kamera, Sensoren, Speicherzugriff verfügbar
- Optional: Schachbrett-Ziel (mindestens 9×7 Ecken, 8×6 Kästchen) mit
  bekannter Kästchengröße in Millimetern

---

## Phase 1: Installation & Hauptbildschirm

| Schritt | Aktion | Erwartetes Ergebnis | OK? |
|---------|--------|---------------------|-----|
| 1.1 | APK installieren | App öffnet sich ohne Absturz | ☐ |
| 1.2 | Hauptbildschirm öffnen | Alle Buttons sichtbar (27 Assistent, 28 Kamera vermessen, 29 Fragen) | ☐ |
| 1.3 | Button 29 tippen | Fragen-Sammlung öffnet sich | ☐ |
| 1.4 | Zurück zum Hauptbildschirm | Kein Absturz, keine Fehlermeldung | ☐ |

---

## Phase 2: Fragen-Sammlung (Schritt 29) — WICHTIG für AR-Kernel

| Schritt | Aktion | Erwartetes Ergebnis | OK? |
|---------|--------|---------------------|-----|
| 2.1 | Alle 29 Fragen in 7 Kategorien sichtbar | Keine leeren oder doppelten Fragen | ☐ |
| 2.2 | Textfelder ausfüllen | Eingabe wird gespeichert | ☐ |
| 2.3 | CheckBoxes ankreuzen | Ja/Nein wird gespeichert | ☐ |
| 2.4 | "Antworten exportieren" tippen | Share-Dialog öffnet sich | ☐ |
| 2.5 | Antworten per E-Mail/Chat senden | Text kommt bei mir an | ☐ |
| 2.6 | Datei gespeichert unter `Downloads/ARKernelDiagnostics/ARKFINAL_*.txt` | Datei ist auffindbar | ☐ |

**Antworte alle Fragen so ehrlich und detailliert wie möglich.** Besonders wichtig:

- **hw_01–hw_07**: Gerätemodell, Android-Version, RAM, verfügbare Sensoren
- **cam_01–cam_06**: Kamera-Konfiguration, Camera2-API, Fokus-Fähigkeiten
- **imu_01–imu_04**: IMU-Stabilität, Latenz, Sensor-Timeouts
- **env_01–env_06**: Schachbrett, Kästchengröße (mm!), Beleuchtung, Entfernung
- **cal_01–cal_05**: Durchgeführte Kalibrierungen, beobachtete Abweichungen
- **prob_01–prob_03**: Was funktioniert nicht, wo versagt die App?

---

## Phase 3: Kamera-Einmessung (Schritt 28)

| Schritt | Aktion | Erwartetes Ergebnis | OK? |
|---------|--------|---------------------|-----|
| 3.1 | Button 28 tippen | IntrinsicsCaptureActivity öffnet sich | ☐ |
| 3.2 | Kamera starten | Vorschau läuft | ☐ |
| 3.3 | Schachbrett ins Bild halten | Ecken werden erkannt | ☐ |
| 3.4 | Vier Ecken tippen | Eckpunkte werden gesetzt | ☐ |
| 3.5 | Ansicht aufnehmen | Ansicht wird gespeichert | ☐ |
| 3.6 | Mehrere Ansichten (mind. 6) aufnehmen | View-Count erhöht sich | ☐ |
| 3.7 | Optional: Kästchengröße in mm eingeben | Wert wird übernommen | ☐ |
| 3.8 | ARKINTR1 erzeugen | Datei wird geschrieben | ☐ |
| 3.9 | ARKINTR1 teilen | Share-Dialog öffnet sich | ☐ |
| 3.10 | Screen verlassen und zurückkehren | Kamera wird neu gestartet, kein Absturz | ☐ |

---

## Phase 4: Assistent (Schritt 27)

| Schritt | Aktion | Erwartetes Ergebnis | OK? |
|---------|--------|---------------------|-----|
| 4.1 | Button 27 tippen | WizardActivity öffnet sich | ☐ |
| 4.2 | Durch alle Schritte klicken | Jeder Schritt hat einen Screen | ☐ |
| 4.3 | Kein Schritt stürzt ab | Alle Activities sind im Manifest | ☐ |

---

## Phase 5: Weitere Kamera-Screens (Knöpfe 24–26)

| Schritt | Aktion | Erwartetes Ergebnis | OK? |
|---------|--------|---------------------|-----|
| 5.1 | Kamera-Extrinsik (Schritt 24) | Öffnet sich, kein Absturz | ☐ |
| 5.2 | IMU-Kalibrierung (Schritt 25) | Öffnet sich, kein Absturz | ☐ |
| 5.3 | Kamera-Timing (Schritt 26) | Öffnet sich, kein Absturz | ☐ |
| 5.4 | Lineal-Skala (Schritt 23) | Öffnet sich, kein Absturz | ☐ |
| 5.5 | Maßstabsleiter (Schritt 22) | Öffnet sich, kein Absturz | ☐ |

---

## Phase 6: Logs erstellen (ARKFDLOG1 / ARKFDPLAN1)

| Schritt | Aktion | Erwartetes Ergebnis | OK? |
|---------|--------|---------------------|-----|
| 6.1 | Opt-in-Checkbox aktivieren | Diagnose-Modus aktiv | ☐ |
| 6.2 | Standard-ARKFDPLAN1 wiederherstellen | Plan-Text wird angezeigt | ☐ |
| 6.3 | ARKFDPLAN1 teilen | Plan-Text wird gesendet | ☐ |
| 6.4 | Physische Tests durchführen (siehe Phase 7) | Logs werden aufgezeichnet | ☐ |
| 6.5 | ARKFDLOG1 teilen | Log-Text wird gesendet | ☐ |
| 6.6 | Dateien unter `Downloads/ARKernelDiagnostics/` | ARKFDLOG1, ARKFDPLAN1, ARKFDPOL1 vorhanden | ☐ |

---

## Phase 7: Physische Tests (auf deinem Handy)

Diese Tests erfordern echte Hardware und können nicht im Repo validiert werden:

| Test | Aktion | Was du mir sendest |
|------|--------|-------------------|
| 7.1 | 10 s IMU-Stillstand | ARKFDLOG1 mit IMU-Stillstand-Event |
| 7.2 | 30 Camera2-Preview-Frames | ARKFDLOG1 mit Kamera-Preview-Event |
| 7.3 | Storage-Roundtrip (64 KiB) | ARKFDLOG1 mit Storage-Event |
| 7.4 | Thermal-Soak (20 s Zeitreihe) | ARKFDLOG1 mit Thermal-Event |
| 7.5 | Schachbrett-Messung mit Maßband | Kästchengröße in mm + beobachtete Abweichung |
| 7.6 | Mehrere Lichtverhältnisse | Feedback zu Rauschen und Erkennung |

---

## Phase 8: Was du mir sendest

Sende mir alle folgenden Dateien/Infos:

1. **ARKFINAL_*.txt** — Antworten auf die 29 Fragen (aus Schritt 2.4)
2. **ARKFDLOG1_*.txt** — Lauf-Log (aus Phase 6)
3. **ARKFDPLAN1_*.txt** — Testplan (aus Phase 6)
4. **ARKINTR1_*.txt** — Kamera-Intrinsik (falls Schritt 3 durchgeführt)
5. **Gerät & Android-Version** (wenn nicht in den Logs enthalten)
6. **Kurzes Feedback**: Was hat funktioniert, was nicht, wo ist die App abgestürzt?

---

## Checkliste für den vollständigen Betatest

- [ ] App installiert und gestartet
- [ ] Alle 29 Fragen beantwortet und exportiert
- [ ] Kamera-Einmindung (Schritt 28) getestet
- [ ] Assistent (Schritt 27) durchlaufen
- [ ] Kamera-Screens (24–26) ohne Absturz getestet
- [ ] ARKFDLOG1 erstellt und geteilt
- [ ] ARKFDPLAN1 erstellt und geteilt
- [ ] Physische Tests durchgeführt (falls Schachbrett verfügbar)
- [ ] Alle Dateien an mich gesendet

---

## Bekannte Einschränkungen

- Die App wurde **nicht auf einem echten Gerät getestet** (0% Geräteverifikation)
- Kein Android SDK in dieser Entwicklungsumgebung → APK kann nur über GitHub Actions CI gebaut werden
- Zentimetervalidierung erfordert physische Bodentruth-Messungen
- Alle Qualitätsstufen bleiben `unverified` bis physische Evidenz vorliegt

---

Vielen Dank für deinen Beitrag zur AR-Kernel-Finalisierung! Deine Antworten helfen dabei, den Kernel für dein spezifisches Gerät zu optimieren.
