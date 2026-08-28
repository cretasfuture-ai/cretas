package com.cretas.aims.service.wip;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSemiOutputPostingTest {

    /**
     * 金检报工: 良品 950 进良品半成品, 回料 50 进回料半成品。
     * 两笔都要入账 —— 只入一笔就等于回料凭空消失。
     */
    @Test
    void 两个产出各入一笔() {
        List<Map<String, Object>> outputs = List.of(
                Map.of("semiCode", "PTSEMI-LSM-0010", "quantity", new BigDecimal("950.00"), "isRework", false),
                Map.of("semiCode", "PTSEMI-LSM-0003", "quantity", new BigDecimal("50.00"), "isRework", true));

        List<Map<String, Object>> postable = WipSemiOutputPlan.postableEntries(outputs);

        assertThat(postable).hasSize(2);
        assertThat(postable).extracting(m -> m.get("semiCode"))
                .containsExactly("PTSEMI-LSM-0010", "PTSEMI-LSM-0003");
    }

    @Test
    void 数量为0或负或缺码的条目被跳过而不是当成0入账() {
        List<Map<String, Object>> outputs = java.util.Arrays.asList(
                Map.of("semiCode", "A", "quantity", new BigDecimal("10")),
                Map.of("semiCode", "B", "quantity", BigDecimal.ZERO),
                Map.of("semiCode", "C", "quantity", new BigDecimal("-5")),
                Map.of("quantity", new BigDecimal("7")),
                Map.of("semiCode", "D"));

        assertThat(WipSemiOutputPlan.postableEntries(outputs))
                .extracting(m -> m.get("semiCode"))
                .containsExactly("A");
    }

    @Test
    void 空列表和null都返回空_旧式报工零回归() {
        assertThat(WipSemiOutputPlan.postableEntries(null)).isEmpty();
        assertThat(WipSemiOutputPlan.postableEntries(List.of())).isEmpty();
    }
}
