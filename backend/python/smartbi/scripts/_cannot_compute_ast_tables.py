"""AST 抽取: 两个 resolver 真正读的表 + 它们用哪个连接池.

⛔ 不用 grep 数结构 —— 用 AST 从函数体(含**传递闭包**里的模块内 helper)
抽出所有字符串常量, 再对字符串做 SQL 表名解析.

用法:
    python -m smartbi.scripts._cannot_compute_ast_tables

退出码: 0=抽到了; 2=没量到(锚点函数找不到 / 抽出 0 张表 —— 仪器坏)
"""

from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

CANNOT_COMPUTE_ROUTER_PATH = (
    Path(__file__).resolve().parents[1] / "gold" / "restaurant" / "restaurant_ops_router.py"
)

CANNOT_COMPUTE_ENTRY_FUNCS = ("resolve_recipe_cost", "resolve_supplier_price")

CANNOT_COMPUTE_POOL_FUNCS = ("get_pg_pool", "get_cretas_pool", "get_food_kb_pool")

# FROM / JOIN / UPDATE / INTO 后面的标识符 (允许 schema.table)
CANNOT_COMPUTE_TABLE_RE = re.compile(
    r"\b(?:from|join|update|into)\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)?)",
    re.IGNORECASE,
)

# 明显不是表的 (CTE 名 / 子查询别名会被单独标出)
CANNOT_COMPUTE_SQL_KEYWORDS = {
    "select",
    "lateral",
    "unnest",
    "generate_series",
    "values",
}


def _module_funcs(tree: ast.Module) -> dict[str, ast.AST]:
    out: dict[str, ast.AST] = {}
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            out[node.name] = node
    return out


def _called_names(node: ast.AST) -> set[str]:
    names: set[str] = set()
    for sub in ast.walk(node):
        if isinstance(sub, ast.Call):
            fn = sub.func
            if isinstance(fn, ast.Name):
                names.add(fn.id)
            elif isinstance(fn, ast.Attribute):
                names.add(fn.attr)
    return names


def _cte_names(sql: str) -> set[str]:
    """WITH x AS (...), y AS (...) —— 这些不是物理表."""
    out: set[str] = set()
    for m in re.finditer(r"(?:with|,)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s+as\s*\(", sql, re.IGNORECASE):
        out.add(m.group(1).lower())
    return out


def collect(entry: str, funcs: dict[str, ast.AST]) -> dict:
    """从 entry 出发做传递闭包, 收表名 / 池函数 / 走过的 helper."""
    if entry not in funcs:
        return {"error": f"锚点函数 {entry} 不在模块顶层"}

    seen: set[str] = set()
    todo = [entry]
    strings: list[str] = []
    pools: set[str] = set()
    visited_helpers: list[str] = []

    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        node = funcs.get(name)
        if node is None:
            continue
        visited_helpers.append(name)
        for sub in ast.walk(node):
            if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
                strings.append(sub.value)
        for called in _called_names(node):
            if called in CANNOT_COMPUTE_POOL_FUNCS:
                pools.add(called)
            if called in funcs and called not in seen:
                todo.append(called)

    tables: dict[str, set[str]] = {}
    for s in strings:
        if not CANNOT_COMPUTE_TABLE_RE.search(s):
            continue
        ctes = _cte_names(s)
        for m in CANNOT_COMPUTE_TABLE_RE.finditer(s):
            t = m.group(1).lower()
            if t in CANNOT_COMPUTE_SQL_KEYWORDS or t in ctes:
                continue
            tables.setdefault(t, set()).add("cte-local" if t in ctes else "sql")

    return {
        "entry": entry,
        "helpers_walked": sorted(visited_helpers),
        "helper_count": len(visited_helpers),
        "pool_funcs": sorted(pools),
        "tables": sorted(tables),
    }


def main() -> int:
    with open(CANNOT_COMPUTE_ROUTER_PATH, "r", encoding="utf-8", newline="") as fh:
        src = fh.read()
    tree = ast.parse(src)
    funcs = _module_funcs(tree)
    print(f"MODULE={CANNOT_COMPUTE_ROUTER_PATH}")
    print(f"MODULE_TOPLEVEL_FUNCS={len(funcs)}")

    bad = 0
    for entry in CANNOT_COMPUTE_ENTRY_FUNCS:
        res = collect(entry, funcs)
        print("=" * 70)
        if "error" in res:
            print(f"ENTRY={entry} ERROR={res['error']}")
            bad += 1
            continue
        print(f"ENTRY={entry}")
        print(f"  helpers_walked={res['helper_count']}")
        print(f"  pool_funcs={res['pool_funcs']}")
        print(f"  tables ({len(res['tables'])}):")
        for t in res["tables"]:
            print(f"    - {t}")
        if not res["tables"]:
            bad += 1

    # 阳性对照: 一个必然含表名的函数, 抽不出来说明正则坏了
    print("=" * 70)
    ctrl = collect("resolve_recipe_cost", funcs)
    if "fact_pos_item" not in " ".join(ctrl.get("tables", [])) and not ctrl.get("tables"):
        print("POSITIVE_CONTROL=FAILED 仪器坏了")
        return 2
    print(f"POSITIVE_CONTROL=OK ({len(ctrl.get('tables', []))} tables from recipe_cost)")
    return 2 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
