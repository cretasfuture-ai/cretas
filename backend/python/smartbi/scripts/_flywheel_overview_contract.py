"""【只读核实】`/api/smartbi/flywheel/overview` 的**真实返回键** vs web-admin 读的键。

⛔ 一次性核实探针，不进 cron，不改任何东西。
⛔ 本轮范围**不含 web-admin** —— 这里只产出逐字对照，修不修由前端那条线决定。

判据: ⛔ 不 grep 判「做没做」，**真跑一次**这个端点函数，把返回的键打出来。
"""
from __future__ import annotations

import asyncio
import json
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe("MOCK_REST")  # noqa: E402


class _StubRequest:
    """只提供 `_require_platform_admin` 要读的三个 state 字段。
    `auth_method='internal'`(Java->Python 内部调用)按既有约定直接放行。"""

    class _S:
        role = "platform_admin"
        auth_method = "internal"
        username = "probe"
        factory_id = None

    state = _S()


async def main() -> int:
    await ctx.pool()  # 先把池起起来
    from smartbi.api import flywheel as fw

    resp = await fw.overview(_StubRequest(), domain="restaurant", days=30)
    data = resp["data"] if isinstance(resp, dict) and "data" in resp else resp

    print("=== Python 真实返回 (顶层 envelope) ===")
    print(sorted(resp.keys()) if isinstance(resp, dict) else type(resp))
    print()
    print("=== Python 真实返回 data 的键 (逐字) ===")
    for k in sorted(data.keys()):
        v = data[k]
        shown = v if not isinstance(v, (dict, list)) else type(v).__name__
        print("  %-32s = %s" % (k, shown))

    # web-admin `FlywheelOverview` / `Overview.vue` 读的键 —— 逐字抄自
    # web-admin/src/api/smartbi/ai-flywheel.ts:47-73 与 Overview.vue:74,98-130,147-151
    fe_top = ["domain", "generated_at", "today", "d7", "d30", "tier_distribution"]
    fe_window = ["qa_count", "llm_calls", "cache_hit_rate", "promotion_hit_rate",
                 "token_estimate", "contract_fail_rate", "clarify_rate",
                 "thumbs_up", "thumbs_down"]
    fe_tier = ["tier", "label", "count", "pct"]

    print()
    print("=== 逐字对照: 前端顶层读的键 vs Python 真的有没有 ===")
    for k in fe_top:
        print("  %-20s 前端读 -> Python %s" % (k, "有 ✅" if k in data else "**没有** ❌"))

    print()
    print("=== 前端窗口对象(today/d7/d30)里的键 vs Python 扁平层 ===")
    for k in fe_window:
        print("  %-22s 前端读 data[窗口].%-22s Python 扁平层 %s"
              % (k, k, "有同名 ✅" if k in data else "**无同名** ❌"))

    print()
    print("=== tier_distribution 行的键 ===")
    td = data.get("tier_distribution") or []
    actual_tier_keys = sorted(td[0].keys()) if td else []
    print("  Python 实际 = %s" % actual_tier_keys)
    for k in fe_tier:
        print("  %-8s 前端读 -> Python %s" % (k, "有 ✅" if k in actual_tier_keys else "**没有** ❌"))

    print()
    print("=== promoted_routes (本次改动的那块) ===")
    print(json.dumps(data.get("promoted_routes"), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
