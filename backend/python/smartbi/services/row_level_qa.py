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
    if WEEK_ROLLUP_COL in df.columns and _WEEK_ASK_RE.search(question or ""):
        hint = (f"\n周汇总或排名使用「{WEEK_ROLLUP_COL}」分组。它是 ISO 周标签，"
                "如有日期范围，先用原日期字段筛选，再按周聚合。\n")
    elif asks_week_on_non_week_table(question, df):
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
_MONTH_PREFIX = r"(?:(?P<month>1[0-2]|[1-9])\s*月\s*[,，]?\s*)?"
_DAY_VALUE_RE = re.compile(r"^(\d{4}[-/]\d{1,2}[-/]\d{1,2})(?=$|[T ])")
_RANK_WORD = "|".join(_LOW_WORDS + _HIGH_WORDS)
_TEXT_LIST_QUESTION = re.compile(
    _MONTH_PREFIX + r"(?P<column>[^「」\n，,？?]*?(?:说明|备注|附注))"
    r"\s*(?:有什么特殊情况|有哪些|有什么内容|有什么)[？?]?"
)
_TEXT_MARKDOWN_CHARS = re.compile(r"[\\\x60*_\[\]<>|&~]")

# 原始列头，来源为 test_weimei_retest_r4b.py 的 weekly_additive_flow 阳性。
_WEEKLY_SUM_COLUMNS = frozenset({"良品产量kg"})

# 原始比率列头 -> (分子原始列头, 分母原始列头)。带公式的键也逐字核对公式。
_WEEKLY_RATIO_COLUMNS = {
    # 味美上线修复，test_weimei_retest_r4.py 的 test_4_week_question_on_daily_table_is_rolled_up_by_the_program。
    "计划品项达成率": ("已产出计划品项数", "原表计划品项数"),
    # test_weimei_retest_r4b.py 的 r3_weekly_ratio 阳性。
    "计划品项达成率(合格品项数/抽检品项数)": ("合格品项数", "抽检品项数"),
    # test_weimei_retest_r4b.py 的 weekly_header_ratio 阳性。
    "良品率(良品件数/生产件数)": ("良品件数", "生产件数"),
    # REVIEW-1981R4 的完好件数对照，test_weimei_retest_r4b.py 的 weekly_ratio_registered_box_totals。
    "完好率(完好件数/抽检件数)": ("完好件数", "抽检件数"),
}


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
        # Validate timestamp suffixes while allowing legacy slash dates.
        if not values.str.replace(_DAY_VALUE_RE, "2000-01-01", regex=True).str.fullmatch(_ISO_DATE_VALUE_RE).all():
            continue
        days = text.str.extract(_DAY_VALUE_RE, expand=False).str.replace("/", "-", regex=False)
        d = pd.to_datetime(days, format="%Y-%m-%d", errors="coerce")
        dates.append((c, d))
    if len(dates) == 1:
        col, parsed = dates[0]
        if pd.api.types.is_datetime64_any_dtype(parsed.dtype) and parsed.notna().all():
            return col, parsed
    return None, None


