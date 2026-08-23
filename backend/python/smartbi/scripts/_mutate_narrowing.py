# -*- coding: utf-8 -*-
"""变异对照 —— 「⛔ 别建议他做已经做过的事」这一刀。

纪律照 `_mutate_store_absent`（硬约束 5 文本模式 + `newline=''`；
形态 C″ 先证明变异落进文件；期望红的那条写具体）。
"""
from __future__ import annotations

import os
import subprocess
import sys

_MN_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
_MN_SVC = os.path.join(
    _MN_ROOT, "smartbi", "gold", "restaurant", "restaurant_intent_service.py")
_MN_TEST = "smartbi/gold/tests/test_narrowing_suggestion_is_not_what_he_already_did.py"

_MN_CASES = [
    (
        "M1 退回硬编码那句（对已限定的老板就是废话）",
        "                    + _narrowing\n                )",
        '                    + "你可以把问题问得更小一点（限定到某家店、某道菜'
        '或某个时间段），那条路我能给你确定的数。"\n                )',
        "test_the_fallback_actually_uses_it",
    ),
    (
        "M2 门店那一格不再看他点没点名",
        '    if not (getattr(spec, "store_slots", ()) or ()):\n        open_slots.append("某家店")',
        '    open_slots.append("某家店")',
        "test_it_does_not_suggest_a_store_he_already_named",
    ),
    (
        "M3 时间那一格不再看他给没给",
        '    if not (has_window and not getattr(spec, "time_range_defaulted", False)):\n        open_slots.append("某个时间段")',
        '    open_slots.append("某个时间段")',
        "test_it_does_not_suggest_a_time_he_already_gave",
    ),
    (
        "M4 三样都限定了还硬要说一句",
        '    if not open_slots:\n        return ""',
        '    if not open_slots:\n        open_slots = ["某家店"]',
        "test_everything_pinned_says_nothing_at_all",
    ),
    (
        "M5 把「补默认的窗口」也当成他限定的",
        '    if not (has_window and not getattr(spec, "time_range_defaulted", False)):',
        "    if not has_window:",
        "test_a_defaulted_window_is_not_something_he_gave",
    ),
    (
        "M6 一律不给建议（把有用的也砍掉）",
        "    if not open_slots:\n        return \"\"\n    return (",
        "    return \"\"\n    return (",
        "test_nothing_pinned_suggests_all_three",
    ),
]


def _run():
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MN_TEST, "-q", "--no-header",
         "-p", "no:cacheprovider", "--tb=no", "-rf"],
        cwd=_MN_ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    return p.returncode, [l for l in (p.stdout or "").split("\n")
                          if l.startswith("FAILED")]


def main() -> int:
    original = open(_MN_SVC, "r", encoding="utf-8", newline="").read()
    rc0, failed0 = _run()
    print("基线: rc=%d 失败 %d 条" % (rc0, len(failed0)))
    for l in failed0:
        print("   " + l)
    if rc0 != 0:
        print("⛔ rc=2 基线不绿 —— 变异读数没有意义")
        return 2

    results = []
    for tag, old, new, expect in _MN_CASES:
        n = original.count(old)
        if n != 1:
            print("\n【%s】⛔ rc=2 原串出现 %d 次（应为 1）" % (tag, n))
            results.append((tag, None, None, expect, "原串不唯一"))
            continue
        open(_MN_SVC, "w", encoding="utf-8", newline="").write(
            original.replace(old, new, 1))
        try:
            landed = open(_MN_SVC, "r", encoding="utf-8",
                          newline="").read().count(old) == 0
            rc, failed = _run()
        finally:
            open(_MN_SVC, "w", encoding="utf-8", newline="").write(original)
        hit = any(expect in l for l in failed)
        print("\n" + "=" * 76)
        print("【%s】\n  落进文件=%s  rc=%d  红了 %d 条" % (tag, landed, rc, len(failed)))
        for l in failed:
            print("     " + l)
        print("  期望红的那条（%s）红了 = %s" % (expect, hit))
        results.append((tag, landed, len(failed), expect, hit))

    assert open(_MN_SVC, "r", encoding="utf-8", newline="").read() == original
    rc1, failed1 = _run()
    print("\n还原后: rc=%d 失败 %d 条（必须与基线相同）" % (rc1, len(failed1)))
    if rc1 != 0:
        print("⛔ rc=2 还原后不绿")
        return 2

    print("\n🔑 汇总（⛔ 逐行）")
    print("%-44s %-8s %-6s %s" % ("变异", "落进文件", "红几条", "期望那条红了"))
    bad = 0
    for tag, landed, nfail, expect, hit in results:
        print("%-44s %-8s %-6s %s" % (tag[:42], landed, nfail, hit))
        if hit is not True:
            bad += 1
    if bad:
        print("\n⛔ 有 %d 条没打红 —— ① 断言守空气 ② 变异没生效 ③ 打在看不见的地方" % bad)
        return 1
    print("\n✅ 每条变异都打红了它自己那条断言")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
