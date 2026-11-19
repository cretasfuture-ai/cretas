"""问数据 (AI) 的行级确定性计算。

背景 (味美 FOOD_0000_001 实测 17 条只对 3 条): 问答链只给 LLM「全表列统计 + 前几行样本」,
LLM 无法逐行定位 / 筛选 / 分组, 于是把别行字段拼在一起 (A1)、编日期 (A2)、
只从样本里挑 (A3)、用别的列冒充没有的指标 (A4)、对比率做简单平均 (A5)、
拒答时字段列表不全 (A10)。

做法: LLM 只负责把问题翻译成结构化查询计划 (JSON, 不含任何数值结论),
服务端用 pandas 在整份数据 (全部行) 上确定性执行 filter / groupby / sort / argmax /
加权比率, 把真实结果行交给 LLM 措辞。数值与行定位只来自计算结果;
算不出就明说, 不退回「样本里猜」。

本模块不依赖数据库驱动: DataFrame 由调用方给出 (或由 load_upload_frame 用 asyncpg 连接读出)。
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional, Sequence

import pandas as pd

logger = logging.getLogger(__name__)

# 单次问答最多载入的行数。超过则明说「算不了」, 不退回样本。
MAX_ROWS = 200_000
# 结果表最多展示行数 (其余只给计数)。
MAX_RESULT_ROWS = 30
# 并列最大值/最小值最多额外带出的行数。
MAX_TIE_ROWS = 20

LlmCall = Callable[[str, str], Awaitable[str]]


# ---------------------------------------------------------------------------
# 数据载入
# ---------------------------------------------------------------------------

class TooManyRows(Exception):
    pass


async def load_upload_frame(conn, upload_id: int, factory_id: str,
                            max_rows: int = MAX_ROWS) -> pd.DataFrame:
    """读出某次上传的全部行 (按租户 factory_id 过滤), 超过 max_rows 抛 TooManyRows。"""
    cnt = await conn.fetchval(
        """SELECT COUNT(*) FROM smart_bi_dynamic_data d
           JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
           WHERE d.upload_id = $1 AND u.factory_id = $2""",
        upload_id, factory_id,
    )
    if cnt is not None and int(cnt) > max_rows:
        raise TooManyRows(f"{int(cnt)} 行超过上限 {max_rows}")
    rows = await conn.fetch(
        """SELECT d.row_data FROM smart_bi_dynamic_data d
           JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
           WHERE d.upload_id = $1 AND u.factory_id = $2
           ORDER BY d.row_index NULLS LAST, d.id""",
        upload_id, factory_id,
    )
    data = [json.loads(r["row_data"]) if isinstance(r["row_data"], str) else r["row_data"]
            for r in rows]
    return pd.DataFrame(data)


def all_field_names(field_meta: Optional[Sequence[dict]], df: Optional[pd.DataFrame]) -> List[str]:
    """全部字段名 (不只 is_measure): 字段定义里的 + 数据框里实际有的, 保序去重。"""
    names: List[str] = []
    for f in field_meta or []:
        n = f.get("original_name")
        if n and n not in names:
            names.append(str(n))
    if df is not None:
        for c in df.columns:
            if c not in names:
                names.append(str(c))
    return names


# ---------------------------------------------------------------------------
# A4: 字段-问题匹配校验 (确定性)
# ---------------------------------------------------------------------------

# (指标名, 问题里出现即视为在问它的词, 字段名里出现任一即视为数据源有它)
_CONCEPTS = [
    ("产量/产出", ("产量", "产出", "生产了多少", "总产量"),
     ("产量", "产出", "良品", "出品", "成品", "产能")),
    ("出成率/良率", ("出成率", "良率"),
     ("出成率", "良率", "良品")),
    ("卷膜用量", ("卷膜",), ("卷膜",)),
    ("停机", ("停机",), ("停机",)),
    ("肉温", ("肉温",), ("肉温",)),
    ("成本/毛利", ("成本", "毛利", "利润"), ("成本", "毛利", "利润", "费用")),
    ("出勤/人数", ("出勤", "人均"), ("出勤", "人数")),
]


def find_missing_concept(question: str, field_names: Sequence[str]) -> Optional[str]:
    """问题点名了某指标, 而整个数据源没有任何字段名含它 -> 返回指标名。

    只看字段名关键词, 不看 is_measure (温度/分钟/架次不是 measure 也算有)。
    """
    q = question or ""
    joined = "".join(field_names)
    for label, triggers, col_tokens in _CONCEPTS:
        if any(t in q for t in triggers) and not any(tok in joined for tok in col_tokens):
            return label
    return None


def build_refusal(missing: str, field_names: Sequence[str]) -> str:
    """拒答: 明说没有该指标, 并列出数据源的全部字段。"""
    names = "、".join(field_names) if field_names else "(无)"
    return (
        "## 数据范围说明\n\n"
        f"当前数据源**没有「{missing}」这个指标**, 无法回答这个问题, 也不会拿别的字段代替。\n\n"
        f"**当前数据源的全部字段 (共 {len(field_names)} 个)**: {names}。\n\n"
        "**建议**: 换问上述字段能回答的问题, 或切换到含该指标的数据源再问。"
    )


# ---------------------------------------------------------------------------
# 计划: 提示词 / 解析 / 校验
# ---------------------------------------------------------------------------

PLAN_SYSTEM = (
    "你是数据查询规划器。只输出一个 JSON 对象, 不要输出任何数值结论, 不要回答问题本身。"
)


def _col_profile(df: pd.DataFrame, col: str) -> str:
    s = df[col]
    num = pd.to_numeric(s, errors="coerce")
    if num.notna().sum() >= max(1, int(0.8 * s.notna().sum())) and s.notna().sum() > 0:
        return f"数值, 范围 {num.min():g} ~ {num.max():g}"
    uniq = s.dropna().astype(str).unique()
    if len(uniq) <= 12:
        return "文本, 取值: " + " / ".join(v[:20] for v in uniq)
    return f"文本, {len(uniq)} 个不同值, 例: " + " / ".join(v[:20] for v in uniq[:3])


def build_plan_prompt(question: str, df: pd.DataFrame) -> str:
    cols = "\n".join(f"- {c} ({_col_profile(df, c)})" for c in df.columns)
    hint = ""
    if asks_week_on_non_week_table(question, df):
        hint = ("\n注意: 数据表没有「周」列, 是按日表。不要 group_by 周 (字段不存在); "
                "直接在日期行上排序/定位, 并在 note 写明「当前是按日表」。\n")
    return f"""数据表共 {len(df)} 行。全部字段:
{cols}
{hint}
用户问题: {question}