def _text_listing_answer(
    question: str, df: pd.DataFrame, field_meta: Optional[Sequence[dict]],
) -> Optional[str]:
    """逐行回答明确点名的说明/备注列。列名、类型或月份不明确时由规划器判断。"""
    match = _TEXT_LIST_QUESTION.fullmatch(question.strip())
    if match is None:
        return None
    column = match["column"]
    if column not in df.columns:
        return None
    if any(f.get("original_name") == column and (f.get("is_measure") or f.get("is_time"))
           for f in field_meta or []):
        return None
    notes = df[column].reset_index(drop=True).dropna()
    if not notes.map(lambda value: isinstance(value, str)).all():
        return None
    notes = notes.map(str.strip)
    notes = notes[notes.ne("")]

    _, dates = _date_column(df, field_meta)
    if dates is not None:
        dates = dates.reset_index(drop=True)
    month = match["month"]
    if month:
        if dates is None or dates.dt.year.nunique() != 1:
            return None
        notes = notes[dates.loc[notes.index].dt.month.eq(int(month))]

    scope = f"{month}月" if month else "当前数据"
    label = _TEXT_MARKDOWN_CHARS.sub(r"\\\g<0>", column)
    lines = [f"{scope}「{label}」共有 {len(notes)} 条非空记录。"]
    if not notes.empty:
        lines.append("")
    for index, value in notes.head(MAX_RESULT_ROWS).items():
        label = dates.iloc[index].date().isoformat() if dates is not None else f"第 {index + 1} 行"
        text = _TEXT_MARKDOWN_CHARS.sub(r"\\\g<0>", " ".join(value.splitlines()))
        lines.append(f"- {label}：{text}")
    if len(notes) > MAX_RESULT_ROWS:
        lines.extend(["", f"以上列出前 {MAX_RESULT_ROWS} 条。"])
    return "\n".join(lines)


def _with_calendar_columns(
    df: pd.DataFrame, question: str, field_meta: Optional[Sequence[dict]],
) -> tuple[pd.DataFrame, bool]:
    """Expose ISO weeks to the planner even when a natural month is ambiguous."""
    _, dates = _date_column(df, field_meta)
    if dates is None:
        return df, False
    cross_year = dates.dt.year.nunique() > 1
    month = re.match(_MONTH_PREFIX, question.strip()).group("month")
    add_week = asks_week_on_non_week_table(question, df)
    add_month = (month and not cross_year and MONTH_SCOPE_COL not in df.columns
                 and dates.dt.month.eq(int(month)).any())
    if not (add_week or add_month):
        return df, cross_year
    out = df.copy()
    if add_week:
        iso = dates.dt.isocalendar()
        out[WEEK_ROLLUP_COL] = iso["year"].astype(str) + "-W" + iso["week"].astype(str).str.zfill(2)
    if add_month:
        out[MONTH_SCOPE_COL] = dates.dt.month.astype("Int64")
    return out, cross_year


def _rule_month_filters(match: re.Match, df: pd.DataFrame) -> Optional[list]:
    month = match.group("month")
    if month is None:
        return []
    if MONTH_SCOPE_COL not in df.columns:
        return None
    return [{"col": MONTH_SCOPE_COL, "op": "==", "value": int(month)}]


def _metric_base(column: str) -> str:
    label = re.split(r"[（(]", column, maxsplit=1)[0].strip()
    return re.sub(r"(?:kg|℃|[%％])$", "", label, flags=re.IGNORECASE).strip()


def _valid_weekly_values(column: str, df: pd.DataFrame, roles: dict, *, counts_only: bool = False) -> bool:
    if column not in df.columns:
        return False
    role = roles.get(column, {})
    if role.get("is_time") or (role.get("is_dimension") and not role.get("is_measure")):
        return False
    values = _to_num(df[column].dropna())
    if values.empty or not values.notna().all():
        return False
    return not counts_only or (len(values) == len(df) and (values.ge(0) & values.mod(1).eq(0)).all())


