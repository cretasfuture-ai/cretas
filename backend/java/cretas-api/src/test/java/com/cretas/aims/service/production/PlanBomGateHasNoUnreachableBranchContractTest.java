package com.cretas.aims.service.production;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 建生产计划这条腿上，不许再有<b>到不了的 BOM 准入分支</b>。
 *
 * <h2>🔴 为什么有这道闸（2026-08-25）</h2>
 *
 * {@code ProductionPlanServiceImpl.applyPlanUnitAuthority} 里曾经有：
 * <pre>
 * if (authority.mode() == WORKFLOW) {
 *     ...
 *     applyWorkflowBomAuthority(plan, authority);
 *     return;                                   // ← 早退
 * }
 * ...
 * if (authority.mode() == WORKFLOW && activeRecipe.isEmpty()) {   // ← 恒为 false
 *     throw new BusinessException(409, "当前产品没有已激活的 BOM")
 *             .withCode("ACTIVE_BOM_REQUIRED");
 * }
 * </pre>
 *
 * <p>走到第二个 {@code if} 时 {@code mode} 必然<b>不是</b> {@code WORKFLOW}（上面已经 return），
 * 所以那道 409 <b>从写下来的那天起一次都没有执行过</b>。本仓形态 B：机制在、没接上。
 *
 * <p>⚠️ 留着一道永不执行的闸<b>比没有更糟</b> —— 它让下一个人以为这里有保护，从而跳过核实。
 *
 * <h2>删掉它不是放松准入</h2>
 * WORKFLOW 模式的 BOM 准入由
 * {@code applyWorkflowBomAuthority → resolvePinnedBomAuthority} 的
 * {@code ACTIVE_BOM_FAMILY_REQUIRED} <b>唯一</b>负责，那道是活的 —— 本闸第二条正是钉它还在。
 */
class PlanBomGateHasNoUnreachableBranchContractTest {

    private static final Path PLAN_SRC = Path.of(
            "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java");
    private static final Path READINESS_SRC = Path.of(
            "src/main/java/com/cretas/aims/service/validation/"
                    + "ProductConfigurationReadinessService.java");

    /**
     * 剥掉注释再看代码。
     *
     * <p>⛔ 这一步不能省：本闸第一版就栽在这里 —— 判据把<b>解释这件事的注释本身</b>
     * 数成了「码还在」（本仓：闸把自己写的注释当成被测对象）。
     */
    private static String codeOf(Path p) throws Exception {
        return Files.readString(p)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    @DisplayName("🔴 那道到不了的 ACTIVE_BOM_REQUIRED 已经从建单腿上删除")
    void theUnreachableGateIsGone() throws Exception {
        String code = codeOf(PLAN_SRC);
        assertFalse(code.contains("ACTIVE_BOM_REQUIRED"),
                "到不了的 BOM 闸又回来了 —— 它一次都不会执行, 只会让人以为这里有保护");
    }

    @Test
    @DisplayName("🔴 真正在拦人的那道闸必须还在 (删的是死代码, 不是准入)")
    void theLiveGateIsStillThere() throws Exception {
        String code = codeOf(PLAN_SRC);
        assertTrue(code.contains("ACTIVE_BOM_FAMILY_REQUIRED"),
                "WORKFLOW 模式的 BOM 准入没了 —— 那才是真的放松了准入");
        assertTrue(code.contains("resolvePinnedBomAuthority"),
                "BOM 权威解析入口不见了");
    }

    @Test
    @DisplayName("🔴 阴性对照: 发布/启用 Workflow 那条腿上的同名码是【活的】, 不许被顺手删掉")
    void theSameCodeOnAnotherLegMustSurvive() throws Exception {
        String code = codeOf(READINESS_SRC);
        assertTrue(code.contains("ACTIVE_BOM_REQUIRED"),
                "ProductConfigurationReadinessService.requireActiveBomComplete 产出的 "
                        + "ACTIVE_BOM_REQUIRED 是活路径(发布/启用 Workflow 时校验), "
                        + "不能因为建单腿上删了同名码就跟着删");
    }

    @Test
    @DisplayName("阳性对照: 剥注释没有把方法体也剥掉 (否则上面全是恒真式)")
    void strippingCommentsDoesNotEatTheCode() throws Exception {
        String code = codeOf(PLAN_SRC);
        assertTrue(code.length() > 100_000,
                "剥注释后只剩 " + code.length() + " 字符, 剥过头了 —— 上面的 assertFalse 会恒真");
        assertTrue(code.contains("applyPlanUnitAuthority"), "目标方法都不在了, 闸在守空气");
        assertEquals(1, countOccurrences(code, "private void applyPlanUnitAuthority("),
                "applyPlanUnitAuthority 的定义处数量不对, 闸的范围钉不住");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
