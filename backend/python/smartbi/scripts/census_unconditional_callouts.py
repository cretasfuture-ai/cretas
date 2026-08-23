"""普查「点名 + 建议去查」的产出句子, 并按**守卫强度**分档。

## 它回答的那一句话

> 排在最前面的那个命中, 老板去查会不会一无所获。

一条「点名某个对象 + 让他去做点什么」的句子, 如果**没有挂在差距阈值上**,
那么在一份平数据上它照样会点名第一名 —— 而第一名和第二名可能只差 0.3 个百分点。
老板照它去查, 必然一无所获。烧掉的是「这东西说的话能信」。

## ⛔ 判据用结构, 不用关键词表

关键词永远补不全(本仓明确裁定), 而且写一张「提示词表」会撞词表棘轮闸。
这里三个维度**全部来自 AST**:

1. **面向用户的中文串** —— 字符类判据(CJK unicode range), ⛔ 不是词表。
   ⚠️ 这是**代理判据**: 它看不见「这个串会不会走到用户面前」(SQL 串 / 给模型的
   prompt / 开发者断言消息都含中文)。所以本探针**只产候选**, 逐条人读定档。

2. **点名** —— f-string 里存在一个插值, 它
   (a) **没有数值 format_spec**(`.1f` / `,.2f` / `d` 这些是**报数**不是点名), 且
   (b) 值能追溯到**数据行**(`r["store_name"]` / `r.get(...)` / `row.name`,
       含「先赋给局部变量再插值」一跳)。
   ⇒ 纯参数插值(`{noun}` / `{title}`)被 (b) 过滤掉。

3. **守卫强度** —— 从该 f-string 沿 parent 链上溯, 收集所有 `ast.If` / `ast.IfExp`
   的 test:
   - `THRESHOLD`  某个 test 里有 `<` / `>` / `<=` / `>=` 且一侧是数值常量或
                  模块级常量名  ⇒ 挂在阈值上
   - `EXISTENCE`  只有真值判断 / `is None` / `==` / `in`  ⇒ 只判「有没有数据」
   - `NONE`       一个 if 都没有                          ⇒ 无条件发

## 三态退出码(硬约束 4)

    rc=0  没有 NONE 档的候选
    rc=1  有 NONE 档候选(读数有效, 且指向缺陷)
    rc=2  **这次没量到东西** —— 阳性对照没通过 / 候选数为 0 / 解析失败

⚠️ 阳性对照是**跨样本**的: 单个文件上成立的对照证明不了仪器在别的文件上活着,
   所以两条锚点刻意放在**两个不同的模块**里。

用法::

    python -m smartbi.scripts.census_unconditional_callouts            # 汇总
    python -m smartbi.scripts.census_unconditional_callouts --json     # 逐条
    python -m smartbi.scripts.census_unconditional_callouts --mutate-selftest
"""
from __future__ import annotations

import argparse
import ast
import json
import pathlib
import sys
from typing import Dict, Iterable, List, Optional, Set, Tuple

# ⚠️ 常量一律带 CENSUS_ 前缀 —— 通名(`CASES` / `SLOTS` / `QS`)会撞同名常量漂移闸
#    `tests/test_no_drifted_duplicate_constants.py`。
CENSUS_ROOT = pathlib.Path(__file__).resolve().parents[1]
CENSUS_SCAN_DIRS: Tuple[str, ...] = ("gold/restaurant", "agent")

#: 数值 format_spec 的判据 —— 带这些的插值是**报数**, 不是**点名**。
#: ⛔ 这不是词表: 它是 format mini-language 的类型码, 集合由 PEP 3101 封闭。
CENSUS_NUMERIC_FORMAT_CODES: frozenset = frozenset("bcdeEfFgGnoxX%")

#: 归档 / 测试 / 探针自身不算产品正文。
CENSUS_SKIP_NAME_PARTS: Tuple[str, ...] = ("test_", "_test", "/tests/", "/eval/")

CENSUS_TIER_THRESHOLD = "THRESHOLD"
CENSUS_TIER_EXISTENCE = "EXISTENCE"
CENSUS_TIER_NONE = "NONE"

