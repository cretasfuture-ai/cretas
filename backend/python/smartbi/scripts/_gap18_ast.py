"""AST census for 归因七步链 (#18) —— ⛔ 不用 grep 数结构.

Counts REAL ast.Call nodes (not docstring mentions) for the step-⑥/⑦ appenders,
and reports which enclosing function each call sits in.

Exit codes (三态):
  0 = 量到了, 且每个被数的符号至少有 1 个真实调用点
  1 = 量到了, 但有符号 0 调用点 (= 定义了没接上)
  2 = 没量到 (文件读不到 / 解析失败 / 阳性对照不成立)
"""
from __future__ import annotations

import ast
import pathlib
import sys

TARGET = pathlib.Path(__file__).resolve().parents[1] / "agent" / "synthesis_engine.py"

# 被数的符号: 第⑥步追加器 / 第⑦步收尾常量 / 第⑥步维度表 / 第②步是非题词表
SYMBOLS = (
    "_append_investigation_directions",
    "_append_dimension_guidance",
)
NAMES = (
    "_INVESTIGATION_LAST_RESORT",
    "_CAUSE_INVESTIGATION_DIMENSIONS",
    "_yes_no_decision_cues",
)
# 阳性对照: 同一个文件里、同一个调用链上的邻居, 必然有 >=1 个真实调用点.
# ⚠️ 第一版写的是 `_render_attribution` —— 那个符号在 factbook.py 里, 本探针
#    根本没解析那个文件 ⇒ 读出 0, rc=2 正确地把那轮读数作废了.
#    教训: 阳性对照必须选**本仪器扫描范围内**的东西, 否则它测的是别的东西.
POSITIVE_CONTROL = "_append_store_comparison_table"


def _enclosing(tree: ast.AST, node: ast.AST) -> str:
    best = "<module>"
    for fn in ast.walk(tree):
        if isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if fn.lineno <= node.lineno and node.lineno <= (fn.end_lineno or fn.lineno):
                best = fn.name
    return best


def main() -> int:
    if not TARGET.exists():
        print(f"INSTRUMENT_DEAD 找不到 {TARGET}")
        return 2
    with open(TARGET, "r", encoding="utf-8", newline="") as fh:
        src = fh.read()
    try:
        tree = ast.parse(src)
    except SyntaxError as exc:
        print(f"INSTRUMENT_DEAD 解析失败: {exc}")
        return 2

    calls: dict[str, list[tuple[int, str]]] = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        name = None
        if isinstance(fn, ast.Name):
            name = fn.id
        elif isinstance(fn, ast.Attribute):
            name = fn.attr
        if name in SYMBOLS or name == POSITIVE_CONTROL:
            calls.setdefault(name, []).append((node.lineno, _enclosing(tree, node)))

    # 名字引用 (非调用): 常量表被谁读
    reads: dict[str, list[tuple[int, str]]] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id in NAMES and isinstance(node.ctx, ast.Load):
            reads.setdefault(node.id, []).append((node.lineno, _enclosing(tree, node)))

    print(f"FILE {TARGET}")
    print(f"LINES {len(src.splitlines())}")
    print("--- ast.Call 真实调用点 ---")
    for sym in SYMBOLS:
        got = calls.get(sym, [])
        print(f"{sym}: {len(got)} 处")
        for lineno, enc in got:
            print(f"    L{lineno}  in {enc}()")
    print("--- ast.Name Load 引用点 (常量表被谁读) ---")
    for nm in NAMES:
        got = reads.get(nm, [])
        print(f"{nm}: {len(got)} 处")
        for lineno, enc in got:
            print(f"    L{lineno}  in {enc}()")

    pc = len(calls.get(POSITIVE_CONTROL, []))
    print(f"--- 阳性对照 ---\n{POSITIVE_CONTROL}: {pc} 处 (预期 >=1)")
    if pc < 1:
        print("INSTRUMENT_DEAD 阳性对照读出 0 —— 本次读数作废")
        return 2

    zero = [s for s in SYMBOLS if not calls.get(s)] + [n for n in NAMES if not reads.get(n)]
    if zero:
        print("DEFINED_BUT_UNCALLED: " + ", ".join(zero))
        return 1
    print("ALL_WIRED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
