package com.cretas.aims.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 良品率算不出来的时候 ⛔ 不许保留上一次算出来的值。
 *
 * <h2>它守什么</h2>
 *
 * <p>{@code yield_rate = good / actual}。{@code V20260826_05/_06} 把编出来的
 * {@code good_quantity}（{@code = actual}，声称零不良）清成了 {@code NULL}，
 * <b>但只清了分子</b> —— 派生的百分比列原样留着，成了那批假数据唯一还活着的残留。</p>
 *
 * <p>🔴 而且是<b>永久冻结</b>的：{@code calculateMetrics()} 里的守卫要求
 * {@code goodQuantity != null}，全库清空之后这个分支<b>永远进不去</b>，
 * 旧值再也不会被重算。</p>
 *
 * <h2>🔴 生产实测（2026-08-28，{@code cretas_prod_db}，只读）</h2>
 *
 * <pre>
 *   46 个批次 / 1 个工厂(F006)
 *   good_quantity 非空 ...... 0 行      ← 8-26 清完之后没人补过
 *   yield_rate  非空 ........ 40 行     ← 「没有分子却有结果」
 *     其中 = 100.00 ......... 39 行
 *     其中 = 60.00 .......... 1 行
 *     连 actual 也为 NULL ... 31 行（且 status = IN_PROGRESS）
 *
 *   可直接证伪(同一定义下算出来就不等):
 *     批次 10766  计划 80 盒 / 实际 75 盒  ⇒ 按定义 93.75%，字段写 100.00
 *     批次 10786  计划 5 盒  / 实际 4 盒   ⇒ 按定义 80.00%， 字段写 100.00
 *
 *   阳性对照: 这一列【能】存别的值(批次 10760 = 60.00) ⇒ 「几乎全是 100」不是字段坏了；
 *            同方法的邻居 efficiency 在 38 个批次上是 NULL(算不出就留空) ⇒ 100 是冻住的
 * </pre>
 *
 * <h2>两层判据，性质不同，都要</h2>
 *
 * <ol>
 *   <li><b>行为断言</b>（下面前三条）：直接驱动 {@code calculateMetrics()}，
 *       断言算不出时 {@code yieldRate} 变成 {@code null}。这是真行为，不是代理。</li>
 *   <li><b>源码级代理判据</b>（最后一条）：读侧 {@code getWeeklyBatchesCost} 不许把
 *       {@code null} 兜底成 {@code ZERO}。⚠️ 这一条是<b>代理</b> —— 静态扫描判不出运行时行为，
 *       换个写法（先赋局部变量）本闸看不出来。它的作用是<b>让「把兜底加回来」必须显式绕过
 *       一道写着理由的闸</b>，不是物理上不可能。</li>
 * </ol>
 *
 * <p>⚠️ 为什么读侧那一处必须一起守：不改它的话，清洗会把那 40 个批次从
 * 「假的 100%（完美）」变成「<b>假的 0%（全废）</b>」—— 比修之前更糟。
 * 0% 良品率是一个灾难性的<b>真实读数</b>，与「还没算出来」含义完全相反。</p>
 */
@DisplayName("良品率: 算不出就说不知道, ⛔ 不许冻住旧值")
class YieldRateIsNotFrozenFromFabricatedGoodQuantityContractTest {

    private static ProductionBatch batchWithStaleYield() {
        ProductionBatch b = new ProductionBatch();
        // 生产上那 40 行的长相: 百分比还在, 分子已经没了
        b.setYieldRate(new BigDecimal("100.00"));
        return b;
    }

    @Test
    @DisplayName("良品数为空 → 良品率必须被清成 null, 而不是留着上一次的 100%")
    void goodQuantityNull_clearsStaleYieldRate() {
        ProductionBatch b = batchWithStaleYield();
        b.setGoodQuantity(null);
        b.setActualQuantity(new BigDecimal("75"));

        b.calculateMetrics();

        assertThat(b.getYieldRate())
                .as("good 为空时良品率必须是 null（不知道）——留着 100.00 就是生产上那 40 行的成因")
                .isNull();
    }

    @Test
    @DisplayName("实际产量为空 → 同样清成 null（生产上 31 行是这个形状）")
    void actualQuantityNull_clearsStaleYieldRate() {
        ProductionBatch b = batchWithStaleYield();
        b.setGoodQuantity(new BigDecimal("70"));
        b.setActualQuantity(null);

        b.calculateMetrics();

        assertThat(b.getYieldRate()).isNull();
    }

    @Test
    @DisplayName("阴性对照: 两个数都齐 → 照常算出来, ⛔ 别把闸做成「一律清空」")
    void bothPresent_stillComputes() {
        ProductionBatch b = batchWithStaleYield();
        b.setGoodQuantity(new BigDecimal("70"));
        b.setActualQuantity(new BigDecimal("75"));

        b.calculateMetrics();

        assertThat(b.getYieldRate())
                .as("70/75 = 93.33%")
                .isEqualByComparingTo(new BigDecimal("93.33"));
    }

    @Test
    @DisplayName("读侧代理判据: 周批次成本报表不许把良品率兜底成 0")
    void weeklyBatchesCostDoesNotFallBackYieldRateToZero() throws IOException {
        Path p = Paths.get("src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java");
        String src = Files.readString(p, StandardCharsets.UTF_8);

        // 阳性对照: 这把尺子确实能在这份源码里认出「兜底成 ZERO」这个形状 ——
        // 同一块里 efficiency 那一处【故意保留】(与本次改动无因果, 已单独登记),
        // 它的存在正好证明本判据不是恒真的。
        assertThat(src)
                .as("阳性对照: 这份源码里应当仍有 efficiency 的 ZERO 兜底; 找不到说明这把尺子失效了")
                .contains("batchCostSummary.put(\"efficiency\", batch.getEfficiency() != null ? batch.getEfficiency() : BigDecimal.ZERO)");

        assertThat(src)
                .as("良品率不许兜底成 0 —— 0% 是灾难性的真实读数, 与「不知道」含义相反")
                .doesNotContain("batchCostSummary.put(\"yieldRate\", batch.getYieldRate() != null ? batch.getYieldRate() : BigDecimal.ZERO)");
    }
}
