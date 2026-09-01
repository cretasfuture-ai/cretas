package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 守: 「任一成品批次成本未知 ⇒ 总成本 null + 说明」, ⛔ 不把未知折成 0。 */
class SummaryCostRollupTest {

    private static OrderCostBreakdownDTO cb(String batch, String total, List<String> missing) {
        return OrderCostBreakdownDTO.builder().orderId(batch)
                .totalCost(total == null ? null : new BigDecimal(total))
                .missingCostItems(missing).build();
    }

    @Test
    @DisplayName("全部已知 → 求和, 无说明")
    void allKnownSums() {
        var r = SummaryCostRollup.rollup(List.of(cb("B1", "100.50", List.of()), cb("B2", "0.50", List.of())));
        assertThat(r.total()).isEqualByComparingTo("101.00");
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("一个未知 → 总成本 null, 说明点名批次与缺项 (2026-09-02 小吃吧 CLK-B 实拍)")
    void oneUnknownMakesTotalUnknown() {
        var r = SummaryCostRollup.rollup(List.of(
                cb("CLK-B-1", "74544.28", List.of()),
                cb("CLK-B-2", null, List.of("SEMI_FINISHED_OR_FINISHED_FEED_PRICE"))));
        assertThat(r.total()).as("未知不能被折成 0 再相加").isNull();
        assertThat(r.note()).contains("CLK-B-2").contains("SEMI_FINISHED_OR_FINISHED_FEED_PRICE");
    }

    @Test
    @DisplayName("计划级容器批次(无产出 + NO_COST_COLLECTED)不算未知 —— 美式线实拍: 有它在也得是 74544.28")
    void planContainerBatchIsIgnored() {
        OrderCostBreakdownDTO container = OrderCostBreakdownDTO.builder().orderId("PB-PLAN-1")
                .totalCost(null).boxCount(0).outputQuantity(null)
                .missingCostItems(List.of("NO_COST_COLLECTED", "EQUIPMENT_COST:PB-PLAN-1")).build();
        var r = SummaryCostRollup.rollup(List.of(container, cb("CLK-B-9", "74544.28", List.of())));
        assertThat(r.total()).isEqualByComparingTo("74544.28");
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("阴性对照: 有产出但一条成本行都没有 → 仍然是未知, ⛔ 不能当作没发生")
    void outputWithoutCostRowsIsStillUnknown() {
        OrderCostBreakdownDTO odd = OrderCostBreakdownDTO.builder().orderId("CLK-B-X")
                .totalCost(null).boxCount(10).outputQuantity(new BigDecimal("10"))
                .missingCostItems(List.of("NO_COST_COLLECTED")).build();
        assertThat(SummaryCostRollup.rollup(List.of(odd)).total()).isNull();
    }

    @Test
    @DisplayName("阴性对照: 没有成品批次 → 0 (这是「还没发生」不是「未知」)")
    void emptyIsZero() {
        var r = SummaryCostRollup.rollup(List.of());
        assertThat(r.total()).isEqualByComparingTo("0");
        assertThat(r.note()).isNull();
    }
}
