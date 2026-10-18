package com.cretas.aims.controller.finance;

import com.cretas.aims.dto.finance.OpeningApCorrectionRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.finance.FinanceRowScopeQuery;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArApGlobalScopeControllerContractTest {
    private final ArApService arApService = mock(ArApService.class);
    private final MobileService mobileService = mock(MobileService.class);
    private final OpeningInventoryService opening = mock(OpeningInventoryService.class);
    private final FinanceRowScopeQuery scope = mock(FinanceRowScopeQuery.class);
    private ArApController controller;

    @BeforeEach
    void setUp() {
        controller = new ArApController(arApService, new ObjectMapper(), mobileService,
                mock(FactoryConfigService.class), opening);
        ReflectionTestUtils.setField(controller, "financeRowScopeQuery", scope);
        when(mobileService.getUserFromToken(any())).thenReturn(UserDTO.builder().id(9L).build());
        doThrow(new BusinessException(403, "该操作需要全厂财务范围"))
                .when(scope).requireAll("F1");
    }

    @Test
    void creditCheckRejectsRestrictedScopeBeforeReadingGlobalCustomerBalance() {
        assertThatThrownBy(() -> controller.checkCreditLimit("F1", "C1", new BigDecimal("1.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(arApService);
    }

    @Test
    void openingCorrectionRejectsRestrictedScopeBeforeWriting() {
        OpeningApCorrectionRequest request = new OpeningApCorrectionRequest();
        request.setApTransactionIds(List.of("AP-1"));

        assertThatThrownBy(() -> controller.correctMisroutedOpeningAp("F1", "Bearer token", request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(opening);
        verifyNoInteractions(mobileService);
    }
}
