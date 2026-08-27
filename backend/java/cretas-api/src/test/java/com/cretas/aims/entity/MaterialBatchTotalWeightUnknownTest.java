package com.cretas.aims.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔒 {@code MaterialBatch.getTotalWeight()} —— 「算不出来」和「重 0 公斤」必须分得开。
 *
 * <p>原实现在 {@code weightPerUnit == null} 时 {@code return BigDecimal.ZERO}，
 * 把「我不知道」翻译成一个**合法的重量读数**，消费方无从分辨。</p>
 *
 * <p>它已经炸过一次：{@code MaterialBatchServiceImpl} 移动加权平均那段的注释写着
 * 「大部分批次未填, 退化成 0 → 加权公式失效成"最新单价覆盖"」。那次是就地绕开的。</p>
 *
 * <p><b>生产实测（2026-08-27）</b>：{@code material_batches} 177 行，
 * {@code weight_per_unit} 非空 <b>0 行</b> —— 线上批次全部来自 7 条不设该字段的创建路径。
 * 也就是说线上每一个批次的总重量都是这个兜底出来的 0。</p>
 */
class MaterialBatchTotalWeightUnknownTest {

    private MaterialBatch batch(String weightPerUnit, String receiptQuantity) {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber("MB-TEST-0001");
        if (weightPerUnit != null) {
            mb.setWeightPerUnit(new BigDecimal(weightPerUnit));
        }
        if (receiptQuantity != null) {
            mb.setReceiptQuantity(new BigDecimal(receiptQuantity));
        }
        return mb;
    }

    @Test
    void weightPerUnit_missing_returns_null_not_zero() {
        // 这就是线上 177/177 的形状: 数量有, 每单位重量没有
        assertThat(batch(null, "20").getTotalWeight())
                .as("算不出来要返回 null —— 返回 0 等于替系统断言「这批重 0 公斤」")
                .isNull();
    }

    @Test
    void receiptQuantity_missing_returns_null_not_zero() {
        assertThat(batch("2.5", null).getTotalWeight()).isNull();
    }

    @Test
    void both_missing_returns_null_not_zero() {
        assertThat(batch(null, null).getTotalWeight()).isNull();
    }

    @Test
    void both_present_still_multiplies() {
        // ⛔ 阴性对照: 别把「不知道就 null」做成「一律 null」
        assertThat(batch("2.5", "20").getTotalWeight())
                .isEqualByComparingTo(new BigDecimal("50.0"));
    }

    @Test
    void a_real_zero_weight_is_still_zero() {
        // 🔴 这条是本测试的要害: 「真的是 0」必须仍然是 0, 且与「不知道」区分得开。
        //    两者都返回 ZERO 时这条断言恒真, 是修复前的那个缺陷本身。
        assertThat(batch("0", "20").getTotalWeight())
                .as("每单位重量真的是 0 → 总重量就是 0, 这是读数不是兜底")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch(null, "20").getTotalWeight())
                .as("而「不知道」必须是 null —— 与上面那个 0 分得开")
                .isNull();
    }
}
