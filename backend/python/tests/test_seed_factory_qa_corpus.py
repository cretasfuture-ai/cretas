"""SEED_F factory Q&A seed corpus: mapping, quality gate, write count, export.

The teacher is faked; everything else (sample building, gate, write path through
persist_distillation_sample, the export gate) is the real code.
"""
from __future__ import annotations

import asyncio
import json
import re

from scripts import seed_factory_qa_corpus as sf
from smartbi.services.training_consent import qa_has_blocked_content


def run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


def _answer_from_input(input_text: str) -> str:
    """A well-behaved teacher: quotes numbers that are in the data it was given."""
    nums = re.findall(r"\d+(?:\.\d+)?", input_text.split("【问题】")[0])
    pick = "、".join(nums[:2]) if nums else "暂无"
    return f"先说结论：现有数据里能看到 {pick} 这两个数，建议关注变化。"


async def good_teacher(_system: str, user: str):
    return _answer_from_input(user)


def test_mapping_covers_every_intent_and_seed():
    assert len(sf.INTENTS) == 22
    assert sum(len(i.seeds) for i in sf.INTENTS) == 79
    assert len({i.code for i in sf.INTENTS}) == 22
    for i in sf.INTENTS:
        assert i.kind == "none" or i.scope in sf._BUILDERS


def test_seeds_and_inputs_have_no_restaurant_words_or_blocked_content():
    samples = sf.build_all_samples()
    assert len(samples) >= 79
    for s in samples:
        for w in sf.RESTAURANT_WORDS:
            assert w not in s.input_text, (w, s.input_text)
        # 输入端已经过现行训练规则; 否则每条都会被丢
        assert not qa_has_blocked_content(s.input_text, "")


def test_build_is_deterministic():
    a = [(s.question, s.input_text) for s in sf.build_all_samples()]
    b = [(s.question, s.input_text) for s in sf.build_all_samples()]
    assert a == b


def _one(code="REPORT_FINANCE"):
    return next(s for s in sf.build_all_samples() if s.intent_code == code)


def test_gate_accepts_traceable_numbers():
    s = _one()
    assert sf.check_answer(s, _answer_from_input(s.input_text)) is None


def test_gate_accepts_derived_number():
    s = _one("SKU_GROSS_MARGIN")
    mg = re.findall(r"\d+\.\d", s.input_text)
    a, b = float(mg[0]), float(mg[1])
    # 两个原值现算的差值也算可追溯
    assert sf.check_answer(s, f"毛利率相差 {abs(a - b):.1f} 个点，值得关注一下。") is None


def test_gate_rejects_invented_number():
    s = _one()
    assert (sf.check_answer(s, "本月销售额是 98765432 元，比上月多不少。") or "").startswith("untraceable_number")


def test_gate_rejects_restaurant_word():
    s = _one()
    assert sf.check_answer(s, "各门店的销售额都比较平稳，请继续关注。") == "restaurant_word"


def test_gate_rejects_blocked_process_content():
    s = _one()
    assert sf.check_answer(s, "可以关注杀菌温度对销售额的影响，持续观察。") == "training_rule"


def test_gate_rejects_unknown_entity_name():
    s = _one("SKU_GROSS_MARGIN")
    assert sf.check_answer(s, "产品Z的毛利率最高，值得关注一下哦。") == "unknown_name"


def test_gate_rejects_empty():
    assert sf.check_answer(_one(), "  ") == "empty"


def test_generate_counts_rejections_and_dedups():
    calls = [0]

    async def bad_every_third(_system, user):
        calls[0] += 1
        if calls[0] % 3 == 0:
            return "总销售额 9999999 元，请继续关注变化。"
        if calls[0] % 7 == 0:
            return None
        return _answer_from_input(user)

    samples = sf.build_all_samples()
    rows, rejects = run(sf.generate(samples, bad_every_third))
    assert rows and rejects.get("untraceable_number", 0) > 0 and rejects.get("llm_error", 0) > 0
    assert len(rows) + sum(rejects.values()) >= len(samples) - 0  # 每条要么进清单, 要么被计数 (去重除外)
    hashes = {r["input_text"] for r in rows}
    assert len(hashes) == len(rows)


def test_every_intent_is_covered_when_teacher_is_good():
    rows, rejects = run(sf.generate(sf.build_all_samples(), good_teacher))
    assert {r["intent_code"] for r in rows} == {i.code for i in sf.INTENTS}, rejects


def test_review_list_has_two_per_intent():
    rows, _ = run(sf.generate(sf.build_all_samples(), good_teacher))
    md = sf.review_markdown(rows, per_intent=2)
    assert md.count("- 问:") == 2 * len(sf.INTENTS)
    assert "| PRICE_HISTORY_QUERY" in sf.mapping_markdown()


class _Conn:
    def __init__(self, store):
        self.store = store

    async def fetchval(self, sql, *a):
        return sum(1 for r in self.store if r["source"] == a[0])

    async def execute(self, sql, *a):
        (source, business_type, factory_id, task_type, codes, system_prompt, input_text,
         teacher_model, teacher_output, input_hash, quality, metadata_json) = a
        self.store.append({"source": source, "business_type": business_type,
                           "factory_id": factory_id, "task_type": task_type,
                           "input_text": input_text, "teacher_output": teacher_output,
                           "input_hash": input_hash, "quality": quality,
                           "metadata": json.loads(metadata_json)})


class _Pool:
    def __init__(self):
        self.store = []

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(_self):
                return _Conn(pool.store)

            async def __aexit__(_self, *exc):
                return False
        return _Ctx()


def test_write_count_equals_manifest_and_rows_are_revocable_and_exportable():
    rows, _ = run(sf.generate(sf.build_all_samples(), good_teacher))
    pool = _Pool()
    before, after = run(sf.write_manifest(pool, rows))
    assert (before, after) == (0, len(rows))
    for r in pool.store:
        assert r["source"] == sf.SOURCE and len(sf.SOURCE) <= 32
        assert r["factory_id"] == "SEED_F" and r["business_type"] == "factory"
        assert r["metadata"]["batch_id"] == sf.BATCH_ID
        assert r["metadata"]["source_kind"] == "synthetic_seed"

    # 导出脚本把 SEED_F 行当合成行直接收入
    from scripts.export_distillation_dataset import gate_shared_rows
    exported = gate_shared_rows(pool.store, {}, {})
    assert len(exported) == len(pool.store)
    assert all(i["consent_class"] == "synthetic" for i in exported)
