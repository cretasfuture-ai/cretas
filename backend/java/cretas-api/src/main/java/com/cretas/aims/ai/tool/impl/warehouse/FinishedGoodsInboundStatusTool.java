package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 成品入库了没 (意图 INVENTORY_FINISHED_GOODS_INBOUND_STATUS, 2026-11-17 v3 复核缺口)。
 *
 * <p>复核原话:「这批草莓酸奶包装好后入库了没?」。以前「成品入库」短语落到原料入库 (写), 查询被当成操作。
 *
 * <p>为什么新写而不是绑现有工具: {@code report_inventory} 只给全厂聚合报表, {@code lineage_query} 给血缘图,
 * {@code material_batch_query} 只查原料批次 —— 没有一个能回答「这个生产批次 / 这个产品最近的批次有没有成品入库」。
 * 本工具只读 {@code finished_goods_batches} 与 {@code production_batches}, 不改任何东西。
 *
 * <p>判定: 生产批次有同批号的成品批次, 或同一生产计划下有该产品的成品批次 ⇒ 已入库; 否则未入库, 并给出生产状态
 * (还在生产 / 已完工未入库)。⛔ 不返回单价、成本 (成品批次上的价格字段一律不投影)。
 */
@Slf4j
@Component
public class FinishedGoodsInboundStatusTool extends AbstractBusinessTool {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 60;
    private static final int MAX_ROWS = 20;

    /** 返回给对话的每行字段白名单 (无价格)。 */
    static final Set<String> ROW_FIELDS = Set.of("batchNumber", "productName", "productionStatus", "inbound",
            "inboundQuantity", "unit", "inventoryStatus", "productionDate");

    @Autowired
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired
    private ProductionBatchRepository productionBatchRepository;
    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Override
    public String getToolName() {
        return "finished_goods_inbound_status";
    }

    @Override
    public String getDescription() {
        return "查成品有没有入库: 按批次号, 或按产品名查最近几天的批次, 回答包装/生产完后成品入库了没、入了多少、现在是可用还是待检。只读。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("batchNumber", Map.of("type", "string", "description", "生产批次号或成品批次号"));
        properties.put("productName", Map.of("type", "string", "description", "产品名称, 如 草莓酸奶"));
        properties.put("days", Map.of("type", "integer",
                "description", "按产品查时看最近几天的批次, 默认 " + DEFAULT_DAYS + ", 最多 " + MAX_DAYS));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String batchNumber = trimToNull(getString(params, "batchNumber"));
        String productName = trimToNull(getString(params, "productName"));
        if (batchNumber != null) {
            return byBatchNumber(factoryId, batchNumber);
        }
        if (productName != null) {
            int days = Math.max(1, Math.min(MAX_DAYS, getInteger(params, "days", DEFAULT_DAYS)));
            return byProduct(factoryId, productName, days);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NEED_INPUT");
        return buildSimpleResult("要查哪一批? 告诉我批次号, 或者产品名 (比如「草莓酸奶」)。", result);
    }

    private Map<String, Object> byBatchNumber(String factoryId, String batchNumber) {
        Optional<FinishedGoodsBatch> fg = finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        Optional<ProductionBatch> pb = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", batchNumber);
        if (fg.isEmpty() && pb.isEmpty()) {
            result.put("status", "NOT_FOUND");
            result.put("rows", List.of());
            return buildSimpleResult("本厂没有批次号为 " + batchNumber + " 的生产批次或成品批次, 请核对批次号。", result);
        }
        Map<String, Object> row = pb.isPresent()
                ? rowFor(factoryId, pb.get(), fg.orElse(null))
                : rowFromFinished(fg.get());
        result.put("status", "FOUND");
        result.put("rows", List.of(row));
        return buildSimpleResult(describe(row), result);
    }

    private Map<String, Object> byProduct(String factoryId, String productName, int days) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", productName);
        result.put("days", days);
        List<ProductType> products = productTypeRepository.findByFactoryId(factoryId).stream()
                .filter(p -> p.getName() != null)
                .toList();
        List<ProductType> exact = products.stream().filter(p -> productName.equals(p.getName().trim())).toList();
        List<ProductType> matched = !exact.isEmpty() ? exact
                : products.stream().filter(p -> p.getName().contains(productName)).toList();
        if (matched.isEmpty()) {
            result.put("status", "NOT_FOUND");
            result.put("rows", List.of());
            return buildSimpleResult("本厂没有叫「" + productName + "」的产品, 请核对产品名或直接给批次号。", result);
        }
        Set<String> names = matched.stream().map(ProductType::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        if (names.size() > 1) {
            result.put("status", "NEEDS_SELECTION");
            result.put("candidates", List.copyOf(names));
            return buildSimpleResult("找到多个产品: " + String.join("、", names) + "。你要查的是哪一个?", result);
        }

        LocalDateTime since = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> coveredFinished = new LinkedHashSet<>();
        for (ProductType p : matched) {
            List<ProductionBatch> batches = productionBatchRepository
                    .findByFactoryIdAndProductTypeIdAndCreatedAtAfter(factoryId, p.getId(), since);
            batches.stream()
                    .sorted(Comparator.comparing(ProductionBatch::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .forEach(b -> {
                        FinishedGoodsBatch fg = finishedFor(factoryId, b);
                        if (fg != null) {
                            coveredFinished.add(fg.getId());
                        }
                        rows.add(rowFor(factoryId, b, fg));
                    });
        }
        // 没挂在上面生产批次上的近期成品入库 (例如手工期初入库) 也列出来, 否则「入库了」会被说成「没入库」。
        Set<String> productIds = matched.stream().map(ProductType::getId).collect(Collectors.toSet());
        finishedGoodsBatchRepository.findByFactoryIdOrderByCreatedAtDesc(factoryId, PageRequest.of(0, 200)).stream()
                .filter(f -> productIds.contains(f.getProductTypeId()))
                .filter(f -> !coveredFinished.contains(f.getId()))
                .filter(f -> f.getProductionDate() == null || !f.getProductionDate().isBefore(since.toLocalDate()))
                .forEach(f -> rows.add(rowFromFinished(f)));

        List<Map<String, Object>> shown = rows.stream().limit(MAX_ROWS).toList();
        result.put("status", rows.isEmpty() ? "NO_BATCHES" : "FOUND");
        result.put("total", rows.size());
        result.put("rows", shown);
        if (rows.isEmpty()) {
            return buildSimpleResult("「" + names.iterator().next() + "」最近 " + days
                    + " 天没有生产批次, 也没有成品入库记录。", result);
        }
        long inbound = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("inbound"))).count();
        String head = "「" + names.iterator().next() + "」最近 " + days + " 天 " + rows.size() + " 个批次, "
                + inbound + " 个已入库, " + (rows.size() - inbound) + " 个还没入库:\n";
        return buildSimpleResult(head + shown.stream().map(FinishedGoodsInboundStatusTool::describe)
                .collect(Collectors.joining("\n")), result);
    }

    /** 生产批次对应的成品批次: 同批号优先, 其次同一生产计划下同产品的成品批次。 */
    private FinishedGoodsBatch finishedFor(String factoryId, ProductionBatch b) {
        if (b.getBatchNumber() != null) {
            Optional<FinishedGoodsBatch> same = finishedGoodsBatchRepository
                    .findByFactoryIdAndBatchNumber(factoryId, b.getBatchNumber());
            if (same.isPresent()) {
                return same.get();
            }
        }
        if (b.getProductionPlanId() != null) {
            return finishedGoodsBatchRepository
                    .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, b.getProductionPlanId()).stream()
                    .filter(f -> Objects.equals(f.getProductTypeId(), b.getProductTypeId()))
                    .findFirst().orElse(null);
        }
        return null;
    }

