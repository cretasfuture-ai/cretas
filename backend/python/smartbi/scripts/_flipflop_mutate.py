# -*- coding: utf-8 -*-
"""变异对照 —— 「没登记的维度名不作废整份计划」这道改动到底守住了什么。

## 三层，一条不能省（⛔ 缺任何一层这轮变异都不作数）

    ① 变异**真的生效**  锚点恰好命中 1 次 → `compile()` 通过 → **落盘复核**
                        → 再跑一个「行为可观测量」证明**行为**变了，
                          ⛔ 不是只证明「字符串替换成功了」
    ② 该红的红，且红在**它守的那一条**上（逐条点名，不看总数）
    ③ 不该红的**没红**

🔴 红在 `NameError` / `SyntaxError` 上的一律作废。

## ⛔ 为什么每条变异都跑**全部**断言

判据里有并列的两块（成员闸 / 形状闸）。本仓实测过「判据里有两把锁，
单独去掉任何一条都不红」——只看被点名那条会把互为备份的两把锁读成恒真式。

## 用法

    cd backend/python && python -m smartbi.scripts._flipflop_mutate
"""
from __future__ import annotations

import json
import os
import subprocess
import sys

MUTATE_TARGET = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "gold", "restaurant", "restaurant_intent.py",
)
MUTATE_TESTS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "gold", "tests", "test_semantic_contract_unknown_dimension.py",
)

MUTATE_MEMBERSHIP_ANCHOR = (
    '    list_contracts = (\n'
    '        ("requested_metrics", _SEMANTIC_METRICS),\n'
    '        ("missing_fields", _SEMANTIC_MISSING_FIELDS),\n'
    '    )\n'
)
MUTATE_SHAPE_ANCHOR = '    shape_only_list_contracts = ("dimensions",)\n'
MUTATE_SHAPE_CHECK_ANCHOR = (
    '        and all(\n'
    '            isinstance(parsed.get(field), list)\n'
    '            and all(isinstance(value, str) for value in parsed.get(field, ()))\n'
    '            for field in shape_only_list_contracts\n'
    '        )\n'
)

#: 每条变异: (名字, 破坏的行为, 锚点, 替换文本, 预期变红的用例名片段, 行为探针输入)
#: ⛔ 「预期变红」写的是**用例名**, 不是条数 —— 条数对不上时看不出是哪条错位。
#: 🔴 最后一格是**这条变异自己的**行为探针输入。共用一个输入时, 只有 M1 证得了
#:    「行为真的变了」, 另外三条会打出「行为没变」—— 那时 ① 那一层等于没做。
MUTATE_CASES = (
    (
        "M1 把 dimensions 挪回成员闸",
        "未登记的维度值不许作废整份计划",
        MUTATE_MEMBERSHIP_ANCHOR,
        '    list_contracts = (\n'
        '        ("requested_metrics", _SEMANTIC_METRICS),\n'
        '        ("dimensions", _SEMANTIC_DIMENSIONS),\n'
        '        ("missing_fields", _SEMANTIC_MISSING_FIELDS),\n'
        '    )\n',
        (
            "does_not_discard_the_whole_plan",
            "value_is_dropped_while_the_plan_stays_alive",
            "survives_alongside_an_unknown_one",
            "same_plan_as_an_empty_one",
        ),
        {},                       # 未登记维度值：基线 llm
    ),
    (
        "M2 形状闸整块失效",
        "放宽的是成员、⛔ 不是形状：形状坏了仍要 fail closed",
        MUTATE_SHAPE_CHECK_ANCHOR,
        '        and all(\n'
        '            True\n'
        '            for field in shape_only_list_contracts\n'
        '        )\n',
        ("broken_dimension_shape_still_fails_closed",),
        {"dimensions": "supplier"},             # 形状坏了: 基线 llm_contract_incomplete
    ),
    (
        "M3 把 requested_metrics 也放宽",
        "指标的未知值不许被静默丢掉",
        MUTATE_MEMBERSHIP_ANCHOR,
        '    list_contracts = (\n'
        '        ("missing_fields", _SEMANTIC_MISSING_FIELDS),\n'
        '    )\n',
        ("unknown_requested_metric_still_fails_closed",),
        {"requested_metrics": ["mystery_metric"]},
    ),
    (
        "M4 把 missing_fields 也放宽",
        "缺项的未知值不许被静默丢掉",
        MUTATE_MEMBERSHIP_ANCHOR,
        '    list_contracts = (\n'
        '        ("requested_metrics", _SEMANTIC_METRICS),\n'
        '    )\n',
        ("unknown_missing_field_still_fails_closed",),
        {"missing_fields": ["mystery_slot"]},
    ),
)

