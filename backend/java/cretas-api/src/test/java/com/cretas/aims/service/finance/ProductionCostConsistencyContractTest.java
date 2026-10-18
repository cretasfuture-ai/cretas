package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import java.sql.Date;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProductionCostConsistencyContractTest {
    @Test void shipmentIsRejectedOutsideOpenAccountingPeriod() {
        var em=mock(EntityManager.class);var periods=mock(AccountingPeriodService.class);
        when(periods.getStatus("F",2026,10)).thenReturn(AccountingPeriod.Status.CLOSED);
        assertThatThrownBy(()->new ProductionCostConsistency(em,periods).assertShipmentPeriodOpen("F",java.time.LocalDate.of(2026,10,17)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("期间未开放");
    }
    @Test void blocksAnyAffectedClosedPeriodUnderTheFactoryLock() {
        EntityManager em=mock(EntityManager.class); Query lock=mock(Query.class, RETURNS_SELF), dates=mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(startsWith("SELECT pg_advisory"))).thenReturn(lock);
        when(em.createNativeQuery(contains("SELECT DISTINCT d"))).thenReturn(dates);
        when(dates.getResultList()).thenReturn(List.of(Date.valueOf("2026-09-30"), Date.valueOf("2026-10-17")));
        AccountingPeriodService periods=mock(AccountingPeriodService.class);
        when(periods.getStatus("NEW-FACTORY",2026,9)).thenReturn(AccountingPeriod.Status.OPEN);
        when(periods.getStatus("NEW-FACTORY",2026,10)).thenReturn(AccountingPeriod.Status.CLOSED);
        var guard=new ProductionCostConsistency(em,periods);
        assertThatThrownBy(()->guard.assertChangeAllowed("NEW-FACTORY","PLAN-17"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2026-10");
        var order=inOrder(lock,dates,periods);
        order.verify(lock).setParameter("key","production-cost-finance:NEW-FACTORY");
        order.verify(lock).getSingleResult(); order.verify(dates).setParameter("factory","NEW-FACTORY");
        verify(dates).setParameter("plan","PLAN-17");
    }

    @Test void allowsOpenPeriodsAndInvalidatesOnlyCurrentCostCachesWithoutDeletingVersions() {
        EntityManager em=mock(EntityManager.class); Query query=mock(Query.class,RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(0L);
        when(query.getResultList()).thenReturn(List.of(Date.valueOf("2026-10-16")));
        AccountingPeriodService periods=mock(AccountingPeriodService.class);
        when(periods.getStatus("NEW-FACTORY",2026,10)).thenReturn(AccountingPeriod.Status.OPEN);
        var guard=new ProductionCostConsistency(em,periods);
        guard.assertChangeAllowed("NEW-FACTORY","PLAN-17"); guard.invalidateCostIndicators("NEW-FACTORY");
        verify(em).createNativeQuery(contains("UPDATE indicators SET last_computed_at=NULL"));
        verify(em,never()).createNativeQuery(contains("DELETE"));
        verify(query,times(2)).setParameter("factory","NEW-FACTORY");
        verify(query).executeUpdate();
    }

    @Test void multiBatchShipmentsUseTheSameOpenPeriodGuard() {
        EntityManager em=mock(EntityManager.class);Query query=mock(Query.class,RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(Date.valueOf("2026-10-17")));
        AccountingPeriodService periods=mock(AccountingPeriodService.class);
        when(periods.getStatus("NEW-FACTORY",2026,10)).thenReturn(AccountingPeriod.Status.OPEN);
        new ProductionCostConsistency(em,periods).assertChangeAllowed("NEW-FACTORY","PLAN-17");
        verify(periods).getStatus("NEW-FACTORY",2026,10);
        verify(em,never()).createNativeQuery(contains("SELECT COUNT(*)"));
    }
}
