# Systematische Roadmap

## Phase 0 – Fundament (jetzt)

- [x] Lizenz- und Kandidatenanalyse
- [x] portable C++17-Modulgrenzen
- [x] IMU-Vorintegration, Qualitätsgate, Sensor-Orchestrator
- [x] Unit-Tests ohne Drittanbieterabhängigkeiten
- [x] Linux Native-CI mit Unit-, Sanitizer- und deterministischem Replay sowie Android-35/NDK-27-Compile-/JVM-Test-/Lint-CI
- [x] API-35-Emulator-Instrumentation mit gepinntem Open-Source-Runner für echte AAR/JNI-Paket-, Lade- und Lifecycle-Smoke-Tests; physische Sensor-/Thermal-/Genauigkeitstests bleiben separat
- [x] stabile C-ABI und Kotlin/JNI-Wrapper
- [x] verzerrtes Kameramodell und numerisch geprüfte Projektion
- [x] robuste Sensor-Uhrenausrichtung
- [x] dependency-free FAST-9/Grid-Feature-Detektor als portabler Fallback
- [x] pyramidenbasiertes Sparse Optical Flow mit Forward/Backward-Gate
- [x] IMU-gestützte Rotationsvorhersage für Optical-Flow-Initialwerte
- [x] kalibrierter Epipolar-RANSAC und Sampson-Inlier-Gate
- [x] Essential-Rangprojektion, Vier-Pose-Zerlegung, Cheirality und Parallaxen-Gate
- [x] Homographie-RANSAC und Homographie-/Epipolar-Modellentscheidung
- [x] zustandsbehaftetes Visual-Frontend, ID-Erhalt, Replenishment und Keyframe-Gate
- [x] stationäre VIO-Initialisierung, Gravity/Bias-Seed und Strapdown-Propagation
- [x] IMU-Weißrauschmodell, diagonale Preintegrationsvarianz und kovarianzabhängige Faktorabschwächung
- [x] konservative visuelle Rotation sowie inertial beobachtbare Intervallskalierung
- [x] begrenztes Fixed-Lag-Keyframefenster mit IMU-/Vision-Faktoren und Huber-Verlust

## Phase 1 – messbares VIO-MVP

- [x] Camera2-ImageReader mit direktem Y-Plane-JNI-Pfad und expliziter Kalibrierung; ein optionaler NDK-AImageReader-Pfad bleibt offen;
- [x] gemeinsame Android-Session mit capture-synchroner IMU-Drainage, vollständiger rotatorisch-translatorischer Kamera-IMU-Extrinsik und visueller VIO-Rückkopplung;
- [x] ARCore-unabhängige Standalone-Runtime samt Accelerometer-/Gyro-/Camera2-YUV-Probe und optionalem External-Runtime-Selector mit garantiertem Fallback; breite Geräteabdeckung benötigt weiterhin versionierte Kalibrierprofile;
- [x] hysteretisches Mapping-Gate für Pose-, Frontend-, Track- und IMU-Queue-Qualität mit öffentlichen Pausengründen;
- Kalibrierdatei und Online-Validierung; Verzerrungsmodell und Timestamp-Offset-Schätzer sind implementiert;
- Vision-Frontend inklusive Feature-Lebenszyklus sowie orientierter BRIEF-Deskriptoraggregation ist nativ implementiert; ein optionales ORB-Backend und vollständiges nichtlineares Sliding Window bleiben offen;
- Sparse Sliding-Window-Gauss-Newton und Schur-Marginalisierung; Bias-Jakobians, First-Order-Faktorkorrektur und ein robuster Fixed-Lag-Bootstrap sind implementiert;
- [x] versioniertes synthetisches Kamera-/IMU-Replay, EuRoC-Konverter sowie SE(3)/Sim(3)-ATE/RPE;
- [x] reproduzierbarer EuRoC-Baseline-Runner mit `T_BS`, Replay, Trajektorienexport und SE(3)/Sim(3)-Reports;
- [ ] reale MH_01/TUM-VI/KITTI-Messwerte, automatische Ergebnisbudgets und Regressionstrends;
- Fehlerfälle: Cover, Blur, Rotation, Sensorlücke, App pause/resume.

