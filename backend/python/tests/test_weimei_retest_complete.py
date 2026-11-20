"""指定上传的 SmartBI SSE 回归。所有业务记录均为合成数据。"""

import pytest

from tests.test_weimei_retest_r3b import PLAN_FIELDS
from tests.test_weimei_retest_r4 import DAY_FIELDS, PLAN_DAY_FIELDS, ask, day_rows

pytestmark = pytest.mark.asyncio

BAD_NARRATIVE = "只列出某些记录。其中 7 笔为计划外；平均为 0.80。"
Q07 = "10月哪些产品计划了但没有生产？哪个最多？"
Q11 = "10月有哪些计划外生产？"


def detail_rows():
    def row(date, code, status, kg):
        return {"日期": date, "产品编码": f"#{code}", "结论": status, "实产良品kg": kg,
                "周": "2024-W40", "计划产线": None, "计划内": None}

    misses = [(f"00{i:03d}X", count) for i, count in enumerate([5, 4, 2, 2, 1, 1, 1, 1, 1, 1, 1])]
    outside = [(f"2024-10-{i + 7:02d}", f"00{i % 7:03d}X", 100.07 + i * 131.11) for i in range(9)]
    rows = [row("2024-10-02", code, "计划了未生产", 0) for code, count in misses for _ in range(count)]
    rows += [row(date, code, "计划外生产", kg) for date, code, kg in outside]
    rows += [row("2024-09-30", "OUT-OF-MONTH", status, 700) for status in ("计划了未生产", "计划外生产")]
    rows = [row("2024-10-03", "MADE", "按计划产出", 1000) for _ in range(200)] + rows
    return rows, misses, outside


async def ask_sheet(monkeypatch, upload, rows, fields, question, plans=None):
    return await ask(monkeypatch, question, {upload: (rows, fields)}, sheet_id=str(upload),
                     answer=BAD_NARRATIVE, plans=plans)


def assert_stream(events, done):
    assert done["success"] is True
    assert [event for event, _ in events].count("done") == 1
    assert not any(event == "error" for event, _ in events)
    assert "".join(data for event, data in events if event == "chunk") == done["answer"]
    assert [data for event, data in events if event == "charts"] == ([done["charts"]] if done["charts"] else [])


@pytest.mark.parametrize("top_n", [None, 3])
async def test_q07_lists_every_code_or_only_the_requested_top_n(monkeypatch, top_n):
    rows, misses, _ = detail_rows()
    question = Q07 if top_n is None else f"{Q07}列出前 {top_n} 个"
    events, done, prompts, calls = await ask_sheet(monkeypatch, 5337, rows, PLAN_FIELDS, question)
    assert_stream(events, done)
    assert prompts == calls == []
    answer = done["answer"]
    assert "20" in answer and "11" in answer
    assert "最多" in answer and "00000X" in answer and "5 次" in answer
    assert [line for line in answer.splitlines() if line.startswith("- ")] == [
        f"- {code}：{count} 次" for code, count in misses[:top_n]
    ]
    assert "#000" not in answer and "OUT-OF-MONTH" not in answer


async def test_q11_lists_nine_date_code_pairs_and_uses_source_weights(monkeypatch):
    rows, _, outside = detail_rows()
    events, done, prompts, calls = await ask_sheet(monkeypatch, 5337, rows, PLAN_FIELDS, Q11)
    assert_stream(events, done)
    assert prompts == calls == []
    answer = done["answer"]
    assert "9 笔" in answer and "5,620.59 kg" in answer
    assert len([line for line in answer.splitlines() if line.startswith("- ")]) == 9
    for date, code, _ in outside:
        assert f"{date} · {code}" in answer
    assert "OUT-OF-MONTH" not in answer


def summary_rows():
    # W41: 3/12，逐日比率分别为 10% 与 100%。W42 无计划分母。
    return [{"日期": date, "原表计划品项数": plan, "已产出计划品项数": made,
             "计划品项达成率": made / plan if plan else None, "计划外生产品项数": outside,
             "备注": "各日备注相同", "实产良品kg": 100.0}
            for date, plan, made, outside in [("2024-10-01", 9, 6, 4), ("2024-10-07", 10, 1, 0),
                                               ("2024-10-08", 2, 2, 1), ("2024-10-15", 0, 0, 2),
                                               ("2024-10-22", 4, 3, 0)]]


