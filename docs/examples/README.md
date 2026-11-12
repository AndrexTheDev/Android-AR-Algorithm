# Beispielpläne für die Diagnose-App

## `ARKFDPLAN1_redmi_light_tuned.txt`

Kopie des Standardplans `arkernel.ultimate.v2` mit genau einer Änderung:

```text
step=SENSOR_LIVE|5000|2000|50|light
```

wird zu

```text
step=SENSOR_LIVE|5000|2000|2|light
```

Ein zweiter Redmi-Lauf mit `2.1-highrate` lieferte beim Lichtsensor 21 Samples
in 5 s bei etwa 300 ms Abstand. Die Strukturinvarianten waren sauber
(`mono.0`, `finite.0`, `gapms.300`), aber die host-gewählte Plan-Schwelle
`expectedMin=50` ließ den Schritt fail-closed auf `pass.0` laufen. Die Schwelle
ist eine Texterwartung, keine Geräteanforderung und keine Qualitätsaussage.
Mit `expectedMin=2` bleiben monotone Zeitstempel, Endlichkeit, Lücke,
Mittelwert und Standardabweichung prüfbar, ohne eine neue APK zu installieren.

Der kanonische Text ist 1069 Bytes lang, hat CRC-32 `0600c70b` und bleibt
`quality_grade=unverified`. Nach dem Einfügen in das Planfeld der App schreibt
diese den Plan kanonisch neu; ein künftiger `ARKFDLOG1` trägt dann diese neue
`PLAN_CRC` statt `c50de25f`. Ein dritter hochgeladener Redmi-Lauf hat genau
diese Datei byte-identisch verwendet und damit `pass.20.of.20.unverified`
erreicht. Es werden keine Bilder, Posen, Trajektorien, Kalibrierarrays oder
physikalischen Bodentruth-Daten erzeugt.

`ARKSURVEYTSV1_example.tsv` zeigt drei synthetische, kanonische TSV-Zeilen für `ArKernelSurveyMeasurementTsv`. Sie sind ein reines Formatbeispiel ohne reale Vermessung, ohne unabhängige Targets, ohne Evaluatorlauf und ohne Qualitätsaussage; der Status bleibt `unverified`.

`ARKSURVEYBIN1_example.bin` zeigt drei synthetische, kanonische Binärrecords für `ArKernelSurveyMeasurementBinary`: 628 Bytes mit 24-Byte-Header, drei 200-Byte-Records und CRC-32-Trailer (`0f59e50e`). Es ist ein reines Formatbeispiel ohne reale Vermessung, ohne unabhängige Targets, ohne Evaluatorlauf, ohne I/O und ohne Qualitätsaussage; der Status bleibt `unverified`.

`ARKREDACT1_example.txt` zeigt zwei synthetische, kanonische Redaktionsregeln für `ArKernelFieldDiagnosticsRedactionBundle`. Sie sind ein reines Formatbeispiel ohne echte Erfassung, Redaktion, Upload, Signaturprüfung oder Qualitätsaussage; der Status bleibt `unverified`.

`ARKLOOP1_example.txt` zeigt zwei synthetische, kanonische Loop-Closure-Kandidaten für `ArKernelLoopClosureCandidateBundle`. Sie sind ein reines Formatbeispiel ohne Deskriptoren, Posen, Trajektorien, geometrische Verifikation, Optimierung oder Qualitätsaussage; der Status bleibt `unverified`.

`ARKLOOPGC1_example.txt` zeigt eine synthetische, kanonische `ARKLOOPGC1`-Geometrie-Evidenz (562 Bytes inklusive abschließendem Zeilenumbruch): drei 3D–3D-Korrespondenzen für den `ARKLOOP1`-Kandidaten `loop-01`, eine Einheitsquaternion, eine Translation und `prior_disagreement_m`. `ArKernelLoopClosureGeometricGate` prüft ausschließlich deterministische Struktur-, Residual-, RMSE-, Inlier-, Prior- und Spread-Gates; es gibt keine Deskriptoren, keine RANSAC-Schätzung im Adapter, keinen verifizierten Loop, keine Optimierung, keine Map-Mutation, keinen physischen Bodentruth, kein I/O und keine Qualitätsaussage; der Status bleibt `unverified`.

`ARKVIEW1_example.txt` zeigt einen synthetischen, kanonischen `ARKVIEW1`-Viewer-Inspektionsplan (463 Bytes inklusive abschließendem Zeilenumbruch): zwei Ansichten mit opaken `arkglb://`-Referenzen, exakten View-/Color-Modi, begrenzten Orbit-Kamera-/Frame-Werten und vier deaktivierten Safety-Flags. `ArKernelAssetViewerPlanBundle` prüft ausschließlich deterministische Struktur-, Budget- und Fail-closed-Regeln; es gibt kein Asset-Laden, kein Filament-Modul, kein Rendering, kein USDZ-/Fremdformat-Import, kein I/O, keinen Upload, keine Qualitätsinferenz und keine physische Validierung; der Status bleibt `unverified`.

