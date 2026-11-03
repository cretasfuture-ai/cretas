"""Python and Java apply the same training-sample rules (2026-10-24, content rule
changed 2026-10-29 option A).

Both run smartbi/services/training_sample_rule_cases.json (the Java side in
TrainingConsentGateContractTest); this test also pins Java's PARAMETER_MARKERS and
the content-rule patterns to the Python ones so the two copies cannot drift apart.
The filter-rule cases (names, codes, people, customers) live in
process_secret_filter_rule_cases.json (tests/test_process_secret_filter_rules_shared.py).
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
        keep, text = tc.desensitize_utterance("private" if c["authorized"] else "none", c["text"],
                                              directory=())
        assert (text if keep else None) == c["stored"], c["text"]


def test_qa_cases_include_both_outcomes():
    cases = _cases()["qa_cases"]
    assert {c["blocked"] for c in cases} == {True, False}
    for c in cases:
        assert tc.qa_has_blocked_content(c["input"], c["answer"],
                                         directory=c.get("directory", ())) == c["blocked"], c


def test_java_parameter_markers_match_python():
    src = _JAVA_RULES.read_text(encoding="utf-8")
    block = re.search(r"PARAMETER_MARKERS = List\.of\((.*?)\);", src, re.S)
    assert block, "PARAMETER_MARKERS not found in TrainingSampleRules.java"
    java = tuple(re.findall(r'"([^"]*)"', block.group(1)))
    assert java == tc.PARAMETER_MARKERS


def test_every_parameter_marker_blocks_an_utterance_only_with_a_value():
    for m in tc.PARAMETER_MARKERS:
        assert tc.utterance_has_blocked_content(f"问一下{m}的情况", directory=()) is False, m
        assert tc.utterance_has_blocked_content(f"{m}是 12", directory=()) is True, m


def test_the_directory_is_required():
    """A caller that forgets the factory's people / customers must fail loudly."""
    import pytest

    with pytest.raises(TypeError):
        tc.utterance_has_blocked_content("操作员王五报工")          # noqa: missing directory
    with pytest.raises(TypeError):
        tc.qa_has_blocked_content("q", "a", directory=None)


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


def _java_pattern(src: str, name: str) -> str:
    """Regex of `static final Pattern NAME = Pattern.compile("..." + "...");`, Java escapes undone."""
    m = re.search(rf"static final Pattern {name} = Pattern\.compile\((.*?)\);", src, re.S)
    assert m, f"Pattern {name} not found in TrainingSampleRules.java"
    literal = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))
    return re.sub(r'\\(["\\])', r"\1", literal)     # undo the Java string escapes only


def same_regex_text(pattern: str) -> str:
    """Regex text with \\uXXXX escapes spelled as the character (one side may write the
    escape, the other the character itself — same regex)."""
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), pattern)


def test_java_content_rule_patterns_match_python():
    """The regex text is identical on both sides."""
    src = _JAVA_RULES.read_text(encoding="utf-8")
    pairs = {"RECORD_SPLIT": tc._RECORD_SPLIT_RE, "ORDINAL": tc._ORDINAL_RE,
             "IDENTIFIER": tc._IDENTIFIER_RE, "DATE_TIME": tc._DATE_TIME_RE, "DIGIT": tc._DIGIT_RE}
    for name, py in pairs.items():
        assert same_regex_text(_java_pattern(src, name)) == same_regex_text(py.pattern), name
