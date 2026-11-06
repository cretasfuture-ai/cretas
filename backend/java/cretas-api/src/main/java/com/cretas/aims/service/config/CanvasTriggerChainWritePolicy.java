package com.cretas.aims.service.config;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.catalog.FactoryIntentCatalog;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.exception.BusinessException;

import java.util.List;
import java.util.Map;

/** Config-time event-chain policy shared by direct HTTP and bound Canvas writes. */
public final class CanvasTriggerChainWritePolicy {
    private CanvasTriggerChainWritePolicy() { }

    @SuppressWarnings("unchecked")
    public static void validateEnabledSteps(List<Map<String, Object>> steps, ToolRegistry registry) {
        if (steps == null || steps.isEmpty()) return;
        if (registry == null) {
            throw new BusinessException(500, "工具注册表不可用，不能校验事件链步骤");
        }
        for (Map<String, Object> step : steps) {
            if (step == null || Boolean.FALSE.equals(step.get("enabled"))) continue;
            if (!(step.get("tool") instanceof String name) || name.isBlank()) continue;
            if (FactoryIntentCatalog.isRetiredTool(name)) {
                throw new BusinessException(400, "已退役的工具不能加入启用的事件链");
            }
            var tool = registry.getExecutor(name)
                    .orElseThrow(() -> new BusinessException(400, "事件链工具不存在或未启用，请刷新工具列表"));
            Object rawParams = step.getOrDefault("params", Map.of());
            if (!(rawParams instanceof Map<?, ?> params)) {
                throw new BusinessException(400, "事件链步骤「" + name + "」的参数必须是对象")
                        .withHint("把 params 改成键值形式，例如 {\"limit\": 10}");
            }
            String refusal = AutonomousToolPolicy.rejection(name, tool, (Map<String, Object>) params);
            if (refusal != null) throw AutonomousToolPolicy.refusal(refusal);
        }
    }
}
