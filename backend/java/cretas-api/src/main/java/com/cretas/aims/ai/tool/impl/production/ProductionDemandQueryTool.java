package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.repository.ProductionLineCapacityConfigRepository;
import com.cretas.aims.repository.ProductionLineRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sprint 10 Loop 5 — 缺产订单查询 Tool (READ).
 *
 * <p>For每个 productId 聚合所有未完成 SO 的 (quantity - deliveredQuantity - inProgressQuantity),
 * 返回 > 0 的产品 + 推荐起产量 + 推荐生产线.</p>
 *
 * <p>"未完成 SO" 定义: status IN (CONFIRMED / FINANCE_APPROVED / PROCESSING / PARTIAL_DELIVERED).</p>
 *
 * <p>"在产数量" 定义: 同 productTypeId 的 PLANNED / PLANNING / IN_PROGRESS / PRODUCING 批次的
 * (plannedQuantity - actualQuantity) 之和 — 即已计划但未交付的产量.</p>
 *
 * <p>防呆 R2: 每行带 productName + 待产量 + 推荐数量 + 推荐生产线 + 推荐排程日 — AI 输出可直接用
 * 不需用户追问.</p>
 */
@Slf4j
@Component
public class ProductionDemandQueryTool extends AbstractBusinessTool {

    private static final Set<SalesOrderStatus> ACTIVE_SO_STATUSES = Set.of(
            SalesOrderStatus.CONFIRMED,
            SalesOrderStatus.FINANCE_APPROVED,
            SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED
    );

    private static final Set<ProductionBatchStatus> IN_PROGRESS_BATCH_STATUSES = Set.of(
            ProductionBatchStatus.PLANNED,
            ProductionBatchStatus.PLANNING,
            ProductionBatchStatus.IN_PROGRESS,
            ProductionBatchStatus.PRODUCING,
            ProductionBatchStatus.PAUSED
    );

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesOrderItemRepository salesOrderItemRepository;

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Autowired
    private ProductionLineRepository productionLineRepository;

    @Autowired
    private ProductionLineCapacityConfigRepository capacityConfigRepository;

    /** 每班按 8 小时折算班次 (工厂没有班时配置, 口径写进回复)。 */
    static final BigDecimal SHIFT_HOURS = new BigDecimal("8");

    @Override
    public String getToolName() {
        return "production_demand_query";
    }

