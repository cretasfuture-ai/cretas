package com.cretas.aims.ai.tool.impl.config;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigActivation;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigService;
import com.cretas.aims.service.config.aiconfig.FactoryShortageConfigStore;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class FactoryShortageConfigTool extends AbstractBusinessTool {
    private final FactoryShortageConfigStore store;
    private final Environment environment;

    @Override
    public boolean isEnabled() {
        return FactoryShortageConfigActivation.isEnabled(environment);
    }

    @Override
    public String getToolName() { return "factory_shortage_config_update"; }

    @Override
    public String getDescription() { return "预览、确认或撤销本厂缺料开工拦截设置"; }

    @Override
    public ActionType getActionType() { return ActionType.UPDATE; }

    @Override
    public AccessMode getAccessMode() { return AccessMode.WRITE; }

    @Override
    public RiskLevel getRiskLevel() { return RiskLevel.MEDIUM; }

    @Override
    public boolean supportsPreview() { return true; }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public boolean hasPermission(String role) {
        return role != null && FactoryShortageConfigService.ROLES.contains(role);
    }

    @Override
    public Set<String> getRequiredRoles() { return FactoryShortageConfigService.ROLES; }

    @Override
    public Set<String> getRequiredPermissions() { return Set.of(); }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public Set<String> getDomainTags() { return Set.of("factory-config", "production", "shortage"); }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of(
                "requestedValue", Map.of("type", "boolean"),
                "undoOf", Map.of("type", "string", "format", "uuid")));
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of(); }

    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        return store.previewFromGateway(context, objectMapper.valueToTree(params));
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) {
        return store.executeFromGateway(context, objectMapper.valueToTree(params));
    }
}
