"""味美 SmartBI「问数据」11-19 夜间上线复测 (#1956/#1957 在线上没生效) 的修复回归。

断言全部跑在产品真实入口 general_analysis_stream (web-admin 问数据页调的 /api/chat/general-analysis-stream)
的 SSE 事件 / done.answer / 注入 LLM 的提示词上; 假连接 + 假 LLM, 表结构与字段角色取自 prod FOOD_0000_001
(上传 5319/5323/5334/5336), 行数据为合成。每条注释写明「改前」读数。
"""
from __future__ import annotations

import io
import json
from types import SimpleNamespace

import pytest

import smartbi.api.chat as chat_mod
from smartbi.services.upload_aggregate_cache import get_cache
from smartbi.services.upload_auto_select import UploadCandidate
from tests.test_upload_auto_select import WEIMEI
from tests.test_weimei_retest_r3 import XINGFA_FIELDS, xingfa_rows
from tests.test_weimei_retest_r3b import FILM_FIELDS, FILM_PLAN, FILM_Q, PLAN_FIELDS, film_rows, plan_rows

FACTORY = "FOOD_0000_001"
XINGFA = UploadCandidate(5336, "醒发蒸制", "味美10月-04-醒发蒸制.xlsx", 2259, [f[0] for f in XINGFA_FIELDS])


@pytest.fixture(autouse=True)
def _clean_agg_cache():
    from smartbi.services import row_level_qa as rl
    get_cache().clear()
    rl._PLAN_CACHE.clear()          # 规划按 (原话, 表头) 缓存: 各用例的规划桩互不串
    yield
    get_cache().clear()


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
    def __init__(self, tables, candidates):
        self.tables, self.candidates = tables, candidates

    async def fetch(self, sql, *args):
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


async def ask(monkeypatch, question, tables, *, answer="（桩）", plans=None, candidates=None):
    """plans: 规划器依次返回的计划 (最后一个重复); 返回 (events, done, 注入 LLM 的提示词列表, 规划器调用次数)。"""
    prompts, calls = [], []
    seq = list(plans or [{"intent": "none"}])

    async def _pool():
        return _Pool(FakeConn(tables, candidates or (WEIMEI + [XINGFA])))

    async def _stream(self, prompt, system_role="", **kw):
        prompts.append(prompt)
        yield answer

    async def _call(self, prompt, system_role="", **kw):
        calls.append(prompt)
        return json.dumps(seq[min(len(calls) - 1, len(seq) - 1)], ensure_ascii=False)

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
    return events, done, prompts, calls


def _chart_titles(events):
    return [c.get("title", "") for e, d in events if e == "charts" for c in d]


def _series_names(events):
    return [s["name"] for e, d in events if e == "charts" for c in d for s in c["option"]["series"]]


def _bundle(fields, measures, primary, top5):
    return {"field_meta": [{"original_name": n, "standard_name": n, "is_measure": m, "is_dimension": d, "is_time": t}
                           for n, m, d, t in fields],
            "measures": measures, "dims": [n for n, _m, d, _t in fields if d], "real_total_rows": 2259,
            "agg_lines": ["- 全量数据行数: 2259"], "top5_by_dim": top5, "primary_measure": primary,
            "compute_time_s": 1.0}


# ============ 1. 页脚批号: 缓存的聚合包按旧字段角色把批号当指标, Top 5 图照出 ============

STALE_XINGFA_FIELDS = [(n, True if n == "批号" else m, False if n == "批号" else d, t) for n, m, d, t in XINGFA_FIELDS]


async def test_1_stale_cached_aggregate_bundle_with_batch_number_as_measure_no_longer_draws_the_footer_chart(monkeypatch):
    # prod 5336 的 L2 聚合包 (2026-11-19 16:45 生成): measures 里有 批号, primary_measure=批号
    stale = _bundle(STALE_XINGFA_FIELDS, ["批号", "架次", "醒发分钟", "温度℃", "湿度%"], "批号",
                    {"品类": [{"label": "刀切", "total": 188709501.0}, {"label": "花卷", "total": 94000000.0},
                              {"label": "刮包60", "total": 47505600.0}]})
    get_cache().set(5336, stale)
    events, done, prompts, _ = await ask(monkeypatch, "10月醒发平均多少分钟？温湿度有无异常？",
                                         {5336: (xingfa_rows(), XINGFA_FIELDS)})
    assert any(e == "data_source" and d["uploadId"] == 5336 for e, d in events)
    titles = _chart_titles(events)
    assert not any("批号" in t for t in titles), titles          # 改前: ['Top 5 品类 (按 批号)', ...]
    assert "批号" not in _series_names(events)
    assert {"醒发分钟", "温度℃", "湿度%"} <= set(_series_names(events))   # 阴性对照: 指标列照常出图
    assert "188709501" not in "".join(prompts) and "188,709,501" not in "".join(prompts)