def week_rollup_plan(question: str, df: pd.DataFrame, field_meta: Optional[Sequence[dict]]) -> Optional[dict]:
    """Match a complete single-metric ranking or total question."""
    if WEEK_ROLLUP_COL not in df.columns:
        return None
    match = re.fullmatch(
        _MONTH_PREFIX + rf"(?:哪一?周\s*(?P<rank_metric>.+?)\s*(?P<rank>{_RANK_WORD})"
        rf"|(?:每周|各周)\s*(?P<total_metric>.+?)\s*(?:合计|总计|总量))"
        r"\s*(?:[?？]\s*是多少)?[?？。]?", question.strip(),
    )
    if not match:
        return None
    filters = _rule_month_filters(match, df)
    if filters is None:
        return None
    requested = (match["rank_metric"] or match["total_metric"]).strip()
    columns = [str(c) for c in df.columns]
    named = [c for c in columns if requested in (_metric_base(c), c)]
    if len(named) != 1:
        return None
    column = named[0]
    roles = {f.get("original_name"): f for f in (field_meta or [])}
    label = _metric_base(column)
    pair = _WEEKLY_RATIO_COLUMNS.get(column)
    if pair and match["rank"]:
        if not all(_valid_weekly_values(c, df, roles, counts_only=True) for c in pair):
            return None
        num, den = pair
        metric = {"name": label + "(按周加权)", "func": "ratio", "num": [num], "den": [den]}
        how = f"按周加权 = Σ{num} / Σ{den}, 不是逐日比率的平均"
    else:
        if column not in _WEEKLY_SUM_COLUMNS or not _valid_weekly_values(column, df, roles):
            return None
        metric = {"name": label + "(按周合计)", "func": "sum", "col": column}
        how = f"按周求和「{column}」"
    scope = f"只统计 {match['month']} 月的日期 (跨月的周只算该月的天); " if filters else ""
    return {
        "intent": "compute", "filters": filters, "group_by": [WEEK_ROLLUP_COL], "metrics": [metric],
        "sort_by": metric["name"] if match["rank"] else WEEK_ROLLUP_COL,
        "descending": match["rank"] in _HIGH_WORDS, "limit": 10 if match["rank"] else MAX_RESULT_ROWS,
        "note": f"这张表是按日表 (没有「周」列), 已由日期折算 ISO 周 (周一至周日) 后按周汇总: {how}; {scope}"
                "回答须说明是按日表按周汇总的结果; 没有数据的周 (值为空) 不参与排名",
    }


_PLAN_QUESTION_RE = re.compile(
    _MONTH_PREFIX + r"(?:"
    r"(?P<quantity>哪(?:一)?天\s*计划\s*(?:产量|数量|产出)\s*(?:最高|最多|最大))"
    r"|(?P<comparison>计划\s*(?:与|和|跟|对比|对照|vs)\s*实际\s*差(?:了)?多少)"
    r"|(?P<gap>哪些产品\s*(?:没按计划完成|未按计划完成)|计划缺口(?:是多少|有多少)?)"
    r")\s*[?？。]?", re.IGNORECASE,
)
_PLAN_QTY_WORDS = ("产量", "数量", "kg", "公斤", "重量")


