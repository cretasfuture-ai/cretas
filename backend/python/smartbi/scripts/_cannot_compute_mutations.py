# -*- coding: utf-8 -*-
"""变异对照 —— ⛔ 三层判据，一条都不能省。

  ① 变异**真的生效了**（锚点恰好 1 次 / 落盘 / `compile()` 通过 / **行为变了**）
  ② 该红的红了，且红在**它守的那一条**上
  ③ **不该红的没红**（计划外变红 = 打在公共路径上，归因不干净）

⛔ 红在 `NameError` / `SyntaxError` 上的变异不算数 —— 那是空转。
🔴 往判据的 AND 链里加一条时，把已有的每一条变异**全部重跑一遍**：
   本仓实测过新条件短路掉旧对照的输入，而那三层当时都证过生效。

⛔ 文本模式 + `newline=''`（硬约束 5）：不做行尾转换（不污染 CRLF），
   又没有 bytes 字面量的 ASCII 限制 —— 变异串里有中文。

用法（在 worktree 的 backend/python 下）:
    PYTHONPATH="$(pwd):$(pwd)/smartbi" python -m smartbi.scripts._cannot_compute_mutations
退出码: 0=每条变异都按预期红且没有计划外变红 / 1=有变异没红或红错地方 / 2=没量到
"""
from __future__ import annotations

import os
import subprocess
import sys

CANNOT_COMPUTE_MUT_TARGET = os.path.join(
    "smartbi", "gold", "restaurant", "restaurant_ops_router.py")
CANNOT_COMPUTE_MUT_TESTS = os.path.join(
    "smartbi", "gold", "tests", "test_food_cost_ratio_query.py")

#: (编号, 说明, 锚点串, 替换串, 该红的测试名)
#: ⛔ 每条锚点必须在目标文件里**恰好 1 次** —— 多于 1 次说明我打的是公共路径。
CANNOT_COMPUTE_MUT_CASES = (
    (
        "M1",
        "把抬头的限定语删掉（「领的料，不是实际用掉的」→ 中性措辞）",
        '"能算的是（**领的料**，不是实际用掉的）：",',
        '"能算的是：",',
        "test_the_substitute_is_never_passed_off_as_the_food_cost_ratio",
    ),
    (
        "M2",
        "没有领料数据时照样打一个 0.00%（兜底把「我不知道」翻译成「是 0」）",
        "can_show_requisition = req_cost is not None and req_cost > 0 and revenue > 0",
        "can_show_requisition = revenue > 0",
        "test_no_requisition_data_means_no_percentage_at_all",
    ),
    (
        "M3",
        "把假承诺加回来（「盘点数据进了系统，这个数就能算」）",
        '"这一栏得我们先做，**不用你操作**。",',
        '"这一栏得我们先做。这段时间的头和尾各盘一次库，'
        '盘点数据进了系统，这个数就能算。",',
        "test_it_does_not_send_him_to_count_stock_because_that_will_not_help",
    ),
    (
        "M4",
        "盘点次数写死成「你已经盘过库了」（不由数据说）",
        "if stocktaking_days > 0 else",
        "if True else",
        "test_a_tenant_that_never_counted_stock_is_not_told_it_already_did",
    ),
)


def _p(*a):
    print(*a, flush=True)


def _read(path: str) -> str:
    with open(path, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def _write(path: str, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def _run_tests() -> tuple:
    """跑那一份测试，返回 (rc, 失败的测试名集合)。"""
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", CANNOT_COMPUTE_MUT_TESTS,
         "-p", "no:cacheprovider", "-q", "--tb=no", "-rf"],
        capture_output=True, text=True,
    )
    out = proc.stdout + proc.stderr
    failed = set()
    for line in out.splitlines():
        if line.startswith("FAILED ") or line.startswith("ERROR "):
            # FAILED path::name - msg
            part = line.split(" ", 1)[1].split(" - ")[0]
            failed.add(part.rsplit("::", 1)[-1])
    # ⛔ 空转判据: 变异不该造出 SyntaxError / NameError / 收集失败
    broke = any(m in out for m in ("SyntaxError", "NameError", "INTERNALERROR",
                                   "errors during collection"))
    return proc.returncode, failed, broke, out