async def test_q10_explains_that_outside_items_are_separate(monkeypatch):
    events, done, _, _ = await ask_sheet(monkeypatch, 5334, summary_rows(), PLAN_DAY_FIELDS, "10月计划与实际差多少？")
    assert_stream(events, done)
    answer = done["answer"]
    assert "计划 25 个" in answer and "计划内已产出 12 个" in answer
    assert "缺口 13 个" in answer and "48.00%" in answer
    assert "另有 7 个计划外生产品项" in answer and "0.80" not in answer


@pytest.mark.parametrize("denominator", [0, None])
@pytest.mark.parametrize("weekly", [False, True], ids=["R04", "C04"])
async def test_weekly_body_and_chart_use_the_same_weighted_week_values(monkeypatch, weekly, denominator):
    rows = summary_rows()
    fields = PLAN_DAY_FIELDS
    if weekly:
        del rows[2]
        rows = [dict(row, 周=week) for row, week in zip(rows, ["2024-W40", "2024-W41", "2024-W42", "2024-W43"])]
        rows[1].update(原表计划品项数=12, 已产出计划品项数=3, 计划品项达成率=0.25)
        for row in rows:
            del row["日期"]
        fields = [("周", False, True, False), *PLAN_DAY_FIELDS[1:]]
    next(row for row in rows if row["原表计划品项数"] == 0)["原表计划品项数"] = denominator
    events, done, _, _ = await ask_sheet(
        monkeypatch, 5335 if weekly else 5334, rows, fields,
        "哪一周计划品项达成率最低？是多少？" if weekly else "哪一周计划品项达成率最低？",
    )
    assert_stream(events, done)
    answer = done["answer"]
    assert "2024-W41" in answer and "25.00%" in answer and "3/12" in answer and "缺口 9" in answer
    assert "2024-W42" in answer and "排除" in answer and "0.80" not in answer
    chart = done["charts"][0]["option"]
    assert chart["xAxis"]["name"] == "周"
    assert chart["xAxis"]["data"] == ["2024-W40", "2024-W41", "2024-W42", "2024-W43"]
    assert chart["series"][0]["data"] == [66.67, 25.0, None, 75.0]


async def test_outside_count_question_keeps_its_aggregate_path(monkeypatch):
    plan = {"intent": "compute", "metrics": [{"name": "计划外笔数", "func": "count"}],
            "filters": [{"col": "结论", "op": "==", "value": "计划外生产"},
                        {"col": "日期", "op": "==", "value": "2024-10"}]}
    rows, _, _ = detail_rows()
    events, done, prompts, calls = await ask(
        monkeypatch, "10月计划外生产有几笔？", {5337: (rows, PLAN_FIELDS)}, sheet_id="5337",
        plans=[plan], answer="10月计划外生产共 9 笔。",
    )
    assert_stream(events, done)
    assert "9 笔" in done["answer"] and "| 9 |" in prompts[0] and len(calls) == 1


@pytest.mark.parametrize("field", ["当日说明", "设备当日说明"])
async def test_5319_r05_lists_existing_notes_and_refuses_an_unknown_field(monkeypatch, field):
    rows = day_rows()
    rows[0]["当日说明"] = "合成备注：切换规格后恢复"
    events, done, _, _ = await ask_sheet(monkeypatch, 5319, rows, DAY_FIELDS,
                                        f"10月{field}有什么特殊情况？",
                                        plans=[{"intent": "no_metric", "missing": field}])
    assert_stream(events, done)
    assert done["charts"] == []
    if field == "当日说明":
        assert done["source"] == "row_level_text"
        assert "2026-10-01：合成备注：切换规格后恢复" in done["answer"]
    else:
        assert done["source"] == "field_metric_mismatch_refusal"
        assert "没有「设备当日说明」" in done["answer"] and "切换规格后恢复" not in done["answer"]
