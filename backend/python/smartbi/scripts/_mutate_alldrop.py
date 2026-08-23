# -*- coding: utf-8 -*-
"""变异对照 —— 「全部维度被剥掉也要说一句」这一刀。

纪律照 `_mutate_store_absent`：硬约束 5 文本模式 + `newline=''`；
形态 C″ 先证明变异落进文件；期望红的那条写具体。
"""
from __future__ import annotations

import os
import subprocess
import sys

_MA_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
_MA_SVC = os.path.join(
    _MA_ROOT, "smartbi", "gold", "restaurant", "restaurant_intent_service.py")
_MA_TEST = "smartbi/gold/tests/test_dropping_every_grain_still_says_so.py"

_MA_CASES = [
    (
        "M1 退回原样：能算的一层都不剩就闭嘴",
        """        advice = _dimension_gap_advice(
            _replace(spec, dimensions=tuple(dropped)), plan)
        return f"{advice}\\n\\n" if advice else \"\"""",
        "        return \"\"",
        "test_all_grains_dropped_still_tells_the_boss",
    ),
    (
        "M2 另写一句（形态 D：第二份措辞）",
        """        advice = _dimension_gap_advice(
            _replace(spec, dimensions=tuple(dropped)), plan)
        return f"{advice}\\n\\n" if advice else \"\"""",
        '        return f"按{_dimension_names(dropped)}拆不出来。\\n\\n"',
        "test_it_reuses_the_refusal_wording_not_a_new_one",
    ),
    (
        "M3 把「被剥掉的加回去」改成「用剥完的」（输入还是空的）",
        "            _replace(spec, dimensions=tuple(dropped)), plan)",
        "            spec, plan)",
        "test_all_grains_dropped_still_tells_the_boss",
    ),
    (
        "M4 部分能做那一支也走 advice（两种情况被混成一句）",
        """    both, _extra = _dimension_gap(spec, plan)
    if not both:""",
        """    both, _extra = _dimension_gap(spec, plan)
    if True:""",
        "test_partial_coverage_wording_is_unchanged",
    ),
    (
        "M5 没剥也说（无中生有）",
        "    if not dropped:\n        return \"\"",
        "    if False:\n        return \"\"",
        "test_nothing_dropped_says_nothing",
    ),
]


def _run():
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MA_TEST, "-q", "--no-header",
         "-p", "no:cacheprovider", "--tb=no", "-rf"],
        cwd=_MA_ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    return p.returncode, [l for l in (p.stdout or "").split("\n")
                          if l.startswith("FAILED")]


def main() -> int:
    original = open(_MA_SVC, "r", encoding="utf-8", newline="").read()
    rc0, failed0 = _run()
    print("基线: rc=%d 失败 %d 条" % (rc0, len(failed0)))
    for l in failed0:
        print("   " + l)
    if rc0 != 0:
        print("⛔ rc=2 基线就不绿 —— 变异读数没有意义")
        return 2

    results = []
    for tag, old, new, expect in _MA_CASES:
        n = original.count(old)
        if n != 1:
            print("\n【%s】⛔ rc=2 原串出现 %d 次（应为 1）" % (tag, n))
            results.append((tag, None, None, expect, "原串不唯一"))
            continue
        open(_MA_SVC, "w", encoding="utf-8", newline="").write(
            original.replace(old, new, 1))
        try:
            landed = open(_MA_SVC, "r", encoding="utf-8",
                          newline="").read().count(old) == 0
            rc, failed = _run()
        finally:
            open(_MA_SVC, "w", encoding="utf-8", newline="").write(original)
        hit = any(expect in l for l in failed)
        print("\n" + "=" * 76)
        print("【%s】\n  落进文件=%s  rc=%d  红了 %d 条" % (tag, landed, rc, len(failed)))
        for l in failed:
            print("     " + l)
        print("  期望红的那条（%s）红了 = %s" % (expect, hit))
        results.append((tag, landed, len(failed), expect, hit))

    assert open(_MA_SVC, "r", encoding="utf-8", newline="").read() == original
    rc1, failed1 = _run()
    print("\n还原后: rc=%d 失败 %d 条（必须与基线相同）" % (rc1, len(failed1)))
    if rc1 != 0:
        print("⛔ rc=2 还原后不绿")
        return 2

    print("\n🔑 汇总（⛔ 逐行）")
    print("%-46s %-8s %-6s %s" % ("变异", "落进文件", "红几条", "期望那条红了"))
    bad = 0
    for tag, landed, nfail, expect, hit in results:
        print("%-46s %-8s %-6s %s" % (tag[:44], landed, nfail, hit))
        if hit is not True:
            bad += 1
    if bad:
        print("\n⛔ 有 %d 条没打红 —— ① 断言守空气 ② 变异没生效 ③ 打在看不见的地方" % bad)
        return 1
    print("\n✅ 每条变异都打红了它自己那条断言")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
