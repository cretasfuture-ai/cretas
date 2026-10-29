package com.cretas.aims.ai.tool.impl.alerts;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI Tool — 启用/禁用 告警规则 (Phase 2 Canvas-Alerts).
 *
 * <p>Toggle 切换 {@code rule.enabled}. 禁用后 scheduler / listener 不再评估.
 *
 * <p>S6-04: 可选参数 {@code enabled} 把规则设成指定状态 (重复执行结果不变), 所以它能作为定时任务的
 * 委托写入 (如每晚关闭、早上打开某条规则)。运行策略 (runtime-tool-policies.yaml) 只对 SCHEDULER 开放,
 * 须确认 + 幂等键 (由委托授权提供); 角色与 HTTP 切换接口一致: factory_super_admin / permission_admin。
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertRuleToggleTool extends AbstractBusinessTool {

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Override
    public String getToolName() {
        return "alert_rule_toggle";
    }

    /** Same roles as {@code POST /alerts/rules/{id}/toggle}. */
    static final Set<String> ALLOWED_ROLES = Set.of("factory_super_admin", "permission_admin");

    @Override
    public ToolExecutor.ActionType getActionType() {
        return ToolExecutor.ActionType.UPDATE;
    }

    @Override
    public ToolExecutor.RiskLevel getRiskLevel() {
        return ToolExecutor.RiskLevel.MEDIUM;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return userRole != null && ALLOWED_ROLES.contains(userRole);
    }

    /** Role-governed like the HTTP toggle; no module permission code is declared. */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of();
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("alert");
    }

    @Override
    public String getDescription() {
        return "切换告警规则启用/禁用状态. 必填 ruleId; 可选 enabled (true/false) 直接设成该状态. "
                + "禁用后 scheduler / event listener 不再评估此规则. "
                + "适用场景: 临时关闭某条规则 / 重新启用.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> ruleId = new HashMap<>();
        ruleId.put("type", "string");
        ruleId.put("description", "告警规则编号");

        Map<String, Object> enabled = new HashMap<>();
        enabled.put("type", "boolean");
        enabled.put("description", "可选: 设成启用(true)或禁用(false); 不填则切换当前状态");

        Map<String, Object> properties = new HashMap<>();
        properties.put("ruleId", ruleId);
        properties.put("enabled", enabled);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("ruleId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("ruleId");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);
        boolean currentlyEnabled = Boolean.TRUE.equals(existing.getEnabled());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "PREVIEW");
        result.put("action", "TOGGLE");
        result.put("ruleId", existing.getId().toString());
        result.put("ruleName", existing.getRuleName());
        result.put("currentEnabled", currentlyEnabled);
        boolean target = targetEnabled(params, currentlyEnabled);
        result.put("newEnabled", target);
        result.put("message", String.format(
                "即将 %s 告警规则 %s (id=%s)",
                target ? "启用" : "禁用",
                existing.getRuleName(), existing.getId()));
        return result;
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        AlertRule existing = loadRule(factoryId, params);
        boolean current = Boolean.TRUE.equals(existing.getEnabled());
        existing.setEnabled(targetEnabled(params, current));

        AlertRule saved = ruleRepository.save(existing);
        log.info("alert_rule_toggle: factoryId={}, ruleId={}, enabled {} -> {}",
                factoryId, saved.getId(), current, saved.getEnabled());

        Map<String, Object> data = new HashMap<>();
        data.put("ruleId", saved.getId().toString());
        data.put("factoryId", factoryId);
        data.put("ruleName", saved.getRuleName());
        data.put("previousEnabled", current);
        data.put("enabled", saved.getEnabled());

        return buildSimpleResult(String.format("告警规则 %s (id=%s) 已 %s",
                saved.getRuleName(), saved.getId(),
                saved.getEnabled() ? "启用" : "禁用"), data);
    }

    /** The explicit {@code enabled} value when given (must be a boolean), otherwise the opposite of now. */
    private static boolean targetEnabled(Map<String, Object> params, boolean current) {
        Object requested = params == null ? null : params.get("enabled");
        if (requested == null) {
            return !current;
        }
        if (requested instanceof Boolean value) {
            return value;
        }
        if ("true".equals(requested) || "false".equals(requested)) {
            return Boolean.parseBoolean((String) requested);
        }
        throw new IllegalArgumentException("enabled 只能是 true 或 false: " + requested);
    }

    private AlertRule loadRule(String factoryId, Map<String, Object> params) {
        String ruleIdStr = getString(params, "ruleId");
        UUID ruleUuid;
        try {
            ruleUuid = UUID.fromString(ruleIdStr);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ruleId 不是合法 UUID: " + ruleIdStr);
        }
        AlertRule rule = ruleRepository.findById(ruleUuid)
                .orElseThrow(() -> new IllegalArgumentException("告警规则不存在: id=" + ruleIdStr));
        if (!rule.getFactoryId().equals(factoryId)) {
            // Same answer as a missing rule: never reveal that the id exists in another factory.
            throw new IllegalArgumentException("告警规则不存在: id=" + ruleIdStr);
        }
        return rule;
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