`ARKFAULT1_example.txt` zeigt einen synthetischen, kanonischen `ARKFAULT1`-Katalog (498 Bytes inklusive abschließendem Zeilenumbruch): zwei von 45 möglichen Zwei-Fehler-Paaren aus `SyntheticCompatibilityFault` mit kanonischen Szenario-IDs, budgetierten Sample-Zahlen, Seed und Dry-Run-Marker sowie vier deaktivierten Safety-Invarianten. `ArKernelSyntheticFaultCatalogBundle` prüft ausschließlich deterministische Struktur-, Reihenfolge-, Budget- und Fail-closed-Regeln; es gibt kein Replay, keine Framework-/Binder-/HAL-Injektion, kein Coverage-Guided Fuzzing, keine Device-Farm-Korpora, kein I/O, keine physische Validierung und keine Qualitätsaussage; der Status bleibt `unverified`.

`ARKGOLDEN1_example.txt` zeigt einen synthetischen, kanonischen `ARKGOLDEN1`-Golden-Suite-Plan (739 Bytes inklusive abschließendem Zeilenumbruch): zwei Cases mit opaken Frame-Digests, einem `SEMANTIC_DETECTION`-Startup-Sentinel und einem `METRIC_DEPTH`-Golden-Case, sechs kanonischen Dezimaltoleranzen sowie fünf deaktivierte Safety-Invarianten. `ArKernelGoldenSuitePlanBundle` prüft ausschließlich deterministische Struktur-, Reihenfolge-, Budget- und Fail-closed-Regeln und meldet nur strukturelle Zahlen; es gibt kein Modell-Laden, keine Manifest-/Signaturprüfung, keine Delegate-/Factory-Konstruktion, keine Inferenz, keinen Startup-Sentinel-Lauf, keinen CPU-Fallback, kein Evidence-Cache-Schreiben, keinen Upload, keine Device-Farm-Korpora, kein I/O und keine Qualitätsaussage; der Status bleibt `unverified`.

`ARKCOV1_example.txt` zeigt einen synthetischen, kanonischen `ARKCOV1`-Set-Cover-Plan (514 Bytes inklusive abschließendem Zeilenumbruch): zwei Auswahlzeilen, eine Single- und eine Pairwise-Requirement-Zeile, konsistente Abdeckungs-/Gewichtssummen sowie fünf deaktivierte Safety-Invarianten. `ArKernelCoveragePlanBundle` prüft ausschließlich deterministische Struktur-, Reihenfolge-, Budget- und Konsistenzregeln und kann ein bestehendes `CapabilityCoveragePlan` verlustfrei serialisieren und zurückbauen; es gibt keine Provider-Anbindung, kein Mobly-Rig, keine thermischen oder Tag/Nacht/Weather-Läufe, keine physische Szenenerfassung, keinen Upload, kein I/O, keinen Gerätebeweis und keine Qualitätsaussage; der Status bleibt `unverified`.

`ARKFDPACK1_example.txt` zeigt ein synthetisches, kanonisches `ARKFDPACK1`-Paketmanifest (488 Bytes inklusive abschließendem Zeilenumbruch): zwei opake Diagnosebericht-Referenzen mit SHA-256-Labels, eine `drop`- und eine `hash`-Regel, eine unabhängige Target-Referenz sowie fünf deaktivierte Safety-Invarianten. `ArKernelFieldDiagnosticsPackageAdapter` prüft ausschließlich deterministische Struktur-, Reihenfolge-, Budget- und Fail-closed-Regeln; es gibt keine Bilder, Posen, Trajektorien, Pixel oder dichte Geometrie, keine Erfassung, keine Redaktion, keine Digest-/Signaturprüfung, keine Target-Messung, kein I/O, keinen Upload, keinen Gerätebeweis und keine Qualitätsaussage; der Status bleibt `unverified`.

`ARKCALIB1_example.txt` zeigt ein synthetisches, kanonisches `ARKCALIB1`-Beispiel (551 Bytes inklusive abschließendem Zeilenumbruch): drei held-out Maßstabsbeobachtungen mit `scale_bar`-, `survey`- und `sensor_ground_truth`-Herkunft, zwei reine Orientierungsproben ohne metrische Position und sechs deaktivierte Safety-Invarianten. Die Zahlen sind **Beispielwerte, keine echte Messung**. `ArKernelCalibrationEvidenceAdapter` prüft ausschließlich deterministische Struktur-, Budget- und Fail-closed-Regeln; die Vorprüfung `evaluationReady` ist keine Qualitätsnote. Es gibt keine Bilder, Pixel, Trajektorien, dichte Geometrie, keine automatische oder universelle Kalibrierung, keine Signaturprüfung, kein I/O, keinen Upload, keinen Gerätebeweis und keine Qualitätsaufstufung; der Status bleibt `unverified`.

