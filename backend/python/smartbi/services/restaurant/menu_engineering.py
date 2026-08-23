"""Kasavana-Smith Menu Engineering 4-quadrant analyzer.

Classifies menu items by popularity (sold quantity) x profitability
(contribution margin ratio). Split point: median of each axis.

Reference: Kasavana & Smith, "Menu Engineering: A Practical Guide to
Menu Analysis" (1990). https://en.wikipedia.org/wiki/Menu_engineering

The analyzer is stateless -- construct once, call analyze() for each
input DataFrame. Handles empty input gracefully (returns empty report).

## 🔴 一道菜没有成本卡时, 它不进四象限 (2026-08-20)

以前: 没有成本卡的菜, 上游按 `revenue * 0.4` 兜一个成本, 也就是**凭空给它
60% 毛利**, 然后拿这个编出来的数判它是明星还是瘦狗。

实测(MOCK_REST 10 道菜, 见下)那个兜底有三层坏处, 一层比一层深:

1. 被兜的那几道菜的象限是假的;
2. 🔴 **它们的 `margin_ratio` 全部**恰好等于 0.6**, 于是把中位数拖到 0.6 ——
   切分线本身变成那个编造的常数, **有成本卡的菜也跟着被它判**;
3. 🔴 同一个「不知道」, 上游把它写成「不在字典里」还是「写成 0.0」,
   结论**正好相反**: 罗氏虾(¥5.5M, 占该租户营收 24.5%)前者被判 dog
   「建议淘汰」, 后者 ratio 变成 1.0000(零成本=100% 毛利)被判 puzzle
   「建议推广」。

⇒ 算不出来的菜**单独列出来并说清为什么**, ⛔ 不进 `classifications`,
   ⛔ 不进 `quadrants`, ⛔ 也不以 0 混进任何一个数。

⚠️ 与本仓 `COALESCE(food_cost, 0)` 那次(把「没有成本卡」算成「零成本」
⇒ 毛利率 88.3%)是同一个形态: **兜底的默认值会把「我不知道」翻译成一个
具体的数, 而这两件事对下游完全不同。**

⚠️ `smartbi/gold/restaurant/dish_margin.py` 是**另一份**菜单工程实现(轴是
`qty × unitMargin`, 不是本文件的 `qty × marginRatio`)。它**早就**只让
`hasCost` 的菜进象限, 理由写在它自己的注释里。两份还在, 因为轴不同 ——
但「没有成本的菜不进象限」这条规则现在两份一致了。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

import pandas as pd

#: 四象限至少要有几道**可比**的菜。低于它就说「比不出来」, ⛔ 不硬切一刀。
#:
#: 🔴 本仓刚踩过同形的: 两条各自正确的剔除规则叠加后把比较集缩到 1 个元素,
#:    于是 `max` 和 `min` 返回同一个 —— 老板问「营收为什么下滑」, 产品答
#:    「最高月 2026-07 / 最低月 2026-07」。⇒ **任何「先过滤再取中位数/
#:    max/min」的地方, 必须守【过滤后的基数】。**
#:
#: 为什么是 4 而不是 2: 中位数切分要把菜分进**四**个格子, 少于 4 道时
#: 至少有一个格子恒空, 而「这个象限没有菜」会被读成一条结论(「你没有明星菜」),
#: 其实只是样本不够。2 或 3 道菜的中位数也几乎完全由单个样本决定。
MIN_COMPARABLE_ITEMS = 4


class MenuQuadrant(str, Enum):
    """4 Kasavana-Smith quadrants.

    Uses string values so JSON serialization works naturally.
    """
    STAR = "star"            # High volume x High margin -- 招牌菜
    CASH_COW = "cash_cow"    # High volume x Low margin -- 走量主力
    PUZZLE = "puzzle"        # Low volume x High margin -- 高利无人点
    DOG = "dog"              # Low volume x Low margin -- 淘汰候选


class ExclusionReason(str, Enum):
    """为什么这道菜进不了四象限。

    ⛔ 这不是一句「数据不全」—— 三类的**补救动作完全不同**, 合成一句话
       等于告诉老板「你去查吧」。
    ⚠️ 类别是**推出来的**(看 food_cost 是缺失 / 非正 / 营收非正), 不是手写
       清单 —— 手写清单会过期。
    """
    NO_COST_CARD = "no_cost_card"
    COST_CARD_WITHOUT_PRICE = "cost_card_without_price"
    NON_POSITIVE_REVENUE = "non_positive_revenue"


#: 每一类一句人话。措辞形状抄本仓已验证有效的 `render_capability_refusal`
#: (实测 5/5 逐字稳定): **说算不出什么 -> 缺的是什么 -> 补了会怎样**。
#: ⛔ 只此一处 —— 这几句话要出现在老板眼前, 不能两条路各写各的(形态 D)。
EXCLUSION_TEXT: dict[ExclusionReason, str] = {
    ExclusionReason.NO_COST_CARD: (
        "还没有成本卡，算不出毛利。缺的是：这道菜用了哪些食材、每份用多少、"
        "进价多少。把成本卡补上，它就会自动进四象限。"
    ),
    ExclusionReason.COST_CARD_WITHOUT_PRICE: (
        "成本卡建了，但里面的食材没有进价，成本算出来是 0。缺的是：食材进价。"
        "⚠️ 这一类最容易被误读成「零成本、100% 毛利」，所以宁可不判，也不拿 0 当真。"
    ),
    ExclusionReason.NON_POSITIVE_REVENUE: (
        "这段时间没有营收（退货冲平，或只送不卖），毛利率没有分母，算不出来。"
        "不是这道菜不好，是这个窗口内没有可算的销售。"
    ),
}


@dataclass
class MenuItemClassification:
    """One menu item's classification result."""
    name: str
    sold_qty: int
    revenue: float
    food_cost: float
    contribution_margin: float   # revenue - food_cost (absolute $)
    margin_ratio: float          # (revenue - food_cost) / revenue
    quadrant: MenuQuadrant

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "soldQty": self.sold_qty,
            "revenue": round(self.revenue, 2),
            "foodCost": round(self.food_cost, 2),
            "contributionMargin": round(self.contribution_margin, 2),
            "marginRatio": round(self.margin_ratio, 4),
            "quadrant": self.quadrant.value,
        }


