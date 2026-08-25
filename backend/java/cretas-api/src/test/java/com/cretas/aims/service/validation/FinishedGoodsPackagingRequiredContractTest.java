package com.cretas.aims.service.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— <b>成品必须有包材</b>；半成品 / 原料 / 来料加工不强制。
 *
 * <h2>🔴 为什么有这道闸（2026-08-25 owner 裁定）</h2>
 *
 * BOM 与工艺画布是<b>一体</b>的（{@code BomRecipe} 自带 {@code workflowRevisionId} /
 * {@code workflowNodesSnapshotJson} / {@code bomFamilyId} / {@code targetTerminalNodeId}），
 * 所以「包材可选」这个说法不成立 —— owner 裁定：<b>成品必须有包材</b>，
 * 只有生产辅料/半成品那类才可以不配包材。
 *
 * <h3>改之前的实际行为</h3>
 * <pre>
 * packagingStatuses.add(... .materialCount(baseCount).complete(true).build());   // ← 恒 true
 * ...
 * return rawCount &gt; 0;                                                          // ← 只看主原料
 * </pre>
 * 也就是说<b>「包材」这一档从来没有参与过完整性判定</b>，只统计数量；
 * 而提示语却写着「工序辅料和包材可按实际需要补充」——
 * 一句与新裁定直接矛盾的<b>过期承诺</b>（本仓形态 B⁵：描述过期比没有描述更糟）。
 *
 * <h3>实测影响面（2026-08-25，生产库只读）</h3>
 * F006 共 13 个 ACTIVE {@code bom_recipes}，全部是 {@code FINISHED_PRODUCT}，
 * 其中<b>只有 3 个配了包材</b>；另有 3 个连主原料都是 0（却已是 ACTIVE 状态）。
 *
 * <h3>为什么判据用 {@code product_category} 而不是单位量纲</h3>
 * 实测 F006 存在 {@code FINISHED_PRODUCT} 配 {@code kg} 的散装成品，
 * 按「单位量纲是 PACKAGE/COUNT」反推会把它们<b>漏掉</b>。
 * {@code product_types.product_category} 是权威语义字段，直接对应 owner 说的「成品」。
 */
class FinishedGoodsPackagingRequiredContractTest {

    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/validation/"
                    + "ProductConfigurationReadinessService.java");

    /** 剥注释再看代码 —— 否则会数到解释这件事的注释本身。 */
    private static String code() throws Exception {
        return Files.readString(SRC)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    @DisplayName("🔴 包材真的参与完整性判定了 —— 不再是恒 true")
    void packagingActuallyParticipatesInCompleteness() throws Exception {
        String c = code();
        assertTrue(c.contains("BOM_PACKAGING_REQUIRED"),
                "缺包材没有产出任何 issue —— 用户不会知道差在哪");
        assertTrue(c.contains("return rawCount > 0 && packagingComplete;"),
                "完整性仍然只看主原料, 包材没有进入返回值");
        assertFalse(c.contains(".materialCount(baseCount).complete(true)"),
                "基本规格包材的 complete 还是硬编码 true —— 那一档等于没判");
    }

    @Test
    @DisplayName("🔴 判据用 product_category, 不用单位量纲反推")
    void theCriterionIsProductCategoryNotUnitDimension() throws Exception {
        String c = code();
        assertTrue(c.contains("FINISHED_PRODUCT"),
                "没有用 product_category 判成品");
        assertTrue(c.contains("getProductCategory()"),
                "没有读 product_category 这个权威语义字段");
        // 阴性对照: 不许把成品判据退化成「单位量纲是包装/计数」——
        // 实测有 FINISHED_PRODUCT 配 kg 的散装成品, 那样会漏掉它们。
        assertFalse(c.contains("boolean finishedGoods = product != null && unitContractService"),
                "成品判据被换成了单位量纲反推, 会漏掉 kg 散装成品");
    }

    @Test
    @DisplayName("🔴 只对成品强制 —— 半成品/原料/来料加工不受影响")
    void onlyFinishedGoodsAreForced() throws Exception {
        String c = code();
        assertTrue(c.contains("!finishedGoods || !packagingItems.isEmpty()"),
                "包材强制没有按成品收窄 —— 会误伤半成品/原料/来料加工");
    }

    @Test
    @DisplayName("🔴 那句过期承诺必须消失 (形态 B⁵: 改承诺要同 commit grep 关键词)")
    void theStalePromiseIsGone() throws Exception {
        String c = code();
        assertFalse(c.contains("可按实际需要补充"),
                "「工序辅料和包材可按实际需要补充」与新裁定直接矛盾, 必须一起改掉");
        assertTrue(c.contains("成品还必须配置包材"),
                "新提示没有说清成品必须配包材, 用户不知道下一步做什么");
    }

    @Test
    @DisplayName("阳性对照: 剥注释没把方法体也剥掉 (否则上面 assertFalse 全恒真)")
    void strippingCommentsDoesNotEatTheCode() throws Exception {
        String c = code();
        assertTrue(c.length() > 10_000,
                "剥注释后只剩 " + c.length() + " 字符, 剥过头了");
        assertEquals(1, countOccurrences(c, "private boolean evaluateBom("),
                "evaluateBom 的定义处数量不对, 闸的范围钉不住");
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
