package com.cretas.aims.service.permission;

import com.cretas.aims.dto.permission.PermissionMatrixView;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 分歧检测器本身的判据 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906)。
 *
 * <p>⚠️ 类名以 {@code ContractTest} 结尾<b>不是随手起的</b>: {@code java-gate-pr.yml} 的
 * fast-contracts 那一步按 {@code -Dtest='*ContractTest,...,*PermissionTest'} 选类,
 * 叫别的名字它在 CI 上<b>一次都不会跑</b> (本轮实测: 原名 {@code ...ServiceTest} 在
 * PR 的 Java Gate 日志里 grep 命中 0 次, 而同批的 {@code ...PermissionTest} 命中并
 * 打出 Tests run: 5)。⛔ 改名前先确认它还落在那个 pattern 里。
 *
 * <p>硬约束 9: prod 上 diff 数不为 0 是个会变的读数, 不能拿它当「检测器工作正常」的证明。
 * 这里用<b>构造的 fixture</b> 分别钉住三种格子, 其中<b>阴性</b>那一条 (两侧一致 ⇒ 不报)
 * 是防止检测器退化成「什么都报」的那一半 —— 少了它, 一个 {@code return allCells()}
 * 的实现也能让所有阳性断言通过。
 */
@ExtendWith(MockitoExtension.class)
class PermissionMatrixViewServiceContractTest {

    @Mock private PlatformRolePermissionRepository platformRepo;
    @Mock private FactoryModuleConfigRepository factoryConfigRepo;
    @InjectMocks private PermissionMatrixViewService service;

    private static final List<PermissionMatrixView.RoleRef> ROLES = List.of(
            new PermissionMatrixView.RoleRef("viewer", "查看者", "factory", 50),
            new PermissionMatrixView.RoleRef("operator", "操作员", "factory", 30));

    @Test
    void l1RowThatContradictsTheFallbackIsAnOverrideDiff() {
        List<PermissionMatrixView.Diff> diffs = PermissionMatrixViewService.computeDiffs(
                ROLES,
                new java.util.TreeSet<>(List.of("finance")),
                Map.of("viewer", Map.of("finance", "-")),
                Map.of("viewer", Map.of("finance", "r")));

        assertThat(diffs).containsExactly(
                new PermissionMatrixView.Diff("viewer", "finance", "-", "r", "override"));
    }