#: ① 的第二半：**行为**可观测量。⛔ 只证明「替换成功」不算数
#:    （本仓踩过：变异串换上了，控制流一模一样，11 条测试全绿被读成「守卫有效」）。
MUTATE_BEHAVIOUR_TEMPLATE = (
    "import sys, json; sys.path.insert(0, '.');"
    "from smartbi.gold.restaurant import restaurant_intent as ri;"
    "plan = {'intent': 'RESTAURANT_OPS_SUPPLIER_PRICE', 'time_range': None,"
    " 'wants_margin': False, 'asks_profitability': False, 'requested_metrics': [],"
    " 'analysis_action': 'lookup', 'aggregation': 'extremes',"
    " 'dimensions': ['supplier'], 'comparison': None, 'dish': None, 'store': None,"
    " 'stores': [], 'store_scope': None, 'confidence': 0.95,"
    " 'clarification_needed': False, 'missing_fields': [],"
    " 'clarification_question': None, 'clarification_options': []};"
    "plan.update(json.loads(sys.argv[1]));"
    "spec = ri._semantic_spec_from_t3(plan, '哪个供应商报价最贵');"
    "print('BEHAVIOUR_NOW=' + str(spec.planner_authority))"
)


def _read() -> str:
    # ⛔ 文本模式 + newline='' —— 不做行尾转换（与二进制 IO 同样不污染 CRLF），
    #    又没有 bytes 字面量「只能含 ASCII」那条限制。
    with open(MUTATE_TARGET, "r", encoding="utf-8", newline="") as handle:
        return handle.read()


