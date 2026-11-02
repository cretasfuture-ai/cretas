package com.cretas.aims.service.platform;

import com.cretas.aims.dto.config.ModuleSummaryDTO;
import com.cretas.aims.dto.platform.FactoryDTO;
import com.cretas.aims.dto.platform.PlatformFactoryAccessDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.permission.PermissionModuleCatalog;
import com.cretas.aims.permission.ProductionModuleRegistry;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PlatformFactoryAccessService {
    private final FactoryService factoryService;
    private final FactoryConfigService factoryConfigService;
    private final FactoryConfigurationRepository configurations;
    private final PermissionService permissionService;
    private final PlatformFactoryControlAuthority authority;
    private final PlatformEmployeeModulePermissions employeePermissions;
    private final UserRepository users;
    private final FactoryModuleConfigRepository moduleConfigs;
    private final UserModuleAccessService userModuleAccess;
    private final com.cretas.aims.repository.config.ConfigChangeLogRepository audit;

    public PlatformFactoryAccessDTO.Access access(String factoryId, ActorRef actor) {
        authority.requireReadRef(actor, factoryId);
        FactoryDTO factory = factoryService.getFactoryById(factoryId);
        List<ModuleSummaryDTO> enabled = factoryConfigService.getEnabledModules(factoryId);
        Integer version = configurations.findLatestPublished(factoryId).map(c -> c.getConfigVersion()).orElse(null);
        boolean pending = configurations.findLatestPending(factoryId).isPresent();
        List<PlatformFactoryAccessDTO.Module> modules = enabled.stream().map(m -> new PlatformFactoryAccessDTO.Module(
                m.getModuleCode(), m.getModuleName(), m.getModuleCategory(), m.isEnabled(), m.getRenderingMode())).toList();
        List<PlatformFactoryAccessDTO.PermissionModule> permissions = PermissionModuleCatalog.labelledCodes().stream()
                .map(code -> new PlatformFactoryAccessDTO.PermissionModule(code, PermissionModuleCatalog.label(code))).toList();
        List<FactoryModuleConfig> permissionRows = moduleConfigs.findByFactoryIdAndConfigVersion(factoryId, 1);
        List<PlatformFactoryAccessDTO.Role> roles = Arrays.stream(FactoryUserRole.values()).filter(FactoryUserRole::isActive).map(role -> {
            Map<String,String> global = roleLevels(null, role);
            Map<String,String> overrides = permissionRows.stream()
                    .map(FactoryModuleConfig::getRoleModuleOverride).filter(Objects::nonNull)
                    .map(values -> values.get(role.name())).filter(Objects::nonNull)
                    .reduce(new LinkedHashMap<>(), (merged, values) -> { values.forEach(merged::putIfAbsent); return merged; });
            Map<String,String> effective = roleLevels(factoryId, role);
            boolean protectedRole = role == FactoryUserRole.factory_super_admin || role == FactoryUserRole.platform_admin;
            return new PlatformFactoryAccessDTO.Role(role.name(), role.getDescription(), protectedRole, global, overrides, effective);
        }).toList();
        return new PlatformFactoryAccessDTO.Access(factoryId, factory.getName(), authority.canManage(actor), version, pending, modules, permissions, roles);
    }

    public PlatformFactoryAccessDTO.Users users(String factoryId, String keyword, int page, int size, ActorRef actor) {
        authority.requireReadRef(actor, factoryId);
        Page<User> result = users.searchUsers(factoryId, keyword == null ? "" : keyword, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
        return new PlatformFactoryAccessDTO.Users(result.getContent().stream().map(this::summary).toList(), result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }

    public PlatformFactoryAccessDTO.UserPermissions permissions(String factoryId, long userId, ActorRef actor) {
        authority.requireReadRef(actor, factoryId);
        User user = users.findByIdAndFactoryId(userId, factoryId).orElseThrow(() -> new com.cretas.aims.exception.BusinessException(404, "员工不存在"));
        Map<String,String> base = roleLevels(factoryId, user.getRoleEnum());
        Map<String,String> overrides = employeePermissions.overrides(user);
        Map<String,String> effective = employeePermissions.apply(user, base);
        List<PlatformFactoryAccessDTO.ModuleLevel> rows = PermissionModuleCatalog.labelledCodes().stream().map(code ->
                new PlatformFactoryAccessDTO.ModuleLevel(code, PermissionModuleCatalog.label(code), base.get(code), overrides.get(code), effective.get(code))).toList();
        return new PlatformFactoryAccessDTO.UserPermissions(summary(user), rows);
    }

    @Transactional
    public PlatformFactoryAccessDTO.Access updateModule(String factoryId, String code, boolean enabled, ActorRef actor) {
        authority.requireWriteRef(actor, factoryId); factoryConfigService.setModuleEnabledForPlatform(factoryId, code, enabled, actor); return access(factoryId, actor);
    }

    @Transactional
    public PlatformFactoryAccessDTO.Access updateRole(String factoryId, String role, String module, String level, ActorRef actor) {
        authority.requireWriteRef(actor, factoryId);
        FactoryUserRole roleValue;
        try { roleValue = FactoryUserRole.valueOf(role); } catch (IllegalArgumentException e) { throw new com.cretas.aims.exception.BusinessException(400, "无效角色"); }
        if (!roleValue.isActive() || roleValue == FactoryUserRole.platform_admin || roleValue == FactoryUserRole.factory_super_admin || module == null
                || !PermissionModuleCatalog.hasLabel(module) || (level != null && !Set.of("rw", "r", "w", "-").contains(level))) {
            throw new com.cretas.aims.exception.BusinessException(400, "无效角色模块权限");
        }
        authority.lock(actor, factoryId);
        List<FactoryModuleConfig> rows = moduleConfigs.findByFactoryIdAndConfigVersion(factoryId, 1);
        FactoryModuleConfig target = rows.stream()
                .filter(row -> "__permissions__".equals(row.getModuleCode())).findFirst().orElseGet(() -> FactoryModuleConfig.builder()
                        .factoryId(factoryId).moduleCode("__permissions__").configVersion(1).enabled(true)
                        .fieldConfig(Map.of()).workflowConfig(Map.of()).validationConfig(Map.of()).permissionConfig(Map.of())
                        .layoutConfig(Map.of()).customLabels(Map.of()).computedFields(Map.of()).roleModuleOverride(new HashMap<>())
                        .renderingMode("LEGACY").build());
        Map<String, Map<String, String>> all = new HashMap<>();
        if (target.getRoleModuleOverride() != null) target.getRoleModuleOverride().forEach((r, values) -> all.put(r, new HashMap<>(values)));
        for (FactoryModuleConfig row : rows) {
            Map<String, Map<String,String>> copy = new HashMap<>();
            if (row.getRoleModuleOverride() != null) row.getRoleModuleOverride().forEach((r, values) -> copy.put(r, new HashMap<>(values)));
            if (copy.containsKey(role)) {
                copy.get(role).remove(module);
                if (copy.get(role).isEmpty()) copy.remove(role);
            }
            row.setRoleModuleOverride(copy);
            moduleConfigs.save(row);
        }
        if (all.containsKey(role)) all.get(role).remove(module);
        if (level != null) all.computeIfAbsent(role, ignored -> new HashMap<>()).put(module, level);
        all.values().removeIf(Map::isEmpty); target.setRoleModuleOverride(all); moduleConfigs.save(target);
        audit(factoryId, module, actor, "PLATFORM_ROLE_PERMISSION", Map.of("role", role, "level", level == null ? "inherit" : level));
        invalidatePermissions();
        return access(factoryId, actor);
    }

    @Transactional
    public PlatformFactoryAccessDTO.UserPermissions updateEmployee(String factoryId, long userId, String module, String level, ActorRef actor) {
        authority.requireWriteRef(actor, factoryId);
        employeePermissions.replace(factoryId, userId, module, level, actor);
        ProductionModuleRegistry.modules().stream().filter(def -> def.permissionModule().equals(module))
                .map(ProductionModuleRegistry.ModuleDefinition::moduleCode)
                .forEach(code -> userModuleAccess.clearOverride(factoryId, String.valueOf(userId), code));
        audit(factoryId, module, actor, "PLATFORM_USER_PERMISSION", Map.of("userId", userId, "level", level == null ? "inherit" : level));
        invalidatePermissions();
        return permissions(factoryId, userId, actor);
    }

    private void invalidatePermissions() {
        if (permissionService instanceof PermissionServiceImpl impl) impl.invalidateCache();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        if (permissionService instanceof PermissionServiceImpl impl) impl.invalidateCache();
                    }
                });
        }
    }

    private void audit(String factoryId, String module, ActorRef actor, String operation, Map<String,Object> value) {
        audit.save(com.cretas.aims.entity.config.ConfigChangeLog.builder().factoryId(factoryId).moduleCode(module)
            .operation(operation).operatorId(actor.id()).operatorType(actor.type().name())
            .afterValue(value).diffSummary("平台调整当前工厂业务权限").build());
    }

    private PlatformFactoryAccessDTO.UserSummary summary(User user) {
        String role = user.getRoleCode();
        return new PlatformFactoryAccessDTO.UserSummary(user.getId(), user.getUsername(), user.getFullName(), role,
                Boolean.TRUE.equals(user.getIsActive()), user.getRoleEnum() == null || !user.getRoleEnum().isActive()
                || "factory_super_admin".equals(role) || "platform_admin".equals(role));
    }

    private Map<String,String> roleLevels(String factoryId, FactoryUserRole role) {
        User synthetic = new User(); synthetic.setFactoryId(factoryId);
        synthetic.setRoleCode(role == null ? "unactivated" : role.name());
        return permissionService.resolveModuleLevelsForAuthorization(synthetic);
    }
}