`ARKADAPT1_example.txt` zeigt ein synthetisches, kanonisches `ARKADAPT1`-Profil (961 Bytes inklusive abschließendem Zeilenumbruch): ein Gerät der Klasse `full` mit `untrusted` Intrinsics, dreizehn systematische Schritte (Gerätekatalog, Intrinsics, IMU, Rotationssensor, Thermik, fünf Maßstäbe von 0,25 m bis 4 m, Survey-Target, Kontrolllänge, Held-out-Review), alle noch `pending`, und fünf deaktivierte Safety-Invarianten. `selectProfile()` wählt die Schrittfolge deterministisch aus Gerätefakten; `summarize()` meldet nur Strukturzahlen. Die Vorprüfung `profileComplete` ist keine Qualitätsnote. Es gibt keine automatische oder universelle Kalibrierung, keine Bilder, Posen, Trajektorien, keine Signaturprüfung, kein I/O, keinen Upload, keinen Gerätebeweis und keine Qualitätsaufstufung; der Status bleibt `unverified`.

`ARKCAMPAIGN1_example.txt` zeigt ein synthetisches, kanonisches `ARKCAMPAIGN1`-Beispiel (1395 Bytes inklusive abschließendem Zeilenumbruch): ein Gerät der Klasse `full` mit neun geplanten Szenen (Maßstab drinnen/draußen, rechte Winkel, Stillstand, Thermik, Tag/Nacht, Held-out, Sitzungen, Gerätevergleich) und zehn kanonischen Fragen mit festen Wiederholungstiefen, alle noch `pending`. Die acht Safety-Invarianten sind verbindlich aktiv, `campaignEvidenceComplete` ist nur eine Vollständigkeits-Vorprüfung. Es gibt keine automatische oder universelle Kalibrierung, keine Bilder, Posen, Trajektorien, Feldmessungen, Signaturprüfung, kein I/O, keinen Upload, keine Antworten und keine Qualitätsaufstufung; der Status bleibt `unverified`.

`ARKBIND1_example.txt` zeigt ein synthetisches, kanonisches `ARKBIND1`-Beispiel (625 Bytes inklusive abschließendem Zeilenumbruch): ein Geräte-Token, eine Kampagnen-ID, drei kleingeschriebene SHA-256-Digests über die kanonisch re-kodierten `ARKADAPT1`-, `ARKCALIB1`- und `ARKCAMPAIGN1`-Quelltexte, die Zähler 9 Szenen / 10 Fragen / 3 Beobachtungen / 2 Posen und sechs verbindlich aktivierte Safety-Invarianten. Die Digests sind gegenüber BOM/CRLF-Darstellung derselben Quellen stabil. `bindingConsistent` ist nur Strukturkonsistenz, keine Antwort und keine Qualitätsnote. Es gibt keine Signaturverifikation, keinen automatischen Upload, keine Feldmessung, kein I/O im Kern und keine Qualitätsaufstufung; der Status bleibt `unverified`.

`ARKCROSS1_example.txt` zeigt ein synthetisches, kanonisches `ARKCROSS1`-Beispiel (615 Bytes inklusive abschließendem Zeilenumbruch): zwei deklarierte `ARKBIND1`-Sitzungen mit unterschiedlichen Geräte-Tokens, Kampagnen-IDs und SHA-256-Bindungsdigests, beide mit 9 Szenen / 10 kanonischen Fragen / 3 Beobachtungen / 2 Posen, sowie acht verbindlich aktivierte Safety-Invarianten. `comparisonConsistent` ist nur Strukturkonsistenz, `sourceReverificationPerformed` bleibt `false`; es gibt keine erneute Quelltextprüfung, keine Signaturverifikation, keinen automatischen Upload, keine Feldmessung und keine Qualitätsaufstufung; der Status bleibt `unverified`.

`ARKRETENT1_example.txt` zeigt ein synthetisches, kanonisches `ARKRETENT1`-Beispiel (330 Bytes inklusive abschließendem Zeilenumbruch): einen geöffneten lokalisierten Dialog mit `de-DE`, Modus `keep`, verfügbarer aber nicht gewählter Schlüssellöschung, noch keiner Aktion und drei verbindlich aktivierten Safety-Invarianten. Es gibt keine echte Löschung, keine Signaturprüfung, keinen Upload, keine physische Gerätevalidierung und keine Qualitätsaufstufung; der Status bleibt `unverified`.

`ARKFDREVIEW1_example.txt` zeigt ein synthetisches, kanonisches `ARKFDREVIEW1`-Beispiel (849 Bytes inklusive abschließendem Zeilenumbruch): drei kanonische SHA-256-Digests, eine nachgerechnete Plan-CRC-32 `323010a2`, `plan_binding=crc_bound`, zwei Plan-Schritte und acht Ereignisse. Es ist ein reines Formatbeispiel ohne echte Erfassung, ohne Signaturprüfung, ohne Redaktion, ohne Upload und ohne physische Validierung; `quality_grade=unverified`.
