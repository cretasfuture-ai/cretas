# -*- coding: utf-8 -*-
"""Q09「部分能做」的五条变异对照 —— 每条断言都要证明**它能红**，且红在它守的那条上。

⛔ 三层判据，一条都不能省：
  ① 变异**真的生效了**（锚点恰好 1 次 / 落盘 / `compile()` 通过）——
     形态 C″：变异不红有两个相反成因（断言恒真 / 变异没打进去），症状一模一样。
  ② 该红的红了，且红的是**它守的那一条**。
  ③ **不该红的没红** —— 计划外变红说明变异打在公共路径上，归因不干净。

⚠️ 红在 `NameError` / `SyntaxError` 上的变异**不算数**（那是把代码弄坏）。
🔴 本判据是 AND 链 ⇒ **每加一条条件，把全部变异重跑一遍**
   （本仓刚实测：加第三个 AND 条件后两条阴性对照变红 0 条，输入被短路了）。

硬约束 5：文本模式 + `newline=''`（不转行尾，且无 bytes 的 ASCII 限制）。
"""
from __future__ import annotations

import ast
import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_SVC = os.path.join(_HERE, "smartbi", "gold", "restaurant",
                    "restaurant_intent_service.py")
_MY_TEST = "smartbi/gold/tests/test_partial_grain_is_given_not_withheld.py"

#: (名字, 旧串, 新串, 期望变红的测试名片段)
_PARTIAL_GRAIN_MUTATIONS = [
    # 🔴 M1 第一版期望的是那三条 helper 断言 —— 它们**一条都没红**。
    #    ⛔ 不是变异没生效（锚点唯一 / 落盘 / compile 三层都过），
    #    是那三条**直接调 helper**，绕过了生产调用点
    #    ▎形态 B：我测的是 helper，不是接线。
    #    ⇒ 补了 AST 接线断言，M1 现在打在它身上。
    ("M1 从 caller_judged 里摘掉 store（接线断开）",
     '"store": bool(store_mentions', '"__no_store__": bool(store_mentions',
     ["test_the_call_site_passes_a_store_judgement"]),
    ("M2 剥了但不投影（说明恒空）",
     'if not dropped:\n        return ""\n    # ⚠️ `spec` 是**剥完之后**的',
     'if True:\n        return ""\n    # ⚠️ `spec` 是**剥完之后**的',
     ["test_dropping_must_be_told_to_the_boss",
      "test_the_note_comes_first_not_after_the_numbers"]),
    ("M3 说明里不提被剥掉的那一层",
     f'f"**按{{_dimension_names(dropped)}}拆不出来**，这次只能按"',
     'f"**拆不出来**，这次只能按"',
     ["test_dropping_must_be_told_to_the_boss",
      "test_the_note_comes_first_not_after_the_numbers"]),
    ("M4 点了名也照剥（无视 caller_judged）",
     "            named = bool(judged[dim])",
     "            named = False",
     ["test_a_named_store_is_never_dropped"]),
    # 🔴 M5 第一版把 `supported` **整个打空** —— 那是公共路径，
    #    四条计划外断言跟着红了，归因不干净。
    #    ⇒ 换成只摘掉 STORE_MARGIN 的 store 这一格：它只影响
    #      「能按店出的 resolver 不许被剥」那一条，⛔ 不碰 INVENTORY_WARNING
    #      （那三条）也不碰 SALES_SUMMARY（ingredient 那条）。
    ("M5 把 STORE_MARGIN 的 store 从能力表里摘掉",
     '"RESTAURANT_OPS_STORE_MARGIN": frozenset({"store", "dish"})',
     '"RESTAURANT_OPS_STORE_MARGIN": frozenset({"dish"})',
     ["test_a_dimension_the_resolver_can_serve_is_never_dropped"]),
    ("M6 拆掉答案里的投影接线（算出来了但不发）",
     "_grain_note = _partial_grain_note(_dropped_grains, spec, plan)",
     '_grain_note = ""',
     ["test_the_note_is_wired_into_the_answer"]),
]


def _read():
    return open(_SVC, "r", encoding="utf-8", newline="").read()


def _write(s):
    open(_SVC, "w", encoding="utf-8", newline="").write(s)


def _failed(out):
    return [l.split(" ", 1)[1].split(" - ")[0]
            for l in out.splitlines() if l.startswith("FAILED ")]


def _run():
    env = dict(os.environ)
    env["PYTHONPATH"] = _HERE + os.pathsep + os.path.join(_HERE, "smartbi")
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MY_TEST,
         "-p", "no:cacheprovider", "-q", "--tb=no"],
        cwd=_HERE, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    return p.stdout + p.stderr


def main() -> int:
    original = _read()
    ok = True
    try:
        base = _failed(_run())
        print("=" * 78)
        print("基线(无变异): 失败 %d 条 %s" % (len(base), base or ""))
        if base:
            print("⛔ rc=2 基线就不全绿，变异读数无意义")
            return 2

        for name, old, new, expect in _PARTIAL_GRAIN_MUTATIONS:
            src = original
            n = src.count(old)
            print("\n" + "=" * 78)
            print("【%s】" % name)
            print("  锚点出现 %d 次 %s" % (n, "" if n == 1 else "⛔ 必须恰好 1 次"))
            if n != 1:
                print("  ⛔ rc=2 锚点不唯一，这条变异没打进去 —— ⛔ 不许据此下结论")
                ok = False
                continue
            mutated = src.replace(old, new, 1)
            _write(mutated)
            try:
                compile(mutated, _SVC, "exec")
            except SyntaxError as exc:
                print("  ⛔ rc=2 变异把代码弄成语法错(%s) —— 这条不算数" % exc)
                _write(original)
                ok = False
                continue
            print("  ✅ 变异已落盘且语法正常")
            failed = _failed(_run())
            print("  实际变红 %d 条:" % len(failed))
            for f in failed:
                print("      " + f.split("::")[-1])
            hit = [e for e in expect if any(e in f for f in failed)]
            missed = [e for e in expect if e not in hit]
            extra = [f for f in failed if not any(e in f for e in expect)]
            print("  期望变红: %s" % [e.split("::")[-1] for e in expect])
            if missed:
                print("  🔴 该红没红: %s —— 那条断言可能在守空气" % missed)
                ok = False
            if extra:
                print("  ⚠️ 计划外变红: %s —— 归因不干净"
                      % [f.split("::")[-1] for f in extra])
                ok = False
            if not missed and not extra:
                print("  ✅ 红在它守的那一条上，⛔ 没有波及别的")
            _write(original)

        print("\n" + "=" * 78)
        print("恢复原文件: %s" % ("一致" if _read() == original else "⛔ 不一致!"))
        return 0 if ok else 1
    finally:
        if _read() != original:
            _write(original)
            print("⚠️ finally 兜底恢复了原文件")


if __name__ == "__main__":
    raise SystemExit(main())