async def test_1_fresh_bundle_with_unchanged_roles_is_still_used_not_recomputed(monkeypatch):
    fresh = _bundle(XINGFA_FIELDS, ["架次", "醒发分钟", "温度℃", "湿度%"], "架次",
                    {"品类": [{"label": "刀切", "total": 99.0}, {"label": "花卷", "total": 80.0}]})
    get_cache().set(5336, fresh)
    await ask(monkeypatch, "10月醒发平均多少分钟？温湿度有无异常？", {5336: (xingfa_rows(), XINGFA_FIELDS)})
    assert get_cache().get(5336) is fresh                         # 角色没变: 缓存包原样保留 (不白白重算)


def test_1_bundle_staleness_compares_roles_of_shared_fields_only():
    from smartbi.services.upload_aggregate_cache import bundle_is_stale_for_field_meta as stale
    meta = [{"original_name": n, "is_measure": m, "is_dimension": d, "is_time": t} for n, m, d, t in XINGFA_FIELDS]
    old = _bundle(STALE_XINGFA_FIELDS, [], None, {})
    assert stale(old, meta) is True
    assert stale(_bundle(XINGFA_FIELDS, [], None, {}), meta) is False
    assert stale(old, []) is False and stale(None, meta) is False   # 没有可比的字段定义: 不判过期


# ============ 5. 页脚固定讲「各周人数」: Top 5 只用第一个指标列 ============

DAY_FIELDS = [("日期", False, False, True), ("周", False, True, False), ("人数", True, False, False),
              ("出勤率", True, False, False), ("批次数", True, False, False),
              ("出成率(良品/(良品+废品+次品))", True, False, False), ("产能利用率(良品/理论)", True, False, False),
              ("良品kg", True, False, False), ("废品kg", True, False, False), ("次品kg", True, False, False),
              ("理论产能kg", True, False, False), ("当日说明", False, False, False)]


def day_rows():
    return [{"日期": f"2026-10-{i + 1:02d}", "周": f"2026-W{40 + i // 7}", "人数": 30.0 + i % 3, "出勤率": 0.9,
             "批次数": 3.0 + i % 4, "出成率(良品/(良品+废品+次品))": 0.97, "产能利用率(良品/理论)": 0.81,
             "良品kg": 1000.0 + i * 10, "废品kg": 20.0, "次品kg": 5.0, "理论产能kg": 1200.0, "当日说明": None}
            for i in range(27)]


def _day_bundle():
    return _bundle(DAY_FIELDS, ["人数", "出勤率", "批次数", "出成率(良品/(良品+废品+次品))", "良品kg"], "人数",
                   {"周": [{"label": "2026-W42", "total": 268.0}, {"label": "2026-W40", "total": 111.0}]})


@pytest.mark.parametrize("question", ["10月当日说明有什么特殊情况？", "10月哪天出成率最低？当天废品多少公斤？"])
async def test_5_footer_chart_does_not_fall_back_to_the_first_measure_when_question_is_about_something_else(
        monkeypatch, question):
    get_cache().set(5319, _day_bundle())
    events, _, _, _ = await ask(monkeypatch, question, {5319: (day_rows(), DAY_FIELDS)})
    assert any(e == "data_source" and d["uploadId"] == 5319 for e, d in events)
    assert not any("Top 5" in t for t in _chart_titles(events)), _chart_titles(events)   # 改前: Top 5 周 (按 人数)
    assert "人数" not in _series_names(events)                                           # 改前: 各周人数


async def test_5_question_about_the_primary_measure_keeps_its_footer_chart(monkeypatch):
    get_cache().set(5319, _day_bundle())
    events, _, _, _ = await ask(monkeypatch, "10月哪天的人数最多", {5319: (day_rows(), DAY_FIELDS)})
    assert "人数" in _series_names(events) or any("人数" in t for t in _chart_titles(events))


