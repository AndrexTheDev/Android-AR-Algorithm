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
- [x] konservative visuelle Rotation sowie inertial beobachtbare Intervallskalierung
- [x] begrenztes Fixed-Lag-Keyframefenster mit IMU-/Vision-Faktoren und Huber-Verlust

## Phase 1 – messbares VIO-MVP

- [x] Camera2-ImageReader mit direktem Y-Plane-JNI-Pfad und expliziter Kalibrierung; ein optionaler NDK-AImageReader-Pfad bleibt offen;
- [x] gemeinsame Android-Session mit capture-synchroner IMU-Drainage und visueller VIO-Rückkopplung;
- Kalibrierdatei und Online-Validierung; Verzerrungsmodell und Timestamp-Offset-Schätzer sind implementiert;
- Vision-Frontend inklusive Feature-Lebenszyklus ist nativ implementiert; ORB-Deskriptor und Sliding-Window-Backend folgen;
- Sparse Sliding-Window-Gauss-Newton, Bias-Jakobians und Schur-Marginalisierung; ein begrenzter robuster Fixed-Lag-Bootstrap ist implementiert;
- [x] versioniertes synthetisches Kamera-/IMU-Replay; EuroC/TUM-VI/KITTI-Import und ATE/RPE bleiben offen;
- Fehlerfälle: Cover, Blur, Rotation, Sensorlücke, App pause/resume.

**Exit:** 30 FPS Midrange, keine unbounded allocations, reproduzierbare Trajektorien und dokumentierte Genauigkeit.

## Phase 2 – Karten und Relokalisierung

- Keyframe-/Landmark-Store mit Budget und Versionierung;
- DBoW-/Embedding-basierte Kandidatensuche;
- geometrische Loop-Verifikation, Pose Graph, Map merge;
- Plane/Raycast/Anchor API; verschlüsselte lokale Persistenz.

## Phase 3 – Tiefe, Mesh, Occlusion

- sparse VIO depth → TSDF/surfel backend;
- optional Depth Anything V2 Small quantisiert als Prior;
- Skalen-/Confidence-Fusion, dynamische Masken, Mesh-LOD;
- Filament-Occlusionadapter.

## Phase 4 – Semantik

- LiteRT/ONNX Runtime Backend SPI;
- Detektion + Tracking + Segmentierung mit Geräteprofilen;
- semantische 3D-Objekte und Unknown-Klasse;
- Modellmanifest: Hash, Lizenz, Labels, Quantisierung, Benchmark.

## Phase 5 – Outdoor und Produktreife

- GNSS/Barometer-Faktoren und robuste Georeferenzierung;
- OpenXR- und optionaler ARCore-/Geospatial-Adapter;
- Privacy Threat Model, Fuzzing, SBOM, API-Stabilität;
- 8+ reale Geräte, thermische 30-Minuten-Läufe, Tag/Nacht/Weather-Szenen.

## Qualitätsgates je Pull Request

Format/Lint → Unit → Sanitizer → Recorded-sensor replay → Accuracy regression → Android instrumented → Lizenz/SBOM. Ein schnellerer Build darf Genauigkeits-, Speicher- oder Temperaturbudgets nicht unbemerkt verschlechtern.