#: 跨样本阳性对照 —— **两个不同模块**, 各钉一条已知必然存在的读数。
#: ⚠️ 它们证明的是「仪器活着」, ⛔ 不是「产品对」。
CENSUS_POSITIVE_CONTROLS: Tuple[Tuple[str, str, str], ...] = (
    # (相对路径, 函数名, 期望这条函数里至少有一档)
    # PR #2859 修好的那条(点名最弱门店), 现在挂在 `_STORE_SPREAD_WORTH_CHASING_PCT` 上
    ("gold/restaurant/restaurant_ops_router.py", "resolve_store_margin", CENSUS_TIER_THRESHOLD),
    ("gold/restaurant/restaurant_ops_router.py", "_store_lead_sentence", CENSUS_TIER_EXISTENCE),
)


def _census_has_cjk(text: str) -> bool:
    """字符类判据 —— ⛔ 不是词表。"""
    return any("一" <= ch <= "鿿" for ch in text)


def _census_format_is_numeric(spec: Optional[ast.expr]) -> bool:
    """format_spec 是不是**数值格式**(⇒ 这个插值在报数, 不在点名)。"""
    if spec is None:
        return False
    if not isinstance(spec, ast.JoinedStr):
        return False
    literal = "".join(
        v.value for v in spec.values
        if isinstance(v, ast.Constant) and isinstance(v.value, str)
    )
    if not literal:
        return False
    # 末位类型码, 或出现千分位 / 精度点
    if literal[-1] in CENSUS_NUMERIC_FORMAT_CODES:
        return True
    return "," in literal or "." in literal


def _census_source_of(node: ast.expr) -> Optional[str]:
    """这个插值的值**从哪来** —— 只认「数据行」形状, 认不出就 None。

    认三种(以及它们的嵌套):
        r["store_name"]        Subscript with str slice
        r.get("store_name")    Call .get(<str>)
        row.store_name         Attribute
    """
    if isinstance(node, ast.Subscript):
        key = node.slice
        if isinstance(key, ast.Constant) and isinstance(key.value, str):
            base = _census_expr_name(node.value)
            return f'{base}["{key.value}"]'
        return None
    if isinstance(node, ast.Call):
        func = node.func
        if isinstance(func, ast.Attribute) and func.attr == "get" and node.args:
            first = node.args[0]
            if isinstance(first, ast.Constant) and isinstance(first.value, str):
                base = _census_expr_name(func.value)
                return f'{base}.get("{first.value}")'
        # f(x) —— 往参数里再看一层(例: str(r["name"]))
        for arg in node.args:
            inner = _census_source_of(arg)
            if inner:
                return inner
        return None
    if isinstance(node, ast.Attribute):
        base = _census_expr_name(node.value)
        if base:
            return f"{base}.{node.attr}"
        return None
    if isinstance(node, (ast.BinOp, ast.BoolOp, ast.IfExp)):
        for child in ast.iter_child_nodes(node):
            if isinstance(child, ast.expr):
                inner = _census_source_of(child)
                if inner:
                    return inner
    return None


def _census_expr_name(node: ast.expr) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return f"{_census_expr_name(node.value)}.{node.attr}"
    if isinstance(node, ast.Subscript):
        return f"{_census_expr_name(node.value)}[]"
    if isinstance(node, ast.Call):
        return f"{_census_expr_name(node.func)}()"
    return "?"


def _census_local_row_vars(fn: ast.AST) -> Dict[str, str]:
    """函数体内「局部变量 = 数据行取值」的一跳映射。

    ⇒ `name = r["store_name"]` 之后的 `{name}` 也算点名。
    """
    out: Dict[str, str] = {}
    for node in ast.walk(fn):
        if isinstance(node, ast.Assign):
            src = _census_source_of(node.value)
            if not src:
                continue
            for tgt in node.targets:
                if isinstance(tgt, ast.Name):
                    out[tgt.id] = src
        elif isinstance(node, (ast.For, ast.comprehension)):
            tgt = node.target
            it = node.iter
            base = _census_expr_name(it)
            names = (
                [tgt] if isinstance(tgt, ast.Name)
                else [e for e in getattr(tgt, "elts", []) if isinstance(e, ast.Name)]
            )
            for nm in names:
                out.setdefault(nm.id, f"for {nm.id} in {base}")
    return out


