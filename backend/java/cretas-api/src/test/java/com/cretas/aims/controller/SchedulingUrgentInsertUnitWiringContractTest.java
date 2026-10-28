package com.cretas.aims.controller;

import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.LinUCBService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.UrgentInsertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4 接线: App 走的是这两个 GET 插单入口, 产能核对要的数量单位 / 指定产线必须真的传到 service。
 *
 * <p>2026-10-28 生产只读核对发现: 这两个入口原来不收 quantityUnit, 单位永远为空 ⇒ 产能永远「未验证」,
 * 而 service 层的测试全绿 (它们直接调 service, 绕过了这一层)。本测试走 controller 的真实请求映射。
 */
class SchedulingUrgentInsertUnitWiringContractTest {

    private UrgentInsertService urgentInsertService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        urgentInsertService = mock(UrgentInsertService.class);
        when(urgentInsertService.analyzeInsertImpact(any(), any(), any())).thenReturn(Map.of());
        when(urgentInsertService.getAvailableSlots(any(), any())).thenReturn(List.of());
        SchedulingController controller = new SchedulingController(
                mock(SchedulingService.class), urgentInsertService, mock(LinUCBService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void impactPassesUnitAndPreferredLineToTheCapacityCheck() throws Exception {
        mvc.perform(get("/api/mobile/F006/scheduling/urgent-insert/slots/S1/impact")
                        .param("productTypeId", "PT-1")
                        .param("quantity", "100")
                        .param("quantityUnit", "kg")
                        .param("preferredProductionLineId", "LINE-1"))
                .andExpect(status().isOk());

        GetInsertSlotsRequest req = captureImpactRequest();
        assertEquals("kg", req.getQuantityUnit());
        assertEquals("LINE-1", req.getPreferredProductionLineId());
        assertEquals(0, new BigDecimal("100").compareTo(req.getRequiredQuantity()));
    }

    @Test
    void impactAcceptsAFractionalQuantity() throws Exception {
        // 按 kg 下单时 12.5 是正常的数量; 原来 Integer 参数会把它拒成 400
        mvc.perform(get("/api/mobile/F006/scheduling/urgent-insert/slots/S1/impact")
                        .param("productTypeId", "PT-1")
                        .param("quantity", "12.5")
                        .param("quantityUnit", "KG"))
                .andExpect(status().isOk());

        assertEquals(0, new BigDecimal("12.5").compareTo(captureImpactRequest().getRequiredQuantity()));
    }

    @Test
    void withoutAUnitTheRequestCarriesNoUnitInsteadOfAKgDefault() throws Exception {
        mvc.perform(get("/api/mobile/F006/scheduling/urgent-insert/slots/S1/impact")
                        .param("productTypeId", "PT-1")
                        .param("quantity", "100"))
                .andExpect(status().isOk());

        assertNull(captureImpactRequest().getQuantityUnit());
    }

    @Test
    void anUnknownUnitIsRejectedNotTreatedAsMissing() throws Exception {
        mvc.perform(get("/api/mobile/F006/scheduling/urgent-insert/slots/S1/impact")
                        .param("productTypeId", "PT-1")
                        .param("quantity", "100")
                        .param("quantityUnit", "TON"))
                .andExpect(status().isBadRequest());

        verify(urgentInsertService, never()).analyzeInsertImpact(any(), any(), any());
    }

    @Test
    void slotListingPassesTheUnitToo() throws Exception {
        mvc.perform(get("/api/mobile/F006/scheduling/urgent-insert/slots")
                        .param("productTypeId", "PT-1")
                        .param("quantity", "20")
                        .param("quantityUnit", "PIECE"))
                .andExpect(status().isOk());

        ArgumentCaptor<GetInsertSlotsRequest> captor = ArgumentCaptor.forClass(GetInsertSlotsRequest.class);
        verify(urgentInsertService).getAvailableSlots(eq("F006"), captor.capture());
        assertEquals("PIECE", captor.getValue().getQuantityUnit());
    }

    private GetInsertSlotsRequest captureImpactRequest() {
        ArgumentCaptor<GetInsertSlotsRequest> captor = ArgumentCaptor.forClass(GetInsertSlotsRequest.class);
        verify(urgentInsertService).analyzeInsertImpact(eq("F006"), eq("S1"), captor.capture());
        return captor.getValue();
    }
}
