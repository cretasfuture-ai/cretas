package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.dto.sales.SalesProductInventorySummaryDTO;
import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesControllerProductInventorySummaryTest {

    @Test
    void aggregatesFinishedAndSemiFinishedAvailableInventoryByProduct() {
        FinishedGoodsFeedService finishedGoods = mock(FinishedGoodsFeedService.class);
        WipInventoryService wipInventory = mock(WipInventoryService.class);
        SalesController controller = controller();
        ReflectionTestUtils.setField(controller, "finishedGoodsFeedService", finishedGoods);
        ReflectionTestUtils.setField(controller, "wipInventoryService", wipInventory);

        when(finishedGoods.listAvailableForFeed("F006", null)).thenReturn(List.of(
                FinishedGoodsStockItem.builder().productTypeId("FG-1").availableQuantity(new BigDecimal("6")).unit("box").build(),
                FinishedGoodsStockItem.builder().productTypeId("FG-1").availableQuantity(new BigDecimal("4")).unit("box").build()));
        when(wipInventory.listWipByFactory("F006", null, null)).thenReturn(List.of(
                WipRowDTO.builder().productTypeId("SEMI-1").availableQuantity(new BigDecimal("2.5")).unit("kg").build(),
                WipRowDTO.builder().productTypeId("SEMI-1").availableQuantity(BigDecimal.ZERO).unit("kg").build()));

        List<SalesProductInventorySummaryDTO> rows = controller.productInventorySummary("F006").getData();

        assertThat(rows).extracting(SalesProductInventorySummaryDTO::getProductTypeId)
                .containsExactly("FG-1", "SEMI-1");
        assertThat(rows.get(0).getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(rows.get(1).getAvailableQuantity()).isEqualByComparingTo("2.5");
    }

    private static SalesController controller() {
        return new SalesController(
                mock(SalesService.class),
                mock(MobileService.class),
                mock(PermissionService.class),
                mock(UserRepository.class),
                mock(SalesOrderRepository.class),
                mock(BusinessLinkRepository.class),
                mock(ProductTypeRepository.class),
                mock(FinishedGoodsBatchRepository.class),
                mock(GrossMarginRedlineService.class),
                mock(EstimatePriceCheckService.class),
                mock(SalesPriceAdjustmentService.class),
                mock(ProductPackagingSpecService.class));
    }
}
