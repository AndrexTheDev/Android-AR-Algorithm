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

- [x] modellunabhängiges, voxelbegrenztes Surfel-Backend mit Konfidenz-/Normalenfusion, begrenztem Nächster-Treffer-Raycast und Android-Mapping-Gate; block-sparser Narrow-Band-TSDF, Marching Tetrahedra, Komponentenfilter, konservative Vereinfachung und begrenzte Android-Fusions-/GLB-Orchestrierung sind implementiert; hochwertiger Quadric-Error-/LOD-Pfad bleibt offen;
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
- [ ] CPU-/Delegate-Golden-Conformance: begrenzte adapterneutrale Single-/Multi-Output-Gates, verifizierte exakte Modell-/Manifest-/Task-Identität, kanonische Detection-/Metric-Depth-Semantikprüfung, read-only Factory-Admission und integrierte atomare einschließlich kanonischer typisierter und vom Delegate selbst gemeldeter Ausführungs-/Treiberidentität exaktsignaturgebundene Erfolgsevidenz mit verpflichtendem Startup-Sentinel mit deterministischem CPU-Fallback sind implementiert; modellnamenfreie exakte Capability-/Backend-/Treiber-Profile sind im Manifest gebunden; konkrete separat paketierte Runtime-Adapter und Device-Farm-Korpora bleiben offen;
- [ ] versionierte verhaltensbasierte Gerätekompatibilität: statische Signatur/Reason Codes sowie begrenzte aktive Kamera-/CaptureResult-/IMU-Messung für Monotonie, Rate, Jitter, Lücken, Paarung, Überlappung und Realtime/Uptime-Klassifikation sind implementiert; ein automatischer terminaler Testzeitraum und nicht-extrapolierende Accelerometer-/Gyro-Synchronisierung sind ebenfalls implementiert; sessionkonstantes Uptime→Realtime-Alignment mit robuster Domänenentscheidung ist implementiert; sessioninterne Offset-/Residual-Sprungüberwachung mit sofortigem Mapping-Stopp ist implementiert; begrenzte robuste native affine Langzeitdrift-/Residualüberwachung mit ppm-Gate ist implementiert; signierte physische Kameraauswahl hinter logischen Multi-Kameras wird ab API 28 am Output gepinnt und gegen physische Capture-Metadaten überwacht; optional signierter Rolling-Shutter-Skew wird mit expliziter Nanosekunden-Toleranz terminal überwacht; ein opt-in terminaler geführter Bewegungs-Admission-Gate kombiniert aktive Streamprüfung, signierte Kamera-/Gyro-Rotation, beobachtbaren Zeitversatz und metrische Laufzeitkonsistenz, ohne daraus selbst Zentimetervalidierung abzuleiten; eine begrenzte UI-neutrale Ablaufsteuerung liefert stabile Stillhalte-/Rotations-/Translations-/optionale Referenzziel-Anweisungen und einen automatischen terminalen Abschluss; lokalisierte App-UX und physische Rig-Evidenz bleiben offen;
- [ ] synthetische Camera2-/IMU-Fehlerinjektion: skalare Timing-, Gap-, Dropout-, Latenz-, Clock- und Überlappungsfehler, alle 45 Fehlerpaare sowie ein produktiv verdrahteter generationssicherer Camera2-Lifecycle-Automat mit exhaustiven kurzen Callback-Sequenzen sind implementiert; Framework-Service-Death-Instrumentierung und breiteres Zufalls-Fuzzing bleiben offen; [x] signierte disable-only Quirk-Policy mit atomarer lokaler Installation und Offline-Fallback;
- [ ] reale Geräte nach Capability-Set-Cover statt Modellliste: deterministische gewichtete Single-/Pairwise-Inventarauswahl ist implementiert; Cloud-/Lab-Provider-Anbindung, Mobly-Sensorfusionsrig, thermische 30-Minuten-Läufe sowie Tag/Nacht/Weather-Szenen bleiben offen;
- [ ] optionales Feld-Diagnosepaket ohne Bilder/Trajektorien als Default; Zentimetervalidierung bleibt an unabhängige Targets und exakte Capability-/Kalibriersignatur gebunden.

