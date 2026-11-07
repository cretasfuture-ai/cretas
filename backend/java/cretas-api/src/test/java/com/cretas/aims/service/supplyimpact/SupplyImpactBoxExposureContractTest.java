package com.cretas.aims.service.supplyimpact;

import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.projection.SupplyImpactBoxExposureRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;

/** Box-equivalent semantics are tested without replacing the repository contract with SQL. */
class SupplyImpactBoxExposureContractTest {

    private FinishedGoodsBatchRepository repository;
    private SupplyImpactBoxExposureService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinishedGoodsBatchRepository.class);
        service = new SupplyImpactBoxExposureService(repository);
    }

    @Test
    void stockUsesPhysicalProducedMinusShippedAndKeepsReservedSeparate() {
        SupplyImpactBoxExposureRow fg = row("F1", "FG1", "B-1", "100", "20", "10",
                "盒", null, null, null, null, "箱", "盒", "8", null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        when(repository.findSupplyImpactBoxExposureRows(eq("F1"), anyCollection(), anyCollection()))
                .thenReturn(List.of(fg));

        var exposure = service.evaluate("F1", List.of("FG1"), List.of(), true);

        assertThat(exposure.getStock().isComplete()).isTrue();
        assertThat(exposure.getStock().getTotalBoxQuantity()).isEqualTo("10");
        assertThat(exposure.getStock().getKnownBoxSubtotal()).isEqualTo("10");
        assertThat(exposure.getStockSources()).singleElement().satisfies(source -> {
            assertThat(source.getSourceQuantity()).isEqualTo("80");
            assertThat(source.getReservedQuantity()).isEqualTo("10");
            assertThat(source.getBoxQuantity()).isEqualTo("10");
        });
        assertThat(exposure.getGaps()).extracting(g -> g.getCode())
                .contains("SHIPMENT_SCOPE_EMPTY");
    }

    @Test
    void shipmentUsesEachQualifiedEdgeOnceAndPreservesDifferentPackagingSnapshots() {
        SupplyImpactBoxExposureRow fg1 = row("F1", "FG1", "B-1", "100", "2", "0",
                "盒", null, null, null, null, "箱", "盒", "8", "E1", "D1", "2", "盒",
                "DR1", "11", "2", "盒", "箱", "盒", "8", "C1", "客户甲");
        SupplyImpactBoxExposureRow duplicate = fg1;
        SupplyImpactBoxExposureRow fg2 = row("F1", "FG2", "B-2", "20", "5", "0",
                "盒", null, null, null, null, "箱", "盒", "5", "E2", "D1", "5", "盒",
                "DR1", "12", "5", "盒", "箱", "盒", "10", "C1", "客户甲");
        when(repository.findSupplyImpactBoxExposureRows(eq("F1"), anyCollection(), anyCollection()))
                .thenReturn(List.of(fg1, duplicate, fg2));

        var exposure = service.evaluate("F1", List.of("FG1", "FG2"), List.of("D1"), true);

        assertThat(exposure.getShipmentSources()).hasSize(2);
        assertThat(exposure.getShipmentSources()).extracting(s -> s.getBoxQuantity())
                .containsExactly("0.25", "0.5");
        assertThat(exposure.getShipped().isComplete()).isTrue();
        assertThat(exposure.getShipped().getTotalBoxQuantity()).isEqualTo("0.75");
        assertThat(exposure.getCustomers()).singleElement().satisfies(customer -> {
            assertThat(customer.getCustomerId()).isEqualTo("C1");
            assertThat(customer.getTotals().getTotalBoxQuantity()).isEqualTo("0.75");
        });
    }

    @Test
    void missingSnapshotAndWrongFactoryNeverBecomeZero() {
        SupplyImpactBoxExposureRow foreign = row("F2", "FG1", "foreign", "100", "0", "0",
                "盒", null, null, null, null, "箱", "盒", "8", null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        when(repository.findSupplyImpactBoxExposureRows(eq("F1"), anyCollection(), anyCollection()))
                .thenReturn(List.of(foreign));

        var exposure = service.evaluate("F1", List.of("FG1"), List.of("D404"), true);

        assertThat(exposure.getStock().isComplete()).isFalse();
        assertThat(exposure.getStock().getTotalBoxQuantity()).isNull();
        assertThat(exposure.getStock().getKnownBoxSubtotal()).isNull();
        assertThat(exposure.getStock().getUnknownSourceCount()).isEqualTo(1);
        assertThat(exposure.getStockSources()).singleElement().satisfies(source ->
                assertThat(source.getBoxQuantity()).isNull());
        assertThat(exposure.getGaps()).extracting(g -> g.getCode())
                .contains("FINISHED_BATCH_FACT_MISSING", "SHIPMENT_EDGE_FACT_MISSING");
    }

    @Test
    void nativeDeductionWithUnknownMassConversionCannotClaimCompleteCoverage() {
        SupplyImpactBoxExposureRow row = row("F1", "FG1", "B-1", "100", "20", "0",
                "盒", null, null, null, null, "箱", "盒", "8", "E1", "D1", "20", "kg",
                "DR1", "11", "20", "kg", "箱", "盒", "8", "C1", "客户甲");
        when(repository.findSupplyImpactBoxExposureRows(eq("F1"), anyCollection(), anyCollection()))
                .thenReturn(List.of(row));

        var exposure = service.evaluate("F1", List.of("FG1"), List.of("D1"), true);

        assertThat(exposure.getShipped().isComplete()).isFalse();
        assertThat(exposure.getShipped().getTotalBoxQuantity()).isNull();
        assertThat(exposure.getShipped().getKnownBoxSubtotal()).isNull();
        assertThat(exposure.getGaps()).extracting(g -> g.getCode())
                .contains("SHIPMENT_DEDUCTION_INCOMPLETE");
        assertThat(exposure.getShipmentSources()).singleElement().satisfies(source ->
                assertThat(source.getGaps()).contains("BOX_UNIT_CONVERSION_UNKNOWN"));
    }

    @Test
    void negativePhysicalAndPackagingFactsAreNullAndPropagatedAsDeduplicatedGaps() {
        SupplyImpactBoxExposureRow row = row("F1", "FG1", "B-1", "100", "-5", "-2",
                "盒", null, null, null, null, "箱", "盒", "-8", null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        when(repository.findSupplyImpactBoxExposureRows(eq("F1"), anyCollection(), anyCollection()))
                .thenReturn(List.of(row));

        var exposure = service.evaluate("F1", List.of("FG1"), List.of(), true);

        assertThat(exposure.getStock().isComplete()).isFalse();
        assertThat(exposure.getStock().getTotalBoxQuantity()).isNull();
        assertThat(exposure.getStockSources()).singleElement().satisfies(source -> {
            assertThat(source.getSourceQuantity()).isNull();
            assertThat(source.getReservedQuantity()).isNull();
            assertThat(source.getPackagingFactor()).isNull();
            assertThat(source.getGaps()).containsExactlyInAnyOrder(
                    "PHYSICAL_QUANTITY_UNKNOWN", "RESERVED_QUANTITY_UNKNOWN",
                    "BOX_PACKAGING_SNAPSHOT_UNKNOWN");
        });
        assertThat(exposure.getGaps()).extracting(g -> g.getCode())
                .contains("PHYSICAL_QUANTITY_UNKNOWN", "RESERVED_QUANTITY_UNKNOWN",
                        "BOX_PACKAGING_SNAPSHOT_UNKNOWN");
    }

    private static SupplyImpactBoxExposureRow row(String factory, String fgId, String batch,
                                                   String produced, String shipped, String reserved,
                                                   String finishedUnit, String ignored1, String ignored2,
                                                   String ignored3, String ignored4,
                                                   String fgPack, String fgBase, String fgFactor,
                                                   String edgeId, String shipmentId, String edgeQty, String edgeUnit,
                                                   String deliveryRecord, String deliveryItem, String deliveredQty,
                                                   String deliveryUnit, String deliveryPack, String deliveryBase,
                                                   String deliveryFactor, String customerId, String customerName) {
        SupplyImpactBoxExposureRow row = mock(SupplyImpactBoxExposureRow.class);
        when(row.getFactoryId()).thenReturn(factory);
        when(row.getFinishedGoodsBatchId()).thenReturn(fgId);
        when(row.getBatchNumber()).thenReturn(batch);
        when(row.getProducedQuantity()).thenReturn(decimal(produced));
        when(row.getShippedQuantity()).thenReturn(decimal(shipped));
        when(row.getReservedQuantity()).thenReturn(decimal(reserved));
        when(row.getFinishedUnit()).thenReturn(finishedUnit);
        when(row.getFinishedPackagingUnit()).thenReturn(fgPack);
        when(row.getFinishedPackagingBaseUnit()).thenReturn(fgBase);
        when(row.getFinishedPackagingFactor()).thenReturn(decimal(fgFactor));
        when(row.getEdgeId()).thenReturn(edgeId);
        when(row.getShipmentId()).thenReturn(shipmentId);
        when(row.getEdgeQuantity()).thenReturn(decimal(edgeQty));
        when(row.getEdgeUnit()).thenReturn(edgeUnit);
        when(row.getDeliveryRecordId()).thenReturn(deliveryRecord);
        when(row.getDeliveryItemId()).thenReturn(deliveryItem);
        when(row.getDeliveredQuantity()).thenReturn(decimal(deliveredQty));
        when(row.getDeliveryUnit()).thenReturn(deliveryUnit);
        when(row.getDeliveryPackagingUnit()).thenReturn(deliveryPack);
        when(row.getDeliveryPackagingBaseUnit()).thenReturn(deliveryBase);
        when(row.getDeliveryPackagingFactor()).thenReturn(decimal(deliveryFactor));
        when(row.getCustomerId()).thenReturn(customerId);
        when(row.getCustomerName()).thenReturn(customerName);
        return row;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
