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
- `sparse_map.h`: Feature-ID-basierte Triangulation, begrenzte Landmark-Karte, Ebenen und Weltanker;
- `spatial_snapshot.h`: portable, versionierte und integritätsgeprüfte Kartenbytes;
- `c_api.h`: versionierte C-Grenze mit opaken, thread-sicheren VIO- und Visual-Handles;
- `modules.h`: Backend-Schnittstellen;
- `imu_preintegrator.h`: Midpoint-Vorintegration, Referenz-Bias, First-Order-Bias-Jakobians und diagonale Unsicherheitspropagation;
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

`ReplayDataset` validiert ein versioniertes, monotones Manifest, exakte kalibrierte P5-Graustufenframes und die optionale Kamera-zu-Body-Extrinsik. `ReplayRunner` verwendet dieselben Frontend- und VIO-Klassen sowie dieselbe Regel „IMU bis einschließlich Capture-Zeit vor Frame“. Rotationsvorhersage und visuelle Fusion transformieren über `body_from_camera`; EuRoC `T_BS` wird vom Konverter übernommen. Ergebniszähler und quantisierte Zustände fließen in einen deterministischen FNV-1a-Regressionshash. Replay-Code wird als separates Support-Target gebaut und vergrößert deshalb die Android-Core-Bibliothek nicht.


## Trajektorienmetriken

Der Evaluator assoziiert Zeitstempel unter einer harten Toleranz, richtet geschätzte Positionen per Horn-Verfahren optional in SE(3) oder Sim(3) aus und berechnet ATE sowie lokale SE(3)-RPE. Sim(3) ist nur Diagnose für monokulare Skalenambiguität; der primäre VIO-Benchmark bleibt SE(3). EuRoC-Ground-Truth und das versionierte ARK-Trajektorienformat werden ohne Laufzeitabhängigkeiten gelesen.


## Bias-linearisierte IMU-Vorintegration

Jeder IMU-Faktor speichert den Bias seines Linearisierungspunkts sowie 3×3-Jakobians für Rotation gegen Gyro-Bias, Geschwindigkeit gegen Accelerometer-/Gyro-Bias und Position gegen beide Biasarten. `corrected(new_bias)` aktualisiert Delta-Rotation, -Geschwindigkeit und -Position erster Ordnung, ohne Rohsamples erneut zu integrieren. Der Fixed-Lag-Smoother fordert vor jeder Residuenberechnung eine Korrektur am Bias des Start-Keyframes an. Bei großen Biasänderungen bleibt vollständige Repropagation notwendig; die First-Order-Korrektur ist bewusst kein Ersatz dafür.


## IMU-Rauschen und Faktorvertrauen

`ImuNoise` beschreibt kontinuierliche Accelerometer- und Gyroskop-Weißrauschdichten in SI-Einheiten pro √Hz. Die Vorintegration propagiert konservative diagonale Varianzen für Rotation, Geschwindigkeit und Position; längere Intervalle und starke spezifische Beschleunigung erhöhen die Unsicherheit. Der Fixed-Lag-Smoother bildet daraus ein auf `[0,1]` begrenztes Vertrauen relativ zu konfigurierbaren Varianzböden. Unsichere Faktoren werden nur herunter-, niemals über ihre manuelle Basisgewichtung hochgewichtet. Die Diagonalnäherung modelliert noch keine vollständigen Kreuzkovarianzen oder Bias-Random-Walks.


## Sparse Map und AR-Anker

Geometrisch akzeptierte Frontend-Tracks werden über ihre stabilen IDs und das kalibrierte Kameramodell in Einheitsstrahlen umgewandelt. Eine Landmark entsteht erst nach ausreichender metrischer Baseline, Parallaxe, positiver Tiefe und kleinem Abstand der beiden Sichtstrahlen. Folgebeobachtungen müssen ein Ray-Residual-Gate bestehen. Pending-Beobachtungen und Landmarken besitzen Zeitbudgets; bei Kapazitätsgrenze werden deterministisch schwach beobachtete und alte Punkte entfernt. Ein deterministischer RANSAC erkennt dominante Ebenen aus dem Snapshot. Anschließend minimiert eine Jacobi-Eigenzerlegung der zentrierten Inlier-Kovarianz den orthogonalen Least-Squares-Fehler; nach erneuter Inlier-Klassifikation wird ein zweites Mal verfeinert. Die Normalenrichtung wird kanonisch orientiert, damit dasselbe Input-Set keine Vorzeichenflips erzeugt. Finale Inlier werden in eine stabile 2D-Ebenenbasis projiziert und per monotoner konvexer Hülle auf die tatsächlich beobachtete Fläche begrenzt. Raycasts normalisieren den Strahl, prüfen Parallelität, Vorwärtsdistanz, Maximaldistanz und Polygonzugehörigkeit; eine unendliche mathematische Ebene erzeugt daher außerhalb beobachteter Geometrie keinen Treffer. `AnchorStore` hält normalisierte Posen im Weltframe unabhängig vom Rendering. Die Android-Session reicht jeden akzeptierten Frame zusammen mit seiner metrischen `world_from_camera`-Pose genau einmal über die C-ABI in diese Karte ein; Plane-Metadaten, begrenzte Raycasts und Anchor-Operationen stehen über JNI/Kotlin zur Verfügung. Dynamische Objektmasken folgen später.


## Spatial Snapshot

Persistiert werden nur primäre Landmark- und Anchor-Zustände; Ebenen bleiben ableitbar und werden nach dem Laden neu geschätzt. `ARKMAP01` kodiert Zahlen explizit Little Endian, begrenzt Counts und Gesamtbytes, validiert eindeutige IDs und schützt gegen zufällige Korruption mit CRC32. Restore baut zunächst einen vollständigen temporären Zustand und tauscht ihn erst nach erfolgreicher Validierung ein. CRC32 ist keine Authentisierung; Android muss die Bytes vor Dateispeicherung mit einem Keystore-gebundenen AEAD-Verfahren schützen.
