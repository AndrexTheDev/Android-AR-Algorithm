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
