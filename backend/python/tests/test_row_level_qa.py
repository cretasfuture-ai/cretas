"""问数据 (AI) 行级确定性计算 —— 味美 FOOD_0000_001 实测 17 条的回归 (缺陷 A1-A5/A10)。

仓库是 public 的, 不放客户真实数据: 这里用同列头的合成小表, 数值按「真实答案」设计
(最大值行与别行字段不同、累计≠单行最大、比率加权≠简单平均、W42 达成率为空等陷阱都在)。
有本机真数据目录时, 末尾的 real-data 用例会把同一批计划在真表上再跑一遍。

LLM 只出「查询计划」, 这里用桩按问题返回计划 JSON (等价于 LLM 的输出);
被测的是: 计划校验 -> 服务端在全部行上执行 -> 结果块 / 拒答 / 提示词接线。
"""
from __future__ import annotations

import ast
import json
import os
from pathlib import Path

import pandas as pd
import pytest

from smartbi.services import row_level_qa as rl
from smartbi.services.dataset_capabilities import detect_capabilities, should_short_circuit

RATE = "出成率(良品/(良品+废品+次品))"


@pytest.fixture(autouse=True)
def _fresh_plan_cache():
    # 规划结果按 (原话, 表头) 缓存: 各用例的桩计划不同, 不能互相串。
    getattr(rl, "_PLAN_CACHE", {}).clear()
    yield


# --------------------------- 合成表 (同味美列头) ---------------------------

def daily_summary() -> pd.DataFrame:
    # 前 3 行 (旧链路只给 LLM 看这 3 行) 的出成率都高; 最低出成率在第 7 行 10-07。
    rows = [
        ("2026-10-01", 47, 4, 9000.0, 100.0, 0.0, 0.98901),
        ("2026-10-02", 46, 4, 9000.0, 100.0, 0.0, 0.98901),
        ("2026-10-03", 18, 1, 9000.0, 100.0, 0.0, 0.98901),
        ("2026-10-04", 40, 3, 5000.0, 200.0, 0.0, 0.96154),
        ("2026-10-05", 40, 3, 5000.0, 200.0, 0.0, 0.96154),
        ("2026-10-06", 40, 3, 5000.0, 200.0, 0.0, 0.96154),
        ("2026-10-07", 40, 3, 1000.0, 1000.0, 0.0, 0.5),
    ]
    return pd.DataFrame(rows, columns=["日期", "人数", "批次数", "良品kg", "废品kg", "次品kg", RATE])


def machine_downtime() -> pd.DataFrame:
    rows = [
        ("2026-10-01", "RAV.A", 0, "无停机"), ("2026-10-01", "RAV.B", 0, "无停机"),
        ("2026-10-01", "RAV.C", 0, "无停机"),
        # RAV.A 单行最大 273 在前面, 但累计最大的是 RAV.C (984)。
        ("2026-10-02", "RAV.A", 150, "面皮输送带"), ("2026-10-03", "RAV.A", 120, "伺服报警"),
        ("2026-10-04", "RAV.B", 300, "切刀"), ("2026-10-05", "RAV.B", 247, "切刀"),
        ("2026-10-05", "RAV.C", 273, "面皮输送带"), ("2026-10-06", "RAV.C", 400, "面皮输送带"),
        ("2026-10-07", "RAV.C", 311, "伺服报警"),
    ]
    return pd.DataFrame(rows, columns=["日期", "机台", "停机分钟", "停机原因/说明"]).assign(
        计划开机分钟=480, 实际开机分钟=450, 停机次数=1,
    )


def proof_steam() -> pd.DataFrame:
    rows = [("2026-10-01", 261001, "刀切", "PANE CINESE", "馒头", 1, 50, 39.7, 88.5),
            ("2026-10-01", 261001, "刀切", "PANE CINESE", "馒头", 2, 50, 39.4, 81.4)]
    return pd.DataFrame(rows, columns=["日期", "批号", "品类", "产品(意)", "产品(中)", "架次",
                                       "醒发分钟", "温度(摄氏度)", "湿度(百分比)"])