输出 JSON, 字段如下 (不用的可省略):
{{
 "intent": "compute" | "no_metric" | "none",
 "missing": "intent=no_metric 时写缺的指标名",
 "filters": [{{"col": "字段名", "op": "==|!=|>|>=|<|<=|contains|notnull", "value": 值}}],
 "group_by": ["字段名"],
 "metrics": [{{"name": "结果列名", "func": "sum|mean|count|min|max|nunique|ratio", "col": "字段名", "num": ["分子字段"], "den": ["分母字段(可多个, 求和)"]}}],
 "sort_by": "结果列名或字段名", "descending": true, "limit": 10,
 "breakdown_by": "对排名第一的分组再按该字段拆解 (如问原因)",
 "note": "口径说明 (如原表没有该数量, 改按品项数回答)"
}}
规则:
1. 问「最大/最小/最高/最低/哪一天/哪一行」且不要求分组汇总: 不写 group_by 和 metrics, 只写 sort_by + descending + limit(1) (服务端会返回整行)。
2. 问「哪台/哪个产品 ... 最多/累计」: group_by + metrics(sum/count) + sort_by。
3. 问「哪些 ... 没/超标/未」: 先用 filters 筛选, 再按需 group_by / 直接列行。
4. 「率/占比」类: 不要对率列求 mean; 用 func=ratio, num/den 填组成它的数量列 (如 出成率=良品/(良品+废品+次品))。
5. 字段名必须原样取自上面的列表。数据里没有被问的指标 (如问产量但无产量列、问成本但无成本列): intent=no_metric。问题不需要查具体数据 (闲聊/泛泛总结): intent=none。
6. 全部筛选/聚合都针对整张表, 不要自己估算数值。
7. 问「一共/总共/合计/总计」: 要的是求和 (sum), 不是最大值; 不要只取排名第一的一项。"""


# ---------------------------------------------------------------------------
# 确定性规则 (不经 LLM, 同一问题每次结果一致): 计划数量 / 缺口 / 周粒度 / 编码列
# ---------------------------------------------------------------------------

_WEEK_ASK_RE = re.compile(r"(哪一?周|每周|各周|按周|第[几一二三四五六七八九十\d]+周|周达成|周产量)")
TOTAL_WORDS = ("一共", "总共", "合计", "总计", "共计", "总数", "加起来")
_CODE_NAME_RE = re.compile(r"(编码|编号|代码|批号|单号|工号|货号|条码|(?<![A-Za-z])(?:id|ID|Id)(?![a-z]))")
_GAP_ASK_WORDS = ("没按计划", "未按计划", "没完成", "未完成", "没达成", "未达成", "缺口",
                  "缺几个", "差几个", "没做完", "没按期")


def asks_total(question: str) -> bool:
    return any(w in (question or "") for w in TOTAL_WORDS)


def is_code_column(name: str) -> bool:
    return bool(_CODE_NAME_RE.search(str(name)))


def identifier_columns(columns: Sequence[str], field_meta: Optional[Sequence[dict]],
                       df: Optional[pd.DataFrame] = None) -> set:
    """编码/批号类标识列: 按字段角色判断 (字段定义里是维度、不是指标也不是时间), 没有角色信息的列才退回列名词表。

    标识列的数值不是量: 不加千分位 (22002 不能写成 22,002), 不做求和/均值/首尾变化。
    给了 df 时, 角色是维度的数值列还要求值都是 >=1000 的整数才算标识: 小整数 (如被误标成维度的计数列)
    不会被格式化出千分位, 没有需要保护的东西, 也不该因此丢掉它的统计。
    字段定义说是指标的列, 即使列名带「编号」也仍按列名挡 (旧上传把批号存成数值时角色是指标)。
    """
    roles = {str(f.get("original_name")): f for f in (field_meta or []) if f.get("original_name")}
    out = set()
    for c in columns:
        f = roles.get(str(c))
        if f is not None and f.get("is_dimension") and not f.get("is_measure") and not f.get("is_time"):
            if df is not None and c in df.columns:
                num = pd.to_numeric(df[c], errors="coerce").dropna()
                if len(num) and (num != num.round()).any() | (num.abs().max() < 1000):
                    # 数值列但不像标识 (带小数 / 都是小数): 不挡
                    if not is_code_column(str(c)):
                        continue
            out.add(c)
        elif is_code_column(str(c)):
            out.add(c)
    return out


def asks_week_on_non_week_table(question: str, df: pd.DataFrame) -> bool:
    if not _WEEK_ASK_RE.search(question or ""):
        return False
    return not any(("周" in str(c) or "week" in str(c).lower()) for c in df.columns)


def _plan_count_columns(columns: Sequence[str]) -> Optional[tuple]:
    """(计划数列, 已产出数列): 两个都唯一才返回, 认不准不猜。"""
    def ok_plan(c: str) -> bool:
        return ("计划" in c and "数" in c and not any(w in c for w in ("已", "外", "率", "达成", "缺口")))
    def ok_done(c: str) -> bool:
        return ("计划" in c and "数" in c and "缺口" not in c
                and any(w in c for w in ("已产出", "已完成", "已达成")))
    plans = [c for c in columns if ok_plan(str(c))]
    dones = [c for c in columns if ok_done(str(c))]
    if len(plans) == 1 and len(dones) == 1:
        return plans[0], dones[0]
    return None


def with_gap_columns(df: pd.DataFrame) -> pd.DataFrame:
    """有「计划数 / 已产出数」成对列时加一列 缺口 = 计划 − 已产出 (逐行现算, 不是计划数本身)。"""
    pair = _plan_count_columns([str(c) for c in df.columns])
    if not pair:
        return df
    name = gap_column_name(pair)
    if name in df.columns:
        return df
    out = df.copy()
    out[name] = _to_num(df[pair[0]]) - _to_num(df[pair[1]])
    return out


def gap_column_name(pair: tuple) -> str:
    return f"缺口数({pair[0]}-{pair[1]})"


WEEK_ROLLUP_COL = "周(按日期折算)"
MONTH_SCOPE_COL = "月份(按日期折算)"
_LOW_WORDS = ("最低", "最少", "最小", "最差", "最弱", "最短")
_HIGH_WORDS = ("最高", "最多", "最大", "最好", "最强", "最长")
_ASK_MONTH_RE = re.compile(r"(?<!\d)(\d{1,2})\s*月")
_DAY_VALUE_RE = re.compile(r"\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:$|[T ])")
_OTHER_TIME_RE = re.compile(
    r"年|季度|半年|[一二三四五六七八九十正腊]+\s*月|\d+\s*[日号]|"
    r"第[几一二三四五六七八九十\d]+周|\d{4}[-/.]|W\d{1,2}|"
    r"月[初中末底份]?\s*\d|月[初中末底]|上旬|中旬|下旬|"
    r"上周|下周|本周|上月|下月|本月|当月|前月|近\s*[\d一二三四五六七八九十]+|"
    r"之前|之后|以前|以后|截至|截止|以来|期间|工作日|周末|周[一二三四五六日天]|"
    r"今天|昨天|前天|明天|最近|过去|从\s*\d|\d\s*[-~～—、至到]\s*\d|\d{3,}\s*月|"
    r"\d{1,2}/\d{1,2}|第\d+天|上半月|下半月",
    re.IGNORECASE,
)
_MEAN_WORDS = ("平均", "均值", "日均", "周均")
_SUM_WORDS = ("一共", "总共", "合计", "总计", "共计", "总数", "累计", "总量")
# 按周/按月加起来才有意义的列 (重量、数量、次数…); 温度/湿度/单价这类强度量不在内, 不替用户求和
_ADDITIVE_HINTS = ("kg", "KG", "公斤", "吨", "重量", "产量", "数量", "件数", "个数", "次数", "批次数", "趟次",
                   "用量", "卷", "箱", "品项数")
_NON_ADDITIVE_RE = re.compile(r"单位|单价|每|人均|日均|周均|均值|平均|温度|湿度|浓度|比例|占比|库存|[/／%％]")


def asked_month_number(question: str) -> Optional[int]:
    months = _ASK_MONTH_RE.findall(question or "")
    if len(months) != 1:
        return None
    n = int(months[0])
    return n if 1 <= n <= 12 else None


def _date_column(df: pd.DataFrame, field_meta: Optional[Sequence[dict]] = None):
    """仅在唯一日期字段的每一行都能定位到日期时，折算周/月。"""
    roles = {f.get("original_name"): f for f in (field_meta or [])}
    dates = []
    for c in df.columns:
        if not _has_date_role(df[c], roles.get(c)):
            continue
        s = df[c]
        text = s.astype(str).str.strip().where(s.notna())
        values = text.dropna()
        if values.empty or not values.str.match(_DAY_VALUE_RE).all():
            continue
        d = pd.to_datetime(text, errors="coerce")
        dates.append((c, d))
    if len(dates) == 1:
        col, parsed = dates[0]
        if pd.api.types.is_datetime64_any_dtype(parsed.dtype) and parsed.notna().all():
            return col, parsed
    return None, None


def with_week_column(df: pd.DataFrame, question: str, field_meta: Optional[Sequence[dict]] = None) -> pd.DataFrame:
    """按日表被问「哪一周/每周」或问题点名了月份: 由日期列折算 ISO 周 / 月份加列, 让程序按周汇总、按月筛选。

    表里已经有周列、问题不涉及周、没有可解析的日期列时不加周列 (按日表的提示仍由 execute_plan 给出)。
    问题点名月份且有日期列时加月份列 (只在能把「10月」落到唯一一个年份时才加, 跨年歧义不替用户选)。
    """
    out = df
    col, d = _date_column(df, field_meta)
    if col is None:
        return df
    if (asks_week_on_non_week_table(question, df) and WEEK_ROLLUP_COL not in df.columns
            and d.dt.year.nunique() == 1):
        iso = d.dt.isocalendar()
        label = iso["year"].astype("Int64").astype(str) + "-W" + iso["week"].astype("Int64").astype(str).str.zfill(2)
        out = out.copy()
        out[WEEK_ROLLUP_COL] = label.where(d.notna(), None)
    month = asked_month_number(question)
    if month is not None and MONTH_SCOPE_COL not in out.columns:
        in_month = d.dt.month == month
        if in_month.any() and d.dt.year.nunique() == 1:
            out = out.copy() if out is df else out
            out[MONTH_SCOPE_COL] = d.dt.month.astype("Int64")
    return out


def month_scope_filters(question: str, df: pd.DataFrame) -> Optional[list]:
    """问题点名月份 -> [月份筛选]; 没点名月份 -> []; 点名了但落不到唯一年份/没有日期列 -> None (交给规划器)。"""
    month = asked_month_number(question)
    if _OTHER_TIME_RE.search(question) or (_ASK_MONTH_RE.search(question) and month is None):
        return None
    if month is None:
        return []
    if MONTH_SCOPE_COL not in df.columns:
        return None
    return [{"col": MONTH_SCOPE_COL, "op": "==", "value": month}]


def _week_agg(col: str, q: str) -> Optional[str]:
    """按用户明确的均值口径聚合，求和只适用于列语义可加的指标。"""
    if any(w in q for w in _MEAN_WORDS):
        return None if any(w in q for w in _SUM_WORDS) else "mean"
    if not _NON_ADDITIVE_RE.search(col) and any(h in col for h in _ADDITIVE_HINTS):
        return "sum"
    return None


def week_rollup_plan(question: str, df: pd.DataFrame) -> Optional[dict]:
    """按日表问「哪一周 X 最低/最高」: 按折算周汇总后排序的确定性计划 (不经 LLM, 同一问题每次一致)。

    X 取问题点名的列: 率列按「分子/分母」加权 (计划品项达成率 = 已产出计划品项数 / 原表计划品项数,
    或列头自带公式); 其余列按问题的口径 (平均 -> 均值, 合计/可相加的量 -> 求和)。
    问题点名了月份就只算该月 (九月的周不参与十月排名)。认不准指标/聚合口径/时间范围返回 None (退回规划器)。
    """
    if WEEK_ROLLUP_COL not in df.columns:
        return None
    q = question or ""
    filters = month_scope_filters(q, df)
    if filters is None:
        return None
    cols = [str(c) for c in df.columns]
    base = lambda c: re.sub(r"[A-Za-z℃%％]+$", "", re.split(r"[（(]", c, maxsplit=1)[0].strip())  # noqa: E731
    named = [c for c in cols if c not in (WEEK_ROLLUP_COL, MONTH_SCOPE_COL)
             and len(base(c)) >= 2 and base(c) in q]
    if len(named) != 1:
        return None
    col = named[0]
    if "率" in base(col):
        if any(w in q for w in _MEAN_WORDS + _SUM_WORDS):
            return None
        pair = _plan_count_columns(cols) if "计划品项" in base(col) else None
        if pair and not all("品项" in c for c in pair):
            pair = None
        derived = derive_ratio_from_header(col, cols)
        if pair:
            num, den = [pair[1]], [pair[0]]
        elif derived:
            num, den = derived
        else:
            return None
        metric = {"name": base(col) + "(按周加权)", "func": "ratio", "num": num, "den": den}
        how = f"按周加权 = Σ{'+'.join(num)} / Σ{'+'.join(den)}, 不是逐日比率的平均"
    else:
        agg = _week_agg(col, q)
        if agg is None:
            return None
        metric = {"name": base(col) + ("(按周平均)" if agg == "mean" else "(按周合计)"), "func": agg, "col": col}
        how = f"按周{'求平均' if agg == 'mean' else '求和'}「{col}」"
    low = any(w in q for w in _LOW_WORDS)
    scope = f"只统计 {asked_month_number(q)} 月的日期 (跨月的周只算该月的天); " if filters else ""
    return {
        "intent": "compute", "filters": filters, "group_by": [WEEK_ROLLUP_COL], "metrics": [metric],
        "sort_by": metric["name"], "descending": not low, "limit": 10,
        "note": f"这张表是按日表 (没有「周」列), 已由日期折算 ISO 周 (周一至周日) 后按周汇总: {how}; {scope}"
                "回答须说明是按日表按周汇总的结果; 没有数据的周 (值为空) 不参与排名",
    }


_PLAN_VS_ACTUAL_RE = re.compile(r"计划\s*(与|和|跟|对比|对照|vs|VS)\s*实际")
_PLAN_QTY_WORDS = ("产量", "数量", "kg", "公斤", "重量")
_PLAN_OTHER_METRIC_WORDS = ("产量", "数量", "kg", "KG", "公斤", "重量", "工单", "分钟", "金额", "批次", "人数", "开机",
                            "停机", "成本", "用量")


def rule_based_plan(question: str, df: pd.DataFrame) -> Optional[tuple]:
    """('refuse', 指标名) | ('plan', 原始计划 dict) | None。

    1. 问「计划产量/计划数量」: 表里没有计划数量列 -> 有计划品项数就固定按品项数答 (并说明),
       没有就拒答; 不拿「实产良品」顶替。
    2. 问「没按计划完成/缺口」: 有 计划数+已产出数 成对列 -> 缺口 = 计划−已产出 逐行现算。
    """
    q = question or ""
    scope = month_scope_filters(q, df)
    if scope is None or _WEEK_ASK_RE.search(q):
        return None
    cols = [str(c) for c in df.columns]
    if re.search(r"计划\s*(产量|数量|产出)", q):
        has_qty = any("计划" in c and any(w in c for w in _PLAN_QTY_WORDS) and "外" not in c for c in cols)
        if not has_qty:
            pair = _plan_count_columns(cols)
            if not pair:
                return ("refuse", "计划产量")
            return ("plan", {
                "intent": "compute", "filters": scope, "sort_by": pair[0], "descending": True, "limit": 1,
                "note": f"原表计划没有数量列 (只有「{pair[0]}」), 按计划品项数回答, 不是计划产量; "
                        "不得拿实产良品顶替计划产量",
            })
    if _PLAN_VS_ACTUAL_RE.search(q) and not re.search(r"哪[一天个]|每天|各天|逐日|哪些", q):
        # 「计划与实际差多少」: 整月口径 = 品项数合计 + 按品项加权的达成率 (不是逐日达成率的均值)。
        # 三样都对上才走规则: ① 问的不是别的指标 (产量kg/工单/分钟…) ② 成对列语义是「品项数」 ③ 时间范围能落成筛选。
        pair = _plan_count_columns(cols)
        if (pair and all("品项" in c for c in pair)
                and not any(w in q for w in _PLAN_OTHER_METRIC_WORDS)):
            gap = gap_column_name(pair)
            metrics = [
                {"name": "计划品项数合计", "func": "sum", "col": pair[0]},
                {"name": "已产出品项数合计", "func": "sum", "col": pair[1]},
            ]
            if gap in cols:
                metrics.append({"name": "缺口合计(计划-产出)", "func": "sum", "col": gap})
            metrics.append({"name": "品项达成率(按合计加权)", "func": "ratio", "num": [pair[1]], "den": [pair[0]]})
            outside = [c for c in cols if "计划外" in c and "数" in c]
            if len(outside) == 1:
                metrics.append({"name": "计划外品项数合计", "func": "sum", "col": outside[0]})
            return ("plan", {
                "intent": "compute", "metrics": metrics, "filters": scope,
                "note": f"原表计划没有数量, 按品项数回答: 达成率 = Σ{pair[1]} / Σ{pair[0]} (不是逐日达成率的平均); "
                        "不得给逐日均值或自行推算天数",
            })
    if any(w in q for w in _GAP_ASK_WORDS):
        pair = _plan_count_columns(cols)
        if pair:
            gap = gap_column_name(pair)
            return ("plan", {
                "intent": "compute",
                "filters": [*scope, {"col": gap, "op": ">", "value": 0}],
                "sort_by": gap, "descending": True, "limit": 10,
                "note": f"缺口 = {pair[0]} − {pair[1]} (逐行现算, 不是计划数本身); "
                        "本表每行是一个时间段的品项数, 不含具体产品名",
            })
    return None


def parse_plan(text: str) -> Optional[dict]:
    if not text:
        return None
    t = text.strip()
    t = re.sub(r"^```(?:json)?\s*|\s*```$", "", t, flags=re.S)
    m = re.search(r"\{.*\}", t, flags=re.S)
    if not m:
        return None
    try:
        obj = json.loads(m.group(0))
    except (ValueError, TypeError):
        return None
    return obj if isinstance(obj, dict) else None


class PlanError(Exception):
    pass


def _resolve_col(name: Any, columns: Sequence[str]) -> str:
    n = str(name or "").strip()
    if n in columns:
        return n
    low = [c for c in columns if c.lower() == n.lower()]
    if len(low) == 1:
        return low[0]
    sub = [c for c in columns if n and (n in c or c in n)]
    if len(sub) == 1:
        return sub[0]
    raise PlanError(f"字段不存在: {n}")


def _to_num(s: pd.Series) -> pd.Series:
    if s.dtype == object:
        s = s.astype(str).str.replace(",", "", regex=False).str.strip().str.rstrip("%")
    return pd.to_numeric(s, errors="coerce")


_FORMULA_RE = re.compile(r"[（(]\s*(.+)\s*[）)]\s*$")


def derive_ratio_from_header(col: str, columns: Sequence[str]) -> Optional[tuple]:
    """列头自带公式 (如 出成率(良品/(良品+废品+次品))) 时, 解出 (分子列, 分母列)。

    token 按「字段名以它开头」对应到实际列 (良品 -> 良品kg)。解不出返回 None。
    """
    m = _FORMULA_RE.search(col)
    if not m or "/" not in m.group(1):
        return None
    body = m.group(1)
    num_s, _, den_s = body.partition("/")

    def tokens(expr: str) -> List[str]:
        return [t for t in re.split(r"[+\-()（）\s]+", expr) if t]

    def to_cols(toks: List[str]) -> Optional[List[str]]:
        out: List[str] = []
        for t in toks:
            cands = [c for c in columns if c != col and (c == t or c.startswith(t))]
            if len(cands) != 1:
                return None
            out.append(cands[0])
        return out or None

    nc, dc = to_cols(tokens(num_s)), to_cols(tokens(den_s))
    if nc and dc:
        return nc, dc
    return None


def validate_plan(plan: dict, df: pd.DataFrame) -> dict:
    """校验并规范化计划; 任何字段对不上都抛 PlanError (不猜)。"""
    cols = [str(c) for c in df.columns]
    out: Dict[str, Any] = {"note": str(plan.get("note") or "").strip()}
    out["filters"] = []
    for f in plan.get("filters") or []:
        op = str(f.get("op") or "==")
        if op not in ("==", "!=", ">", ">=", "<", "<=", "contains", "notnull"):
            raise PlanError(f"不支持的筛选运算: {op}")
        out["filters"].append({"col": _resolve_col(f.get("col"), cols), "op": op,
                               "value": f.get("value")})
    out["group_by"] = [_resolve_col(g, cols) for g in (plan.get("group_by") or [])]
    metrics = []
    for m in plan.get("metrics") or []:
        func = str(m.get("func") or "sum")
        if func not in ("sum", "mean", "count", "min", "max", "nunique", "ratio"):
            raise PlanError(f"不支持的聚合: {func}")
        spec: Dict[str, Any] = {"func": func}
        if func == "ratio":
            spec["num"] = [_resolve_col(c, cols) for c in (m.get("num") or [])]
            spec["den"] = [_resolve_col(c, cols) for c in (m.get("den") or [])]
            if not spec["num"] or not spec["den"]:
                raise PlanError("ratio 缺分子/分母")
            spec["name"] = str(m.get("name") or "比率")
        else:
            if func == "count" and not m.get("col"):
                spec["col"] = None
            else:
                spec["col"] = _resolve_col(m.get("col"), cols)
            spec["name"] = str(m.get("name") or f"{func}({spec['col'] or '行数'})")
        # A5: 对率列求平均 = 逐行比率的简单平均, 改为按列头公式加权。
        if func == "mean" and spec.get("col") and "率" in spec["col"]:
            derived = derive_ratio_from_header(spec["col"], cols)
            if derived:
                spec = {"func": "ratio", "num": derived[0], "den": derived[1],
                        "name": spec["name"],
                        "reweighted_from": spec["col"]}
        metrics.append(spec)
    out["metrics"] = metrics
    sb = plan.get("sort_by")
    out["sort_by"] = str(sb) if sb else None
    out["descending"] = bool(plan.get("descending", True))
    try:
        out["limit"] = max(1, min(int(plan.get("limit") or 10), MAX_RESULT_ROWS))
    except (TypeError, ValueError):
        out["limit"] = 10
    bb = plan.get("breakdown_by")
    out["breakdown_by"] = _resolve_col(bb, cols) if bb else None
    if out["sort_by"]:
        known = set(cols) | {m["name"] for m in metrics}
        if out["sort_by"] not in known:
            out["sort_by"] = _resolve_col(out["sort_by"], cols)
    return out


# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------

@dataclass
class ExecResult:
    columns: List[str]
    rows: List[List[Any]]
    total_rows: int
    matched_rows: int
    filters_desc: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    breakdown: Optional[str] = None
    group_by: List[str] = field(default_factory=list)
    mode: str = "rows"
    pct_cols: set = field(default_factory=set)
    code_cols: set = field(default_factory=set)
    totals: Dict[str, Any] = field(default_factory=dict)
    total_columns: Dict[str, str] = field(default_factory=dict)
    all_group_labels: Optional[list] = None
    asks_total: bool = False
    hash_cols: set = field(default_factory=set)      # 整列带「#」转义前缀的编码列: 展示时去掉一个 #


def strip_code_hash(s: str) -> str:
    """去掉一个「#」转义前缀 (#21014C -> 21014C)。只对整列都带「#」的编码列调用 (见 hash_escaped_column)。"""
    value = s.strip()
    return value[1:] if len(value) > 1 and value.startswith("#") else s


