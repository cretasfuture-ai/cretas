package com.cretas.aims.ai.tool.impl.material;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领用原料 (material_batch_use) 的工具层角色名单 —— 与 App 同口径 (2026-10-05 一线 AI 入口)。
 *
 * <p>App 的 {@code POST /material-batches/{id}/use} 要 warehouse/inventory 读写;
 * 一线角色里只有仓库员带这个权限。工具声明了 requiresPermission 且没有权限码,
 * 所以 ToolDispatchService 走的就是这里的 hasPermission —— 这是那道闸本身, 不是它的模拟。
 */
class MaterialBatchUseToolRoleTest {

    private final MaterialBatchUseTool tool = new MaterialBatchUseTool();

    @Test
    @DisplayName("前提: 工具声明需要权限、且没有权限码 ⇒ 名单就是闸")
    void roleListIsTheGate() {
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.getRequiredPermissions()).isEmpty();
    }

    @Test
    @DisplayName("仓库员能领用 (App 里本来就能)")
    void warehouseWorkerAllowed() {
        assertThat(tool.hasPermission("warehouse_worker")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory_super_admin", "warehouse_manager", "platform_admin", "factory_admin", "super_admin"})
    @DisplayName("原有名单不变 (对照)")
    void existingRolesUnchanged(String role) {
        assertThat(tool.hasPermission(role)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"operator", "yield_operator", "quality_inspector", "viewer", ""})
    @DisplayName("App 里领用不了的角色, AI 里也不行")
    void rolesWithoutWarehouseWriteDenied(String role) {
        assertThat(tool.hasPermission(role)).isFalse();
    }

    @Test
    @DisplayName("没有角色 ⇒ 拒")
    void nullRoleDenied() {
        assertThat(tool.hasPermission(null)).isFalse();
    }
}
