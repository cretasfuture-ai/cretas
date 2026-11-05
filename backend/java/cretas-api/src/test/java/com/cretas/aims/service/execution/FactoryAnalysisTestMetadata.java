package com.cretas.aims.service.execution;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

/** Named manufacturing records for the existing positive execution fixtures; unknown IDs stay unknown. */
final class FactoryAnalysisTestMetadata {
    private static final List<String> FACTORIES = List.of("F001", "F002", "F006", "F007", "DEMO_FACTORY2", "F_DEMO");
    private FactoryAnalysisTestMetadata() {}

    static void bindNamedFactories(IntentExecutionOrchestrator target) {
        var existing = (IntentConfigManagementService) ReflectionTestUtils.getField(target, "configService");
        if (existing == null) {
            ReflectionTestUtils.setField(target, "configService", metadata());
        } else if (mockingDetails(existing).isMock()) {
            for (String id : FACTORIES) doReturn("FACTORY").when(existing).resolveBusinessDomain(id);
        }
    }

    static void bindNamedFactories(SseStreamingService target) {
        var existing = (BusinessTypeGate) ReflectionTestUtils.getField(target, "businessTypeGate");
        if (existing == null) {
            ReflectionTestUtils.setField(target, "businessTypeGate", new BusinessTypeGate(metadata()));
        } else if (mockingDetails(existing).isMock()) {
            for (String id : FACTORIES) doReturn("FACTORY").when(existing).resolveDomain(id);
        }
    }

    private static IntentConfigManagementService metadata() {
        var factories = mock(FactoryRepository.class);
        for (String id : FACTORIES) {
            Factory factory = new Factory();
            factory.setId(id); factory.setType(FactoryType.FACTORY);
            when(factories.findById(id)).thenReturn(Optional.of(factory));
        }
        var service = new IntentConfigManagementServiceImpl(mock(AIIntentConfigRepository.class));
        service.setFactoryRepository(factories);
        return service;
    }
}