def hash_escaped_column(s: pd.Series, role: Optional[dict] = None) -> bool:
    """兼容旧导入约定：明确命名的编码列，且所有非空值统一加了一个 #。"""
    if not is_code_column(str(s.name)) or not pd.api.types.is_string_dtype(s.dtype):
        return False
    if role and (role.get("is_measure") or role.get("is_time")):
        return False
    v = s.dropna().astype(str).str.strip()
    v = v[v != ""]
    return len(v) > 0 and bool(v.str.fullmatch(r"#[^#\s].*").all())


_MONTH_PREFIX_RE = re.compile(r"\d{4}-\d{2}(-\d{2})?")
_ISO_DATE_VALUE_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}(?:[T ](?:[01]\d|2[0-3]):[0-5]\d"
    r"(?::[0-5]\d(?:\.\d+)?)?(?:Z|[+-](?:[01]\d|2[0-3]):?[0-5]\d)?)?"
)
_DATE_NAME_RE = re.compile(r"日期|(?<![A-Za-z])date(?![A-Za-z])", re.IGNORECASE)


def _has_date_role(s: pd.Series, role: Optional[dict]) -> bool:
    name = str(s.name)
    if is_code_column(name) or re.search(r"(?<![A-Za-z])sku(?![A-Za-z])", name, re.IGNORECASE):
        return False
    if role and role.get("is_time"):
        return True
    if role and (role.get("is_measure") or role.get("is_dimension")):
        return False
    return bool(_DATE_NAME_RE.search(name)) or pd.api.types.is_datetime64_any_dtype(s.dtype)


