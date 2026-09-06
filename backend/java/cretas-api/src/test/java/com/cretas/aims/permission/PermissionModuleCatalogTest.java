package com.cretas.aims.permission;

import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionModuleCatalog} 是 2026-09-07 刀 D 新立的「模块码 → 中文名」唯一来源。
 * 它替掉了两份手写表 ({@code PermissionInterceptor.moduleLabel} 的 17 条 switch、
 * {@code RoleController.DISPLAY_MODULES} 的 11 条 map)。
 *
 * <h3>这个测试守三件事</h3>
 * <ol>
 *   <li><b>无损</b>: {@code moduleLabel} 那 17 个码的返回值<b>一个字都没变</b> ⇒ 403 文案不变。
 *       期望值逐字抄自 refactor 前的 switch (git 上的 {@code fcdf1605b0}), ⛔ 不是从新表反抄的。</li>
 *   <li><b>覆盖</b>: 兜底矩阵声明的每个模块码都得有中文名 —— 加模块忘了补名字会红。
 *       这条不是恒真式: 阴性对照证明有码可以没有名字 (见 {@code 未登记的码原样返回})。</li>
 *   <li><b>不编造</b>: 表里没有的码原样返回码, 让缺口看得见。</li>
 * </ol>
 */
class PermissionModuleCatalogTest {

    /**
     * refactor 前 {@code PermissionInterceptor.moduleLabel} 那个 switch 的全部 17 个分支,
     * 逐字抄写。⛔ 不许「照着新表填一遍」—— 那样这条断言就是恒真式。
     */
    private static final Map<String, String> LABELS_BEFORE_CUT_D = new LinkedHashMap<>();
    static {
        LABELS_BEFORE_CUT_D.put("dashboard", "首页");
        LABELS_BEFORE_CUT_D.put("production", "生产管理");
        LABELS_BEFORE_CUT_D.put("warehouse", "仓储管理");
        LABELS_BEFORE_CUT_D.put("quality", "质量管理");
        LABELS_BEFORE_CUT_D.put("procurement", "采购管理");
        LABELS_BEFORE_CUT_D.put("sales", "销售管理");
        LABELS_BEFORE_CUT_D.put("hr", "人事管理");
        LABELS_BEFORE_CUT_D.put("equipment", "设备管理");
        LABELS_BEFORE_CUT_D.put("finance", "财务管理");
        LABELS_BEFORE_CUT_D.put("system", "系统管理");
        LABELS_BEFORE_CUT_D.put("analytics", "数据分析");
        LABELS_BEFORE_CUT_D.put("scheduling", "智能调度");
        LABELS_BEFORE_CUT_D.put("work_report", "工作报告");
        LABELS_BEFORE_CUT_D.put("inventory", "库存管理");
        LABELS_BEFORE_CUT_D.put("report", "报表");
        LABELS_BEFORE_CUT_D.put("rd", "研发管理");
        LABELS_BEFORE_CUT_D.put("restaurant", "餐饮管理");
    }

    @Test
    @DisplayName("无损: 403 文案用的那 17 个码, 中文名一个字都没变")
    void refactorIsLossless() {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : LABELS_BEFORE_CUT_D.entrySet()) {
            String now = PermissionModuleCatalog.label(e.getKey());
            if (!e.getValue().equals(now)) {
                changed.add(e.getKey() + ": 「" + e.getValue() + "」→「" + now + "」");
            }
        }
        assertThat(changed)
                .as("刀 D 承诺「403 文案一个字不变」。改这些码的中文名会改掉线上已经发出去的拒绝文案, "
                        + "要改先确认那不是 regression: %s", changed)
                .isEmpty();
    }

    @Test
    @DisplayName("覆盖: 兜底矩阵声明的每个模块都有中文名 (加模块忘补名字会红)")
    void everyHardcodedModuleHasLabel() {
        List<String> hardcoded = PermissionServiceImpl.hardcodedModules();
        // 阳性对照: 清单本身不能是空的, 否则下面那条断言什么都没检查
        assertThat(hardcoded).as("INSTRUMENT DEAD — 兜底模块清单读到 0 条").hasSizeGreaterThan(10);

        List<String> unlabelled = hardcoded.stream()
                .filter(m -> !PermissionModuleCatalog.hasLabel(m))
                .toList();
        assertThat(unlabelled)
                .as("这些模块码在 PermissionModuleCatalog 里没有中文名, 会在角色权限页上"
                        + "原样显示成英文码: %s", unlabelled)
                .isEmpty();
    }

    @Test
    @DisplayName("不编造: 未登记的码原样返回, 且排在最后")
    void unknownCodeFallsBackToTheCodeItself() {
        assertThat(PermissionModuleCatalog.hasLabel("module_nobody_registered")).isFalse();
        assertThat(PermissionModuleCatalog.label("module_nobody_registered")).isEqualTo("module_nobody_registered");
        assertThat(PermissionModuleCatalog.columnOrder("module_nobody_registered")).isEqualTo(Integer.MAX_VALUE);
        assertThat(PermissionModuleCatalog.label(null)).isNull();
    }

    @Test
    @DisplayName("列序: 已登记的码两两不同且首页排第一 (侧边栏顺序)")
    void columnOrderIsATotalOrder() {
        List<String> codes = PermissionModuleCatalog.labelledCodes();
        assertThat(codes).as("INSTRUMENT DEAD — 表读到空").isNotEmpty();
        assertThat(codes.stream().map(PermissionModuleCatalog::columnOrder).distinct().count())
                .as("列序有重复")
                .isEqualTo(codes.size());
        assertThat(codes.get(0))
                .as("列序按侧边栏走, 首页 (dashboard) 是侧边栏第一项")
                .isEqualTo("dashboard");
    }
}
