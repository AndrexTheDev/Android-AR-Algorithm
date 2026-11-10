# AR Kernel for Android

Ein modularer, offline-fähiger Augmented-Reality-Kern für Android – ohne LiDAR-Zwang und ohne Cloud-Zwang.

> **Status: Phase 0 / Forschungs- und Kernfundament (v0.1).** Dies ist noch kein marktreifes SLAM-System. Der aktuelle Stand liefert die lizenzsaubere Architektur, native Datentypen, Sensor-Orchestrierung, IMU-Vorintegration, Qualitäts-Hysterese und ausführbare Tests. Die Tracking-, ML- und Mapping-Backends werden über stabile Interfaces ergänzt.

## Realistisches Ziel

Eine einzelne Smartphone-Kamera kann nicht „alles“ zuverlässig erkennen. Unbekannte Objekte, spiegelnde/strukturlose Flächen, Dunkelheit, schnelle Bewegung und große Outdoor-Szenen bleiben harte Grenzen. Der Kern ist deshalb **hybrid**: geometrisches VIO/SLAM für Pose und Maßstab, lernbasierte Tiefe/Semantik für Verständnis, optionale GNSS-Anker für Outdoor und strikte Confidence-Werte statt erfundener Gewissheit.

## Architektur

```text
Camera2 + IMU + optional GNSS
          │
          ▼
 timestamped sensor ingress ──► visual-inertial tracker (mandatory)
          │                              │
          ├──► monocular depth (paced)  ├──► pose + quality
          └──► semantic perception      │
                                         ▼
                            spatial mapper / anchors
                                         │
                                         ▼
                              stable public AR API
```

Der portable C++17-Kern hat keine Android-, Google-, ML- oder Render-Abhängigkeit. Konkrete Backends können dadurch ausgetauscht, auf Geräteklassen abgestimmt und separat lizenziert werden.

## Bereits implementiert

- streng monotone Kamera-/IMU-Zeitstempel und begrenzte Sensor-Queue;
- thread-sichere IMU-Aufnahme und framegenaue Batches;
- Midpoint-IMU-Vorintegration mit Bias-API und Gap-Rejection;
- Quaternion-/Pose-/Kamera-Grundtypen ohne Drittbibliothek;
- kalibriertes Pinhole-/Brown-Conrady-Kameramodell mit Projektion und robuster Rückprojektion;
- robuste affine Uhrenausrichtung für Sensoren mit fremder Zeitbasis;
- mobiles FAST-9-Feature-Frontend mit Non-Maximum-Suppression und Grid-Quoten;
- pyramidenbasiertes Lucas-Kanade-Tracking mit Subpixel-Interpolation, Texturprüfung und Vorwärts-Rückwärts-Kontrolle;
- IMU-Rotationsvorhersage als Initialisierung für große Kameradrehungen;
- deterministischer kalibrierter 8-Punkt-RANSAC mit Hartley-Normalisierung, Sampson-Fehler und Degenerationsprüfung;
- Essential-Rangprojektion und relative Posezerlegung mit Triangulation, Cheirality- und Parallaxen-Gate;
- normalisierter Homographie-RANSAC und konservative Homographie-/Epipolar-Modellwahl für planare oder rotationsdominierte Szenen;
- zustandsbehaftetes Visual-Frontend mit stabilen Feature-IDs, Geometriefilterung, Feature-Nachfüllung, Keyframes und kontrollierter Wiederinitialisierung;
- VIO-Bootstrap mit stationärer Schwerkraft-/Gyro-Bias-Initialisierung, Strapdown-Propagation und konservativer visueller Korrektur;
- bias-linearisierte IMU-Vorintegration mit Rotations-, Geschwindigkeits- und Positions-Jakobians;
- propagierte IMU-Unsicherheit aus konfigurierbaren Rauschdichten und kovarianzabhängige Faktorgewichtung;
- speicherbegrenztes Fixed-Lag-Fenster mit IMU-/Vision-Faktoren, Huber-Gewichtung und Prior-Promotion;
- metrische Sparse-Landmark-Karte mit Drei-Sicht-Bestätigung und dynamischer Inkonsistenzfilterung, mehrere stabil verfolgte und als Boden/Decke/Wand/Schräge klassifizierte Ebenen mit konservativer Raumhöhen-/Flächenzusammenfassung, sichere Nächster-Treffer-Raycasts und persistente Weltanker;
- versionierte, größenbegrenzte und CRC-geprüfte Spatial Snapshots mit Android-Keystore-AES-GCM- und AtomicFile-Speicher sowie lokalem BRIEF/Hamming-Matching und robust gegateter metrischer 3D–3D-Relokalisierung;
- kalibrierte native Tiefenbild-Unprojektion, pose-kompensierte konservative Temporalfilterung, voxelbegrenzte Surfel-Fusion und hart begrenzter metrischer CPU-Occlusion-Depthbuffer ohne LiDAR-Pflicht;
- validiertes Modellmanifest, deterministisches begrenztes Native-Tensor-Pre-/Depth-/Detection-Postprocessing, verifizierte runtime-unabhängige Depth-/Detection-/Multi-Head-Backends und Single-Flight-Scheduler ohne unbegrenzte Queue;
- modellunabhängige, begrenzte semantische 3D-Objektfusion mit Unknown-Klasse, Konfidenz-, Track-, Distanz- und Dynamik-Gates;
- versionierte C-ABI, JNI/Kotlin-Fassade, lifecycle-sichere Android-SensorManager-/Camera2-Adapter und eine explizite ARCore-unabhängige Standalone-Runtime mit optionalem External-Runtime-Fallback-Selector;
- kalibrierter, direkter Camera2-Y-Plane-Pfad zum nativen Visual-Frontend ohne Java-Pixelkopie;
- gemeinsame Android-Session mit zeitgeordneter IMU-Queue, Capture-Time-Propagation, vollständiger Kamera-IMU-Hebelarmextrinsik, visueller Rückkopplung und hysteretischem Mapping-Sicherheitsgate;
- versionierter Offline-Replay-Runner mit deterministischen Metriken und CI-Regressionshash;
- EuRoC-Konverter inklusive `T_BS`-Kamera/IMU-Extrinsik, Baseline-Runner und Trajektorienauswertung mit SE(3)/Sim(3), ATE und lokaler RPE;
- Tracking-Qualitätsautomat mit Hysterese gegen Statusflattern;
- modulare Interfaces für VIO, Tiefe, Semantik und Mapping;
- adaptive Ausführung: Tiefe/Mapping nur bei stabilem Tracking, ML mit konfigurierbarer Kadenz;
- dependency-free Native-Tests.

