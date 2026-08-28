package com.cretas.aims.service.wip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回料纯度闸的判定矩阵。
 *
 * <p>⚠️ <b>16 格逐格断言, 不是只测对角线</b> —— 一个「来源 == 目标才放行」的错误实现在对角线上
 * 全绿, 而它会挡掉「美式进小吃吧」这条业务上最常走的路。
 */
class ReworkPurityGuardTest {

    /** 客户实拍确认的四个产品。等级 null 表示这个产品不参与全序 (汉堡排靠 acceptsRework 判)。 */
    private record Product(String name, Integer level, boolean acceptsRework) {
    }

    private static final Product US = new Product("美式汉堡饼", 1, true);
    private static final Product SNACK = new Product("小吃吧汉堡饼", 2, true);
    private static final Product COOKED = new Product("熟肉饼", 3, true);
    /**
     * 🔴 汉堡排要配<b>两样</b>: {@code acceptsRework=false} <b>且</b> {@code level=4}。
     *
     * <p>矩阵里汉堡排那一<b>行</b>全 ❌ (它不收回料) 由 {@code acceptsRework=false} 表达;
     * 但汉堡排那一<b>列</b>也全 ❌ (它产出的回料谁都不收) 是<b>另一件事</b> ——
     * {@code acceptsRework} 管不到它, 那是「这个产品作为来源」的方向。
     * 只有给它一个比所有目标都大的等级 (4 > 3) 才拦得住。
     *
     * <p>⚠️ 这一条是写这个测试时才发现的: 最初按需求描述给汉堡排配了 {@code level=null},
     * 16 格里有 3 格当场变红 —— null 会走「未配置放行」那条路, 于是汉堡排的回料
     * <b>能被领给任何产品</b>。配置口径因此写进 {@link ReworkPurityGuard} 的类注释。
     */
    private static final Product PATTY = new Product("汉堡排", 4, false);

    /** 行 = 投入到; 列 = 回料来自。顺序与 {@link #PRODUCTS} 一致。 */
    private static final List<Product> PRODUCTS = List.of(US, SNACK, COOKED, PATTY);

    private static final boolean[][] EXPECTED = {
            //         美式   小吃吧  熟肉饼  汉堡排   ← 回料来自
            /* 美式   */ {true,  false, false, false},
            /* 小吃吧 */ {true,  true,  false, false},
            /* 熟肉饼 */ {true,  true,  true,  false},
            /* 汉堡排 */ {false, false, false, false},
    };

    @Test
    @DisplayName("客户实拍矩阵 4x4 全部 16 格逐格成立")
    void customerMatrixHoldsInAllSixteenCells() {
        List<String> mismatches = new ArrayList<>();
        int cells = 0;
        for (int t = 0; t < PRODUCTS.size(); t++) {
            for (int s = 0; s < PRODUCTS.size(); s++) {
                Product target = PRODUCTS.get(t);
                Product source = PRODUCTS.get(s);
                boolean actual = ReworkPurityGuard.evaluate(
                        source.level(), target.level(), target.acceptsRework()).allowed();
                cells++;
                if (actual != EXPECTED[t][s]) {
                    mismatches.add(String.format("投入到[%s] 回料来自[%s]: 期望 %s 实际 %s",
                            target.name(), source.name(), EXPECTED[t][s], actual));
                }
            }
        }
        // 🔴 先证明这一轮真的跑了 16 格 —— 「全绿」和「一格都没跑」的读数一模一样。
        assertEquals(16, cells, "矩阵没跑满 16 格, 下面那条断言无论红不红都没有意义");
        assertTrue(mismatches.isEmpty(), "矩阵有格子不符:\n" + String.join("\n", mismatches));
    }

