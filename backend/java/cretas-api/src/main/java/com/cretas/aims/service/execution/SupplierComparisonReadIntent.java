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
            // Native scalar authority includes tombstones hidden by the Entity's @Where.
            // Current tenant controls before platform; foreign-only presence is never absence.
            var authority = repository.findSupplierComparisonAuthority(CODE);
            if (authority == null) return Optional.empty();
            if (!authority.isEmpty()) {
                var selected = authority.stream().filter(row -> factoryId.equals(row.getFactoryId())).toList();
                if (selected.isEmpty()) {
                    selected = authority.stream().filter(row -> row.getFactoryId() == null).toList();
                }
                if (selected.size() != 1) return Optional.empty();
                var owner = selected.get(0);
                if (!Boolean.TRUE.equals(owner.getActive()) || !Boolean.FALSE.equals(owner.getDeleted())
                        || owner.getId() == null) return Optional.empty();
                var configured = repository.findByFactoryIdAndIntentCode(owner.getFactoryId(), CODE);
                if (configured.isEmpty()) return Optional.empty();
                AIIntentConfig row = configured.get();
                if (!owner.getId().equals(row.getId()) || !Boolean.TRUE.equals(row.getIsActive())
                        || row.getDeletedAt() != null || !CODE.equals(row.getIntentCode())
                        || !TOOL.equals(row.getToolName())
                        || !java.util.Objects.equals(owner.getFactoryId(), row.getFactoryId())) {
                    return Optional.empty();
                }
                // Return the current configured restrictions verbatim, never code defaults.
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
