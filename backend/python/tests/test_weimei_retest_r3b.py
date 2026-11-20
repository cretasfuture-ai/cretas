"""味美 SmartBI「问数据」11-19 复测剩余问题 (第二批: 卷膜「全月一共」答成单品类 / 「有哪些计划外生产」选错表)。

断言跑在真实入口 general_analysis_stream 的最终 done.answer 上 (假连接 + 假 LLM; 数据形状取自 prod
FOOD_0000_001 上传 5321 卷膜消耗 / 5323 计划vs实产_品项 的真实列名与字段角色, 行数据为合成)。
"""
from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

import smartbi.api.chat as chat_mod
from tests.test_upload_auto_select import WEIMEI

FACTORY = "FOOD_0000_001"

# 5321: FIL20-C 52 卷, 其余 15 个品类共 81 卷, 合计 133 (与 prod 一致)
FILM_FIELDS = [("日期", False, True, True), ("周", False, False, True), ("卷膜编码", False, True, False),
               ("用量(卷)", True, False, False)]


def film_rows():
    rows = []
    for i in range(13):                       # FIL20-C: 13 行 x 4 = 52
        rows.append({"周": "2026-W40", "日期": f"2026-10-{i + 1:02d}T00:00:00", "用量(卷)": 4.0, "卷膜编码": "FIL20-C"})
    others = [7, 2, 7, 4, 3, 1, 12, 12, 6, 7, 1, 2, 1, 10, 6]   # 15 个品类, 合计 81
    for k, qty in enumerate(others):
        rows.append({"周": "2026-W41", "日期": f"2026-10-{k + 1:02d}T00:00:00", "用量(卷)": float(qty),
                     "卷膜编码": f"FIL{k + 30}"})
    return rows


PLAN_FIELDS = [("日期", False, False, True), ("周", True, False, False), ("产品编码", False, True, False),
               ("结论", True, False, False), ("计划产线", False, True, True), ("计划内", True, False, False),
               ("实产良品kg", True, False, False)]


def plan_rows():
    outside = [("2026-10-08", 21003.0, 2613.6), ("2026-10-09", 210021.0, 1809.0), ("2026-10-10", None, 324.0),
               ("2026-10-19", 210016.0, 3169.8), ("2026-10-23", 260066.0, 4708.0), ("2026-10-28", 210016.0, 2953.8),
               ("2026-10-28", 23006.0, 3548.4), ("2026-10-29", 23004.0, 704.0), ("2026-10-30", 23002.0, 2878.4)]
    rows = [{"周": "2026-W41", "日期": d, "结论": "计划外生产", "计划内": "否", "产品编码": c,
             "计划产线": None, "实产良品kg": kg} for d, c, kg in outside]
    rows += [{"周": "2026-W40", "日期": "2026-10-02", "结论": "按计划产出", "计划内": "是", "产品编码": 22002.0,
              "计划产线": "A线", "实产良品kg": 100.0}] * 3
    return rows


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *a):
        return False


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


class FakeConn:
    def __init__(self, tables):
        self.tables = tables

    async def fetch(self, sql, *args):
        if "array_agg(fd.original_name" in sql:
            return [{"id": c.upload_id, "sheet_name": c.sheet_name, "file_name": c.file_name,
                     "row_count": c.row_count, "field_names": c.field_names} for c in WEIMEI]
        uid = args[0] if args and isinstance(args[0], int) else None
        if uid in self.tables:
            rows, fields = self.tables[uid]
            if "smart_bi_pg_field_definitions fd" in sql:
                return [{"original_name": n, "standard_name": n, "is_measure": m, "is_dimension": d, "is_time": t}
                        for n, m, d, t in fields]
            if "FROM smart_bi_dynamic_data d" in sql and "d.row_data" in sql:
                return [{"row_data": json.dumps(r, ensure_ascii=False)} for r in rows]
        return []

    async def fetchrow(self, sql, *args):
        return {"id": 1}

    async def fetchval(self, sql, *args):
        uid = args[0] if args else None
        if uid in self.tables and "COUNT(*)" in sql:
            return len(self.tables[uid][0])
        return 0

    async def execute(self, *a, **k):
        return "OK"


class _Request:
    def __init__(self):
        self.state = SimpleNamespace(factory_id=FACTORY, user_id=None, role="factory_super_admin")

    async def is_disconnected(self):
        return False


def _parse_sse(body: str):
    events = []
    for block in body.split("\n\n"):
        if not block.strip():
            continue
        ev = next((l[7:] for l in block.splitlines() if l.startswith("event: ")), None)
        data = next((l[6:] for l in block.splitlines() if l.startswith("data: ")), "null")
        events.append((ev, json.loads(data)))
    return events


async def ask_stream(monkeypatch, question, tables, llm_answer, plan):
    prompts = []

    async def _pool():
        return _Pool(FakeConn(tables))

    async def _stream(self, prompt, system_role="", **kw):
        prompts.append(prompt)
        yield llm_answer

    async def _call(self, prompt, system_role="", **kw):
        return json.dumps(plan, ensure_ascii=False)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(chat_mod.InsightGenerator, "_call_llm_stream_text", _stream)
    monkeypatch.setattr(chat_mod.InsightGenerator, "_call_llm", _call)
    monkeypatch.setattr(chat_mod, "_chat_cache_get", lambda _k: None)
    monkeypatch.setattr(chat_mod, "_chat_cache_set", lambda *a: None)
    resp = await chat_mod.general_analysis_stream(chat_mod.GeneralAnalysisRequest(query=question), _Request())
    body = ""
    async for chunk in resp.body_iterator:
        body += chunk.decode() if isinstance(chunk, bytes) else chunk
    events = _parse_sse(body)
    done = next(d for e, d in events if e == "done")
    return events, done, prompts


