package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「建计划时按标准算料」的摊分算法。
 *
 * <h3>业务口径（2026-09-05 会议 [26:05]）</h3>
 *
 * <p>「第一次生产之前你没有这个出成率，你怎么知道要做多少」—— 这一步要回答的就是它：
 * 一个成品还没生产过，也能按<b>研发填的标准</b>算出该买/该投多少主料。</p>
 *
 * <pre>
 *   总投入   = 计划产出 ÷ (标准出成率 / 100)
 *   某端口   = 总投入 × (该端口份额 ÷ 参与端口份额之和)
 * </pre>
 *
 * <h3>为什么归一化的分母不是 100</h3>
 *
 * <p>份额是<b>相对量</b>（{@link InputShareRatios} 的类注释：「各端口之和不必等于 100」）。
 * 30/70 与 3/7 必须算出同一个结果 —— 拿 100 当分母会让后者算出十分之一的投入量，
 * 而那个数看起来完全正常。</p>
 *
 * <h3>算不出的时候必须是空表，不是 0</h3>
 *
 * <p>⛔「需要 0kg 主料」是个看起来很确定的假答案。本仓的既有教训：
 * 兜底默认值会把「我不知道」翻译成「是 0」，而这两件事对下游完全不同 ——
 * 0 会让缺料预警说「都够」，空表才能让它说「这一项算不出来」。</p>
 */
class PlanInputShareResolverTest {

    private final PlanInputShareResolver resolver = new PlanInputShareResolver();

    private static Map<String, BigDecimal> shares(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1] == null ? null : new BigDecimal(String.valueOf(kv[i + 1])));
        }
        return m;
    }

    /**
     * ⚠️ 这一条<b>抓不住归一化写错</b> —— 30+70 恰好等于 100，
     * 「除以份额之和」与「除以 100」在这个样本上<b>由构造相等</b>（形态 B⁴：
     * 一道闸在某个样本上是恒真式）。实测：把分母改成写死的 100，本条纹丝不动，
     * 红的是下面 {@link #sharesAreRelative_soThirtySeventyEqualsThreeSeven}
     * 和 {@link #singlePortTakesTheWholeInput}。
     *
     * <p>⛔ 不要因为它「最直观」就以为它守住了摊分。它守的只是「摊分这件事发生了」。</p>
     */
    @Test
    void spreadsPlannedOutputAcrossPortsByShare() {
        // 产出 100kg, 出成率 50% ⇒ 总投入 200kg; 份额 30/70 ⇒ 60 / 140
        Map<String, BigDecimal> out = resolver.spreadToPorts(
                shares("p1", 30, "p2", 70), new BigDecimal("100"), new BigDecimal("50"));

        assertEquals(2, out.size());
        assertEquals(0, out.get("p1").compareTo(new BigDecimal("60")), "p1 应为 60kg, 实际 " + out.get("p1"));
        assertEquals(0, out.get("p2").compareTo(new BigDecimal("140")), "p2 应为 140kg, 实际 " + out.get("p2"));
    }

    @Test
    void sharesAreRelative_soThirtySeventyEqualsThreeSeven() {
        // 份额是相对量: 3/7 与 30/70 必须给出同一个答案。
        // ⚠️ 拿 100 当分母的实现会让这一条算出十分之一 —— 而那个数看起来完全正常。
        Map<String, BigDecimal> big = resolver.spreadToPorts(
                shares("p1", 30, "p2", 70), new BigDecimal("100"), new BigDecimal("50"));
        Map<String, BigDecimal> small = resolver.spreadToPorts(
                shares("p1", 3, "p2", 7), new BigDecimal("100"), new BigDecimal("50"));

        assertEquals(0, big.get("p1").compareTo(small.get("p1")), "3/7 与 30/70 算出了不同的投入量");
        assertEquals(0, big.get("p2").compareTo(small.get("p2")));
    }

    @Test
    void portsWithoutShareAreNeitherFilledNorCountedInTheDenominator() {
        // p3 份额为 null: 既不出现在结果里, 也不能进分母 (进了分母会把 p1/p2 算小)
        Map<String, BigDecimal> out = resolver.spreadToPorts(
                shares("p1", 30, "p2", 70, "p3", null), new BigDecimal("100"), new BigDecimal("50"));

        assertEquals(2, out.size(), "份额为 null 的端口不该出现在结果里");
        assertTrue(out.get("p3") == null);
        // 分母仍是 30+70 ⇒ p1 还是 60。若 p3 被当成 0 混进分母, 数值不变;
        // 但若实现把 null 当 0 又把它塞进结果, size 会变成 3 —— 上面那条守的就是这个。
        assertEquals(0, out.get("p1").compareTo(new BigDecimal("60")));
    }

    @Test
    void noYieldRateMeansEmpty_notZero() {
        // 🔴 这是本步最要紧的一条: 没有标准出成率就是算不出来。
        //    返回空表 ⇒ 缺料预警会说「这一项算不出」; 返回 0 ⇒ 它会说「都够」。
        for (BigDecimal badYield : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            Map<String, BigDecimal> out = resolver.spreadToPorts(
                    shares("p1", 30), new BigDecimal("100"), badYield);
            assertTrue(out.isEmpty(), "出成率=" + badYield + " 时应返回空表, 实际 " + out);
        }
    }

    @Test
    void noSharesOrNoOutputMeansEmpty() {
        assertTrue(resolver.spreadToPorts(Map.of(), new BigDecimal("100"), new BigDecimal("50")).isEmpty());
        assertTrue(resolver.spreadToPorts(null, new BigDecimal("100"), new BigDecimal("50")).isEmpty());
        assertTrue(resolver.spreadToPorts(shares("p1", 30), null, new BigDecimal("50")).isEmpty());
        assertTrue(resolver.spreadToPorts(shares("p1", 30), BigDecimal.ZERO, new BigDecimal("50")).isEmpty());
        // 份额全是 0 ⇒ 分母为 0, 不能除 —— 空表, ⛔ 不抛也不返回 0
        assertTrue(resolver.spreadToPorts(shares("p1", 0, "p2", 0),
                new BigDecimal("100"), new BigDecimal("50")).isEmpty());
    }

    @Test
    void singlePortTakesTheWholeInput() {
        // 单主料: 产出 88kg, 出成率 80% ⇒ 110kg
        Map<String, BigDecimal> out = resolver.spreadToPorts(
                shares("only", 42), new BigDecimal("88"), new BigDecimal("80"));

        assertEquals(1, out.size());
        assertEquals(0, out.get("only").compareTo(new BigDecimal("110")),
                "单端口应拿到全部投入量(份额多少都一样), 实际 " + out.get("only"));
    }
}
