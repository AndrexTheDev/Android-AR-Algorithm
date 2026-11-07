# Architekturvertrag

## Prinzipien

1. **Core owns policy, adapters own technology.** `CoreEngine` entscheidet Reihenfolge, Kadenz und Qualitätsgates; Adapter kapseln ARCore, OpenCV, LiteRT, GNSS oder Renderer.
2. **Zeit ist Teil jedes Datums.** Nanosekunden im monotonen Boot-Zeitbereich; Wall-Clock-Zeit darf niemals in Fusion eingehen.
3. **Keine stille Degradation.** Trackingstatus und Confidence werden öffentlich; Mapping stoppt bei instabiler Pose.
4. **Bounded resources.** Queues, Map, Keyframes und Inferenzkadenz haben harte Grenzen.
5. **Privacy by default.** Rohbilder werden nicht persistiert oder übertragen, außer der Host aktiviert dies explizit.

## Koordinatensysteme

- rechtshändig; Meter und Sekunden;
- Pose `world_from_camera` transformiert Kamerakoordinaten in Weltkoordinaten;
- Backend-Adapter müssen Achsenkonventionen am Rand konvertieren;
- Intrinsics beziehen sich exakt auf die an den Tracker übergebene Bildgröße und Crop-Region.

## Threading

Sensor-Callbacks rufen ausschließlich `submitImu` auf. Der Kamera-/Trackingworker ruft seriell `processFrame` auf. ML-Adapter dürfen später asynchron arbeiten; Ergebnisse müssen dann mit Frame-Timestamp und Pose verknüpft werden. Renderer liest unveränderliche Snapshots, niemals die mutierende Map.

## Fehlervertrag

Programmierfehler (ungültige Intrinsics, rückläufige Kamerazeit) werden sofort als Exception abgewiesen. Laufzeitunsicherheit (zu wenige Features, ML-Konfidenz) wird als Status/Confidence transportiert. Sensor-Überlauf verwirft älteste Werte begrenzt statt unkontrolliert Speicher zu verbrauchen.

## Öffentliche Module

- `types.h`: ABI-nahe, einfache Werttypen;
- `camera_model.h`: kalibrierte Projektion/Rückprojektion ohne OpenCV-Zwang;
- `clock_aligner.h`: robuste Umrechnung externer Sensoruhren;
- `feature_detector.h`: FAST-9 und räumlich begrenzte Feature-Auswahl;
- `optical_flow.h`: coarse-to-fine Sparse Optical Flow mit expliziten Fehlerstatus;
- `motion_prediction.h`: IMU-Rotation in erwartete Featurepositionen umrechnen;
- `epipolar_ransac.h`: robuste statische Szenenkonsistenz in normalisierten Kamerakoordinaten;
- `essential_pose.h`: rangbeschränkte Essential-Matrix, relative Rotation und maßstabslose Translationsrichtung;
- `homography_ransac.h`: planare Projektivgeometrie mit symmetrischem Transferfehler;
- `geometry_model_selector.h`: konservatives Gate gegen falsche 3D-Initialisierung;
- `visual_frontend.h`: zustandsbehaftete Verkettung der Visionmodule und Feature-Lebenszyklus;
- `vio_estimator.h`: metrischer IMU-Zustand, stationäre Initialisierung und visueller Bootstrap;
- `modules.h`: Backend-Schnittstellen;
- `imu_preintegrator.h`: erste echte Schätzkomponente;
- `tracking_quality.h`: stabiler Zustandsautomat;
- `core_engine.h`: deterministischer Orchestrator.

Der Kern speichert keine Pointer auf Kamerabuffer über `processFrame` hinaus. Der Host behält Ownership. Ein späteres stabiles C-ABI/JNI liegt vor C++-Details und versioniert alle Strukturen.


## Visual-Frontend-Zustände

`kInitializing` erzeugt den ersten Featurebestand. `kTracking` bedeutet geometrisch verifizierte Korrespondenzen; `kLimited` behält optisch plausible Tracks, gibt aber keine sichere Geometrie frei. `kReinitialized` verwirft alte Tracks nach Zeitlücke, Auflösungswechsel oder Kollaps. Feature-IDs bleiben über erfolgreiche Frames stabil; neu erkannte Punkte erhalten niemals recycelte IDs innerhalb einer Session.


## VIO-Bootstrap versus Optimizer

Der aktuelle `VioEstimator` initialisiert die Welt-z-Achse aus einem nachweislich stationären IMU-Fenster, schätzt den Gyro-Bias und den entlang der Schwerkraft beobachtbaren Accelerometer-Bias, propagiert Pose/Geschwindigkeit und korrigiert konservativ mit visueller Relativgeometrie. Eine Essential-Translation besitzt nur eine Richtung; der metrische Faktor gilt deshalb für genau dieses Frameintervall und wird nur bei konsistenter inertialer Verschiebung akzeptiert. Das ist ein sicherer Bootstrap, aber noch kein Sliding-Window-Bundle-Adjustment. Kamera-zu-IMU-Rotation ist konfigurierbar; der kleine räumliche Hebelarm wird erst im Optimizer vollständig modelliert.