def film_usage() -> pd.DataFrame:
    rows = [("2026-10-01", "FIL20-C", 3), ("2026-10-01", "FIL55", 2),
            ("2026-10-02", "FIL20-C", 3), ("2026-10-02", "FIL55", 1), ("2026-10-03", "FIL20-C", 4)]
    return pd.DataFrame(rows, columns=["日期", "卷膜编码", "用量(卷)"])


def plan_vs_actual() -> pd.DataFrame:
    ok, no = "按计划产出", "计划了未生产"
    rows = [("2026-10-01", "210061", 7476.0, ok), ("2026-10-01", "26002", 0.0, no),
            ("2026-10-02", "26002", 0.0, no), ("2026-10-02", "210061", 7000.0, ok)]
    # 22002 排了 5 天一次没产 (排在表的后面, 前几行样本里看不到)
    rows += [(f"2026-10-0{d}", "22002", 0.0, no) for d in range(5, 10)]
    rows += [("2026-10-07", "210016", 0.0, no), ("2026-10-10", "24004", 3500.0, ok)]
    return pd.DataFrame(rows, columns=["日期", "产品编码", "实产良品kg", "结论"])


def mixing_daily() -> pd.DataFrame:
    rows = [("2026-10-01", "210061", 9, 3.76, 0), ("2026-10-02", "210061", 6, 3.52, 0),
            ("2026-10-03", "21024", 4, 1.0, 3), ("2026-10-24", "21004M", 7, 2.2, 5),
            ("2026-10-25", "210061", 8, 3.9, 0)]
    return pd.DataFrame(rows, columns=["日期", "产品编码", "拌馅趟次", "平均肉温", "肉温超出3到5度趟次"])


def plan_daily() -> pd.DataFrame:
    rows = [("2026-10-01", 5, 4), ("2026-10-09", 7, 4), ("2026-10-16", 3, 3), ("2026-10-17", 6, 5)]
    return pd.DataFrame(rows, columns=["日期", "原表计划品项数", "已产出计划品项数"]).assign(
        计划品项达成率=lambda d: d["已产出计划品项数"] / d["原表计划品项数"],
    )


def plan_weekly() -> pd.DataFrame:
    return pd.DataFrame(
        [("2026-W40", 12, 9, 0.75), ("2026-W41", 32, 22, 0.6875), ("2026-W42", 0, 0, float("nan")),
         ("2026-W43", 16, 13, 0.8125)],
        columns=["周", "原表计划品项数", "已产出计划品项数", "计划品项达成率"],
    )


# ------------------------------ 计划桩 ------------------------------

PLANS = {
    "q3": {"filters": [], "metrics": [
        {"name": "总良品kg", "func": "sum", "col": "良品kg"},
        {"name": "整体出成率", "func": "mean", "col": RATE}]},  # LLM 习惯性写 mean, 服务端必须改加权
    "q3_explicit": {"metrics": [
        {"name": "整体出成率", "func": "ratio", "num": ["良品kg"], "den": ["良品kg", "废品kg", "次品kg"]}]},
    "q17": {"sort_by": RATE, "descending": False, "limit": 1},
    "q5": {"group_by": ["机台"], "metrics": [{"name": "停机分钟合计", "func": "sum", "col": "停机分钟"}],
           "sort_by": "停机分钟合计", "descending": True, "limit": 3, "breakdown_by": "停机原因/说明"},
    "q9": {"sort_by": "原表计划品项数", "descending": True, "limit": 1,
           "note": "原表计划没有数量, 按计划品项数回答"},
    "q12": {"sort_by": "计划品项达成率", "descending": False, "limit": 1},
    "q14": {"filters": [{"col": "结论", "op": "==", "value": "计划了未生产"}], "group_by": ["产品编码"],
            "metrics": [{"name": "未生产日数", "func": "count"}], "sort_by": "未生产日数",
            "descending": True, "limit": 5},
    "q15": {"filters": [{"col": "肉温超出3到5度趟次", "op": ">", "value": 0}]},
    "q7": {"group_by": ["卷膜编码"], "metrics": [{"name": "用量合计", "func": "sum", "col": "用量(卷)"}],
           "sort_by": "用量合计", "descending": True, "limit": 3},
}