def _valid_date_prefix(s: pd.Series, value: str) -> bool:
    """仅对有效 ISO 日期值允许按月或按日匹配。"""
    values = s.dropna().astype(str).str.strip()
    if values.empty or not values.str.fullmatch(_ISO_DATE_VALUE_RE).all():
        return False
    dates = pd.to_datetime(values.str[:10], format="%Y-%m-%d", errors="coerce")
    return dates.notna().all() and pd.notna(pd.to_datetime(value, errors="coerce"))


class ValueNotFound(PlanError):
    """等值筛选的取值在该列里根本没出现 (多半是规划器把「10月」写成 日期=='2026-10' 这类)。"""


def _apply_filter(
    df: pd.DataFrame, f: dict, strict_values: bool = True, *,
    source: Optional[pd.Series] = None, role: Optional[dict] = None, hash_escaped: bool = False,
) -> pd.Series:
    col, op, val = f["col"], f["op"], f["value"]
    s = df[col]
    if op in ("==", "!=") and isinstance(val, str) and (
        pd.api.types.is_string_dtype(s.dtype) or pd.api.types.is_datetime64_any_dtype(s.dtype)
    ):
        sv0, v0 = s.astype(str).str.strip(), val.strip()
        if (_MONTH_PREFIX_RE.fullmatch(v0) and _has_date_role(s, role)
                and _valid_date_prefix(source if source is not None else s, v0)):
            matches = s.notna() & (sv0.str[:len(v0)] == v0)
            if matches.any() or op == "!=" or not strict_values:
                return matches if op == "==" else ~matches
        if v0 and not (sv0 == v0).any():
            if hash_escaped and not v0.startswith("#"):
                m = sv0 == "#" + v0
                if m.any():
                    return m if op == "==" else ~m
            if strict_values and op == "==" and s.notna().any():
                uniq = list(dict.fromkeys(sv0[s.notna()].tolist()))
                shown = " / ".join(u[:20] for u in uniq[:12])
                raise ValueNotFound(
                    f"字段「{col}」没有取值「{v0}」(现有取值: {shown}{' …' if len(uniq) > 12 else ''})")
    if op == "notnull":
        return s.notna() & (s.astype(str).str.strip() != "")
    if op == "contains":
        return s.astype(str).str.contains(str(val), regex=False, na=False)
    if isinstance(val, (int, float)) and not isinstance(val, bool):
        n = _to_num(s)
        return {"==": n == val, "!=": n != val, ">": n > val, ">=": n >= val,
                "<": n < val, "<=": n <= val}[op]
    sv = s.astype(str).str.strip()
    v = str(val).strip()
    if op == "==":
        return sv == v
    if op == "!=":
        return sv != v
    # 字符串比较 (ISO 日期可按字典序)
    return {">": sv > v, ">=": sv >= v, "<": sv < v, "<=": sv <= v}[op].where(s.notna(), False)


