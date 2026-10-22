package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.intent.PurchaseGrantCallerService;
import com.cretas.aims.service.intent.PurchaseTaskGrantService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** A grant reference selects a task, never supplies its identity, command or confirmation. */
@Component
@RequiredArgsConstructor
public class PurchaseGrantExecuteTool extends AbstractBusinessTool {
    public static final String NAME = "purchase_grant_execute";
    /** Server-owned alias of the same confirmed purchase task, never caller-selected metadata. */
    public static com.cretas.aims.entity.config.AIIntentConfig canonicalIntent() {
        var intent = new com.cretas.aims.entity.config.AIIntentConfig();
        intent.setIntentCode("PURCHASE_ORDER_CREATE");
        intent.setIntentName("执行已确认采购草稿");
        return intent;
    }
    private final ObjectProvider<PurchaseGrantCallerService> callers;

    @Override public String getToolName() { return "purchase_grant_execute"; }
    @Override public String getDescription() { return "执行当前登录采购用户已人工确认的有效采购授权；仅创建采购草稿，原采购及财务审批继续生效。"; }
    @Override public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("grantId", Map.of("type", "string", "pattern", "^preview-[1-9][0-9]{0,18}$"),
                        "expectedVersion", Map.of("type", "integer", "minimum", 1)),
                "required", getRequiredParameters());
    }
    @Override protected List<String> getRequiredParameters() { return List.of("grantId", "expectedVersion"); }
    @Override public boolean requiresPermission() { return true; }
    @Override public Set<String> getRequiredPermissions() { return Set.of("procurement:read_write"); }
    @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
    @Override public ActionType getActionType() { return ActionType.WRITE; }
    @Override public RiskLevel getRiskLevel() { return RiskLevel.HIGH; }

    @Override protected Map<String, Object> doExecute(String factory, Map<String, Object> params, Map<String, Object> context) {
        // The execute override below preserves typed failure/UNKNOWN; this is not another lane.
        throw new UnsupportedOperationException("Use the typed grant Tool entry");
    }

    @Override public String execute(ToolCall call, Map<String, Object> context) throws Exception {
        Map<String, Object> params = parseArguments(call);
        String grantId = params.get("grantId") instanceof String value ? value : null;
        try {
            if (!NAME.equals(call.getFunction().getName())) throw new BusinessException(403, "采购授权工具绑定不匹配");
            if (grantId == null || !grantId.matches("preview-[1-9][0-9]{0,18}")) throw new BusinessException(403, "采购授权参考无效");
            long version;
            try {
                if (!(params.get("expectedVersion") instanceof Number number)) throw new ArithmeticException();
                version = new BigDecimal(number.toString()).longValueExact();
                if (version <= 0) throw new ArithmeticException();
            } catch (ArithmeticException | NumberFormatException invalid) {
                throw new BusinessException(403, "缺少有效授权版本");
            }
            String factory = context != null && context.get("factoryId") instanceof String value ? value : null;
            var outcome = callers.getObject().executeAuthorized(factory, grantId, version);
            return objectMapper.writeValueAsString(Map.of("success", true, "data", outcome));
        } catch (BusinessException | PurchaseTaskGrantService.GrantRejectedException refused) {
            boolean auditUnknown = refused instanceof BusinessException business && Integer.valueOf(503).equals(business.getCode());
            return objectMapper.writeValueAsString(Map.of("success", false, "code", auditUnknown ? "PURCHASE_GRANT_OUTCOME_UNKNOWN" : "PURCHASE_GRANT_REJECTED",
                    "error", refused.getMessage() == null ? "采购授权执行已拒绝" : refused.getMessage()));
        } catch (RuntimeException unknown) {
            // Never turn infrastructure/commit ambiguity into a confirmed refusal or retry.
            return objectMapper.writeValueAsString(Map.of("success", false, "code", "PURCHASE_GRANT_OUTCOME_UNKNOWN",
                    "error", "采购执行结果未知，请查询原授权状态，勿重新执行"));
        }
    }
}
