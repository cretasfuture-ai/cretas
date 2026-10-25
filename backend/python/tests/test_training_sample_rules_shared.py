"""Python and Java apply the same training-sample rules (2026-10-24).

Both run smartbi/services/training_sample_rule_cases.json (the Java side in
TrainingConsentGateContractTest); this test also pins Java's PARAMETER_MARKERS to
the Python tuple so the two copies cannot drift apart.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from smartbi.services import training_consent as tc

_PY_ROOT = Path(__file__).resolve().parents[1]
_CASES = _PY_ROOT / "smartbi" / "services" / "training_sample_rule_cases.json"
_JAVA_RULES = (_PY_ROOT.parent / "java" / "cretas-api" / "src" / "main" / "java" / "com" / "cretas"
               / "aims" / "service" / "consent" / "TrainingSampleRules.java")


def _cases():
    return json.loads(_CASES.read_text(encoding="utf-8"))


def test_utterance_cases():
    cases = _cases()["utterance_cases"]
    assert len(cases) >= 10
    for c in cases:
        keep, text = tc.desensitize_utterance("private" if c["authorized"] else "none", c["text"])
        assert (text if keep else None) == c["stored"], c["text"]


def test_qa_cases_include_both_outcomes():
    cases = _cases()["qa_cases"]
    assert {c["blocked"] for c in cases} == {True, False}
    for c in cases:
        assert tc.qa_has_blocked_content(c["input"], c["answer"]) == c["blocked"], c


def test_java_parameter_markers_match_python():
    src = _JAVA_RULES.read_text(encoding="utf-8")
    block = re.search(r"PARAMETER_MARKERS = List\.of\((.*?)\);", src, re.S)
    assert block, "PARAMETER_MARKERS not found in TrainingSampleRules.java"
    java = tuple(re.findall(r'"([^"]*)"', block.group(1)))
    assert java == tc.PARAMETER_MARKERS


def test_every_parameter_marker_blocks_an_utterance():
    for m in tc.PARAMETER_MARKERS:
        assert tc.utterance_has_blocked_content(f"问一下{m}的情况"), m


def _java_string_constant(src: str, name: str) -> str:
    """Concatenated value of `static final String NAME = "..." + "...";` in Java source."""
    m = re.search(rf"static final String {name} =(.*?);", src, re.S)
    assert m, f"{name} not found in TrainingSampleRules.java"
    return "".join(re.findall(r'"([^"]*)"', m.group(1)))  # these constants hold no escapes


def test_java_name_rules_match_python():
    src = _JAVA_RULES.read_text(encoding="utf-8")
    assert _java_string_constant(src, "SURNAMES") == tc._SURNAMES
    assert _java_string_constant(src, "ORG_SUFFIX") == tc._ORG_SUFFIX
    assert _java_string_constant(src, "PERSON_ROLE") == tc._PERSON_ROLE