async def test_5_question_that_picks_a_measure_gets_that_measure_not_the_first_one(monkeypatch):
    get_cache().set(5319, _day_bundle())
    events, _, _, _ = await ask(monkeypatch, "10月哪天出成率最低？当天废品多少公斤？", {5319: (day_rows(), DAY_FIELDS)})
    names = _series_names(events)
    assert any("出成率" in n for n in names) and "废品kg" in names, names


# ============ 3b. 卷膜: 同一回答里另一处写了 133, 结论的 52 也要改 ============

async def test_3_conclusion_that_says_total_is_52_is_fixed_even_though_133_appears_elsewhere(monkeypatch):
    wrong = ("**结论**：10月卷膜用量最大的是 FIL20-C，全月一共 52 卷。\n"
             "**关键发现**：FIL20-C 卷膜用量为 52 卷，占全月总用量 133 卷的 39.1%。")
    events, done, _, _ = await ask(monkeypatch, FILM_Q, {5321: (film_rows(), FILM_FIELDS)},
                                   answer=wrong, plans=[FILM_PLAN])
    ans = done["answer"]
    assert "全月一共 52 卷" not in ans                              # 改前: 原样保留 (133 在正文里, 校验直接放行)
    assert "133" in ans.split("关键发现")[0]                         # 结论句里是代码算的 133
    assert done["answer_corrected"] is True
    assert "占全月总用量 133 卷的 39.1%" in ans                      # 阴性对照: 对的那句不动


async def test_3_a_clause_with_the_total_word_and_an_unrelated_number_is_left_alone(monkeypatch):
    ok = "FIL20-C 用量最大，共 52 卷；全月一共用了 133 卷，平均每个品类 8.3 卷。"
    _, done, _, _ = await ask(monkeypatch, FILM_Q, {5321: (film_rows(), FILM_FIELDS)}, answer=ok, plans=[FILM_PLAN])
    assert done["answer"] == ok and done["answer_corrected"] is False


# ============ 4. 计划外生产: 「10月」被规划成 日期=='2026-10' 命中 0 行 ============

OUTSIDE_Q = "10月有哪些计划外生产？"


async def test_4_month_written_as_date_equality_still_lists_the_nine_rows(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外生产"},
                                              {"col": "日期", "op": "==", "value": "2026-10"}]}
    _, done, prompts, _ = await ask(monkeypatch, OUTSIDE_Q, {5323: (plan_rows(), PLAN_FIELDS)}, plans=[plan])
    block = prompts[0]
    assert "命中 9 / 12 行" in block                                # 改前: 命中 0 / 12 行 -> 「10月没有计划外生产」
    assert block.count("计划外生产 |") == 9 and "编码缺失" in block
    assert "无满足条件的行" not in block


async def test_4_planner_value_that_does_not_exist_is_sent_back_once_with_the_real_values(monkeypatch):
    bad = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外"}]}
    good = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划外生产"}]}
    _, _, prompts, calls = await ask(monkeypatch, OUTSIDE_Q, {5323: (plan_rows(), PLAN_FIELDS)}, plans=[bad, good])
    assert len(calls) == 2 and "没有取值「计划外」" in calls[1] and "计划外生产" in calls[1]
    assert "命中 9 / 12 行" in prompts[0]


async def test_4_value_that_really_is_absent_is_answered_as_zero_rows_not_as_uncomputed(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "停线"}]}
    _, _, prompts, _ = await ask(monkeypatch, "10月计划vs实产里有没有停线的品项？", {5323: (plan_rows(), PLAN_FIELDS)}, plans=[plan],
                             candidates=PLAN_DETAIL)
    assert "命中 0 / 12 行" in prompts[0] and "行级计算未完成" not in prompts[0]


# ============ 12. 计划与实际: 浏览器那次答成按日平均 0.80 ============

def plan_day_rows():
    plan = [10, 10, 10, 10, 10, 5, 5, 5, 5, 5]                    # 合计 75
    done = [6, 6, 6, 6, 6, 5, 5, 5, 5, 5]                         # 合计 55
    rows = [{"日期": f"2026-10-{i + 1:02d}", "原表计划品项数": p, "已产出计划品项数": d,
             "计划品项达成率": d / p, "计划外生产品项数": 1 if i < 9 else 0, "备注": None, "实产良品kg": 100.0}
            for i, (p, d) in enumerate(zip(plan, done))]
    return rows


