package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.BatchReferenceClarifier;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.service.TraceabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批次完整追溯 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 2).
 *
 * <p>查指定 batch 的完整 trace: 原料 + 供应商 + 加工记录 + 出货客户列表.
 * 在 batch_trace_by_customer_date 找到 root batch 后, 用此 Tool 找原料/供应商/影响客户.
 *
 * <p>复用 {@link TraceabilityService#getFullTrace} (已有 full trace 实现) +
 * {@link ShipmentRecordRepository#findByFactoryIdAndBatchNumber} (反查影响客户).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-20260518-A03 全追溯"</li>
 *   <li>"这批的原料和影响客户"</li>
 *   <li>"批次完整追溯"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code BATCH_FULL_TRACE}
 */
@Slf4j
@Component
public class BatchFullTraceTool extends AbstractBusinessTool {

    @Autowired
    private TraceabilityService traceabilityService;

    @Autowired
    private ShipmentRecordRepository shipmentRecordRepository;

    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    @Override
    public String getToolName() {
        return "batch_full_trace";
    }

    @Override
    public String getDescription() {
        return "批次完整追溯 — 原料 + 供应商 + 加工记录 + 受影响客户列表 (食品召回闭环 step 2). "
                + "LLM 触发: 'B-X 全追溯' / '这批的原料和影响客户' / '批次完整追溯' / "
                + "'这批用了哪些原料卖给了谁'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        // ⛔ 描述里不放示例批次号: 模型会把示例当成用户说的批次 (实测: 没给批次号, 它自己选了示例批次去查)
        batchNumber.put("description", "要往回追溯的成品批次号 (必填)");
        properties.put("batchNumber", batchNumber);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    /** 缺批次号时对用户说的话 (不示例批次号: 模型会把示例当成用户说的批次)。 */
    @Override
    protected String getParameterQuestion(String paramName) {
        if ("batchNumber".equals(paramName)) {
            return "请告诉我成品批次号，我帮你往回追原料批次和供应商。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        // 「成品批次 / 这个批次」是泛称不是批次号: 不查, 追问并列最近批次
        if (BatchReferenceClarifier.isGenericReference(batchNumber)) {
            return BatchReferenceClarifier.clarify(productionBatchRepository, factoryId, "往回追原料批次和供应商");
        }
        log.info("batch_full_trace — factory={} batch={}", factoryId, batchNumber);

        // 1. 调 TraceabilityService 拿 full trace (原料 + 供应商 + 加工记录)
        TraceabilityDTO.FullTraceResponse traceResp = null;
        try {
            traceResp = traceabilityService.getFullTrace(factoryId, batchNumber);
        } catch (Exception ex) {
            log.warn("getFullTrace failed batch={}: {}", batchNumber, ex.getMessage());
        }

        // 2. 反查影响客户 (通过 ShipmentRecord.batchNumber 找出货客户)
        List<ShipmentRecord> shipments = shipmentRecordRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);

        Set<String> affectedCustomerIds = new LinkedHashSet<>();
        List<Map<String, Object>> affectedShipments = new ArrayList<>();
        for (ShipmentRecord s : shipments) {
            if (s.getCustomerId() != null) {
                affectedCustomerIds.add(s.getCustomerId());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shipmentNumber", s.getShipmentNumber());
            row.put("customerId", s.getCustomerId());
            row.put("productName", s.getProductName());
            row.put("quantity", s.getQuantity());
            row.put("shipmentDate", s.getShipmentDate() != null
                    ? s.getShipmentDate().toString() : null);
            affectedShipments.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("trace", traceResp);
        data.put("affectedCustomerIds", new ArrayList<>(affectedCustomerIds));
        data.put("affectedCustomerCount", affectedCustomerIds.size());
        data.put("affectedShipments", affectedShipments);
        data.put("affectedShipmentCount", affectedShipments.size());
        data.put("actionHint", "/quality/recall?batchNumber=" + batchNumber);

        String message = traceMessage(batchNumber, traceResp, affectedShipments.size(), affectedCustomerIds.size());
        return buildSimpleResult(message, data);
    }

    /** 回复正文: 先答「用了哪些原料批次、哪些供应商」, 再说出货影响; ⛔ 不指向内部字段名。 */
    static String traceMessage(String batchNumber, TraceabilityDTO.FullTraceResponse trace, int shipmentCount, int customerCount) {
        StringBuilder sb = new StringBuilder();
        List<TraceabilityDTO.MaterialInfo> materials = trace == null ? null : trace.getMaterials();
        if (trace == null) {
            sb.append("批次 ").append(batchNumber).append(" 暂时没查到追溯记录。");
        } else if (materials == null || materials.isEmpty()) {
            sb.append("批次 ").append(batchNumber).append(" 没有登记投料的原料批次。");
        } else {
            sb.append("批次 ").append(batchNumber).append(" 用了 ").append(materials.size()).append(" 个原料批次：\n");
            Set<String> suppliers = new LinkedHashSet<>();
            for (TraceabilityDTO.MaterialInfo m : materials) {
                sb.append("• ").append(m.getMaterialName() == null ? "未命名原料" : m.getMaterialName())
                        .append("（批次 ").append(m.getBatchNumber() == null ? "未登记" : m.getBatchNumber())
                        .append("，供应商 ").append(m.getSupplierName() == null ? "未登记" : m.getSupplierName()).append("）\n");
                if (m.getSupplierName() != null) suppliers.add(m.getSupplierName());
            }
            sb.append("涉及供应商：").append(suppliers.isEmpty() ? "均未登记" : String.join("、", suppliers)).append("。\n");
        }
        sb.append("该批次出货 ").append(shipmentCount).append(" 笔，涉及 ").append(customerCount).append(" 家客户。");
        return sb.toString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
