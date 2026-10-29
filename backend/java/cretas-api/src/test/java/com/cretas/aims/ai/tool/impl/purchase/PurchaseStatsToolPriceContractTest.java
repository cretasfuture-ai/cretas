package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.service.inventory.PurchaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5-12 (B2): 采购统计工具原来 requiredPermissions 为空 —— 任何能用 AI 的人都能问到本月采购额。
 * 现在要采购读; 金额只在网关算出的采购价口径 (canViewPurchasePrices) 为真时给, 缺这个判定也不给。
 */
class PurchaseStatsToolPriceContractTest {

    private final PurchaseService purchaseService = mock(PurchaseService.class);
    private final PurchaseStatsTool tool = new PurchaseStatsTool();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tool, "purchaseService", purchaseService);
        when(purchaseService.getPurchaseStatistics("F1")).thenAnswer(inv -> new HashMap<>(Map.of(
                "monthlyOrderCount", 3L, "monthlyPurchaseAmount", new BigDecimal("450.00"))));
    }

    @Test
    void declaresProcurementRead() {
        assertThat(tool.requiresPermission()).isTrue();
        assertThat(tool.getRequiredPermissions()).containsExactly("procurement:read");
    }

    @Test
    void amountOnlyWhenGatewaySaysPurchasePricesAreVisible() throws Exception {
        assertThat(tool.doExecute("F1", Map.of(), Map.of("canViewPurchasePrices", false)))
                .containsEntry("monthlyOrderCount", 3L)
                .containsEntry("monthlyPurchaseAmount", null);
        // 只有旧名单口径 (canViewPrices) 不够
        assertThat(tool.doExecute("F1", Map.of(), Map.of("canViewPrices", true)))
                .containsEntry("monthlyPurchaseAmount", null);
        assertThat(tool.doExecute("F1", Map.of(), Map.of("canViewPurchasePrices", true)).get("monthlyPurchaseAmount"))
                .isEqualTo(new BigDecimal("450.00"));
    }
}
