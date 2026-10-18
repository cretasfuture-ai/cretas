package com.cretas.aims.controller;

import com.cretas.aims.controller.inventory.ReturnOrderFinancialFollowUpController;
import com.cretas.aims.dto.inventory.ReturnFinancialFollowUpDTO;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.service.inventory.ReturnOrderFinancialFollowUpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReturnOrderFinancialFollowUpControllerContractTest {
    ReturnOrderFinancialFollowUpService service;
    MockMvc mvc;
    @AfterEach void cleanup() { PriceSensitiveContext.clear(); }
    @BeforeEach void setup() {
        service = mock(ReturnOrderFinancialFollowUpService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReturnOrderFinancialFollowUpController(service)).build();
    }
    @Test void exactFactoryAndReturnArePassedToTheReadOnlyService() throws Exception {
        when(service.get("A", "return-a")).thenReturn(ReturnFinancialFollowUpDTO.builder()
                .returnOrderId("return-a").returnNumber("RT-A").status("PENDING").build());
        mvc.perform(get("/api/mobile/A/return-orders/return-a/financial-follow-up"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.returnOrderId").value("return-a"));
        verify(service).get("A", "return-a"); verifyNoMoreInteractions(service);
    }
    @Test void resubmitUsesTheAuthenticatedActorAndOriginalSourceNotPayloadIdentity() throws Exception {
        when(service.resubmit("A", "return-a", 7L, "核对后重提")).thenReturn(new ArApTransaction());
        mvc.perform(post("/api/mobile/A/return-orders/return-a/resubmit-adjustment").requestAttr("userId", 7L)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"核对后重提\",\"actorId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).resubmit("A", "return-a", 7L, "核对后重提"); verifyNoMoreInteractions(service);
    }
    @Test void blankReasonOrMissingAuthenticatedActorCannotReachTheService() throws Exception {
        mvc.perform(post("/api/mobile/A/return-orders/return-a/resubmit-adjustment").requestAttr("userId", 7L)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/mobile/A/return-orders/return-a/resubmit-adjustment")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重提\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void financeModuleAndWritePermissionRemainOnTheActualEndpoints() throws Exception {
        assertThat(ReturnOrderFinancialFollowUpController.class.getAnnotation(RequireModule.class).value()).contains("finance_ar");
        assertThat(ReturnOrderFinancialFollowUpController.class.getMethod("get", String.class, String.class)
                .getAnnotation(RequirePermission.class).value()).containsExactly("finance:read", "finance:read_write", "finance:approve_adjustment");
        assertThat(ReturnOrderFinancialFollowUpController.class.getMethod("resubmit", String.class, String.class,
                Long.class, ReturnFinancialFollowUpDTO.ResubmitRequest.class).getAnnotation(RequirePermission.class).value())
                .containsExactly("finance:read_write");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nestedLedgerUsesExistingPriceMaskingWithoutMutatingTheManagedEntity(boolean allowed) throws Exception {
        var permissions = mock(PermissionService.class); var users = mock(UserRepository.class);
        var actor = new User(); actor.setId(9L); when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(permissions.hasPermission(actor, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)).thenReturn(allowed);
        var advice = new PriceFieldResponseAdvice();
        org.springframework.test.util.ReflectionTestUtils.setField(advice, "permissionService", permissions);
        org.springframework.test.util.ReflectionTestUtils.setField(advice, "userRepository", users);
        var mapper = new ObjectMapper().findAndRegisterModules()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule());
        mvc = MockMvcBuilders.standaloneSetup(new ReturnOrderFinancialFollowUpController(service)).setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        var row = new ArApTransaction(); row.setId("AR-A"); row.setAmount(new BigDecimal("-150"));
        row.setBalanceAfter(new BigDecimal("540")); row.setSettledAmount(BigDecimal.TEN); row.setOutstandingAmount(BigDecimal.ONE);
        when(service.get("A", "return-a")).thenReturn(ReturnFinancialFollowUpDTO.builder().returnOrderId("return-a")
                .returnNumber("RT-A").status("PENDING").adjustments(List.of(row)).build());
        var result = mvc.perform(get("/api/mobile/A/return-orders/return-a/financial-follow-up").requestAttr("userId", 9L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.adjustments[0].id").value("AR-A"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        for (var entry : java.util.Map.of("amount", -150, "balanceAfter", 540, "settledAmount", 10, "outstandingAmount", 1).entrySet()) {
            if (allowed) result.andExpect(jsonPath("$.data.adjustments[0]."+entry.getKey()).value(entry.getValue()));
            else result.andExpect(jsonPath("$.data.adjustments[0]."+entry.getKey()).value(nullValue()));
        }
        assertThat(row.getAmount()).isEqualByComparingTo("-150");
        assertThat(row.getBalanceAfter()).isEqualByComparingTo("540");
    }
}