## Qualitätsgates je Pull Request

Format/Lint → Unit → Sanitizer → Recorded-sensor replay → Accuracy regression → Android instrumented → Lizenz/SBOM. Ein schnellerer Build darf Genauigkeits-, Speicher- oder Temperaturbudgets nicht unbemerkt verschlechtern.

## Metric reconstruction, object mode and portable assets

- [x] Add evidence-based `unverified` / `approximate` / `centimeter-validated` quality contract and assessed glTF/GLB/OBJ/PLY export paths that recompute and embed the held-out report; legacy geometry-only exports remain `unverified`.
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
- [x] Add optional authenticated encryption for sensitive retained keyframes and explicit host retention UX: opt-in AES-256-GCM envelopes for colour frames and frame-owned depth companions, AAD-bound session/file/role identity, non-exportable Android Keystore keys, strict fail-closed decoding and explicit host retention/deletion controls are implemented; full app UX composition remains open.
- [x] Add localized app retention UI resources and host selection contract: Android resource-qualified German/English dialog strings, three deletion choices, encryption-key availability/warning, explicit confirmation and a fail-closed policy selector are implemented.
- [x] Add a bounded localized visual app retention dialog: a platform `AlertDialog` with an Activity window token, three modes, dynamic key-deletion availability, explicit confirm/cancel, fail-closed selection and no automatic dismissal on invalid choices is implemented; full app UX composition is host-owned.
- [x] Add a bounded host-owned retention UX composition contract: one localized Activity dialog at a time, fail-closed policy confirmation, explicit publication/release actions, action-sink failure propagation and localized result text are composed; hosts still apply actions and own full app UX and physical-device lifecycle validation.
- [ ] Add full app retention UX composition and physical-device validation of the encrypted keyframe lifecycle.
- [ ] Add verified loop closure, pose graph optimization and global bundle adjustment.
- [x] Add a bounded calibrated inverse-depth plane-sweep MVS fallback with distortion-aware multi-view reprojection, ZNCC support gates, explicit unknown pixels and sampling uncertainty.
- [ ] Add dense reconstruction refinement and fusion: bounded inverse-depth refinement, uncertainty, matching/consistency, sparse TSDF fusion, meshing, filtering, conservative clustering, Android orchestration, view selection, coherent labels, UV layout, relative per-view RGB normalization, local seam blending, bounded global least-squares seam optimization, measured quadric-error-guided clustering LOD and canonical RGBA/I420 pixel baking are implemented; full Poisson/multiband seam solving, calibrated camera-response modelling and dynamic edge-collapse QEM remain; bounded durable multi-session state is implemented.
- [x] Add deterministic bounded visibility-aware per-triangle multi-view selection with calibrated projection and optional uncertainty-aware depth checks.
- [x] Add bounded deterministic coherent patch labeling over visibility-approved alternatives while preserving sharp/non-manifold boundaries.
- [x] Add bounded UV-island extraction, patch provenance, seam-aware vertex duplication and deterministic single-page atlas layout.
- [x] Add bounded Gray/RGB/RGBA/canonical-I420 atlas rasterization and patch-local padding dilation with raw RGBA8 output.
- [x] Add bounded robust cross-view seam sampling and deterministic per-view linear RGB exposure normalization.
- [x] Add deterministic bounded local RGB blending across paired manifold atlas seams.
- [x] Add bounded Android RGBA8-to-PNG encoding and portable PNG/JPEG embedding in canonical GLB output.
- [x] Add bounded crash-safe Android publication of canonical GLB assets with opaque references and deterministic eviction.
- [x] Add bounded Android decoding of archived JPEG/PNG sources to provenance-preserving canonical RGBA8.
- [x] Add bounded JNI/Android finalization from seam-aware mesh plus RGBA atlas through PNG, embedded GLB and atomic publication.
- [x] Add fail-closed portable orchestration across view selection, coherent labels, atlas, photometry, baking and local seam blending.
- [x] Add bounded fail-closed Android resolution of the unique core view table from opaque archived-frame references to owned canonical pixels.
- [x] Expose the complete portable graph through bounded JNI using loaded canonical sources, preserve exact stage failures, and feed successful output directly to the final publisher.
- [x] Add timestamp-bound calibrated visibility depth/confidence/uncertainty to the Android/JNI texture graph and verify occlusion fail-closed on API 35.
- [x] Add CRC-framed, budgeted, frame-provenance-bound visibility-depth companions under the existing opaque archive reference.
- [x] Expose the filtered metric source mesh directly from Android fusion as bounded native-order buffers and feed it to the texture graph without GLB reparsing.
- [x] Add one bounded session-level transaction from archived colour/depth frames through direct complete fused source mesh, calibrated texture construction, embedded GLB and atomic publication, preserving exact provenance, uncertainty, stage telemetry and explicit `unverified` quality.
- [x] Add a bounded durable multi-session reconstruction state archive with versioned CRC envelopes, monotonic generations, opaque references, exact terminal provenance/telemetry and deterministic session/generation/byte-budget eviction; no image/depth pixels or dense trajectories are stored.
- [x] Add optional bounded global least-squares seam optimization and measured quadric-error-guided clustering LOD with explicit budgets, fail-closed telemetry, opt-in JNI/Kotlin flags and preserved `unverified` quality.
- [x] Add a bounded native camera-response linearity estimator: unique bounded sample IDs, finite positive paired exposures, unsaturated intensities, diverse exposure ratios/intensities, a robust black-level median, positive-slope and residual/RMSE gates, exact rejection reasons and explicit `unverified` quality are implemented; it is synthetic consistency evidence, not physical camera calibration or full response modelling.
- [ ] Add a full global Poisson/multiband seam solve, calibrated camera-response model and dynamic edge-collapse QEM topology optimizer.
- [x] Add isolated-object ROI/foreground capture state machine, coverage guidance and turntable motion model: a bounded deterministic state machine over host-measured foreground ratios and object centres, measured azimuth/elevation coverage, UI-neutral instructions and a bounded turntable target are implemented; localized host prompts are implemented, while physical hardware/rig evidence remains open.
- [x] Add localized isolated-object capture prompts: bounded German/English UI-neutral prompt formatting with measured coverage detail and exact phase/instruction/rejection tone is implemented; physical hardware/rig evidence and real-device validation remain open.
- [x] Add a bounded host-owned turntable actuation protocol: one-at-a-time commands, exact command IDs, monotonic timestamps and deadlines, step/target integrity, measured executed-step feedback, fail-closed adapter faults and terminal reset are implemented; the library does not control hardware.
- [ ] Add physical turntable/rig evidence and real-device validation of the guidance model.
- [x] Add a bounded held-out dimensional evaluator over reconstructed 3D point pairs with unique evidence IDs, explicit independent reference provenance, uncertainty-aware absolute/relative error, dimension diversity and assessed-extent coverage, exposed through stable C ABI/JNI/Kotlin reason flags; `centimeter-validated` is impossible without it.
- [x] Add GLB 2.0 geometry/material packaging and local viewer loading.
- [x] Embed bounded encoded PNG/JPEG image buffer views for true single-file textured GLB output.
- [x] Add a bounded canonical survey-measurement CSV adapter for held-out dimensional evidence: an exact schema, input/line/row budgets, unique bounded IDs, exact reference kinds, finite coordinates/distances/uncertainties, explicit held-out flags, deterministic encode/parse and fail-closed rejection are implemented; broader survey formats remain open.
- [ ] Add an optional Android Filament viewer module and USDZ/broader survey-format adapters.