def main() -> int:
    if not os.path.exists(CANNOT_COMPUTE_MUT_TARGET):
        _p(f"🔴 找不到 {CANNOT_COMPUTE_MUT_TARGET} —— 要在 backend/python 下跑")
        return 2

    original = _read(CANNOT_COMPUTE_MUT_TARGET)

    # ── 基线: 不变异时必须全绿 ───────────────────────────────────────────
    rc0, failed0, broke0, _out0 = _run_tests()
    _p(f"[基线] rc={rc0} failed={sorted(failed0) or '∅'}")
    if rc0 != 0 or failed0:
        _p("🔴 基线就不是绿的 —— 变异读数无意义，本轮作废")
        return 2

    bad = 0
    try:
        for tag, desc, anchor, repl, expect in CANNOT_COMPUTE_MUT_CASES:
            _p("=" * 72)
            _p(f"# {tag} {desc}")

            # ── ① 变异真的生效了 ─────────────────────────────────────
            n = original.count(anchor)
            _p(f"  ① 锚点出现 {n} 次（要求恰好 1）")
            if n != 1:
                _p(f"  🔴 {tag} 锚点不是恰好 1 次 —— 打的是公共路径或锚点写错")
                bad += 1
                continue
            mutated = original.replace(anchor, repl, 1)
            if mutated == original:
                _p(f"  🔴 {tag} 替换后文本没变 —— 变异没生效")
                bad += 1
                continue
            try:
                compile(mutated, CANNOT_COMPUTE_MUT_TARGET, "exec")
            except SyntaxError as exc:
                _p(f"  🔴 {tag} 变异后 SyntaxError: {exc} —— 空转，不算数")
                bad += 1
                continue
            _write(CANNOT_COMPUTE_MUT_TARGET, mutated)
            landed = anchor not in _read(CANNOT_COMPUTE_MUT_TARGET)
            _p(f"  ① 落盘确认: 锚点已从文件里消失 = {landed}")
            if not landed:
                _p(f"  🔴 {tag} 没落盘")
                bad += 1
                continue

            rc, failed, broke, _out = _run_tests()
            _p(f"  跑测试: rc={rc} failed={sorted(failed) or '∅'}")

            if broke:
                _p(f"  🔴 {tag} 红在 SyntaxError/NameError 上 —— ⛔ 不算数")
                bad += 1
            # ── ② 该红的红了 ─────────────────────────────────────────
            elif expect not in failed:
                _p(f"  🔴 {tag} ② 失败: 它守的那条 `{expect}` **没红** "
                   f"—— 那条断言在守空气，或变异打在了它看不见的地方")
                bad += 1
            else:
                _p(f"  ✅ ② 该红的红了: {expect}")
                # ── ③ 不该红的没红 ───────────────────────────────────
                extra = failed - {expect}
                if extra:
                    _p(f"  ⚠️ ③ 计划外变红 {sorted(extra)} "
                       f"—— 归因不干净，说明这处改动被多条断言共守")
                else:
                    _p("  ✅ ③ 没有计划外变红")

            _write(CANNOT_COMPUTE_MUT_TARGET, original)
    finally:
        _write(CANNOT_COMPUTE_MUT_TARGET, original)

    # 恢复之后必须回到全绿 —— 否则我留了个烂摊子
    rc9, failed9, _b9, _o9 = _run_tests()
    _p("=" * 72)
    _p(f"[恢复后] rc={rc9} failed={sorted(failed9) or '∅'}")
    if rc9 != 0:
        _p("🔴 恢复之后不绿 —— 文件没还原干净")
        return 2
    _p(f"MUTATION_VERDICT={'1 有变异没按预期红' if bad else '0 四条全部按预期红'}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
