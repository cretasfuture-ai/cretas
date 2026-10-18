package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.ReturnOrderLineageDTO;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.service.unit.UnitContractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnOrderLineageServiceContractTest {

    private static final String FACTORY = "F006";

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private UnitContractService unitContractService;

    private ReturnOrderLineageService service() {
        ReturnOrderLineageService service = new ReturnOrderLineageService(
                returnOrderRepository, finishedGoodsBatchRepository);
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
        ReflectionTestUtils.setField(service, "unitContractService", unitContractService);
        return service;
    }

    @Test
    void stableItemIdsKeepSameSkuLinesMappedAfterCollectionReorder() {
        ReturnOrderItem first = item(11L, "SRC-A", "SRC-A-ID", "SKU-SAME", "kg", "2");
        ReturnOrderItem second = item(12L, "SRC-B", "SRC-B-ID", "SKU-SAME", "盒", "3");
        ReturnOrder order = order("RO-1", List.of(second, first));
        when(returnOrderRepository.findById("RO-1")).thenReturn(Optional.of(order));
        when(finishedGoodsBatchRepository.findById("SRC-B-ID"))
                .thenReturn(Optional.of(source("SRC-B-ID", "SRC-B", "SKU-SAME", "盒")));
        when(finishedGoodsBatchRepository.findById("SRC-A-ID"))
                .thenReturn(Optional.of(source("SRC-A-ID", "SRC-A", "SKU-SAME", "kg")));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 12L))
                .thenReturn(List.of(returned("RTN-B", "SKU-SAME", "盒", "3", 12L)));
        when(finishedGoodsBatchRepository.findByFactoryIdAndReturnOrderItemId(FACTORY, 11L))
                .thenReturn(List.of(returned("RTN-A", "SKU-SAME", "kg", "2", 11L)));

        ReturnOrderLineageDTO result = service().get(FACTORY, "RO-1");

        assertThat(result.getLines()).extracting(ReturnOrderLineageDTO.Line::getReturnOrderItemId)
                .containsExactly(12L, 11L);
        assertThat(result.getLines()).extracting(ReturnOrderLineageDTO.Line::getSourceStatus)
                .containsExactly("VERIFIED", "VERIFIED");
        assertThat(result.getLines().get(0).getReturnedStock()).extracting(
                ReturnOrderLineageDTO.ReturnedStock::getBatchNumber).containsExactly("RTN-B");
        assertThat(result.getLines().get(1).getReturnedStock()).extracting(
                ReturnOrderLineageDTO.ReturnedStock::getBatchNumber).containsExactly("RTN-A");
    }

    @Test
    void historicalMultiLineWithoutStableIdsRemainsUnknownAndDoesNotUseRtnOrder() {
        ReturnOrderItem first = item(null, "SRC-A", null, "SKU-SAME", "kg", "2");
        ReturnOrderItem second = item(null, "SRC-B", null, "SKU-SAME", "kg", "3");
        ReturnOrder order = order("RO-LEGACY", List.of(first, second));
        when(returnOrderRepository.findById("RO-LEGACY")).thenReturn(Optional.of(order));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "SRC-A"))
                .thenReturn(Optional.of(source("SRC-A-ID", "SRC-A", "SKU-SAME", "kg")));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "SRC-B"))
                .thenReturn(Optional.of(source("SRC-B-ID", "SRC-B", "SKU-SAME", "kg")));

        ReturnOrderLineageDTO result = service().get(FACTORY, "RO-LEGACY");

        assertThat(result.getLines()).extracting(ReturnOrderLineageDTO.Line::getSourceStatus)
                .containsExactly("UNKNOWN", "UNKNOWN");
        assertThat(result.getLines()).extracting(ReturnOrderLineageDTO.Line::getSourceBatchNumber)
                .containsExactly("SRC-A", "SRC-B");
        assertThat(result.getLines()).allSatisfy(line -> assertThat(line.getReturnedStock()).isEmpty());
    }

    @Test
    void foreignOrderIsRejectedBeforeReadingLineage() {
        ReturnOrder order = order("RO-FOREIGN", List.of(item(1L, "SRC", "SRC-ID", "SKU", "kg", "1")));
        order.setFactoryId("F007");
        when(returnOrderRepository.findById("RO-FOREIGN")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().get(FACTORY, "RO-FOREIGN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");
    }

    private ReturnOrder order(String id, List<ReturnOrderItem> items) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setFactoryId(FACTORY);
        order.setReturnNumber(id);
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setItems(items);
        return order;
    }

    private ReturnOrderItem item(Long id, String batchNumber, String sourceId,
                                 String sku, String unit, String quantity) {
        ReturnOrderItem item = new ReturnOrderItem();
        item.setId(id);
        item.setReturnOrderId("RO-1");
        item.setBatchNumber(batchNumber);
        item.setSourceFinishedGoodsBatchId(sourceId);
        item.setProductTypeId(sku);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    private FinishedGoodsBatch source(String id, String batchNumber, String sku, String unit) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber(batchNumber);
        batch.setProductTypeId(sku);
        batch.setUnit(unit);
        batch.setProducedQuantity(new BigDecimal("10"));
        return batch;
    }

    private FinishedGoodsBatch returned(String batchNumber, String sku, String unit,
                                        String quantity, Long returnOrderItemId) {
        FinishedGoodsBatch batch = source("RETURNED-" + batchNumber, batchNumber, sku, unit);
        batch.setReturnOrderItemId(returnOrderItemId);
        batch.setProducedQuantity(new BigDecimal(quantity));
        batch.setShippedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
        return batch;
    }
}
