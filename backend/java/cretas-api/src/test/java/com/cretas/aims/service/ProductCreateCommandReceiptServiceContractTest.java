package com.cretas.aims.service;

import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.ProductWorkProcessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCreateCommandReceiptServiceContractTest {

    private final ToolExecutionIdempotencyRepository repository = mock(ToolExecutionIdempotencyRepository.class);
    private final ProductTypeService productTypeService = mock(ProductTypeService.class);
    private final ProductWorkProcessService productWorkProcessService = mock(ProductWorkProcessService.class);
    private final ProductCreateCommandReceiptService service =
            new ProductCreateCommandReceiptService(repository, productTypeService, productWorkProcessService);

    @AfterEach
    void clearScope() {
        ProductCreateCommandReceiptService.currentScope();
        // The production scope is always closed by try-with-resources; this protects failed tests.
        ProductCreateCommandReceiptService.CommandScope scope = ProductCreateCommandReceiptService.currentScope();
        if (scope != null) scope.close();
    }

    @Test
    void requiresBoundScopeAndRejectsNesting() {
        assertThatThrownBy(() -> service.recordProductCreated("P1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope");
        try (ProductCreateCommandReceiptService.CommandScope ignored = scope()) {
            assertThatThrownBy(() -> ProductCreateCommandReceiptService.openScope(
                    "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                    "idem2", "command2", "confirmation2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nested");
        }
    }

    @Test
    void attachesAndAdvancesOnlyTheFullLocator() {
        try (ProductCreateCommandReceiptService.CommandScope ignored = scope()) {
            when(repository.attachProductCreateReference(
                    eq("F006"), eq("FACTORY"), eq(PrincipalType.USER), eq("42"),
                    eq("product_create"), eq("2.0.0"), eq("idem"), eq("command"),
                    eq("confirmation"), eq(ToolExecutionIdempotencyRecord.State.IN_PROGRESS),
                    eq("PRODUCT_TYPE"), eq("P1"), eq("PRODUCT_CREATED"))).thenReturn(1);
            service.recordProductCreated("P1");

            ToolExecutionIdempotencyRecord row = record("ledger-1");
            setReference(row, "PRODUCT_TYPE", "P1", ProductCreateCommandReceiptService.PRODUCT_CREATED);
            when(repository.findByFullProductCreateLocator(
                    eq("F006"), eq("FACTORY"), eq(PrincipalType.USER), eq("42"),
                    eq("product_create"), eq("2.0.0"), eq("idem"), eq("command"),
                    eq("confirmation"))).thenReturn(Optional.of(row));
            when(repository.advanceProductCreateReference(
                    eq("ledger-1"), eq("F006"), eq("FACTORY"), eq(PrincipalType.USER), eq("42"),
                    eq("product_create"), eq("2.0.0"), eq("idem"), eq("command"), eq("confirmation"),
                    eq(ToolExecutionIdempotencyRecord.State.IN_PROGRESS), eq("PRODUCT_TYPE"), eq("P1"),
                    eq("PRODUCT_CREATED"), eq("COPY_COMMITTED"))).thenReturn(1);
            service.recordCopyCommitted("P1");
            verify(repository).advanceProductCreateReference(
                    eq("ledger-1"), eq("F006"), eq("FACTORY"), eq(PrincipalType.USER), eq("42"),
                    eq("product_create"), eq("2.0.0"), eq("idem"), eq("command"), eq("confirmation"),
                    eq(ToolExecutionIdempotencyRecord.State.IN_PROGRESS), eq("PRODUCT_TYPE"), eq("P1"),
                    eq("PRODUCT_CREATED"), eq("COPY_COMMITTED"));
        }
    }

    @Test
    void onlyVerifiedSuccessCanReplayAndInDoubtRemainsUnknown() {
        try (ProductCreateCommandReceiptService.CommandScope ignored = scope()) {
            ToolExecutionIdempotencyRecord row = record("ledger-replay");
            setReference(row, "PRODUCT_TYPE", "P-REPLAY", ProductCreateCommandReceiptService.READBACK_VERIFIED);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    row, "state", ToolExecutionIdempotencyRecord.State.SUCCEEDED);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    row, "outcomeStatus", ToolExecutionStatus.SUCCEEDED);
            when(repository.findByFullProductCreateLocator(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Optional.of(row));

            IntentPreviewToken token = IntentPreviewToken.builder()
                    .token("token-replay").factoryId("F006").tenantId("F006").userId(42L)
                    .status(IntentPreviewToken.TokenStatus.CONFIRMED)
                    .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
            assertThat(service.classifyTerminal(token).kind())
                    .isEqualTo(ProductCreateCommandReceiptService.ReplayDecision.Kind.REPLAY);

            org.springframework.test.util.ReflectionTestUtils.setField(
                    row, "state", ToolExecutionIdempotencyRecord.State.IN_DOUBT);
            assertThat(service.classifyTerminal(token).kind())
                    .isEqualTo(ProductCreateCommandReceiptService.ReplayDecision.Kind.UNKNOWN);
        }
    }

    private ProductCreateCommandReceiptService.CommandScope scope() {
        return ProductCreateCommandReceiptService.openScope(
                "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                "idem", "command", "confirmation");
    }

    private static ToolExecutionIdempotencyRecord record(String id) {
        return ToolExecutionIdempotencyRecord.reserve(
                id, "F006", "FACTORY", PrincipalType.USER, "42", "product_create", "2.0.0",
                "idem", "command", "confirmation", "audit-" + id, LocalDateTime.now());
    }

    private static void setReference(ToolExecutionIdempotencyRecord row,
                                     String type, String id, String stage) {
        org.springframework.test.util.ReflectionTestUtils.setField(row, "resultEntityType", type);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "resultEntityId", id);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "resultReferenceStage", stage);
    }
}
