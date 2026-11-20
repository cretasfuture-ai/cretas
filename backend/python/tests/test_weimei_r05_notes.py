import pytest

from tests.test_weimei_retest_r4 import DAY_FIELDS, _clean_agg_cache, ask, day_rows


async def test_r05_returns_all_nonempty_notes_for_the_requested_month(monkeypatch):
    rows = day_rows()
    expected = []
    for i, row in enumerate(rows[:23]):
        note = f"第{i + 1}笔生产情况" + ("，设备调整后恢复生产" * 8 if i == 22 else "")
        row["当日说明"] = note
        expected.append(f'- {row["日期"]}：{note}')
    rows[24]["当日说明"] = "  "
    rows.append(dict(rows[0], 日期="2026-09-30", 当日说明="上月说明不得列出"))

    events, done, prompts, calls = await ask(
        monkeypatch, "10月当日说明有什么特殊情况？", {5319: (rows, DAY_FIELDS)},
        plans=[{"intent": "no_metric", "missing": "当日说明"}],
    )

    assert any(e == "data_source" and d["uploadId"] == 5319 for e, d in events)
    assert done["source"] == "row_level_text", done["answer"]
    assert "23 条" in done["answer"]
    assert [line for line in done["answer"].splitlines() if line.startswith("- ")] == expected
    assert "".join(d for e, d in events if e == "chunk") == done["answer"]
    assert done["charts"] == []
    assert prompts == calls == []


async def test_r05_still_refuses_an_absent_field_with_a_shared_suffix(monkeypatch):
    rows = day_rows()
    rows[0]["当日说明"] = "实际存在的说明"
    missing = "设备当日说明"
    _, done, prompts, calls = await ask(
        monkeypatch, f"10月{missing}有什么特殊情况？", {5319: (rows, DAY_FIELDS)},
        plans=[{"intent": "no_metric", "missing": missing}],
    )

    assert done["source"] == "field_metric_mismatch_refusal"
    assert f"没有「{missing}」" in done["answer"]
    assert "实际存在的说明" not in done["answer"]
    assert len(calls) == 1 and not prompts


@pytest.mark.parametrize("question", [
    "10月当日说明有什么特殊情况，产量多少？",
    "10月当日说明按产线分组有哪些？",
])
async def test_r05_leaves_composite_queries_to_the_planner(monkeypatch, question):
    rows = day_rows()
    rows[0]["当日说明"] = "换线后恢复"
    _, _, _, calls = await ask(monkeypatch, question, {5319: (rows, DAY_FIELDS)})
    assert len(calls) == 1
