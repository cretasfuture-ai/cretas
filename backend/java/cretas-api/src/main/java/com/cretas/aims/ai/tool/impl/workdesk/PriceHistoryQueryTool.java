package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import com.cretas.aims.ai.tool.WorkdeskRole;

/**
 * 价格历史查询 Tool — Sprint 8 P4b 采购员 Workdesk.
 *
 * <p>采购员小赵关心: "X 物料历史采购价多少, 这次报价合理吗?" — 输出最近 N 个月每月
 * 平均单价 + 同比趋势, 让采购员对比当前供应商报价是否在合理区间.
 *
 * <p>注: 受 {@code @PriceSensitive} 影响, 仓管员 (warehouse_manager) 角色看到的
 * unitPrice 会被 strip 为 null, 本 Tool 会显示"无权限查看". 采购员 (procurement) /
 * 财务 (finance) 角色不受影响.
 *
 * <p>Intent Code: {@code PRICE_HISTORY_QUERY}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4b)
 */
@Slf4j
@Component
public class PriceHistoryQueryTool extends AbstractBusinessTool {

    private static final int DEFAULT_MONTHS_BACK = 3;
    private static final int MAX_MONTHS_BACK = 24;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    /** #1305 采购价口径: 配置的 procurement 读 (无配置行时只认采购岗)。缺了就拒绝 (fail closed)。 */
    @Autowired(required = false)
    private com.cretas.aims.security.PriceVisibility priceVisibility;

    @Autowired(required = false)
    private com.cretas.aims.repository.UserRepository userRepository;

    @Override
    public String getToolName() {
        return "price_history_query";
    }

    @Override
    public WorkdeskRole workdeskRole() {
        return WorkdeskRole.PURCHASER;
    }

