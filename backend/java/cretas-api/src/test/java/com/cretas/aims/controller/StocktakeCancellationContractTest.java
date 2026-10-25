package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.factory.StocktakeCancelRequest;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for the audited maker-only stocktake cancellation endpoint. */
@DisplayName("Stocktake cancellation HTTP contract")
class StocktakeCancellationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FactoryStocktakeService stocktakeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stocktakeService = mock(FactoryStocktakeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StocktakeController(stocktakeService)).build();
    }

    @Test
    @DisplayName("POST cancel sends typed reason/version and authenticated actor, returns persisted CANCELLED")
    void cancel_sendsFrozenPayloadAndActor() throws Exception {
        StocktakeDTO cancelled = new StocktakeDTO();
        cancelled.setId("ST-1");
        cancelled.setStatus("CANCELLED");
        cancelled.setVersion(3L);
        cancelled.setCancelledBy(42L);
        cancelled.setCancelledAt(LocalDateTime.now());
        cancelled.setCancelReason("修正盘点范围");
        when(stocktakeService.cancel(eq("ST-1"), eq("F006"), any(StocktakeCancelRequest.class), eq(42L)))
                .thenReturn(cancelled);

        mvc.perform(post("/api/mobile/{factoryId}/stocktakes/{stocktakeId}/cancel", "F006", "ST-1")
                        .requestAttr("userId", 42L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                newRequest("修正盘点范围", 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("修正盘点范围"));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(StocktakeCancelRequest.class);
        verify(stocktakeService).cancel(eq("ST-1"), eq("F006"), requestCaptor.capture(), eq(42L));
        assertThat(requestCaptor.getValue().getReason()).isEqualTo("修正盘点范围");
        assertThat(requestCaptor.getValue().getExpectedVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("cancel route requires warehouse read/write permission and never accepts actor from body")
    void cancel_routeHasFrozenPermissionAndTypedBody() throws Exception {
        var method = StocktakeController.class.getDeclaredMethod(
                "cancel", String.class, String.class, StocktakeCancelRequest.class, HttpServletRequest.class);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("warehouse:read_write");
        assertThat(StocktakeCancelRequest.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .containsExactlyInAnyOrder("reason", "expectedVersion");
    }

    private StocktakeCancelRequest newRequest(String reason, Long expectedVersion) {
        StocktakeCancelRequest request = new StocktakeCancelRequest();
        request.setReason(reason);
        request.setExpectedVersion(expectedVersion);
        return request;
    }
}