    private Map<String, Object> rowFor(String factoryId, ProductionBatch b, FinishedGoodsBatch knownFinished) {
        FinishedGoodsBatch fg = knownFinished != null ? knownFinished : finishedFor(factoryId, b);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchNumber", b.getBatchNumber());
        row.put("productName", b.getProductName());
        row.put("productionStatus", b.getStatus() == null ? null : b.getStatus().getDescription());
        fillFinished(row, fg);
        return row;
    }

    private static Map<String, Object> rowFromFinished(FinishedGoodsBatch f) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchNumber", f.getBatchNumber());
        row.put("productName", f.getProductName());
        row.put("productionStatus", null);
        fillFinished(row, f);
        return row;
    }

    private static void fillFinished(Map<String, Object> row, FinishedGoodsBatch f) {
        row.put("inbound", f != null);
        row.put("inboundQuantity", f == null ? null : f.getProducedQuantity());
        row.put("unit", f == null ? null : f.getUnit());
        row.put("inventoryStatus", f == null ? null : inventoryStatusLabel(f.getStatus()));
        row.put("productionDate", f == null ? null : f.getProductionDate());
    }

    static String inventoryStatusLabel(String status) {
        if (status == null) {
            return "状态未知";
        }
        return switch (status) {
            case FinishedGoodsBatch.Status.AVAILABLE -> "已入库可用";
            case FinishedGoodsBatch.Status.PENDING_INSPECTION -> "已入库待检";
            case FinishedGoodsBatch.Status.DEFECTIVE -> "已入库, 判不良隔离";
            case FinishedGoodsBatch.Status.FROZEN -> "已入库, 冻结中";
            case FinishedGoodsBatch.Status.DEPLETED -> "已入库, 已全部出库";
            case FinishedGoodsBatch.Status.EXPIRED -> "已入库, 已过期";
            case FinishedGoodsBatch.Status.REVERSED -> "入库已撤回";
            default -> "状态未知(" + status + ")";
        };
    }

    private static String describe(Map<String, Object> row) {
        String name = row.get("productName") == null ? "" : row.get("productName") + " ";
        String head = name + "批次 " + row.get("batchNumber") + ": ";
        if (Boolean.TRUE.equals(row.get("inbound"))) {
            Object qty = row.get("inboundQuantity");
            return head + row.get("inventoryStatus")
                    + (qty == null ? "" : ", 入库 " + ((java.math.BigDecimal) qty).stripTrailingZeros().toPlainString()
                    + (row.get("unit") == null ? "" : " " + row.get("unit")));
        }
        Object ps = row.get("productionStatus");
        return head + "还没有成品入库" + (ps == null ? "" : " (生产状态: " + ps + ")");
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * 成品批次在 App 里经成品库存列表 ({@code SalesController#listFinishedGoods}: 销售/库存/质检读) 与
     * 逐道投料成品库存 ({@code FinishedGoodsInventoryController#listInventory}: production:read) 给到; 取两者之并。
     */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("sales:read_write", "sales:read", "inventory:read", "quality:read", "quality:read_write",
                "production:read");
    }
}
