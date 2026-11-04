package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.gate.GateDiagnosis;
import com.cretas.aims.service.gate.GateDiagnosisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读: 「这批为什么领不了 / 出库为什么卡住」。调用与写路径拒绝同一份闸诊断
 * ({@link GateDiagnosisService}), 只回答原因和解开方式, ⛔ 不代替用户执行放行/处置等解开动作。
 */
@Component
@RequiredArgsConstructor
public class GateDiagnoseBatchTool extends AbstractBusinessTool {

    private final GateDiagnosisService diagnosisService;

    @Override
    public String getToolName() {
        return "gate_diagnose_batch";
    }

    @Override
    public String getDescription() {
        return "诊断某个批次为什么领不了料 / 出不了库 / 投不了料: 返回被哪道闸拦 (待检、过期、状态不可用等)、"
                + "涉及的批次与数量、以及用户该去哪里怎么解开。只读, 不执行任何解开动作。"
                + "适用: 这批为什么领不了、出库为什么卡住、批次 XXX 为什么不能用。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public AccessMode getAccessMode() {
        // 只读诊断: 不声明会按 fail-closed 当写工具, 被 W0 写确认门拦成「需要确认」
        return AccessMode.READ;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("warehouse:read", "warehouse:read_write", "inventory:read", "inventory:read_write");
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("material", "warehouse", "quality");
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> batch = new HashMap<>();
        batch.put("type", "string");
        batch.put("description", "批次号 (原料批次 / 半成品批次 / 成品批次均可)");
        Map<String, Object> props = new HashMap<>();
        props.put("batchNumber", batch);
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) {
        String batchNumber = getString(params, "batchNumber");
        List<GateDiagnosis> found = diagnosisService.diagnoseBatch(factoryId, batchNumber);
        Map<String, Object> result = new HashMap<>();
        result.put("batchNumber", batchNumber);
        result.put("blocked", !found.isEmpty());
        result.put("gateDiagnoses", found);
        result.put("message", found.isEmpty()
                ? "批次 " + batchNumber + " 没有被任何批次层面的闸拦住; 若仍被拒绝, 可能是计划/订单层面的限制 (客户归属、缺料开工、上游工序), 请告知具体计划或订单"
                : describe(batchNumber, found));
        return result;
    }

    /** 面向仓管员的说法: 被哪道闸拦、为什么、怎么解开; 解开动作由用户自己去做, 这里不执行。 */
    static String describe(String batchNumber, List<GateDiagnosis> found) {
        StringBuilder sb = new StringBuilder("批次 ").append(batchNumber)
                .append(" 被 ").append(found.size()).append(" 道闸拦住:");
        int i = 1;
        for (GateDiagnosis d : found) {
            sb.append("\n").append(i++).append(". 「").append(d.gateLabel()).append("」");
            if (d.cause() != null && !d.cause().isBlank()) {
                sb.append(" ").append(d.cause());
            }
            if (d.shortBy() != null && d.shortBy().signum() > 0) {
                String u = d.unit() == null ? "" : d.unit();
                sb.append("，要 ").append(d.required().stripTrailingZeros().toPlainString()).append(u)
                        .append("，可用 ").append(d.available().stripTrailingZeros().toPlainString()).append(u)
                        .append("，缺 ").append(d.shortBy().stripTrailingZeros().toPlainString()).append(u);
            }
            sb.append("。怎么解开：").append(d.howToResolve());
        }
        sb.append("\n以上需要您自己到对应页面操作，我不会代替您执行放行或处置。");
        return sb.toString();
    }
}
