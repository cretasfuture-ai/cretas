package com.cretas.aims.service.finance;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.repository.VoucherRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ClosingReadinessServiceTest {
    @Test void draftsBlockBeforeAdjustmentQuery() {
        var repo=mock(VoucherRepository.class);var em=mock(EntityManager.class);
        when(repo.countByFactoryIdAndStatusAndVoucherDateBetweenAndDeletedAtIsNull(anyString(),eq(VoucherStatus.DRAFT),any(),any())).thenReturn(2L);
        assertThatThrownBy(()->new ClosingReadinessService(repo,em).assertReady("F",2026,10)).hasMessageContaining("2张待过账");
        verifyNoInteractions(em);
    }
    @Test void pendingAdjustmentsBlockAndQueryIsFactoryPeriodScoped() {
        var repo=mock(VoucherRepository.class);var em=mock(EntityManager.class);var q=mock(Query.class,RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(q);when(q.getSingleResult()).thenReturn(1L);
        assertThatThrownBy(()->new ClosingReadinessService(repo,em).assertReady("F",2026,10)).hasMessageContaining("调整待审核");
        verify(q).setParameter("factory","F");verify(q).setParameter("start",java.time.LocalDate.of(2026,10,1));
        verify(q).setParameter("end",java.time.LocalDate.of(2026,10,31));
    }
    @Test void emptyPeriodCanLock() {
        var repo=mock(VoucherRepository.class);var em=mock(EntityManager.class);var q=mock(Query.class,RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(q);when(q.getSingleResult()).thenReturn(0L);
        assertThatCode(()->new ClosingReadinessService(repo,em).assertReady("F",2026,10)).doesNotThrowAnyException();
    }
}
