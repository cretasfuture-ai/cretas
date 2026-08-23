from __future__ import annotations

"""Menu engineering section — Kasavana-Smith 4-quadrant classification.


Part of P3 Task 3.2. Wraps the P3.1 MenuEngineeringAnalyzer as a
FastAPI section endpoint. Classifies menu items into Star / Cash Cow /
Puzzle / Dog quadrants by popularity × profitability, returns the
full classification + recommendations.

Inputs (via context):
  pos_df (pd.DataFrame, required): POS sales data with columns
    product_col / quantity_col / revenue_col (defaults: 商品名称 / 数量 / 实收额)
  sku_food_costs (dict[str, float], required): per-dish food cost lookup,
    keyed by dish name, value = AGGREGATE food cost for the period (not unit
    cost — see the handler's own test fixture: 肥牛拼盘 revenue 27200 /
    cost 10880 = 40% cost). Populated by the orchestrator from SKU form data
    (BOM Layer 2+). Entirely missing → SKIPPED.
    **Partially** missing → the dishes it covers get classified, the rest go
    to `data["excluded"]` with a per-class reason. ⛔ 不再兜底(见下)。

Params (optional overrides):
  product_col, quantity_col, revenue_col — column name overrides

⚠️ **本 handler 目前在 prod 上到不了**(实测 2026-08-20, 见下), 但修的不是
   死代码的样子 —— 一旦有人接上 `sku_food_costs` 的产出方, 兜底立刻开火。

📏 实测(2026-08-20, 活的 prod, 每个 2 轮, 走 `POST /api/smartbi/restaurant/
   sections/menu_engineering`):
     MOCK_REST   → skipped「未提供 POS DataFrame」(该租户没有 upload)
     F006        → skipped「未提供 POS DataFrame」(upload 有但 0 行)
     LIUSHANMEN  → POS **加载成功**(uploadId 5188, reason=loaded),
                   仍然 skipped「缺少 SKU 食材成本数据 (sku_food_costs)」
   全仓 grep `sku_food_costs` / `skuFoodCosts`: **没有任何产出方**
   (Python / Java / 前端都没有), 只有本文件读它、analyzer 转发它、两个测试造它。
   ⇒ `revenue * 0.4` 那行在 prod 上**一次都没执行过**。它是埋着的雷, 不是
     正在发生的假数 —— 这一点必须说清, 否则「修好了线上的假数」是句假话。
"""

import time
from typing import Any

from smartbi.services.restaurant.menu_engineering import (
    ExclusionReason,
    MenuEngineeringAnalyzer,
)
from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)


class MenuEngineeringHandler(AbstractSectionHandler):
    """Kasavana-Smith 4-quadrant menu analyzer section."""

    section_name = "menu_engineering"

    def __init__(self) -> None:
        self._analyzer = MenuEngineeringAnalyzer()

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()

        # Prefer context (batch mode), fall back to params (standalone mode)
        pos_df = context.get("pos_df")
        if pos_df is None:
            pos_df = request.params.get("pos_df")
        if pos_df is None:
            return self.skipped(
                request,
                "未提供 POS DataFrame (需要 pos_df via context 或 params)",
                started,
            )

        # SKU food costs come from BOM Layer 2 data — check context first
        sku_costs = context.get("sku_food_costs") or request.params.get("sku_food_costs")
        if not sku_costs:
            return self.skipped(
                request,
                "缺少 SKU 食材成本数据 (sku_food_costs) — 需要上传 SKU form 或启用 BOM Layer 2+",
                started,
            )

        name_col = request.params.get("product_col", "商品名称")
        qty_col = request.params.get("quantity_col", "数量")
        revenue_col = request.params.get("revenue_col", "实收")

        if not hasattr(pos_df, "columns"):
            return self.skipped(
                request,
                "pos_df 不是 DataFrame (缺 columns 属性)",
                started,
            )

        if revenue_col not in pos_df.columns:
            for alias in ("实收额", "实收金额", "销售金额", "营业额"):
                if alias in pos_df.columns:
                    revenue_col = alias
                    break

        missing_cols = [c for c in (name_col, qty_col, revenue_col) if c not in pos_df.columns]
        if missing_cols:
            return self.skipped(
                request,
                f"POS 缺少必需列: {missing_cols}",
                started,
            )

        # Aggregate POS by dish name
        aggregated = (
            pos_df.groupby(name_col, as_index=False)
            .agg({qty_col: "sum", revenue_col: "sum"})
            .rename(columns={name_col: "name", qty_col: "sold_qty", revenue_col: "revenue"})
        )

        # Attach food_cost per dish from the sku_costs lookup.
        #
        # 🔴 2026-08-20: 这里以前写的是
        #        sku_costs.get(r["name"], r["revenue"] * 0.4)
        #    —— 查不到成本卡就**凭空按 40% 成本率兜一个数**, 也就是给这道菜编
        #    一个 60% 毛利, 然后拿它判明星/瘦狗。实测(MOCK_REST 10 道菜)这个
        #    兜底会把 `marginMedian` 本身拖到 0.6, 于是**有成本卡的菜也被那个
        #    编造的常数判**; 而同一个「不知道」写成「不在字典里」还是「0.0」,
        #    结论正好相反(淘汰 ↔ 推广)。详见 analyzer 模块 docstring。
        #
        # ⇒ 查不到就留空(NaN), 由 analyzer 归入 `excluded` 并说明原因。
        #    **只有这一层看得见「不在字典里」和「在字典里但值不可用」的区别**,
        #    所以原因在这里标, analyzer 不再猜。
        def _cost_and_reason(name: str):
            if name not in sku_costs:
                return None, ExclusionReason.NO_COST_CARD.value
            raw = sku_costs.get(name)
            value = self._safe_float(raw)
            if value is None:
                # 键在、值不是数(None / "" / 脏字符串) —— 成本卡在, 但算不出钱。
                return None, ExclusionReason.COST_CARD_WITHOUT_PRICE.value
            if value <= 0:
                # 成本卡在、食材没进价 ⇒ 成本算出来是 0。
                # ⛔ 绝不当成「零成本」放行: 那会变成 100% 毛利。
                return None, ExclusionReason.COST_CARD_WITHOUT_PRICE.value
            return value, None

        resolved = [_cost_and_reason(str(n)) for n in aggregated["name"]]
        aggregated["food_cost"] = [c for c, _ in resolved]
        aggregated["exclusion_reason"] = [r for _, r in resolved]

        try:
            report = self._analyzer.analyze(aggregated)
        except Exception as e:
            return self.skipped(
                request,
                f"菜品工程分析失败: {e}",
                started,
            )

        return self.ok(request, data=report.to_dict(), started=started)
