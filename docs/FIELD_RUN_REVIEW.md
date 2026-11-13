# Strukturprüfung hochgeladener Feld-Diagnoseläufe

Dieses Dokument enthält **nur Digests und Strukturzahlen** aus zwölf
hochgeladenen Textdateien. Die Rohlogs werden nicht in das Repository
übernommen; Gerätefingerprints, Build-Kennungen und freie Texte werden hier
nicht wiederholt. Es gibt keine Signaturprüfung, keine Redaktion der Quellen,
keinen automatischen Upload, keine physischen Referenzmessungen und keine
Qualitätsbewertung. Status: **`unverified`**.

## Methode

`tools/field_review/verify_arkfd_triple.py` liest je ein `ARKFDPOL1`-,
`ARKFDPLAN1`- und `ARKFDLOG1`-Tripel, parst alle drei Texte mit einer
unabhängigen Replik der ausgelieferten Kotlin-Verträge erneut, kanonisiert sie
(BOM/CRLF-Toleranz, genau ein optionaler Schluss-Separator), berechnet
SHA-256 über die kanonischen Texte sowie CRC-32 über den kanonischen Plan und
gibt ein fail-closed `ARKFDREVIEW1` aus. Geprüft werden ausschließlich
Strukturbindungen: `policy_id`-Gleichheit, Plan-CRC (`crc_bound`,
`crc_mismatch`, `unbound`), Plan-Schritt-/Ereigniszahlen und der opake,
host-berichtete Ergebnis-Token. `review_consistent=true` bedeutet **nicht**
„Messung richtig", sondern nur „Texte widerspruchsfrei zueinander".

`--batch DIR` gruppiert 1–16 vollständige Tripel nach ihrem opaken
Dateinamen-Suffix und gibt eine deterministische Strukturübersicht als JSON
aus: pro Lauf Digests, Bindungszustand, Zähler und Ergebnis-Token, plus
`run_count`, `unique_log_digests`, `unique_plan_digests`,
`duplicate_log_digests` und `all_consistent`. Unvollständige Gruppen lehnen den
ganzen Stapel fail-closed ab. Es werden keine Gerätekennungen, keine
`session_id`, keine `device_token`, keine Event-Details und keine Rohlogs
ausgegeben.

## Geprüfte Tripel

| Run (UTC) | Policy SHA-256 | Plan SHA-256 | Log SHA-256 | Plan-CRC | Bindung | Events | Plan-Schritte | Ergebnis-Token | Konsistent |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `run-1794350771912.2` (2026-11-10 22:46) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `f24f046767753268c515830f98f6a62f9e4e86d1e88da38cbd30175e5649ff62` | `acdbd723baca0a08c98d0b6e54b4d06e8d75ec7ddf7b70de8383b0860b8ace4c` | `c50de25f` | `crc_bound` | 105 | 20 | `pass.19.of.20.unverified` | ja |
| `run-1794352173246.1` (2026-11-10 23:09) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `ffce98522342bc6f16f3597e234e001368f9f52b5426f1ec0361d5b585a9c38b` | `a10e3b44c2efb59797cbf6b7d3f97de25a43eaf49df1985d412abd8fdc6a8acb` | `0600c70b` | `crc_bound` | 105 | 20 | `pass.20.of.20.unverified` | ja |
| `run-1794472244896.4` (2026-11-12 08:30) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `f24f046767753268c515830f98f6a62f9e4e86d1e88da38cbd30175e5649ff62` | `1d083ee362b2088509136dea237b949658c66c79dac7263bcb05a50b6a67db73` | `c50de25f` (Plan-Datei; im Log **nicht** gebunden) | `unbound` | 16 | 0 | `none` | ja |
| `run-1794476069939.2` (2026-11-12 09:34) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `f24f046767753268c515830f98f6a62f9e4e86d1e88da38cbd30175e5649ff62` | `3ff006ea0f7ed013f3cd88b7077c8fdb5d5c043bbb57ac78a12796ad41deb58c` | `c50de25f` | `crc_bound` | 105 | 20 | `pass.19.of.20.unverified` | ja |

Alle zwölf Textdateien bestehen die fail-closed Parser; alle vier Tripel
liefern `review_consistent=true` und `quality_grade=unverified`. Der Stapel
liefert `run_count=4`, `unique_log_digests=4`, `unique_plan_digests=2`,
`duplicate_log_digests=false`, `all_consistent=true`.

## Befunde

1. **Policy unverändert.** Alle vier `ARKFDPOL1` sind byte-identisch
   (`fc1e30fc…`): `collection_opt_in=true`, alle fünf Safety-Invarianten aktiv,
   `quality_grade=unverified`, `host_redaction_required=true`,
   `host_signature_verification_required=true`,
   `independent_targets_required=true`.
