package com.cretas.aims.service.finance;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.unit.UnitContractService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.springframework.test.util.ReflectionTestUtils;
class ReturnedCostSynchronizationServiceTest {
    final ReturnOrderItemRepository items=mock(ReturnOrderItemRepository.class);
    final ReturnOrderRepository orders=mock(ReturnOrderRepository.class);
    final FinishedGoodsBatchRepository batches=mock(FinishedGoodsBatchRepository.class);
    final FinishedGoodsAdjustmentLogRepository logs=mock(FinishedGoodsAdjustmentLogRepository.class);
    final AccountingPeriodService periods=mock(AccountingPeriodService.class);
    final DisposalCostAccountingService disposals=mock(DisposalCostAccountingService.class);
    final UnitContractService unitContractService=mock(UnitContractService.class);
    final EntityManager em=mock(EntityManager.class);final Query q=mock(Query.class,RETURNS_SELF);
    final ReturnedCostSynchronizationService service=new ReturnedCostSynchronizationService(items,orders,batches,logs,periods,disposals);
    FinishedGoodsBatch source,child;
    @BeforeEach void setup() {
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(anyString(), anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        ReflectionTestUtils.setField(service,"unitContractService",unitContractService);
        ReflectionTestUtils.setField(service,"entityManager",em);when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(0L);
        source=fg("SOURCE","B1","8.0000");child=fg("CHILD","RTN-RO-1","7.0000");child.setReturnOrderItemId(1L);
        var line=new ReturnOrderItem();line.setId(1L);line.setReturnOrderId("RO");line.setBatchNumber("B1");line.setProductTypeId("SKU");line.setQuantity(BigDecimal.ONE);
        var order=new ReturnOrder();order.setId("RO");order.setFactoryId("F");order.setReturnNumber("RO");order.setReturnDate(LocalDate.of(2026,10,1));order.setItems(List.of(line));
        when(items.findCompletedPhysicalReturnsForSource("F","SOURCE","B1")).thenReturn(List.of(line));
        when(orders.findById("RO")).thenReturn(Optional.of(order));when(periods.getStatus("F",2026,10)).thenReturn(AccountingPeriod.Status.OPEN);
        when(batches.findByFactoryIdAndReturnOrderItemId("F",1L)).thenReturn(List.of(child));
        when(batches.findByIdAndFactoryIdForUpdate("CHILD","F")).thenReturn(Optional.of(child));
    }
    FinishedGoodsBatch fg(String id,String number,String cost) {
        var b=new FinishedGoodsBatch();b.setId(id);b.setFactoryId("F");b.setBatchNumber(number);b.setProductTypeId("SKU");
        b.setUnit("盒");b.setUnitCost(new BigDecimal(cost));b.setProducedQuantity(BigDecimal.ONE);b.setShippedQuantity(BigDecimal.ZERO);b.setReservedQuantity(BigDecimal.ZERO);return b;
    }
    @Test void updatesProvenReturnedCostAndPreservesAuditAndRevaluesDisposals() {
        service.synchronize("F",source,new BigDecimal("7"),2L);
        assertThat(child.getUnitCost()).isEqualByComparingTo("8");verify(batches).save(child);
        verify(logs).save(argThat(l->l.getAdjustmentQuantity().signum()==0 && l.getOperatorId()==2L && l.getReason().contains("7.0000")));
        verify(disposals).revalueForBatch("F","CHILD",2L);verify(disposals).revalueForBatch("F","SOURCE",2L);
    }
    @Test void lockedReturnPeriodRejectsBeforeAnyCostWrite() {
        when(periods.getStatus("F",2026,10)).thenReturn(AccountingPeriod.Status.CLOSED);
        assertThatThrownBy(()->service.synchronize("F",source,new BigDecimal("7"),2L)).hasMessageContaining("先反结账");
        verify(batches,never()).save(any());verifyNoInteractions(disposals);
    }
    @Test void inconsistentOldSnapshotIsNotSilentlyOverwritten() {
        child.setUnitCost(new BigDecimal("6"));
        assertThatThrownBy(()->service.synchronize("F",source,new BigDecimal("7"),2L)).hasMessageContaining("更正前源成本不符");
        verify(batches,never()).save(any());
    }
    @Test void disposedQuantityIsReconstructedFromExistingLogs() {
        child.setProducedQuantity(new BigDecimal("0.5"));
        var log=FinishedGoodsAdjustmentLog.builder().factoryId("F").batchId("CHILD").adjustmentQuantity(new BigDecimal("-0.5")).build();
        when(logs.findByBatchIdOrderByCreatedAtDesc("CHILD")).thenReturn(List.of(log));
        service.synchronize("F",source,new BigDecimal("7"),2L);assertThat(child.getUnitCost()).isEqualByComparingTo("8");
    }
    @Test void crossTenantSourceAndMissingActorAreRejected() {
        assertThatThrownBy(()->service.synchronize("OTHER",source,new BigDecimal("7"),2L)).hasMessageContaining("源批次无效");
        assertThatThrownBy(()->service.synchronize("F",source,new BigDecimal("7"),null)).hasMessageContaining("已认证操作者");
        verify(batches,never()).save(any());
    }
    @Test void reshippedReturnWithoutAnyPhysicalEdgesCannotRewriteCost() {
        child.setShippedQuantity(BigDecimal.ONE);
        assertThatThrownBy(()->service.synchronize("F",source,new BigDecimal("7"),2L)).hasMessageContaining("扣库证据缺失");
        verify(batches,never()).save(any());
    }
    @Test void partiallyMissingReshipmentEvidenceCannotRewriteCost() {
        child.setShippedQuantity(new BigDecimal("2"));
        when(q.getResultList()).thenReturn(Collections.singletonList(new Object[]{LocalDate.of(2026,10,2),BigDecimal.ONE,0L}));
        assertThatThrownBy(()->service.synchronize("F",source,new BigDecimal("7"),2L)).hasMessageContaining("数量不完整");
        verify(batches,never()).save(any());
    }
    @Test void equalQuantityWithWrongUnitOrNonpositiveEdgesCannotRewriteCost() {
        child.setShippedQuantity(BigDecimal.ONE);
        when(q.getResultList()).thenReturn(Collections.singletonList(new Object[]{LocalDate.of(2026,10,2),BigDecimal.ONE,1L}));
        assertThatThrownBy(()->service.synchronize("F",source,new BigDecimal("7"),2L)).hasMessageContaining("原生单位或正数量");
        verify(q).setParameter("nativeUnit","盒");
        verify(em).createNativeQuery(contains("e.unit=:nativeUnit AND e.quantity_used>0"));
        verify(batches,never()).save(any());
    }

    @Test void clearCostWithoutDownstreamFactsIsAllowed() {
        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of());
        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of());
        when(q.getResultList()).thenReturn(List.of());
        source.setShippedQuantity(BigDecimal.ZERO);
        source.setReservedQuantity(BigDecimal.ZERO);