class StubPlanner:
    """按问题回计划 JSON; 记录收到的提示词 (断言 LLM 看到了全部字段)。"""

    def __init__(self, key: str | None = None, raw: str | None = None):
        self.key, self.raw, self.prompts = key, raw, []

    async def __call__(self, prompt: str, system: str) -> str:
        self.prompts.append(prompt)
        if self.raw is not None:
            return self.raw
        return json.dumps({"intent": "compute", **PLANS[self.key]}, ensure_ascii=False)


async def ask(question, df, key=None, raw=None):
    names = rl.all_field_names(None, df)
    planner = StubPlanner(key, raw)
    out = await rl.answer_row_level(question, df, names, planner)
    return out, planner


# ---------------------- 第 3 / 5 / 9 / 10 / 12 / 14 / 15 / 17 条 ----------------------

async def test_q3_ratio_is_weighted_over_all_rows_not_mean_of_daily_ratios():
    out, planner = await ask("10月总产量多少公斤？整体出成率多少？", daily_summary(), "q3")
    assert out.kind == "computed"
    (total, rate), = out.result.rows
    assert total == pytest.approx(43000.0)
    # Σ良 / (Σ良 + Σ废 + Σ次) = 43000 / 44900 = 95.77% ; 逐日简单平均会是 90.74%
    assert rl._fmt(rate, pct=True) == "95.77%"
    assert "95.77%" in out.text
    assert "90.74%" not in out.text
    assert any("加权" in n for n in out.result.notes)
    # LLM 规划时看到了全部字段 (A10 的另一半)
    assert all(c in planner.prompts[0] for c in daily_summary().columns)


async def test_q3_explicit_ratio_plan_also_weighted():
    out, _ = await ask("整体出成率多少", daily_summary(), "q3_explicit")
    assert rl._fmt(out.result.rows[0][0], pct=True) == "95.77%"


async def test_q17_lowest_rate_returns_the_whole_row_not_fields_from_other_rows():
    out, _ = await ask("10月哪天出成率最低？是多少？当天废品多少公斤？", daily_summary(), "q17")
    assert out.kind == "computed"
    assert len(out.result.rows) == 1
    row = dict(zip(out.result.columns, out.result.rows[0]))
    assert row["日期"] == "2026-10-07"          # A2: 日期来自行定位, 不是编的
    assert row["废品kg"] == 1000.0              # 同一行的废品, 不是第 1 行的 100
    assert row[RATE] == pytest.approx(0.5)
    assert "50.00%" in out.text


async def test_q5_group_total_picks_rav_c_984_not_single_row_max_of_rav_a():
    out, _ = await ask("10月哪台机停机时间最长？主要是什么原因？", machine_downtime(), "q5")
    assert out.kind == "computed"
    first = out.result.rows[0]
    assert first[0] == "RAV.C" and first[1] == 984
    assert [r[0] for r in out.result.rows] == ["RAV.C", "RAV.B", "RAV.A"]
    # 原因按 RAV.C 的行拆解: 面皮输送带 673 = 273 + 400
    assert "面皮输送带=673" in out.result.breakdown


@pytest.mark.parametrize("question", [
    "10月哪天计划产量最高？",
    "按日看，原表计划品项数最多的是哪一天？那天已产出几个？",
])
async def test_q9_q10_argmax_row_keeps_fields_of_the_same_row(question):
    out, _ = await ask(question, plan_daily(), "q9")
    row = dict(zip(out.result.columns, out.result.rows[0]))
    assert (row["日期"], row["原表计划品项数"], row["已产出计划品项数"]) == ("2026-10-09", 7, 4)
    assert any("没有数量" in n for n in out.result.notes)