FILM_Q = "10月卷膜用量最大的是哪种？全月一共多少卷？"
FILM_PLAN = {"intent": "compute", "group_by": ["卷膜编码"],
             "metrics": [{"name": "总用量", "func": "sum", "col": "用量(卷)"}],
             "sort_by": "总用量", "descending": True, "limit": 1}


# ------------------------------ 3. 「全月一共」必须是合计 ------------------------------

async def test_3_final_answer_that_calls_the_biggest_group_the_monthly_total_is_corrected(monkeypatch):
    wrong = "**结论**：FIL20-C 用量最大，共 52 卷，占比约 39%。全月一共用了 52 卷。"
    events, done, prompts = await ask_stream(monkeypatch, FILM_Q, {5321: (film_rows(), FILM_FIELDS)}, wrong, FILM_PLAN)
    assert any(e == "data_source" and d["uploadId"] == 5321 for e, d in events)
    assert "总用量 = 133" in prompts[0]                        # 结果块里本来就有 133, 小模型没用 (改前也是这样)
    assert "133" in done["answer"], done["answer"]             # 改前: done.answer 里没有 133
    assert "一共用了 52 卷" not in done["answer"]
    assert done["answer_corrected"] is True                    # 前端据此用 done.answer 替换已流出的文字
    assert "FIL20-C 用量最大，共 52 卷" in done["answer"]       # 阴性对照: 对的那半句 (最大品类 52) 原样保留


async def test_3_answer_that_already_has_the_right_total_is_left_alone(monkeypatch):
    right = "FIL20-C 用量最大，共 52 卷。全月一共用了 133 卷。"
    _, done, _ = await ask_stream(monkeypatch, FILM_Q, {5321: (film_rows(), FILM_FIELDS)}, right, FILM_PLAN)
    assert done["answer"] == right and done["answer_corrected"] is False


def test_3_totals_check_only_fires_when_the_question_asks_for_a_total_and_is_idempotent():
    import pandas as pd
    from smartbi.services import row_level_qa as rl
    df = pd.DataFrame(film_rows())
    res_total = rl.execute_plan(df, rl.validate_plan(FILM_PLAN, df), FILM_Q)
    res_plain = rl.execute_plan(df, rl.validate_plan(FILM_PLAN, df), "10月卷膜用量最大的是哪种？")
    fixed, changed = rl.reconcile_answer_with_totals("FIL20-C 最多，一共 52 卷", res_total)
    assert changed and "133" in fixed
    again, changed2 = rl.reconcile_answer_with_totals(fixed, res_total)
    assert not changed2 and again == fixed
    same, changed3 = rl.reconcile_answer_with_totals("FIL20-C 最多，共 52 卷", res_plain)   # 没问总数: 不动
    assert not changed3 and same == "FIL20-C 最多，共 52 卷"
    appended, changed4 = rl.reconcile_answer_with_totals("FIL20-C 最多", res_total)           # 找不到那一句: 末尾补更正
    assert changed4 and appended.startswith("FIL20-C 最多") and "133" in appended


def test_3_prompt_hint_names_the_top_group_instead_of_repeating_its_number():
    import pandas as pd
    from smartbi.services import row_level_qa as rl
    res = rl.execute_plan(pd.DataFrame(film_rows()), rl.validate_plan(FILM_PLAN, pd.DataFrame(film_rows())), FILM_Q)
    text = rl.render_block(FILM_Q, res)
    assert "排名第一的分组「FIL20-C」只是最大项, 不是总数" in text
    assert "(52)" not in text


# ------------------------------ 4. 「有哪些计划外生产」选计划vs实产明细表 ------------------------------

@pytest.mark.parametrize("question, expected", [
    ("10月有哪些计划外生产？", 5323),                    # 改前: 5334 按日表 (只有计划外生产品项数一个计数)
    ("10月哪些产品是计划外生产的", 5323),
    ("列出计划外生产", 5323),
    ("哪天计划外生产最多", 5334),                        # 阴性对照: 问哪天/几个仍走按日表
    ("10月计划外生产了几个品项", 5334),
])
def test_4_plan_outside_listing_picks_the_detail_table(question, expected):
    from smartbi.services.upload_auto_select import choose_upload
    assert choose_upload(question, WEIMEI).upload_id == expected


async def test_4_stream_entry_lists_every_actual_row_and_marks_missing_code(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外生产"}]}
    events, done, prompts = await ask_stream(
        monkeypatch, "10月哪些产品是计划外生产的", {5323: (plan_rows(), PLAN_FIELDS)}, "（桩）", plan)
    sel = [d for e, d in events if e == "data_source"]
    assert sel and sel[0]["uploadId"] == 5323, sel
    block = prompts[0]
    assert "命中 9 / 12 行" in block
    for code in ("21003", "210021", "210016", "260066", "23006", "23004", "23002"):
        assert f"| {code} |" in block, code
    assert block.count("计划外生产 |") == 9                      # 表里实际 9 行, 不凑 10
    assert "编码缺失" in block                                  # 产品编码为空的 1 行也列出并标注
