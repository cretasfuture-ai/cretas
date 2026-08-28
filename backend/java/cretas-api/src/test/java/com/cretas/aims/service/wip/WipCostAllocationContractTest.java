package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一份报工的成本怎么摊到它<b>实际入账的每一条产出</b>上 —— 纯函数, 唯一实现。
 *
 * <p>这段算术在本仓两次以「每个槽各自去算一次全额」的形状出过缺陷 (2026-08-28 C-1 与 Critical 2),
 * 抽成一份就是为了它不能再各写一遍。
 */
@DisplayName("WipCostAllocation: 一份报工的成本按量摊到 N 个槽")
class WipCostAllocationContractTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("各槽同一个单价, 且 Σ 恰好等于 totalCost (尾数归最后一槽)")
    void allocatesByQuantity_sumIsExact() {
        // 金检: 回料 50 + 良品 950, 本道 ¥100
        WipCostAllocation a = WipCostAllocation.byQuantity(d("100.00"), List.of(d("50.00"), d("950.00")));

        assertThat(a.unitCost()).isEqualByComparingTo(d("0.1000"));
        assertThat(a.costAt(0)).isEqualByComparingTo(d("5.00"));
        assertThat(a.costAt(1)).isEqualByComparingTo(d("95.00"));
        assertThat(a.costAt(0).add(a.costAt(1)))
                .as("Σ 必须一分不多一分不少 —— 差一分就是把一份成本记成了别的数")
                .isEqualByComparingTo(d("100.00"));
    }

    @Test
    @DisplayName("除不尽时尾数归最后一槽, Σ 仍恰好等于 totalCost")
    void remainderGoesToLastSlot() {
        WipCostAllocation a = WipCostAllocation.byQuantity(d("100.00"), List.of(d("1"), d("1"), d("1")));

        assertThat(a.costAt(0)).isEqualByComparingTo(d("33.33"));
        assertThat(a.costAt(1)).isEqualByComparingTo(d("33.33"));
        assertThat(a.costAt(2)).as("尾数归最后一槽").isEqualByComparingTo(d("33.34"));
        assertThat(a.costAt(0).add(a.costAt(1)).add(a.costAt(2))).isEqualByComparingTo(d("100.00"));
    }

    @Test
    @DisplayName("单槽拿全额, 无舍入损失 (⇒ 与单产出路径逐字节一致)")
    void singleSlotGetsFullAmount() {
        WipCostAllocation a = WipCostAllocation.byQuantity(d("10000.00"), List.of(d("950.00")));

        assertThat(a.costAt(0)).isEqualByComparingTo(d("10000.00"));
        assertThat(a.unitCost()).isEqualByComparingTo(d("10.5263"));
    }

    @Test
    @DisplayName("成本未知 ⇒ 每槽诚实 null, ⛔ 不摊成 0")
    void unknownCost_isNullNotZero() {
        WipCostAllocation a = WipCostAllocation.byQuantity(null, List.of(d("50"), d("950")));

        assertThat(a.unitCost()).isNull();
        assertThat(a.costAt(0)).as("一个假的 0 会顺着移动加权把整条链的单价摊薄, 而且不报错").isNull();
        assertThat(a.costAt(1)).isNull();
    }

    @Test
    @DisplayName("总量为 0 ⇒ 不除零, 每槽诚实 null")
    void zeroQuantity_doesNotDivideByZero() {
        WipCostAllocation a = WipCostAllocation.byQuantity(d("100.00"), List.of(BigDecimal.ZERO));

        assertThat(a.unitCost()).isNull();
        assertThat(a.costAt(0)).isNull();
    }

    /**
     * {@code is_rework} 列是 {@code NOT NULL} —— builder 不显式设它时必须落 {@code FALSE} 而不是 null,
     * 否则任何一条不经过回料判据的入账 (例如 {@code postClerkOutput} 的占位行) 都会在 insert 时炸。
     */
    @Test
    @DisplayName("SemiFinishedInventory.isRework 默认 FALSE (NOT NULL 列, builder 不设也不能是 null)")
    void isReworkDefaultsToFalse_notNull() {
        SemiFinishedInventory row = SemiFinishedInventory.builder()
                .factoryId("F006")
                .intermediateBatchNo("IB-1")
                .build();

        assertThat(row.getIsRework()).isNotNull().isFalse();
    }
}
