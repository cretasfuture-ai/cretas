package com.cretas.aims.controller.finance;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.finance.VoucherGenerateRequest;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.VoucherEntryRepository;
import com.cretas.aims.repository.VoucherRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.service.finance.BalanceSheetService;
import com.cretas.aims.service.finance.CashFlowService;
import com.cretas.aims.service.finance.IncomeStatementService;
import com.cretas.aims.service.finance.VoucherRowScopeQuery;
import com.cretas.aims.service.finance.VoucherOwnershipSql;
import com.cretas.aims.service.voucher.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** Real controller method proof for source-owner filtering and restricted report projection. */
@ExtendWith(MockitoExtension.class)
class VoucherAndFinanceReportRowScopeControllerContractTest {

    @Mock private VoucherService voucherService;
    @Mock private VoucherRepository voucherRepository;
    @Mock private VoucherEntryRepository voucherEntryRepository;
    @Mock private BusinessRowScope businessRowScope;
    @Mock private VoucherRowScopeQuery voucherRowScopeQuery;
    @Mock private BalanceSheetService balanceSheetService;
    @Mock private IncomeStatementService incomeStatementService;
    @Mock private CashFlowService cashFlowService;

    private VoucherController voucherController;
    private FinanceReportController reportController;

    @BeforeEach
    void setUp() {
        voucherController = new VoucherController(voucherService, voucherRepository, voucherEntryRepository);
        ReflectionTestUtils.setField(voucherController, "businessRowScope", businessRowScope);
        ReflectionTestUtils.setField(voucherController, "voucherRowScopeQuery", voucherRowScopeQuery);
        reportController = new FinanceReportController(balanceSheetService, incomeStatementService, cashFlowService);
        ReflectionTestUtils.setField(reportController, "businessRowScope", businessRowScope);
        ReflectionTestUtils.setField(reportController, "voucherRowScopeQuery", voucherRowScopeQuery);
    }

    @Test
    void voucherListUsesSourceOwnerIdsBeforeRepositoryPaging() {
        List<Long> owners = List.of(56L);
        when(businessRowScope.creatorIds("F1")).thenReturn(owners);
        String ownerIdsJson = VoucherOwnershipSql.ownerIdsJson(owners);
        when(voucherRepository.searchVouchersInOwnerScope(eq("F1"), eq(null), eq(null), eq(null),
                eq(ownerIdsJson), any())).thenReturn(new PageImpl<>(List.<Voucher>of()));

        voucherController.list("F1", null, null, null, 1, 20);

        verify(voucherRepository).searchVouchersInOwnerScope(eq("F1"), eq(null), eq(null), eq(null),
                eq(ownerIdsJson), any());
        verify(voucherRepository, never()).searchVouchers(anyString(), any(), any(), any(), any());
    }

    @Test
    void voucherGenerateRejectsAnUnownedSourceBeforeCallingTheService() {
        List<Long> owners = List.of(56L);
        when(businessRowScope.creatorIds("F1")).thenReturn(owners);
        doThrow(new BusinessException(403, "source is outside scope"))
                .when(voucherRowScopeQuery).requireSourceAccess("F1", "SALES_ORDER", "SO-OTHER", owners);
        VoucherGenerateRequest request = new VoucherGenerateRequest();
        request.setBusinessType("SALES_ORDER");
        request.setBusinessId("SO-OTHER");

        assertThatThrownBy(() -> voucherController.generate("F1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("source is outside scope");
        verify(voucherService, never()).createFromBusiness(anyString(), anyString(), anyString());
    }

    @Test
    void restrictedBalanceSheetUsesScopedVoucherIdsAndCarriesProjectionMetadata() {
        List<Long> owners = List.of(56L);
        BalanceSheetDTO dto = new BalanceSheetDTO();
        when(businessRowScope.creatorIds("F1")).thenReturn(owners);
        when(balanceSheetService.generate("F1", 2026, 5, null)).thenReturn(dto);

        var response = reportController.balanceSheet("F1", 2026, 5);

        verify(balanceSheetService).generate("F1", 2026, 5, null);
        verify(balanceSheetService, never()).generate("F1", 2026, 5);
        assertThat(response.getHeaders().getFirst("X-Finance-Row-Scope"))
                .isEqualTo("RESTRICTED_SOURCE_OWNER_PROJECTION");
        ApiResponse<BalanceSheetDTO> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getData()).isSameAs(dto);
        assertThat(dto.getScopeApplied()).isTrue();
        assertThat(dto.getScopeLabel()).isEqualTo("授权凭证发生额投影");
        assertThat(dto.getScopeNote()).contains("不等同完整法定报表");
    }
}
