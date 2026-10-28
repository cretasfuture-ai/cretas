package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.pot.PotTraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * S3-16 审查 #1279: 召回影响面上的「按锅收窄」只是附加一层 —— 它出错时批次级影响面照常返回,
 * potScopes 为 null 并给出原因 (不是空列表: 空列表会被读成「没有锅受影响」)。
 */
@ExtendWith(MockitoExtension.class)
class LineageImpactPotScopeIsolationContractTest {
    @Mock BatchLineageClosureRepository closureRepository;
    @Mock BatchLineageEdgeRepository edgeRepository;
    @Mock MaterialBatchRepository materialBatchRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock CustomerRepository customerRepository;
    @Mock SourceIndividualRepository sourceIndividualRepository;
    @Mock PotTraceService potTraceService;
    @InjectMocks LineageGraphAssembler assembler;

    private void existingRoot() {
        MaterialBatch root = new MaterialBatch();
        root.setFactoryId("F006");
        when(materialBatchRepository.findById("mb-1")).thenReturn(Optional.of(root));
        ReflectionTestUtils.setField(assembler, "potTraceService", potTraceService);
    }

    @Test
    void aPotLayerFailureDoesNotFailTheBatchLevelImpact() {
        existingRoot();
        when(potTraceService.scopesFor(eq("F006"), anyCollection(), isNull()))
                .thenThrow(new IllegalStateException("pot layer broken"));
        TraceabilityDTO.ImpactResponse impact = assembler.assembleImpact("F006", "MATERIAL_BATCH", "mb-1", 0);
        assertThat(impact.getProductionBatchCount()).isZero();
        assertThat(impact.getPotScopes()).isNull();
        assertThat(impact.getPotScopesError()).contains("批次级影响面");
    }

    @Test
    void theRootIsPassedToThePotLayerAndItsScopesAreReturned() {
        existingRoot();
        when(potTraceService.scopesFor(eq("F006"), any(), isNull())).thenAnswer(inv -> {
            assertThat(inv.<java.util.Collection<String>>getArgument(1)).contains("MATERIAL_BATCH#mb-1");
            return List.of();
        });
        TraceabilityDTO.ImpactResponse impact = assembler.assembleImpact("F006", "MATERIAL_BATCH", "mb-1", 0);
        assertThat(impact.getPotScopes()).isEmpty();
        assertThat(impact.getPotScopesError()).isNull();
    }
}
