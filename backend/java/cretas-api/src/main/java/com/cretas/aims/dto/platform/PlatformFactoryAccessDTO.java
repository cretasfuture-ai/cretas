package com.cretas.aims.dto.platform;

import java.util.List;
import java.util.Map;

public final class PlatformFactoryAccessDTO {
    private PlatformFactoryAccessDTO() {}
    public record Module(String code, String name, String category, boolean enabled, String renderingMode) {}
    public record PermissionModule(String code, String name) {}
    public record Role(String code, String name, boolean protectedRole, Map<String, String> globalLevels,
                       Map<String, String> overrides, Map<String, String> effectiveLevels) {}
    public record Access(String factoryId, String factoryName, boolean canManage, Integer configurationVersion,
                         boolean pendingConfiguration, List<Module> modules, List<PermissionModule> permissionModules,
                         List<Role> roles) {}
    public record UserSummary(Long id, String username, String displayName, String roleCode, boolean active,
                              boolean protectedRole) {}
    public record Users(List<UserSummary> content, long totalElements, int totalPages, int number, int size) {}
    public record UserPermissions(UserSummary user, List<ModuleLevel> modules) {}
    public record ModuleLevel(String code, String name, String roleLevel, String overrideLevel, String effectiveLevel) {}
    public record ModuleUpdate(Boolean enabled) {}
    public record LevelUpdate(String level) {}
}
