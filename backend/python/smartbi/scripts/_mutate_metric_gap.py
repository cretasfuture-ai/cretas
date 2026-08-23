# -*- coding: utf-8 -*-
"""变异对照 —— 「要的指标没给数就说一句」这一刀。

纪律照 `_mutate_store_absent`（硬约束 5 文本模式 + `newline=''`；
形态 C″ 先证明变异落进文件；期望红的那条写具体）。
"""
from __future__ import annotations

import os
import subprocess
import sys

_MG_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
_MG_SVC = os.path.join(
    _MG_ROOT, "smartbi", "gold", "restaurant", "restaurant_intent_service.py")
_MG_TEST = "smartbi/gold/tests/test_metric_gap_is_said_not_swallowed.py"

_MG_CASES = [
    (
        "M1 判据退回「提到了这个词」（契约闸那个子串陷阱）",
        """        in_header = any(tok in ln for ln in header_lines for tok in tokens)
        near_number = any(
            re.search(r"%s[^\\n]{0,24}?(¥|\\d[\\d,]*\\.?\\d*\\s*%%|\\d{3,})"
                      % re.escape(tok), answer_text)
            for tok in tokens
        )
        if in_header or near_number:""",
        """        if any(tok in answer_text for tok in tokens):""",
        "test_it_says_the_metric_is_not_available",
    ),
    (
        "M2 表头那一半拆掉（有毛利表也要多嘴）",
        "        in_header = any(tok in ln for ln in header_lines for tok in tokens)",
        "        in_header = False",
        "test_margin_in_the_table_header_says_nothing",
    ),
    (
        "M3 行内数字那一半拆掉（不用表格给的毛利被漏判）",
        """        near_number = any(
            re.search(r"%s[^\\n]{0,24}?(¥|\\d[\\d,]*\\.?\\d*\\s*%%|\\d{3,})"
                      % re.escape(tok), answer_text)
            for tok in tokens
        )""",
        "        near_number = False",
        "test_margin_given_inline_says_nothing",
    ),
    (
        "M4 标签硬编码（不再用他自己的说法）",
        '        missing_labels.append(\n            next((t for t in tokens if t in query), tokens[0]))',
        '        missing_labels.append("毛利")',
        "test_the_label_comes_from_his_own_wording",
    ),
    (
        "M5 投影拆掉（算出来了不说 —— 形态 B 本身）",
        """        _metric_note = _metric_gap_note(spec, answer_text)
        if _metric_note and answer_text:
            answer_text = _metric_note + answer_text""",
        "        pass",
        "test_the_answer_path_projects_it",
    ),
    (
        "M6 判不了的指标也硬说（没有 token 也报）",
        "        if not tokens:\n            continue                      # 判不了 ⇒ 不动",
        "        if not tokens:\n            tokens = (\"毛利\",)",
        "test_a_metric_without_tokens_is_left_alone",
    ),
]

#: ⚠️ 留痕：M6 第一版**一条都不红**，而那**不是**「断言在守空气」——
#:   是**变异没改变行为**（形态 C″ 第 2 类）：原来的阴性 4 用的问句推导出的
#:   `requested_metrics` 全都有 token，「没有 token」那一支根本没被执行。
#:   ⇒ 补了 `test_a_metric_without_tokens_is_left_alone`（用 `actual_receive`
#:     —— 它在模型可选指标里，却不在 `_REQUEST_TEXT_TOKENS` 的 15 项里）。


def _run():
    p = subprocess.run(
        [sys.executable, "-m", "pytest", _MG_TEST, "-q", "--no-header",
         "-p", "no:cacheprovider", "--tb=no", "-rf"],
        cwd=_MG_ROOT, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    return p.returncode, [l for l in (p.stdout or "").split("\n")
                          if l.startswith("FAILED")]


def main() -> int:
    original = open(_MG_SVC, "r", encoding="utf-8", newline="").read()
    rc0, failed0 = _run()
    print("基线: rc=%d 失败 %d 条" % (rc0, len(failed0)))
    for l in failed0:
        print("   " + l)
    if rc0 != 0:
        print("⛔ rc=2 基线不绿")
        return 2

    results = []
    for tag, old, new, expect in _MG_CASES:
        n = original.count(old)
        if n != 1:
            print("\n【%s】⛔ rc=2 原串出现 %d 次（应为 1）" % (tag, n))
            results.append((tag, None, None, expect, "原串不唯一"))
            continue
        open(_MG_SVC, "w", encoding="utf-8", newline="").write(
            original.replace(old, new, 1))
        try:
            landed = open(_MG_SVC, "r", encoding="utf-8",
                          newline="").read().count(old) == 0
            rc, failed = _run()
        finally:
            open(_MG_SVC, "w", encoding="utf-8", newline="").write(original)
        hit = any(expect in l for l in failed)
        print("\n" + "=" * 76)
        print("【%s】\n  落进文件=%s rc=%d 红了 %d 条" % (tag, landed, rc, len(failed)))
        for l in failed:
            print("     " + l)
        print("  期望红的那条（%s）红了 = %s" % (expect, hit))
        results.append((tag, landed, len(failed), expect, hit))

    assert open(_MG_SVC, "r", encoding="utf-8", newline="").read() == original
    rc1, failed1 = _run()
    print("\n还原后: rc=%d 失败 %d 条" % (rc1, len(failed1)))
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