    @Test
    void missingL1RowWithAFallbackGrantIsAFallbackDiffAndKeepsL1Null() {
        List<PermissionMatrixView.Diff> diffs = PermissionMatrixViewService.computeDiffs(
                ROLES,
                new java.util.TreeSet<>(List.of("production")),
                Map.of(),
                Map.of("operator", Map.of("production", "rw")));

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).role()).isEqualTo("operator");
        assertThat(diffs.get(0).kind()).isEqualTo("fallback");
        assertThat(diffs.get(0).l1())
                .as("L1 没有这一行 ⇒ 必须是 null, ⛔ 不能填成 '-' (那会与「L1 明确说不给」混淆)")
                .isNull();
        assertThat(diffs.get(0).hardcoded()).isEqualTo("rw");
    }

    /** 阴性对照 1: 两侧一致 ⇒ 一条都不报。 */
    @Test
    void identicalCellsProduceNoDiff() {
        List<PermissionMatrixView.Diff> diffs = PermissionMatrixViewService.computeDiffs(
                ROLES,
                new java.util.TreeSet<>(List.of("quality")),
                Map.of("viewer", Map.of("quality", "r"), "operator", Map.of("quality", "r")),
                Map.of("viewer", Map.of("quality", "r"), "operator", Map.of("quality", "r")));

        assertThat(diffs).isEmpty();
    }

    /** 阴性对照 2: 两侧都没有这一格 (L1 无行 + 兜底无键) ⇒ 不是分歧, 不报。 */
    @Test
    void cellAbsentFromBothSidesProducesNoDiff() {
        List<PermissionMatrixView.Diff> diffs = PermissionMatrixViewService.computeDiffs(
                ROLES,
                new java.util.TreeSet<>(List.of("hr")),
                Map.of(),
                Map.of());

        assertThat(diffs).isEmpty();
    }

    /** legacy 与新格式必须先归一化, 否则 read_write vs rw 会被当成分歧 (假阳性洪水)。 */
    @Test
    void legacyAndNewLevelSpellingsAreNormalizedBeforeComparison() {
        assertThat(PermissionMatrixViewService.normalizeLevel("read_write")).isEqualTo("rw");
        assertThat(PermissionMatrixViewService.normalizeLevel("read")).isEqualTo("r");
        assertThat(PermissionMatrixViewService.normalizeLevel("write")).isEqualTo("w");
        assertThat(PermissionMatrixViewService.normalizeLevel("none")).isEqualTo("-");
        assertThat(PermissionMatrixViewService.normalizeLevel("rw")).isEqualTo("rw");
        assertThat(PermissionMatrixViewService.normalizeLevel(null)).isEqualTo("-");
    }

    @Test
    void buildIsReadOnlyAndSurfacesLayer2ToggleAndRoleOverrideSeparately() {
        PlatformRolePermission row = new PlatformRolePermission();
        row.setRoleCode("viewer");
        row.setModuleCode("finance");
        row.setPermissionLevel("-");
        when(platformRepo.findByDeletedAtIsNull()).thenReturn(List.of(row));

        FactoryModuleConfig permissionsPseudoRow = FactoryModuleConfig.builder()
                .factoryId("F006")
                .moduleCode("__permissions__")
                .configVersion(1)
                .roleModuleOverride(Map.of(
                        "production_manager", Map.of("production_report_correction", "rw")))
                .build();
        FactoryModuleConfig disabledModule = FactoryModuleConfig.builder()
                .factoryId("F006")
                .moduleCode("sales_order")
                .configVersion(1)
                .enabled(false)
                .roleModuleOverride(Map.of())
                .build();
        when(factoryConfigRepo.findByFactoryIdAndConfigVersion("F006", 1))
                .thenReturn(List.of(permissionsPseudoRow, disabledModule));

        PermissionMatrixView view = service.build("F006");

        assertThat(view.factoryId()).isEqualTo("F006");
        assertThat(view.l2RoleOverrides()).containsExactly(
                new PermissionMatrixView.RoleOverride(
                        "production_manager", "production_report_correction", "rw"));
        assertThat(view.l2Overrides())
                .as("__permissions__ 是承载 role 覆盖的伪模块, ⛔ 不能当成一个「被关掉的模块」渲染")
                .containsExactly(new PermissionMatrixView.ModuleToggle("sales_order", false));
        assertThat(view.modules())
                .as("模块轴取并集: L1 的 finance + 兜底的全部 + L2 带出来的两个")
                .contains("finance", "production_report_correction", "sales_order", "operations");
        assertThat(view.modulesWithoutL1())
                .as("L1 一行都没有的模块要单独点名, 否则新模块会静默地没有列")
                .contains("production_report_correction", "sales_order")
                .doesNotContain("finance");
        assertThat(view.roles()).extracting(PermissionMatrixView.RoleRef::code)
                .contains("platform_admin", "factory_super_admin", "viewer", "restaurant_owner");
    }

    @Test
    void rolesAreGroupedIntoPlatformFactoryAndRestaurant() {
        assertThat(PermissionMatrixViewService.groupOf(
                "platform_admin", com.cretas.aims.entity.enums.FactoryUserRole.platform_admin))
                .isEqualTo("platform");
        assertThat(PermissionMatrixViewService.groupOf(
                "restaurant_owner", com.cretas.aims.entity.enums.FactoryUserRole.restaurant_owner))
                .isEqualTo("restaurant");
        assertThat(PermissionMatrixViewService.groupOf(
                "warehouse_worker", com.cretas.aims.entity.enums.FactoryUserRole.warehouse_worker))
                .isEqualTo("factory");
    }

    /**
     * 模块轴必须是<b>派生</b>的 (L1 ∪ 兜底 ∪ L2), ⛔ 不是第 8 份手写清单。
     *
     * <p>判据: 给 L1 塞一个谁都没听说过的 module_code, 它必须出现在轴上。
     * 一个写死清单的实现在这里必红。
     */
    @Test
    void moduleAxisIsDerivedFromDataNotFromAHardcodedEighthList() {
        PlatformRolePermission exotic = new PlatformRolePermission();
        exotic.setRoleCode("viewer");
        exotic.setModuleCode("brand_new_module_nobody_declared");
        exotic.setPermissionLevel("r");
        when(platformRepo.findByDeletedAtIsNull()).thenReturn(List.of(exotic));
        when(factoryConfigRepo.findByFactoryIdAndConfigVersion("F006", 1)).thenReturn(List.of());

        PermissionMatrixView view = service.build("F006");

        assertThat(view.modules()).contains("brand_new_module_nobody_declared");
        assertThat(view.diffs())
                .as("L1 给了 r 而兜底矩阵里根本没这个模块 ⇒ override 分歧")
                .anySatisfy(d -> {
                    assertThat(d.module()).isEqualTo("brand_new_module_nobody_declared");
                    assertThat(d.role()).isEqualTo("viewer");
                    assertThat(d.kind()).isEqualTo("override");
                    assertThat(d.hardcoded()).isEqualTo("-");
                });
    }
}
