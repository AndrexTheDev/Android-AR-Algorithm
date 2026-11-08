# Architekturvertrag

## Prinzipien

1. **Core owns policy, adapters own technology.** `CoreEngine` entscheidet Reihenfolge, Kadenz und Qualitätsgates; Adapter kapseln ARCore, OpenCV, LiteRT, GNSS oder Renderer.
2. **Zeit ist Teil jedes Datums.** Nanosekunden im monotonen Boot-Zeitbereich; Wall-Clock-Zeit darf niemals in Fusion eingehen.
3. **Keine stille Degradation.** Trackingstatus und Confidence werden öffentlich; Mapping stoppt bei instabiler Pose.
4. **Bounded resources.** Queues, Map, Keyframes und Inferenzkadenz haben harte Grenzen.
5. **Privacy by default.** Rohbilder werden nicht persistiert oder übertragen, außer der Host aktiviert dies explizit.

## Android-Runtime-Auswahl

Die Kern-AAR hat keine ARCore-Abhängigkeit. `ArRuntimeSelector` behandelt einen separat gelieferten ARCore-/External-Adapter als optionale Optimierung und fällt bei Abwesenheit, fehlendem Support oder Erzeugungsfehler auf `StandaloneArRuntime` zurück. Der Standalone-Pfad prüft Accelerometer, Gyroskop und einen zur expliziten Kalibrierung passenden Camera2-YUV-Stream; er rät keine Kamera-IMU-Extrinsik. Details und ehrliche Feature-Matrix: `FALLBACK.md`.

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
- `semantic_map.h`: modellunabhängige, begrenzte Fusion metrischer 3D-Klassenbeobachtungen;
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

`ArKernelSession` puffert kombinierte IMU-Samples begrenzt. Vor einem Bild mit Capture-Zeit `t` werden ausschließlich Samples mit `timestamp <= t` in VIO propagiert; erst danach entstehen Rotationsvorhersage und visuelle Messung. Eine allgemeine 3D-Relativpose wird anschließend am gleichen Zeitstempel fusioniert. Später eintreffende IMU-Samples bleiben für das nächste Bild sortiert. Queue-Überläufe werden gezählt und im Session-Ergebnis sichtbar. Vor jeder Kartenintegration bewertet ein natives `MappingQualityGate` initialisierte beziehungsweise limitierte Pose, Frontend-Tracking, akzeptierte Track-Anzahl und neue IMU-Queue-Überläufe. Ein schlechter Frame pausiert standardmäßig sofort; erst drei aufeinanderfolgende gute Frames aktivieren Mapping wieder. Der Pausengrund wird öffentlich gemeldet, und der Status vor visueller Fusion bleibt relevant, damit eine Fusion einen vorherigen IMU-Gap nicht im selben Frame verdeckt. Die vollständige kalibrierte Pose `body_from_camera` ist Pflicht: Android-Body- und optische Kameraachsen sowie ihre Ursprünge dürfen niemals stillschweigend gleichgesetzt werden. Die rotierte metrische Translation bestimmt den Kameramittelpunkt im Weltframe. Visuelle Skale wird gegen dessen vorhergesagte Bewegung beobachtet; erst danach wird die korrigierte Kameraposition über den Hebelarm zurück auf den Body-Ursprung transformiert.


## Replay als ausführbare Spezifikation

`ReplayDataset` validiert ein versioniertes, monotones Manifest, exakte kalibrierte P5-Graustufenframes und die optionale Kamera-zu-Body-Extrinsik. `ReplayRunner` verwendet dieselben Frontend- und VIO-Klassen sowie dieselbe Regel „IMU bis einschließlich Capture-Zeit vor Frame“. Rotationsvorhersage und visuelle Fusion transformieren über `body_from_camera`; Rotation und metrische Translation des EuRoC-`T_BS` werden vom Konverter übernommen. Ergebniszähler und quantisierte Zustände fließen in einen deterministischen FNV-1a-Regressionshash. Replay-Code wird als separates Support-Target gebaut und vergrößert deshalb die Android-Core-Bibliothek nicht.


## Trajektorienmetriken

Der Evaluator assoziiert Zeitstempel unter einer harten Toleranz, richtet geschätzte Positionen per Horn-Verfahren optional in SE(3) oder Sim(3) aus und berechnet ATE sowie lokale SE(3)-RPE. Sim(3) ist nur Diagnose für monokulare Skalenambiguität; der primäre VIO-Benchmark bleibt SE(3). EuRoC-Ground-Truth und das versionierte ARK-Trajektorienformat werden ohne Laufzeitabhängigkeiten gelesen.