## Lokal bauen

Mit CMake:

```bash
cmake -S . -B build -DARKERNEL_BUILD_TESTS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

Oder nur mit einem C++17-Compiler:

```bash
c++ -std=c++17 -Wall -Wextra -Wpedantic -Icore/include \
  core/src/*.cpp core/tests/core_tests.cpp -pthread -o /tmp/arkernel_tests
/tmp/arkernel_tests
```

Android/NDK-Integration ist in `android/arkernel/` vorbereitet. Forschungsstand, Auswahlbegründung und Roadmap stehen in [`docs/RESEARCH.md`](docs/RESEARCH.md), [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/ROADMAP.md`](docs/ROADMAP.md), [`docs/REPLAY.md`](docs/REPLAY.md), [`docs/EVALUATION.md`](docs/EVALUATION.md), [`docs/PERSISTENCE.md`](docs/PERSISTENCE.md), [`docs/DEPTH.md`](docs/DEPTH.md), [`docs/FALLBACK.md`](docs/FALLBACK.md), [`docs/DEVICE_COMPATIBILITY_STRATEGY.md`](docs/DEVICE_COMPATIBILITY_STRATEGY.md), [`docs/INFERENCE.md`](docs/INFERENCE.md), [`docs/SEMANTICS.md`](docs/SEMANTICS.md) und [`docs/OPEN_SOURCE_ADOPTION.md`](docs/OPEN_SOURCE_ADOPTION.md).

## Diagnose-APK für ein echtes Gerät

Für ein physisches Gerät (z. B. Redmi Note 13) gibt es das sideloadbare Modul
[`android/diagnostics-app`](android/diagnostics-app/README.md). Nach explizitem
Opt-in schreibt es eine `ARKFDLOG1`-Textdatei und die zugehörige
`ARKFDPOL1`-Richtlinie in den Download- bzw. app-eigenen Dateibereich; die
Datei kann hier im Chat hochgeladen werden. Die App enthält keine Bilder,
Posen, Trajektorien oder Kalibrierarrays, lädt nichts automatisch hoch und
bleibt `unverified`. Die Android-CI baut die Debug-APK als Artefakt
`arkernel-diagnostics-apk` und veröffentlicht sie zusätzlich als Release-Asset
unter dem Tag `diagnostics-apk-latest`. Sie liefert begrenzte Laufzeitfakten,
keinen physischen Bodentruth und keine Zentimetervalidierung.

## Lizenz

Eigenständiger Code: Apache-2.0. Externe Backends und **Modellgewichte** müssen vor jeder Distribution separat geprüft werden. Insbesondere ist „Open Source Code“ nicht automatisch gleichbedeutend mit kommerziell frei nutzbaren Gewichten.

### Quality relative to ARCore

ARCore remains the mature live-tracking baseline on certified devices; the standalone kernel does not claim parity without device/scene evidence. Its advantage is a controllable ARCore-independent reconstruction/export path that can use global offline optimization. The required same-device parity metrics and claim rules are in [`docs/ARCORE_COMPARISON.md`](docs/ARCORE_COMPARISON.md).

### Reconstruction and viewing

The portable core now validates canonical textured triangle assets and exports glTF 2.0, OBJ/MTL and PLY. `tools/viewer/index.html` opens the generated glTF, binary and texture locally in WebGL 2. Accuracy labels are evidence-based: metric output is not called centimetre-validated unless calibration, independent scale, global optimization, residual, uncertainty and coverage gates all pass. See [`docs/RECONSTRUCTION.md`](docs/RECONSTRUCTION.md).