def _metric_value(sub: pd.DataFrame, m: dict):
    func = m["func"]
    if func == "ratio":
        num = sum(_to_num(sub[c]).sum() for c in m["num"])
        den = sum(_to_num(sub[c]).sum() for c in m["den"])
        return None if not den else num / den
    if func == "count":
        return int(len(sub)) if m["col"] is None else int(sub[m["col"]].notna().sum())
    if func == "nunique":
        return int(sub[m["col"]].nunique(dropna=True))
    n = _to_num(sub[m["col"]])
    if n.notna().sum() == 0:
        return None
    return float({"sum": n.sum, "mean": n.mean, "min": n.min, "max": n.max}[func]())


def _fmt(v: Any, pct: bool = False, code: bool = False, strip_hash: bool = False) -> str:
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return "(空)"
    if code:  # 编码/ID 类列: 原样, 不加千分位, 不带 .0
        if isinstance(v, float) and v == int(v):
            return str(int(v))
        return strip_code_hash(str(v)) if strip_hash else str(v)
    if pct:
        return f"{v * 100:.2f}%"
    if isinstance(v, bool):
        return str(v)
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return f"{v:,.2f}".rstrip("0").rstrip(".") if abs(v) < 1e15 else str(v)
    s = str(v)
    return s if len(s) <= 40 else s[:40] + "…"