def rule_based_plan(question: str, df: pd.DataFrame) -> Optional[tuple]:
    """('refuse', 指标名) | ('plan', 原始计划 dict) | None。

    1. 问「计划产量/计划数量」: 表里没有计划数量列 -> 有计划品项数就固定按品项数答 (并说明),
       没有就拒答; 不拿「实产良品」顶替。
    2. 问「没按计划完成/缺口」: 有 计划数+已产出数 成对列 -> 缺口 = 计划−已产出 逐行现算。
    """
    match = _PLAN_QUESTION_RE.fullmatch(question.strip())
    if not match:
        return None
    scope = _rule_month_filters(match, df)
    if scope is None:
        return None
    cols = [str(c) for c in df.columns]
    if match["quantity"]:
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
    if match["comparison"]:
        pair = _plan_count_columns(cols)
        if pair and all("品项" in c for c in pair):
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
    if match["gap"]:
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
    if n in (WEEK_ROLLUP_COL, MONTH_SCOPE_COL):
        raise PlanError(f"字段不存在: {n}")
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

    允许唯一前缀匹配 (良品 -> 良品kg)。解不出返回 None。
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
            cands = [c for c in columns if c != col and c.startswith(t)]
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
    role: Optional[dict] = None, hash_escaped: bool = False,
) -> pd.Series:
    col, op, val = f["col"], f["op"], f["value"]
    s = df[col]
    if op in ("==", "!=") and isinstance(val, str) and (
        pd.api.types.is_string_dtype(s.dtype) or pd.api.types.is_datetime64_any_dtype(s.dtype)
    ):
        sv0, v0 = s.astype(str).str.strip(), val.strip()
        if (_MONTH_PREFIX_RE.fullmatch(v0) and _has_date_role(s, role)
                and _valid_date_prefix(s, v0)):
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
    # A date-only boundary applies to the entire calendar day of a real date field.
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", v) and _has_date_role(s, role) and _valid_date_prefix(s, v):
        sv = sv.str[:10]
    # 日期时间边界与其他字符串仍按原始值比较。
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
    elif (_WEEK_ASK_RE.search(question or "") and WEEK_ROLLUP_COL in df.columns
          and WEEK_ROLLUP_COL not in res.group_by):
        res.notes.append("本结果未按 ISO 周汇总，回答不得把逐日数据或全表合计当作周结果")
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
    matched_mask = pd.Series(True, index=df.index)
    fdesc: List[str] = []
    for f in plan["filters"]:
        col = f["col"]
        # 校验各条件在原表中的取值，再求交集；空交集是有效的计算结果。
        matched_mask &= _apply_filter(
            df, f, strict_values, role=roles.get(col), hash_escaped=col in hash_cols,
        )
        fdesc.append(f"{f['col']} {f['op']} {f['value']!r}" if f["op"] != "notnull"
                     else f"{f['col']} 非空")
    work = df[matched_mask] if fdesc else df
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
        if asks_total(question):
            res.all_group_labels = [label for group in gb for label in out[group].drop_duplicates()]
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
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[；;。！？!?])|(?<=\.)(?=\s|$)")
_THOUSANDS_COMMA_RE = re.compile(r"(?<=\d),(?=\d)")
_PROTECT = "\x01"
_TOTAL_SCOPE_RE = re.compile(r"(?:全月|整月|全表)\s*(?:所有\s*)?|所有\s*")
_TOTAL_VERB = r"(?:一共|总共|合计|总计|共计|共)"
_CLAIM_NUMBER = r"(?P<number>[+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)"
_CLAIM_END = r"\s*[，,；;。.!！?？\n]?"
_UNIT_RE = re.compile(r"[（(]([^()（）]+)[）)]$|((?i:kg)|公斤|吨|元|卷|箱|分钟|台|批|人|次|件|个)$")
_LOCAL_TOTAL_MARKER_RE = re.compile(r"方面|其中|中的|这一类|的话")
_RANK_ANSWER_RE = re.compile(rf"(?:{_RANK_WORD})\s*的是\s*[「“\"]?\s*$")


def _group_total_topic(labels: Optional[list], answer: str) -> Optional[re.Pattern]:
    text = answer.casefold()
    names = {name for label in labels or [] if (name := str(label).strip()) and name.casefold() in text}
    if not names:
        return None
    group = "(?:" + "|".join(re.escape(name) for name in sorted(names, key=len, reverse=True)) + ")"
    return re.compile(rf"(?<![A-Za-z0-9_]){group}\s*[」”\"]?\s*[，,]$", re.IGNORECASE)


def _all_groups_prefix(claim: str, labels: Optional[list]) -> int:
    """An explicit enumeration qualifies only when it names every group exactly once."""
    if not labels or len(labels) < 2 or any(not isinstance(label, str) for label in labels):
        return 0
    names = [label.strip() for label in labels]
    if len(set(names)) != len(names) or any(not label or re.search(r"和|及|、", label) for label in names):
        return 0
    match = re.match(rf"(?P<groups>.+?)\s*(?={_TOTAL_VERB})", claim)
    if match and sorted(re.split(r"\s*(?:和|及|、)\s*", match["groups"])) == sorted(names):
        return match.end()
    return 0