## Bias-linearisierte IMU-Vorintegration

Jeder IMU-Faktor speichert den Bias seines Linearisierungspunkts sowie 3×3-Jakobians für Rotation gegen Gyro-Bias, Geschwindigkeit gegen Accelerometer-/Gyro-Bias und Position gegen beide Biasarten. `corrected(new_bias)` aktualisiert Delta-Rotation, -Geschwindigkeit und -Position erster Ordnung, ohne Rohsamples erneut zu integrieren. Der Fixed-Lag-Smoother fordert vor jeder Residuenberechnung eine Korrektur am Bias des Start-Keyframes an. Bei großen Biasänderungen bleibt vollständige Repropagation notwendig; die First-Order-Korrektur ist bewusst kein Ersatz dafür.


## IMU-Rauschen und Faktorvertrauen

`ImuNoise` beschreibt kontinuierliche Accelerometer- und Gyroskop-Weißrauschdichten in SI-Einheiten pro √Hz. Die Vorintegration propagiert konservative diagonale Varianzen für Rotation, Geschwindigkeit und Position; längere Intervalle und starke spezifische Beschleunigung erhöhen die Unsicherheit. Der Fixed-Lag-Smoother bildet daraus ein auf `[0,1]` begrenztes Vertrauen relativ zu konfigurierbaren Varianzböden. Unsichere Faktoren werden nur herunter-, niemals über ihre manuelle Basisgewichtung hochgewichtet. Die Diagonalnäherung modelliert noch keine vollständigen Kreuzkovarianzen oder Bias-Random-Walks.


## Sparse Map und AR-Anker

Geometrisch akzeptierte Frontend-Tracks werden über ihre stabilen IDs und das kalibrierte Kameramodell in Einheitsstrahlen umgewandelt. Eine Landmark entsteht erst nach ausreichender metrischer Baseline, Parallaxe und positiver Tiefe zunächst als unveröffentlichter Kandidat. Erst eine standardmäßig dritte, zeitlich neuere Sicht muss dessen projizierten Strahlabstand bestätigen; inkonsistente bewegte Kandidaten werden verworfen, bevor sie Ebenen beeinflussen. Folgebeobachtungen veröffentlichter Landmarken müssen weiterhin ein Ray-Residual- und Cheirality-Gate bestehen. Nach standardmäßig drei aufeinanderfolgenden Verstößen wird eine wahrscheinlich dynamische Landmark früh entfernt und muss neu bestätigt werden. Pending-Beobachtungen und Landmarken besitzen außerdem Zeitbudgets; bei Kapazitätsgrenze werden deterministisch schwach beobachtete und alte Punkte entfernt. Ein deterministischer RANSAC erkennt dominante Ebenen aus dem Snapshot. Anschließend minimiert eine Jacobi-Eigenzerlegung der zentrierten Inlier-Kovarianz den orthogonalen Least-Squares-Fehler; nach erneuter Inlier-Klassifikation wird ein zweites Mal verfeinert. Die Normalenrichtung wird kanonisch orientiert, damit dasselbe Input-Set keine Vorzeichenflips erzeugt. Finale Inlier werden in eine stabile 2D-Ebenenbasis projiziert und per monotoner konvexer Hülle auf die tatsächlich beobachtete Fläche begrenzt. Für Raumgeometrie wird das stärkste Modell iterativ extrahiert, seine Inlier werden entfernt und der Rest bis zu einem festen Plane-Budget erneut geschätzt. Ein geometrisches Normalen-/Offset-/Zentrum-Gate ordnet Folgeschätzungen stabilen `PlaneId`s zu. Weil VIO Welt-Z explizit entgegen der Gravitation ausrichtet, klassifiziert ein konfigurierbares Winkel-Gate horizontale und vertikale Flächen. Die vorzeichenkorrigierte Beobachterdistanz trennt Boden von Decke; vertikale Modelle werden Wände, Zwischenwinkel Schrägen. Ohne gültige Beobachterpose bleibt eine horizontale Fläche bewusst `Unknown`. Das ist geometrische Raumsemantik, keine behauptete Objekterkennung. `estimateRoomLayout` wählt Boden und Decke deterministisch nach größter beobachteter Fläche, projiziert ihre Höhen auf Welt-Up und akzeptiert eine Raumhöhe nur innerhalb eines konfigurierten Plausibilitätsintervalls. Wandanzahl und Flächensummen beziehen sich ausschließlich auf beobachtete Hüllen; sie werden weder als geschlossene Raumkontur noch als gesamte reale Wandfläche ausgegeben. Die bisherige dominante Plane bleibt als kompatible Kurzansicht erhalten. Raycasts normalisieren den Strahl, prüfen alle aktiven Ebenen und liefern den nächsten gültigen Treffer; dabei gelten Parallelität, Vorwärtsdistanz, Maximaldistanz und Polygonzugehörigkeit; eine unendliche mathematische Ebene erzeugt daher außerhalb beobachteter Geometrie keinen Treffer. `AnchorStore` hält normalisierte Posen im Weltframe unabhängig vom Rendering. Die Android-Session reicht jeden akzeptierten Frame zusammen mit seiner metrischen `world_from_camera`-Pose genau einmal über die C-ABI in diese Karte ein; Plane-Metadaten, begrenzte Raycasts und Anchor-Operationen stehen über JNI/Kotlin zur Verfügung. Dynamische Objektmasken folgen später.


