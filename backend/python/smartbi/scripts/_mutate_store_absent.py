# -*- coding: utf-8 -*-
"""变异对照 —— 「点名一家不存在的门店」这一刀的每条断言，各自能不能红。

## 纪律（每条都踩过）

- **硬约束 5**：文本模式 + `newline=''`。⛔ 不用 `bytes` 字面量（中文当场
  SyntaxError），⛔ 不用 `pathlib.write_text`（Windows 把整文件转 CRLF，
  两个 PR 因此被打回）。
- **形态 C″**：变异不红时，先证明**变异真的生效了**再下「断言没用」的结论。
  这里每条变异都先断言「替换成功」（`count == 1`），再看测试红不红。
  ⇒ 替换成功而不红 ⇒ 那条断言在守空气 / 或变异打在了断言看不见的地方。
- **硬约束 8**：改动碰了**两个**上游块，所以 M1/M2 分开打 ——
  只打一个而两条都红，说明另一处没有独立的断言守着（半吊子会全绿）。
- **形态 C⁷**：写文件的命令不与别的命令同格；这里全程先算好字符串再落盘。
"""
from __future__ import annotations

import os
import subprocess
import sys

_MU_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
_MU_SRC = os.path.join(
    _MU_ROOT, "smartbi", "gold", "restaurant", "restaurant_intent.py")
_MU_TEST = "smartbi/gold/tests/test_named_store_absent_is_told_not_substituted.py"

#: (标签, 原串, 变异串, 期望红的那条断言的关键词)
#: ⚠️ 期望红的那一条要写具体 —— 「有测试红了」不算数，
#:    形态 C 的教训是变异可能打红了**相邻**的另一条断言。
_MU_CASES = [
    (
        "M1 上游块①不再让路（模型给了 store 维度那一格）",
        """        and not store_names
        # 🔴 2026-08-19: 他**点名了**一家门店时不在这里定死 —— 让 guard 去核对
        #    名单（唯一权威）。`not store_names` 分不开「没说」和「说了一家
        #    我们没有的」，prod 6/6 因此拿到全店合计且零说明。
        and not named_store_mentioned""",
        "        and not store_names",
        "test_upstream_stops_preempting_when_an_unknown_store_was_named",
    ),
    (
        "M2 上游块②不再让路（缺项都有安全默认值那一格）",
        """        and not store_names
        # 🔴 2026-08-19: 与上面那个块**同一条判据的第二份载体**（硬约束 8:
        #    `not store_names` 当「他没说门店」用的共 4 处，改这 2 处，
        #    另 2 处 L6662/L6994 是「归一」「简称歧义」的触发条件，
        #    与本判据无关 —— **登记为故意不改**）。
        and not named_store_mentioned""",
        "        and not store_names",
        "test_upstream_stops_preempting_when_an_unknown_store_was_named",
    ),
    (
        "M3 反问不再以常量结尾（延续轮认不出来 ⇒ 按钮变死的）",
        '        f"{STORE_SCOPE_CLARIFICATION_QUESTION}"',
        '        ""',
        "test_it_ends_with_the_constant_so_the_slot_still_maps_back",
    ),
    (
        "M4 guard 退回通用反问（不说是哪家店没有）",
        """            clarification_question=store_not_found_clarification(
                spec.store_slot, names),""",
        "            clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,",
        "test_guard_tells_which_store_is_missing",
    ),
    (
        "M5 边界判据从「结尾」滑成「包含」",
        '        or question.endswith("\\n\\n" + STORE_SCOPE_CLARIFICATION_QUESTION)',
        "        or STORE_SCOPE_CLARIFICATION_QUESTION in question",
        "test_the_constant_in_the_middle_is_not_a_store_question",
    ),
    (
        "M6 fail-open 拆掉（名单读不到也敢说「名单里没有这家店」）",
        "    if not known or not named:\n        return STORE_SCOPE_CLARIFICATION_QUESTION",
        "    if not named:\n        return STORE_SCOPE_CLARIFICATION_QUESTION",
        "test_fail_open_when_the_store_list_is_unavailable",
    ),
    (
        "M8 门店核对退回到 store+time 合成反问之后（顺序那一刀）",
        "    if spec.store_slot and spec.store_slot not in names:",
        "    if False and spec.store_slot and spec.store_slot not in names:",
        "test_the_missing_store_is_told_before_asking_for_a_time_range",
    ),
    (
        "M7 判据不再要求名字出自老板原话（反幻觉那一层拆掉）",
        "        name = _verbatim_entity(value, query)\n        if name:\n            return name",
        "        if value:\n            return str(value)",
        "test_a_hallucinated_name_not_in_the_question_does_not_count",
    ),
]