def reconcile_answer_with_totals(answer: str, res: Optional[ExecResult]) -> tuple:
    """Correct explicit totals with a uniquely identified metric."""
    if res is None or not res.asks_total or not res.totals or not answer:
        return answer, False
    protected = _THOUSANDS_COMMA_RE.sub(_PROTECT, answer)
    group_topic = _group_total_topic(res.all_group_labels, answer)
    parts = []
    for sentence in _SENTENCE_SPLIT_RE.split(protected):
        local = False
        for clause in _CLAUSE_SPLIT_RE.split(sentence):
            if not clause:
                continue
            part = clause.replace(_PROTECT, ",")
            parts.append((part, local))
            topic = group_topic.search(part) if group_topic else None
            local = local or bool(_LOCAL_TOTAL_MARKER_RE.search(part))
            # 「最大的是 X」回答的是排名子问，同句后续仍可给出全表合计。
            if topic and not _RANK_ANSWER_RE.search(part[:topic.start()]):
                local = True
    units = {}
    for name, column in res.total_columns.items():
        match = _UNIT_RE.search(column)
        if match:
            units[name] = (match[1] or match[2]).lower()

    seen = set()
    updated = []
    for index, (part, is_local) in enumerate(parts):
        if is_local:
            updated.append(part)
            continue
        claim_start = len(part) - len(part.lstrip())
        scope = _TOTAL_SCOPE_RE.match(part, claim_start)
        if scope:
            claim_start = scope.end()
            if "所有" in scope[0] and len(res.group_by) == 1:
                claim_start += _all_groups_prefix(part[claim_start:], res.all_group_labels)
        elif index != 0 or not part[claim_start:].startswith("总计"):
            updated.append(part)
            continue

        claim = part[claim_start:]
        candidates = {}
        for name, value in res.totals.items():
            unit = units.get(name)
            column = res.total_columns.get(name, "")
            aliases = {name}
            if unit:
                aliases.add(_UNIT_RE.sub("", column))
            if column in ("用量(卷)", "用量（卷）") and res.group_by == ["卷膜编码"]:
                aliases.add("卷膜用量")
            metric = "|".join(re.escape(alias) for alias in sorted(aliases - {""}, key=len, reverse=True))
            suffix = rf"(?:\s*{re.escape(unit)})?" if unit else ""
            named = rf"(?:{metric})\s*(?:{_TOTAL_VERB}\s*)?(?:[=:：为]|是)?\s*"
            match = re.fullmatch(named + _CLAIM_NUMBER + suffix + _CLAIM_END, claim, re.IGNORECASE)
            if match is None and unit and list(units.values()).count(unit) == 1:
                unnamed = rf"{_TOTAL_VERB}\s*(?:(?:使用了|用了|用掉了|为|是)\s*)?"
                match = re.fullmatch(unnamed + _CLAIM_NUMBER + rf"\s*{re.escape(unit)}" + _CLAIM_END,
                                     claim, re.IGNORECASE)
            if match:
                candidates[name] = (match, value)
        if len(candidates) == 1:
            name, (match, value) = next(iter(candidates.items()))
            left, right = match.span("number")
            if not _LOCAL_TOTAL_MARKER_RE.search(part[:claim_start + left]):
                seen.add(name)
                expected = _fmt(value)
                if float(match["number"].replace(",", "")) != float(expected.replace(",", "")):
                    part = part[:claim_start + left] + expected + part[claim_start + right:]
        updated.append(part)
    out = "".join(updated)
    # A requested total absent from the answer is supplied separately without editing an ambiguous claim.
    missing = [f"全表 {name} = {_fmt(v)}" for name, v in res.totals.items() if name not in seen]
    if missing:
        out = out.rstrip() + "\n\n**更正（程序核对）**\n" + "。\n".join(missing) + "。"
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
    kind: str                      # refuse | text_listing | computed | uncomputed | none
    text: str = ""                 # refuse/text_listing: 完整答复; computed/uncomputed: 注入提示词的段落
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

    text_listing = _text_listing_answer(question, df, field_meta)
    if text_listing is not None:
        return RowLevelOutcome("text_listing", text_listing)

    df = with_gap_columns(df)
    df, cross_year = _with_calendar_columns(df, question, field_meta)

    rule = None
    if not cross_year:
        rule = rule_based_plan(question, df)
        if rule is None:
            week = week_rollup_plan(question, df, field_meta)
            if week:
                rule = ("plan", week)
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