async def test_q12_lowest_weekly_rate_is_w41_and_empty_week_is_not_zero_or_borrowed():
    out, _ = await ask("哪一周计划品项达成率最低？是多少？", plan_weekly(), "q12")
    row = dict(zip(out.result.columns, out.result.rows[0]))
    assert row["周"] == "2026-W41"
    assert "68.75%" in out.text
    assert any("1 行为空" in n for n in out.result.notes)   # W42 被排除而不是当 0 / 借别行的值


async def test_q14_planned_but_not_produced_groups_by_product_over_all_rows():
    out, _ = await ask("10月哪些产品计划了但没有生产？哪个最多？", plan_vs_actual(), "q14")
    assert out.result.matched_rows == 8
    top = out.result.rows[0]
    assert (top[0], top[1]) == ("22002", 5)          # 样本前 3 行里根本没有 22002
    assert dict((r[0], r[1]) for r in out.result.rows)["26002"] == 2


async def test_q15_meat_temp_over_limit_lists_the_real_days_and_products():
    out, _ = await ask("拌馅肉温有没有超出3到5度的？是哪几天、哪个产品、几趟？", mixing_daily(), "q15")
    cols = out.result.columns
    got = {(r[cols.index("日期")], r[cols.index("产品编码")], r[cols.index("肉温超出3到5度趟次")])
           for r in out.result.rows}
    assert got == {("2026-10-03", "21024", 3), ("2026-10-24", "21004M", 5)}
    assert out.result.matched_rows == 2


# ---------------------- A4 / A10: 指标缺失拒答 + 全字段 ----------------------

async def test_q1_no_output_metric_in_proofing_table_refuses_and_lists_all_fields():
    df = proof_steam()
    out, planner = await ask("10月一共生产了多少批？总产量多少？", df, "q9")
    assert out.kind == "refuse"
    assert "没有「产量/产出」" in out.text
    for col in df.columns:                      # 含 架次/温度/醒发分钟 这类非 measure 字段
        assert col in out.text
    assert planner.prompts == []                # 确定性拦下, 连规划都不用调 LLM


async def test_q6_film_usage_asked_on_machine_table_refuses_instead_of_using_minutes():
    out, _ = await ask("10月卷膜用量最大的是哪种？全月一共用了多少卷？", machine_downtime(), "q9")
    assert out.kind == "refuse" and "卷膜用量" in out.text
    assert "停机分钟" in out.text and "机台" in out.text


async def test_q16_cost_question_refuses_with_complete_field_list():
    df = daily_summary()
    out, _ = await ask("10月生产成本是多少？毛利多少？", df, "q9")
    assert out.kind == "refuse"
    for col in df.columns:
        assert col in out.text                  # 以前只说「包含: 时间/日期」, 漏良品/废品/人数


def test_capability_short_circuit_lists_all_fields_when_given():
    df = daily_summary()
    caps = detect_capabilities([{"original_name": c} for c in df.columns])
    msg = should_short_circuit("finance", caps, field_names=list(df.columns))
    assert msg and all(c in msg for c in ("良品kg", "废品kg", "人数"))
    # 不传 field_names 时保持旧行为 (向后兼容)
    assert should_short_circuit("finance", caps)


async def test_llm_reports_no_metric_is_also_a_refusal():
    raw = json.dumps({"intent": "no_metric", "missing": "单价"}, ensure_ascii=False)
    out, _ = await ask("平均单价多少", daily_summary(), raw=raw)
    assert out.kind == "refuse" and "单价" in out.text and "良品kg" in out.text


# ---------------------- 阴性对照: 有该指标的问题不被误拒 ----------------------

