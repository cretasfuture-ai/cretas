# -*- coding: utf-8 -*-
"""四条变异对照的驱动 —— 证明每条断言**能红**，且**红在它守的那一条**上。

⛔ 这不是「跑一遍测试」。判据是三层：
  ① 变异**真的生效了**（形态 C″：变异不红时，先证明变异到达了被测代码，
     再下「断言没用」的结论 —— 两者症状都是「不红」）
  ② 该红的红了
  ③ **不该红的没红**（否则说明变异打在了公共路径上，读数不可归因）

⚠️ 红在 NameError / SyntaxError 上的变异**不算数** —— 那是把代码弄坏，
   不是移除被守的行为。所以每条变异后先跑一次 `compile()`。

硬约束 5：文本模式 + `newline=''` —— 不做行尾转换（Windows 上 CRLF 污染
曾让两个 PR 被打回），且没有 bytes 字面量的 ASCII 限制。
"""
from __future__ import annotations

import compileall  # noqa: F401  (占位, 见 _check_syntax)
import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_TARGET_FILE = os.path.join(_HERE, "smartbi", "gold", "restaurant", "restaurant_intent.py")

_MY_TESTS = "smartbi/gold/tests/test_llm_store_grain_survives_all_scope.py"
NAMED_DISH = ("tests/test_restaurant_intent.py::"
              "test_named_dish_low_sales_diagnosis_repairs_broad_optimization_to_dish_resolver")

#: (名字, 旧串, 新串, 期望变红的测试名片段)
_STORE_GRAIN_MUTATIONS = [
    ("M1 删掉整个新条件",
     "\n        and not llm_asked_store_grain", "",
     ["test_llm_asked_store_grain_survives_all_store_scope",
      "test_other_store_count_phrasings_also_survive"]),
    ("M2 无条件豁免(去掉「LLM 给过」这一半)",
     'llm_dimensions is not None\n        and "store" in tuple(llm_dimensions)', "True",
     ["test_derived_store_grain_is_still_stripped"]),
    ("M3 拆掉安全阀(去掉能力表核对)",
     'and _resolver_serves_dimensions(code, ("store",))', "and True",
     ["test_llm_store_grain_is_stripped_when_resolver_cannot_serve_it"]),
    ("M4 去掉「用户原话是不是 all」",
     'and explicit_store_scope != "all"', "and True",
     ["test_user_saying_all_stores_still_means_scope_not_grain",
      "test_named_dish_low_sales_diagnosis"]),
]


def _read():
    return open(_TARGET_FILE, "r", encoding="utf-8", newline="").read()


def _write(s):
    open(_TARGET_FILE, "w", encoding="utf-8", newline="").write(s)


def _failed_names(out: str):
    names = []
    for line in out.splitlines():
        if line.startswith("FAILED "):
            names.append(line.split(" ", 1)[1].split(" - ")[0])
    return names


def _run_tests():
    env = dict(os.environ)
    env["PYTHONPATH"] = _HERE + os.pathsep + os.path.join(_HERE, "smartbi")
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MY_TESTS, NAMED_DISH,
         "-p", "no:cacheprovider", "-q", "--tb=no"],
        cwd=_HERE, env=env, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    return p.stdout + p.stderr


def main() -> int:
    original = _read()
    all_ok = True
    try:
        # 基线：没有变异时必须全绿 —— 否则后面每一条读数都不可归因
        base_out = _run_tests()
        base_failed = _failed_names(base_out)
        print("=" * 78)
        print("基线(无变异): 失败 %d 条 %s" % (len(base_failed), base_failed or ""))
        if base_failed:
            print("⛔ rc=2 基线就不是全绿, 变异读数无意义")
            return 2

        for name, old, new, expect_red in _STORE_GRAIN_MUTATIONS:
            src = original
            n = src.count(old)
            print("\n" + "=" * 78)
            print("【%s】" % name)
            print("  锚点出现 %d 次 %s" % (n, "" if n == 1 else "⛔ 必须恰好 1 次"))
            if n != 1:
                print("  ⛔ rc=2 锚点不唯一, 这条变异没打进去 —— ⛔ 不许据此下结论")
                all_ok = False
                continue
            mutated = src.replace(old, new, 1)
            # ① 变异真的生效了吗（文本层）
            assert mutated != src
            _write(mutated)
            # ② 语法还是好的吗 —— 红在 SyntaxError 上的变异不算数
            try:
                compile(mutated, _TARGET_FILE, "exec")
            except SyntaxError as exc:
                print("  ⛔ rc=2 变异把代码弄成语法错了(%s) —— 这条不算数" % exc)
                _write(original)
                all_ok = False
                continue
            print("  ✅ 变异已落盘且语法正常")

            out = _run_tests()
            failed = _failed_names(out)
            print("  实际变红 %d 条:" % len(failed))
            for f in failed:
                print("      " + f)

            hit = [e for e in expect_red if any(e in f for f in failed)]
            missed = [e for e in expect_red if e not in hit]
            # ③ 不该红的红了吗
            extra = [f for f in failed if not any(e in f for e in expect_red)]
            print("  期望变红: %s" % expect_red)
            print("  ✅ 命中: %s" % (hit or "无"))
            if missed:
                print("  🔴 该红没红: %s —— 那条断言可能在守空气" % missed)
                all_ok = False
            if extra:
                print("  ⚠️ 计划外变红: %s —— 变异打在了公共路径上, 归因不干净" % extra)
            if not missed and not extra:
                print("  ✅ 红在它守的那一条上, ⛔ 没有波及别的")
            _write(original)

        print("\n" + "=" * 78)
        print("恢复原文件: %s" % ("一致" if _read() == original else "⛔ 不一致!"))
        return 0 if all_ok else 1
    finally:
        if _read() != original:
            _write(original)
            print("⚠️ finally 兜底恢复了原文件")


if __name__ == "__main__":
    raise SystemExit(main())
