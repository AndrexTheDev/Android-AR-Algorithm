# Systematische Roadmap

## Phase 0 – Fundament (jetzt)

- [x] Lizenz- und Kandidatenanalyse
- [x] portable C++17-Modulgrenzen
- [x] IMU-Vorintegration, Qualitätsgate, Sensor-Orchestrator
- [x] Unit-Tests ohne Drittanbieterabhängigkeiten
- [x] Linux Native-CI mit Unit-, Sanitizer- und deterministischem Replay; Android-NDK-CI bleibt offen
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
- geometrische Loop-Verifikation, Pose Graph, Map merge;
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
- [x] konservative pose-kompensierte temporale metrische Tiefenfilterung ohne Hole-Filling einschließlich eines Backend-Decorators vor Scheduler/Session; Skalenfusion, dynamische Masken und Mesh-LOD bleiben offen;
- [x] begrenzter metrischer CPU-Z-Depthbuffer aus endlichen Surfelscheiben; Filament-/GPU-Occlusionadapter bleibt offen.

## Phase 4 – Semantik

- [x] backend-unabhängiges Android-Inferenz-SPI und begrenzter Single-Flight-Scheduler; konkrete LiteRT-/ONNX-Runtime-Adapter bleiben offen;
- [x] dependency-freies, begrenztes Native-Preprocessing für bilineares Resize, Normalisierung sowie FLOAT32-/INT8-/UINT8-NHWC-Ausgabe;
- [x] ungültigkeitsbewusstes, begrenztes Depth-Tensor-Postprocessing für quantisierte/direct/inverse Modellausgaben zu kameragroßer metrischer Tiefe plus Konfidenz;
- [x] verifizierte, pufferwiederverwendende metrische Depth-, row-basierte Detection- und kombinierte Multi-Head-Backends um schmale runtime-unabhängige Tensor-Schnittstellen; konkrete LiteRT-/ONNX-Adapter und Delegate-Profile bleiben offen;
- Detektion + Tracking + Segmentierung mit gemessenen Geräteprofilen;
- [x] begrenzte semantische 3D-Objektfusion, Unknown-Klasse und kalibrierte Boden-/Wandprojektion; konkrete Inferenzadapter bleiben offen;
- [x] validiertes Modellmanifest für Hash, Lizenz, Labels, Quantisierung, metrische Tiefe und Geräteprofile; Paketierung muss Modellbytes/Notices weiterhin verifizieren.

## Phase 5 – Outdoor und Produktreife

- GNSS/Barometer-Faktoren und robuste Georeferenzierung;
- OpenXR- und optionaler ARCore-/Geospatial-Adapter;
- Privacy Threat Model, Fuzzing, SBOM, API-Stabilität;
- 8+ reale Geräte, thermische 30-Minuten-Läufe, Tag/Nacht/Weather-Szenen.

## Qualitätsgates je Pull Request

Format/Lint → Unit → Sanitizer → Recorded-sensor replay → Accuracy regression → Android instrumented → Lizenz/SBOM. Ein schnellerer Build darf Genauigkeits-, Speicher- oder Temperaturbudgets nicht unbemerkt verschlechtern.