def _run_tests():
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MU_TEST, "-q", "--no-header",
         "-p", "no:cacheprovider", "--tb=no", "-rf"],
        cwd=_MU_ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace",
    )
    failed = [l for l in (p.stdout or "").split("\n") if l.startswith("FAILED")]
    return p.returncode, failed


def main() -> int:
    original = open(_MU_SRC, "r", encoding="utf-8", newline="").read()

    rc0, failed0 = _run_tests()
    print("基线（未变异）: rc=%d  失败 %d 条" % (rc0, len(failed0)))
    for l in failed0:
        print("   " + l)
    if rc0 != 0:
        print("⛔ rc=2 基线就不是绿的 —— 变异读数没有意义")
        return 2

    results = []
    for tag, old, new, expect in _MU_CASES:
        n = original.count(old)
        if n != 1:
            print("\n" + "=" * 76)
            print("【%s】⛔ rc=2 原串在源码里出现 %d 次（应为 1）—— 变异打不准" % (tag, n))
            results.append((tag, None, None, expect, "原串不唯一"))
            continue
        mutated = original.replace(old, new, 1)
        open(_MU_SRC, "w", encoding="utf-8", newline="").write(mutated)
        try:
            # 形态 C″：先证明变异真的落进了文件（⛔ 不是「我以为替换了」）
            back = open(_MU_SRC, "r", encoding="utf-8", newline="").read()
            landed = (back.count(old) == 0)
            rc, failed = _run_tests()
        finally:
            open(_MU_SRC, "w", encoding="utf-8", newline="").write(original)
        hit = any(expect in l for l in failed)
        print("\n" + "=" * 76)
        print("【%s】" % tag)
        print("  变异落进文件 = %s" % landed)
        print("  rc=%d  红了 %d 条" % (rc, len(failed)))
        for l in failed:
            print("     " + l)
        print("  期望红的那条（%s）红了 = %s" % (expect, hit))
        results.append((tag, landed, len(failed), expect, hit))

    # 收尾对照：文件必须逐字还原
    assert open(_MU_SRC, "r", encoding="utf-8", newline="").read() == original
    rc1, failed1 = _run_tests()
    print("\n" + "=" * 76)
    print("还原后重跑: rc=%d  失败 %d 条（必须与基线相同）" % (rc1, len(failed1)))
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
    print("\n⚠️ 留痕: M7 换过一次。")
    print("   第一版 M7 打的是「判据退回 extract_store_mention」那一路，**不红** ——")
    print("   而那不是「断言在守空气」，是那一路**本身就该删掉**:")
    print("   全量回归当场打红 `test_模型没说缺门店时同样把按门店拆读成全部门店`，")
    print("   因为 extract_store_mention('拖后腿的那家店是哪家') == '拖后腿的那家店'。")
    print("   ⇒ 删掉那一路之后，M7 改打反幻觉那一层，现在正常红。")
    if bad:
        print("\n⛔ 有 %d 条变异没打红它该打红的断言 —— 逐条查:" % bad)
        print("   ① 断言在守空气（B′）  ② 变异没生效（C″）  ③ 变异打在看不见的地方")
        return 1
    print("\n✅ 每条变异都打红了它自己那条断言")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