**Exit:** 30 FPS Midrange, keine unbounded allocations, reproduzierbare Trajektorien und dokumentierte Genauigkeit.

## Phase 2 – Karten und Relokalisierung

- [x] metrischer Sparse-Landmark-Store mit Parallaxen-/Residual-Gates und hartem Budget;
- [x] lokaler dependency-freier 256-Bit-BRIEF-/Hamming-/Ratio-/Mutual-Best-Matcher für überlappende bestätigte Sparse Maps; skalierbare DBoW-/Embedding-Ortssuche bleibt offen;
- [x] begrenzte deterministische Appearance-Place-Recognition-Datenbank mit zeitlichem Ausschluss und gewichteter Kandidatensuche;
- [x] metrische geometrische Loop-Verifikation mit Mutual-Best-/Ratio-Matches, 3D–3D-RANSAC, Inlier-/RMSE-/Degenerations- und VIO-Prior-Gates;
- [x] begrenzter transaktionaler SE(3)-Pose-Graph mit festem Gauge, Unsicherheitsgewichtung, robusten Loop-Faktoren, matrixfreiem PCG und Line Search;
- [x] begrenztes transaktionales globales Bundle Adjustment für Keyframe-Posen und mehransichtige metrische Landmarks mit robusten Reprojektionsfaktoren und Pose-Graph-Priors;
- [x] atomare generationierte Archiv-Pose-Propagation über CRC-geschütztes Manifest mit exakter Frame-ID-/Timestamp-Bindung und Invalidierung bei Frame-Verlust;
- [x] deterministischer globaler Landmark-Map-Merge aus geometrischen Loop-Inliern mit metrischem Complete-Link-Gate, beobachtungsgewichteter Fusion und stabiler Alias-Tabelle;
- [x] deterministische Multi-Plane-API mit robustem RANSAC, orthogonaler Least-Squares-Inlier-Verfeinerung und stabilen Plane-IDs;
- [x] gravitationsbezogene geometrische Klassifikation als Boden, Decke, Wand oder Schräge;
- [x] konservative Raumlayout-Zusammenfassung mit Boden-/Deckenhöhe, plausibler Raumhöhe und beobachteter Wandfläche;
- [x] begrenzte Nächster-Treffer-Raycasts über alle aktiven Ebenen und World-Anchor-API;
- [x] Drei-Sicht-Landmark-Bestätigung und Entfernung wiederholt inkonsistenter dynamischer Tracks;
- [x] Runtime-, C-ABI-, JNI- und Kotlin-Integration für Map-Updates, Plane-Raycasts und Weltanker;
- [x] versioniertes Spatial-Snapshot-Format, CRC, harte Decode-Limits und atomarer Restore;
- [x] Android-Keystore-AES-GCM-Envelope mit `AtomicFile`, No-Backup-Speicher und Löschpfad;
- [ ] visuelle Relokalisierung zur sicheren automatischen Wiederverwendung persistierter Weltframes; robuster metrischer 3D–3D-RANSAC, lokales Deskriptor-Matching, Qualitätsgates und transformierter atomarer Restore sind implementiert; großräumige Place Recognition bleibt offen.

## Phase 3 – Tiefe, Mesh, Occlusion

- [x] modellunabhängiges, voxelbegrenztes Surfel-Backend mit Konfidenz-/Normalenfusion, begrenztem Nächster-Treffer-Raycast und Android-Mapping-Gate; TSDF bleibt offen;
- optional Depth Anything V2 Small quantisiert als Prior;
- [x] konservative pose-kompensierte temporale metrische Tiefenfilterung ohne Hole-Filling einschließlich eines Backend-Decorators vor Scheduler/Session;
- [x] begrenzte dynamische Box-Maskierung sowie generisches Class-Index-Segmentmasken-Postprocessing für direkte FLOAT32-/INT8-/UINT8-Ausgaben; modellspezifisches Logit-/Instanzmasken-Decoding, Skalenfusion und Mesh-LOD bleiben offen;
- [x] begrenzter metrischer CPU-Z-Depthbuffer aus endlichen Surfelscheiben; Filament-/GPU-Occlusionadapter bleibt offen.

