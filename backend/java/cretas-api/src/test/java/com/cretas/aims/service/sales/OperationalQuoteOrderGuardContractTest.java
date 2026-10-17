package com.cretas.aims.service.sales;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.sales.OperationalQuote;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.sales.OperationalQuoteRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OperationalQuoteOrderGuardContractTest {
    final OperationalQuoteRepository repository = mock(OperationalQuoteRepository.class);
    final UnitContractService units = mock(UnitContractService.class);
    final DataScopeResolver resolver = mock(DataScopeResolver.class);
    final EntityManager em = mock(EntityManager.class);
    final OperationalQuoteOrderGuard guard = new OperationalQuoteOrderGuard(repository, units, resolver);
    OperationalQuote quote;
    CreateSalesOrderRequest request;

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(guard, "entityManager", em);
        quote = new OperationalQuote(); quote.setId("Q"); quote.setFactoryId("F");
        quote.setCustomerId("C"); quote.setProductTypeId("P"); quote.setSubmittedByUserId(3L);
        quote.setStatus("APPROVED"); quote.setUnit("个"); quote.setUnitPrice(new BigDecimal("62"));
        quote.setMinOrderQty(BigDecimal.ONE);
        when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("Q", "F")).thenReturn(Optional.of(quote));
        when(units.normalize(eq("F"), anyString())).thenAnswer(invocation -> {
            String unit = invocation.getArgument(1);
            String code = switch (unit) { case "box", "盒" -> "box"; case "case", "箱" -> "case"; default -> unit; };
            return new UnitNormalizationResult(unit, code, null);
        });
        request = new CreateSalesOrderRequest(); request.setQuoteId("Q"); request.setCustomerId("C");
        var line = new CreateSalesOrderRequest.SalesOrderItemDTO();
        line.setProductTypeId("P"); line.setQuantity(new BigDecimal("2")); line.setUnit("个");
        line.setTaxRate(new BigDecimal("13")); line.setUnitPrice(new BigDecimal("54.8673"));
        request.setItems(List.of(line));
    }
    @Test void approvedGross62PerPieceAcceptsTheEquivalentNetPriceAndLocksAuthoritativeQuote() {
        assertDoesNotThrow(() -> guard.validateCreate("F", request));
        verify(em).refresh(quote, LockModeType.PESSIMISTIC_READ);
        verify(resolver).assertCanAccess("F", 3L);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING_QUOTE","PENDING_APPROVAL","REJECTED","EXPIRED"})
    void unapprovedStatesRejectBeforeOrderCreation(String status) { quote.setStatus(status); blocked("尚未批准"); }
    @Test void expiredApprovedQuoteRejects() { quote.setValidUntil(LocalDate.now().minusDays(1)); blocked("过期"); }
    @Test void lastValidDayAndNoExpiryRemainUsable() { quote.setValidUntil(LocalDate.now()); assertDoesNotThrow(() -> guard.validateCreate("F", request)); }
    @Test void wrongCustomerRejects() { request.setCustomerId("OTHER"); blocked("客户"); }
    @Test void deletionCommittedBeforeTheLockAlsoRejects() {
        doAnswer(i -> { quote.setDeletedAt(java.time.LocalDateTime.now()); return null; })
                .when(em).refresh(quote, LockModeType.PESSIMISTIC_READ);
        blocked("删除");
    }
    @Test void wrongProductRejects() { request.getItems().get(0).setProductTypeId("OTHER"); blocked("产品"); }
    @Test void wrongUnitRejectsInsteadOfInventingAConversion() { request.getItems().get(0).setUnit("kg"); blocked("单位"); }
    @Test void changedGrossPriceRejects() { request.getItems().get(0).setUnitPrice(new BigDecimal("62")); blocked("含税单价"); }
    @Test void roundingToTheSameCentCannotHideAnUnapprovedNetPrice() { request.getItems().get(0).setUnitPrice(new BigDecimal("54.8630")); blocked("含税单价"); }
    @Test void actualDatabaseScaleIsUsedForTheComparison() { request.getItems().get(0).setUnitPrice(new BigDecimal("54.86731")); assertDoesNotThrow(() -> guard.validateCreate("F",request)); }
    @Test void headerDiscountCannotUndercutTheApprovedPrice() { request.setDiscountAmount(BigDecimal.ONE); blocked("整单"); }
    @Test void discountCannotSilentlyUndercutApprovedPrice() { request.getItems().get(0).setDiscountRate(new BigDecimal("0.1")); blocked("折扣"); }
    @Test void unknownTaxRateRejects() { request.getItems().get(0).setTaxRate(null); blocked("税率"); }
    @Test void belowMinimumQuantityRejects() { quote.setMinOrderQty(new BigDecimal("3")); blocked("起订量"); }
    @Test void missingOrForeignQuoteRejects() { when(repository.findByIdAndFactoryIdAndDeletedAtIsNull("Q", "F")).thenReturn(Optional.empty()); blocked("当前工厂"); verifyNoInteractions(em); }
    @Test void hiddenOwnerRejectsEvenInsideASalesOrderRoute() {
        doThrow(new BusinessException(403,"无权访问")).when(resolver).assertCanAccess("F",3L);
        assertThrows(BusinessException.class, () -> guard.validateCreate("F", request)); verifyNoInteractions(em);
    }
    @Test void authoritativeRefreshDefeatsAStaleApprovedObject() {
        doAnswer(i -> { quote.setStatus("PENDING_QUOTE"); return null; }).when(em).refresh(quote,LockModeType.PESSIMISTIC_READ);
        blocked("尚未批准");
    }
    @ParameterizedTest @ValueSource(strings={"box","case","g"})
    void legacyUnitsKeepTheirOwnPriceBasis(String unit) {
        quote.setUnit(unit); request.getItems().get(0).setUnit(unit.equals("box") ? "盒" : unit.equals("case") ? "箱" : "g");
        assertDoesNotThrow(() -> guard.validateCreate("F",request));
    }
    @Test void confirmationRechecksThePersistedReferenceAndActualLinePrices() {
        var order = new SalesOrder(); order.setQuoteId("Q"); order.setCustomerId("C");
        var item = new SalesOrderItem(); item.setProductTypeId("P"); item.setUnit("个"); item.setQuantity(new BigDecimal("2"));
        item.setUnitPrice(new BigDecimal("54.8673")); item.setTaxRate(new BigDecimal("13")); item.setQuotedGrossUnitPrice(new BigDecimal("62"));
        assertDoesNotThrow(() -> guard.validateConfirmation("F",order,List.of(item)));
        quote.setStatus("REJECTED"); assertThrows(BusinessException.class,() -> guard.validateConfirmation("F",order,List.of(item)));
    }
    @Test void aLegacyBoundDraftCannotBeConfirmedWithoutAGrossSnapshot() {
        var order = new SalesOrder(); order.setQuoteId("Q"); order.setCustomerId("C");
        var item = new SalesOrderItem(); item.setProductTypeId("P"); item.setUnit("个"); item.setQuantity(new BigDecimal("2"));
        item.setUnitPrice(new BigDecimal("54.8673")); item.setTaxRate(new BigDecimal("13"));
        var error=assertThrows(BusinessException.class,()->guard.validateConfirmation("F",order,List.of(item)));
        assertTrue(error.getMessage().contains("快照"));
        item.setQuotedGrossUnitPrice(new BigDecimal("61"));
        assertThrows(BusinessException.class,()->guard.validateConfirmation("F",order,List.of(item)));
    }
    @Test void aFullDraftEditCanUpgradeOnlyWithTheLockedQuotePrice() {
        var order = new SalesOrder(); order.setQuoteId("Q"); order.setCustomerId("C");
        var update = new com.cretas.aims.dto.inventory.UpdateSalesOrderRequest(); update.setItems(request.getItems());
        assertEquals(0,new BigDecimal("62").compareTo(guard.validateUpdate("F",order,update,List.of())));
        update.getItems().get(0).setUnitPrice(new BigDecimal("54.8630"));
        assertThrows(BusinessException.class,()->guard.validateUpdate("F",order,update,List.of()));
    }
    @Test void standaloneOrderWithoutQuotePreservesLegacyPath() {
        request.setQuoteId(null); clearInvocations(repository,units);
        guard.validateCreate("F",request); verifyNoInteractions(repository,units,em,resolver);
    }
    private void blocked(String message) {
        var error = assertThrows(BusinessException.class,() -> guard.validateCreate("F",request));
        assertTrue(error.getMessage().contains(message),error.getMessage());
        verify(repository,never()).save(any());
    }
}
