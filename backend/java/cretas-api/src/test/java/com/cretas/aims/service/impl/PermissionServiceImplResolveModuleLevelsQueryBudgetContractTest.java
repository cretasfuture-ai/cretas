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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #663 终审 follow-up: 登录路径 {@link PermissionServiceImpl#resolveModuleLevels} 的<b>查询预算</b>与
 * <b>三层互异</b>正向对照。
 *
 * <p>之前每格重跑 L2 list 查询 + JSONB 反序列化 + L1 单行 ⇒ 一次登录 20+ 次往返。现在一次调用只允许:
 * L2 list 1 次、L1 list 1 次、L1 单行 0 次。变异「resolveModuleLevels 不传快照」⇒ 单行查询次数从 0 涨到模块数 ⇒ 红。
 *
 * <p>三层互异: 同一格 L2 / L1 / 硬编码三个值都不同, 登录 map 与 hasPermission 必须都取 L2 —— 这是
 * `PermissionServiceImplResolveModuleLevelsContractTest` 里没有的形状 (那里 L2 与硬编码碰巧可能相同)。
 * 契约测试本身一字不改 (organizer 要求)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionServiceImplResolveModuleLevelsQueryBudgetContractTest {

    @Mock private PlatformRolePermissionRepository platformRepo;
    @Mock private FactoryModuleConfigRepository factoryConfigRepo;
    @InjectMocks private PermissionServiceImpl service;

    private final Map<String, Map<String, String>> fakeL1 = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> fakeL2 = new HashMap<>();
    private long nextId = 5000;

    @BeforeEach
    void wire() {
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString())).thenAnswer(inv -> {
            List<PlatformRolePermission> rows = new ArrayList<>();
            fakeL1.getOrDefault(inv.getArgument(0), Collections.emptyMap()).forEach((m, l) -> {
                PlatformRolePermission r = new PlatformRolePermission();
                r.setRoleCode(inv.getArgument(0)); r.setModuleCode(m); r.setPermissionLevel(l);
                rows.add(r);
            });
            return rows;
        });
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String l = fakeL1.getOrDefault(inv.getArgument(0), Collections.emptyMap()).get(inv.getArgument(1));
            if (l == null) return Optional.empty();
            PlatformRolePermission r = new PlatformRolePermission();
            r.setRoleCode(inv.getArgument(0)); r.setModuleCode(inv.getArgument(1)); r.setPermissionLevel(l);
            return Optional.of(r);
        });
        when(factoryConfigRepo.findByFactoryIdAndConfigVersion(anyString(), eq(1))).thenAnswer(inv -> {
            Map<String, Map<String, String>> override = fakeL2.get(inv.getArgument(0));
            if (override == null) return Collections.emptyList();
            FactoryModuleConfig cfg = new FactoryModuleConfig();
            cfg.setFactoryId(inv.getArgument(0));
            cfg.setRoleModuleOverride(override);
            return List.of(cfg);
        });
    }

    private User user(FactoryUserRole role) {
        User u = new User();
        u.setId(nextId++);
        u.setRoleCode(role.name());
        u.setFactoryId("F006");
        return u;
    }

    @Test
    @DisplayName("三层互异: 同一格 L2=r / L1=- / 硬编码=read_write ⇒ 登录 map 与 hasPermission 都取 L2 (r: 能读不能写)")
    void threeLayersDifferAndL2Wins() {
        // sales_manager.sales: 硬编码 read_write (阳性前提), L1 '-', L2 'r' —— 三个都不同
        assertThat(PermissionServiceImpl.hardcodedMatrixSnapshot().get("sales_manager").get("sales")).isEqualTo("read_write");
        fakeL1.put("sales_manager", Map.of("sales", "-", "dashboard", "r"));
        fakeL2.put("F006", Map.of("sales_manager", Map.of("sales", "read")));

        User u = user(FactoryUserRole.sales_manager);
        Map<String, String> levels = service.resolveModuleLevels(u);
        System.out.println("OBSERVED three-layer sales_manager levels = " + levels);

        assertThat(levels.get("sales")).isEqualTo("r");
        assertThat(service.hasPermission(u, "sales:read")).isTrue();
        assertThat(service.hasPermission(u, "sales:write")).isFalse();
        // 另外两格: 只有 L1 的 dashboard (r), 只有硬编码的 report (硬编码 read ⇒ r)
        assertThat(levels.get("dashboard")).isEqualTo("r");
        assertThat(levels.get("report")).isEqualTo("r");
        assertThat(service.hasPermission(u, "report:read")).isTrue();
    }

    @Test
    @DisplayName("查询预算: 一次 resolveModuleLevels = L2 list ×1 + L1 list ×1 + L1 单行 ×0 (之前每格一次)")
    void oneLoginCostsTwoQueries() {
        Map<String, String> l1 = new HashMap<>();
        for (String m : new String[]{"dashboard", "sales", "warehouse", "finance", "hr", "report", "rd", "inventory"}) l1.put(m, "r");
        fakeL1.put("sales_manager", l1);
        fakeL2.put("F006", Map.of("sales_manager", Map.of("sales", "rw")));
        clearInvocations(platformRepo, factoryConfigRepo);

        Map<String, String> levels = service.resolveModuleLevels(user(FactoryUserRole.sales_manager));
        System.out.println("OBSERVED modules=" + levels.size());
        assertThat(levels.size()).isGreaterThanOrEqualTo(8);   // 阳性前提: 确实有很多格要解析

        verify(factoryConfigRepo, times(1)).findByFactoryIdAndConfigVersion("F006", 1);
        verify(platformRepo, times(1)).findByRoleCodeAndDeletedAtIsNull("sales_manager");
        verify(platformRepo, never()).findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    @DisplayName("鉴权路径不受影响: hasPermission 仍逐格查 (单行 L1 ×1, L2 list ×1), 且有缓存")
    void hasPermissionStillQueriesPerCell() {
        fakeL1.put("viewer", Map.of("finance", "r"));
        clearInvocations(platformRepo, factoryConfigRepo);
        User u = user(FactoryUserRole.viewer);

        assertThat(service.hasPermission(u, "finance:read")).isTrue();
        verify(platformRepo, times(1)).findByRoleCodeAndModuleCodeAndDeletedAtIsNull("viewer", "finance");
        verify(platformRepo, never()).findByRoleCodeAndDeletedAtIsNull(anyString());
        // DisplayName 说的「L2 list ×1」也要真 verify (#683 终审备注): 逐格路径每格查一次 L2 配置
        verify(factoryConfigRepo, times(1)).findByFactoryIdAndConfigVersion("F006", 1);

        assertThat(service.hasPermission(u, "finance:read")).isTrue();   // 第二次命中缓存, 不再查
        verify(platformRepo, times(1)).findByRoleCodeAndModuleCodeAndDeletedAtIsNull("viewer", "finance");
    }
}
