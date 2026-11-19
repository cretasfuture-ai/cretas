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
    return f"""数据表共 {len(df)} 行。全部字段:
{cols}

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
6. 全部筛选/聚合都针对整张表, 不要自己估算数值。"""


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


def _apply_filter(df: pd.DataFrame, f: dict) -> pd.Series:
    col, op, val = f["col"], f["op"], f["value"]
    s = df[col]
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


def _fmt(v: Any, pct: bool = False) -> str:
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return "(空)"
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


def execute_plan(df: pd.DataFrame, plan: dict) -> ExecResult:
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
        work = work[_apply_filter(work, f)]
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
        res = ExecResult(cols, shown.values.tolist(), total, matched, fdesc, notes,
                         group_by=gb, mode="grouped")
        res.notes.append(f"共 {len(out)} 个分组, 展示前 {len(shown)} 个")
        res.pct_cols = pct_cols
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
                notes.append(f"另有 {len(extra)} 行与末位并列, 已一并列出")
        if nan_n:
            notes.append(f"排序字段「{sb}」有 {nan_n} 行为空, 已排除出比较 (不是 0)")
        shown = head[show_cols]
        res = ExecResult(show_cols, shown.values.tolist(), total, matched, fdesc, notes, mode="rows",
                         pct_cols=_rate_cols(work, show_cols))
        res.notes.append(f"按「{sb}」{'降序' if plan['descending'] else '升序'}取前 {plan['limit']} 行")
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
            _fmt(v, pct=(c in pct)) for c, v in zip(res.columns, r)) + " |")
    lines = [
        f"## 全表精确计算结果 (权威: 服务端在该数据集全部 {res.total_rows} 行上计算)",
        "回答规则: 数字、日期、产品、机台等只能取自下表同一行, 不得改写、拼接别的行的字段、"
        "不得补充表里没有的日期/名称; 表里没有的信息直接说「数据里没有」。",
    ]
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
) -> RowLevelOutcome:
    """对整份数据做行级计算; 返回拒答 / 已计算段落 / 未能计算 / 无需计算。

    llm_call(prompt, system_role) -> 文本 (JSON)。只用来出查询计划。
    """
    if not (question or "").strip() or df is None or df.empty:
        return RowLevelOutcome("none")

    missing = find_missing_concept(question, field_names)
    if missing:
        return RowLevelOutcome("refuse", build_refusal(missing, field_names))

    try:
        raw = await llm_call(build_plan_prompt(question, df), PLAN_SYSTEM)
    except Exception as e:  # LLM 不可用: 如实标记未计算, 不退回样本猜
        logger.warning("[row-level-qa] planner call failed: %s", e)
        return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason="查询规划不可用"))
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
        res = execute_plan(df, vplan)
    except PlanError as e:
        return RowLevelOutcome("uncomputed", UNCOMPUTED_NOTE.format(reason=str(e)), plan=plan)
    return RowLevelOutcome("computed", render_block(question, res), plan=vplan, result=res)
