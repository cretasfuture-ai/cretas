package com.cretas.aims.service.trace;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.inventory.SalesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按销售单追溯（对话层）：销售单 → 生产计划 → 生产批次 → 每批次完整溯源。
 *
 * <p>链路与 web 溯源页「销售单」入口同源：计划取自 source_order_id / source_order_ids 两路，
 * 批次取计划下的正式批次，每个批次复用 {@link TraceabilityService#getFullTrace}（原料含供应商、工序、
 * 质检、出货含客户），工艺保密的裁剪也留在那一层，本类不另开口子。
 *
 * <p>销售单本身经 {@link SalesService#getSalesOrderById} 取，带数据范围校验；越权直接抛 403，不降级。
 * 文字里不写数量配比（保密开关下这些数在 getFullTrace 里会被裁剪，这里一律不展示）。
 */
@Service
@RequiredArgsConstructor
public class SalesOrderTraceService {

    /** 最多逐批展开的生产批次数；超出如实说明，不静默截断。 */
    static final int MAX_BATCHES = 8;

    /** 销售单号形态：含独立的 SO 段，例如 SO-20261105-001、DEMO-20261105-SO-001。 */
    private static final Pattern SALES_ORDER_NO = Pattern.compile("(?i)(^|[-_])SO[-_][A-Za-z0-9]");

    private final SalesOrderRepository salesOrderRepository;
    private final SalesService salesService;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final TraceabilityService traceabilityService;

    public static boolean looksLikeSalesOrderNo(String value) {
        return value != null && SALES_ORDER_NO.matcher(value.trim()).find();
    }

    /**
     * 这个「批次号」参数其实是不是销售单号。真有这个批次号的生产批次时永远按批次走（批次号不受影响）；
     * 没有对应批次时，形态像销售单号或库里确有这张销售单，才按销售单走。
     */
    public boolean isSalesOrderTarget(String factoryId, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String no = value.trim();
        if (productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, no).isPresent()) {
            return false;
        }
        return looksLikeSalesOrderNo(no)
                || salesOrderRepository.findByFactoryIdAndOrderNumber(factoryId, no).isPresent();
    }

    public Map<String, Object> trace(String factoryId, String salesOrderNo) {
        String no = salesOrderNo == null ? "" : salesOrderNo.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("salesOrderNo", no);

        Optional<SalesOrder> found = salesOrderRepository.findByFactoryIdAndOrderNumber(factoryId, no);
        if (found.isEmpty()) {
            result.put("success", false);
            result.put("found", false);
            result.put("message", "没有查到销售单 " + no + "，请核对单号是否正确。");
            return result;
        }
        // 数据范围校验：越权抛 BusinessException(403)，交给 Tool 基类如实报「无权」
        SalesOrder order;
        try {
            order = salesService.getSalesOrderById(factoryId, found.get().getId());
        } catch (ResourceNotFoundException e) {
            result.put("success", false);
            result.put("found", false);
            result.put("message", "没有查到销售单 " + no + "，请核对单号是否正确。");
            return result;
        }

        List<ProductionPlan> plans = loadPlans(factoryId, order.getId());
        List<ProductionBatch> batches = plans.isEmpty()
                ? List.of()
                : productionBatchRepository.findByFactoryIdAndProductionPlanIdIn(
                        factoryId, plans.stream().map(ProductionPlan::getId).toList());

        List<TraceabilityDTO.FullTraceResponse> traces = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int shown = Math.min(batches.size(), MAX_BATCHES);
        for (int i = 0; i < shown; i++) {
            String batchNumber = batches.get(i).getBatchNumber();
            TraceabilityDTO.FullTraceResponse full = traceabilityService.getFullTrace(factoryId, batchNumber);
            if (full == null) {
                unreadable.add(batchNumber);
            } else {
                traces.add(full);
            }
        }

        result.put("success", true);
        result.put("found", true);
        result.put("planCount", plans.size());
        result.put("batchCount", batches.size());
        result.put("message", render(order, plans, batches, batches.size(), traces, unreadable));
        return result;
    }

    private List<ProductionPlan> loadPlans(String factoryId, String salesOrderId) {
        Set<String> seen = new LinkedHashSet<>();
        List<ProductionPlan> plans = new ArrayList<>();
        addAll(plans, seen, productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, salesOrderId));
        String json = "[\"" + salesOrderId.replace("\"", "\\\"") + "\"]";
        addAll(plans, seen, productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(factoryId, json));
        return plans;
    }

    private static void addAll(List<ProductionPlan> target, Set<String> seen, Collection<ProductionPlan> source) {
        if (source == null) {
            return;
        }
        for (ProductionPlan plan : source) {
            if (plan != null && seen.add(plan.getId())) {
                target.add(plan);
            }
        }
    }

    // ==================== 文字 ====================

    static String render(SalesOrder order, List<ProductionPlan> plans, List<ProductionBatch> batches, int batchTotal,
                         List<TraceabilityDTO.FullTraceResponse> traces, List<String> unreadable) {
        StringBuilder sb = new StringBuilder();
        sb.append("销售单 ").append(order.getOrderNumber());
        if (notBlank(order.getCustomerName())) {
            sb.append("（客户：").append(order.getCustomerName().trim()).append("）");
        }
        sb.append(" 的追溯链路：\n");

        if (batchTotal == 0) {
            sb.append("\n这张销售单还没有关联到生产批次，所以暂时追溯不到原料、工序、质检和出货。");
            return sb.toString();
        }

        // 一、生产计划（计划号 / 产品 / 计划量）。产品名取自该计划下批次的溯源明细。
        Map<String, String> productByPlan = new LinkedHashMap<>();
        for (ProductionBatch b : batches) {
            for (TraceabilityDTO.FullTraceResponse t : traces) {
                TraceabilityDTO.ProductionInfo p = t.getProduction();
                if (p != null && notBlank(p.getProductName()) && Objects.equals(p.getBatchNumber(), b.getBatchNumber())
                        && b.getProductionPlanId() != null) {
                    productByPlan.putIfAbsent(b.getProductionPlanId(), p.getProductName().trim());
                }
            }
        }
        sb.append("\n一、生产计划（").append(plans.size()).append(" 个）\n");
        for (ProductionPlan plan : plans) {
            sb.append("- 计划 ").append(notBlank(plan.getPlanNumber()) ? plan.getPlanNumber().trim() : "（未编号）");
            String product = productByPlan.get(plan.getId());
            if (product != null) {
                sb.append("，产品 ").append(product);
            }
            if (plan.getPlannedQuantity() != null) {
                sb.append("，计划量 ").append(plan.getPlannedQuantity().stripTrailingZeros().toPlainString())
                        .append(notBlank(plan.getPlannedUnit()) ? plan.getPlannedUnit().trim() : "");
            }
            sb.append("\n");
        }

        // 二、原料批次（跨生产批次按原料批次号去重）
        Map<String, TraceabilityDTO.MaterialInfo> materials = new LinkedHashMap<>();
        for (TraceabilityDTO.FullTraceResponse t : traces) {
            if (t.getMaterials() == null) {
                continue;
            }
            for (TraceabilityDTO.MaterialInfo m : t.getMaterials()) {
                String key = notBlank(m.getBatchNumber()) ? m.getBatchNumber().trim()
                        : "#" + materials.size();
                materials.putIfAbsent(key, m);
            }
        }
        sb.append("\n二、原料批次（").append(materials.size()).append(" 批）\n");
        if (materials.isEmpty()) {
            sb.append("没有查到原料领用记录。\n");
        }
        int n = 1;
        for (TraceabilityDTO.MaterialInfo m : materials.values()) {
            sb.append(n++).append(". ");
            sb.append(notBlank(m.getMaterialName()) ? m.getMaterialName().trim() : "原料");
            if (notBlank(m.getBatchNumber())) {
                sb.append("，批次 ").append(m.getBatchNumber().trim());
            }
            if (notBlank(m.getSupplierName())) {
                sb.append("，供应商 ").append(m.getSupplierName().trim());
            }
            sb.append("\n");
        }

        // 二、生产批次与工序
        sb.append("\n三、生产批次与工序（共 ").append(batchTotal).append(" 个批次");
        if (batchTotal > traces.size() + unreadable.size()) {
            sb.append("，下面只展开前 ").append(traces.size() + unreadable.size()).append(" 个");
        }
        sb.append("）\n");
        for (TraceabilityDTO.FullTraceResponse t : traces) {
            TraceabilityDTO.ProductionInfo p = t.getProduction();
            sb.append("- 批次 ").append(p != null && notBlank(p.getBatchNumber()) ? p.getBatchNumber() : "（未编号）");
            if (p != null && notBlank(p.getProductName())) {
                sb.append("，产品 ").append(p.getProductName().trim());
            }
            List<String> stages = stageNames(t.getProcessingStages());
            sb.append(stages.isEmpty()
                    ? "，没有查到工序记录"
                    : "，工序 " + stages.size() + " 道：" + String.join(" → ", stages));
            sb.append("\n");
        }
        for (String b : unreadable) {
            sb.append("- 批次 ").append(b).append("，这个批次的溯源明细暂时读不到\n");
        }

        // 三、质检
        sb.append("\n四、质检\n");
        boolean anyQuality = false;
        for (TraceabilityDTO.FullTraceResponse t : traces) {
            if (t.getQualityInspections() == null) {
                continue;
            }
            for (TraceabilityDTO.QualityInfo q : t.getQualityInspections()) {
                anyQuality = true;
                String batchNo = t.getProduction() != null ? t.getProduction().getBatchNumber() : null;
                sb.append("- ").append(notBlank(batchNo) ? "批次 " + batchNo + "：" : "")
                        .append(qualityResult(q.getResult()));
                if (q.getPassCount() != null && q.getSampleSize() != null && q.getSampleSize() > 0) {
                    // 件数与合格率同给: 「32/32 合格」比单独一个百分比更能核对
                    sb.append("，").append(trim(q.getPassCount())).append("/").append(trim(q.getSampleSize())).append(" 合格");
                }
                if (q.getPassRate() != null) {
                    sb.append("，合格率 ").append(trim(q.getPassRate())).append("%");
                }
                if (notBlank(q.getInspectorName())) {
                    sb.append("，检验员 ").append(q.getInspectorName().trim());
                }
                sb.append("\n");
            }
        }
        if (!anyQuality) {
            sb.append("没有查到质检记录。\n");
        }

        // 四、出货与客户
        sb.append("\n五、出货与客户\n");
        Set<String> shipmentSeen = new LinkedHashSet<>();
        boolean anyShipment = false;
        for (TraceabilityDTO.FullTraceResponse t : traces) {
            if (t.getShipments() == null) {
                continue;
            }
            for (TraceabilityDTO.ShipmentInfo s : t.getShipments()) {
                String key = Objects.toString(s.getShipmentNumber(), "") + "|" + Objects.toString(s.getCustomerName(), "");
                if (!shipmentSeen.add(key)) {
                    continue;
                }
                anyShipment = true;
                sb.append("- 出货单 ").append(notBlank(s.getShipmentNumber()) ? s.getShipmentNumber().trim() : "（未编号）");
                if (notBlank(s.getCustomerName())) {
                    sb.append("，客户 ").append(s.getCustomerName().trim());
                }
                if (s.getShipmentDate() != null) {
                    sb.append("，出货日期 ").append(s.getShipmentDate());
                }
                if (s.getQuantity() != null) {
                    sb.append("，").append(trim(s.getQuantity())).append(notBlank(s.getUnit()) ? s.getUnit().trim() : "");
                }
                String status = shipmentStatus(s.getStatus());
                if (status != null) {
                    sb.append("，").append(status);
                }
                sb.append("\n");
            }
        }
        if (!anyShipment) {
            sb.append("还没有查到出货记录。\n");
        }
        return sb.toString().stripTrailing();
    }

    private static List<String> stageNames(List<TraceabilityDTO.ProcessingStageInfo> stages) {
        List<String> names = new ArrayList<>();
        if (stages == null) {
            return names;
        }
        for (TraceabilityDTO.ProcessingStageInfo s : stages) {
            if (notBlank(s.getStageName())) {
                names.add(s.getStageName().trim());
            }
        }
        return names;
    }

    private static String qualityResult(String result) {
        if (!notBlank(result)) {
            return "结论未记录";
        }
        String r = result.trim();
        return switch (r.toLowerCase(Locale.ROOT)) {
            case "pass", "passed" -> "合格";
            case "fail", "failed" -> "不合格";
            case "pending" -> "待检验";
            case "conditional", "conditional_pass" -> "让步放行";
            default -> isAscii(r) ? "结论未记录" : r;
        };
    }

    private static String shipmentStatus(String status) {
        if (!notBlank(status)) {
            return null;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "pending" -> "待发货";
            case "shipped", "in_transit" -> "已发货";
            case "delivered" -> "已送达";
            case "cancelled" -> "已取消";
            case "returned" -> "已退回";
            default -> isAscii(status.trim()) ? null : status.trim();
        };
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
