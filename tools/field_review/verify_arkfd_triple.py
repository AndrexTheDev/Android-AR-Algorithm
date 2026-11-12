#!/usr/bin/env python3
"""Bounded host-side structural review of one ARKFD* text triple.

Reads one ``ARKFDPOL1`` policy, one ``ARKFDPLAN1`` plan and one ``ARKFDLOG1``
run log, re-parses all three with an independent replica of the shipped Kotlin
contracts, canonicalizes them, recomputes SHA-256 digests and the plan CRC-32,
and emits one canonical ``ARKFDREVIEW1`` text.

This is a structural review only. It does NOT verify signatures, redact device
identifiers, upload anything, measure physical targets, calibrate a device,
validate an encrypted keyframe lifecycle or assess reconstruction quality. The
grade stays ``unverified``. Host-supplied raw logs are never copied into the
repository by this tool.

Exit codes: 0 = structurally consistent review, 2 = fail-closed rejection,
3 = review emitted but ``review_consistent=false``.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# --------------------------------------------------------------------------
# Constants mirrored from the shipped Kotlin contracts.
# --------------------------------------------------------------------------
POLICY_KEYS = [
    "version", "policy_id", "revision", "bundle_version",
    "max_bundle_chars", "collection_opt_in", "terminal_text_only",
    "host_redaction_required", "core_upload_disabled",
    "host_signature_verification_required", "independent_targets_required",
    "quality_grade",
]
POLICY_SAFETY = [
    "terminal_text_only", "host_redaction_required", "core_upload_disabled",
    "host_signature_verification_required", "independent_targets_required",
]
PLAN_KEYS = [
    "version", "plan_id", "revision", "max_events",
    "terminal_text_only", "host_redaction_required", "core_upload_disabled",
    "host_signature_verification_required", "independent_targets_required",
    "quality_grade", "step_count",
]
PLAN_SAFETY = PLAN_KEYS[4:9]
SUPPORTED_OPS = {
    "CAPABILITY_CATALOG", "SENSOR_CATALOG", "CAMERA_CATALOG",
    "PERMISSION_CATALOG", "PACKAGE_CATALOG", "MEMORY_SNAPSHOT",
    "DISPLAY_SNAPSHOT", "AUDIO_CATALOG", "CONNECTIVITY_CATALOG",
    "BATTERY_SNAPSHOT", "IMU_STILLNESS", "SENSOR_LIVE", "CAMERA_PREVIEW",
    "CAMERA_LIFECYCLE_REPEAT", "STORAGE_ROUNDTRIP", "COMPUTE_SELFTEST",
    "THERMAL_SOAK",
}
LOG_META = [
    "version", "session_id", "run_id", "policy_id", "quality_grade",
    "event_count",
]
LOG_KINDS = {
    "RUN_START", "CAPABILITY", "PLAN_METADATA", "PLAN_STEP", "PLAN_RESULT",
    "PLAN_CRC", "SENSOR_CATALOG", "CAMERA_CATALOG", "GUIDED_STEP",
    "IMU_STILLNESS", "SENSOR_LIVE", "CAMERA_LIFECYCLE", "CAMERA_FRAMES",
    "CAMERA_REPEAT", "STORAGE_ROUNDTRIP", "STORAGE_REPEAT",
    "COMPUTE_SELFTEST", "COMPUTE_REPEAT", "TIMING", "THERMAL", "THERMAL_SOAK",
    "BATTERY_SNAPSHOT", "DISPLAY_SNAPSHOT", "AUDIO_CATALOG",
    "CONNECTIVITY_CATALOG", "PERMISSION_CATALOG", "PACKAGE_CATALOG",
    "MEMORY_SNAPSHOT", "SERVICE_DEATH", "SESSION_TERMINAL", "RUN_END",
}
REVIEW_HEADER = "ARKFDREVIEW1"
REVIEW_END = "END"
REVIEW_VERSION = "1"
REVIEW_QUALITY = "unverified"
REVIEW_KEYS = [
    "version", "review_id", "device_token",
    "policy_sha256", "plan_sha256", "log_sha256",
    "policy_id", "plan_id", "session_id", "run_id",
    "policy_id_match", "plan_binding", "plan_crc32",
    "plan_steps", "plan_step_events", "plan_step_count_match",
    "event_count", "plan_result_token", "review_consistent",
    "source_reverification_performed", "signature_verification_disabled",
    "redaction_performed", "physical_validation_disabled",
    "automatic_upload_disabled", "manual_upload_only", "quality_grade",
]
REVIEW_TRUE_INVARIANTS = [
    "source_reverification_performed", "signature_verification_disabled",
    "physical_validation_disabled", "automatic_upload_disabled",
    "manual_upload_only",
]
REVIEW_FALSE_INVARIANTS = ["redaction_performed"]

TOKEN128 = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")
TOKEN64 = re.compile(r"[A-Za-z0-9._:-]{1,64}\Z")
PLAN_OP = re.compile(r"[A-Z0-9_]{1,32}\Z")
UNSIGNED = re.compile(r"(?:0|[1-9][0-9]*)\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
CRC8 = re.compile(r"[0-9a-f]{8}\Z")
BOOLS = {"true": True, "false": False}

MAX_POLICY_INPUT, MAX_PLAN_INPUT, MAX_LOG_INPUT = 4_096, 4_096, 32_768
MAX_POLICY_LINE, MAX_PLAN_LINE, MAX_LOG_LINE = 256, 256, 512
MAX_REVIEW_INPUT, MAX_REVIEW_LINE = 8_192, 256


@dataclass(frozen=True)
class ParseResult:
    values: Optional[dict]
    reason: str
    line_number: int = 0


def _fail(reason: str, line: int = 0) -> ParseResult:
    return ParseResult(None, reason, line)


def canonical_lines(text: str) -> List[str]:
    """BOM tolerance + exactly one optional final separator + CRLF tolerance."""
    if text.startswith("\ufeff"):
        text = text[1:]
    if text.endswith("\r\n"):
        text = text[:-2]
    elif text.endswith("\n") or text.endswith("\r"):
        text = text[:-1]
    return re.split(r"\r\n|\n|\r", text) if text else []


def canonical_text(lines: List[str]) -> str:
    return "\n".join(lines)


def _line_error(line: str, max_len: int) -> Optional[str]:
    if len(line) > max_len:
        return "LINE_TOO_LONG"
    if line.strip() == "":
        return "EMPTY_LINE"
    if any(ch.isspace() for ch in line):
        return "INVALID_SCHEMA"
    return None


def _kv(line: str, key: str, max_len: int, line_number: int) -> ParseResult:
    error = _line_error(line, max_len)
    if error:
        return _fail(error, line_number)
    eq = line.find("=")
    if eq <= 0 or eq == len(line) - 1:
        return _fail("INVALID_SCHEMA", line_number)
    if line[:eq] != key:
        return _fail("INVALID_SCHEMA", line_number)
    return ParseResult({"value": line[eq + 1:]}, "NONE", line_number)


def _uint(value: str) -> Optional[int]:
    return int(value) if UNSIGNED.fullmatch(value) else None


def parse_policy(text: str) -> ParseResult:
    if len(text) > MAX_POLICY_INPUT:
        return _fail("INPUT_TOO_LARGE")
    lines = canonical_lines(text)
    if not lines:
        return _fail("EMPTY_POLICY")
    head = lines[0].rstrip("\r")
    if head != "ARKFDPOL1" or any(c.isspace() for c in head):
        return _fail("INVALID_HEADER", 1)
    if len(lines) != len(POLICY_KEYS) + 1:
        return _fail("INVALID_SCHEMA")
    values: Dict[str, str] = {}
    for i, key in enumerate(POLICY_KEYS):
        result = _kv(lines[i + 1].rstrip("\r"), key,
                     MAX_POLICY_LINE, i + 2)
        if result.reason != "NONE":
            return result
        values[key] = result.values["value"]
    if values["version"] != "1":
        return _fail("INVALID_VALUE")
    if values["bundle_version"] != "ARKFDIAG1":
        return _fail("INVALID_VALUE")
    if values["quality_grade"] != REVIEW_QUALITY:
        return _fail("INVALID_QUALITY")
    if not TOKEN128.fullmatch(values["policy_id"]):
        return _fail("INVALID_VALUE")
    for key in ("revision", "max_bundle_chars"):
        number = _uint(values[key])
        if number is None:
            return _fail("INVALID_VALUE")
        if key == "revision" and not 1 <= number <= 1_000_000:
            return _fail("INVALID_VALUE")
        if key == "max_bundle_chars" and not 1 <= number <= 65_536:
            return _fail("INVALID_VALUE")
    for key in ("collection_opt_in",) + tuple(POLICY_SAFETY):
        if values[key] not in BOOLS:
            return _fail("INVALID_VALUE")
    for key in POLICY_SAFETY:
        if not BOOLS[values[key]]:
            return _fail("UNSAFE_INVARIANT")
    return ParseResult(values, "NONE")


def parse_plan(text: str) -> ParseResult:
    if len(text) > MAX_PLAN_INPUT:
        return _fail("INPUT_TOO_LARGE")
    lines = canonical_lines(text)
    if not lines:
        return _fail("EMPTY_PLAN")
    head = lines[0].rstrip("\r")
    if (head != "ARKFDPLAN1" or any(c.isspace() for c in head)
            or len(head) > MAX_PLAN_LINE):
        return _fail("INVALID_HEADER", 1)
    if len(lines) < len(PLAN_KEYS) + 3:
        return _fail("INVALID_SCHEMA")
    values: Dict[str, str] = {}
    for i, key in enumerate(PLAN_KEYS):
        result = _kv(lines[i + 1].rstrip("\r"), key,
                     MAX_PLAN_LINE, i + 2)
        if result.reason != "NONE":
            return result
        values[key] = result.values["value"]
    if values["version"] != "1":
        return _fail("INVALID_VALUE")
    if values["quality_grade"] != REVIEW_QUALITY:
        return _fail("INVALID_QUALITY")
    for key in PLAN_SAFETY:
        if values[key] != "true":
            return _fail("UNSAFE_INVARIANT")
    if not TOKEN128.fullmatch(values["plan_id"]):
        return _fail("INVALID_TOKEN")
    for key, low, high in (("revision", 1, 1_000_000),
                           ("max_events", 2, 128),
                           ("step_count", 1, 24)):
        number = _uint(values[key])
        if number is None or not low <= number <= high:
            return _fail("INVALID_VALUE")
    step_count = int(values["step_count"])
    start = len(PLAN_KEYS) + 1
    if len(lines) != start + step_count + 1:
        return _fail("INVALID_SCHEMA")
    steps = []
    for i in range(step_count):
        line = lines[start + i].rstrip("\r")
        error = _line_error(line, MAX_PLAN_LINE)
        if error:
            return _fail(error, start + i + 1)
        if not line.startswith("step="):
            return _fail("INVALID_STEP", start + i + 1)
        fields = line[5:].split("|")
        if len(fields) != 5:
            return _fail("INVALID_STEP", start + i + 1)
        op, duration, count, expected, label = fields
        if not PLAN_OP.fullmatch(op):
            return _fail("INVALID_TOKEN", start + i + 1)
        if op not in SUPPORTED_OPS:
            return _fail("UNKNOWN_OP", start + i + 1)
        for value in (duration, count, expected):
            if _uint(value) is None:
                return _fail("INVALID_VALUE", start + i + 1)
        if (int(duration) > 120_000 or int(count) > 262_144
                or int(expected) > 262_144):
            return _fail("INVALID_VALUE", start + i + 1)
        if not TOKEN128.fullmatch(label):
            return _fail("INVALID_TOKEN", start + i + 1)
        steps.append(fields)
    end = lines[start + step_count].rstrip("\r")
    error = _line_error(end, MAX_PLAN_LINE)
    if error:
        return _fail(error, start + step_count + 1)
    if end != REVIEW_END:
        return _fail("INVALID_SCHEMA", start + step_count + 1)
    return ParseResult({"values": values, "steps": steps}, "NONE")


def parse_log(text: str) -> ParseResult:
    if len(text) > MAX_LOG_INPUT:
        return _fail("INPUT_TOO_LARGE")
    lines = canonical_lines(text)
    if not lines:
        return _fail("EMPTY_LOG")
    head = lines[0].rstrip("\r")
    if (head != "ARKFDLOG1" or any(c.isspace() for c in head)
            or len(head) > MAX_LOG_LINE):
        return _fail("INVALID_HEADER", 1)
    if len(lines) < len(LOG_META) + 3:
        return _fail("INVALID_SCHEMA")
    values: Dict[str, str] = {}
    for i, key in enumerate(LOG_META):
        result = _kv(lines[i + 1].rstrip("\r"), key,
                     MAX_LOG_LINE, i + 2)
        if result.reason != "NONE":
            return result
        values[key] = result.values["value"]
    if values["version"] != "1":
        return _fail("INVALID_VALUE")
    if values["quality_grade"] != REVIEW_QUALITY:
        return _fail("INVALID_QUALITY")
    for key in ("session_id", "run_id", "policy_id"):
        if not TOKEN128.fullmatch(values[key]):
            return _fail("INVALID_TOKEN")
    event_count = _uint(values["event_count"])
    if event_count is None or not 2 <= event_count <= 128:
        return _fail("INVALID_VALUE")
    start = len(LOG_META) + 1
    if len(lines) != start + event_count + 1:
        return _fail("INVALID_SCHEMA")
    events, previous = [], -1
    for i in range(event_count):
        line = lines[start + i].rstrip("\r")
        error = _line_error(line, MAX_LOG_LINE)
        if error:
            return _fail(error, start + i + 1)
        if not line.startswith("event="):
            return _fail("INVALID_SCHEMA", start + i + 1)
        fields = line[6:].split("|")
        if len(fields) != 6:
            return _fail("INVALID_SCHEMA", start + i + 1)
        seq, timestamp, kind, code, value, detail = fields
        seq_number = _uint(seq)
        if seq_number is None:
            return _fail("INVALID_VALUE", start + i + 1)
        if seq_number != i:
            return _fail("INVALID_ORDER", start + i + 1)
        ts_number = _uint(timestamp)
        if ts_number is None:
            return _fail("INVALID_VALUE", start + i + 1)
        if ts_number <= previous:
            return _fail("INVALID_ORDER", start + i + 1)
        previous = ts_number
        if kind not in LOG_KINDS:
            return _fail("INVALID_VALUE", start + i + 1)
        for token in (code, detail):
            if token != "-" and not TOKEN128.fullmatch(token):
                return _fail("INVALID_TOKEN", start + i + 1)
        if value != "-" and _uint(value) is None:
            return _fail("INVALID_VALUE", start + i + 1)
        events.append({"kind": kind, "code": code,
                       "value": value, "detail": detail})
    end = lines[start + event_count].rstrip("\r")
    error = _line_error(end, MAX_LOG_LINE)
    if error:
        return _fail(error, start + event_count + 1)
    if end != REVIEW_END:
        return _fail("INVALID_SCHEMA", start + event_count + 1)
    kinds = [event["kind"] for event in events]
    if (kinds[0] != "RUN_START" or kinds[-1] != "RUN_END" or
            any(k in ("RUN_START", "RUN_END") for k in kinds[1:-1])):
        return _fail("UNSAFE_EVENT", start + 1)
    return ParseResult({"values": values, "events": events}, "NONE")


def parse_review(text: str) -> ParseResult:
    """Fail-closed structural check of one canonical ARKFDREVIEW1 envelope."""
    if len(text) > MAX_REVIEW_INPUT:
        return _fail("INPUT_TOO_LARGE")
    lines = canonical_lines(text)
    if not lines:
        return _fail("EMPTY_REVIEW")
    head = lines[0].rstrip("\r")
    if (head != REVIEW_HEADER or any(c.isspace() for c in head)
            or len(head) > MAX_REVIEW_LINE):
        return _fail("INVALID_HEADER", 1)
    if len(lines) != len(REVIEW_KEYS) + 2:
        return _fail("INVALID_SCHEMA")
    values: Dict[str, str] = {}
    for i, key in enumerate(REVIEW_KEYS):
        result = _kv(lines[i + 1].rstrip("\r"), key,
                     MAX_REVIEW_LINE, i + 2)
        if result.reason != "NONE":
            return result
        values[key] = result.values["value"]
    if values["version"] != REVIEW_VERSION:
        return _fail("INVALID_VALUE")
    if values["quality_grade"] != REVIEW_QUALITY:
        return _fail("INVALID_QUALITY")
    for key in ("review_id", "device_token", "policy_id", "plan_id",
                "session_id", "run_id"):
        if not TOKEN64.fullmatch(values[key]):
            return _fail("INVALID_TOKEN")
    if (values["plan_result_token"] != "none" and
            not TOKEN64.fullmatch(values["plan_result_token"])):
        return _fail("INVALID_TOKEN")
    for key in ("policy_sha256", "plan_sha256", "log_sha256"):
        if not HEX64.fullmatch(values[key]):
            return _fail("INVALID_DIGEST")
    if values["plan_crc32"] != "none" and not CRC8.fullmatch(values["plan_crc32"]):
        return _fail("INVALID_CRC")
    for key in ("policy_id_match", "review_consistent"):
        if values[key] not in BOOLS:
            return _fail("INVALID_BOOLEAN")
    if values["plan_binding"] not in ("crc_bound", "crc_mismatch", "unbound"):
        return _fail("INVALID_BINDING")
    if values["plan_step_count_match"] not in ("true", "false", "unavailable"):
        return _fail("INVALID_VALUE")
    for key in REVIEW_TRUE_INVARIANTS:
        if values[key] != "true":
            return _fail("UNSAFE_INVARIANT")
    for key in REVIEW_FALSE_INVARIANTS:
        if values[key] != "false":
            return _fail("UNSAFE_INVARIANT")
    for key, low, high in (("plan_steps", 1, 24),
                           ("plan_step_events", 0, 128),
                           ("event_count", 2, 128)):
        number = _uint(values[key])
        if number is None or not low <= number <= high:
            return _fail("INVALID_VALUE")
    end = lines[len(REVIEW_KEYS) + 1].rstrip("\r")
    error = _line_error(end, MAX_REVIEW_LINE)
    if error:
        return _fail(error, len(REVIEW_KEYS) + 2)
    if end != REVIEW_END:
        return _fail("INVALID_SCHEMA", len(REVIEW_KEYS) + 2)
    return ParseResult(values, "NONE")


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def crc32_hex(text: str) -> str:
    return "%08x" % (zlib.crc32(text.encode("utf-8")) & 0xFFFFFFFF)


@dataclass(frozen=True)
class TripleReview:
    text: str
    values: dict
    summary: dict
    consistent: bool


def review_triple(
    policy_text: str,
    plan_text: str,
    log_text: str,
    review_id: Optional[str] = None,
    device_token: Optional[str] = None,
) -> TripleReview:
    """Re-parse, canonicalize and structurally review one text triple.

    Raises ``ValueError`` on any fail-closed rejection; no partial review is
    returned. No source file is written and nothing is uploaded.
    """
    policy_lines = canonical_lines(policy_text)
    plan_lines = canonical_lines(plan_text)
    log_lines = canonical_lines(log_text)
    for label, lines in (("ARKFDPOL1", policy_lines),
                         ("ARKFDPLAN1", plan_lines),
                         ("ARKFDLOG1", log_lines)):
        if not lines:
            raise ValueError("%s %s" % (label, "EMPTY_INPUT"))
        if lines[-1] == "":
            raise ValueError("%s EMPTY_LINE at line %d" %
                             (label, len(lines)))
    policy_canonical = canonical_text(policy_lines)
    plan_canonical = canonical_text(plan_lines)
    log_canonical = canonical_text(log_lines)

    parsed = (
        ("ARKFDPOL1", parse_policy(policy_canonical)),
        ("ARKFDPLAN1", parse_plan(plan_canonical)),
        ("ARKFDLOG1", parse_log(log_canonical)),
    )
    for label, result in parsed:
        if result.reason != "NONE":
            raise ValueError("%s %s at line %d" %
                             (label, result.reason, result.line_number))
    policy_result, plan_result, log_result = (
        parsed[0][1], parsed[1][1], parsed[2][1])

    policy_values = policy_result.values
    plan_values = plan_result.values["values"]
    plan_steps = plan_result.values["steps"]
    log_values = log_result.values["values"]
    events = log_result.values["events"]

    policy_digest = sha256_hex(policy_canonical)
    plan_digest = sha256_hex(plan_canonical)
    log_digest = sha256_hex(log_canonical)
    plan_crc = crc32_hex(plan_canonical)

    crc_tokens = [
        event["detail"] for event in events
        if event["kind"] == "PLAN_CRC" and event["code"] == "plan.crc32"
    ]
    if len(crc_tokens) > 1:
        raise ValueError("ARKFDLOG1 duplicate PLAN_CRC events")
    if crc_tokens:
        match = re.fullmatch(r"crc\.([0-9a-f]{8})", crc_tokens[0])
        if match is None:
            raise ValueError("ARKFDLOG1 malformed PLAN_CRC token")
        plan_binding = ("crc_bound" if match.group(1) == plan_crc
                        else "crc_mismatch")
    else:
        plan_binding = "unbound"

    plan_step_events = sum(1 for event in events
                           if event["kind"] == "PLAN_STEP")
    if plan_binding == "unbound" and plan_step_events == 0:
        step_match = "unavailable"
    elif plan_step_events == len(plan_steps):
        step_match = "true"
    else:
        step_match = "false"

    result_tokens = [
        event["detail"] for event in events
        if event["kind"] == "PLAN_RESULT"
    ]
    if len(result_tokens) > 1:
        raise ValueError("ARKFDLOG1 duplicate PLAN_RESULT events")
    plan_result_token = result_tokens[0] if result_tokens else "none"

    policy_id_match = (policy_values["policy_id"] ==
                       log_values["policy_id"])
    event_count = int(log_values["event_count"])
    event_count_consistent = event_count == len(events)
    consistent = bool(
        policy_id_match and event_count_consistent and
        plan_binding != "crc_mismatch" and
        step_match != "false" and
        (plan_binding != "crc_bound" or step_match == "true")
    )

    resolved_review_id = review_id or ("review." + log_digest[:16])
    resolved_device_token = device_token or "device.unknown"
    if not TOKEN64.fullmatch(resolved_review_id):
        raise ValueError("Invalid review_id token")
    if not TOKEN64.fullmatch(resolved_device_token):
        raise ValueError("Invalid device_token token")

    values = {
        "version": REVIEW_VERSION,
        "review_id": resolved_review_id,
        "device_token": resolved_device_token,
        "policy_sha256": policy_digest,
        "plan_sha256": plan_digest,
        "log_sha256": log_digest,
        "policy_id": policy_values["policy_id"],
        "plan_id": plan_values["plan_id"],
        "session_id": log_values["session_id"],
        "run_id": log_values["run_id"],
        "policy_id_match": "true" if policy_id_match else "false",
        "plan_binding": plan_binding,
        "plan_crc32": plan_crc if plan_binding != "unbound" else "none",
        "plan_steps": str(len(plan_steps)),
        "plan_step_events": str(plan_step_events),
        "plan_step_count_match": step_match,
        "event_count": str(event_count),
        "plan_result_token": plan_result_token,
        "review_consistent": "true" if consistent else "false",
        "source_reverification_performed": "true",
        "signature_verification_disabled": "true",
        "redaction_performed": "false",
        "physical_validation_disabled": "true",
        "automatic_upload_disabled": "true",
        "manual_upload_only": "true",
        "quality_grade": REVIEW_QUALITY,
    }
    lines = ([REVIEW_HEADER] +
             ["%s=%s" % (key, values[key]) for key in REVIEW_KEYS] +
             [REVIEW_END])
    text = "\n".join(lines)
    if len(text) > MAX_REVIEW_INPUT or any(
            len(line) > MAX_REVIEW_LINE for line in lines):
        raise ValueError("ARKFDREVIEW1 exceeds its bounded size")
    parsed = parse_review(text)
    if parsed.reason != "NONE":
        raise ValueError("ARKFDREVIEW1 self-check failed: %s" % parsed.reason)
    summary = {
        "policy_sha256": policy_digest,
        "plan_sha256": plan_digest,
        "log_sha256": log_digest,
        "plan_crc32": plan_crc,
        "policy_id_match": policy_id_match,
        "plan_binding": plan_binding,
        "plan_steps": len(plan_steps),
        "plan_step_events": plan_step_events,
        "event_count": event_count,
        "plan_result_token": plan_result_token,
        "review_consistent": consistent,
        "quality_grade": REVIEW_QUALITY,
    }
    return TripleReview(text, values, summary, consistent)


def read_text(path: str) -> str:
    raw = Path(path).read_bytes()
    return raw.decode("utf-8")


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Bounded host-side structural review of one ARKFD* triple")
    parser.add_argument("policy", help="path to ARKFDPOL1 text")
    parser.add_argument("plan", help="path to ARKFDPLAN1 text")
    parser.add_argument("log", help="path to ARKFDLOG1 text")
    parser.add_argument("--review-id", default=None,
                        help="opaque review token (default derived from log digest)")
    parser.add_argument("--device-token", default=None,
                        help="opaque device token (default device.unknown)")
    parser.add_argument("--out", default=None,
                        help="explicit opt-in output path (default stdout only)")
    parser.add_argument("--json", action="store_true",
                        help="print the structural summary as JSON")
    args = parser.parse_args(argv)
    try:
        result = review_triple(
            read_text(args.policy), read_text(args.plan),
            read_text(args.log), args.review_id, args.device_token)
    except (ValueError, UnicodeDecodeError, OSError) as error:
        print("REJECTED %s" % error, file=sys.stderr)
        return 2
    if args.out:
        Path(args.out).write_text(result.text + "\n", encoding="utf-8")
    if args.json:
        print(json.dumps(result.summary, sort_keys=True, indent=2))
    else:
        print(result.text)
    if not result.consistent:
        print("REVIEW_INCONSISTENT", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
