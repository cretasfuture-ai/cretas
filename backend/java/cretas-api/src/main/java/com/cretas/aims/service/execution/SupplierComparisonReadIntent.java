package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;

import java.util.Optional;
import java.util.Set;

/** Code-owned READ metadata, never an authorization grant or a persisted seed. */
public final class SupplierComparisonReadIntent {
    public static final String CODE = "SUPPLIER_PRICE_COMPARISON";
    public static final String TOOL = "supplier_price_comparison";
    public static final String VERSION = "2.0.0";
    public static final Set<String> PERMISSIONS = Set.of("procurement:read_write", "finance:read_write");

    private SupplierComparisonReadIntent() { }

    public static Optional<AIIntentConfig> resolve(String factoryId, ToolRegistry tools,
                                                  AIIntentConfigRepository repository) {
        if (factoryId == null || factoryId.isBlank() || tools == null || repository == null) {
            return Optional.empty();
        }
        try {
            Optional<ToolExecutor> registered = tools.getExecutor(TOOL);
            if (registered.isEmpty()) return Optional.empty();
            ToolExecutor tool = registered.get();
            if (!tool.isEnabled() || !TOOL.equals(tool.getToolName()) || !VERSION.equals(tool.getVersion())
                    || tool.getAccessMode() != ToolExecutor.AccessMode.READ
                    || tool.getActionType() != ToolExecutor.ActionType.READ
                    || !tool.requiresPermission() || !PERMISSIONS.equals(tool.getRequiredPermissions())) {
                return Optional.empty();
            }
            // Include inactive rows: an explicit restriction cannot become an absent-row default.
            Optional<AIIntentConfig> configured = repository.findByIntentCodeAndDeletedAtIsNull(CODE);
            if (configured.isPresent()) {
                AIIntentConfig row = configured.get();
                if (!Boolean.TRUE.equals(row.getIsActive()) || !CODE.equals(row.getIntentCode())
                        || !TOOL.equals(row.getToolName())
                        || (row.getFactoryId() != null && !factoryId.equals(row.getFactoryId()))) {
                    return Optional.empty();
                }
                // Keep all configured role/permission/approval restrictions verbatim.
                return configured;
            }
            return Optional.of(AIIntentConfig.builder()
                    .intentCode(CODE).intentName("供应商报价比较能力查询")
                    .toolName(TOOL).intentCategory("CRM").businessType("FACTORY")
                    .sensitivityLevel("LOW").requiredPermission("procurement:read_write,finance:read_write")
                    .requiresApproval(false).factoryId(factoryId).isActive(true).build());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