        service.assertCanClearAuthoritativeCost("F", source, BigDecimal.ONE);

        verifyNoInteractions(batches);
    }

    @Test void clearCostRejectsShipmentReturnScrapOrQuantityMismatchFacts() {
        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of());
        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of());
        when(q.getResultList()).thenReturn(List.of());

        source.setShippedQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost("F", source,
                BigDecimal.ONE)).hasMessageContaining("出货");

        source.setShippedQuantity(BigDecimal.ZERO);
        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of(new ReturnOrderItem()));
        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost("F", source,
                BigDecimal.ONE)).hasMessageContaining("实物退货");

        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of());
        FinishedGoodsAdjustmentLog scrap = FinishedGoodsAdjustmentLog.builder()
                .factoryId("F").batchId("SOURCE").adjustmentQuantity(new BigDecimal("-1"))
                .referenceType("SCRAP").build();
        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of(scrap));
        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost("F", source,
                BigDecimal.ONE)).hasMessageContaining("库存调整");

        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of());
        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost("F", source,
                new BigDecimal("2.0000"))).hasMessageContaining("证据不一致");
    }

    @Test void clearCostRejectsCrossFactoryAdjustmentEvidence() {
        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of());
        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of(
                FinishedGoodsAdjustmentLog.builder().factoryId("OTHER").batchId("SOURCE")
                        .adjustmentQuantity(BigDecimal.ZERO).build()));
        source.setShippedQuantity(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost("F", source,
                BigDecimal.ONE)).hasMessageContaining("跨工厂");
    }

    @Test void approvedLegacyDisposalWithoutAdjustmentLogStillRejectsCostClear() {
        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of());
        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of());
        Query approved = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(contains("FROM disposal_records"))).thenReturn(approved);
        when(approved.getSingleResult()).thenReturn(1L);

        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost(
                "F", source, BigDecimal.ONE))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("RECEIPT_COST_CLEAR_DOWNSTREAM_FACTS"))
                .hasMessageContaining("批准处置");
        verify(approved).setParameter("factory", "F");
        verify(approved).setParameter("batch", "SOURCE");
        verifyNoInteractions(batches, disposals);
    }

    @Test void unreadableApprovedDisposalEvidenceFailsClosedBeforeCostWrites() {
        when(items.findCompletedPhysicalReturnsForSource("F", "SOURCE", "B1"))
                .thenReturn(List.of());
        when(logs.findByBatchIdOrderByCreatedAtDesc("SOURCE")).thenReturn(List.of());
        when(em.createNativeQuery(contains("FROM disposal_records")))
                .thenThrow(new IllegalStateException("fixture: unavailable disposal evidence"));

        assertThatThrownBy(() -> service.assertCanClearAuthoritativeCost(
                "F", source, BigDecimal.ONE))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("RECEIPT_COST_CLEAR_DOWNSTREAM_FACTS"));
        verifyNoInteractions(batches, disposals);
    }
}