@dataclass
class MenuItemExclusion:
    """一道**没有**进四象限的菜, 和它进不去的原因。

    ⛔ `foodCost` / `marginRatio` / `quadrant` 一律 `None`, **不是 0** ——
       「算不出」和「真的是 0」在下游必须长得不一样。本仓栽过: 消费方拿不到
       条目就落到 `0.0` 默认值, 两者从此不可区分。
    """
    name: str
    sold_qty: int
    revenue: float
    reason: ExclusionReason

    @property
    def reason_text(self) -> str:
        return EXCLUSION_TEXT[self.reason]

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "soldQty": self.sold_qty,
            "revenue": round(self.revenue, 2),
            "reason": self.reason.value,
            "reasonText": self.reason_text,
            # 显式 None: 下游读到 None 才知道「这里没有数」, 读到 0 会当成数。
            "foodCost": None,
            "marginRatio": None,
            "quadrant": None,
        }


@dataclass
class MenuEngineeringReport:
    """Complete analyzer output."""
    classifications: list[MenuItemClassification]
    popularity_median: float
    margin_median: float
    summary: dict[str, int]              # quadrant counts + totals
    recommendations: list[str] = field(default_factory=list)
    excluded: list[MenuItemExclusion] = field(default_factory=list)
    #: 可比基数够不够(见 MIN_COMPARABLE_ITEMS)。不够时 classifications 为空。
    comparable_sufficient: bool = True
    #: 两个轴的离散度。⛔ 只报数不下判断 —— 见 to_dict 的注释。
    axis_spread: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "classifications": [c.to_dict() for c in self.classifications],
            "popularityMedian": self.popularity_median,
            "marginMedian": self.margin_median,
            "summary": self.summary,
            "recommendations": self.recommendations,
            "quadrants": self._grouped(),
            # ── 以下是「算不出的那几道」的机器可读侧 ────────────────────
            # 🔴 正文(recommendations)和这里**必须说同一件事**。本仓踩过:
            #    只改正文而机器可读侧照旧发 0 ⇒ 前端 KPI 卡仍显示假数。
            # ⚠️ 键名刻意避开 `items` / `products` / `topProducts` /
            #    `allProducts` / `classifications` —— boss_decision_brief 的
            #    `_menu_item_metrics` 按这些键**精确相等**地捞菜品指标, 用它们
            #    会把「算不出的菜」重新喂回下游。
            "excluded": [e.to_dict() for e in self.excluded],
            "excludedSummary": self._excluded_summary(),
            # 字段名与 gold/restaurant/dish_margin.py 的 `coverage` 一致 ——
            # 同一个意思两套字段名就是下一处漂移(形态 D)。
            "coverage": self._coverage(),
            "comparable": {
                "classifiedCount": len(self.classifications),
                "minRequired": MIN_COMPARABLE_ITEMS,
                "sufficient": self.comparable_sufficient,
                # ⛔ 只报离散度, **不**据它自动隐藏象限。一个会误报的提示比没有
                #    提示更糟 —— 它烧掉的是信任。数摆在这里, 判断留给读的人。
                "axisSpread": self.axis_spread,
            },
        }

    def _grouped(self) -> dict[str, list[dict]]:
        """Items grouped by quadrant for frontend rendering convenience."""
        groups: dict[str, list[dict]] = {q.value: [] for q in MenuQuadrant}
        for c in self.classifications:
            groups[c.quadrant.value].append(c.to_dict())
        return groups

    def _excluded_summary(self) -> dict:
        by_reason: dict[str, dict] = {}
        for e in self.excluded:
            slot = by_reason.setdefault(
                e.reason.value,
                {"count": 0, "revenue": 0.0, "names": [], "text": e.reason_text},
            )
            slot["count"] += 1
            slot["revenue"] = round(slot["revenue"] + e.revenue, 2)
            slot["names"].append(e.name)
        return {
            "count": len(self.excluded),
            "revenue": round(sum(e.revenue for e in self.excluded), 2),
            "byReason": by_reason,
        }

    def _coverage(self) -> dict:
        rev_classified = sum(c.revenue for c in self.classifications)
        rev_total = rev_classified + sum(e.revenue for e in self.excluded)
        return {
            "dishCount": len(self.classifications),
            "totalDishCount": len(self.classifications) + len(self.excluded),
            "revenueClassified": round(rev_classified, 2),
            "revenueTotal": round(rev_total, 2),
            # ⛔ 分母为 0 时给 None 而不是 0.0 —— 「没有营收」不是「覆盖率 0%」。
            "revenueRatio": (
                round(rev_classified / rev_total, 4) if rev_total > 0 else None
            ),
        }


