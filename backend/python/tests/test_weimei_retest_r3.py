"""味美 SmartBI「问数据」11-19 复测剩余问题 (第一批: 页脚把批号当数值求和 / 「计划与实际」选错表)。

断言跑在真实入口 general_analysis_stream 上 (假连接 + 假 LLM, 数据形状取自 prod FOOD_0000_001
的 上传 5336 醒发蒸制 / 5319~5335 各表的真实列名与字段角色, 行数据为合成)。
"""
from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

import smartbi.api.chat as chat_mod
from tests.test_upload_auto_select import WEIMEI

FACTORY = "FOOD_0000_001"

# 5336 醒发蒸制: 字段角色取自 prod smart_bi_pg_field_definitions (批号是 TEXT 维度, 不是指标)
XINGFA_FIELDS = [
    ("日期", False, False, True), ("批号", False, True, False), ("品类", False, True, False),
    ("产品(意)", False, True, False), ("产品(中)", False, True, False), ("架次", True, False, False),
    ("进醒发房", False, False, False), ("进蒸箱", False, False, False), ("醒发分钟", True, False, False),
    ("温度℃", True, False, False), ("湿度%", True, False, False), ("备注", False, False, False),
]


def xingfa_rows():
    rows = []
    for i in range(60):
        cat = "刀切" if i % 2 == 0 else "花卷"
        rows.append({"品类": cat, "备注": None, "批号": 261001.0 + (i // 10), "日期": f"2026-10-{1 + i // 4:02d}",
                     "架次": float(i % 4 + 1), "湿度%": 80.0 + i % 5, "温度℃": 38.0 + i % 3,
                     "进蒸箱": "06:44", "产品(中)": "馒头", "产品(意)": "PANE", "进醒发房": "05:54",
                     "醒发分钟": 50.0 + i % 4})
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
    """按 SQL 特征回放 prod 形状的数据; 其余查询一律空结果。"""

    def __init__(self, tables, candidates):
        self.tables = tables            # upload_id -> (rows, field tuples)
        self.candidates = candidates
        self.sql = []

    async def fetch(self, sql, *args):
        self.sql.append(sql)
        if "array_agg(fd.original_name" in sql:
            return [{"id": c.upload_id, "sheet_name": c.sheet_name, "file_name": c.file_name,
                     "row_count": c.row_count, "field_names": c.field_names} for c in self.candidates]
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
        self.sql.append(sql)
        return {"id": 1}

    async def fetchval(self, sql, *args):
        self.sql.append(sql)
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


async def ask_stream(monkeypatch, question, tables, llm_answer="（桩）已回答。", plan=None):
    conn = FakeConn(tables, WEIMEI + [_xingfa_candidate()])

    async def _pool():
        return _Pool(conn)

    async def _stream(self, prompt, system_role, **kw):
        yield llm_answer

    async def _call(self, prompt, system_role="", **kw):
        return json.dumps(plan or {"intent": "none"}, ensure_ascii=False)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(chat_mod.InsightGenerator, "_call_llm_stream_text", _stream)
    monkeypatch.setattr(chat_mod.InsightGenerator, "_call_llm", _call)
    monkeypatch.setattr(chat_mod, "_chat_cache_get", lambda _k: None)
    monkeypatch.setattr(chat_mod, "_chat_cache_set", lambda *a: None)
    resp = await chat_mod.general_analysis_stream(chat_mod.GeneralAnalysisRequest(query=question), _Request())
    body = ""
    async for chunk in resp.body_iterator:
        body += chunk.decode() if isinstance(chunk, bytes) else chunk
    return _parse_sse(body)


def _xingfa_candidate():
    from smartbi.services.upload_auto_select import UploadCandidate
    return UploadCandidate(5336, "醒发蒸制", "味美10月-04-醒发蒸制.xlsx", 2259, [f[0] for f in XINGFA_FIELDS])


# ------------------------------ 1. 页脚只对指标列聚合 ------------------------------

async def test_1_footer_chart_never_plots_a_dimension_column_such_as_batch_number(monkeypatch):
    ev = await ask_stream(monkeypatch, "10月醒发平均多少分钟？温湿度有无异常？",
                          {5336: (xingfa_rows(), XINGFA_FIELDS)})
    assert any(e == "data_source" and d["uploadId"] == 5336 for e, d in ev), [e for e, _ in ev]
    charts = next(d for e, d in ev if e == "charts")
    names = [s["name"] for c in charts for s in c["option"]["series"]]
    assert names, "没出图"
    assert "批号" not in names, names                       # 改前: ['批号', '湿度%', '温度℃']
    # 阴性对照: 真正的指标列照常画 (页脚照常聚合)
    assert {"湿度%", "温度℃"} <= set(names), names


def test_1_measure_filter_keeps_real_measures_and_drops_roles_that_say_dimension():
    from smartbi.services.question_chart import measure_columns_only
    meta = [{"original_name": n, "is_measure": m} for n, m, _d, _t in XINGFA_FIELDS]
    assert measure_columns_only(["批号", "架次", "醒发分钟", "温度℃"], meta) == ["架次", "醒发分钟", "温度℃"]
    # 没有字段定义 (角色未知): 保留数值列, 但编码类列名仍挡掉
    assert measure_columns_only(["批号", "良品kg", "产品编码"], None) == ["良品kg"]


# ------------------------------ 2. 「计划与实际」按口径 A 选计划品项表 ------------------------------

@pytest.mark.parametrize("question, expected", [
    ("10月计划与实际差多少？", 5334),                    # 改前: 5320 机台开机停机 (计划开机/实际开机列名撞词)
    ("10月计划和实际差了多少", 5334),
    ("10月哪台机停机时间最长？主要原因？", 5320),         # 第 5 题不能被带偏
    ("10月计划开机和实际开机差多少分钟", 5320),           # 明说开机/分钟: 仍是机台表
    ("计划与实际的停机时长差多少", 5320),
])
def test_2_plan_vs_actual_prefers_plan_item_table_unless_machine_words(question, expected):
    from smartbi.services.upload_auto_select import choose_upload
    assert choose_upload(question, WEIMEI).upload_id == expected


async def test_2_stream_entry_selects_plan_item_table_for_plan_vs_actual(monkeypatch):
    plan_rows = [{"日期": "2026-10-01", "已产出计划品项数": 4, "原表计划品项数": 5,
                  "计划品项达成率": 0.8, "计划外生产品项数": 1, "备注": None, "实产良品kg": 100.0}]
    fields = [("日期", False, False, True), ("已产出计划品项数", True, False, False),
              ("计划品项达成率", True, False, False), ("计划外生产品项数", False, True, False),
              ("备注", True, False, False), ("原表计划品项数", False, False, False),
              ("实产良品kg", True, False, False)]
    ev = await ask_stream(monkeypatch, "10月计划与实际差多少？", {5334: (plan_rows, fields)})
    sel = [d for e, d in ev if e == "data_source"]
    assert sel and sel[0]["uploadId"] == 5334 and sel[0]["label"] == "计划品项达成率_按日", sel