def _census_test_is_threshold(test: ast.expr) -> bool:
    """这个条件里有没有**数量比较**(⇒ 挂在阈值上)。"""
    for node in ast.walk(test):
        if not isinstance(node, ast.Compare):
            continue
        if not any(isinstance(op, (ast.Lt, ast.Gt, ast.LtE, ast.GtE))
                   for op in node.ops):
            continue
        operands = [node.left] + list(node.comparators)
        for opnd in operands:
            if isinstance(opnd, ast.Constant) and isinstance(opnd.value, (int, float)):
                # ⚠️ `len(x) > 0` / `>= 1` / `< 2` 是**存在性**, 不是差距阈值。
                if isinstance(opnd.value, bool):
                    continue
                if _census_compare_is_existence(node):
                    continue
                return True
            if isinstance(opnd, ast.Name) and (
                opnd.id.isupper() or opnd.id.lstrip("_").isupper()
                or (opnd.id.startswith("_") and any(c.isupper() for c in opnd.id))
            ):
                return True
    return False


def _census_compare_is_existence(node: ast.Compare) -> bool:
    """`len(rows) < 2` / `len(x) > 0` 这类是「有没有数据」, ⛔ 不是「差距够不够大」。"""
    left = node.left
    if isinstance(left, ast.Call) and _census_expr_name(left.func) in ("len",):
        return True
    for c in node.comparators:
        if isinstance(c, ast.Call) and _census_expr_name(c.func) in ("len",):
            return True
    return False


def _census_build_parents(tree: ast.AST) -> Dict[int, ast.AST]:
    parents: Dict[int, ast.AST] = {}
    for node in ast.walk(tree):
        for child in ast.iter_child_nodes(node):
            parents[id(child)] = node
    return parents


def _census_guard_chain(
    node: ast.AST, parents: Dict[int, ast.AST], stop_at: ast.AST,
) -> List[str]:
    """沿 parent 链上溯到函数边界, 收集所有 if 的 test 源码。"""
    tests: List[str] = []
    cur: Optional[ast.AST] = node
    while cur is not None and cur is not stop_at:
        parent = parents.get(id(cur))
        if parent is None:
            break
        if isinstance(parent, ast.If):
            tests.append(ast.unparse(parent.test))
        elif isinstance(parent, ast.IfExp) and cur is not parent.test:
            tests.append(ast.unparse(parent.test))
        cur = parent
    return tests


def _census_sink_of(node: ast.AST, parents: Dict[int, ast.AST]) -> str:
    """这个串**流向哪里** —— 分诊用, ⛔ 不是定档判据。

    `raise` / `logger.*` / SQL 拼接不会走到老板面前; `return` / `append` /
    赋给正文变量会。⚠️ 它是**代理**: 真正判「会不会进正文」要读调用链。
    """
    cur: Optional[ast.AST] = node
    hops = 0
    while cur is not None and hops < 12:
        parent = parents.get(id(cur))
        if parent is None:
            return "?"
        if isinstance(parent, ast.Return):
            return "return"
        if isinstance(parent, ast.Raise):
            return "raise"
        if isinstance(parent, ast.Assign):
            tgts = [_census_expr_name(t) for t in parent.targets]
            return f"assign:{','.join(tgts)}"
        if isinstance(parent, ast.AugAssign):
            return f"augassign:{_census_expr_name(parent.target)}"
        if isinstance(parent, ast.Call):
            fname = _census_expr_name(parent.func)
            short = fname.split(".")[-1]
            if short in ("append", "extend", "add", "insert"):
                return f"append:{fname}"
            if short in ("debug", "info", "warning", "error", "exception", "critical"):
                return "log"
            return f"call:{fname}"
        if isinstance(parent, ast.keyword):
            return f"kwarg:{parent.arg}"
        if isinstance(parent, ast.Dict):
            for k, v in zip(parent.keys, parent.values):
                if v is cur and isinstance(k, ast.Constant):
                    return f'dictkey:{k.value}'
            return "dict"
        cur = parent
        hops += 1
    return "?"