def _empty_summary() -> dict[str, int]:
    return {
        "total_items": 0,
        "star_count": 0,
        "cow_count": 0,
        "puzzle_count": 0,
        "dog_count": 0,
    }


class MenuEngineeringAnalyzer:
    """Classify menu items into 4 quadrants by popularity x margin.

    Uses median split on both axes -- stable regardless of data size.
    The >= comparison ensures items exactly at the median are classified
    as 'high' rather than 'low', biasing toward more optimistic outcomes.

    ⛔ 只有**能真的算出毛利**的菜进象限。缺成本 / 成本非正 / 营收非正的菜
       走 `excluded`, 各带各的原因。理由见模块 docstring。
    """

    def analyze(
        self,
        df: pd.DataFrame,
        name_col: str = "name",
        qty_col: str = "sold_qty",
        revenue_col: str = "revenue",
        food_cost_col: str = "food_cost",
        reason_col: str = "exclusion_reason",
    ) -> MenuEngineeringReport:
        """`reason_col` 可选: 上游(handler)知道**为什么**没有成本时填它。

        上游不填时本函数按结构自己判(缺失 / 非正), 但分不出「没建卡」和
        「建了卡但食材没进价」—— 那个区别只有拿着成本字典的上游看得见。
        """
        if df is None or df.empty:
            return MenuEngineeringReport(
                classifications=[],
                popularity_median=0.0,
                margin_median=0.0,
                summary=_empty_summary(),
                recommendations=[],
                excluded=[],
                comparable_sufficient=False,
                axis_spread={},
            )

        required = [name_col, qty_col, revenue_col, food_cost_col]
        missing = [c for c in required if c not in df.columns]
        if missing:
            raise ValueError(f"Missing required columns: {missing}")

        df = df.copy()

        # ── 分流: 能算的 / 算不出的 ──────────────────────────────────────
        # ⛔ 这里**没有**任何兜底成本。以前是 `revenue * 0.4`(见模块 docstring),
        #    现在读不到就归入 excluded 并说明原因 —— 读不到就说读不到,
        #    比读到一个假的数好。
        priced: list[dict] = []
        excluded: list[MenuItemExclusion] = []
        for _, row in df.iterrows():
            name = str(row[name_col])
            qty = int(row[qty_col])
            revenue = float(row[revenue_col])
            declared = row.get(reason_col) if reason_col in df.columns else None
            cost = row[food_cost_col]
            cost_missing = cost is None or pd.isna(cost)

            reason = self._exclusion_reason(declared, cost_missing, cost, revenue)
            if reason is not None:
                excluded.append(MenuItemExclusion(
                    name=name, sold_qty=qty, revenue=revenue, reason=reason))
                continue

            food_cost = float(cost)
            priced.append({
                "name": name, "qty": qty, "revenue": revenue,
                "food_cost": food_cost,
                # 营收已保证 > 0(否则上面就被排除了), 所以这里没有除零兜底。
                # ⚠️ 旧版写的是 `df[revenue_col].replace(0, 1)` —— 那是把分母
                #    0 悄悄换成 1, 又一个「把不知道翻译成一个具体的数」。
                "margin_ratio": (revenue - food_cost) / revenue,
            })

        axis_spread = self._axis_spread(priced)

        # ── 基数守卫 ────────────────────────────────────────────────────
        if len(priced) < MIN_COMPARABLE_ITEMS:
            return MenuEngineeringReport(
                classifications=[],
                popularity_median=0.0,
                margin_median=0.0,
                summary=_empty_summary(),
                recommendations=self._insufficient_recommendations(priced, excluded),
                excluded=excluded,
                comparable_sufficient=False,
                axis_spread=axis_spread,
            )

        popularity_median = float(pd.Series([p["qty"] for p in priced]).median())
        margin_median = float(pd.Series([p["margin_ratio"] for p in priced]).median())

        classifications: list[MenuItemClassification] = []
        for p in priced:
            high_volume = p["qty"] >= popularity_median
            high_margin = p["margin_ratio"] >= margin_median

            if high_volume and high_margin:
                q = MenuQuadrant.STAR
            elif high_volume and not high_margin:
                q = MenuQuadrant.CASH_COW
            elif not high_volume and high_margin:
                q = MenuQuadrant.PUZZLE
            else:
                q = MenuQuadrant.DOG

            classifications.append(MenuItemClassification(
                name=p["name"],
                sold_qty=p["qty"],
                revenue=p["revenue"],
                food_cost=p["food_cost"],
                contribution_margin=p["revenue"] - p["food_cost"],
                margin_ratio=p["margin_ratio"],
                quadrant=q,
            ))

        counts = {q.value: 0 for q in MenuQuadrant}
        for c in classifications:
            counts[c.quadrant.value] += 1

        summary = {
            # ⚠️ 口径: `total_items` 是**进了象限的**道数, 与四个 count 相加相等。
            #    「一共看了几道菜」是 coverage.totalDishCount, 两个数不同是对的。
            "total_items": len(classifications),
            "star_count": counts["star"],
            "cow_count": counts["cash_cow"],
            "puzzle_count": counts["puzzle"],
            "dog_count": counts["dog"],
        }

        return MenuEngineeringReport(
            classifications=classifications,
            popularity_median=popularity_median,
            margin_median=margin_median,
            summary=summary,
            recommendations=self._generate_recommendations(
                classifications, summary, excluded),
            excluded=excluded,
            comparable_sufficient=True,
            axis_spread=axis_spread,
        )

    @staticmethod
    def _exclusion_reason(declared, cost_missing: bool, cost, revenue: float):
        """这道菜要不要被排除, 排除的话是哪一类。返回 None 表示可以进象限。

        ⚠️ 营收判在最前: 没有分母时, 有没有成本卡都算不出毛利率。
        """
        if revenue <= 0:
            return ExclusionReason.NON_POSITIVE_REVENUE
        if declared is not None and not (isinstance(declared, float) and pd.isna(declared)):
            return ExclusionReason(declared) if not isinstance(
                declared, ExclusionReason) else declared
        if cost_missing:
            return ExclusionReason.NO_COST_CARD
        if float(cost) <= 0:
            # 上游没说是哪一类时, 「成本算出来是 0」最可能是食材没进价。
            return ExclusionReason.COST_CARD_WITHOUT_PRICE
        return None

    @staticmethod
    def _axis_spread(priced: list[dict]) -> dict:
        """两个轴的离散度。⛔ 只是把数摆出来, 不据它自动做任何隐藏。

        为什么要有它: 中位数切分在**很平的**轴上等于按噪声分类 ——
        实测 MOCK_REST 有成本卡的 6 道菜销量 CV 只有 6.74%(最畅销的比最不畅销的
        只多卖 22%), 那条「高销/低销」的线切的基本是噪声。
        ⛔ 但**不**据此自动隐藏象限: 阈值型提示会误报, 而一个会误报的提示比没有
           提示更糟。把 min/median/max 给出去, 让读的人自己看那条线值不值钱。
        """
        if not priced:
            return {}

        def block(vals: list[float]) -> dict:
            s = pd.Series(vals)
            lo, hi, mean = float(s.min()), float(s.max()), float(s.mean())
            return {
                "min": round(lo, 4),
                "median": round(float(s.median()), 4),
                "max": round(hi, 4),
                # 变异系数; 均值为 0 或负时不可解释 -> None, ⛔ 不填 0
                "cv": round(float(s.std(ddof=0)) / mean, 4) if mean > 0 else None,
                # 完全打平: 中位数切分在这条轴上不含任何信息
                "flat": lo == hi,
            }

        return {
            "popularity": block([p["qty"] for p in priced]),
            "marginRatio": block([p["margin_ratio"] for p in priced]),
        }

    @staticmethod
    def _exclusion_recommendations(
        excluded: list[MenuItemExclusion],
    ) -> list[str]:
        """把「这几道算不出」写成老板读得懂的话, 一类一句。

        🔴 正文和 `excludedSummary` 是**同一份数据的两种渲染**, 不是两处手写 ——
           否则它们会漂(形态 D)。
        """
        if not excluded:
            return []
        by_reason: dict[ExclusionReason, list[MenuItemExclusion]] = {}
        for e in excluded:
            by_reason.setdefault(e.reason, []).append(e)

        recs: list[str] = []
        for reason, items in by_reason.items():
            names = "、".join(i.name for i in items[:5])
            more = f" 等 {len(items)} 道" if len(items) > 5 else ""
            revenue = sum(i.revenue for i in items)
            recs.append(
                f"{len(items)} 道菜没有进四象限（{names}{more}，合计营收 "
                f"{revenue:,.2f} 元）：{EXCLUSION_TEXT[reason]}"
            )
        return recs

    @classmethod
    def _insufficient_recommendations(
        cls,
        priced: list[dict],
        excluded: list[MenuItemExclusion],
    ) -> list[str]:
        """可比基数不够时说「比不出来」, ⛔ 不硬切一刀。"""
        return [
            f"**四象限这次比不出来。** 能算出毛利的只有 {len(priced)} 道菜，"
            f"少于 {MIN_COMPARABLE_ITEMS} 道就没法用中位数把菜分进四个格子——"
            f"硬切出来的「明星」「瘦狗」只反映这几道菜互相之间的高低，"
            f"不是菜单结构。",
            *cls._exclusion_recommendations(excluded),
        ]

    @classmethod
    def _generate_recommendations(
        cls,
        classifications: list[MenuItemClassification],
        summary: dict[str, int],
        excluded: list[MenuItemExclusion] | None = None,
    ) -> list[str]:
        """Generate actionable Chinese recommendations per quadrant."""
        recs: list[str] = []
        dog_count = summary["dog_count"]
        puzzle_count = summary["puzzle_count"]
        star_count = summary["star_count"]
        total = summary["total_items"]

        if dog_count > 0:
            dogs = [c.name for c in classifications if c.quadrant == MenuQuadrant.DOG][:5]
            recs.append(
                f"淘汰 {dog_count} 道 Dog 菜 (低销 x 低利): {', '.join(dogs)} -- 释放菜单空间和备料 SKU"
            )

        if puzzle_count > 0:
            puzzles = [c.name for c in classifications if c.quadrant == MenuQuadrant.PUZZLE][:5]
            recs.append(
                f"{puzzle_count} 道 Puzzle 菜 (高利无人点): {', '.join(puzzles)} -- 考虑服务员话术推广或重新定位"
            )

        if star_count > 0:
            stars = [c.name for c in classifications if c.quadrant == MenuQuadrant.STAR][:5]
            recs.append(
                f"保护 {star_count} 道 Star 菜 (高销高利): {', '.join(stars)} -- 锁定 BOM, 固定 SOP, 不许私改配方"
            )

        if total >= 20 and dog_count / total > 0.25:
            recs.append(
                f"Dog 占比 {dog_count}/{total} 过高, 菜单结构性问题, 建议整体重设计"
            )

        # ⚠️ 排在**最后**: 上面几条是「可以照着做的动作」, 这几条是「这部分还没
        #    有结论」。⛔ 不要让「算不出」把动作挤下去 —— 但也绝不省掉。
        recs.extend(cls._exclusion_recommendations(excluded or []))

        return recs
