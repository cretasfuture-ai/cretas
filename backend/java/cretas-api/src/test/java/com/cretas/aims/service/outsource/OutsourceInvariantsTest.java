package com.cretas.aims.service.outsource;

import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.outsource.OutsourceOrderLine;
import com.cretas.aims.service.finance.VoucherConsumptionSourcePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 委外闭环的几条钉子 (设计 docs/specs/outsource-closed-loop-design.md D13 / 第 3 节)。 */
class OutsourceInvariantsTest {

    @Test
    void outsourceWarehouseIsNotSettlementEligible() {
        // D13: 加入会让生产结单直接扣委外厂手里的料, 再在委外收货时扣一次 = 重复耗用。
        assertThat(FactoryWarehouse.SETTLEMENT_ELIGIBLE_TYPES).doesNotContain(FactoryWarehouse.WarehouseType.OUTSOURCE);
    }

    @Test
    void outsourceConsumptionSourcesAreKnownVoucherExclusions() {
        assertThat(VoucherConsumptionSourcePolicy.isKnownExclusion(OutsourceService.SOURCE_RECEIVE)).isTrue();
        assertThat(VoucherConsumptionSourcePolicy.isKnownExclusion(OutsourceService.SOURCE_LOSS)).isTrue();
        assertThat(VoucherConsumptionSourcePolicy.isProductionConsumption(OutsourceService.SOURCE_RECEIVE)).isFalse();
    }

    @Test
    void remainingToReceiveSubtractsReworkPending() {
        OutsourceOrderLine l = new OutsourceOrderLine();
        l.setPlannedQuantity(new BigDecimal("100"));
        l.setReceivedGoodQuantity(new BigDecimal("90"));   // 收 100, 退 10 返工
        l.setReceivedDefectQuantity(BigDecimal.ZERO);
        l.setReworkPendingQuantity(new BigDecimal("10"));
        assertThat(l.remainingToReceive()).isEqualByComparingTo("0");
        l.setReworkPendingQuantity(BigDecimal.ZERO);        // 阴性对照: 没有返工待收时确实还差 10
        assertThat(l.remainingToReceive()).isEqualByComparingTo("10");
    }
}
