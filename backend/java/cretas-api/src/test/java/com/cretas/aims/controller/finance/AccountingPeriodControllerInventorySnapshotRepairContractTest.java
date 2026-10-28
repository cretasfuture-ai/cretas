package com.cretas.aims.controller.finance;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.finance.AccountingPeriodService;
import com.cretas.aims.service.inventory.InventoryLedgerSnapshotService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountingPeriodControllerInventorySnapshotRepairContractTest {

    @Mock AccountingPeriodService periodService;
    @Mock InventoryLedgerSnapshotService snapshotService;
    @Mock HttpServletRequest request;

    private AccountingPeriodController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountingPeriodController(periodService, snapshotService);
        when(request.getAttribute("factoryId")).thenReturn("F006");
        when(request.getAttribute("userId")).thenReturn("1309");
        when(request.getAttribute("role")).thenReturn("factory_super_admin");
    }

    @Test
    void acceptsOnlyMatchingPositiveSuperAdminAndReturnsReceipt() {
        var receipt = new InventoryLedgerSnapshotService.RepairResult(
                "F006", "period-1", 2, 0, List.of("MAT-1", "MAT-2"));
        when(snapshotService.repairMissingInventorySnapshots("F006", "period-1"))
                .thenReturn(receipt);

        var response = controller.repairMissingInventorySnapshots("F006", "period-1", request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuccess()).isTrue();
        assertThat(response.getBody().getData()).isEqualTo(receipt);
        verify(snapshotService).repairMissingInventorySnapshots("F006", "period-1");
    }

    @Test
    void rejectsMismatchedFactoryRoleOrNonPositiveUserBeforeServiceCall() {
        when(request.getAttribute("factoryId")).thenReturn("F007");
        assertThatThrownBy(() -> controller.repairMissingInventorySnapshots("F006", "period-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(403);

        when(request.getAttribute("factoryId")).thenReturn("F006");
        when(request.getAttribute("role")).thenReturn("finance_manager");
        assertThatThrownBy(() -> controller.repairMissingInventorySnapshots("F006", "period-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(403);

        when(request.getAttribute("role")).thenReturn("factory_super_admin");
        when(request.getAttribute("userId")).thenReturn("0");
        assertThatThrownBy(() -> controller.repairMissingInventorySnapshots("F006", "period-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(403);

        verifyNoInteractions(snapshotService);
    }
}
