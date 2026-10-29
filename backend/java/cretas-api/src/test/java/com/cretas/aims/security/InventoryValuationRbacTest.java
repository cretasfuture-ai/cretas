package com.cretas.aims.security;

import com.cretas.aims.controller.MaterialBatchController;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存估值的门 (S5-12 D-S5-05, 取代 2026-05-12 E5 §5.2 的 procurement:price:view 名单门)。
 *
 * <p>响应是裸 {@code BigDecimal} (全厂库存价值合计), advice 没有字段可剥, 所以仍然是「看不到就 403」;
 * 口径改为批次采购成本同一个: {@link PriceSensitive#PURCHASE_OR_FINANCE} (配置的采购读 或 财务读)。
 * 原来的名单让销售 / 生产经理 (配置里采购、财务都是「-」) 读得到全厂估值。
 * 逐角色的真实矩阵读数见 {@code PurchasePriceVisibilityConfigContractTest#inventoryValuationFollowsProcurementOrFinanceRead}。
 */
@DisplayName("S5-12 — 库存估值按「采购读 或 财务读」(配置) 判")
class InventoryValuationRbacTest {

    private final MaterialBatchService service = mock(MaterialBatchService.class);
    private final PriceMaskResolver resolver = mock(PriceMaskResolver.class);
    private final MaterialBatchController controller =
            new MaterialBatchController(service, mock(MobileService.class), resolver, null);

    @Test
    void callerWithoutProcurementOrFinanceReadGets403BeforeAnyBusinessRead() {
        when(resolver.shouldMaskPrice(anyString(), org.mockito.ArgumentMatchers.eq(PriceSensitive.PURCHASE_OR_FINANCE)))
                .thenReturn(true);
        assertThatThrownBy(() -> controller.getInventoryValuation("F1", "Bearer t"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
        verify(service, never()).getInventoryValuation(anyString());
    }

    @Test
    void callerWithProcurementOrFinanceReadGetsTheValuation() {
        when(resolver.shouldMaskPrice(anyString(), org.mockito.ArgumentMatchers.eq(PriceSensitive.PURCHASE_OR_FINANCE)))
                .thenReturn(false);
        when(service.getInventoryValuation("F1")).thenReturn(new BigDecimal("119084.21"));
        assertThat(controller.getInventoryValuation("F1", "Bearer t").getData()).isEqualByComparingTo("119084.21");
    }
}
