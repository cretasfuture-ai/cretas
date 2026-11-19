package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.trace.SalesOrderTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 完整溯源工具
 *
 * 查询指定批次的完整溯源链路，包括从原材料到成品的全过程追踪。
 *
 * Intent Code: TRACE_FULL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class TraceFullTool extends AbstractBusinessTool {

    @Autowired
    private TraceabilityService traceabilityService;

    @Autowired
    private SalesOrderTraceService salesOrderTraceService;

    @Override
    public String getToolName() {
        return "trace_full";
    }

    @Override
    public String getDescription() {
        return "查询批次的完整溯源链路。包括原材料采购、入库检验、生产加工、质检、出库等全流程追踪信息。" +
                "适用场景：需要完整追溯链的质量问题调查、客户溯源要求。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchNumber: 批次号（必需）
        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "要完整溯源的生产批次号（销售单号不要填这里）");
        properties.put("batchNumber", batchNumber);

        // salesOrderNo: 销售单号（按销售单追溯时用，与批次号二选一）
        Map<String, Object> salesOrderNo = new HashMap<>();
        salesOrderNo.put("type", "string");
        salesOrderNo.put("description", "销售单号（如 SO-20261105-001）。用户说「追溯销售单/订单 XXX」时填这里，不要填到 batchNumber");
        properties.put("salesOrderNo", salesOrderNo);

        schema.put("properties", properties);
        // 批次号与销售单号二选一，见 validateRequiredParams
        schema.put("required", Arrays.asList("batchNumber"));
        // 抽参 / 填槽两处都要把销售单号也抽出来 (只认 required 会反问「请提供生产批次号」, 见 ToolParameterGroups)
        schema.put(com.cretas.aims.ai.tool.ToolParameterGroups.ANY_OF_KEY,
                List.of(List.of("batchNumber", "salesOrderNo")));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchNumber");
    }

    /** 批次号或销售单号给一个就算齐。 */
    @Override
    protected List<String> validateRequiredParams(Map<String, Object> params) {
        if (!getString(params, "salesOrderNo", "").isBlank()) {
            return new ArrayList<>();
        }
        return super.validateRequiredParams(params);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行完整溯源 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 获取参数
        String batchNumber = getString(params, "batchNumber", "").trim();
        String salesOrderNo = getString(params, "salesOrderNo", "").trim();

        // 销售单号：明确给了 salesOrderNo，或「批次号」其实是销售单号（没有同号批次时）→ 按销售单追溯
        if (salesOrderNo.isBlank() && salesOrderTraceService.isSalesOrderTarget(factoryId, batchNumber)) {
            salesOrderNo = batchNumber;
        }
        if (!salesOrderNo.isBlank()) {
            Map<String, Object> bySalesOrder = salesOrderTraceService.trace(factoryId, salesOrderNo);
            log.info("按销售单追溯完成 - 销售单号: {}, 找到: {}", salesOrderNo, bySalesOrder.get("found"));
            return bySalesOrder;
        }

        // 调用溯源服务
        TraceabilityDTO.FullTraceResponse traceResponse = traceabilityService.getFullTrace(factoryId, batchNumber);

        Map<String, Object> result = new HashMap<>();
        if (traceResponse == null) {
            // 查不到不能报成功：否则对话里只剩一个批次号，像「查到了但没内容」
            result.put("success", false);
            result.put("found", false);
            result.put("batchNumber", batchNumber);
            result.put("message", "没有查到批次 " + batchNumber + "，请核对批次号；如果这是销售单号，请说「追溯销售单 " + batchNumber + "」。");
            return result;
        }
        result.put("success", true);
        result.put("batchNumber", batchNumber);
        result.put("fullTraceInfo", traceResponse);

        log.info("完整溯源完成 - 批次号: {}", batchNumber);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "batchNumber", "请问要查询哪个批次的完整溯源信息？请提供批次号或销售单号。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "batchNumber", "批次号"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
