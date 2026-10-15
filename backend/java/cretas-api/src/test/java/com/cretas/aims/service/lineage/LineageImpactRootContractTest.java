package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LineageImpactRootContractTest {
    @Mock BatchLineageClosureRepository closureRepository;
    @Mock BatchLineageEdgeRepository edgeRepository;
    @Mock MaterialBatchRepository materialBatchRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock CustomerRepository customerRepository;
    @Mock SourceIndividualRepository sourceIndividualRepository;
    @InjectMocks LineageGraphAssembler assembler;

    @Test
    void unknownRootCannotBeReportedAsZeroImpact() {
        assertThatThrownBy(() -> assembler.assembleImpact("F006", "MATERIAL_BATCH", "missing", 5))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未找到");
    }

    @Test
    void aRootFromAnotherFactoryCannotBeReportedAsZeroImpact() {
        MaterialBatch foreign = new MaterialBatch();
        foreign.setFactoryId("F_OTHER");
        when(materialBatchRepository.findById("foreign")).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> assembler.assembleImpact("F006", "MATERIAL_BATCH", "foreign", 5))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未找到");
    }

    @Test
    void existingMaterialWithoutRecordedDescendantsHasZeroRecordedImpact() {
        MaterialBatch root = new MaterialBatch();
        root.setFactoryId("F006");
        when(materialBatchRepository.findById("mb-1")).thenReturn(Optional.of(root));
        var impact = assembler.assembleImpact("F006", "MATERIAL_BATCH", "mb-1", 5);
        assertThat(impact.getProductionBatchCount()).isZero();
        assertThat(impact.getFinishedBatchCount()).isZero();
        assertThat(impact.getShipmentCount()).isZero();
    }

    @Test
    void existingProductionFinishedAndShipmentRootsUseTheirOwnEntityTypes() {
        ProductionBatch production = new ProductionBatch();
        production.setFactoryId("F006");
        FinishedGoodsBatch finished = new FinishedGoodsBatch();
        finished.setFactoryId("F006");
        SalesDeliveryRecord shipment = new SalesDeliveryRecord();
        shipment.setFactoryId("F006");
        when(productionBatchRepository.findById(17L)).thenReturn(Optional.of(production));
        when(finishedGoodsBatchRepository.findById("fg-1")).thenReturn(Optional.of(finished));
        when(salesDeliveryRecordRepository.findById("sd-1")).thenReturn(Optional.of(shipment));
        assertThat(assembler.assembleImpact("F006", "PRODUCTION_BATCH", "17", 5).getShipmentCount()).isZero();
        assertThat(assembler.assembleImpact("F006", "FINISHED_BATCH", "fg-1", 5).getShipmentCount()).isZero();
        assertThat(assembler.assembleImpact("F006", "SHIPMENT_RECORD", "sd-1", 5).getShipmentCount()).isZero();
    }

    @Test
    void malformedProductionIdIsRejectedInsteadOfBecomingAnEmptyGraph() {
        assertThatThrownBy(() -> assembler.assembleImpact("F006", "PRODUCTION_BATCH", "not-a-number", 5))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ID");
        verifyNoInteractions(productionBatchRepository);
    }
}