def execute_plan(df: pd.DataFrame, plan: dict, question: str = "",
                 field_meta: Optional[Sequence[dict]] = None, strict_values: bool = True) -> ExecResult:
    roles = {f.get("original_name"): f for f in (field_meta or [])}
    hash_cols = {c for c in df.columns if hash_escaped_column(df[c], roles.get(c))}
    res = _execute_plan(df, plan, question, strict_values, roles, hash_cols)
    res.asks_total = asks_total(question)
    res.code_cols = set(identifier_columns(res.columns, field_meta, df))
    res.hash_cols = res.code_cols & hash_cols
    if asks_week_on_non_week_table(question, df):
        res.notes.append(
            "当前数据源是按日表 (没有「周」列), 未按周汇总; 以下是按日结果, "
            "回答须明说「这张是按日表」, 不得把某一天说成某一周")
    return res


def _execute_plan(df: pd.DataFrame, plan: dict, question: str, strict_values: bool,
                  roles: dict, hash_cols: set) -> ExecResult:
    total = len(df)
    notes: List[str] = []
    if plan["note"]:
        notes.append(plan["note"])
    for m in plan["metrics"]:
        if m.get("reweighted_from"):
            notes.append(
                f"「{m['reweighted_from']}」不能逐行取平均, 已按其列头公式用全部行加权计算 "
                f"(Σ{'+'.join(m['num'])} / Σ{'+'.join(m['den'])})"
            )
    work = df
    fdesc: List[str] = []
    for f in plan["filters"]:
        col = f["col"]
        work = work[_apply_filter(
            work, f, strict_values, source=df[col], role=roles.get(col), hash_escaped=col in hash_cols,
        )]
        fdesc.append(f"{f['col']} {f['op']} {f['value']!r}" if f["op"] != "notnull"
                     else f"{f['col']} 非空")
    matched = len(work)
    gb, metrics = plan["group_by"], plan["metrics"]
    pct_cols: set = set()

    if gb and metrics:
        recs = []
        for key, sub in work.groupby(gb, dropna=True, sort=False):
            key = key if isinstance(key, tuple) else (key,)
            recs.append(list(key) + [_metric_value(sub, m) for m in metrics])
        cols = gb + [m["name"] for m in metrics]
        out = pd.DataFrame(recs, columns=cols)
        pct_cols = {m["name"] for m in metrics if m["func"] == "ratio"}
        sb = plan["sort_by"] if plan["sort_by"] in cols else (metrics[0]["name"])
        out = out.sort_values(sb, ascending=not plan["descending"], na_position="last")
        shown = out.head(plan["limit"])
        # astype(object): 混合 int/float 列 .values 会整体升成 float (编码 22002 -> 22002.0 -> 22,002)
        res = ExecResult(cols, shown.astype(object).values.tolist(), total, matched, fdesc, notes,
                         group_by=gb, mode="grouped")
        if len(gb) == 1 and asks_total(question):
            res.all_group_labels = out[gb[0]].tolist()
        res.notes.append(f"共 {len(out)} 个分组, 展示前 {len(shown)} 个")
        res.pct_cols = pct_cols
        for m in metrics:  # 一共/总共/合计: 全部分组的求和, 不是排名第一的那一项
            if m["func"] in ("sum", "count"):
                t = out[m["name"]].sum(skipna=True)
                res.totals[m["name"]] = int(t) if float(t) == int(t) else float(t)
                res.total_columns[m["name"]] = m["col"] or ""
        res.notes.append(f"全部 {len(out)} 个分组共 {matched} 行")
        if plan["breakdown_by"] and len(out):
            top_key = out.iloc[0][gb].tolist()
            mask = pd.Series(True, index=work.index)
            for g, k in zip(gb, top_key):
                mask &= work[g] == k
            top = work[mask]
            bcol = plan["breakdown_by"]
            first = metrics[0]
            lines = []
            for bval, sub in top.groupby(bcol, dropna=True, sort=False):
                lines.append((bval, _metric_value(sub, first), len(sub)))
            lines.sort(key=lambda t: (t[1] is None, -(t[1] or 0)))
            res.breakdown = (
                f"排名第一 ({', '.join(str(k) for k in top_key)}) 按「{bcol}」拆解 "
                f"({first['name']}): "
                + "; ".join(f"{b}={_fmt(v)} (行数 {c})" for b, v, c in lines[:8])
            )
        return res

    if metrics:  # 无分组: 对筛选后全表求一行汇总
        vals = [_metric_value(work, m) for m in metrics]
        res = ExecResult([m["name"] for m in metrics], [vals], total, matched, fdesc, notes,
                         mode="aggregate")
        res.pct_cols = {m["name"] for m in metrics if m["func"] == "ratio"}
        return res

    # 行模式: 返回整行 (argmax / argmin / 列出满足条件的行)
    sb = plan["sort_by"]
    show_cols = [c for c in work.columns if not _is_long_text(work[c])]
    if sb:
        key = _to_num(work[sb]) if sb in work.columns else None
        if key is None:
            raise PlanError(f"排序字段不存在: {sb}")
        if key.notna().sum() == 0:  # 文本列: 按文本排
            key = work[sb].astype(str)
        nan_n = int(key.isna().sum())
        valid = work[key.notna()]
        key = key[key.notna()]
        order = key.sort_values(ascending=not plan["descending"], kind="stable").index
        ordered = valid.loc[order]
        head = ordered.head(plan["limit"])
        if len(head) and len(ordered) > len(head):
            thr = key.loc[head.index[-1]]
            ties = ordered[key.loc[ordered.index] == thr]
            extra = ties[~ties.index.isin(head.index)].head(MAX_TIE_ROWS)
            if len(extra):
                head = pd.concat([head, extra])
                notes.append(f"并列: 另有 {len(extra)} 行与末位同值 ({_fmt(thr)}), 已一并列出, "
                             "回答须把并列的行全部说出来, 不得只说其中一个")
        if nan_n:
            notes.append(f"排序字段「{sb}」有 {nan_n} 行为空, 已排除出比较 (不是 0)")
        shown = head[show_cols]
        res = ExecResult(show_cols, shown.values.tolist(), total, matched, fdesc, notes, mode="rows",
                         pct_cols=_rate_cols(work, show_cols))
        res.notes.append(f"按「{sb}」{'降序' if plan['descending'] else '升序'}取前 {plan['limit']} 行")
        if asks_total(question) and _to_num(work[sb]).notna().any():
            tot = float(_to_num(work[sb]).sum())
            res.totals[f"{sb}合计"] = int(tot) if tot == int(tot) else tot
            res.total_columns[f"{sb}合计"] = sb
        return res
    shown = work[show_cols].head(MAX_RESULT_ROWS)
    res = ExecResult(show_cols, shown.values.tolist(), total, matched, fdesc, notes, mode="rows",
                     pct_cols=_rate_cols(work, show_cols))
    if matched > len(shown):
        res.notes.append(f"命中 {matched} 行, 仅展示前 {len(shown)} 行")
    return res


