# -*- coding: utf-8 -*-
"""变异对照 —— 「菜名比菜单里的长」这一刀的每条断言，各自能不能红。

形状与纪律照 `_mutate_store_absent`（硬约束 5 文本模式 + `newline=''`；
形态 C″ 先证明变异落进文件；期望红的那条要写具体）。
"""
from __future__ import annotations

import os
import subprocess
import sys

_MD_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
_MD_ROUTER = os.path.join(
    _MD_ROOT, "smartbi", "gold", "restaurant", "restaurant_ops_router.py")
_MD_INTENT = os.path.join(
    _MD_ROOT, "smartbi", "gold", "restaurant", "restaurant_intent.py")
_MD_TEST = "smartbi/gold/tests/test_a_longer_dish_name_is_not_that_dish.py"

#: (标签, 文件, 原串, 变异串, 期望红的那条)
_MD_CASES = [
    (
        "M1 措辞侧不再认「比菜单里的长」",
        _MD_ROUTER,
        """    if (_catalogue_says_not_a_dish(candidate)
            or _catalogue_says_a_longer_name(candidate)):""",
        "    if _catalogue_says_not_a_dish(candidate):",
        "test_it_no_longer_claims_the_dish_is_on_the_menu",
    ),
    (
        "M2 判定侧不再认（够不着 => 走执行链 => 被接地闸拦）",
        _MD_INTENT,
        """    if not (_catalogue_says_not_a_dish(slot)
            or _catalogue_says_a_longer_name(slot)):""",
        "    if not _catalogue_says_not_a_dish(slot):",
        "test_the_clarification_path_now_reaches_the_longer_name",
    ),
    (
        "M3 口语后缀不再放行（「凉拌木耳这道菜」被拦成查无此菜）",
        _MD_ROUTER,
        "            return not _generic_tail_consumable(candidate[len(name):])",
        "            return True",
        "test_a_real_dish_with_a_spoken_tail_is_let_through",
    ),
    (
        "M4 判据写宽了（把「完全不像」的也一起收了 => 与现有判据重复）",
        _MD_ROUTER,
        """    for name in names:
        if len(name) >= 2 and candidate.startswith(name):
            # 「凉拌木耳这道菜」尾巴 =「这道」+「菜」，都在已登记表里 ⇒ 口语，放行。
            # 「水煮牛肉丝」  尾巴 =「丝」，不在 ⇒ 那是另一道菜。
            return not _generic_tail_consumable(candidate[len(name):])
    # 菜名出现在中间/末尾而不是开头 ⇒ 保守放行，⛔ 不下断言。
    return False""",
        """    for name in names:
        if len(name) >= 2 and candidate.startswith(name):
            return not _generic_tail_consumable(candidate[len(name):])
    return True""",
        "test_the_two_criteria_are_complementary",
    ),
    (
        "M5 fail-open 拆掉（目录没加载也敢说「这是另一道菜」）",
        _MD_ROUTER,
        """    names = _DISH_CATALOGUE.get()
    if not names or not candidate:
        return False
    if candidate in names:
        return False
    for name in names:
        if candidate in name:""",
        """    names = _DISH_CATALOGUE.get() or ("水煮牛肉",)
    if not candidate:
        return False
    if candidate in names:
        return False
    for name in names:
        if candidate in name:""",
        "test_fail_open_when_the_menu_is_unavailable",
    ),
    (
        "M6 前缀判据放宽成子串（「这道菜水煮牛肉」这类会被误判）",
        _MD_ROUTER,
        "        if len(name) >= 2 and candidate.startswith(name):",
        "        if len(name) >= 2 and name in candidate:",
        "test_a_menu_name_in_the_middle_is_conservatively_let_through",
    ),
]

#: ⚠️ 撤掉的一条变异，**留痕**（形态 C″ 第 2 类）:
#:   「M6 旧版：`candidate in name` 改成 `candidate == name`」——
#:   变异落进文件了，但**一条断言都不红**。查清是**变异没改变行为**:
#:   半个菜名不满足 `startswith`，会落到函数末尾同样 `return False`。
#:   ⛔ 不是「断言在守空气」。那一支已在源码里**登记为不承重**。
#:   ▎变异不红时先证明行为真的变了，再下「断言没用」的结论。


def _run():
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MD_TEST, "-q", "--no-header",
         "-p", "no:cacheprovider", "--tb=no", "-rf"],
        cwd=_MD_ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace",
    )
    return p.returncode, [l for l in (p.stdout or "").split("\n")
                          if l.startswith("FAILED")]


def main() -> int:
    originals = {f: open(f, "r", encoding="utf-8", newline="").read()
                 for f in {c[1] for c in _MD_CASES}}

    rc0, failed0 = _run()
    print("基线（未变异）: rc=%d  失败 %d 条" % (rc0, len(failed0)))
    for l in failed0:
        print("   " + l)
    if rc0 != 0:
        print("⛔ rc=2 基线就不是绿的 —— 变异读数没有意义")
        return 2

    results = []
    for tag, path, old, new, expect in _MD_CASES:
        src = originals[path]
        n = src.count(old)
        if n != 1:
            print("\n" + "=" * 76)
            print("【%s】⛔ rc=2 原串出现 %d 次（应为 1）—— 变异打不准" % (tag, n))
            results.append((tag, None, None, expect, "原串不唯一"))
            continue
        open(path, "w", encoding="utf-8", newline="").write(src.replace(old, new, 1))
        try:
            landed = open(path, "r", encoding="utf-8", newline="").read().count(old) == 0
            rc, failed = _run()
        finally:
            open(path, "w", encoding="utf-8", newline="").write(src)
        hit = any(expect in l for l in failed)
        print("\n" + "=" * 76)
        print("【%s】" % tag)
        print("  变异落进文件 = %s   rc=%d   红了 %d 条" % (landed, rc, len(failed)))
        for l in failed:
            print("     " + l)
        print("  期望红的那条（%s）红了 = %s" % (expect, hit))
        results.append((tag, landed, len(failed), expect, hit))

    for f, s in originals.items():
        assert open(f, "r", encoding="utf-8", newline="").read() == s
    rc1, failed1 = _run()
    print("\n还原后重跑: rc=%d 失败 %d 条（必须与基线相同）" % (rc1, len(failed1)))
    if rc1 != 0:
        print("⛔ rc=2 还原后不绿 —— 变异脚本自己弄脏了源码")
        return 2

    print("\n🔑 汇总（⛔ 逐行）")
    print("%-46s %-8s %-6s %s" % ("变异", "落进文件", "红几条", "期望那条红了"))
    bad = 0
    for tag, landed, nfail, expect, hit in results:
        print("%-46s %-8s %-6s %s" % (tag[:44], landed, nfail, hit))
        if hit is not True:
            bad += 1
    if bad:
        print("\n⛔ 有 %d 条变异没打红它该打红的断言 —— 逐条查:" % bad)
        print("   ① 断言在守空气  ② 变异没生效  ③ 变异打在看不见的地方")
        return 1
    print("\n✅ 每条变异都打红了它自己那条断言")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