    @Test
    @DisplayName("汉堡排(accepts_rework=false): 任何来源都拒 —— 包括来源等级未配置时")
    void productThatRejectsReworkBlocksEverySource() {
        for (Integer sourceLevel : new Integer[]{null, 1, 2, 3, 99}) {
            // 目标等级也遍历: 「不收」不能被「等级没配」放行掉。
            for (Integer targetLevel : new Integer[]{null, 1, 3}) {
                ReworkPurityGuard.Verdict verdict =
                        ReworkPurityGuard.evaluate(sourceLevel, targetLevel, false);
                assertEquals(ReworkPurityGuard.Verdict.BLOCKED_TARGET_REJECTS_REWORK, verdict,
                        "sourceLevel=" + sourceLevel + " targetLevel=" + targetLevel);
                assertFalse(verdict.allowed());
            }
        }
    }

    @Test
    @DisplayName("等级未配置(任一侧 null): 放行, 且档位是 UNCONFIGURED 而不是 ALLOWED")
    void unconfiguredLevelPassesButIsMarkedAsNotJudged() {
        // 🔴 这两个档必须分得开: ALLOWED = 判过了没问题; UNCONFIGURED = 根本没判。
        //    如果这里断言的只是 allowed(), 把 UNCONFIGURED 实现成 ALLOWED 也会绿, 而那样
        //    前端就再也说不清「这一行没经过纯度判定」。
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED_PURITY_UNCONFIGURED,
                ReworkPurityGuard.evaluate(null, 1, true), "来源未配");
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED_PURITY_UNCONFIGURED,
                ReworkPurityGuard.evaluate(1, null, true), "目标未配");
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED_PURITY_UNCONFIGURED,
                ReworkPurityGuard.evaluate(null, null, true), "两侧都未配");
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED_PURITY_UNCONFIGURED,
                ReworkPurityGuard.evaluate(null, null, null), "acceptsRework 也未配");

        // 阴性对照: 两侧都配了就必须真的判 —— 否则上面四条可能只是「一律 UNCONFIGURED」。
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED,
                ReworkPurityGuard.evaluate(1, 3, true));
        assertEquals(ReworkPurityGuard.Verdict.BLOCKED_SOURCE_DIRTIER_THAN_TARGET,
                ReworkPurityGuard.evaluate(3, 1, true));
    }

    @Test
    @DisplayName("acceptsRework=null 视为收 (DB 列默认 TRUE), 但不影响等级比较")
    void nullAcceptsReworkBehavesLikeTrue() {
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED,
                ReworkPurityGuard.evaluate(1, 2, null));
        assertEquals(ReworkPurityGuard.Verdict.BLOCKED_SOURCE_DIRTIER_THAN_TARGET,
                ReworkPurityGuard.evaluate(2, 1, null));
    }

    @Test
    @DisplayName("配置陷阱: 只配 acceptsRework=false 而不配等级, 它的回料照样能领给别人")
    void acceptsReworkAloneDoesNotBlockThisProductAsASource() {
        // 🔴 这不是缺陷, 是【两个方向】: acceptsRework 管「谁能投进我」, 等级管「我能投进谁」。
        //    把这条钉下来是因为需求描述里只说了「汉堡排=false」, 照着配会漏掉列方向,
        //    而漏掉的表现是【放行】—— 一个不会报错、只会多出选项的失败。
        assertTrue(ReworkPurityGuard.evaluate(null, 1, true).allowed(),
                "汉堡排等级留 null ⇒ 它的回料进美式会被当成「未配置」放行");
        assertEquals(ReworkPurityGuard.Verdict.ALLOWED_PURITY_UNCONFIGURED,
                ReworkPurityGuard.evaluate(null, 1, true));

        // 配上 level=4 之后才真的拦得住。
        assertEquals(ReworkPurityGuard.Verdict.BLOCKED_SOURCE_DIRTIER_THAN_TARGET,
                ReworkPurityGuard.evaluate(4, 3, true));
    }

    @Test
    @DisplayName("同级放行 (<=, 不是 <) —— 熟肉饼的回料能进熟肉饼")
    void sameLevelIsAllowedBecauseTheComparisonIsSubsetNotStrict() {
        for (int level = 1; level <= 3; level++) {
            assertEquals(ReworkPurityGuard.Verdict.ALLOWED,
                    ReworkPurityGuard.evaluate(level, level, true), "level=" + level);
        }
    }
}