def _census_enclosing_funcs(tree: ast.AST) -> List[ast.AST]:
    return [
        n for n in ast.walk(tree)
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
    ]


def _census_scan_file(path: pathlib.Path, rel: str) -> List[dict]:
    with open(path, "r", encoding="utf-8", newline="") as fh:
        src = fh.read()
    tree = ast.parse(src, filename=str(path))
    parents = _census_build_parents(tree)
    findings: List[dict] = []

    # ── 轴二: **纯常量**的中文串 ──────────────────────────────────────────
    # 🔴 仪器盲区补丁(2026-08-18): 实例一(`_advice_line`)的**原形**是
    #    「一句无条件常量」—— 没有任何插值 ⇒ 它是 `ast.Constant` 而不是
    #    `ast.JoinedStr`, 轴一**完全看不见它**。
    # ▎「点名」那一维在这里判不出来(常量里没有插值), 所以这一轴**只报守卫强度**,
    # ▎逐条人读定档。⚠️ 它的假阳比轴一高得多, 因此单列 `axis` 字段。
    for fn in _census_enclosing_funcs(tree):
        for node in ast.walk(fn):
            if not isinstance(node, ast.Constant) or not isinstance(node.value, str):
                continue
            literal = node.value
            if not _census_has_cjk(literal):
                continue
            # docstring 不是产出
            if isinstance(parents.get(id(node)), ast.Expr):
                continue
            sink = _census_sink_of(node, parents)
            # 只看会流进正文的落点 —— ⛔ 不是定档判据, 是分诊
            if sink.split(":")[0] not in ("return", "append", "assign", "augassign"):
                continue
            tests = _census_guard_chain(node, parents, fn)
            if any(_census_test_is_threshold_src(t) for t in tests):
                tier = CENSUS_TIER_THRESHOLD
            elif tests:
                tier = CENSUS_TIER_EXISTENCE
            else:
                tier = CENSUS_TIER_NONE
            findings.append({
                "file": rel,
                "line": node.lineno,
                "func": getattr(fn, "name", "?"),
                "axis": "const",
                "tier": tier,
                "sink": sink,
                "guards": tests,
                "named": [],
                "text": literal.strip().replace("\n", " ")[:160],
            })

    # ── 轴一: 点名型 f-string ─────────────────────────────────────────────
    for fn in _census_enclosing_funcs(tree):
        row_vars = _census_local_row_vars(fn)
        for node in ast.walk(fn):
            if not isinstance(node, ast.JoinedStr):
                continue
            literal = "".join(
                v.value for v in node.values
                if isinstance(v, ast.Constant) and isinstance(v.value, str)
            )
            if not _census_has_cjk(literal):
                continue
            named: List[str] = []
            for v in node.values:
                if not isinstance(v, ast.FormattedValue):
                    continue
                if _census_format_is_numeric(v.format_spec):
                    continue
                src_desc = _census_source_of(v.value)
                if src_desc is None and isinstance(v.value, ast.Name):
                    src_desc = row_vars.get(v.value.id)
                if src_desc:
                    named.append(src_desc)
            if not named:
                continue
            tests = _census_guard_chain(node, parents, fn)
            if any(_census_test_is_threshold_src(t) for t in tests):
                tier = CENSUS_TIER_THRESHOLD
            elif tests:
                tier = CENSUS_TIER_EXISTENCE
            else:
                tier = CENSUS_TIER_NONE
            findings.append({
                "file": rel,
                "line": node.lineno,
                "func": getattr(fn, "name", "?"),
                "axis": "named",
                "tier": tier,
                "sink": _census_sink_of(node, parents),
                "guards": tests,
                "named": sorted(set(named)),
                "text": literal.strip().replace("\n", " ")[:160],
            })
    return findings


_CENSUS_TEST_CACHE: Dict[str, bool] = {}


def _census_test_is_threshold_src(test_src: str) -> bool:
    """对 unparse 出来的 test 源码再解析一次做判定(缓存)。

    ⚠️ 这里**只是**把上面那条 AST 判据套在同一棵子树上 —— ⛔ 不是文本匹配。
    """
    hit = _CENSUS_TEST_CACHE.get(test_src)
    if hit is not None:
        return hit
    try:
        parsed = ast.parse(test_src, mode="eval").body
        hit = _census_test_is_threshold(parsed)
    except SyntaxError:
        hit = False
    _CENSUS_TEST_CACHE[test_src] = hit
    return hit


