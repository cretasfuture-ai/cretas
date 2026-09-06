package com.cretas.aims.service.permission;

import com.cretas.aims.dto.permission.PermissionMatrixView;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 组装「权限矩阵」只读视图。<b>只读</b> —— 本类不含任何 save/delete。
 *
 * <p>owner 裁定 2026-09-06: 先看清真相再决定改什么, 所以 step 1 不做 L1 编辑器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionMatrixViewService {

    private final PlatformRolePermissionRepository platformRepo;
    private final FactoryModuleConfigRepository factoryConfigRepo;

    /**
     * L2 role×module 覆盖寄生在这个伪 module_code 的行上 (见 F006 生产数据与
     * {@code ProductionReportCorrectionPermissionContractTest})。它<b>不是一个模块</b>,
     * 所以不能进模块轴, 也不该在表头当成「被关掉的模块」渲染。
     */
    private static final String L2_PERMISSION_PSEUDO_MODULE = "__permissions__";

    public PermissionMatrixView build(String factoryId) {
        List<PlatformRolePermission> l1Rows = platformRepo.findByDeletedAtIsNull();

        Map<String, Map<String, String>> l1 = new LinkedHashMap<>();
        Set<String> l1Modules = new TreeSet<>();
        for (PlatformRolePermission row : l1Rows) {
            String level = normalizeLevel(row.getPermissionLevel());
            l1.computeIfAbsent(row.getRoleCode(), k -> new LinkedHashMap<>())
                    .put(row.getModuleCode(), level);
            l1Modules.add(row.getModuleCode());
        }

        Map<String, Map<String, String>> hardcoded = new LinkedHashMap<>();
        Set<String> hardcodedModules = new TreeSet<>();
        for (Map.Entry<String, Map<String, String>> e
                : PermissionServiceImpl.hardcodedMatrixSnapshot().entrySet()) {
            Map<String, String> perRole = new LinkedHashMap<>();
            for (Map.Entry<String, String> cell : e.getValue().entrySet()) {
                perRole.put(cell.getKey(), normalizeLevel(cell.getValue()));
                hardcodedModules.add(cell.getKey());
            }
            hardcoded.put(e.getKey(), perRole);
        }

        List<PermissionMatrixView.ModuleToggle> moduleToggles = new ArrayList<>();
        List<PermissionMatrixView.RoleOverride> roleOverrides = new ArrayList<>();
        Set<String> l2Modules = new TreeSet<>();
        collectLayer2(factoryId, moduleToggles, roleOverrides, l2Modules);

        // 模块轴 = 三个权威来源的并集, ⛔ 不新写一份清单 (全仓已有 7 份互不一致的模块清单)。
        Set<String> modules = new TreeSet<>();
        modules.addAll(l1Modules);
        modules.addAll(hardcodedModules);
        modules.addAll(l2Modules);

        List<String> modulesWithoutL1 = modules.stream()
                .filter(m -> !l1Modules.contains(m))
                .toList();

        List<PermissionMatrixView.RoleRef> roles = buildRoles(l1.keySet(), hardcoded.keySet());
        List<PermissionMatrixView.Diff> diffs = computeDiffs(roles, modules, l1, hardcoded);

        return new PermissionMatrixView(
                factoryId,
                List.copyOf(modules),
                roles,
                Collections.unmodifiableMap(l1),
                Collections.unmodifiableMap(hardcoded),
                diffs,
                moduleToggles,
                roleOverrides,
                modulesWithoutL1);
    }

    private void collectLayer2(String factoryId,
                               List<PermissionMatrixView.ModuleToggle> moduleToggles,
                               List<PermissionMatrixView.RoleOverride> roleOverrides,
                               Set<String> l2Modules) {
        List<FactoryModuleConfig> configs;
        try {
            configs = factoryConfigRepo.findByFactoryIdAndConfigVersion(factoryId, 1);
        } catch (Exception e) {
            // ⛔ 不吞成空列表就完事: 空列表与「这个工厂真的没配 L2」长得一模一样。
            //    这里只能记日志, 但下游拿到的 l2Overrides 为空时页面会显示「未配置 L2」,
            //    读的人需要知道这是可能的两种情况之一 —— 已写进接口 javadoc。
            log.warn("L2 config 读取失败 factoryId={}: {}", factoryId, e.getMessage());
            return;
        }
        Set<String> seenToggle = new LinkedHashSet<>();
        for (FactoryModuleConfig cfg : configs) {
            String moduleCode = cfg.getModuleCode();
            if (moduleCode != null
                    && !L2_PERMISSION_PSEUDO_MODULE.equals(moduleCode)
                    && seenToggle.add(moduleCode)) {
                moduleToggles.add(new PermissionMatrixView.ModuleToggle(
                        moduleCode, !Boolean.FALSE.equals(cfg.getEnabled())));
                l2Modules.add(moduleCode);
            }
            Map<String, Map<String, String>> override = cfg.getRoleModuleOverride();
            if (override == null) {
                continue;
            }
            for (Map.Entry<String, Map<String, String>> roleEntry : override.entrySet()) {
                if (roleEntry.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, String> cell : roleEntry.getValue().entrySet()) {
                    roleOverrides.add(new PermissionMatrixView.RoleOverride(
                            roleEntry.getKey(), cell.getKey(), normalizeLevel(cell.getValue())));
                    l2Modules.add(cell.getKey());
                }
            }
        }
        moduleToggles.sort(Comparator.comparing(PermissionMatrixView.ModuleToggle::module));
        roleOverrides.sort(Comparator
                .comparing(PermissionMatrixView.RoleOverride::role)
                .thenComparing(PermissionMatrixView.RoleOverride::module));
    }

    /**
     * 角色轴 = 枚举 ∪ L1 表里出现过的 role_code ∪ 兜底矩阵的键。
     *
     * <p>取并集而不是只取枚举: L1 表里如果有一个枚举里已经删掉的 role_code,
     * 那些行仍然在库里、仍然会被 {@code resolveLayer1} 命中 —— 只列枚举会让它隐形。
     */
    static List<PermissionMatrixView.RoleRef> buildRoles(Set<String> l1Roles, Set<String> hardcodedRoles) {
        Set<String> codes = new LinkedHashSet<>();
        for (FactoryUserRole role : FactoryUserRole.values()) {
            codes.add(role.name());
        }
        codes.addAll(l1Roles);
        codes.addAll(hardcodedRoles);

        List<PermissionMatrixView.RoleRef> roles = new ArrayList<>();
        for (String code : codes) {
            FactoryUserRole enumRole = null;
            try {
                enumRole = FactoryUserRole.valueOf(code);
            } catch (IllegalArgumentException ignored) {
                // L1 表里的孤儿 role_code —— 保留并显示, 见方法 javadoc
            }
            roles.add(new PermissionMatrixView.RoleRef(
                    code,
                    enumRole == null ? code : enumRole.getDisplayName(),
                    groupOf(code, enumRole),
                    enumRole == null ? 99 : enumRole.getLevel()));
        }
        roles.sort(Comparator
                .comparingInt((PermissionMatrixView.RoleRef r) -> groupOrder(r.group()))
                .thenComparingInt(PermissionMatrixView.RoleRef::level)
                .thenComparing(PermissionMatrixView.RoleRef::code));
        return roles;
    }

    private static int groupOrder(String group) {
        switch (group) {
            case "platform": return 0;
            case "factory": return 1;
            case "restaurant": return 2;
            default: return 3;
        }
    }

    static String groupOf(String code, FactoryUserRole role) {
        if ("platform_admin".equals(code)) {
            return "platform";
        }
        if (role != null && "restaurant".equals(role.getDepartment())) {
            return "restaurant";
        }
        if (code.startsWith("restaurant_")) {
            return "restaurant";
        }
        return role == null ? "unknown" : "factory";
    }

    /**
     * 分歧计算 —— 本 PR 唯一有实质判断的一段, 变异必须打在这里。
     *
     * <p>两种分歧的下游后果是<b>相反</b>的, 所以不能合成一类:
     * <ul>
     *   <li>{@code override}: L1 有行 ⇒ {@code resolveLayer1} 一定返回它, 兜底那格<b>永远到不了</b>。
     *       兜底与它不同 = 读兜底的人 (以及镜像它的前端 PERMISSION_MATRIX) 会得出错误结论。</li>
     *   <li>{@code fallback}: L1 无行 ⇒ 兜底那格<b>正在真的生效</b>。它给了权限而 L1 从没批准过。</li>
     * </ul>
     */
    static List<PermissionMatrixView.Diff> computeDiffs(
            List<PermissionMatrixView.RoleRef> roles,
            Set<String> modules,
            Map<String, Map<String, String>> l1,
            Map<String, Map<String, String>> hardcoded) {

        List<PermissionMatrixView.Diff> diffs = new ArrayList<>();
        for (PermissionMatrixView.RoleRef role : roles) {
            Map<String, String> l1Row = l1.getOrDefault(role.code(), Map.of());
            Map<String, String> hcRow = hardcoded.getOrDefault(role.code(), Map.of());
            for (String module : modules) {
                String l1Level = l1Row.get(module);            // null = L1 表里没有这一行
                String hcLevel = hcRow.getOrDefault(module, "-");
                if (l1Level == null) {
                    if (!"-".equals(hcLevel)) {
                        diffs.add(new PermissionMatrixView.Diff(
                                role.code(), module, null, hcLevel, "fallback"));
                    }
                } else if (!l1Level.equals(hcLevel)) {
                    diffs.add(new PermissionMatrixView.Diff(
                            role.code(), module, l1Level, hcLevel, "override"));
                }
            }
        }
        return diffs;
    }

    /** legacy (read_write/read/write/none) 与新格式 (rw/r/w/-) 统一成新格式。 */
    static String normalizeLevel(String level) {
        if (level == null) {
            return "-";
        }
        switch (level) {
            case "read_write": return "rw";
            case "read": return "r";
            case "write": return "w";
            case "none": return "-";
            default: return level;
        }
    }
}
