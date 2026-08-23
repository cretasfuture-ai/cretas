# -*- coding: utf-8 -*-
"""抓 Q16 / Q18 的**逐字原文** —— 老板真实会看到的那段话。

⛔ 不从代码里抄模板 —— 模板里有分支，抄下来的那一支不一定是他命中的那一支。
   跑一次让它自己说（形态 B⁷）。

⛔ 不重造仪器: `run_turn` / `clear_caches` 都从 `restaurant_panorama_probe`
   **import**，与全景基线同一条生产序列（形态 D：同一个东西两份一定会漂）。

每轮开跑前清 4 个缓存并**贴出清了哪几个**（硬约束 3）。
每条读数带 `tier`（`llm` / `plan_cache`）—— 分不出来源时「两轮一致」没有意义。

阳性对照：
  * 菜单目录条数 > 0（执行链活着）
  * `authority != llm_unavailable`（本进程有 LLM key，⛔ 不然每条都是 fail-closed 拒答）
  ⚠️ 问一句「仪器坏掉时它会不会也成立」：目录 0 条 / authority 是
     `llm_unavailable` —— 两者在链断掉时都会出现 ⇒ 这两条对照能红。

退出码: 0=抓到了 / 2=没量到（对照没过）
"""
from __future__ import annotations

import asyncio
import os
import sys

PROBE_ROUNDS_DEFAULT = "3"
os.environ.setdefault("PROBE_ROUNDS", PROBE_ROUNDS_DEFAULT)

from smartbi.scripts.restaurant_panorama_probe import (  # noqa: E402
    FID,
    LLM_UNAVAILABLE_AUTHORITY,
    ROUNDS,
    clear_caches,
    ctx,
    run_turn,
)

#: ⛔ 只这两句 —— 与全景那份 24 句是**两份不同的仪器**，读数不可混比。
CANNOT_COMPUTE_TEXT_QUESTIONS = (
    ("Q16", "食材成本占营收多少"),
    ("Q18", "哪个供应商报价最贵"),
)


def _p(*a):
    print(*a, flush=True)


async def main() -> int:
    _p(f"FID={FID} ROUNDS={ROUNDS} ctx={ctx!r}")
    pool = await ctx.pool()

    bad = False
    for rnd in range(1, ROUNDS + 1):
        cleared = clear_caches()
        _p("=" * 74)
        _p(f"# 第 {rnd} 轮  清了这几个缓存: {cleared}")
        for tag, q in CANNOT_COMPUTE_TEXT_QUESTIONS:
            row = await run_turn(pool, q, session_key=f"cc-{tag}-r{rnd}")
            _p("-" * 74)
            _p(f"[{tag}] {q}")
            _p(f"  kind={row.get('kind')} code={row.get('code')} "
               f"intent={row.get('intent')} tier={row.get('tier')} "
               f"authority={row.get('authority')} contract={row.get('contract')} "
               f"n={row.get('n')} fp={row.get('fp')} catalogue_n={row.get('catalogue_n')}")
            if row.get("catalogue_n", 0) <= 0:
                _p("  🔴 阳性对照失败: 菜单目录 0 条 ⇒ 执行链没起来")
                bad = True
            if row.get("authority") == LLM_UNAVAILABLE_AUTHORITY:
                _p("  🔴 阳性对照失败: authority=llm_unavailable ⇒ 探针没 key，不是产品答不上来")
                bad = True
            _p("  ── 原文 ──")
            _p(row.get("text") or "(run_turn 没带回原文)")

    _p("=" * 74)
    if bad:
        _p("PROBE_VERDICT=2 没量到（对照没过）")
        return 2
    _p("PROBE_VERDICT=0 抓到了")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
