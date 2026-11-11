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
