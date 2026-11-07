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

Android/NDK-Integration ist in `android/arkernel/` vorbereitet. Forschungsstand, Auswahlbegründung und Roadmap stehen in [`docs/RESEARCH.md`](docs/RESEARCH.md), [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) und [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Lizenz

Eigenständiger Code: Apache-2.0. Externe Backends und **Modellgewichte** müssen vor jeder Distribution separat geprüft werden. Insbesondere ist „Open Source Code“ nicht automatisch gleichbedeutend mit kommerziell frei nutzbaren Gewichten.