## Relokalisierung

Persistierte Weltframes werden nicht blind in einen neuen VIO-Ursprung geladen. Der portable Relokalisierungskern schätzt aus metrischen gespeicherten/aktuellen 3D-Korrespondenzen eine starre Transformation `current_from_saved`: deterministischer Drei-Punkt-RANSAC verwirft Ausreißer und kollineare Stichproben, anschließend verfeinert eine Horn-Quaternion-Lösung über alle Inlier. Mindestanzahl, Inlier-Anteil, Distanzschwelle und RMSE sind harte Gates. Nur ein gültiges Ergebnis transformiert Landmarken sowie vollständige Anchor-Posen und wird atomar installiert. Ein dependency-freier orientierter 256-Bit-BRIEF-Pfad beschreibt bestätigte Landmarken; wiederholte Beobachtungen werden je Bit mehrheitsbasiert in einem hart begrenzten Store fusioniert und beim Entfernen der Landmarke mit entfernt. Hamming-Distanz, Ratio-Test und gegenseitiger Best-Match erzeugen lokale Kandidaten zwischen gespeicherter und aktuell bestätigter Sparse Map. Eine skalierbare DBoW-/Embedding-Ortserkennung über große Kartensammlungen bleibt ein späterer Layer.

## Spatial Snapshot

Persistiert werden primäre Landmark- und Anchor-Zustände sowie optional ein begrenzter 256-Bit-Relokalisierungsdeskriptor je Landmarke; Ebenen bleiben ableitbar und werden nach dem Laden neu geschätzt. `ARKMAP01` Version 2 kodiert Zahlen explizit Little Endian, begrenzt Counts und Gesamtbytes, validiert eindeutige IDs und schützt gegen zufällige Korruption mit CRC32. Restore baut zunächst einen vollständigen temporären Zustand und tauscht ihn erst nach erfolgreicher Validierung ein. CRC32 ist keine Authentisierung. `ArKernelMapStore` schützt die Bytes deshalb vor Dateispeicherung mit einem nicht exportierbaren Android-Keystore-AES-256-GCM-Schlüssel, authentisiert seinen versionierten Envelope als AAD und schreibt über `AtomicFile` in den No-Backup-Bereich. Beim Runtime-Restore werden Landmark-Zeitstempel auf die aktuelle Boot-Time umgebunden und Frontend-Feature-IDs kollisionsfrei oberhalb importierter IDs fortgesetzt. Eine alte Karte darf dennoch erst nach externer Relokalisierung in denselben Weltframe geladen werden; AEAD beweist Datenintegrität, nicht räumliche Ausrichtung.

## Tiefe und Surfels

`SurfelMap` bildet die modellunabhängige Grenze für metrische Stereo-, Monokular- oder Sensorsystemtiefe. Ein nativer Dense-Pfad liest über einen bequemen `FloatArray`- oder kopierfreien direkten Native-Order-`FloatBuffer`-Pfad ein exakt kalibriertes, ausgerichtetes metrisches Z-Tiefenbild, überspringt ungültige Werte, unprojiziert verzerrungsbewusst und schätzt Normalen nur über nachbarschaftliche Tiefen ohne Diskontinuität. Frame-synchrone Punkte und optionale Normalen werden mit der exakt zeitzugeordneten Kamerapose in den Weltframe transformiert, in 5-cm-Voxeln konfidenzgewichtet fusioniert und erst nach zwei Beobachtungen veröffentlicht. Batch-, Distanz-, Radius-, Zeit-, Lebensdauer- und Gesamtbudgets sind hart begrenzt; bestätigte Surfels unterstützen einen begrenzten Nächster-Treffer-Ray gegen endliche Normalenscheiben beziehungsweise approximative Kugeln. Für Occlusion-Vorstufen rasterisiert ein separater hart begrenzter CPU-Pfad wahlweise in ein bequemes Array oder kopierfrei in einen wiederverwendbaren direkten Renderer-Buffer nur verlässliche Normalenscheiben durch echte verzerrungsbewusste Pixelstrahl-Schnitte in metrische Kamera-Z-Tiefe; `0` bleibt unbekannt. Android schreibt nur bei aktivem Mapping-Gate. Der Zustand ist transient und noch kein TSDF, Mesh oder Occlusion-Renderer. Details stehen in `DEPTH.md`.

