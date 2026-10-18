package com.cretas.aims.service.production;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FormalFinishedOutputScopeContractTest {
    @Test void emptySelectionDoesNotReadOtherFactoryData() {
        assertThat(new FormalFinishedOutputScope().visibleBatchNumbers("F", List.of())).isEmpty();
        assertThatThrownBy(() -> new FormalFinishedOutputScope().visibleBatchNumbers("", List.of("A")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void queriesAreBoundAndUseExactFactoryPlanBatchAndSkuIdentity() {
        var entityManager = mock(EntityManager.class); var query = mock(Query.class);
        var service = new FormalFinishedOutputScope();
        ReflectionTestUtils.setField(service,"entityManager",entityManager);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            assertThat(sql).contains("b.factory_id=:factory", "b.batch_number IN (:numbers)",
                    "fs.factory_id=b.factory_id", "fs.production_plan_id=b.production_plan_id",
                    "fo.factory_id=fs.factory_id", "fo.production_plan_id=fs.production_plan_id", "fo.product_type_id=b.product_type_id",
                    "fo.reported_batch_number=b.batch_number", "NOT EXISTS");
            return query;
        });
        when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("REAL-A","REAL-B"));
        assertThat(service.visibleBatchNumbers("F",List.of("REAL-A","REAL-B","OWNER")))
                .containsExactlyInAnyOrder("REAL-A","REAL-B");
        verify(query).setParameter("factory","F");
        verify(query).setParameter("numbers",List.of("REAL-A","REAL-B","OWNER"));
        when(query.getResultList()).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.visibleBatchNumbers("F",List.of("REAL-A")))
                .isInstanceOf(IllegalStateException.class);
    }
}
