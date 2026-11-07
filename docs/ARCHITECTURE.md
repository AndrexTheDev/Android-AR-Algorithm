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
- `modules.h`: Backend-Schnittstellen;
- `imu_preintegrator.h`: erste echte Schätzkomponente;
- `tracking_quality.h`: stabiler Zustandsautomat;
- `core_engine.h`: deterministischer Orchestrator.

Der Kern speichert keine Pointer auf Kamerabuffer über `processFrame` hinaus. Der Host behält Ownership. Ein späteres stabiles C-ABI/JNI liegt vor C++-Details und versioniert alle Strukturen.