## On-Device-Inferenzgrenze

Android-Modelle implementieren `OnDeviceInferenceBackend` hinter einem validierten `ModelManifest`; Tracking und Karten kennen weder LiteRT noch ONNX Runtime. Für einkanalige metrische Tiefenmodelle komponiert `MetricDepthTensorBackend.createVerified` die verifizierten Modellbytes, einen schmalen `SingleInputTensorRuntime`-Adapter und wiederverwendete begrenzte Tensorpuffer zu einem vollständigen Backend. `SemanticDetectionTensorBackend.createVerified` ergänzt dieselbe Pipeline um explizit konfiguriertes Box-Decoding, Klassenprüfung und deterministische NMS. Echte Multi-Head-Modelle laufen über `DepthAndDetectionTensorBackend` nur einmal und liefern Tiefe sowie Boxen mit identischer Pose und identischem Timestamp; Output-Reihenfolge und Bytezahl bleiben explizit geprüft. Der gemeinsame native `ArKernelTensorPreprocessor` liefert Backend-Adaptern deterministisches, begrenztes Resize/Normalisieren/Quantisieren in direkte NHWC-Puffer; `ArKernelDepthPostprocessor` wandelt direkte einkanalige Modellausgaben mit expliziter Quantisierung, Direct-/Inverse-Transformation und Einheit in kameragroße metrische Tiefe samt Gültigkeitskonfidenz um, ohne eine ML-Laufzeit in den Kern zu ziehen. `BoundedInferenceScheduler` erlaubt exakt einen laufenden Request, keine versteckte Queue, monotone Frames und eine explizite Mindestkadenz. Der Frame hält Kalibrierung, direkten Bildspeicher und die exakt zugehörige Weltpose zusammen. Resultate werden gegen Manifest, Timestamp, Pose, Bildgrenzen, Klassen und metrische Tiefenfähigkeit geprüft, bevor `integrateInferenceOutput` sie durch Mapping-Gate, Tiefensampler und semantische Plane-Projektion leitet. Details: `INFERENCE.md`.

## Semantische 3D-Objekte

`SemanticObjectMap` nimmt ausschließlich metrische Weltbeobachtungen eines externen On-Device-Modells entgegen; eine 2D-Box wird nie als erfundene 3D-Position behandelt. Für flächengetragene Objekte unprojiziert `projectSemanticObservation` einen kalibrierten In-Image-Pixel mit der exakt zeitzugeordneten Kamerapose und akzeptiert nur den nächsten Treffer innerhalb einer beobachteten Plane-Hülle; optional muss diese als Boden oder Wand klassifiziert sein. Klassen- und optionale Track-ID, Konfidenz, Dynamikstatus, Distanz und Zeit steuern die Assoziation. Zwei Beobachtungen bestätigen standardmäßig ein Objekt, Positionen werden konfidenzgewichtet fusioniert, dynamische Instanzen bleiben von statischen getrennt und verfallen schneller. Das harte Objektbudget nutzt deterministische Eviction. Android lässt semantische Writes nur bei aktivem Mapping-Gate zu. Taxonomie, Modellhash, Lizenz und Labelnamen gehören in das Adapter-Manifest, nicht in den Trackingkern; Klasse `0` bleibt Unknown. Semantik ist derzeit transient und aus Datenschutz- und Schema-Gründen nicht Teil von `ARKMAP01`.

## Final reconstruction and asset boundary

Live tracking and final reconstruction are deliberately separate. VIO provides immediate poses; it does not by itself authorize a precision claim. Final environment or isolated-object reconstruction consumes retained full-resolution keyframes and uncertainty, performs global optimization, dense depth, confidence-aware volumetric fusion, meshing, UV generation and multi-view texture baking. `ReconstructionMesh` is the dependency-free canonical asset boundary. `assessReconstructionQuality` prevents metric units from being confused with demonstrated centimetre accuracy. Export and viewing details are in `RECONSTRUCTION.md`.
