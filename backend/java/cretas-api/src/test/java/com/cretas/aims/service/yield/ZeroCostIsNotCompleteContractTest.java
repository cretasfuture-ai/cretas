package com.cretas.aims.service.yield;

import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 一个<b>已经有产出</b>的正式计划，各成本桶全是 0，那是「没归集」不是「零成本」。
 *
 * <h2>🔴 为什么有这道闸（2026-08-25 部署后 prod 实测）</h2>
 *
 * {@code PB-PLAN-1787560061105-4E56E10E-88197} 读出
 * {@code totalCost=0.0 / costComplete=true}，而它无领料、无人工、BOM 也没有包材行。
 * 结单侧本来就安全（{@code usableTotalCost} 要求 {@code signum>0}），
 * 但成本<b>页面</b>上「0.0 且完整」会让人以为这批货真的不花钱。
 *
 * <p>本仓形态 A¹¹：算「缺了多少」之前先问<b>「这里的 0 是不是一种合法状态」</b> ——
 * 一个已完工批次的全 0 几乎必然是没归集。
 *
 * <h2>⚠️ 这是一道代理判据的闸</h2>
 * {@code nothingCollected} 是 {@code computeForBatches} 里的局部变量，测不到，
 * 所以这里量的是<b>源码结构</b>而不是行为。真正的行为证据来自部署后生产实测
 * （见 PR 描述里那个批次改前/改后的读数）。
 * ⛔ 别把这道闸当成行为背书。
 */
class ZeroCostIsNotCompleteContractTest {

    private static final Path SRC = Path.of(
            "src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java");

    private static String code() throws Exception {
        return Files.readString(SRC)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    @DisplayName("🔴 全 0 时必须登记 NO_COST_COLLECTED 并进入阻断集")
    void zeroCostIsRegisteredAsBlocking() throws Exception {
        String c = code();
        assertTrue(c.contains("NO_COST_COLLECTED"), "全 0 没有产出任何缺项码");
        assertTrue(c.contains("boolean nothingCollected = auditEligible"),
                "判据没有按 auditEligible 收窄 —— 会误伤没有 BOM/Workflow 的临时批次");
        assertTrue(c.contains("blockingMissingItems.add(\"NO_COST_COLLECTED\")"),
                "只登记不阻断 = costComplete 仍然是 true, 页面照样说「完整」");
    }

    @Test
    @DisplayName("🔴 五个成本桶一个都不能漏 (漏一个就会把「只有那一桶有值」误判成全 0)")
    void allFiveBucketsParticipate() throws Exception {
        String c = code();
        int at = c.indexOf("boolean nothingCollected = auditEligible");
        assertTrue(at > 0);
        String expr = c.substring(at, c.indexOf(';', at));
        for (String bucket : new String[]{"raw", "labor", "seasoning", "packaging", "semiFeedCost"}) {
            assertTrue(expr.contains(bucket + ".signum() == 0"),
                    "判据漏了成本桶 " + bucket + ": " + expr);
        }
    }

    @Test
    @DisplayName("那个码要有中文说明 —— 机器码甩给用户等于没说")
    void theCodeHasAChineseLabel() {
        String label = ProductionPlanServiceImpl.missingCostItemLabel("NO_COST_COLLECTED");
        assertFalse(label.contains("NO_COST_COLLECTED"), "没翻译成中文: " + label);
        assertTrue(label.contains("没有归集"), "没说清是「没归集」: " + label);
    }

    @Test
    @DisplayName("阳性对照: 剥注释没把方法体也剥掉")
    void strippingCommentsDoesNotEatTheCode() throws Exception {
        String c = code();
        assertTrue(c.length() > 20_000, "剥过头了, 只剩 " + c.length() + " 字符");
        assertEquals(1, c.split("boolean costComplete = ", -1).length - 1,
                "costComplete 的赋值处数量不对, 闸的范围钉不住");
    }
}
