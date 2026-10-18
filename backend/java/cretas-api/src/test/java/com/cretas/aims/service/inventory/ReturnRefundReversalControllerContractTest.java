package com.cretas.aims.service.inventory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.inventory.ReturnRefundController;
import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReturnRefundReversalControllerContractTest {
    @Test void actualReversalRoutesBindOriginalRequestAndDecisionLedger() throws Exception {
        var service=mock(ReturnRefundService.class);
        var view=ReturnRefundDTO.builder().returnOrderId("RO")
                .request(ReturnRefundDTO.RequestView.builder().id("PR").status("PAID")
                    .reversal(ReturnRefundDTO.ReversalView.builder().status("PENDING")
                        .ledger(ReturnRefundDTO.LedgerView.builder().id("REV").sourceType("RETURN_REFUND_REVERSAL").sourceId("PR").build()).build())
                    .build()).build();
        when(service.getByRequest("F","PR")).thenReturn(view);
        var mvc=MockMvcBuilders.standaloneSetup(new ReturnRefundController(service)).build();
        String base="/api/mobile/F/return-orders/RO/refund/reversal";
        mvc.perform(post(base).requestAttr("userId",10L).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"PR\",\"paymentReference\":\"现金收回1\",\"reason\":\"错误退款款项已收回\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.request.id").value("PR"))
                .andExpect(jsonPath("$.data.request.reversal.ledger.sourceType").value("RETURN_REFUND_REVERSAL"))
                .andExpect(jsonPath("$.data.request.reversal.ledger.sourceId").value("PR"));
        verify(service).requestReversal(eq("F"),eq("RO"),eq(10L),argThat(c->c.getRequestId().equals("PR")&&c.getPaymentReference().equals("现金收回1")));
        mvc.perform(post(base+"/approve").requestAttr("userId",20L).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"PR\",\"reversalTransactionId\":\"REV\"}"))
                .andExpect(status().isOk());
        verify(service).approveReversal(eq("F"),eq("RO"),eq(20L),argThat(c->c.getRequestId().equals("PR")&&c.getReversalTransactionId().equals("REV")));
        mvc.perform(post(base+"/reject").requestAttr("userId",20L).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"PR\",\"reversalTransactionId\":\"REV\",\"reason\":\"请补充收回凭据\"}"))
                .andExpect(status().isOk());
        verify(service).rejectReversal(eq("F"),eq("RO"),eq(20L),argThat(c->c.getReason().equals("请补充收回凭据")));
    }
    @Test void missingRecoveryEvidenceIsRejectedBeforeCallingBusinessService() throws Exception {
        var service=mock(ReturnRefundService.class);var mvc=MockMvcBuilders.standaloneSetup(new ReturnRefundController(service)).build();
        mvc.perform(post("/api/mobile/F/return-orders/RO/refund/reversal").requestAttr("userId",10L)
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"PR\",\"paymentReference\":\" \",\"reason\":\"原因\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void reversalUsesExistingFactoryFinanceModuleAndIndependentFinanceDecisionPermission() throws Exception {
        assertThat(ReturnRefundController.class.getAnnotation(RequireModule.class).value()).isEqualTo("finance_ar");
        for(String name:new String[]{"approveReversal","rejectReversal"}) {
            assertThat(ReturnRefundController.class.getMethod(name,String.class,String.class,Long.class,ReturnRefundDTO.ReversalDecision.class)
                    .getAnnotation(RequirePermission.class).value()).containsExactly("finance:read_write");
        }
    }
}
