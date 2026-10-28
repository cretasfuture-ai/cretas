package com.cretas.aims.controller;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.scheduling.daily.ProductDailyCapacityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PUT /product-types/{id}/daily-capacity: 写人工日产能 (或清掉), 操作人取自 Authorization 令牌
 * (getCurrentUserId 在本项目恒为 null, 见 CurrentUserIdSourceGateContractTest), 只走 ProductDailyCapacityService。
 */
class ProductDailyCapacityEndpointTest {

    private ProductTypeController controller;
    private ProductDailyCapacityService capacityService;
    private ProductTypeService productTypeService;

    @BeforeEach
    void setUp() {
        productTypeService = mock(ProductTypeService.class);
        MobileService mobileService = mock(MobileService.class);
        capacityService = mock(ProductDailyCapacityService.class);
        controller = new ProductTypeController(productTypeService, mobileService, null, null, null, null,
                capacityService);
        UserDTO user = new UserDTO();
        user.setId(1552L);
        when(mobileService.getUserFromToken("tok")).thenReturn(user);
        when(productTypeService.getProductTypeById("F006", "PT-1")).thenReturn(ProductTypeDTO.builder()
                .id("PT-1").dailyCapacity(new BigDecimal("300")).dailyCapacityUnit("袋").build());
        ReflectionTestUtils.setField(controller, "productDailyCapacityService", capacityService);
    }

    @Test
    void savesThroughTheCapacityServiceWithTheTokenUserAndReturnsTheProduct() {
        var response = controller.updateDailyCapacity("F006", "PT-1",
                new ProductTypeController.DailyCapacityUpdateRequest(new BigDecimal("300"), "袋"), "Bearer tok");

        verify(capacityService).save("F006", "PT-1", new BigDecimal("300"), "袋", 1552L);
        verify(capacityService, never()).clear(anyString(), anyString(), any());
        assertThat(response.getData().getDailyCapacity()).isEqualByComparingTo("300");
        assertThat(response.getData().getDailyCapacityUnit()).isEqualTo("袋");
    }

    @Test
    void nullQuantityClearsInsteadOfSaving() {
        controller.updateDailyCapacity("F006", "PT-1",
                new ProductTypeController.DailyCapacityUpdateRequest(null, null), "Bearer tok");

        verify(capacityService).clear("F006", "PT-1", 1552L);
        verify(capacityService, never()).save(anyString(), anyString(), any(), any(), any());
    }
}
