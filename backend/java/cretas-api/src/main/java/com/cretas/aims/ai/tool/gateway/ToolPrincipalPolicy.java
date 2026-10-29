package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reloads USER identity, tenant, business type, role, and permissions from current DB truth. */
@Component
@RequiredArgsConstructor
public class ToolPrincipalPolicy {

    private final UserRepository userRepository;
    private final FactoryRepository factoryRepository;
    private final PermissionService permissionService;

    public Optional<RehydratedPrincipal> rehydrate(ExecutionPrincipal asserted) {
        if (asserted == null || asserted.principalType() != PrincipalType.USER) {
            return Optional.empty();
        }
        Long userId;
        try {
            userId = Long.valueOf(asserted.principalId());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }

        try {
            Optional<User> userOptional = userRepository.findById(userId);
            if (userOptional.isEmpty()) {
                return Optional.empty();
            }
            User user = userOptional.get();
            if (!Boolean.TRUE.equals(user.getIsActive())
                    || !asserted.tenantId().equals(user.getFactoryId())) {
                return Optional.empty();
            }

            Optional<Factory> factoryOptional = factoryRepository.findById(user.getFactoryId());
            if (factoryOptional.isEmpty()) {
                return Optional.empty();
            }
            Factory factory = factoryOptional.get();
            if (!Boolean.TRUE.equals(factory.getIsActive()) || factory.getType() == null) {
                return Optional.empty();
            }
            String assertedBusinessType = asserted.businessType().trim().toUpperCase(Locale.ROOT);
            String currentBusinessType = factory.getType().name();
            if (!currentBusinessType.equals(assertedBusinessType)) {
                return Optional.empty();
            }
            String currentRole = user.getRoleCode();
            if (currentRole == null || currentRole.isBlank()) {
                return Optional.empty();
            }
            Map<String, String> currentLevels = permissionService.resolveModuleLevelsForAuthorization(user);
            if (currentLevels == null) {
                return Optional.empty();
            }
            Set<String> currentPermissions = moduleLevelPermissions(currentLevels);
            boolean canViewPrices = permissionService.hasPermission(
                    user, "procurement:price:view");
            ExecutionPrincipal current = new ExecutionPrincipal(
                    factory.getId(),
                    currentBusinessType,
                    user.getId().toString(),
                    PrincipalType.USER,
                    Set.of(currentRole),
                    currentPermissions,
                    Set.of());

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("factoryId", factory.getId());
            context.put("tenantId", factory.getId());
            context.put("businessType", currentBusinessType);
            context.put("userId", user.getId());
            context.put("userRole", currentRole);
            context.put("roleCode", currentRole);
            context.put("permissions", currentPermissions);
            context.put("canViewPrices", canViewPrices);
            // S5-12: 采购价按配置的 procurement 读权限判, 与上面的默认名单口径分开 (同 PriceFieldResponseAdvice)
            com.cretas.aims.security.PriceVisibility visibility =
                    new com.cretas.aims.security.PriceVisibility(permissionService);
            context.put("canViewPurchasePrices", visibility.canView(
                    user, com.cretas.aims.security.PriceSensitive.PURCHASE));
            context.put("canViewPurchaseOrFinancePrices", visibility.canView(
                    user, com.cretas.aims.security.PriceSensitive.PURCHASE_OR_FINANCE));
            return Optional.of(new RehydratedPrincipal(current, Map.copyOf(context)));
        } catch (RuntimeException unavailableAuthorizationTruth) {
            return Optional.empty();
        }
    }

    public record RehydratedPrincipal(
            ExecutionPrincipal principal,
            Map<String, Object> executionContext) {
    }

    /**
     * PermissionService emits separate module:read and module:write codes. Runtime policies use
     * the historical module:read_write contract, so derive that composite only from matching,
     * current DB-backed halves. Roles and asserted command permissions never participate.
     */
    static Set<String> withReadWriteComposites(Set<String> currentPermissions) {
        Set<String> source = currentPermissions == null
                ? Set.of()
                : Set.copyOf(currentPermissions);
        Set<String> expanded = new LinkedHashSet<>(source);
        for (String permission : source) {
            if (permission.endsWith(":read")) {
                String module = permission.substring(0, permission.length() - ":read".length());
                if (!module.isBlank() && source.contains(module + ":write")) {
                    expanded.add(module + ":read_write");
                }
            }
        }
        return Set.copyOf(expanded);
    }

    /** Converts the canonical module-level snapshot into the legacy admission permission codes. */
    static Set<String> moduleLevelPermissions(Map<String, String> levels) {
        if (levels == null || levels.isEmpty()) {
            return Set.of();
        }
        Set<String> permissions = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : levels.entrySet()) {
            String module = entry.getKey();
            String level = entry.getValue();
            if (module == null || module.isBlank() || level == null) {
                continue;
            }
            String normalized = level.trim().toLowerCase(Locale.ROOT);
            if ("r".equals(normalized) || "rw".equals(normalized)) {
                permissions.add(module + ":read");
            }
            if ("w".equals(normalized) || "rw".equals(normalized)) {
                permissions.add(module + ":write");
            }
            if ("rw".equals(normalized)) {
                permissions.add(module + ":read_write");
            }
        }
        return Set.copyOf(permissions);
    }
}