def census_collect() -> List[dict]:
    out: List[dict] = []
    for sub in CENSUS_SCAN_DIRS:
        base = CENSUS_ROOT / sub
        if not base.exists():
            continue
        for path in sorted(base.rglob("*.py")):
            rel = path.relative_to(CENSUS_ROOT).as_posix()
            if any(part in rel for part in CENSUS_SKIP_NAME_PARTS):
                continue
            out.extend(_census_scan_file(path, rel))
    return out


def census_positive_controls(findings: List[dict]) -> List[str]:
    """跨样本阳性对照 —— 返回**没通过**的那几条。"""
    failed: List[str] = []
    for rel, func, tier in CENSUS_POSITIVE_CONTROLS:
        ok = any(
            f["file"] == rel and f["func"] == func and f["tier"] == tier
            for f in findings
        )
        if not ok:
            failed.append(f"{rel}::{func} 期望有 {tier} 档, 实际没有")
    return failed


def main(argv: Optional[Iterable[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--json", action="store_true", help="逐条输出 JSON")
    ap.add_argument("--tier", default="", help="只看某一档")
    ap.add_argument("--axis", default="", help="named(点名型 f-string) / const(纯常量)")
    args = ap.parse_args(list(argv) if argv is not None else None)

    try:
        findings = census_collect()
    except SyntaxError as exc:  # 仪器起不来
        print(f"INSTRUMENT DEAD — 解析失败: {exc}", file=sys.stderr)
        return 2

    if not findings:
        print("INSTRUMENT DEAD — 候选数为 0, 本次读数作废", file=sys.stderr)
        return 2

    failed = census_positive_controls(findings)
    if failed:
        print("INSTRUMENT DEAD — 阳性对照未通过, 本次读数作废", file=sys.stderr)
        for line in failed:
            print(f"  {line}", file=sys.stderr)
        return 2

    # ⚠️ rc 由**全量**算, ⛔ 不由过滤后的子集算 —— 否则 `--tier THRESHOLD` 会
    #    读出 rc=0 而看起来像「没有无条件的」。
    none_total = [f for f in findings if f["tier"] == CENSUS_TIER_NONE]

    if args.tier:
        findings = [f for f in findings if f["tier"] == args.tier]
    if args.axis:
        findings = [f for f in findings if f["axis"] == args.axis]

    if args.json:
        for f in findings:
            print(json.dumps(f, ensure_ascii=False))
    else:
        print("口径: gold/restaurant + agent, 两轴")
        print("  named = f-string 含中文字面 且 至少一个「非数值 format_spec +")
        print("          值来自数据行取值」的插值(=点名)")
        print("  const = 纯常量中文串, 落点是 return/append/assign(=会进正文)")
        print("⚠️ 中文/落点都是**代理判据**: 看不见「这个串会不会走到老板面前」。")
        print("   ⇒ 本探针只产候选, 定档靠人读 + 真实数据。\n")
        print(f"候选总数: {len(findings)}")
        for axis in ("named", "const"):
            sub = [f for f in findings if f["axis"] == axis]
            counts: Dict[str, int] = {}
            for f in sub:
                counts[f["tier"]] = counts.get(f["tier"], 0) + 1
            print(f"  轴 {axis:<6} {len(sub):>4}   " + "  ".join(
                f"{t}={counts.get(t, 0)}" for t in
                (CENSUS_TIER_NONE, CENSUS_TIER_EXISTENCE, CENSUS_TIER_THRESHOLD)
            ))
        by_file: Dict[str, int] = {}
        for f in findings:
            by_file[f["file"]] = by_file.get(f["file"], 0) + 1
        print("\n按文件(全部, ⛔ 不截断):")
        for name, n in sorted(by_file.items(), key=lambda kv: -kv[1]):
            print(f"  {n:>4}  {name}")

    return 1 if none_total else 0


if __name__ == "__main__":
    sys.exit(main())
