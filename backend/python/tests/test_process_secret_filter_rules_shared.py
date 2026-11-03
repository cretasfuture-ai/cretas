"""Training-sample filter rules (Steve 2026-10-29, option A) — the Python side of
the shared case file smartbi/services/process_secret_filter_rule_cases.json.

Java runs the same file (ProcessSecretFilterRulesSharedContractTest); both must
give the same verdict on the same input. Covered here:

* term_cases: only full name / code / product id / registered exclusive alias;
  pure-numeric names and category words are not collected; codes match only on
  ASCII boundaries (F006's product "398" vs batch numbers ...-39879);
* row_cases: a sample is matched against ITS factory's lists only; no factory ->
  nothing (never the union of every factory's lists);
* directory_cases: the factory's own people / customers, matched exactly (the 14
  person-word sentences and 5 customer sentences that the old regex blocked pass);
* content_cases: a process word alone does not block, a process word plus a
  concrete value in the same record does;
* qa_cases (Python only): the Q&A rule, incl. the S2 shape of distill#12884;
* desensitize_cases (Python only): 「按产品明细」 / 「分析以下工厂数据」 stay as written.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from common import process_secret_terms as pst
from smartbi.services import process_secret_names as psn
from smartbi.services import training_consent as tc
from smartbi.services.corpus_anonymize import desensitize_for_training

_PY_ROOT = Path(__file__).resolve().parents[1]
_CASES = _PY_ROOT / "smartbi" / "services" / "process_secret_filter_rule_cases.json"
_JAVA_TERMS = (_PY_ROOT.parent / "java" / "cretas-api" / "src" / "main" / "java" / "com" / "cretas"
               / "aims" / "service" / "consent" / "ProcessSecretTerms.java")


def _cases():
    assert _CASES.exists(), "shared case file must exist (no silent skip)"
    return json.loads(_CASES.read_text(encoding="utf-8"))


def _mismatches(checks, verdict):
    return [c["text"] for c in checks if verdict(c) != c["match"]]


def test_category_words_are_one_list_on_both_sides():
    data = _cases()
    assert tuple(data["category_words"]) == pst.CATEGORY_WORDS
    src = _JAVA_TERMS.read_text(encoding="utf-8")
    block = re.search(r"CATEGORY_WORDS = List\.of\((.*?)\);", src, re.S)
    assert block, "CATEGORY_WORDS not found in ProcessSecretTerms.java"
    assert tuple(re.findall(r'"([^"]*)"', block.group(1))) == pst.CATEGORY_WORDS


def test_pure_numeric_pattern_is_the_same_on_both_sides():
    src = _JAVA_TERMS.read_text(encoding="utf-8")
    m = re.search(r'PURE_NUMERIC = Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', src)
    assert m
    from tests.test_training_sample_rules_shared import same_regex_text

    java = re.sub(r'\\(["\\])', r"\1", m.group(1))
    assert same_regex_text(java) == same_regex_text(pst._PURE_NUMERIC.pattern)


def test_term_cases():
    cases = _cases()["term_cases"]
    assert len(cases) >= 5
    total = 0
    for case in cases:
        terms = pst.secret_terms(case["products"], case["aliases"])
        bad = _mismatches(case["checks"], lambda c: pst.mentions_any(terms, c["text"]))
        assert not bad, (case["name"], sorted(terms), bad)
        total += len(case["checks"])
    assert total >= 25
    # both outcomes are exercised (an all-False file would pass a matcher that never matches)
    outcomes = {c["match"] for case in cases for c in case["checks"]}
    assert outcomes == {True, False}


def test_f006_398_is_not_collected_but_its_code_is():
    case = _cases()["term_cases"][0]
    terms = pst.secret_terms(case["products"], case["aliases"])
    assert "398" not in terms                       # the pure-numeric name
    assert "398礼盒" in terms and "ptshhpskgylyxgs0125" in terms


def test_row_cases_never_fall_back_to_every_factorys_lists():
    rows = _cases()["row_cases"]
    by_factory = {f: psn.lists_from_rows(products, ()) for f, products in rows["products_by_factory"].items()}
    bad = _mismatches(rows["checks"], lambda c: psn.lists_for_row(c["factory"], by_factory).names_any(c["text"]))
    assert not bad, bad
    # the same text with no factory, matched against the union, WOULD hit: the case is not vacuous
    union = frozenset(t for lists in by_factory.values() for t in lists.terms)
    assert pst.mentions_any(union, "398礼盒今天发了多少")


def test_directory_cases():
    cases = _cases()["directory_cases"]
    for case in cases:
        names = pst.directory_names(case["people"] + case["customers"])
        bad = _mismatches(case["checks"], lambda c: pst.mentions_any(names, c["text"]))
        assert not bad, (case["name"], bad)


def test_the_19_sentences_the_old_regex_blocked_now_pass():
    """14 person-word + 5 customer sentences (2026-10-29 analysis, constructed there
    from prod wording): the old regex blocked every one. They name nobody."""
    case = _cases()["directory_cases"][0]
    allowed = [c["text"] for c in case["checks"] if not c["match"]]
    old = [t for t in allowed if tc._PERSON_RE.search(t) or ("客户" in t and tc._ORG_NAME_RE.search(t))]
    assert len(old) >= 19, old          # positive control: the old proxy really fired on them
    names = pst.directory_names(case["people"] + case["customers"])
    assert not [t for t in old if pst.mentions_any(names, t)]
    assert not [t for t in old if tc.utterance_has_blocked_content(t, directory=names)]


def test_content_cases():
    cases = _cases()["content_cases"]
    assert {c["blocked"] for c in cases} == {True, False}
    bad = [c["text"] for c in cases
           if tc.utterance_has_blocked_content(c["text"], directory=()) != c["blocked"]]
    assert not bad, bad


def test_qa_cases():
    cases = _cases()["qa_cases"]
    assert {c["blocked"] for c in cases} == {True, False}
    bad = [c["name"] for c in cases
           if tc.qa_has_blocked_content(c["input"], c["answer"], directory=c["directory"]) != c["blocked"]]
    assert not bad, bad


def test_desensitize_cases():
    for c in _cases()["desensitize_cases"]:
        out = desensitize_for_training(c["text"], "")[0]
        assert out == c["expected"], (c["text"], out)
    assert "菜品A" not in desensitize_for_training("### 按产品明细", "")[0]
    assert "工厂A" not in desensitize_for_training("分析以下工厂数据", "")[0]
