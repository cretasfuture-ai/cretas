"""外发文本脱敏: Python 与 Java 同一套规则 (Steve 2026-10-27)。

共用用例 common/llm_egress_text_cases.json; Java 侧 LlmEgressTextSharedCasesContractTest 跑同一份。
两边输出都必须与 expected 一字不差 —— 改一边的规则不改另一边就会红。另钉住只在外发里用的那几个常量与 Java 源码逐字相同。
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from common import llm_egress_text as egress

_PY_ROOT = Path(__file__).resolve().parents[1]
_CASES = _PY_ROOT / "common" / "llm_egress_text_cases.json"
_JAVA = (_PY_ROOT.parent / "java" / "cretas-api" / "src" / "main" / "java" / "com" / "cretas" / "aims"
         / "service" / "consent" / "LlmEgressRedactor.java")


def _cases():
    return json.loads(_CASES.read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", _cases(), ids=lambda c: c["name"])
def test_python_produces_exactly_the_shared_expected_outputs(case):
    r = egress.redact_texts(case["texts"], case.get("known_parties"), case.get("protected_names"),
                            case["detect_names"])

    assert r.refusal == case["refusal"]
    assert (r.texts if not r.refused else None) == case["expected"]
    for a in case.get("answers", []):
        assert r.restore(a["answer"]) == a["expected"]


def test_the_shared_file_covers_numbers_names_parties_products_and_restore():
    cases = _cases()
    assert len(cases) >= 10
    joined = json.dumps([c.get("expected") for c in cases], ensure_ascii=False)
    for token in ("[电话", "[证件号", "[卡号", "[单位", "[人员", "[客户", "[保密产品"):
        assert token in joined, token
    assert any(not c["detect_names"] for c in cases)
    assert any(c.get("answers") for c in cases)


def _java_string(src: str, name: str) -> str:
    m = re.search(rf'{name}\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+);', src)
    assert m, name
    return "".join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', m.group(1)))


def test_egress_only_constants_match_java_source():
    src = _JAVA.read_text(encoding="utf-8")
    assert _java_string(src, "EXTRA_SURNAMES") == egress.EXTRA_SURNAMES
    assert _java_string(src, "SINGLE_CHAR_ORG_LEAD_INS") == egress.SINGLE_CHAR_ORG_LEAD_INS
    assert _java_string(src, "BARE_TOKEN_CLASSIFIERS") == egress.BARE_TOKEN_CLASSIFIERS
    for kind in egress.KINDS:
        assert f'("{kind}")' in src, kind


def test_stream_restorer_waits_for_a_token_split_across_chunks():
    r = egress.redact_texts(["给华东冷链商贸有限公司下单"])
    restorer = egress.StreamRestorer(r.token_to_real)

    out = "".join(restorer.push(c) for c in ["好的，[单", "位", "A] 已下单，", "单位", "A 确认"]) + restorer.flush()

    assert out == "好的，华东冷链商贸有限公司 已下单，华东冷链商贸有限公司 确认"
