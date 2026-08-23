package com.cretas.aims.service.bom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「两侧分摊比例是不是同一个值」—— null 与 null 算相等，且只许有一份判据。
 *
 * <h2>它修的是什么（2026-08-19 prod 实测，500）</h2>
 *
 * <p>坎 3 起，「≥2 个都不是副产的终端」这一支刻意返回 {@code ratio = null}
 * （{@code REPORTED_MASS}，设计期不可判，⛔ 不兜一个假数）。
 * 于是快照侧与 BOM 行两边<b>一致地</b>是 null —— 那是「一致」。
 *
 * <p>而 {@code requireCompleteActiveFamily} 当时直接
 * {@code output.costAllocationRatio().compareTo(...)}：
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "java.math.BigDecimal.compareTo(...)"
 *   because the return value of "…TerminalOutput.costAllocationRatio()" is null
 *     at BomWorkflowRevisionService.requireCompleteActiveFamily(:681)
 *     ← BomRecipeServiceImpl.synchronizeActiveBomToWorkflowRevision(:748)
 *     ← WorkflowBomSynchronizationService.synchronizeForPublish(:331)
 *     ← ProductProcessWorkflowServiceImpl.publishAndActivate(:246)
 * ⇒ 发布多产出工作流 500「系统处理异常」(追踪码 71D81F80)
 * </pre>
 *
 * <p>⚠️ 同语义的 helper 当时**已经存在**于 {@code BomRecipeServiceImpl.sameRatio}，
 * 只是没被用到这一处 —— 形态 D 的教科书长相：<b>同一个东西有两份，其中一份是对的。</b>
 */
@DisplayName("契约 — 分摊比例比较的 null 安全, 且判据只有一份")
class AllocationRatioNullSafetyContractTest {

    private static final Path REVISION = Path.of("src", "main", "java", "com", "cretas", "aims",
            "service", "bom", "BomWorkflowRevisionService.java");
    private static final Path RECIPE = Path.of("src", "main", "java", "com", "cretas", "aims",
            "service", "bom", "impl", "BomRecipeServiceImpl.java");

    // ---------- 行为 ----------

    @Test
    @DisplayName("🔴 两边都不可判(null) = 一致 —— 直接 compareTo 会 NPE, 那正是 2026-08-19 那个 500")
    void bothUndecidableCountsAsMatching() {
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(null, null)).isTrue();
    }

    @Test
    @DisplayName("一边可判一边不可判 = 不一致 —— ⛔ 不许把 null 当成 0 或 100 去比")
    void oneSidedNullIsAMismatch() {
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(null, new BigDecimal("100"))).isFalse();
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(new BigDecimal("100"), null)).isFalse();
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(null, BigDecimal.ZERO)).isFalse();
    }

    @Test
    @DisplayName("同值不同标度算相等 —— DB 是 numeric(7,4), 读回来是 100.0000")
    void scaleDoesNotChangeTheAnswer() {
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(
                new BigDecimal("100"), new BigDecimal("100.0000")))
                .as("用 equals 而不是 compareTo 会把 100 与 100.0000 判成不一致, 于是每次发布都报「不完整」")
                .isTrue();
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(
                BigDecimal.ZERO, new BigDecimal("0.0000"))).isTrue();
    }

    @Test
    @DisplayName("不同值就是不一致 —— 阳性对照, 免得上面几条是恒真的")
    void differentValuesStillMismatch() {
        assertThat(BomWorkflowRevisionService.sameAllocationRatio(
                new BigDecimal("60"), new BigDecimal("40"))).isFalse();
    }

    // ---------- 唯一判据(源码级, 只挡「有人又实现一遍」) ----------

    @Test
    @DisplayName("完整性校验必须走 helper, ⛔ 不许再裸 compareTo")
    void familyCompletenessUsesTheHelper() throws IOException {
        String src = Files.readString(REVISION);
        assertThat(src)
                .as("requireCompleteActiveFamily 必须问 helper")
                .contains("!sameAllocationRatio(output.costAllocationRatio(), active.getCostAllocationRatio())");
        assertThat(src)
                .as("⛔ 裸 compareTo 回来 = 多产出发布又 500")
                .doesNotContain("output.costAllocationRatio().compareTo(active.getCostAllocationRatio())");
    }

    @Test
    @DisplayName("另一处不许自己再实现一遍 —— 委托过来")
    void theOtherConsumerDelegates() throws IOException {
        String src = Files.readString(RECIPE);
        assertThat(src)
                .as("BomRecipeServiceImpl.sameRatio 应委托到唯一判据")
                .contains("BomWorkflowRevisionService.sameAllocationRatio(left, right)");
        assertThat(src)
                .as("⛔ 不许在这里重新写一遍 null 处理 —— 两份一定会漂")
                .doesNotContain("if (left == null || right == null) return left == null && right == null;");
    }

    @Test
    @DisplayName("阳性对照: 锚点串确实存在 —— 否则上面的 doesNotContain 是恒真的")
    void anchorsExist() throws IOException {
        assertThat(Files.readString(REVISION))
                .contains("private BomRecipe requireCompleteActiveFamily(")
                .contains("public static boolean sameAllocationRatio(");
        assertThat(Files.readString(RECIPE))
                .contains("private static boolean sameRatio(BigDecimal left, BigDecimal right)");
    }
}
