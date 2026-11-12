# Strukturprüfung hochgeladener Feld-Diagnoseläufe

Dieses Dokument enthält **nur Digests und Strukturzahlen** aus neun
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

## Geprüfte Tripel

| Run (UTC) | Policy SHA-256 | Plan SHA-256 | Log SHA-256 | Plan-CRC | Bindung | Events | Plan-Schritte | Ergebnis-Token | Konsistent |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `run-1794350771912.2` (2026-11-10 22:46) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `f24f046767753268c515830f98f6a62f9e4e86d1e88da38cbd30175e5649ff62` | `acdbd723baca0a08c98d0b6e54b4d06e8d75ec7ddf7b70de8383b0860b8ace4c` | `c50de25f` | `crc_bound` | 105 | 20 | `pass.19.of.20.unverified` | ja |
| `run-1794352173246.1` (2026-11-10 23:09) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `ffce98522342bc6f16f3597e234e001368f9f52b5426f1ec0361d5b585a9c38b` | `a10e3b44c2efb59797cbf6b7d3f97de25a43eaf49df1985d412abd8fdc6a8acb` | `0600c70b` | `crc_bound` | 105 | 20 | `pass.20.of.20.unverified` | ja |
| `run-1794472244896.4` (2026-11-12 08:30) | `fc1e30fc0c16d285f1507d8213a9d147575099c387f78478b68327fa9fc36c8e` | `f24f046767753268c515830f98f6a62f9e4e86d1e88da38cbd30175e5649ff62` | `1d083ee362b2088509136dea237b949658c66c79dac7263bcb05a50b6a67db73` | `c50de25f` (Plan-Datei; im Log **nicht** gebunden) | `unbound` | 16 | 0 | `none` | ja |

Alle neun Textdateien bestehen die fail-closed Parser; alle drei Tripel liefern
`review_consistent=true` und `quality_grade=unverified`.

## Befunde

1. **Policy unverändert.** Alle drei `ARKFDPOL1` sind byte-identisch
   (`fc1e30fc…`): `collection_opt_in=true`, alle fünf Safety-Invarianten aktiv,
   `quality_grade=unverified`, `host_redaction_required=true`,
   `host_signature_verification_required=true`,
   `independent_targets_required=true`.
2. **Zwei Planvarianten.** `run-1794350771912.2` und `run-1794472244896.4`
   enthalten byte-identisch den ausgelieferten Standardplan
   `arkernel.ultimate.v2` (`c50de25f`, 20 Schritte). `run-1794352173246.1`
   ist byte-identisch mit `docs/examples/ARKFDPLAN1_redmi_light_tuned.txt`
   (`0600c70b`): genau eine Änderung, `SENSOR_LIVE|5000|2000|50|light` →
   `…|2|light`. Die `PLAN_CRC`-Ereignisse der beiden langen Logs stimmen exakt
   mit den nachgerechneten CRC-Werten überein.
3. **19/20 vs. 20/20 ist eine Plan-Schwelle, kein Geräteurteil.** Im
   Standardlauf liefert der Lichtsensor 21 Samples in 5 s bei ~300 ms Abstand
   und `expectedMin=50`; der Schritt geht fail-closed auf `pass.0`. Mit
   `expectedMin=2` läuft er auf `pass.1`. Zeitstempel-Monotonie, Endlichkeit,
   Lücken und Statistik blieben in beiden Läufen strukturell sauber. Beide
   Ergebnis-Tokens tragen ausdrücklich `unverified`.
4. **Der dritte Lauf hat den Plan nicht ausgeführt.** Er enthält 16 Ereignisse,
   neun `CAPABILITY`-Einträge, ein `GUIDED_STEP` mit
   `user.confirmation.timeout`, `SESSION_TERMINAL` mit
   `pass.0.of.0.unverified`, aber **kein** `PLAN_CRC`, **kein** `PLAN_STEP`
   und **kein** `PLAN_RESULT`. Die mitgelieferte Plan-Datei ist daher
   `unbound`; sie ist vorhanden, aber nicht mit dem Log verknüpft.
5. **Host-Zusicherungen sind nicht erfüllt.** Die Rohlogs enthalten
   Geräte-/Build-Kennungen sowie RAM-/Speicherangaben, obwohl die Policy
   Redaktion verlangt. Es liegt keine Signatur vor, keine unabhängigen
   physischen Targets, keine Zentimeterreferenz und keine geführte
   Bestätigung (`host.reported.not.verified`). Die Digests sind selbstberichtet
   und kryptografisch nicht beglaubigt.
6. **Die neuere Kette ist nicht belegt.** Es liegen keine `ARKCALIB1-`,
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
