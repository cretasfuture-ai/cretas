package com.cretas.aims.service.finance;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.repository.inventory.*;
import com.cretas.aims.service.unit.UnitContractService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
        b.setUnit("盒");b.setUnitCost(new BigDecimal(cost));b.setProducedQuantity(BigDecimal.ONE);b.setShippedQuantity(BigDecimal.ZERO);return b;
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
}