## Phase 4 – Semantik

- [x] backend-unabhängiges Android-Inferenz-SPI, begrenzter Single-Flight-Scheduler, latenzadaptive Kadenz, PowerManager-Thermaladapter und besitzende korrekt geordnete Perception-Pipeline ohne Queue; konkrete LiteRT-/ONNX-Runtime-Adapter bleiben offen;
- [x] dependency-freies, begrenztes Native-Preprocessing für bilineares Resize, Normalisierung sowie FLOAT32-/INT8-/UINT8-NHWC-Ausgabe;
- [x] ungültigkeitsbewusstes, begrenztes Depth-Tensor-Postprocessing für quantisierte/direct/inverse Modellausgaben zu kameragroßer metrischer Tiefe plus Konfidenz;
- [x] verifizierte, pufferwiederverwendende metrische Depth-, row-basierte Detection- und kombinierte Multi-Head-Backends um schmale runtime-unabhängige Tensor-Schnittstellen;
- [x] begrenzte runtime-neutrale Decoder/Backends für SSD/EfficientDet-`Detection_PostProcess`, YOLOX-artige Objectness×Klassenscores und RT-DETR-artige normalisierte Boxen mit Probability/Sigmoid/Softmax; konkrete LiteRT-/ONNX-/ncnn-/MNN-Adapter und Delegate-Profile bleiben offen;
- [x] spezialisierte begrenzte Multi-Level-DFL-Decodierung für zusammengeführte sowie getrennte NanoDet-/PicoDet-artige Klassen-/Regressions-Heads mit exakten Outputreihenfolge-, Stride-, Zeilen-, Klassen- und `reg_max`-Verträgen;
- [x] begrenztes deterministisches klassenbewusstes IoU-Fallback-Tracking für Detektionen ohne Modell-Track-ID; modellgelerntes Re-ID, Segmentierung und gemessene Geräteprofile bleiben offen;
- [x] begrenzte semantische 3D-Objektfusion, Unknown-Klasse und kalibrierte Boden-/Wandprojektion; konkrete Inferenzadapter bleiben offen;
- [x] validiertes Modellmanifest für Hash, Lizenz, Labels, Quantisierung, metrische Tiefe und Geräteprofile; Paketierung muss Modellbytes/Notices weiterhin verifizieren.

## Phase 5 – Outdoor und Produktreife

- GNSS/Barometer-Faktoren und robuste Georeferenzierung;
- OpenXR- und optionaler ARCore-/Geospatial-Adapter hinter `OptionalExternalArRuntimeFactory`; Standalone bleibt zwingender Default-Fallback;
- Privacy Threat Model, Fuzzing, SBOM, API-Stabilität;
- [ ] CPU-/Delegate-Golden-Conformance: begrenzte adapterneutrale Single- und Multi-Output-Gates mit deterministischem CPU-Fallback sind implementiert; konkrete Runtime-Adapter und Device-Farm-Korpora bleiben offen;
- [ ] versionierte verhaltensbasierte Gerätekompatibilität: statische Signatur/Reason Codes sowie begrenzte aktive Kamera-/CaptureResult-/IMU-Messung für Monotonie, Rate, Jitter, Lücken, Paarung, Überlappung und Realtime/Uptime-Klassifikation sind implementiert; ein automatischer terminaler Testzeitraum und nicht-extrapolierende Accelerometer-/Gyro-Synchronisierung sind ebenfalls implementiert; sessionkonstantes Uptime→Realtime-Alignment mit robuster Domänenentscheidung ist implementiert; sessioninterne Offset-/Residual-Sprungüberwachung mit sofortigem Mapping-Stopp ist implementiert; begrenzte robuste native affine Langzeitdrift-/Residualüberwachung mit ppm-Gate ist implementiert; geführte visuell-inertiale Bewegungsobservierbarkeit bleibt offen;
- [ ] synthetische Camera2-/IMU-Fehlerinjektion: skalare Timing-, Gap-, Dropout-, Latenz-, Clock- und Überlappungsfehler, alle 45 Fehlerpaare sowie ein produktiv verdrahteter generationssicherer Camera2-Lifecycle-Automat mit exhaustiven kurzen Callback-Sequenzen sind implementiert; Framework-Service-Death-Instrumentierung und breiteres Zufalls-Fuzzing bleiben offen; [x] signierte disable-only Quirk-Policy mit atomarer lokaler Installation und Offline-Fallback;
- [ ] reale Geräte nach Capability-Set-Cover statt Modellliste: deterministische gewichtete Single-/Pairwise-Inventarauswahl ist implementiert; Cloud-/Lab-Provider-Anbindung, Mobly-Sensorfusionsrig, thermische 30-Minuten-Läufe sowie Tag/Nacht/Weather-Szenen bleiben offen;
- [ ] optionales Feld-Diagnosepaket ohne Bilder/Trajektorien als Default; Zentimetervalidierung bleibt an unabhängige Targets und exakte Capability-/Kalibriersignatur gebunden.

