package com.cretas.aims.service.impl;

import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.FactoryCapacityConfig;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrgentInsertGenerationContractTest {

    private static final String FACTORY = "F-GENERATE";
    private InsertSlotRepository slots;
    private ProductionLineRepository lines;
    private ImpactAnalysisService impact;
    private SchedulingResourceWriteLock factoryLock;
    private UrgentInsertServiceImpl service;

    @BeforeEach
    void setUp() {
        slots = mock(InsertSlotRepository.class);
        lines = mock(ProductionLineRepository.class);
        impact = mock(ImpactAnalysisService.class);
        factoryLock = mock(SchedulingResourceWriteLock.class);

        FactoryCapacityConfig capacity = new FactoryCapacityConfig();
        capacity.setStandardShiftWorkers(4);
        capacity.setMaxWorkers(8);
        capacity.setSwitchCostMinutes(0);
        when(impact.getFactoryCapacityConfig(FACTORY)).thenReturn(capacity);
        when(impact.checkWorkerAvailability(anyString(), any(), any(), anyInt()))
                .thenReturn(ResourceCheckResult.available("WORKER", BigDecimal.valueOf(4), BigDecimal.valueOf(4)));
        when(impact.calculateChainImpact(anyString(), any(), any(), isNull(), isNull()))
                .thenReturn(com.cretas.aims.dto.scheduling.ChainImpactResult.builder()
                        .impactLevel("none").build());
        when(impact.calculateRecommendScore(anyString(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(new ImpactAnalysisService.RecommendScoreResult());
        when(slots.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, slots,
                ProductionLineRepository.class, lines,
                ImpactAnalysisService.class, impact,
                SchedulingResourceWriteLock.class, factoryLock,
                ObjectMapper.class, new ObjectMapper()));
    }

    @Test
    void generatedSlotsUseOnlyRealActiveProductionLineIds() {
        ProductionLine line1 = line("LINE-A", "产线A");
        ProductionLine line2 = line("LINE-B", "产线B");
        when(lines.findActiveLinesByFactory(FACTORY)).thenReturn(List.of(line1, line2));

        int generated = service.generateInsertSlots(FACTORY, 4);

        assertThat(generated).isPositive();
        ArgumentCaptor<Iterable<InsertSlot>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(slots).saveAll(saved.capture());
        List<InsertSlot> savedSlots = ((List<?>) saved.getValue()).stream()
                .map(InsertSlot.class::cast)
                .toList();
        assertThat(savedSlots).allMatch(slot -> Set.of("LINE-A", "LINE-B").contains(slot.getProductionLineId())
                        && !"default".equals(slot.getProductionLineId())
                        && slot.getAvailableCapacity() == null);
    }

    @Test
    void noActiveProductionLineProducesNoPersistentSlots() {
        when(lines.findActiveLinesByFactory(FACTORY)).thenReturn(List.of());

        int generated = service.generateInsertSlots(FACTORY, 4);

        assertThat(generated).isZero();
        verify(slots, never()).saveAll(any());
    }

    private static ProductionLine line(String id, String name) {
        ProductionLine line = new ProductionLine();
        line.setId(id);
        line.setFactoryId(FACTORY);
        line.setName(name);
        return line;
    }
}
