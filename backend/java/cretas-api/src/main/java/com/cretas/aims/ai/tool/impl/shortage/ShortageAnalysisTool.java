package com.cretas.aims.ai.tool.impl.shortage;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.shortage.ShortageAnalysisService;
import com.cretas.aims.service.shortage.dto.ProcurementSuggestion;
import com.cretas.aims.service.shortage.dto.ProductionPlanSuggestion;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S-MRP-1 销售订单缺料分析 (Sprint 2 Track E — N31).
 *
 * <p>客户原话 (六扇门 F006): "销售单审核通过 → 系统自动判断库存 → 缺料则分流采购,
 * 否则直接生产 → 输出 chain-card UI"。Tool 入口让用户/AI 通过自然语言查询任意销售单的缺料。
 *
 * <p>典型调用:
 * <ul>
 *   <li>"F006 销售单 SO-001 缺什么?"</li>
 *   <li>"这单缺多少原料?"</li>
 *   <li>"库存够吗?"</li>
 * </ul>
 *
 * <p>{@code @Lazy} 注入 {@link ShortageAnalysisService} — 防御性, 避免 Tool 框架/AI 子链
 * 启动期间形成隐式循环依赖。Service 本身不依赖任何 AI 组件, 这里 @Lazy 仅做保险。
 */
@Slf4j
@Component
public class ShortageAnalysisTool extends AbstractBusinessTool {

    @Autowired
    @Lazy
    private ShortageAnalysisService shortageService;

    @Autowired
    @Lazy
    private SalesService salesService;

    /** 全厂缺料总览最多分析的在途订单数 (按交期最近的先查)。 */
    static final int OVERVIEW_ORDER_LIMIT = 20;
    private static final Set<SalesOrderStatus> NOT_OPEN = Set.of(SalesOrderStatus.DRAFT, SalesOrderStatus.COMPLETED,
            SalesOrderStatus.CANCELLED, SalesOrderStatus.SHORT_CLOSED, SalesOrderStatus.FINANCE_REJECTED);

    @Override
    public String getToolName() {
        return "shortage_analyze";
    }

    @Override
    public String getDescription() {
        return "分析销售订单的缺料情况, 返回需要采购的物料 + 推荐的生产任务 + chain-card 渲染提示。"
             + "适用场景: 销售单审批后或 AI 用户问 '这单缺什么 / 缺多少 / 库存够吗'。"
             + "数据为 read-only 快照, 不创建采购单/生产计划。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> salesOrderId = new HashMap<>();
        salesOrderId.put("type", "string");
        salesOrderId.put("description", "销售订单编号或订单号。"
                + "若用户只提了订单号 (如 'SO-001'), 必要时先按订单号找到订单再分析。");

        Map<String, Object> properties = new HashMap<>();
        properties.put("salesOrderId", salesOrderId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // 不给订单号 = 看全厂在途订单哪些缺料、缺什么
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
                                            Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String salesOrderId = getString(params, "salesOrderId");
        if (salesOrderId == null || salesOrderId.isBlank()) {
            return overview(factoryId);
        }
        log.info("[shortage_analyze] factoryId={}, salesOrderId={}", factoryId, salesOrderId);

        ShortageReport report = shortageService.analyzeForSalesOrder(factoryId, salesOrderId);
        List<ProcurementSuggestion> procurement = shortageService.suggestProcurement(factoryId, report);
        List<ProductionPlanSuggestion> production = shortageService.suggestProduction(factoryId, report);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("salesOrderId", salesOrderId);
        result.put("analysisStatus", report.getAnalysisStatus());
        result.put("fullySatisfied", report.isFullySatisfied());
        result.put("finishedGoodsLineItems",
                report.getFinishedGoodsLineItems() != null ? report.getFinishedGoodsLineItems() : Collections.emptyList());
        result.put("totalRequired",
                report.getTotalRequired() != null ? report.getTotalRequired() : Collections.emptyList());
        result.put("shortage",
                report.getMaterialShortages() != null ? report.getMaterialShortages() : Collections.emptyList());
        result.put("procurementSuggestions", procurement);
        result.put("productionSuggestions", production);
        result.put("analysisSummary", report.getSummary());
        // 前端 (RN AIChat) 识别此 hint 自动渲染 <ShortageChainCard> 而非纯文字 reply
        result.put("displayHint", "chain-card");
        return result;
    }

    /**
     * 没指定订单: 把在途订单 (交期最近的 OVERVIEW_ORDER_LIMIT 单) 逐单做缺料分析,
     * 只列有缺口的订单 + 缺的物料名称与数量。分析失败的订单计数后明说, 不当成「不缺」。
     */
    Map<String, Object> overview(String factoryId) {
        List<SalesOrder> open = new ArrayList<>();
        for (SalesOrder order : salesService.getSalesOrders(factoryId, 1, 100).getContent()) {
            if (order.getStatus() != null && !NOT_OPEN.contains(order.getStatus())) {
                open.add(order);
            }
        }
        open.sort(Comparator.comparing(SalesOrder::getRequiredDeliveryDate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<SalesOrder> checked = open.size() > OVERVIEW_ORDER_LIMIT ? open.subList(0, OVERVIEW_ORDER_LIMIT) : open;
        List<Map<String, Object>> rows = new ArrayList<>();
        int failed = 0;
        for (SalesOrder order : checked) {
            try {
                ShortageReport report = shortageService.analyzeForSalesOrder(factoryId, order.getId());
                if (report.getMaterialShortages() == null || report.getMaterialShortages().isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> materials = new ArrayList<>();
                for (var m : report.getMaterialShortages()) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("materialName", m.getMaterialTypeName());
                    mm.put("shortfallQuantity", m.getShortfallQuantity());
                    mm.put("unit", m.getQuantityUnit());
                    materials.add(mm);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderNumber", order.getOrderNumber());
                row.put("customerName", order.getCustomerName());
                row.put("requiredDeliveryDate", order.getRequiredDeliveryDate() == null ? null : order.getRequiredDeliveryDate().toString());
                row.put("shortages", materials);
                rows.add(row);
            } catch (RuntimeException e) {
                failed++;
                log.warn("[shortage_analyze] 总览中订单 {} 分析失败: {}", order.getOrderNumber(), e.toString());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("orderShortages", rows);
        result.put("openOrderCount", open.size());
        result.put("checkedOrderCount", checked.size());
        result.put("failedOrderCount", failed);
        return result;
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
        return java.util.Set.of("procurement:read", "procurement:read_write", "production:read", "production:read_write", "quality:read", "quality:read_write", "sales:read", "sales:read_write", "warehouse:read", "warehouse:read_write");
    }
}
