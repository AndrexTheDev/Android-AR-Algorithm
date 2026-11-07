# Technische Recherche und Auswahl (Stand 7. November 2026)

## 1. Entscheidung in einem Satz

Wir bauen **keinen Monolithen und keinen Fork eines Forschungsprojekts**, sondern einen kleinen Apache-2.0-Orchestrierungskern mit austauschbaren Backends: Camera2/IMU → eigenes Sensor-Fundament → wahlweise ARCore-Adapter oder freies VIO → mobile Inferenz → semantisch markierte räumliche Karte → Filament/OpenXR-Adapter.

Die Metadaten und Lizenzen wurden direkt in den verlinkten GitHub-Repositories geprüft. Sterne sind nur ein Reifeindikator, kein Qualitätsbeweis.

## 2. Kandidatenmatrix

| Baustein | Lizenz / Einschränkung | Android-Tauglichkeit | Entscheidung |
|---|---|---:|---|
| [ARCore Android SDK](https://github.com/google-ar/arcore-android-sdk) | Samples meist Apache-2.0; zentrale Binärdateien und AAR unter **ARCore Additional Terms**, Kern nicht vollständig OSS | sehr hoch | optionaler Best-Performance-Adapter, niemals harte Kernabhängigkeit |
| [Kimera-VIO](https://github.com/MIT-SPARK/Kimera-VIO) („Chimera“) | BSD-2-Clause | Forschungs-C++, nicht drop-in mobil | mathematische/architektonische Referenz; selektive saubere Neuimplementierung oder späterer Adapter |
| [ORB-SLAM3](https://github.com/UZ-SLAMLab/ORB_SLAM3) | GPL-3.0 | Portierungsaufwand hoch | nicht in permissivem Distributionskern; nur Benchmark/Research-Prozess |
| [OpenVINS](https://github.com/rpng/open_vins) | GPL-3.0 | Forschungsplattform | ebenfalls nur Benchmark/Referenz |
| [RTAB-Map](https://github.com/introlab/rtabmap) | BSD-artige 3-Clause-Lizenz; Android-Code vorhanden | mittel | Kandidat für Loop Closure / persistente Karte, zunächst Adapter statt Vendor-Fork |
| [OpenCV](https://github.com/opencv/opencv) | Apache-2.0 | hoch | Feature-, Kalibrierungs- und Geometrie-Backend |
| [MediaPipe](https://github.com/google-ai-edge/mediapipe) | Apache-2.0 | hoch | optionaler Streaming-Graph/Perception-Adapter |
| [LiteRT](https://github.com/google-ai-edge/LiteRT) | Apache-2.0 | sehr hoch | primärer Android-Inferenzadapter (CPU/GPU/NPU je Gerät) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | hoch | portabler alternativer Inferenzadapter |
| [ncnn](https://github.com/Tencent/ncnn) | BSD-3-Clause plus Drittkomponenten | sehr hoch | kleine, schnelle Fallback-Runtime; Third-Party-Notice auditieren |
| [Depth Anything V2](https://github.com/DepthAnything/Depth-Anything-V2) | Code/Small Apache-2.0; Base/Large/Giant-Gewichte CC-BY-NC-4.0 | Small muss konvertiert/quantisiert werden | nur Small als experimenteller permissiver Tiefenadapter; metrische Skalierung durch VIO |
| [SAM 2](https://github.com/facebookresearch/sam2) | Apache-2.0 Repository; Gewichte separat prüfen | Vollmodell für viele Telefone zu schwer | nicht im Echtzeitpfad; mobile Segmentierer bevorzugen |
| [Filament](https://github.com/google/filament) | Apache-2.0 | sehr hoch | Rendering-Adapter, bewusst außerhalb des Trackingkerns |
| [OpenXR SDK](https://github.com/KhronosGroup/OpenXR-SDK-Source) | Apache-2.0 | hoch auf unterstützten Runtimes | API-/Headset-Interoperabilität, nicht Trackingersatz |
| [SceneView](https://github.com/sceneview/sceneview) | Apache-2.0 | hoch | Referenz/optionale UI-Schicht, nicht Bestandteil des Kernalgorithmus |

## 3. Empfohlene Hybridpipeline

### A. Pose und Geometrie – immer zuerst

1. Camera2 liefert YUV-Frames mit Hardware-Zeitstempel; Android SensorManager liefert Gyro und Accelerometer.
2. Online-Zeitversatz, IMU-Bias und Kamera-IMU-Extrinsik werden kalibriert.
3. KLT/ORB-Feature-Frontend mit pyramidalem Tracking, RANSAC und räumlicher Feature-Verteilung.
4. Sliding-Window-VIO mit IMU-Preintegration und robustem Verlust (Huber); marginalisierte Zustände begrenzen RAM/CPU.
5. Keyframes + Place Recognition + Pose-Graph-Optimierung reduzieren Drift.
6. Nur bei verlässlicher Pose werden Anker, Tiefe und Map integriert.

**Warum nicht ausschließlich neuronales Tracking?** Geometrie ist auf Mittelklassegeräten deterministischer, erklärbarer und energieärmer. ML ergänzt, ersetzt aber nicht die Messgeometrie.

### B. Tiefe ohne LiDAR

Priorität: Mehrbild-Triangulation (metrisch durch VIO) → plane/mesh reconstruction → optional monocular depth prior. Monokulare Netztiefe allein hat Skalen- und Domänenfehler. Der Fusionsschritt richtet inverse Netztiefe robust an triangulierten Punkten aus und speichert pro Zelle Unsicherheit.

### C. „Alles erkennen“ als offene Semantik

Ein geschlossener Detektor kennt nur seine Trainingsklassen. Deshalb drei Ebenen:

- schneller On-device-Detektor für häufige Klassen;
- Segmentierer für Pixelmasken und Occlusion;
- Embedding-/Open-Vocabulary-Adapter für unbekannte Kategorien, nur wenn das Gerät die Rechenlast trägt.

Jede Beobachtung enthält Label, Modellversion, Konfidenz, Zeit und räumliche Unsicherheit. Unbekannt bleibt ein valides Ergebnis. Standardmäßig verlassen Bilder das Gerät nicht.

### D. Outdoor

VIO allein driftet über lange Wege. Optional werden GNSS (mit gemeldeter Accuracy), Magnetometer nur nach Interferenzprüfung, Barometer sowie visuelle Relokalisierung als lose Faktoren eingebunden. ARCore Geospatial kann ein optionaler Service-Adapter sein, ist aber weder offline noch vollständig Open Source. Sonne, Vegetation, bewegte Fahrzeuge und große Tiefen erfordern eigene Outdoor-Benchmarks.

## 4. Lizenzstrategie

- Kern und öffentliche API: Apache-2.0.
- GPL-Komponenten nicht linken oder kopieren; Vergleich nur in isolierten Benchmark-Prozessen.
- Vor Release wird eine SBOM erzeugt und jedes AAR/SO sowie jedes Modellgewicht geprüft.
- Keine Annahme, dass Repository-Lizenz und Modellgewichte identisch sind. Konkretes Beispiel: Depth Anything V2 Small ist Apache-2.0, größere Gewichte sind CC-BY-NC-4.0.
- ARCore wird als optionaler Adapter mit klarer Zustimmung zu den Google-Bedingungen angeboten.

## 5. Nicht ausgewählte Abkürzungen

- **Nur ARCore:** gute Time-to-market, aber kein vollständig offener, unabhängiger Kern.
- **ORB-SLAM3 direkt einbetten:** technisch stark, aber GPL-3.0 bestimmt dann die Distribution.
- **Kimera komplett auf Android kompilieren:** zu große Desktop-/ROS-nahe Abhängigkeitsfläche für den Basiskern.
- **Ein gigantisches Foundation Model:** thermisch, energetisch und lizenzseitig unzuverlässig.
- **Cloud als Voraussetzung:** verletzt Offline-, Kosten- und Datenschutzanforderung.

## 6. Verifizierbare Erfolgskriterien

Keine Aussage „bester am Markt“ ohne reproduzierbare Messung. Mindestmetriken: ATE/RPE und Tracking-Lost-Rate; Relokalisierungszeit; Tiefen-AbsRel; mAP/mIoU; Anchor-Jitter; p50/p95 Framezeit; RAM; Energie/Minute; thermisches Throttling. Geräteklassen: Low/Mid/Flagship, indoor/outdoor, Tag/Nacht, statisch/dynamisch.