def _rate_cols(work: pd.DataFrame, cols: Sequence[str]) -> set:
    """列头含「率」且是 0~1.5 的小数 -> 展示时同时给百分数, 避免 0.6875 被读成 0.69。"""
    out = set()
    for c in cols:
        if "率" in c:
            n = _to_num(work[c])
            if n.notna().any() and n.dropna().abs().max() <= 1.5:
                out.add(c)
    return out


def _is_long_text(s: pd.Series) -> bool:
    if s.dtype != object:
        return False
    v = s.dropna().astype(str)
    return len(v) > 0 and v.str.len().mean() > 60


def render_block(question: str, res: ExecResult) -> str:
    pct = res.pct_cols
    head = "| " + " | ".join(res.columns) + " |"
    sep = "|" + "---|" * len(res.columns)
    body = []
    for r in res.rows:
        body.append("| " + " | ".join(
            ("编码缺失" if (c in res.code_cols and (v is None or (isinstance(v, float) and pd.isna(v))))
             else _fmt(v, pct=(c in pct), code=(c in res.code_cols), strip_hash=(c in res.hash_cols)))
            for c, v in zip(res.columns, r)) + " |")
    lines = [
        f"## 全表精确计算结果 (权威: 服务端在该数据集全部 {res.total_rows} 行上计算)",
        "回答规则: 数字、日期、产品、机台等只能取自下表同一行, 不得改写、拼接别的行的字段、"
        "不得补充表里没有的日期/名称; 表里没有的信息直接说「数据里没有」。"
        "编码/编号/批号类的值原样书写, 不加千分位逗号。",
    ]
    if res.totals:
        tt = "; ".join(f"{k} = {_fmt(v)}" for k, v in res.totals.items())
        lines.append(f"全部分组合计 (所有行求和): {tt}")
        if res.asks_total:
            top = (f"; 排名第一的分组「{'/'.join(str(v) for v in res.rows[0][:len(res.group_by)])}」"
                   "只是最大项, 不是总数"
                   if res.mode == "grouped" and res.rows else "")
            lines.append(f"问题问的是「一共/总共/合计」: 总数以上面的合计为准{top}。")
    if res.filters_desc:
        lines.append("筛选条件: " + "; ".join(res.filters_desc))
    lines.append(f"命中 {res.matched_rows} / {res.total_rows} 行")
    lines += [head, sep] + body
    if not res.rows:
        lines.append("(无满足条件的行: 如实回答「没有」, 不要编造)")
    for n in res.notes:
        lines.append(f"口径: {n}")
    if res.breakdown:
        lines.append(res.breakdown)
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# 回答校验: 「一共/总共」的总数必须是代码算的合计
# ---------------------------------------------------------------------------

_CLAUSE_SPLIT_RE = re.compile(r"(?<=[，,；;。！？!?\n])")
_GLOBAL_SCOPE_WORDS = ("全月", "整月", "全表", "全部", "所有", "整体", "全厂", "月度")
_THOUSANDS_COMMA_RE = re.compile(r"(?<=\d),(?=\d)")
_PROTECT = "\x01"
_TOTAL_SIGNAL_RE = re.compile("|".join(map(re.escape, _GLOBAL_SCOPE_WORDS + TOTAL_WORDS)))
_TOTAL_FILLER = r"\s*(?:(?:一共|总共|合计|总计|共计|共|使用了|用了|用掉了|为|是)\s*)*"
_CLAIM_NUMBER = r"(?<![\d.,])(?P<number>[+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)(?![\d]|[.,]\d)"
_UNIT_RE = re.compile(r"[（(]([^()（）]+)[）)]$|((?i:kg)|公斤|吨|元|卷|箱|分钟|台|批|人|次|件|个)$")


def _group_labels(res: ExecResult) -> List[str]:
    """全表分组或预览行中的文本标签，用来识别局部小计。"""
    values = res.all_group_labels
    if values is None:
        values = [v for row in res.rows for v in (row[:len(res.group_by)] if res.group_by else row)]
    labels = {v.strip() for v in values if isinstance(v, str) and len(v.strip()) >= 2}
    return sorted(labels, key=len, reverse=True)


def _whole_scope_start(part: str, prev: str, labels: Sequence[str], group_count: int) -> Optional[int]:
    global_scope = any(w in part for w in _GLOBAL_SCOPE_WORDS)
    mentioned = [label for label in labels if label in part]
    if mentioned:
        if not global_scope or len(mentioned) != group_count or group_count < 2:
            return None
        last_label = max(part.index(label) + len(label) for label in mentioned)
        if any(w in part[last_label:] for w in TOTAL_WORDS):
            return last_label
        return None
    if global_scope or (any(w in part for w in TOTAL_WORDS) and not any(label in prev for label in labels)):
        return 0
    return None


def reconcile_answer_with_totals(answer: str, res: Optional[ExecResult]) -> tuple:
    """逐句核对可绑定到全表范围及对应指标的数值，缺失的合计单独补上。"""
    if res is None or not res.asks_total or not res.totals or not answer:
        return answer, False
    protected = _THOUSANDS_COMMA_RE.sub(_PROTECT, answer)
    parts = [part.replace(_PROTECT, ",") for part in _CLAUSE_SPLIT_RE.split(protected)]
    labels = _group_labels(res)
    group_count = len(res.all_group_labels or [])
    units = {}
    for name, col in res.total_columns.items():
        match = _UNIT_RE.search(col)
        if match:
            units[name] = (match[1] or match[2]).lower()
    seen = set()
    updated = []
    for i, part in enumerate(parts):
        start = _whole_scope_start(part, parts[i - 1] if i else "", labels, group_count)
        if start is None:
            updated.append(part)
            continue
        claim = part[start:]
        signal = _TOTAL_SIGNAL_RE.search(claim)
        edits = {}
        for name, value in res.totals.items():
            # 指标名与数字直接相连，或数字带有可唯一识别该指标的单位。
            spans = {m.span("number") for m in re.finditer(
                re.escape(name) + r"\s*(?:[=:：为]|是)?\s*" + _CLAIM_NUMBER, claim,
            )}
            unit = units.get(name)
            if unit and list(units.values()).count(unit) == 1 and signal:
                offset = signal.end()
                match = re.match(
                    _TOTAL_FILLER + _CLAIM_NUMBER + r"\s*" + re.escape(unit) + r"(?![A-Za-z])",
                    claim[offset:], re.IGNORECASE,
                )
                if match:
                    spans.add((offset + match.start("number"), offset + match.end("number")))
            for left, right in spans:
                seen.add(name)
                expected = _fmt(value)
                if float(claim[left:right].replace(",", "")) != float(expected.replace(",", "")):
                    edits[left, right] = expected
        for (left, right), value in sorted(edits.items(), reverse=True):
            claim = claim[:left] + value + claim[right:]
        updated.append(part[:start] + claim)
    out = "".join(updated)
    missing = [f"全部合计 {name} = {_fmt(v)}" for name, v in res.totals.items() if name not in seen]
    if missing:
        out = out.rstrip() + "\n\n**更正（程序核对）**：" + "；".join(missing) + "。"
    return out, out != answer


