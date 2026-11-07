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
- `fixed_lag_smoother.h`: begrenztes Keyframe-Fenster, angrenzende IMU-/Vision-Faktoren und robuste Residuen;
- `c_api.h`: versionierte C-Grenze mit opaken, thread-sicheren VIO- und Visual-Handles;
- `modules.h`: Backend-Schnittstellen;
- `imu_preintegrator.h`: erste echte Schätzkomponente;
- `tracking_quality.h`: stabiler Zustandsautomat;
- `core_engine.h`: deterministischer Orchestrator.

Der Kern speichert keine Pointer auf Kamerabuffer über `processFrame` hinaus. Der Host behält Ownership. Ein späteres stabiles C-ABI/JNI liegt vor C++-Details und versioniert alle Strukturen.


## Visual-Frontend-Zustände

`kInitializing` erzeugt den ersten Featurebestand. `kTracking` bedeutet geometrisch verifizierte Korrespondenzen; `kLimited` behält optisch plausible Tracks, gibt aber keine sichere Geometrie frei. `kReinitialized` verwirft alte Tracks nach Zeitlücke, Auflösungswechsel oder Kollaps. Feature-IDs bleiben über erfolgreiche Frames stabil; neu erkannte Punkte erhalten niemals recycelte IDs innerhalb einer Session.


## VIO-Bootstrap versus Optimizer

Der aktuelle `VioEstimator` initialisiert die Welt-z-Achse aus einem nachweislich stationären IMU-Fenster, schätzt den Gyro-Bias und den entlang der Schwerkraft beobachtbaren Accelerometer-Bias, propagiert Pose/Geschwindigkeit und korrigiert konservativ mit visueller Relativgeometrie. Eine Essential-Translation besitzt nur eine Richtung; der metrische Faktor gilt deshalb für genau dieses Frameintervall und wird nur bei konsistenter inertialer Verschiebung akzeptiert. Das ist ein sicherer Bootstrap, aber noch kein Sliding-Window-Bundle-Adjustment. Kamera-zu-IMU-Rotation ist konfigurierbar; der kleine räumliche Hebelarm wird erst im Optimizer vollständig modelliert.


## Fixed-Lag-Fenster

Das Fenster hält eine harte Maximalzahl an Keyframes. Der älteste Zustand ist der feste Prior; bei Überlauf wird er entfernt und der nächste aktuelle Zustand zum neuen Prior. IMU-Faktoren koppeln Rotation, Geschwindigkeit und Position, visuelle Faktoren koppeln Rotation und die zur Essential-Geometrie passende Translationsrichtung. Huber-Verluste begrenzen den Einfluss grober Residuen. Die aktuelle deterministische Forward-Sweep-Lösung ist ein dependency-free Fixed-Lag-Bootstrap. Sie ist ausdrücklich noch keine vollständige sparse Gauss-Newton-Lösung mit Bias-Jakobians und Schur-Komplement-Marginalisierung.


## Android- und ABI-Grenze

Die C-ABI verwendet nur C11-kompatible POD-Strukturen, `struct_size` und eine explizite API-Version. C++-Exceptions verlassen die Bibliothek nie. Handles sind opak und intern per Mutex geschützt; Fehlertexte werden in Caller-Speicher kopiert. JNI transportiert Nanosekunden als `Long`, nicht als ungenaues `Double`. Die Kotlin-Fassade besitzt den nativen Handle eindeutig und implementiert `AutoCloseable`. `AndroidImuSource` registriert beide Sensoren auf einem eigenen `HandlerThread`; Pause/Resume setzt den Zustand zurück, damit Boot-Time-Sequenzen verschiedener Kamera-/App-Sessions nie vermischt werden.


## Camera2-Puffervertrag

`Camera2FrameSource` fordert exakt die Auflösung der übergebenen `CameraCalibration` an. Der Host muss Kamera-ID, Crop, Stabilisierung und Intrinsics konsistent wählen; der Kern rät keine Brennweite. Der direkte Y-Plane-`ByteBuffer` wird synchron über JNI verarbeitet und das `Image` erst danach geschlossen. `rowStride` und ein möglicherweise ungepolstertes letztes Bildsegment werden korrekt berücksichtigt. Das Visual-Frontend kopiert nur den benötigten Grauwertzustand für den nächsten Frame; kein Android-Pufferpointer wird gespeichert.


## Android-Session und zeitliche Ordnung

`ArKernelSession` puffert kombinierte IMU-Samples begrenzt. Vor einem Bild mit Capture-Zeit `t` werden ausschließlich Samples mit `timestamp <= t` in VIO propagiert; erst danach entstehen Rotationsvorhersage und visuelle Messung. Eine allgemeine 3D-Relativpose wird anschließend am gleichen Zeitstempel fusioniert. Später eintreffende IMU-Samples bleiben für das nächste Bild sortiert. Queue-Überläufe werden gezählt und im Session-Ergebnis sichtbar. Die kalibrierte Rotation `body_from_camera` ist Pflicht: Android-Body- und optische Kameraachsen dürfen niemals stillschweigend gleichgesetzt werden.


## Replay als ausführbare Spezifikation

`ReplayDataset` validiert ein versioniertes, monotones Manifest und exakte kalibrierte P5-Graustufenframes. `ReplayRunner` verwendet dieselben Frontend- und VIO-Klassen sowie dieselbe Regel „IMU bis einschließlich Capture-Zeit vor Frame“. Ergebniszähler und quantisierte Zustände fließen in einen deterministischen FNV-1a-Regressionshash. Replay-Code wird als separates Support-Target gebaut und vergrößert deshalb die Android-Core-Bibliothek nicht.