PLAN_DAY_FIELDS = [("日期", False, False, True), ("已产出计划品项数", True, False, False),
                   ("计划品项达成率", True, False, False), ("计划外生产品项数", False, True, False),
                   ("备注", True, False, False), ("原表计划品项数", False, False, False),
                   ("实产良品kg", True, False, False)]
PLAN_DAY = [c for c in WEIMEI if c.upload_id == 5334]
PLAN_DETAIL = [c for c in WEIMEI if c.upload_id == 5323]


@pytest.mark.parametrize("llm_plan", [
    {"intent": "none"},
    {"intent": "compute", "metrics": [{"name": "均值", "func": "mean", "col": "计划品项达成率"}]},   # 浏览器那次的写法
])
async def test_12_plan_vs_actual_is_the_weighted_item_rate_whatever_the_planner_says(monkeypatch, llm_plan):
    _, _, prompts, calls = await ask(monkeypatch, "10月计划与实际差多少？", {5334: (plan_day_rows(), PLAN_DAY_FIELDS)},
                                     plans=[llm_plan], candidates=PLAN_DAY)
    block = prompts[0]
    assert "计划品项数合计 | 已产出品项数合计 | 缺口合计(计划-产出)" in block
    assert "| 75 | 55 | 20 | 73.33% | 9 |" in block                  # 改前(浏览器): 均值 0.80 + 「37 天」
    assert calls == []                                              # 不经规划器: 同一问题每次一致


async def test_12_other_plan_questions_still_go_to_the_planner(monkeypatch):
    _, _, _, calls = await ask(monkeypatch, "10月哪天计划品项数最多", {5334: (plan_day_rows(), PLAN_DAY_FIELDS)},
                               plans=[{"intent": "compute", "sort_by": "原表计划品项数", "limit": 1}], candidates=PLAN_DAY)
    assert len(calls) == 1


# ============ 4b. 按日表问「哪一周」: 程序按周汇总 ============

async def test_4_week_question_on_daily_table_is_rolled_up_by_the_program(monkeypatch):
    rows = [{"日期": d, "原表计划品项数": p, "已产出计划品项数": o, "计划品项达成率": o / p, "计划外生产品项数": 0,
             "备注": None, "实产良品kg": 1.0}
            for d, p, o in [("2026-10-05", 10, 1), ("2026-10-09", 2, 2), ("2026-10-23", 4, 3), ("2026-10-30", 4, 2)]]
    _, _, prompts, calls = await ask(monkeypatch, "哪一周计划品项达成率最低？", {5334: (rows, PLAN_DAY_FIELDS)},
                                     plans=[{"intent": "compute", "sort_by": "计划品项达成率", "limit": 1}],
                                     candidates=PLAN_DAY)
    block = prompts[0]
    assert "| 2026-W41 | 25.00% |" in block                        # W41 = 10/05+10/09: (1+2)/(10+2) 加权; 逐日平均会是 55%
    assert block.index("2026-W41") < block.index("2026-W44") < block.index("2026-W43")   # 升序: 最低在前
    assert "按日表" in block and "按周汇总" in block
    assert calls == []


# ============ 3. 编码不加千分位 / 导入方的「#」前缀 ============

def code_rows(prefix=""):
    codes = [22002.0, 260403.0, 210016.0]
    return [{"周": "2026-W40", "日期": f"2026-10-{i + 1:02d}", "结论": "计划了未生产" if i < 9 else "按计划产出", "计划内": "是",
             "产品编码": (f"{prefix}{int(codes[i % 3])}" if prefix else codes[i % 3]), "计划产线": "A线",
             "实产良品kg": 100.0 * i} for i in range(20)]


CODE_PLAN = {"intent": "compute", "filters": [{"col": "结论", "op": "==", "value": "计划了未生产"}],
             "group_by": ["产品编码"], "metrics": [{"name": "次数", "func": "count", "col": None}],
             "sort_by": "次数", "descending": True, "limit": 5}
CODE_Q = "10月哪些产品计划了但没有生产？哪个最多？"


