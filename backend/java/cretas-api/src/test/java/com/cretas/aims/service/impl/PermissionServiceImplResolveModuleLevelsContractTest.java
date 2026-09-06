package com.cretas.aims.service.impl;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906 契约:
 * 登录响应里的 {@code modulePermissions} (= {@link PermissionServiceImpl#resolveModuleLevels})
 * 必须与 {@code @RequirePermission} 真正用的 {@link PermissionServiceImpl#hasPermission}
 * <b>逐格一致</b> —— RN 端据此判断按钮显不显示, 两边不一致就是「按钮出现 → 后端 403」
 * 或「按钮不出现 → 其实能做」。
 *
 * <p>三层都用假数据钉住 (L2 工厂覆盖 / L1 platform_role_permissions / 硬编码兜底),
 * 并且<b>故意让 L1 与硬编码在若干格上不同</b> —— 这些格正是 2026-09-06 量出来的
 * 29 处 RN↔prod L1 漂移里的代表 (sales_manager.warehouse: RN r / L1 -,
 * permission_admin.sales: RN rw / L1 -, operator.production: RN w / L1 rw)。
 * 若 L1 与硬编码相同, 「L1 赢」的断言就是恒真式 (形态 B⁴), 所以对照必须不同。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionServiceImplResolveModuleLevelsContractTest {

    @Mock
    private PlatformRolePermissionRepository platformRepo;
    @Mock
    private FactoryModuleConfigRepository factoryConfigRepo;

    @InjectMocks
    private PermissionServiceImpl service;

    /** 假 L1 表: role → module → level. 两条仓库查询 (按角色列出 / 按角色+模块取一格) 都从它答, 与 prod 同一张表同义. */
    private final Map<String, Map<String, String>> fakeL1 = new HashMap<>();
    /** 假 L2: factoryId → (role → module → level). */
    private final Map<String, Map<String, Map<String, String>>> fakeL2 = new HashMap<>();

    private long nextUserId = 1000;

    @BeforeEach
    void wireFakeTables() {
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(0);
            List<PlatformRolePermission> rows = new ArrayList<>();
            fakeL1.getOrDefault(role, Collections.emptyMap()).forEach((module, level) -> {
                PlatformRolePermission row = new PlatformRolePermission();
                row.setRoleCode(role);
                row.setModuleCode(module);
                row.setPermissionLevel(level);
                rows.add(row);
            });
            return rows;
        });
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String role = inv.getArgument(0);
            String module = inv.getArgument(1);
            String level = fakeL1.getOrDefault(role, Collections.emptyMap()).get(module);
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(role);
            row.setModuleCode(module);
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        when(factoryConfigRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenAnswer(inv -> {
            String factoryId = inv.getArgument(0);
            Map<String, Map<String, String>> override = fakeL2.get(factoryId);
            if (override == null) return Collections.emptyList();
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(factoryId);
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
    }

    private User user(FactoryUserRole role, String factoryId) {
        User u = new User();
        u.setId(nextUserId++);   // hasPermission 有 userId 级缓存, 每个用户独立 id 免得串
        u.setRoleCode(role.name());
        u.setFactoryId(factoryId);
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> hardcoded(FactoryUserRole role) {
        Map<FactoryUserRole, Map<String, String>> matrix =
                (Map<FactoryUserRole, Map<String, String>>) ReflectionTestUtils.getField(PermissionServiceImpl.class, "PERMISSION_MATRIX");
        return matrix.getOrDefault(role, Collections.emptyMap());
    }

    private static boolean levelReads(String level) {
        return "rw".equals(level) || "r".equals(level);
    }

    private static boolean levelWrites(String level) {
        return "rw".equals(level) || "w".equals(level);
    }

    @Test
    @DisplayName("L1 有行且与硬编码不同 ⇒ 登录响应那一格是 L1 的值 (permission_admin.sales: 硬编码 read_write / prod L1 -)")
    void l1RowWinsOverHardcodedMirror() {
        // 阳性对照前提: 两份在这一格必须不同, 否则本断言守不住任何东西。
        // permission_admin.sales 正是 #640 终审量出的那一格: RN 旧矩阵 rw、prod L1 -。
        assertThat(hardcoded(FactoryUserRole.permission_admin).get("sales")).isEqualTo("read_write");
        fakeL1.put("permission_admin", Map.of("sales", "-", "operations", "rw", "dashboard", "-"));

        Map<String, String> levels = service.resolveModuleLevels(user(FactoryUserRole.permission_admin, "F006"));
        System.out.println("OBSERVED permission_admin levels = " + levels);

        assertThat(levels.get("sales")).isEqualTo("-");
        assertThat(levels.get("operations")).isEqualTo("rw");
        assertThat(levels.get("dashboard")).isEqualTo("-");
    }

    @Test
    @DisplayName("L1 无行的模块 ⇒ 硬编码兜底透出 (与 hasPermission 的 fallback 同一格)")
    void hardcodedShowsThroughWhereL1HasNoRow() {
        fakeL1.put("sales_manager", Map.of("sales", "rw"));   // 只给一行, report/rd 没有 L1 行

        Map<String, String> levels = service.resolveModuleLevels(user(FactoryUserRole.sales_manager, "F006"));
        System.out.println("OBSERVED (L1 only sales) sales_manager levels = " + levels);

        Map<String, String> hard = hardcoded(FactoryUserRole.sales_manager);
        assertThat(hard.get("report")).isEqualTo("read");
        assertThat(levels.get("report")).isEqualTo("r");   // hardcoded "read" normalize → r
        assertThat(levels.get("rd")).isEqualTo("rw");      // hardcoded "read_write" normalize → rw
        assertThat(levels.keySet()).containsAll(hard.keySet());
    }

    @Test
    @DisplayName("L2 工厂覆盖 ⇒ 压过 L1 (与 hasPermission 的 L2 → L1 顺序一致)")
    void l2OverridesL1() {
        fakeL1.put("sales_manager", Map.of("sales", "rw", "warehouse", "-"));
        fakeL2.put("F006", Map.of("sales_manager", Map.of("warehouse", "read_write")));

        Map<String, String> withOverride = service.resolveModuleLevels(user(FactoryUserRole.sales_manager, "F006"));
        Map<String, String> otherFactory = service.resolveModuleLevels(user(FactoryUserRole.sales_manager, "F007"));
        System.out.println("OBSERVED F006 warehouse=" + withOverride.get("warehouse") + " F007 warehouse=" + otherFactory.get("warehouse"));

        assertThat(withOverride.get("warehouse")).isEqualTo("rw");
        assertThat(otherFactory.get("warehouse")).isEqualTo("-");   // 阴性对照: 别的工厂不受 F006 覆盖影响
    }

    @Test
    @DisplayName("契约: 每个角色、map 里每一格, hasPermission(module:read/write) 与该格级别一致; map 外的模块一律 deny")
    void everyCellAgreesWithHasPermissionForEveryRole() {
        // 给每个角色都种几行故意与硬编码不同的 L1, 让「L1 赢」在每个角色上都有可能红
        for (FactoryUserRole role : FactoryUserRole.values()) {
            Map<String, String> rows = new HashMap<>();
            rows.put("dashboard", "-");
            rows.put("sales", "w");
            rows.put("quality", "rw");
            rows.put("report", "r");   // 硬编码里多数角色没有 report 这一列 ⇒ 只有 L1 知道它
            fakeL1.put(role.name(), rows);
        }
        // 再给 F006 一个 L2 覆盖, 覆盖的是 L1 也有的格
        fakeL2.put("F006", Map.of("operator", Map.of("quality", "-"), "viewer", Map.of("sales", "read_write")));

        int cellsChecked = 0;
        int rolesChecked = 0;
        for (FactoryUserRole role : FactoryUserRole.values()) {
            User u = user(role, "F006");
            Map<String, String> levels = service.resolveModuleLevels(u);
            if (role == FactoryUserRole.unactivated) {
                assertThat(levels).isEmpty();
                assertThat(service.hasPermission(u, "sales:read")).isFalse();
                continue;
            }
            rolesChecked++;
            assertThat(levels).as("role %s must have at least the seeded L1 modules", role).containsKeys("dashboard", "sales", "quality", "report");
            for (Map.Entry<String, String> cell : levels.entrySet()) {
                String module = cell.getKey();
                String level = cell.getValue();
                assertThat(level).as("%s.%s", role, module).isIn("rw", "r", "w", "-");
                if (PermissionServiceImpl.MODULE_LEVEL_WILDCARD.equals(module)) continue;
                assertThat(service.hasPermission(u, module + ":read"))
                        .as("%s %s:read vs level %s", role, module, level).isEqualTo(levelReads(level));
                assertThat(service.hasPermission(u, module + ":write"))
                        .as("%s %s:write vs level %s", role, module, level).isEqualTo(levelWrites(level));
                cellsChecked++;
            }
            boolean superAdmin = role == FactoryUserRole.factory_super_admin || role == FactoryUserRole.platform_admin;
            // map 外的模块: 非超管一律 deny —— RN 端「map 里没有 = 没权限」正是靠这一条
            assertThat(service.hasPermission(u, "module_nobody_registered:read")).isEqualTo(superAdmin);
            assertThat(levels.containsKey(PermissionServiceImpl.MODULE_LEVEL_WILDCARD)).isEqualTo(superAdmin);
        }
        System.out.println("OBSERVED contract cells checked = " + cellsChecked + " across roles = " + rolesChecked);
        assertThat(rolesChecked).isEqualTo(FactoryUserRole.values().length - 1);
        assertThat(cellsChecked).isGreaterThan(rolesChecked * 4);   // 每个角色至少种了 4 行 L1
    }

    @Test
    @DisplayName("超管: 带 \"*\"=rw, 所有已知模块 rw —— 与 hasPermission 对任何 code 都放行同口径")
    void superAdminGetsWildcard() {
        fakeL1.put("factory_super_admin", Map.of("sales", "-", "dashboard", "r"));   // 就算 L1 写了 -, 超管分支也不看

        Map<String, String> levels = service.resolveModuleLevels(user(FactoryUserRole.factory_super_admin, "F006"));
        System.out.println("OBSERVED factory_super_admin levels = " + levels);

        assertThat(levels.get(PermissionServiceImpl.MODULE_LEVEL_WILDCARD)).isEqualTo("rw");
        assertThat(levels.get("sales")).isEqualTo("rw");
        assertThat(levels.values()).allMatch("rw"::equals);
    }

    @Test
    @DisplayName("null 用户 / 未激活 / 无角色 ⇒ 空 map, 永不 null")
    void emptyForNullOrUnactivated() {
        assertThat(service.resolveModuleLevels(null)).isEmpty();
        assertThat(service.resolveModuleLevels(user(FactoryUserRole.unactivated, "F006"))).isEmpty();
        User noRole = new User();
        noRole.setId(nextUserId++);
        noRole.setFactoryId("F006");
        assertThat(service.resolveModuleLevels(noRole)).isEmpty();
    }

    @Test
    @DisplayName("仓库查询抛异常 ⇒ 退到硬编码兜底而不是抛出 (与 resolveLayer1 的 catch 同口径)")
    void repositoryFailureFallsBackToHardcoded() {
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenThrow(new RuntimeException("db down"));
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenThrow(new RuntimeException("db down"));

        User u = user(FactoryUserRole.sales_manager, "F006");
        Map<String, String> levels = service.resolveModuleLevels(u);
        System.out.println("OBSERVED (db down) sales_manager levels = " + levels);

        Set<String> hardKeys = hardcoded(FactoryUserRole.sales_manager).keySet();
        assertThat(levels.keySet()).isEqualTo(hardKeys);
        for (String module : hardKeys) {
            assertThat(service.hasPermission(u, module + ":read")).isEqualTo(levelReads(levels.get(module)));
        }
    }
}