    @Override
    public String getDescription() {
        return "查询当前工厂所有缺产订单 — 列出 (SO 总量 - 已发货 - 在产) > 0 的产品 + 推荐起产量 + "
                + "产线 + 所需人数与班次 (按产线标准人数、小时产能折算). 用户问 '今天要起产什么?' / '订单缺产' / "
                + "'排产建议' / '什么订单缺货要做' / '这几个产品各需要多少人、排几个班' 时调用. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "maxRows", Map.of(
                                "type", "integer",
                                "description", "返回最多多少行 (默认 20)"
                        ),
                        "productNames", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "只看这些产品 (取自上一轮回答里列出的产品名, 如「这三个产品」); 不传则看全部缺产产品"
                        )
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("production", "demand", "planning");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        int maxRows = Optional.ofNullable(getInteger(params, "maxRows", 20)).orElse(20);
        if (maxRows <= 0 || maxRows > 100) {
            maxRows = 20;
        }

        // 1. 获取所有 active SO + 行项目
        List<SalesOrder> activeOrders = salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                factoryId, ACTIVE_SO_STATUSES, PageRequest.of(0, 500));
        if (activeOrders.isEmpty()) {
            return buildSimpleResult("当前无未完成销售订单, 无需排产", buildEmptyEnvelope());
        }

        // 2. 聚合各产品的 (quantity - deliveredQuantity)
        Map<String, ProductDemand> demandByProduct = new LinkedHashMap<>();
        for (SalesOrder so : activeOrders) {
            List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(so.getId());
            for (SalesOrderItem item : items) {
                if (item.getProductTypeId() == null) continue;
                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                BigDecimal delivered = item.getDeliveredQuantity() != null
                        ? item.getDeliveredQuantity() : BigDecimal.ZERO;
                BigDecimal pending = qty.subtract(delivered);
                if (pending.compareTo(BigDecimal.ZERO) <= 0) continue;

                ProductDemand pd = demandByProduct.computeIfAbsent(
                        item.getProductTypeId(),
                        k -> new ProductDemand(k, item.getProductName(),
                                item.getUnit() != null ? item.getUnit() : "kg"));
                pd.addOrderLine(so, pending);
            }
        }

        if (demandByProduct.isEmpty()) {
            return buildSimpleResult("所有未完成订单已全部发货, 无需排产",
                    buildEmptyEnvelope());
        }

        // 3. 减去在产数量 (PLANNED/IN_PROGRESS 批次的 plannedQuantity - actualQuantity)
        deductInProgressProduction(factoryId, demandByProduct);

        // 4. 过滤 net > 0, 排序 + 限制行数
        List<ProductDemand> shortage = demandByProduct.values().stream()
                .filter(pd -> pd.getNetShortage().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(ProductDemand::getNetShortage).reversed())
                .collect(Collectors.toList());

        if (shortage.isEmpty()) {
            return buildSimpleResult("所有缺货订单已有在产批次覆盖, 无需新排产",
                    buildEmptyEnvelope());
        }

        // 4b. 指定产品 (承接上一轮的「这几个产品」): 匹配不上就列出候选让用户选, 不猜
        List<String> wanted = requestedProductNames(params);
        List<ProductDemand> nonEmpty;
        if (wanted.isEmpty()) {
            nonEmpty = shortage.stream().limit(maxRows).collect(Collectors.toList());
        } else {
            nonEmpty = shortage.stream()
                    .filter(pd -> matchesAny(pd.getProductName(), wanted))
                    .limit(maxRows)
                    .collect(Collectors.toList());
            if (nonEmpty.isEmpty()) {
                String names = shortage.stream().limit(10)
                        .map(pd -> pd.getProductName() != null ? pd.getProductName() : "(未命名)")
                        .collect(Collectors.joining("、"));
                Map<String, Object> data = buildEmptyEnvelope();
                data.put("needProductChoice", true);
                return buildSimpleResult("没有找到您指的产品。最近待排产的产品有：" + names
                        + "。请告诉我是哪几个。", data);
            }
        }

        // 5. 构建结果 (人数/班次按产线配置折算)
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        text.append("建议安排 ").append(nonEmpty.size()).append(" 个产品生产。\n\n")
                .append("| 产品 | 排多少 | 几人几班 | 最早交期 |\n")
                .append("| --- | --- | --- | --- |\n");
        StringBuilder details = new StringBuilder("\n客户与订单\n");
        for (ProductDemand pd : nonEmpty) {
            Map<String, Object> row = toRow(factoryId, pd);
            rows.add(row);
            appendScheduleRow(text, details, pd, row);
        }
        text.append(details).append("\n（排产量已扣除发货量和在产量。人数按产线配置，班次按每班 ")
                .append(SHIFT_HOURS.toPlainString()).append(" 小时估算。）");

        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", nonEmpty.size());
        data.put("products", rows);
        return buildSimpleResult(text.toString(), data);
    }

    private List<String> requestedProductNames(Map<String, Object> params) {
        Object raw = params.get("productNames");
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，、;；]")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        return out;
    }

    private boolean matchesAny(String productName, List<String> wanted) {
        if (productName == null) return false;
        for (String w : wanted) {
            if (productName.contains(w) || w.contains(productName)) return true;
        }
        return false;
    }

    /** 数量与单位紧挨: 「2000 盒」。 */
    static String qtyWithUnit(BigDecimal qty, String unit) {
        String n = qty == null ? "0" : qty.stripTrailingZeros().toPlainString();
        return unit == null || unit.isBlank() ? n : n + " " + unit;
    }

    @SuppressWarnings("unchecked")
    private void appendScheduleRow(StringBuilder summary, StringBuilder details, ProductDemand demand,
                                   Map<String, Object> row) {
        List<PendingOrder> orders = demand.pendingOrders.values().stream()
                .sorted(Comparator.comparing(PendingOrder::deliveryDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String name = (String) row.get("productName");
        Object workers = row.get("requiredWorkersPerShift");
        Object shifts = row.get("requiredShifts");
        summary.append("| ").append(tableCell(name)).append(" | ")
                .append(tableCell(qtyWithUnit(demand.getNetShortage(), demand.getUnit())))
                .append(" | ").append(shifts == null ? "班次待估" : shifts + " 个班")
                .append("、").append(workers == null ? "人数待定" : "每班 " + workers + " 人")
                .append(" | ").append(Objects.toString(orders.get(0).deliveryDate(), "未填交期")).append(" |\n");

        details.append('\n').append(name).append("：");
        for (int i = 0; i < Math.min(2, orders.size()); i++) {
            PendingOrder order = orders.get(i);
            if (i > 0) details.append("；");
            if (order.customerName() != null && !order.customerName().isBlank()) {
                details.append(order.customerName()).append(' ');
            }
            details.append(Objects.toString(order.orderNumber(), "未填单号")).append(" 待交 ")
                    .append(qtyWithUnit(order.quantity(), demand.getUnit()))
                    .append("（").append(Objects.toString(order.deliveryDate(), "未填交期")).append("）");
        }
        if (orders.size() > 2) details.append("；另 ").append(orders.size() - 2).append(" 单");
        details.append("。产线：").append(row.get("recommendedLineName"));
        List<String> missing = (List<String>) row.get("staffingMissing");
        if (missing != null && !missing.isEmpty()) {
            details.append("（缺").append(String.join("、", missing)).append("）");
        }
        details.append("。");
        if (Set.of("盒", "袋", "件", "包", "箱", "个").contains(demand.getUnit())
                && (demand.getNetShortage().stripTrailingZeros().scale() > 0
                    || orders.stream().limit(2).anyMatch(order -> order.quantity().stripTrailingZeros().scale() > 0))) {
            details.append("小数来自订单待交量和在产量的原始记录。");
        }
        details.append('\n');
    }

    private static String tableCell(String value) {
        return value.replace("|", "\\|").replaceAll("\\R", " ");
    }

    private void deductInProgressProduction(String factoryId, Map<String, ProductDemand> map) {
        // 近 30 天创建 + 状态为 active 的 batch
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<ProductionBatch> recent = productionBatchRepository
                .findByFactoryIdAndCreatedAtAfter(factoryId, cutoff);
        for (ProductionBatch b : recent) {
            if (b.getProductTypeId() == null) continue;
            if (!IN_PROGRESS_BATCH_STATUSES.contains(b.getStatus())) continue;
            ProductDemand pd = map.get(b.getProductTypeId());
            if (pd == null) continue;
            BigDecimal planned = b.getPlannedQuantity() != null
                    ? b.getPlannedQuantity() : BigDecimal.ZERO;
            BigDecimal actual = b.getActualQuantity() != null
                    ? b.getActualQuantity() : BigDecimal.ZERO;
            BigDecimal remaining = planned.subtract(actual).max(BigDecimal.ZERO);
            pd.addInProgress(remaining);
        }
    }

    private Map<String, Object> toRow(String factoryId, ProductDemand pd) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productTypeId", pd.getProductTypeId());
        row.put("productName", pd.getProductName() != null ? pd.getProductName() : "(未命名)");
        row.put("unit", pd.getUnit());
        row.put("totalDemand", pd.getTotalDemand());
        row.put("inProgressQuantity", pd.getInProgressQuantity());
        row.put("netShortage", pd.getNetShortage());
        row.put("recommendedQuantity", pd.getNetShortage());  // 默认推荐 = 净缺量
        row.put("orderCount", pd.getOrderLineCount());
        row.put("orderRefs", pd.getOrderRefs());              // 最多 5 个 (productName + orderNumber)
        applyStaffing(factoryId, pd, row);
        return row;
    }

    /**
     * 产线 + 人数 + 班次: 取与订单单位一致的小时产能 (产品专用行优先于通用行, 多行取最快),
     * 产线名与标准人数来自产线配置; 缺什么就如实列出缺什么, 不套默认值、不外露内部码。
     */
    private void applyStaffing(String factoryId, ProductDemand pd, Map<String, Object> row) {
        List<String> missing = new ArrayList<>();
        row.put("recommendedLineName", "未配置产线");
        row.put("staffingMissing", missing);
        row.put("staffingBasis", "所需班次 = 待产量 ÷ 产线小时产能 ÷ " + SHIFT_HOURS.toPlainString()
                + " 小时/班 (向上取整)；每班人数 = 产线标准人数");

        List<ProductionLineCapacityConfig> effective = capacityConfigRepository
                .findEffectiveForProduct(factoryId, pd.getProductTypeId(), LocalDateTime.now());
        List<ProductionLineCapacityConfig> sameUnit = new ArrayList<>();
        for (ProductionLineCapacityConfig c : effective) {
            if (c.getCapacityPerHour() != null && c.getCapacityPerHour().signum() > 0
                    && unitMatches(c, pd.getUnit())) {
                sameUnit.add(c);
            }
        }
        boolean hasSpecific = sameUnit.stream().anyMatch(c -> c.getProductTypeId() != null);
        ProductionLineCapacityConfig best = sameUnit.stream()
                .filter(c -> !hasSpecific || c.getProductTypeId() != null)
                .max(Comparator.comparing(ProductionLineCapacityConfig::getCapacityPerHour))
                .orElse(null);
        if (best == null) {
            missing.add("该产品按「" + pd.getUnit() + "」计的产线小时产能");
            return;
        }

        ProductionLine line = productionLineRepository
                .findByIdAndFactoryIdAndDeletedAtIsNull(best.getProductionLineId(), factoryId)
                .orElse(null);
        String lineName = line != null && line.getName() != null && !line.getName().isBlank()
                ? ProductionLineLabels.display(line.getName()) : "未命名产线";
        row.put("recommendedLineName", lineName);

        BigDecimal hours = pd.getNetShortage().divide(best.getCapacityPerHour(), 1, RoundingMode.UP);
        BigDecimal shifts = hours.divide(SHIFT_HOURS, 0, RoundingMode.CEILING).max(BigDecimal.ONE);
        row.put("requiredLineHours", hours);
        row.put("requiredShifts", shifts.intValue());

        Integer standard = line != null ? line.getStandardWorkers() : null;
        if (standard == null || standard <= 0) {
            missing.add("产线「" + lineName + "」的标准人数");
        } else {
            row.put("requiredWorkersPerShift", standard);
            row.put("requiredPersonShifts", standard * shifts.intValue());
        }
    }

    private boolean unitMatches(ProductionLineCapacityConfig c, String orderUnit) {
        if (orderUnit == null || c.getCapacityUnit() == null) return false;
        String u = orderUnit.trim().toLowerCase();
        return switch (c.getCapacityUnit()) {
            case KG -> Set.of("kg", "千克", "公斤").contains(u);
            case PIECE -> Set.of("件", "个", "pcs", "piece").contains(u);
            case BATCH -> Set.of("批", "batch").contains(u);
            case PACKAGE -> c.getPackageUnit() != null && c.getPackageUnit().trim().equalsIgnoreCase(u);
        };
    }

    private Map<String, Object> buildEmptyEnvelope() {
        Map<String, Object> data = new HashMap<>();
        data.put("totalCount", 0);
        data.put("products", List.of());
        return data;
    }

    private record PendingOrder(String orderNumber, String customerName, LocalDate deliveryDate,
                                BigDecimal quantity) {}

    /** 单一产品的需求聚合状态. */
    private static class ProductDemand {
        private final String productTypeId;
        private final String productName;
        private final String unit;
        private BigDecimal totalDemand = BigDecimal.ZERO;
        private BigDecimal inProgressQuantity = BigDecimal.ZERO;
        private final List<Map<String, Object>> orderRefs = new ArrayList<>();
        private final Map<String, PendingOrder> pendingOrders = new LinkedHashMap<>();
        private int orderLineCount = 0;

        ProductDemand(String productTypeId, String productName, String unit) {
            this.productTypeId = productTypeId;
            this.productName = productName;
            this.unit = unit;
        }

        void addOrderLine(SalesOrder so, BigDecimal pending) {
            totalDemand = totalDemand.add(pending);
            orderLineCount++;
            pendingOrders.merge(so.getId(),
                    new PendingOrder(so.getOrderNumber(), so.getCustomerName(), so.getRequiredDeliveryDate(), pending),
                    (a, b) -> new PendingOrder(a.orderNumber(), a.customerName(), a.deliveryDate(),
                            a.quantity().add(b.quantity())));
            if (orderRefs.size() < 5) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("salesOrderId", so.getId());
                ref.put("orderNumber", so.getOrderNumber());
                ref.put("customerName", so.getCustomerName());
                ref.put("pendingQuantity", pending);
                ref.put("requiredDeliveryDate", so.getRequiredDeliveryDate());
                orderRefs.add(ref);
            }
        }

        void addInProgress(BigDecimal qty) {
            inProgressQuantity = inProgressQuantity.add(qty);
        }

        BigDecimal getNetShortage() {
            return totalDemand.subtract(inProgressQuantity).max(BigDecimal.ZERO);
        }

        public String getProductTypeId() { return productTypeId; }
        public String getProductName() { return productName; }
        public String getUnit() { return unit; }
        public BigDecimal getTotalDemand() { return totalDemand; }
        public BigDecimal getInProgressQuantity() { return inProgressQuantity; }
        public int getOrderLineCount() { return orderLineCount; }
        public List<Map<String, Object>> getOrderRefs() { return orderRefs; }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
