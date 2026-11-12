#!/usr/bin/env python3
"""Self-tests for the bounded host-side ARKFD* structural review.

Runs on any plain Python 3 host; no Android SDK, no Kotlin, no network, no
I/O beyond temporary files created by the test itself. Included in Native CI.
"""
from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import verify_arkfd_triple as v

POLICY = "\n".join([
    "ARKFDPOL1",
    "version=1",
    "policy_id=diagnostics.apk.v1",
    "revision=1",
    "bundle_version=ARKFDIAG1",
    "max_bundle_chars=65536",
    "collection_opt_in=true",
    "terminal_text_only=true",
    "host_redaction_required=true",
    "core_upload_disabled=true",
    "host_signature_verification_required=true",
    "independent_targets_required=true",
    "quality_grade=unverified",
])

PLAN_BODY = [
    "ARKFDPLAN1",
    "version=1",
    "plan_id=arkernel.review.synthetic",
    "revision=1",
    "max_events=128",
    "terminal_text_only=true",
    "host_redaction_required=true",
    "core_upload_disabled=true",
    "host_signature_verification_required=true",
    "independent_targets_required=true",
    "quality_grade=unverified",
    "step_count=2",
    "step=CAPABILITY_CATALOG|0|0|0|base",
    "step=THERMAL_SOAK|2000|10|8|thermal",
    "END",
]
PLAN = "\n".join(PLAN_BODY)
PLAN_CRC = v.crc32_hex(PLAN)


def make_log(crc: str = PLAN_CRC, plan_events: bool = True,
             policy_id: str = "diagnostics.apk.v1",
             result: str = "pass.2.of.2.unverified") -> str:
    lines = [
        "ARKFDLOG1",
        "version=1",
        "session_id=session-synthetic",
        "run_id=run-synthetic",
        "policy_id=" + policy_id,
        "quality_grade=unverified",
    ]
    events = ["event=0|1000|RUN_START|apk.open|-|opt.in.apk"]
    if plan_events:
        events += [
            "event=1|2000|PLAN_METADATA|plan.engine|1|arkfdplan1.v1",
            "event=2|3000|PLAN_CRC|plan.crc32|0|crc." + crc,
            "event=3|4000|PLAN_STEP|capability.catalog|0|"
            "count.0.expected.0.label.base.pass.1",
            "event=4|5000|PLAN_STEP|thermal.soak|2000|"
            "count.10.expected.8.label.thermal.pass.1",
            "event=5|6000|PLAN_RESULT|plan.result|2|" + result,
            "event=6|7000|SESSION_TERMINAL|structural.summary|2|" + result,
            "event=7|8000|RUN_END|-|8|-",
        ]
    else:
        events += [
            "event=1|2000|CAPABILITY|android.api|35|release.15",
            "event=2|3000|SESSION_TERMINAL|structural.summary|0|"
            "pass.0.of.0.unverified",
            "event=3|4000|RUN_END|-|4|-",
        ]
    lines.append("event_count=%d" % len(events))
    lines += events + ["END"]
    return "\n".join(lines)


LOG = make_log()


def triple(policy: str = POLICY, plan: str = PLAN, log: str = LOG):
    return v.review_triple(policy, plan, log)


def replace_once(text: str, old: str, new: str) -> str:
    assert old in text, old
    return text.replace(old, new, 1)


def replace_line(text: str, index: int, value: str) -> str:
    lines = text.split("\n")
    lines[index] = value
    return "\n".join(lines)


