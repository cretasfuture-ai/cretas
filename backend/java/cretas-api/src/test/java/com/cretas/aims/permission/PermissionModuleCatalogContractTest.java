package com.cretas.aims.permission;

import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionModuleCatalog} 是 2026-09-07 刀 D 新立的「模块码 → 中文名」唯一来源。
 * 它替掉了两份手写表 ({@code PermissionInterceptor.moduleLabel} 的 17 条 switch、
 * {@code RoleController.DISPLAY_MODULES} 的 11 条 map)。
 *
 * <h3>这个测试守四件事</h3>
 * <ol>
 *   <li><b>无损</b>: {@code moduleLabel} 那 17 个码的返回值<b>逐字不变</b> ⇒ 那些 403 文案不变。
 *       期望值逐字抄自 refactor 前的 switch (git 上的 {@code fcdf1605b0}), ⛔ 不是从新表反抄的。</li>
 *   <li>🔴 <b>覆盖面与风险面互补</b>: 上面那张表<b>是从旧 switch 的 case 抄来的</b>, 所以它盯的恰好是
 *       <b>不会变</b>的那些码 —— 对「原本走 {@code default}、现在有名字了」的码<b>按构造是盲的</b>。
 *       刀 D 就在这里漏报过一次: {@code operations} 原本没有 case, 403 印的是 {@code [operations]},
 *       给它中文名之后变成 {@code [运营协调]}, 而当时的结论写的是「一个字都没变」。
 *       ⇒ {@code catalogCodesAreEitherPinnedOrRegistered} 要求本表的码集<b>恰好</b>等于
 *       「17 个既有 case ∪ {@link #CODES_NEWLY_LABELLED_BY_CUT_D}」—— 加一个码而不登记就红。</li>
 *   <li><b>覆盖</b>: 兜底矩阵声明的每个模块码都得有中文名 —— 加模块忘了补名字会红。
 *       这条不是恒真式: 阴性对照证明有码可以没有名字 (见 {@code 未登记的码原样返回})。</li>
 *   <li><b>不编造</b>: 表里没有的码原样返回码, 让缺口看得见。</li>
 * </ol>
 */
class PermissionModuleCatalogContractTest {

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

    /**
     * 刀 D <b>新给了中文名</b>的码 → 它在刀 D 之前的渲染结果。
     *
     * <p>旧 switch 对没有 case 的码是 {@code default: return moduleCode} ⇒ 旧值就是码本身。
     * 每加一条就意味着「某处 403 / 某张表的措辞变了」, 所以必须显式登记, ⛔ 不许默默加。
     *
     * <p>{@code operations}: {@code CustomerMaterialArrivalNoticeController} 有 3 处
     * {@code @RequirePermission({"operations:…"})} (:39/:49/:61) ⇒ 那些 403 从
     * {@code [operations] 模块} 变成 {@code [运营协调] 模块}。
     */
    private static final Map<String, String> CODES_NEWLY_LABELLED_BY_CUT_D = Map.of(
            "operations", "operations");

    @Test
    @DisplayName("覆盖面: 表里的码只能是「17 个既有 case」或「显式登记的新增」, 加码不登记就红")
    void catalogCodesAreEitherPinnedOrRegistered() {
        Set<String> expected = new LinkedHashSet<>(LABELS_BEFORE_CUT_D.keySet());
        expected.addAll(CODES_NEWLY_LABELLED_BY_CUT_D.keySet());

        assertThat(PermissionModuleCatalog.labelledCodes())
                .as("表里的码集必须恰好等于「旧 switch 的 17 个 case ∪ 登记过的新增」。"
                        + "多出来的码 = 有人给一个原本走 default 的码起了中文名, "
                        + "那会改掉它现有的 403 / 表格措辞 ⇒ 请登记进 CODES_NEWLY_LABELLED_BY_CUT_D 并说明影响面。"
                        + "少掉的码 = 删了中文名, 那会让它退回显示英文码。")
                .containsExactlyInAnyOrderElementsOf(expected);

        // 登记表自己要说实话: 登记的「旧值」必须真的是码本身 (旧 switch 没有它的 case)
        for (Map.Entry<String, String> e : CODES_NEWLY_LABELLED_BY_CUT_D.entrySet()) {
            assertThat(LABELS_BEFORE_CUT_D)
                    .as("%s 在旧 switch 里是有 case 的, 它不属于「新给名字」这一类", e.getKey())
                    .doesNotContainKey(e.getKey());
            assertThat(e.getValue())
                    .as("旧 switch 对没有 case 的码返回码本身, 登记的旧值应当等于码")
                    .isEqualTo(e.getKey());
            assertThat(PermissionModuleCatalog.label(e.getKey()))
                    .as("%s 登记为「新给了名字」, 那它现在就不该还等于码本身", e.getKey())
                    .isNotEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("无损: 403 文案用的那 17 个既有 case, 中文名逐字未变")
    void refactorIsLossless() {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : LABELS_BEFORE_CUT_D.entrySet()) {
            String now = PermissionModuleCatalog.label(e.getKey());
            if (!e.getValue().equals(now)) {
                changed.add(e.getKey() + ": 「" + e.getValue() + "」→「" + now + "」");
            }
        }
        assertThat(changed)
                .as("刀 D 承诺「这 17 个既有 case 的措辞逐字不变」(⛔ 不是「所有码都不变」—— "
                        + "operations 变了, 登记在 CODES_NEWLY_LABELLED_BY_CUT_D)。"
                        + "改这些码的中文名会改掉线上已经发出去的拒绝文案, 要改先确认那不是 regression: %s", changed)
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