@pytest.mark.parametrize("question,df,key", [
    ("10月总产量多少公斤？整体出成率多少？", daily_summary(), "q3"),
    ("哪天出成率最低？", daily_summary(), "q17"),
    ("10月哪台机停机时间最长？", machine_downtime(), "q5"),
    ("卷膜用量最大的是哪种？全月一共用了多少卷？", film_usage(), "q7"),
    ("哪天计划产量最高", plan_daily(), "q9"),          # 有「实产良品kg」之外的计划品项数, 不拒
    ("拌馅肉温有没有超出", mixing_daily(), "q15"),
])
async def test_questions_whose_metric_exists_are_not_refused(question, df, key):
    out, _ = await ask(question, df, key)
    assert out.kind == "computed", out.text


async def test_film_usage_positive_control_answers_52_style_total_by_group():
    out, _ = await ask("卷膜用量最大的是哪种？", film_usage(), "q7")
    assert out.result.rows[0][:2] == ["FIL20-C", 10]


# ---------------------- 不退回样本猜: 规划失败 / 字段对不上 ----------------------

@pytest.mark.parametrize("raw", ["不是 JSON", "", json.dumps(
    {"intent": "compute", "sort_by": "不存在的列", "limit": 1}, ensure_ascii=False)])
async def test_unplannable_question_is_marked_uncomputed_and_forbids_naming_rows(raw):
    out, _ = await ask("哪天出成率最低", daily_summary(), raw=raw)
    assert out.kind == "uncomputed"
    assert "不得指出具体是哪一天" in out.text


async def test_planner_exception_is_uncomputed_not_a_silent_fallback():
    async def boom(p, s):
        raise RuntimeError("llm down")
    out = await rl.answer_row_level("哪天最低", daily_summary(), list(daily_summary().columns), boom)
    assert out.kind == "uncomputed"


async def test_intent_none_leaves_old_path_alone():
    raw = json.dumps({"intent": "none"})
    out, _ = await ask("你好", daily_summary(), raw=raw)
    assert out.kind == "none" and out.text == ""


# ---------------------- 载入: 租户过滤 / 行数上限 ----------------------

class FakeConn:
    def __init__(self, rows, count):
        self.rows, self.count, self.sql = rows, count, []

    async def fetchval(self, sql, *a):
        self.sql.append((sql, a))
        return self.count

    async def fetch(self, sql, *a):
        self.sql.append((sql, a))
        return [{"row_data": json.dumps(r, ensure_ascii=False)} for r in self.rows]


async def test_load_upload_frame_reads_all_rows_scoped_to_tenant():
    recs = daily_summary().to_dict("records")
    conn = FakeConn(recs, len(recs))
    df = await rl.load_upload_frame(conn, 5319, "FOOD_0000_001")
    assert len(df) == 7 and "LIMIT" not in conn.sql[1][0].upper()
    assert all("factory_id = $2" in s for s, _ in conn.sql)
    assert all(a == (5319, "FOOD_0000_001") for _, a in conn.sql)


async def test_load_upload_frame_over_cap_raises_instead_of_sampling():
    with pytest.raises(rl.TooManyRows):
        await rl.load_upload_frame(FakeConn([], rl.MAX_ROWS + 1), 1, "F")


# ---------------------- 接线: 产品真实入口 ----------------------

def test_chat_stream_wires_row_level_qa_into_prompt_refusal_and_cache_bypass():
    """用 AST 核对由 event_stream 驱动的实际分析生成器: 调用了
    answer_row_level、结果块进了提示词、拒答直接 done、行级问题不读旧 LLM 缓存。"""
    src = (Path(__file__).resolve().parents[1] / "smartbi" / "api" / "chat.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    stream = next(n for n in ast.walk(tree) if isinstance(n, ast.AsyncFunctionDef)
                  and n.name == "analysis_events" and "answer_row_level" in ast.unparse(n))
    wrapper = next(n for n in ast.walk(tree) if isinstance(n, ast.AsyncFunctionDef)
                   and n.name == "event_stream" and "analysis_events(leases)" in ast.unparse(n))
    assert any(isinstance(n, ast.Yield) for n in ast.walk(wrapper))
    code = ast.unparse(stream)
    assert "_rl_answer(" in code and "load_upload_frame" in code or "_rl_load(" in code
    # 提示词: 结果块变量出现在 prompt f-string 里
    prompt_assign = next(n for n in ast.walk(stream) if isinstance(n, ast.Assign)
                         and any(isinstance(t, ast.Name) and t.id == "prompt" for t in n.targets)
                         and isinstance(n.value, ast.JoinedStr))
    names = {x.id for x in ast.walk(prompt_assign.value) if isinstance(x, ast.Name)}
    assert "_rl_block" in names
    # 拒答分支: kind == "refuse" 后 return
    assert "field_metric_mismatch_refusal" in code
    # 旧缓存: 查询条件里有 not _rl_active
    assert "not _rl_active" in code


