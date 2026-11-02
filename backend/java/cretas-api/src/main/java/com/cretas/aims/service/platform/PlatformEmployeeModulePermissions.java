package com.cretas.aims.service.platform;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.auth.UserMenuPermission;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.auth.UserMenuPermissionRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** New reserved namespace only: historical menu grants never become API grants. */
@Service
@RequiredArgsConstructor
public class PlatformEmployeeModulePermissions {
    public static final String PREFIX = "platform.module.";
    private final UserMenuPermissionRepository repository;
    private final UserRepository users;
    private final PlatformRolePermissionRepository globalPermissions;
    private final PlatformFactoryControlAuthority authority;

    public Map<String, String> overrides(User user) {
        if (user == null || user.getId() == null || user.getFactoryId() == null) return Map.of();
        Map<String, EnumMap<Action, Boolean>> values = new TreeMap<>();
        try {
            for (UserMenuPermission row : repository.findByFactoryIdAndUserId(user.getFactoryId(), String.valueOf(user.getId()))) {
                String code = row.getMenuCode();
                if (code == null || !code.startsWith(PREFIX)) continue;
                if (row.getGrantedBy() == null || !row.getGrantedBy().matches("PLATFORM_ADMIN:[1-9][0-9]*")) throw unavailable();
                String key = code.substring(PREFIX.length());
                int dot = key.lastIndexOf('.');
                if (dot < 1 || !key.substring(0, dot).matches("[a-z][A-Za-z0-9_]{0,63}") || row.getGrantType() == null) {
                    throw unavailable();
                }
                Action action = switch (key.substring(dot + 1)) {
                    case "read" -> Action.READ;
                    case "write" -> Action.WRITE;
                    default -> throw unavailable();
                };
                values.computeIfAbsent(key.substring(0, dot), ignored -> new EnumMap<>(Action.class))
                        .put(action, row.getGrantType() == UserMenuPermission.GrantType.GRANT);
            }
        } catch (BusinessException error) { throw error; }
        catch (RuntimeException error) { throw unavailable(); }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((module, actions) -> {
            if (actions.size() != 2) throw unavailable();
            result.put(module, level(actions.get(Action.READ), actions.get(Action.WRITE)));
        });
        return result;
    }

    public Map<String, String> apply(User user, Map<String, String> base) {
        if (protectedRole(user)) return base;
        Map<String, String> result = new LinkedHashMap<>(base);
        result.putAll(overrides(user));
        return result;
    }

    public String apply(User user, String module, String baseLevel) {
        if (protectedRole(user)) return baseLevel;
        return overrides(user).getOrDefault(module, baseLevel);
    }

    @Transactional
    public void replace(String factoryId, long userId, String module, String value, ActorRef actor) {
        authority.lock(actor, factoryId);
        User user = users.findByIdAndFactoryId(userId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "员工不属于所选工厂"));
        if (protectedRole(user) || !Boolean.TRUE.equals(user.getIsActive())
                || user.getRoleEnum() == null || user.getRoleEnum() == FactoryUserRole.unactivated) {
            throw new BusinessException(400, "不可修改全权管理员或未启用员工的个人权限");
        }
        Set<String> known = new HashSet<>();
        PermissionServiceImpl.hardcodedMatrixSnapshot().values().forEach(map -> known.addAll(map.keySet()));
        globalPermissions.findByDeletedAtIsNull().forEach(row -> known.add(row.getModuleCode()));
        if (module == null || !known.contains(module) || !module.matches("[a-z][A-Za-z0-9_]{0,63}")) {
            throw new BusinessException(400, "无效业务权限模块");
        }
        if (value != null && !Set.of("rw", "r", "w", "-").contains(value)) {
            throw new BusinessException(400, "权限级别必须为 rw/r/w/- 或恢复默认");
        }
        String id = String.valueOf(userId);
        for (String action : List.of("read", "write")) {
            String code = PREFIX + module + "." + action;
            if (value == null) {
                repository.deleteByFactoryIdAndUserIdAndMenuCode(factoryId, id, code);
                continue;
            }
            boolean grant = action.equals("read") ? value.contains("r") : value.contains("w");
            UserMenuPermission row = repository.findByFactoryIdAndUserIdAndMenuCode(factoryId, id, code)
                    .orElseGet(UserMenuPermission::new);
            row.setFactoryId(factoryId); row.setUserId(id); row.setMenuCode(code);
            row.setGrantType(grant ? UserMenuPermission.GrantType.GRANT : UserMenuPermission.GrantType.REVOKE);
            row.setGrantedBy(actor.type().name() + ":" + actor.id());
            row.setRemark("平台设置的员工模块权限");
            repository.save(row);
        }
    }

    private boolean protectedRole(User user) {
        return user == null || user.getRoleEnum() == null || user.getRoleEnum() == FactoryUserRole.unactivated
                || user.getRoleEnum() == FactoryUserRole.factory_super_admin || user.getRoleEnum() == FactoryUserRole.platform_admin;
    }
    private String level(boolean read, boolean write) { return read ? (write ? "rw" : "r") : (write ? "w" : "-"); }
    private BusinessException unavailable() { return new BusinessException(503, "员工权限配置暂时无法读取，请稍后重试"); }
    private enum Action { READ, WRITE }
}
