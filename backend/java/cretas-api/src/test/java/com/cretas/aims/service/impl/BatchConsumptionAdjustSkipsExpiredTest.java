package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.util.InventoryExpiry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 差异调整(+) 按 FEFO 补扣原料时, ⛔ 不扣过期批。
 *
 * <p>到期次日 00:00–02:05 之间过期批的状态还是 AVAILABLE, 查询照样返回且排在最前。
 * 判据: 过期批一点不扣; 阴性对照: 到期日 = 今天的批次照常扣满差额。</p>
 */
@DisplayName("差异调整(+): 不从过期原料批补扣")
class BatchConsumptionAdjustSkipsExpiredTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 1001L;

    @Test
    void positiveAdjustmentSkipsExpiredBatches() {
        MaterialBatchService materialBatchService = mock(MaterialBatchService.class);
        MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
        MaterialConsumptionRepository consumptionRepository = mock(MaterialConsumptionRepository.class);
        ProductionBatchRepository productionBatchRepository = mock(ProductionBatchRepository.class);
        RawMaterialTypeRepository rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
        WarehouseResolver warehouseResolver = mock(WarehouseResolver.class);
        BatchConsumptionServiceImpl service = new BatchConsumptionServiceImpl(
                mock(BomExpansionService.class), materialBatchService, materialBatchRepository,
                consumptionRepository, productionBatchRepository, rawMaterialTypeRepository,
                warehouseResolver);

        ProductionBatch batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        when(productionBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(consumptionRepository.findByProductionBatchIdAndMaterialTypeId(BATCH_ID, "MAT-PORK"))
                .thenReturn(List.of());
        when(rawMaterialTypeRepository.findById(anyString())).thenReturn(Optional.empty());
        when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn("WH-WKS");

        LocalDate today = InventoryExpiry.today();
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse(FACTORY, "MAT-PORK", "WH-WKS"))
                .thenReturn(List.of(batch("MB-EXP", today.minusDays(1)), batch("MB-TODAY", today)));

        service.adjustConsumption(FACTORY, BATCH_ID, "MAT-PORK", new BigDecimal("5"), "称重差异");

        verify(materialBatchService, never()).useBatchQuantity(eq(FACTORY), eq("MB-EXP"), any());
        // 一定会发生的读数: 差额 5 全部从未过期的那批扣 (否则「没扣过期批」可能只是什么都没扣)
        verify(materialBatchService).useBatchQuantity(FACTORY, "MB-TODAY", new BigDecimal("5"));
    }

    private static MaterialBatch batch(String id, LocalDate expire) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(new BigDecimal("2.00"));
        b.setExpireDate(expire);
        return b;
    }
}