# ---------------------- 本机真数据 (CI 无此目录则跳过) ----------------------

REAL = Path(os.environ.get(
    "WEIMEI_SHEETS_DIR",
    r"C:\Users\Steve\AppData\Roaming\Claude\side-session-notes\local_804a4dbf-f90b-4132-8db7-c271e4d3c854"
    r"\ydwm-import-v2\smartbi-sheets"))


def _real(name):
    return pd.read_excel(REAL / name)


@pytest.mark.skipif(not REAL.exists(), reason="本机无味美真表目录")
class TestRealWeimeiSheets:
    async def test_real_numbers(self):
        out, _ = await ask("整体出成率", _real("味美10月-01-日汇总.xlsx"), "q3_explicit")
        assert rl._fmt(out.result.rows[0][0], pct=True) == "97.42%"           # 第 3 条
        out, _ = await ask("最低", _real("味美10月-01-日汇总.xlsx"), "q17")
        row = dict(zip(out.result.columns, out.result.rows[0]))
        assert (row["日期"], row["废品kg"]) == ("2026-10-07", 1381.0)         # 第 17 条
        out, _ = await ask("停机", _real("味美10月-02-机台开机停机.xlsx"), "q5")
        assert out.result.rows[0][:2] == ["RAV.C", 984]                       # 第 5 条
        out, _ = await ask("计划", _real("agg/味美10月-08a-计划品项达成率_按日.xlsx"), "q9")
        row = dict(zip(out.result.columns, out.result.rows[0]))
        assert (row["日期"], row["原表计划品项数"], row["已产出计划品项数"]) == ("2026-10-09", 7, 4)  # 9/10
        out, _ = await ask("周", _real("agg/味美10月-08b-计划品项达成率_按周.xlsx"), "q12")
        assert out.result.rows[0][0] == "2026-W41" and "68.75%" in out.text   # 第 12 条
        out, _ = await ask("没生产", _real("味美10月-07-计划vs实产_品项.xlsx"), "q14")
        assert out.result.matched_rows == 20 and out.result.rows[0][:2] == ["22002", 5]  # 第 14 条
        out, _ = await ask("肉温", _real("agg/味美10月-06-拌馅_日汇总.xlsx"), "q15")
        cols = out.result.columns
        got = {(r[cols.index("日期")], r[cols.index("产品编码")], r[cols.index("肉温超出3到5度趟次")])
               for r in out.result.rows}
        assert got == {("2026-10-03", "21024", 3), ("2026-10-24", "21004M", 5)}  # 第 15 条
        out, _ = await ask("10月一共生产了多少批？总产量多少？", pd.read_excel(REAL / "v2" / "味美10月-04-醒发蒸制.xlsx"), "q9")
        assert out.kind == "refuse"                                           # 第 1 条
        out, _ = await ask("10月卷膜用量最大的是哪种？", _real("味美10月-02-机台开机停机.xlsx"), "q9")
        assert out.kind == "refuse"                                           # 第 6 条
        out, _ = await ask("10月生产成本是多少？", _real("味美10月-01-日汇总.xlsx"), "q9")
        assert out.kind == "refuse" and "良品kg" in out.text                  # 第 16 条
