package com.cretas.aims.service.impl;

import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Both legacy processing writers must lock and resolve the same tenant before any mutation. */
@ExtendWith(MockitoExtension.class)
class ProcessingEquipmentTenantContractTest {
    @Mock SchedulingResourceWriteLock resourceWriteLock;
    @Mock EquipmentRepository equipmentRepository;
    @Mock BatchEquipmentUsageRepository usageRepository;
    @InjectMocks ProcessingServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void fieldInjection() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "resourceWriteLock", resourceWriteLock);
    }

    @Test
    void anotherFactoryEquipmentCannotBeRecordedAsUsed() {
        when(equipmentRepository.findByIdAndFactoryId(17L, "OTHER")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordEquipmentUsage("OTHER", "1", 17L, LocalDate.now(), LocalDate.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        var order = inOrder(resourceWriteLock, equipmentRepository);
        order.verify(resourceWriteLock).lockFactory("OTHER");
        order.verify(equipmentRepository).findByIdAndFactoryId(17L, "OTHER");
        verify(equipmentRepository, never()).findById(17L);
        verify(equipmentRepository, never()).save(any());
        verifyNoInteractions(usageRepository);
    }

    @Test
    void anotherFactoryEquipmentCannotBeMovedOutOfMaintenance() {
        when(equipmentRepository.findByIdAndFactoryId(17L, "OTHER")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordEquipmentMaintenance("OTHER", 17L, Map.of("notes", "test")))
                .isInstanceOf(ResourceNotFoundException.class);
        var order = inOrder(resourceWriteLock, equipmentRepository);
        order.verify(resourceWriteLock).lockFactory("OTHER");
        order.verify(equipmentRepository).findByIdAndFactoryId(17L, "OTHER");
        verify(equipmentRepository, never()).findById(17L);
        verify(equipmentRepository, never()).save(any());
        verifyNoInteractions(usageRepository);
    }
}