2. **Zwei Planvarianten.** `run-1794350771912.2`, `run-1794472244896.4` und
   `run-1794476069939.2` enthalten byte-identisch den ausgelieferten
   Standardplan `arkernel.ultimate.v2` (`c50de25f`, 20 Schritte).
   `run-1794352173246.1` ist byte-identisch mit
   `docs/examples/ARKFDPLAN1_redmi_light_tuned.txt` (`0600c70b`): genau eine
   Änderung, `SENSOR_LIVE|5000|2000|50|light` → `…|2|light`. Die
   `PLAN_CRC`-Ereignisse der drei gebundenen Logs stimmen exakt mit den
   nachgerechneten CRC-Werten überein.
3. **19/20 vs. 20/20 ist eine Plan-Schwelle, kein Geräteurteil – jetzt bei
   zwei sehr unterschiedlichen Lichtverhältnissen bestätigt.** Der
   Lichtsensor liefert in allen drei vollständigen Läufen exakt **21 Samples
   in 5 s bei ~300 ms Abstand**: bei mittleren Helligkeiten `6.9048` und
   `6.1619` (10.11., abends) sowie `307.7952` (12.11., vormittags). Die
   Samplezahl ändert sich mit der Helligkeit **nicht**; nur der
   host-gewählte `expectedMin=50` lässt den Schritt fail-closed auf `pass.0`
   laufen. Mit `expectedMin=2` läuft er auf `pass.1`. Zeitstempel-Monotonie,
   Endlichkeit, Lücken und Statistik blieben in allen drei Läufen strukturell
   sauber. Das ist eine portable Aussage über eine Texterwartung, keine
   Geräteanforderung und keine Qualitätsaussage; alle Ergebnis-Tokens tragen
   ausdrücklich `unverified`.
4. **Der dritte Lauf hat den Plan nicht ausgeführt; der vierte schon.** Lauf
   `.4` enthält 16 Ereignisse, neun `CAPABILITY`-Einträge, ein `GUIDED_STEP`
   mit `user.confirmation.timeout`, `SESSION_TERMINAL` mit
   `pass.0.of.0.unverified`, aber **kein** `PLAN_CRC`, **kein** `PLAN_STEP`
   und **kein** `PLAN_RESULT`; seine Plan-Datei ist `unbound`. Der neue Lauf
   `run-1794476069939.2` zeigt dagegen `user.confirmed.plan=1`, 105 Ereignisse
   und `crc_bound`. Die geführte Bestätigung hat also funktioniert; der
   Timeout war eine einmalige Interaktion, kein Codefehler.
5. **Strukturelle Wiederholbarkeit.** Die drei vollständigen Läufe haben die
   identische Ereignisarten-Verteilung (105 Events, 20 `PLAN_STEP`, 25
   `SENSOR_CATALOG`, 11 `THERMAL_SOAK`, je 1 `PLAN_CRC`/`PLAN_RESULT`).
   Speicher-Roundtrip-CRC `11382463`, 3/3 Compute-Selbsttests, 30
   Kamera-Frames und 4000 IMU-Samples sind in allen dreien gleich; die
   Zahlenwerte von Akku, freiem Speicher, Thermik-Headroom und
   Sensormittelwerten variieren erwartungsgemäß. Das ist
   Strukturkonstanz, kein Genauigkeitsbeweis.
6. **Host-Zusicherungen sind nicht erfüllt.** Die Rohlogs enthalten
   Geräte-/Build-Kennungen sowie RAM-/Speicherangaben, obwohl die Policy
   Redaktion verlangt. Es liegt keine Signatur vor, keine unabhängigen
   physischen Targets, keine Zentimeterreferenz und keine verifizierte
   geführte Bestätigung (`host.reported.not.verified`). Die Digests sind
   selbstberichtet und kryptografisch nicht beglaubigt.
7. **Die neuere Kette ist nicht belegt.** Es liegen keine `ARKCALIB1-`,
   `ARKADAPT1-`, `ARKCAMPAIGN1-`, `ARKBIND1-`, `ARKCROSS1-`, `ARKRETENT1-`,
   `ARKFDPACK1-` oder `ARKREDACT1`-Dateien vor, keine Messungen mit Zollstock
   oder Baustellenreferenz und kein signierter Upload-Ablauf. Zwei ebenfalls
   vorhandene Screenshots wurden nicht ausgewertet und nicht übernommen.

## Grenzen

Diese Prüfung ist eine **Strukturprüfung**, keine physikalische Validierung. Sie
beweist keine Genauigkeit, keine Zentimetergenauigkeit, keine
ARCore-Äquivalenz, keine universelle oder automatische Kalibrierung, keine
Gerätefarm-Evidenz und keine Qualitätsaufstufung. Es wurden keine Bilder,
Pixel, Posen, Trajektorien oder dichten Geometrien erfasst. Der maßgebliche
Qualitätspfad bleibt `assessReconstructionQuality` mit
`evaluateHeldOutDimensions`; `quality_grade` bleibt **`unverified`**. Tatsächliche
feldtaugliche Erfassungs-, Redaktions-, Signatur- und Upload-Abläufe bleiben
offen.
