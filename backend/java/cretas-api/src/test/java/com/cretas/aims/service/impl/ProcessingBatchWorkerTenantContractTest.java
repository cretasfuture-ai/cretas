package com.cretas.aims.service.impl;

import com.cretas.aims.entity.BatchWorkSession;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real service behavior: tenant validation must precede session lookup and mutation. */
@ExtendWith(MockitoExtension.class)
class ProcessingBatchWorkerTenantContractTest {
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private BatchWorkSessionRepository batchWorkSessionRepository;
    @InjectMocks private ProcessingServiceImpl service;

    @ParameterizedTest
    @ValueSource(strings = {"F007", "F008"})
    void foreignFactoryCannotCancelAnExistingF006Assignment(String requestingFactory) {
        // A foreign batch ID must be rejected before resolving any associated employee session.
        when(productionBatchRepository.findByIdAndFactoryId(101L, requestingFactory))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.cancelWorkerAssignment(requestingFactory, 101L, 202L));

        verifyNoInteractions(batchWorkSessionRepository);
    }

    @Test
    void missingBatchCannotReachSessionLookupOrSave() {
        when(productionBatchRepository.findByIdAndFactoryId(999L, "F006"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.cancelWorkerAssignment("F006", 999L, 202L));

        verifyNoInteractions(batchWorkSessionRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {BatchWorkSession.Status.ASSIGNED, BatchWorkSession.Status.WORKING})
    void ownFactoryCanCancelItsActiveAssignment(String status) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(101L);
        batch.setFactoryId("F006");
        BatchWorkSession existing = session(status);
        when(productionBatchRepository.findByIdAndFactoryId(101L, "F006"))
                .thenReturn(Optional.of(batch));
        when(batchWorkSessionRepository.findActiveByBatchIdAndEmployeeId(101L, 202L))
                .thenReturn(Optional.of(existing));

        Map<String, Object> result = service.cancelWorkerAssignment("F006", 101L, 202L);

        var order = inOrder(productionBatchRepository, batchWorkSessionRepository);
        order.verify(productionBatchRepository).findByIdAndFactoryId(101L, "F006");
        order.verify(batchWorkSessionRepository).findActiveByBatchIdAndEmployeeId(101L, 202L);
        order.verify(batchWorkSessionRepository).save(existing);
        order.verifyNoMoreInteractions();
        assertEquals(BatchWorkSession.Status.CANCELLED, existing.getStatus());
        assertNotNull(existing.getUpdatedAt());
        assertEquals(true, result.get("success"));
        assertEquals(303L, result.get("sessionId"));
        assertEquals(202L, result.get("workerId"));
    }

    @Test
    void ownFactoryWithoutActiveAssignmentDoesNotSave() {
        when(productionBatchRepository.findByIdAndFactoryId(101L, "F006"))
                .thenReturn(Optional.of(new ProductionBatch()));
        when(batchWorkSessionRepository.findActiveByBatchIdAndEmployeeId(101L, 202L))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.cancelWorkerAssignment("F006", 101L, 202L));

        verify(batchWorkSessionRepository).findActiveByBatchIdAndEmployeeId(101L, 202L);
        verifyNoMoreInteractions(batchWorkSessionRepository);
    }

    private BatchWorkSession session(String status) {
        BatchWorkSession session = new BatchWorkSession();
        session.setId(303L);
        session.setBatchId(101L);
        session.setEmployeeId(202L);
        session.setStatus(status);
        return session;
    }
}