def _write(text: str) -> None:
    with open(MUTATE_TARGET, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def _behaviour(overrides: dict) -> str:
    proc = subprocess.run(
        [sys.executable, "-c", MUTATE_BEHAVIOUR_TEMPLATE, json.dumps(overrides)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    for line in (proc.stdout or "").splitlines():
        if line.startswith("BEHAVIOUR_NOW="):
            return line.split("=", 1)[1]
    return "<probe failed: %s>" % (proc.stderr or "")[-200:]


def _run_tests() -> tuple:
    """跑**全部**断言，返回 `(失败的用例名, 原始输出)`。"""
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", MUTATE_TESTS, "-q", "--tb=line", "-rf",
         "-p", "no:cacheprovider", "-p", "no:warnings"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    failed = set()
    # ⛔ 只认 `FAILED ` 开头的短摘要行。第一版还认了「以 smartbi 开头且含 ::」的行 ——
    #    那把 **warnings summary** 里的定位行也数成了失败，基线当场「红」了一条。
    #    （形态 A⁗ 的又一例：闸把自己的输出也数了进去。）
    for line in out.splitlines():
        if line.startswith("FAILED "):
            for token in line.split():
                if "::" in token:
                    failed.add(token.split("::", 1)[1].split("[")[0])
    if "NameError" in out or "SyntaxError" in out:
        failed.add("<NameError/SyntaxError —— 这轮变异作废>")
    return failed, out


def main() -> int:
    original = _read()
    baseline_behaviour = _behaviour({})
    baseline_failed, _ = _run_tests()
    print("=" * 78)
    print("基线（未变异）")
    print("  行为可观测量 planner_authority = %s" % baseline_behaviour)
    print("  失败的用例 = %s" % (sorted(baseline_failed) or "无"))
    if baseline_failed:
        print("⛔ rc=2 基线就是红的，变异对照没有意义")
        return 2
    if baseline_behaviour != "llm":
        print("⛔ rc=2 基线行为不是 'llm' —— 修复没生效或探针连错了树")
        return 2

    # 🔴 每条变异先在**未变异**的树上量一次它自己那个输入的基线 ——
    #    ⛔ 拿 M1 的基线去判 M2 的变异，就是「用另一个读数当对照」。
    case_baselines = {}
    for name, _g, _a, _r, _e, probe_input in MUTATE_CASES:
        case_baselines[name] = _behaviour(probe_input)
        print("  变异 %-28s 自己那个输入的基线 = %s" % (name, case_baselines[name]))

    verdicts = []
    try:
        for (name, guarded, anchor, replacement, expect_red,
             probe_input) in MUTATE_CASES:
            print("\n" + "=" * 78)
            print("%s —— 守的是「%s」" % (name, guarded))
            hits = original.count(anchor)
            print("  ① 锚点命中 = %d 次（必须恰好 1）" % hits)
            if hits != 1:
                print("  ⛔ 锚点不唯一/不存在，这条变异作废")
                verdicts.append((name, "作废(锚点)", set()))
                continue
            mutated = original.replace(anchor, replacement, 1)
            try:
                compile(mutated, MUTATE_TARGET, "exec")
            except SyntaxError as exc:
                print("  ⛔ compile 失败: %s —— 作废" % exc)
                verdicts.append((name, "作废(语法)", set()))
                continue
            _write(mutated)
            on_disk = _read().count(replacement) == 1
            print("  ① 落盘复核 = %s" % ("✓" if on_disk else "✗ 作废"))
            before = case_baselines[name]
            behaviour = _behaviour(probe_input)
            changed = behaviour != before
            print("  ① 行为可观测量（输入 %s）%s → %s  %s"
                  % (json.dumps(probe_input, ensure_ascii=False),
                     before, behaviour,
                     "✓ 行为真的变了" if changed
                     else "🔴 行为没变 —— 这条变异【空转】，②③ 的读数不作数"))
            failed, out = _run_tests()
            print("  ② 实测变红的用例（⛔ 逐条列，不看总数）:")
            for item in sorted(failed) or ["（无）"]:
                print("       %s" % item)
            hit_expected = {f for f in failed
                            if any(frag in f for frag in expect_red)}
            missed = [frag for frag in expect_red
                      if not any(frag in f for f in failed)]
            extra = sorted(failed - hit_expected)
            ok = (not missed) and (not extra) and changed
            print("  ② 点名命中 %d/%d %s" % (len(hit_expected), len(expect_red),
                                             "" if not missed else "缺: %s" % missed))
            print("  ③ 不该红的没红 = %s" % ("✓" if not extra else "✗ 多红了 %s" % extra))
            verdicts.append((name, "✓" if ok else "✗", failed))
            if "SyntaxError" in out or "NameError" in out:
                print("  ⛔ 红在 NameError/SyntaxError 上 —— 作废")
                verdicts[-1] = (name, "作废(报错)", failed)
            _write(original)
    finally:
        _write(original)
        print("\n已还原源码。落盘复核 = %s"
              % ("✓ 与变异前逐字相同" if _read() == original else "🔴 没还原干净！"))

    print("\n" + "=" * 78)
    print("变异对照汇总（口径：%d 条变异 × 全部 %d 条断言）"
          % (len(MUTATE_CASES), 12))
    for name, verdict, failed in verdicts:
        print("  %-28s %-10s 变红 %d 条" % (name, verdict, len(failed)))
    bad = [v for _n, v, _f in verdicts if v != "✓"]
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
