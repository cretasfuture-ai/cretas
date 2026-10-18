package com.cretas.aims.service.inventory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.inventory.ReturnRefundSourceController;
import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.security.PriceSensitive;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReturnRefundSourceControllerContractTest {
    @Test void exactRequestPathReturnsTheSourceBoundPresentation() throws Exception {
        ReturnRefundService service = mock(ReturnRefundService.class);
        ReturnRefundDTO view = ReturnRefundDTO.builder().returnOrderId("return-1").returnNumber("退货-1")
                .request(ReturnRefundDTO.RequestView.builder().id("refund-1").status("PAID").build()).build();
        when(service.getByRequest("factory-1", "refund-1")).thenReturn(view);
        MockMvcBuilders.standaloneSetup(new ReturnRefundSourceController(service)).build()
                .perform(get("/api/mobile/factory-1/return-refunds/refund-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.returnOrderId").value("return-1"))
                .andExpect(jsonPath("$.data.request.id").value("refund-1"));
        verify(service).getByRequest("factory-1", "refund-1");
        verifyNoMoreInteractions(service);
    }

    @Test void sourceLookupKeepsFinancialModuleAndPriceContracts() throws Exception {
        assertThat(ReturnRefundSourceController.class.getAnnotation(RequireModule.class).value()).isEqualTo("finance_ar");
        assertThat(ReturnRefundSourceController.class.getAnnotation(RequirePermission.class).value())
                .containsExactlyInAnyOrder("finance:read", "finance:read_write");
        for (String field : new String[]{"amount", "balanceBefore", "balanceAfter"}) {
            assertThat(ReturnRefundDTO.LedgerView.class.getDeclaredField(field).getAnnotation(PriceSensitive.class))
                    .as(field).isNotNull();
        }
    }
}