async def test_3_code_column_is_not_summarised_as_a_number_in_the_prompt(monkeypatch):
    _, _, prompts, _ = await ask(monkeypatch, CODE_Q, {5323: (code_rows(), PLAN_FIELDS)}, plans=[CODE_PLAN])
    p = prompts[0]
    assert "260,403" not in p and "22,002" not in p                 # 改前: 产品编码: 最大=260,403.00, 最小=22,002.00
    assert "产品编码: 总计" not in p and "- 产品编码:" not in p
    assert '"产品编码": "22002"' in p                                # 样本里按文本写, 不是 22002.0
    assert "实产良品kg: 总计" in p                                   # 阴性对照: 指标列统计照旧


async def test_3_answer_that_writes_the_code_with_a_thousands_separator_is_corrected(monkeypatch):
    wrong = "产品编码 22,002 最多，其次是 260,403，共 3 次。"
    _, done, _, _ = await ask(monkeypatch, CODE_Q, {5323: (code_rows(), PLAN_FIELDS)}, answer=wrong, plans=[CODE_PLAN])
    assert "22002" in done["answer"] and "260403" in done["answer"]
    assert "22,002" not in done["answer"] and "260,403" not in done["answer"]
    assert done["answer_corrected"] is True


async def test_3_a_quantity_that_merely_looks_like_a_code_keeps_its_separator(monkeypatch):
    text = "产品编码 22,002 最多；良品合计 22,002 kg。"
    _, done, _, _ = await ask(monkeypatch, CODE_Q, {5323: (code_rows(), PLAN_FIELDS)}, answer=text, plans=[CODE_PLAN])
    assert done["answer"] == "产品编码 22002 最多；良品合计 22,002 kg。"      # 带单位的数量不动


async def test_3_hash_prefix_added_by_the_importer_is_not_shown_and_filters_still_match(monkeypatch):
    plan = {"intent": "compute", "filters": [{"col": "产品编码", "op": "==", "value": "22002"}]}
    _, _, prompts, _ = await ask(monkeypatch, "10月计划vs实产里产品编码22002有几天", {5323: (code_rows("#"), PLAN_FIELDS)},
                             plans=[plan], candidates=PLAN_DETAIL)
    block = prompts[0].split("## 全表精确计算结果")[1]
    assert "命中 7 / 20 行" in block                                 # 库里是 #22002, 规划里写 22002: 去前缀后仍命中
    assert "| 22002 |" in block and "#22002" not in block


# ============ 上传真实入口: Java 调的 /api/excel/auto-parse ============

async def test_upload_entry_keeps_alphanumeric_codes_and_hash_prefixed_codes():
    import openpyxl
    from fastapi import UploadFile
    import smartbi.api.excel as ex

    def build(codes):
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.append(["周", "日期", "产品编码", "结论", "计划产线", "计划内", "实产良品kg"])
        for i, c in enumerate(codes):
            ws.append(["2026-W40", f"2026-10-{(i % 28) + 1:02d}", c, "按计划产出", "linea 2", "是", 100.0 + i])
        b = io.BytesIO()
        wb.save(b)
        return b.getvalue()

    async def parse(content):
        uf = UploadFile(filename="味美10月-07-计划vs实产_品项.xlsx", file=io.BytesIO(content))
        r = await ex.auto_parse_excel(
            file=uf, factory_id=FACTORY, use_cache=False, max_rows=500000, skip_empty_rows=True, calculate_stats=False,
            transpose=False, sheet_index=None, sheetIndex=None, sheet_name=None, sheetName=None, business_type=None,
            header_rows_override=None, headerRowsOverride=None, selected_region_start=None, selected_region_end=None,
            selectedRegionStart=None, selectedRegionEnd=None)
        assert r.success, r.errorMessage
        return [row["产品编码"] for row in r.previewData]

    plain = [21000 + i for i in range(1, 111)]
    for i in (21, 59, 100):
        plain[i] = "21014C"
    out = await parse(build(plain))
    assert len(out) == 110 and None not in out                       # 改前 (#1958 之前): 带字母的 3 行被清成 null
    assert out[21] == out[59] == out[100] == "21014C" and out[0] == "21001"
    hashed = await parse(build([f"#{c}" for c in plain]))
    assert hashed[21] == "#21014C" and hashed[0] == "#21001"          # 导入方的「#」写法原样入库 (展示层去掉)