def strip_code_separators(answer: str, res: Optional[ExecResult]) -> tuple:
    """回答里的编码/批号值被模型写成带千分位 (22,002) 时, 改回原样 (22002)。

    只改结果表里标识列出现过的值, 不碰同样位数的普通数量。返回 (新回答, 是否改了)。
    """
    if res is None or not answer or not res.code_cols:
        return answer, False
    values = set()
    for r in res.rows:
        for c, v in zip(res.columns, r):
            if c in res.code_cols and v is not None and not (isinstance(v, float) and pd.isna(v)):
                t = _fmt(v, code=True, strip_hash=c in res.hash_cols)
                if t.isdigit() and len(t) >= 4:
                    values.add(t)
    out = answer
    for t in sorted(values, key=len, reverse=True):
        grouped = f"{int(t):,}"
        out = re.sub(r"(?<![\d.,])" + re.escape(grouped) + r"(?![\d]|,\d|\.\d)(?!\s*(?:kg|KG|公斤|吨|元|%|个|卷|箱|分钟|台|批|人|天|次|趟))", t, out)
    return out, out != answer


# ---------------------------------------------------------------------------
# 总入口
# ---------------------------------------------------------------------------

@dataclass
class RowLevelOutcome:
    kind: str                      # refuse | computed | uncomputed | none
    text: str = ""                 # refuse: 完整拒答; computed/uncomputed: 注入提示词的段落
    plan: Optional[dict] = None
    result: Optional[ExecResult] = None


UNCOMPUTED_NOTE = (
    "## 行级计算未完成\n"
    "本次未能对全表执行逐行计算 ({reason})。回答规则: 只可引用上面的全量聚合数字; "
    "不得指出具体是哪一天/哪一行/哪个产品/哪台机器, 不得对比率求平均; "
    "需要行级结论时明说「本次未能逐行计算, 请换种问法或稍后重试」。\n"
)


async def answer_row_level(
    question: str,
    df: pd.DataFrame,
    field_names: Sequence[str],
    llm_call: LlmCall,
    field_meta: Optional[Sequence[dict]] = None,
    *,
    upload_key: Optional[tuple[str, int]] = None,
) -> RowLevelOutcome:
    """对整份数据做行级计算; 返回拒答 / 已计算段落 / 未能计算 / 无需计算。

    llm_call(prompt, system_role) -> 文本 (JSON)。只用来出查询计划。
    """
    if not (question or "").strip() or df is None or df.empty:
        return RowLevelOutcome("none")

    df = with_gap_columns(df)
    df = with_week_column(df, question, field_meta)

    # 确定性规则: 不经 LLM, 同一问题每次一致 (按日表问「哪一周」先按周汇总)
    rule = rule_based_plan(question, df)
    wk = week_rollup_plan(question, df)
    if wk and not (rule and rule[0] == "refuse"):
        rule = ("plan", wk)
    if rule and rule[0] == "refuse":
        return RowLevelOutcome("refuse", build_refusal(rule[1], field_names))

    missing = find_missing_concept(question, field_names)
    if missing:
        return RowLevelOutcome("refuse", build_refusal(missing, field_names))

    if rule and rule[0] == "plan":
        try:
            vplan = validate_plan(rule[1], df)
            res = execute_plan(df, vplan, question, field_meta)
        except PlanError as e:
            return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason=str(e)), plan=rule[1])
        return RowLevelOutcome("computed", render_block(question, res), plan=vplan, result=res)

    cache_key = None
    if upload_key is not None:
        schema = tuple((str(c), str(dtype)) for c, dtype in df.dtypes.items())
        roles = tuple((f.get("original_name"), f.get("standard_name"), bool(f.get("is_time")),
                       bool(f.get("is_dimension")), bool(f.get("is_measure"))) for f in (field_meta or []))
        cache_key = (upload_key, question.strip(), schema, roles)
    prompt = build_plan_prompt(question, df)
    raw = _PLAN_CACHE.get(cache_key) if cache_key is not None else None
    from_cache = raw is not None

    async def _ask_planner(extra: str = "") -> Optional[str]:
        try:
            return await llm_call(prompt + extra, PLAN_SYSTEM)
        except Exception as e:  # LLM 不可用: 如实标记未计算, 不退回样本猜
            logger.warning("[row-level-qa] planner call failed: %s", e)
            return None

    if raw is None:
        raw = await _ask_planner()
        if raw is None:
            return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason="查询规划不可用"))

    attempt = 1
    while True:
        plan = parse_plan(raw)
        if plan is None:
            return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason="查询规划无法解析"))
        intent = str(plan.get("intent") or "compute")
        if intent == "none":
            return RowLevelOutcome("none", plan=plan)
        if intent == "no_metric":
            return RowLevelOutcome("refuse", build_refusal(
                str(plan.get("missing") or "被问的"), field_names), plan=plan)
        try:
            vplan = validate_plan(plan, df)
            # 严格模式: 筛选值在列里不存在就报错, 交回规划器重写; 重写后 (第二次) 仍然没有 -> 如实按 0 行回答。
            # 缓存里取出的计划一律走严格模式。
            res = execute_plan(df, vplan, question, field_meta, strict_values=(attempt == 1))
        except PlanError as e:
            if from_cache:
                # 同上传的数据也可能变更：严格检查失败时，丢掉缓存并重新规划。
                logger.info("[row-level-qa] cached plan invalid for this upload (%s), re-planning", e)
                _PLAN_CACHE.pop(cache_key, None)
                from_cache = False
                raw = await _ask_planner()
                if raw is None:
                    return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason="查询规划不可用"))
                attempt = 1
                continue
            if attempt == 1:
                # 字段/取值对不上 (如在按日表上 group_by 周): 把错误和真实字段交回规划器重写一次,
                # 不让「行定位时好时坏」停在 uncomputed。
                logger.info("[row-level-qa] plan invalid (%s), retrying once", e)
                raw = await _ask_planner(f"\n\n上一次的计划无效: {e}。只能使用上面列出的字段, 请重新输出 JSON。")
                if raw is None:
                    return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason=str(e)), plan=plan)
                attempt = 2
                continue
            return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason=str(e)), plan=plan)
        if attempt == 1 and cache_key is not None:
            # 只缓存「严格校验也通过」的计划: 取值确实存在于这份数据。第二次 (非严格) 才跑通的不缓存,
            # 否则同一个零结果问题第二次会因命中缓存而与第一次不同。
            _plan_cache_put(cache_key, raw)
        return RowLevelOutcome("computed", render_block(question, res), plan=vplan, result=res)


# 只在相同租户、上传、表结构与原问题下复用已严格执行的计划。
_PLAN_CACHE: Dict[tuple, str] = {}
_PLAN_CACHE_MAX = 256


def _plan_cache_put(key: tuple, raw: str) -> None:
    if len(_PLAN_CACHE) >= _PLAN_CACHE_MAX:
        _PLAN_CACHE.pop(next(iter(_PLAN_CACHE)))
    _PLAN_CACHE[key] = raw
