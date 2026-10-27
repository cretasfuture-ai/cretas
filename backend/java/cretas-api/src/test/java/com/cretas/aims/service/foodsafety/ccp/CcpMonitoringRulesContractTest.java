package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口 13 的纯判据 (无 IO): 限值闭区间、未处理偏离、逐道录入锚号 → 计划前缀、CSV 公式注入防护。
 * 端到端行为见 {@link CcpMonitoringContractTest}。
 */
@DisplayName("CCP 判据: 限值 / 未处理偏离 / 锚号 / CSV")
class CcpMonitoringRulesContractTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("限值是闭区间: 等于上下限算合格, 超出一点算偏离; 只配了一侧时只判那一侧")
    void limitsAreInclusive() {
        assertThat(CcpMonitoringService.isOutOfLimit(d("75"), d("75"), d("100"))).isFalse();
        assertThat(CcpMonitoringService.isOutOfLimit(d("100"), d("75"), d("100"))).isFalse();
        assertThat(CcpMonitoringService.isOutOfLimit(d("74.99"), d("75"), d("100"))).isTrue();
        assertThat(CcpMonitoringService.isOutOfLimit(d("100.01"), d("75"), d("100"))).isTrue();
        assertThat(CcpMonitoringService.isOutOfLimit(d("5"), null, d("4"))).isTrue();
        assertThat(CcpMonitoringService.isOutOfLimit(null, d("75"), d("100"))).isFalse();
    }

    @Test
    @DisplayName("未处理偏离 = 偏离且没有处理时间; 合格记录与已处理偏离都不挡 (出货闸用同一判据)")
    void openDeviation() {
        HaccpMonitoringRecord ok = HaccpMonitoringRecord.builder().isDeviation(false).build();
        HaccpMonitoringRecord open = HaccpMonitoringRecord.builder().isDeviation(true).build();
        HaccpMonitoringRecord handled = HaccpMonitoringRecord.builder().isDeviation(true)
                .deviationHandledAt(LocalDateTime.now()).build();
        assertThat(CcpOpenDeviationLookup.isOpenDeviation(ok)).isFalse();
        assertThat(CcpOpenDeviationLookup.isOpenDeviation(open)).isTrue();
        assertThat(CcpOpenDeviationLookup.isOpenDeviation(handled)).isFalse();
        assertThat(CcpOpenDeviationLookup.isOpenDeviation(null)).isFalse();
        assertThat(CcpMonitoringService.resultOf(open)).isEqualTo("DEVIATION_OPEN");
        assertThat(CcpMonitoringService.resultOf(handled)).isEqualTo("DEVIATION_HANDLED");
        assertThat(CcpMonitoringService.resultOf(ok)).isEqualTo("OK");
    }

    @Test
    @DisplayName("逐道录入锚号 CLK-SEMI-<计划前8位>-<产品前8位> → 计划前缀; 无计划 (8 个 0) / 非锚号 → null")
    void anchorPlanPrefix() {
        assertThat(CcpOpenDeviationLookup.planPrefixOfAnchor("CLK-SEMI-1a2b3c4d-9f8e7d6c")).isEqualTo("1a2b3c4d");
        assertThat(CcpOpenDeviationLookup.planPrefixOfAnchor("CLK-SEMI-00000000-9f8e7d6c")).isNull();
        assertThat(CcpOpenDeviationLookup.planPrefixOfAnchor("CLK-W-20261026-123")).isNull();
        assertThat(CcpOpenDeviationLookup.planPrefixOfAnchor(null)).isNull();
    }

    @Test
    @DisplayName("CSV: 逗号/引号转义; = + @ 开头的文本加单引号防 Excel 公式注入, 负数照常")
    void csvEscaping() {
        assertThat(CcpMonitoringService.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(CcpMonitoringService.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CcpMonitoringService.csv("=1+1")).isEqualTo("'=1+1");
        assertThat(CcpMonitoringService.csv("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CcpMonitoringService.csv("-12.5")).isEqualTo("-12.5");
        assertThat(CcpMonitoringService.csv(null)).isEmpty();
    }
}