## Qualitätsgates je Pull Request

Format/Lint → Unit → Sanitizer → Recorded-sensor replay → Accuracy regression → Android instrumented → Lizenz/SBOM. Ein schnellerer Build darf Genauigkeits-, Speicher- oder Temperaturbudgets nicht unbemerkt verschlechtern.

## Metric reconstruction, object mode and portable assets

- [x] Add evidence-based `unverified` / `approximate` / `centimeter-validated` quality contract.
- [x] Add bounded canonical indexed mesh with normals, UVs and base-colour texture reference.
- [x] Add dependency-free glTF 2.0, OBJ/MTL and PLY exporters.
- [x] Add offline WebGL 2 viewer for exported textured glTF assets.
- [x] Add bounded portable keyframe quality/novelty selector with pose uncertainty and opaque full-resolution image references.
- [x] Add object ROI, distance, stable-center and azimuth/elevation coverage planner.
- [x] Implement bounded crash-safe Android frame archive with exposure, pose, uncertainty, CRC and opaque references.
- [x] Associate Camera2 YUV colour, exposure/ISO and session pose by exact sensor timestamp.
- [x] Add stride-safe canonical I420 copy and bounded asynchronous archive writer with explicit backpressure.
- [x] Add bounded pre-copy luminance sharpness/clipping metrics and a session-level quality gate.
- [x] Connect the native portable planner through JNI (pose, uncertainty, quality, ROI, view bins and rejection reason).
- [x] Add one-at-a-time planner reservation with tokenized commit/cancel and unchanged pre-commit coverage.
- [x] Add host scan controller wiring quality gate, reservation, I420 packet, bounded writer and durable commit/cancel.
- [ ] Add optional authenticated encryption for sensitive retained keyframes and explicit host retention UX.
- [ ] Add verified loop closure, pose graph optimization and global bundle adjustment.
- [x] Add a bounded calibrated inverse-depth plane-sweep MVS fallback with distortion-aware multi-view reprojection, ZNCC support gates, explicit unknown pixels and sampling uncertainty.
- [ ] Add coarse-to-fine/census refinement, forward-backward depth consistency, tiled sparse TSDF fusion, meshing and simplification.
- [ ] Add UV atlas generation, visibility-aware multi-view selection, exposure compensation and seam optimization.
- [ ] Add isolated-object ROI/foreground capture state machine, coverage guidance and turntable motion model.
- [ ] Add held-out scale-target and dimensional-error evaluator; never claim centimetre accuracy without it.
- [x] Add GLB 2.0 geometry/material packaging and local viewer loading.
- [ ] Embed texture image chunks/data URIs where true single-file GLB is required.
- [ ] Add optional Android Filament viewer module; then USDZ and survey-format adapters.
