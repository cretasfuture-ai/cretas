package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.ReturnOrderItem;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ReturnOrderItemRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnedGoodsCostResolverContractTest {

    private static final String FACTORY = "VISION-ENTRY-A";
    private static final String RETURN_NUMBER = "RT-SAL-20261016-0006";

    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ReturnOrderItemRepository returnOrderItemRepository;

    private ReturnedGoodsCostResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReturnedGoodsCostResolver(returnOrderRepository, finishedGoodsBatchRepository);
        ReflectionTestUtils.setField(resolver, "returnOrderItemRepository", returnOrderItemRepository);
    }

    @Test
    void resolvesCompletedSameFactoryReturnUsingExactItemAndSourceCost() {
        ReturnOrder order = returnOrder();
        FinishedGoodsBatch source = source("PB-9", "SKU-9", "盒", "6.4165");
        FinishedGoodsBatch returned = returned("RTN-" + RETURN_NUMBER + "-1", "SKU-9", "盒", "10");

        when(returnOrderRepository.findByFactoryIdAndReturnNumber(FACTORY, RETURN_NUMBER))
                .thenReturn(Optional.of(order));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "PB-9"))
                .thenReturn(Optional.of(source));

        Optional<ReturnedGoodsCostResolver.ResolvedCost> result = resolver.resolve(FACTORY, returned);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().unitCost()).isEqualByComparingTo("6.4165");
        assertThat(result.orElseThrow().sourceBatchId()).isEqualTo("source-PB-9");
        assertThat(result.orElseThrow().itemIndex()).isOne();
    }

    @Test
    void rejectsPendingCrossSkuWrongUnitOrUnknownSourceCostEvidence() {
        ReturnOrder pending = returnOrder();
        pending.setStatus(ReturnOrderStatus.DRAFT);
        FinishedGoodsBatch returned = returned("RTN-" + RETURN_NUMBER + "-1", "SKU-9", "盒", "10");
        when(returnOrderRepository.findByFactoryIdAndReturnNumber(FACTORY, RETURN_NUMBER))
                .thenReturn(Optional.of(pending));
        assertThat(resolver.resolve(FACTORY, returned)).isEmpty();

        ReturnOrder completed = returnOrder();
        when(returnOrderRepository.findByFactoryIdAndReturnNumber(FACTORY, RETURN_NUMBER))
                .thenReturn(Optional.of(completed));
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(eq(FACTORY), eq("PB-9")))
                .thenReturn(Optional.of(source("PB-9", "SKU-OTHER", "盒", "6.4165")));
        assertThat(resolver.resolve(FACTORY, returned)).isEmpty();

        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "PB-9"))
                .thenReturn(Optional.of(source("PB-9", "SKU-9", "kg", "6.4165")));
        assertThat(resolver.resolve(FACTORY, returned)).isEmpty();

        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "PB-9"))
                .thenReturn(Optional.of(source("PB-9", "SKU-9", "盒", null)));
        assertThat(resolver.resolve(FACTORY, returned)).isEmpty();

        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "PB-9"))
                .thenReturn(Optional.of(source("PB-9", "SKU-9", "盒", "-0.01")));
        assertThat(resolver.resolve(FACTORY, returned)).isEmpty();
    }

    @Test
    void rejectsMultiLineHistoricalReturnBecauseItemOrderIsNotStableEvidence() {
        ReturnOrder order = returnOrder();
        ReturnOrderItem second = new ReturnOrderItem();
        second.setProductTypeId("SKU-B");
        second.setBatchNumber("PB-B");
        second.setQuantity(new BigDecimal("2"));
        order.setItems(List.of(order.getItems().get(0), second));
        FinishedGoodsBatch returned = returned("RTN-" + RETURN_NUMBER + "-1", "SKU-9", "盒", "10");
        when(returnOrderRepository.findByFactoryIdAndReturnNumber(FACTORY, RETURN_NUMBER))
                .thenReturn(Optional.of(order));

        assertThat(resolver.resolve(FACTORY, returned)).isEmpty();
    }

    @Test
    void resolvesNewMultiLineReturnByStableItemIdInsteadOfRtnSuffixOrder() {
        ReturnOrder order = returnOrder();
        ReturnOrderItem second = new ReturnOrderItem();
        second.setId(12L);
        second.setReturnOrderId(order.getId());
        second.setProductTypeId("SKU-B");
        second.setBatchNumber("PB-B");
        second.setSourceFinishedGoodsBatchId("source-PB-B");
        second.setQuantity(new BigDecimal("2"));
        order.getItems().get(0).setId(11L);
        order.getItems().get(0).setReturnOrderId(order.getId());
        order.setItems(List.of(order.getItems().get(0), second));

        FinishedGoodsBatch source = source("PB-B", "SKU-B", "盒", "4.25");
        FinishedGoodsBatch returned = returned("RTN-" + RETURN_NUMBER + "-2", "SKU-B", "盒", "2");
        returned.setReturnOrderItemId(12L);
        when(returnOrderRepository.findByFactoryIdAndReturnNumber(FACTORY, RETURN_NUMBER))
                .thenReturn(Optional.of(order));
        when(returnOrderItemRepository.findById(12L)).thenReturn(Optional.of(second));
        when(finishedGoodsBatchRepository.findById("source-PB-B")).thenReturn(Optional.of(source));

        Optional<ReturnedGoodsCostResolver.ResolvedCost> result = resolver.resolve(FACTORY, returned);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().unitCost()).isEqualByComparingTo("4.25");
        assertThat(result.orElseThrow().sourceBatchNumber()).isEqualTo("PB-B");
    }

    private ReturnOrder returnOrder() {
        ReturnOrder order = new ReturnOrder();
        order.setId("return-9");
        order.setFactoryId(FACTORY);
        order.setReturnNumber(RETURN_NUMBER);
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(ReturnOrderStatus.COMPLETED);
        order.setWithGoods(true);
        ReturnOrderItem item = new ReturnOrderItem();
        item.setProductTypeId("SKU-9");
        item.setBatchNumber("PB-9");
        item.setQuantity(new BigDecimal("10"));
        order.setItems(List.of(item));
        return order;
    }

    private FinishedGoodsBatch source(String batchNumber, String sku, String unit, String unitCost) {
        FinishedGoodsBatch source = new FinishedGoodsBatch();
        source.setId("source-" + batchNumber);
        source.setFactoryId(FACTORY);
        source.setBatchNumber(batchNumber);
        source.setProductTypeId(sku);
        source.setUnit(unit);
        source.setUnitCost(unitCost == null ? null : new BigDecimal(unitCost));
        return source;
    }

    private FinishedGoodsBatch returned(String batchNumber, String sku, String unit, String produced) {
        FinishedGoodsBatch returned = new FinishedGoodsBatch();
        returned.setId("returned-9");
        returned.setFactoryId(FACTORY);
        returned.setBatchNumber(batchNumber);
        returned.setProductTypeId(sku);
        returned.setUnit(unit);
        returned.setProducedQuantity(new BigDecimal(produced));
        returned.setShippedQuantity(BigDecimal.ZERO);
        returned.setReservedQuantity(BigDecimal.ZERO);
        return returned;
    }
}