class ReviewTests(unittest.TestCase):
    def test_good_triple_round_trips_and_is_deterministic(self):
        result = triple()
        self.assertTrue(result.consistent)
        self.assertEqual("NONE", v.parse_review(result.text).reason)
        self.assertEqual(result.text, triple().text)
        self.assertEqual("crc_bound", result.summary["plan_binding"])
        self.assertEqual("true", result.values["plan_step_count_match"])
        self.assertEqual("unverified", result.summary["quality_grade"])
        self.assertEqual(v.sha256_hex(POLICY),
                         result.summary["policy_sha256"])
        self.assertEqual(v.sha256_hex(PLAN),
                         result.summary["plan_sha256"])
        self.assertEqual(v.sha256_hex(LOG),
                         result.summary["log_sha256"])
        self.assertEqual(PLAN_CRC, result.summary["plan_crc32"])
        self.assertTrue(result.text.endswith("END"))
        self.assertFalse(result.text.endswith("END\n"))

    def test_bom_crlf_and_one_final_separator_are_canonical(self):
        base = triple()
        variant = v.review_triple(
            "\ufeff" + POLICY.replace("\n", "\r\n") + "\r\n",
            "\ufeff" + PLAN.replace("\n", "\r\n") + "\r\n",
            "\ufeff" + LOG.replace("\n", "\r\n") + "\r\n")
        self.assertEqual(base.text, variant.text)
        self.assertEqual(base.summary, variant.summary)

    def test_two_final_separators_are_rejected(self):
        with self.assertRaises(ValueError):
            triple(log=LOG + "\n\n")

    def test_unbound_plan_shape_is_still_consistent(self):
        result = triple(log=make_log(plan_events=False))
        self.assertTrue(result.consistent)
        self.assertEqual("unbound", result.summary["plan_binding"])
        self.assertEqual("none", result.values["plan_crc32"])
        self.assertEqual("none", result.values["plan_result_token"])
        self.assertEqual("unavailable",
                         result.values["plan_step_count_match"])

    def test_crc_mismatch_is_inconsistent_but_still_emitted(self):
        changed = replace_once(PLAN, "THERMAL_SOAK|2000|10|8|thermal",
                               "THERMAL_SOAK|2000|10|7|thermal")
        result = triple(plan=changed)
        self.assertFalse(result.consistent)
        self.assertEqual("crc_mismatch", result.summary["plan_binding"])
        self.assertEqual("NONE", v.parse_review(result.text).reason)
        self.assertEqual("false", result.values["review_consistent"])

    def test_policy_id_mismatch_is_inconsistent(self):
        result = triple(log=make_log(policy_id="diagnostics.other.v1"))
        self.assertFalse(result.consistent)
        self.assertEqual("false", result.values["policy_id_match"])

    def test_step_count_mismatch_is_inconsistent(self):
        log = replace_once(LOG, "PLAN_STEP|capability.catalog",
                           "CAPABILITY|capability.catalog")
        result = triple(log=log)
        self.assertFalse(result.consistent)
        self.assertEqual("false", result.values["plan_step_count_match"])

    def test_duplicate_plan_crc_events_are_rejected(self):
        log = replace_once(LOG, "PLAN_RESULT|plan.result",
                           "PLAN_CRC|plan.result")
        # The replacement produces a second crc token only if the detail is
        # shaped as crc.*; use an explicit extra event instead.
        log = replace_once(LOG, "event=5|6000|PLAN_RESULT",
                           "event=5|6000|PLAN_CRC|plan.crc32|0|crc."
                           + PLAN_CRC + "\n"
                           "event=5|6000|PLAN_RESULT")
        with self.assertRaises(ValueError):
            triple(log=log)

    def test_malformed_plan_crc_token_is_rejected(self):
        log = replace_once(LOG, "crc." + PLAN_CRC, "crc.NOTHEX")
        with self.assertRaises(ValueError):
            triple(log=log)

    def test_custom_tokens_are_validated(self):
        self.assertTrue(triple().consistent)
        with self.assertRaises(ValueError):
            v.review_triple(POLICY, PLAN, LOG, review_id="bad!id")
        with self.assertRaises(ValueError):
            v.review_triple(POLICY, PLAN, LOG, device_token="bad!id")
        custom = v.review_triple(POLICY, PLAN, LOG,
                                 review_id="review.host.v1",
                                 device_token="device.host.v1")
        self.assertEqual("review.host.v1", custom.values["review_id"])
        self.assertEqual("device.host.v1", custom.values["device_token"])

    def test_cli_exit_codes_and_explicit_opt_in_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, text in (("pol.txt", POLICY), ("plan.txt", PLAN),
                               ("log.txt", LOG)):
                (root / name).write_text(text, encoding="utf-8")
            out = root / "review.txt"
            with contextlib.redirect_stdout(io.StringIO()):
                code = v.main([
                    str(root / "pol.txt"), str(root / "plan.txt"),
                    str(root / "log.txt"), "--out", str(out)])
            self.assertEqual(0, code)
            self.assertEqual("NONE",
                             v.parse_review(out.read_text(
                                 encoding="utf-8")).reason)
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                json_code = v.main([
                    str(root / "pol.txt"), str(root / "plan.txt"),
                    str(root / "log.txt"), "--json"])
            self.assertEqual(0, json_code)
            self.assertIn('"quality_grade": "unverified"',
                          buffer.getvalue())
            bad = root / "bad.txt"
            bad.write_text("not a policy", encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, v.main([
                    str(bad), str(root / "plan.txt"),
                    str(root / "log.txt")]))
            changed = root / "changed.txt"
            changed.write_text(replace_once(
                PLAN, "THERMAL_SOAK|2000|10|8|thermal",
                "THERMAL_SOAK|2000|10|7|thermal"), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(3, v.main([
                    str(root / "pol.txt"), str(changed),
                    str(root / "log.txt")]))

    def test_source_rejections_fail_closed(self):
        cases = [
            ("policyHeader", replace_line(POLICY, 0, "OTHER1"), None, None),
            ("policyVersion", replace_once(POLICY, "version=1",
                                           "version=2"), None, None),
            ("policyBundle", replace_once(POLICY, "ARKFDIAG1",
                                          "ARKFDIAG2"), None, None),
            ("policyQuality", replace_once(POLICY, "quality_grade=unverified",
                                           "quality_grade=verified"), None, None),
            ("policyUnsafe", replace_once(POLICY, "core_upload_disabled=true",
                                          "core_upload_disabled=false"),
             None, None),
            ("policyBool", replace_once(POLICY, "collection_opt_in=true",
                                        "collection_opt_in=maybe"), None, None),
            ("policyToken", replace_once(POLICY, "diagnostics.apk.v1",
                                         "bad!id"), None, None),
            ("policyKeyOrder", replace_line(POLICY, 1,
                                            "policy_id=diagnostics.apk.v1"),
             None, None),
            ("policyLongLine", replace_line(POLICY, 1, "x" * 257), None, None),
            ("policyBlank", replace_line(POLICY, 1, ""), None, None),
            ("policyWhitespace", replace_once(POLICY, "version=1",
                                              "version=1 x"), None, None),
            ("policyTooLarge", POLICY + "x" * 4096, None, None),
            ("policyExtraLine", POLICY + "\nextra=1", None, None),
            ("planHeader", None, replace_line(PLAN, 0, "OTHER1"), None),
            ("planQuality", None, replace_once(PLAN, "quality_grade=unverified",
                                               "quality_grade=verified"), None),
            ("planUnsafe", None, replace_once(PLAN, "core_upload_disabled=true",
                                              "core_upload_disabled=false"), None),
            ("planUnknownOp", None, replace_once(PLAN, "CAPABILITY_CATALOG",
                                                 "MAGIC_OP"), None),
            ("planBadLabel", None, replace_once(PLAN, "|base", "|bad!base"), None),
            ("planBadDuration", None, replace_once(PLAN, "THERMAL_SOAK|2000",
                                                   "THERMAL_SOAK|200001"), None),
            ("planKeyOrder", None, replace_line(PLAN, 1,
                                                "plan_id=arkernel.review.synthetic"),
             None),
            ("planLongLine", None, replace_line(PLAN, 1, "x" * 257), None),
            ("planBlank", None, replace_line(PLAN, 1, ""), None),
            ("planWhitespace", None, replace_once(PLAN, "version=1",
                                                  "version=1 x"), None),
            ("planMissingEnd", None, replace_line(PLAN, 14, "STOP"), None),
            ("planExtraLine", None, PLAN + "\nextra=1", None),
            ("planTooLarge", None, PLAN + "x" * 4096, None),
            ("logHeader", None, None, replace_line(LOG, 0, "OTHER1")),
            ("logQuality", None, None,
             replace_once(LOG, "quality_grade=unverified",
                          "quality_grade=verified")),
            ("logToken", None, None,
             replace_once(LOG, "run-synthetic", "bad!id")),
            ("logEventCount", None, None,
             replace_once(LOG, "event_count=8", "event_count=7")),
            ("logSequence", None, None,
             replace_once(LOG, "event=3|4000", "event=9|4000")),
            ("logTimestamp", None, None,
             replace_once(LOG, "event=3|4000", "event=3|1000")),
            ("logKind", None, None,
             replace_once(LOG, "PLAN_METADATA|plan.engine",
                          "MAGIC_KIND|plan.engine")),
            ("logCodeToken", None, None,
             replace_once(LOG, "PLAN_METADATA|plan.engine",
                          "PLAN_METADATA|bad!code")),
            ("logValue", None, None,
             replace_once(LOG, "|plan.engine|1|", "|plan.engine|-1|")),
            ("logMissingRunEnd", None, None,
             replace_once(LOG, "event=7|8000|RUN_END",
                          "event=7|8000|CAPABILITY")),
            ("logMisplacedRunStart", None, None,
             replace_once(LOG, "event=1|2000|PLAN_METADATA",
                          "event=1|2000|RUN_START")),
            ("logBlank", None, None, replace_line(LOG, 1, "")),
            ("logLongLine", None, None, replace_line(LOG, 1, "x" * 513)),
            ("logWhitespace", None, None,
             replace_once(LOG, "version=1", "version=1 x")),
            ("logMissingEnd", None, None, replace_line(LOG, 15, "STOP")),
            ("logExtraLine", None, None, LOG + "\nextra=1"),
            ("logTooLarge", None, None, LOG + "x" * 32768),
        ]
        for name, policy, plan, log in cases:
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    triple(policy or POLICY, plan or PLAN, log or LOG)

    def test_review_envelope_rejections_fail_closed(self):
        good = triple().text
        cases = [
            ("reviewHeader", replace_line(good, 0, "OTHER1")),
            ("reviewVersion", replace_once(good, "version=1", "version=2")),
            ("reviewQuality", replace_once(good, "quality_grade=unverified",
                                           "quality_grade=verified")),
            ("reviewDigestCase", replace_once(
                good, "policy_sha256=" + v.sha256_hex(POLICY),
                "policy_sha256=" + v.sha256_hex(POLICY).upper())),
            ("reviewBadCrc", replace_once(good, "plan_crc32=" + PLAN_CRC,
                                          "plan_crc32=ZZZZZZZZ")),
            ("reviewUnsafe", replace_once(
                good, "signature_verification_disabled=true",
                "signature_verification_disabled=false")),
            ("reviewRedaction", replace_once(good, "redaction_performed=false",
                                              "redaction_performed=true")),
            ("reviewUpload", replace_once(
                good, "automatic_upload_disabled=true",
                "automatic_upload_disabled=false")),
            ("reviewManual", replace_once(good, "manual_upload_only=true",
                                          "manual_upload_only=false")),
            ("reviewKeyOrder", replace_line(good, 1,
                                            "review_id=" + good.split("\n")[1].split("=", 1)[1])),
            ("reviewBool", replace_once(good, "policy_id_match=true",
                                        "policy_id_match=maybe")),
            ("reviewBinding", replace_once(good, "plan_binding=crc_bound",
                                           "plan_binding=auto")),
            ("reviewStepMatch", replace_once(
                good, "plan_step_count_match=true",
                "plan_step_count_match=unknown")),
            ("reviewToken", replace_once(good, "review_id=review.",
                                         "review_id=bad!")),
            ("reviewRange", replace_once(good, "event_count=8",
                                         "event_count=129")),
            ("reviewLongLine", replace_line(good, 1, "x" * 257)),
            ("reviewBlank", replace_line(good, 1, "")),
            ("reviewWhitespace", replace_once(good, "version=1",
                                              "version=1 x")),
            ("reviewMissingEnd", replace_line(good, len(good.split("\n")) - 1,
                                              "STOP")),
            ("reviewExtraLine", good + "\nextra=1"),
            ("reviewFinalSeparators", good + "\n\n"),
            ("reviewTooLarge", good + "x" * 8192),
        ]
        for name, text in cases:
            with self.subTest(name=name):
                self.assertNotEqual("NONE", v.parse_review(text).reason)
        self.assertEqual("NONE", v.parse_review(good).reason)
        self.assertEqual("NONE", v.parse_review("\ufeff" + good + "\n").reason)
        print("SOURCE_REJECTION_CASES_OK %d" % len(cases))

    def test_no_assessed_quality_claim(self):
        result = triple()
        self.assertNotIn("quality_grade=verified", result.text)
        self.assertNotIn("quality_grade=assessed", result.text)
        self.assertNotIn("quality_upgrade", result.text)
        self.assertEqual(1, result.text.count("quality_grade=unverified"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