    @Override
    public String getDescription() {
        return "价格历史查询 — 按 materialTypeId 输出最近 N 个月每月加权平均采购价 + 同比趋势. "
                + "LLM 触发场景: 采购员问 'X 物料历史价格' / '这次报价 Y 元/kg 贵吗?' / "
                + "'X 物料涨价了多少' / '价格曲线' / '采购价对比'; 不指定物料时出原料进价涨幅排名 ('哪些原料价格涨得最多'). read-only. "
                + "默认 monthsBack=3 (上限 24). 受 PriceSensitive 影响, "
                + "仓管员角色看不到 unitPrice (返 null + 提示).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "材料类型 ID (可选; 不填 = 按原料比较近期进价涨幅, 出涨幅排名)");
        properties.put("materialTypeId", materialTypeId);

        Map<String, Object> monthsBack = new HashMap<>();
        monthsBack.put("type", "integer");
        monthsBack.put("description", "回看月数 (1-24, 默认 3)");
        monthsBack.put("default", DEFAULT_MONTHS_BACK);
        monthsBack.put("minimum", 1);
        monthsBack.put("maximum", MAX_MONTHS_BACK);
        properties.put("monthsBack", monthsBack);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 整个结果就是采购价 (月均价 / 环比), 不能像列表那样只遮价格字段: 看不到采购价的人一个数都不给。
        // 与 PurchaseController / PriceFieldResponseAdvice 同一个判定 (S5-12 / #1305), 不用默认矩阵。
        if (!callerMayViewPurchasePrices(context)) {
            throw new com.cretas.aims.exception.BusinessException(403,
                    "采购价历史只对有采购价查看权限的人开放 (按工厂权限配置; 未配置时为采购岗)。");
        }
        String materialTypeId = getString(params, "materialTypeId");
        if (materialTypeId == null || materialTypeId.isBlank()) {
            return doRiseRanking(factoryId, params);
        }
        int monthsBack = clamp(getInteger(params, "monthsBack", DEFAULT_MONTHS_BACK),
                1, MAX_MONTHS_BACK);

        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth fromMonth = currentMonth.minusMonths(monthsBack - 1L);
        LocalDate windowStart = fromMonth.atDay(1);
        LocalDate windowEnd = today;

        log.info("price_history_query — factory={} material={} monthsBack={}",
                factoryId, materialTypeId, monthsBack);

        // 拉该物料所有历史 items
        List<PurchaseOrderItem> allItems = purchaseOrderItemRepository
                .findByMaterialTypeId(materialTypeId);
        if (allItems.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("materialTypeId", materialTypeId);
            empty.put("monthsBack", monthsBack);
            empty.put("monthlyPrices", Collections.emptyList());
            return buildSimpleResult(
                    String.format("物料 %s 无任何历史采购记录, 无法对比价格", materialTypeId),
                    empty);
        }

        // 按 PO 维度过滤工厂 + 时间窗口
        String materialName = null;
        String unit = null;
        Map<YearMonth, MonthAgg> aggByMonth = new TreeMap<>();
        int totalItemsInWindow = 0;
        int itemsWithoutPrice = 0;

        for (PurchaseOrderItem item : allItems) {
            Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findById(item.getPurchaseOrderId());
            if (poOpt.isEmpty()) continue;
            PurchaseOrder po = poOpt.get();
            if (!factoryId.equals(po.getFactoryId())) continue;
            if (po.getOrderDate() == null) continue;
            if (po.getOrderDate().isBefore(windowStart) || po.getOrderDate().isAfter(windowEnd)) continue;

            totalItemsInWindow++;
            if (materialName == null) materialName = item.getMaterialName();
            if (unit == null) unit = item.getUnit();

            BigDecimal price = item.getUnitPrice();
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            if (price == null) {
                itemsWithoutPrice++;
                continue;
            }
            YearMonth ym = YearMonth.from(po.getOrderDate());
            aggByMonth.computeIfAbsent(ym, k -> new MonthAgg()).add(price, qty);
        }

        // 构建月数据 + 同比 (MoM)
        List<Map<String, Object>> monthlyPrices = new ArrayList<>();
        BigDecimal prevAvg = null;
        for (YearMonth ym : aggByMonth.keySet()) {
            MonthAgg agg = aggByMonth.get(ym);
            BigDecimal wAvg = agg.weightedAvg();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("yearMonth", ym.toString());
            row.put("orderCount", agg.count);
            row.put("totalQty", agg.totalQty);
            row.put("weightedAvgPrice", wAvg);
            row.put("minPrice", agg.minPrice);
            row.put("maxPrice", agg.maxPrice);
            if (prevAvg != null && prevAvg.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changePct = wAvg.subtract(prevAvg)
                        .multiply(new BigDecimal("100"))
                        .divide(prevAvg, 2, RoundingMode.HALF_UP);
                row.put("momChangePct", changePct);
                String trendIcon = changePct.compareTo(BigDecimal.ZERO) > 0 ? "📈"
                        : changePct.compareTo(BigDecimal.ZERO) < 0 ? "📉" : "➖";
                row.put("trend", trendIcon);
            } else {
                row.put("momChangePct", null);
                row.put("trend", "➖");
            }
            prevAvg = wAvg;
            monthlyPrices.add(row);
        }

        // 整体均价 + 整体趋势
        BigDecimal overallAvg = null;
        if (!monthlyPrices.isEmpty()) {
            BigDecimal sumW = BigDecimal.ZERO;
            BigDecimal sumQ = BigDecimal.ZERO;
            for (MonthAgg agg : aggByMonth.values()) {
                sumW = sumW.add(agg.totalAmount);
                sumQ = sumQ.add(agg.totalQty);
            }
            if (sumQ.compareTo(BigDecimal.ZERO) > 0) {
                overallAvg = sumW.divide(sumQ, 4, RoundingMode.HALF_UP);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("materialTypeId", materialTypeId);
        data.put("materialName", materialName);
        data.put("unit", unit);
        data.put("monthsBack", monthsBack);
        data.put("windowStart", windowStart.toString());
        data.put("windowEnd", windowEnd.toString());
        data.put("totalItemsInWindow", totalItemsInWindow);
        data.put("itemsWithoutPriceVisibility", itemsWithoutPrice);
        data.put("overallWeightedAvgPrice", overallAvg);
        data.put("monthlyPrices", monthlyPrices);

        String message;
        if (monthlyPrices.isEmpty()) {
            if (itemsWithoutPrice > 0) {
                message = String.format(
                        "⚠️ 物料 %s 最近 %d 个月有 %d 条采购记录, 但当前角色无权限查看单价 (PriceSensitive)",
                        materialName != null ? materialName : materialTypeId,
                        monthsBack, itemsWithoutPrice);
            } else {
                message = String.format("物料 %s 最近 %d 个月无采购记录",
                        materialName != null ? materialName : materialTypeId, monthsBack);
            }
        } else {
            message = String.format(
                    "%s 最近 %d 个月价格历史: %d 月数据, 加权均价 %s%s%s",
                    materialName != null ? materialName : materialTypeId,
                    monthsBack, monthlyPrices.size(),
                    overallAvg != null ? overallAvg.toPlainString() : "?",
                    unit != null ? "/" + unit : "",
                    itemsWithoutPrice > 0
                            ? String.format(" (%d 行无价格 — PriceSensitive)", itemsWithoutPrice)
                            : "");
        }
        return buildSimpleResult(message, data);
    }

    /** 涨幅排名: 近期窗口 (最近 30 天) vs 此前窗口 (再往前 60 天) 的加权均价, 按涨幅% 降序取前 N。 */
    static final int RANK_RECENT_DAYS = 30;
    static final int RANK_BASE_DAYS = 60;
    static final int RANK_TOP_N = 5;

    private Map<String, Object> doRiseRanking(String factoryId, Map<String, Object> params) {
        LocalDate today = LocalDate.now();
        LocalDate recentStart = today.minusDays(RANK_RECENT_DAYS - 1L);
        LocalDate baseEnd = recentStart.minusDays(1);
        LocalDate baseStart = baseEnd.minusDays(RANK_BASE_DAYS - 1L);
        String window = String.format("近期 %s~%s vs 此前 %s~%s", recentStart, today, baseStart, baseEnd);

        List<PurchaseOrder> orders = purchaseOrderRepository.findByFactoryIdAndDateRange(factoryId, baseStart, today);
        Map<String, RiseAgg> byMaterial = new LinkedHashMap<>();
        int itemsWithoutPrice = 0;
        for (PurchaseOrder po : orders) {
            if (po.getOrderDate() == null) continue;
            boolean recent = !po.getOrderDate().isBefore(recentStart);
            for (PurchaseOrderItem item : purchaseOrderItemRepository.findByPurchaseOrderId(po.getId())) {
                if (item.getUnitPrice() == null) { itemsWithoutPrice++; continue; }
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                if (qty.compareTo(BigDecimal.ZERO) <= 0) continue;
                String key = item.getMaterialTypeId() != null ? item.getMaterialTypeId()
                        : String.valueOf(item.getMaterialName());
                RiseAgg agg = byMaterial.computeIfAbsent(key, k -> new RiseAgg());
                if (agg.name == null) agg.name = item.getMaterialName() != null ? item.getMaterialName() : key;
                if (agg.unit == null) agg.unit = item.getUnit();
                (recent ? agg.recent : agg.base).add(item.getUnitPrice(), qty);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RiseAgg a : byMaterial.values()) {
            if (a.recent.qty.signum() <= 0 || a.base.qty.signum() <= 0) continue; // 缺一边没法比, 不编
            BigDecimal before = a.base.avg();
            BigDecimal after = a.recent.avg();
            if (before.signum() <= 0) continue;
            BigDecimal pct = after.subtract(before).multiply(new BigDecimal("100"))
                    .divide(before, 1, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialName", a.name);
            row.put("unit", a.unit);
            row.put("beforeAvgPrice", before.setScale(2, RoundingMode.HALF_UP));
            row.put("recentAvgPrice", after.setScale(2, RoundingMode.HALF_UP));
            row.put("risePct", pct);
            rows.add(row);
        }
        rows.sort((x, y) -> ((BigDecimal) y.get("risePct")).compareTo((BigDecimal) x.get("risePct")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "RISE_RANKING");
        data.put("window", window);
        data.put("comparableMaterials", rows.size());
        data.put("itemsWithoutPriceVisibility", itemsWithoutPrice);
        if (rows.isEmpty()) {
            data.put("ranking", Collections.emptyList());
            return buildSimpleResult(String.format(
                    "近 %d 天没有足够的进价记录可比较涨幅（需同一原料在近 %d 天和此前 %d 天都有带单价的采购）。口径：%s。",
                    RANK_RECENT_DAYS + RANK_BASE_DAYS, RANK_RECENT_DAYS, RANK_BASE_DAYS, window), data);
        }
        List<Map<String, Object>> top = new ArrayList<>(rows.subList(0, Math.min(RANK_TOP_N, rows.size())));
        data.put("ranking", top);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("原料进价涨幅排名（口径：近 %d 天加权均价 vs 此前 %d 天，%s）：",
                RANK_RECENT_DAYS, RANK_BASE_DAYS, window));
        int i = 1;
        for (Map<String, Object> r : top) {
            BigDecimal pct = (BigDecimal) r.get("risePct");
            sb.append(String.format("\n%d. %s：%s → %s%s，%s%s%%", i++, r.get("materialName"),
                    r.get("beforeAvgPrice"), r.get("recentAvgPrice"),
                    r.get("unit") != null ? " 元/" + r.get("unit") : " 元",
                    pct.signum() > 0 ? "涨 " : pct.signum() < 0 ? "降 " : "持平 ", pct.abs().toPlainString()));
        }
        if (top.get(0).get("risePct") instanceof BigDecimal b && b.signum() <= 0) {
            sb.append("\n（可比较的原料近期都没有涨价）");
        }
        return buildSimpleResult(sb.toString(), data);
    }

    private static final class RiseAgg {
        String name;
        String unit;
        final Side recent = new Side();
        final Side base = new Side();
    }

    private static final class Side {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        void add(BigDecimal price, BigDecimal q) { amount = amount.add(price.multiply(q)); qty = qty.add(q); }
        BigDecimal avg() { return amount.divide(qty, 4, RoundingMode.HALF_UP); }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 月度聚合: 加权 AVG, min, max. */
    private static class MonthAgg {
        BigDecimal totalAmount = BigDecimal.ZERO; // sum(price × qty)
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        int count = 0;

        void add(BigDecimal price, BigDecimal qty) {
            count++;
            BigDecimal q = qty != null ? qty : BigDecimal.ZERO;
            totalAmount = totalAmount.add(price.multiply(q));
            totalQty = totalQty.add(q);
            if (minPrice == null || price.compareTo(minPrice) < 0) minPrice = price;
            if (maxPrice == null || price.compareTo(maxPrice) > 0) maxPrice = price;
        }

        BigDecimal weightedAvg() {
            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
            return totalAmount.divide(totalQty, 4, RoundingMode.HALF_UP);
        }
    }

    private boolean callerMayViewPurchasePrices(Map<String, Object> context) {
        if (priceVisibility == null || userRepository == null || context == null) {
            return false;
        }
        Object id = context.get("userId");
        Long userId;
        try {
            userId = id instanceof Number n ? n.longValue() : id != null ? Long.parseLong(id.toString()) : null;
        } catch (NumberFormatException e) {
            return false;
        }
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId).map(priceVisibility::canViewPurchasePrices).orElse(false);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * S6 gated-data rule (2026-11-03): its data is served by endpoints gated with these codes (any-of), so the AI path admits the same callers;
     * pinned by GatedDataToolAiPathPermissionTest (strict sweep).
     */
    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("procurement:read");
    }
}
