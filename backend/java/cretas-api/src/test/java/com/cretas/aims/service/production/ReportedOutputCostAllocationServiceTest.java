package com.cretas.aims.service.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportedOutputCostAllocationServiceTest {
    private static final String F = "F006";
    private static final String P = "PLAN-1";

    @Test
    void resolvesVerifiedCostsAcrossSkusAndKeepsTotalAt100() {
        ProcessSheetRow a = row(1L, "A", "BA", "20", "40");
        ProcessSheetRow b = row(2L, "B", "BB", "30", "60");
        ProductionBatch ba = batch(1L, "BA", "A", "20");
        ProductionBatch bb = batch(2L, "BB", "B", "30");
        ReportedOutputCostAllocationService service = service(List.of(a, b), ba, bb);

        Map<String, BigDecimal> result = service.resolve(F, P, List.of(
                output("A", "BA", "40", "盒"), output("B", "BB", "60", "box")));

        assertEquals(0, new BigDecimal("40").compareTo(result.get("A")));
        assertEquals(0, new BigDecimal("60").compareTo(result.get("B")));
        assertEquals(0, new BigDecimal("100").compareTo(result.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Test
    void weightsMultipleBatchesForOneSkuByVerifiedCost() {
        ProcessSheetRow a1 = row(1L, "A", "BA1", "20", "20");
        ProcessSheetRow a2 = row(2L, "A", "BA2", "30", "30");
        ProcessSheetRow b = row(3L, "B", "BB", "50", "50");
        ReportedOutputCostAllocationService service = service(List.of(a1, a2, b),
                batch(1L, "BA1", "A", "20"), batch(2L, "BA2", "A", "30"), batch(3L, "BB", "B", "50"));
        Map<String, BigDecimal> result = service.resolve(F, P, List.of(
                output("A", "BA1", "20"), output("A", "BA2", "30"), output("B", "BB", "50")));
        assertEquals(0, new BigDecimal("50").compareTo(result.get("A")));
        assertEquals(0, new BigDecimal("50").compareTo(result.get("B")));
    }

    @Test
    void rejectsAllZeroVerifiedCosts() {
        ProcessSheetRow zero = row(1L, "A", "BA", "0", "40");
        assertThrows(BusinessException.class, () -> service(List.of(zero), batch(1L, "BA", "A", "0"))
                .resolve(F, P, List.of(output("A", "BA", "40"))));
    }

    @Test
    void rejectsTamperedCostAndDuplicateOutput() {
        ProcessSheetRow row = row(1L, "A", "BA", "20", "40");
        ProductionBatch batch = batch(1L, "BA", "A", "21");
        ReportedOutputCostAllocationService service = service(List.of(row), batch);
        assertThrows(BusinessException.class, () -> service.resolve(F, P, List.of(output("A", "BA", "40"))));

        ProductionBatch valid = batch(1L, "BA", "A", "20");
        var validService = service(List.of(row), valid);
        assertThrows(BusinessException.class, () -> validService.resolve(F, P,
                List.of(output("A", "BA", "40"), output("A", "BA", "40"))));
    }

    @Test
    void rejectsBadJsonDraftForeignRowsAndDistinctCountUnits() {
        ProcessSheetRow bad = row(1L, "A", "BA", "20", "40");
        bad.setRowPayload("null");
        assertThrows(BusinessException.class, () -> service(List.of(bad), batch(1L, "BA", "A", "20"))
                .resolve(F, P, List.of(output("A", "BA", "40"))));
        bad.setRowPayload(null);
        assertThrows(BusinessException.class, () -> service(List.of(bad), batch(1L, "BA", "A", "20"))
                .resolve(F, P, List.of(output("A", "BA", "40"))));

        ProcessSheetRow draft = row(1L, "A", "BA", "20", "40");
        draft.setSubmissionStatus(ProcessSheetRow.SUBMISSION_DRAFT);
        assertThrows(BusinessException.class, () -> service(List.of(draft), batch(1L, "BA", "A", "20"))
                .resolve(F, P, List.of(output("A", "BA", "40"))));

        ProcessSheetRow foreign = row(1L, "A", "BA", "20", "40");
        foreign.setFactoryId("OTHER");
        assertThrows(BusinessException.class, () -> service(List.of(foreign), batch(1L, "BA", "A", "20"))
                .resolve(F, P, List.of(output("A", "BA", "40"))));

        ProcessSheetRow counts = row(1L, "A", "BA", "20", "40");
        counts.setRowPayload(counts.getRowPayload().replace("\"outputUnit\":\"box\"", "\"outputUnit\":\"件\""));
        assertThrows(BusinessException.class, () -> service(List.of(counts), batch(1L, "BA", "A", "20"))
                .resolve(F, P, List.of(output("A", "BA", "40", "个"))));
    }

    @Test void rejectsCancelledCrossSkuCrossPlanAndColumnBatchMismatch() {
        for (String variant : List.of("cancelled", "sku", "plan", "factory", "row-batch")) {
            var row = row(1L, "A", "BA", "20", "40");
            var batch = batch(1L, "BA", "A", "20");
            switch (variant) {
                case "cancelled" -> batch.setStatus(com.cretas.aims.entity.enums.ProductionBatchStatus.CANCELLED);
                case "sku" -> batch.setProductTypeId("OTHER-SKU");
                case "plan" -> batch.setProductionPlanId("OTHER-PLAN");
                case "factory" -> batch.setFactoryId("OTHER-FACTORY");
                case "row-batch" -> row.setBatchNumber("OTHER-BATCH");
            }
            assertThrows(BusinessException.class, () -> service(List.of(row), batch)
                    .resolve(F, P, List.of(output("A", "BA", "40"))), variant);
        }
    }

    @Test void repositoryErrorsRemainVisible() {
        var rows = mock(ProcessSheetRowRepository.class); var batches = mock(ProductionBatchRepository.class);
        when(rows.findByFactoryIdAndPlanId(F, P)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> new ReportedOutputCostAllocationService(rows, batches, new ObjectMapper())
                .resolve(F, P, List.of(output("A", "BA", "40"))));
    }

    private static ReportedOutputCostAllocationService service(List<ProcessSheetRow> rows, ProductionBatch... batches) {
        ProcessSheetRowRepository rowRepo = mock(ProcessSheetRowRepository.class);
        ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
        when(rowRepo.findByFactoryIdAndPlanId(F, P)).thenReturn(rows);
        for (ProductionBatch batch : batches) {
            when(batchRepo.findByIdAndFactoryId(batch.getId(), F)).thenReturn(Optional.of(batch));
        }
        return new ReportedOutputCostAllocationService(rowRepo, batchRepo, new ObjectMapper());
    }

    private static ProcessSheetRow row(Long id, String sku, String batch, String cost, String qty) {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setId(id);
        row.setFactoryId(F);
        row.setPlanId(P);
        row.setBatchId(id);
        row.setBatchNumber(batch);
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setRowPayload("{\"multiOutputMember\":true,\"multiOutputBaseRowId\":\"base\",\"finished\":true,"
                + "\"productTypeId\":\"" + sku + "\","
                + "\"outputQuantity\":" + qty + ",\"outputUnit\":\"box\","
                + "\"materialNodeId\":\"node\",\"workflowPortId\":\"port\","
                + "\"allocatedCost\":" + cost + ",\"costAllocationRatio\":50,"
                + "\"costAllocationBasis\":\"EXPLICIT_RATIO\"}");
        return row;
    }

    private static ProductionBatch batch(Long id, String number, String sku, String cost) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(F);
        batch.setProductionPlanId(P);
        batch.setBatchNumber(number);
        batch.setProductTypeId(sku);
        batch.setTotalCost(new BigDecimal(cost));
        return batch;
    }

    private static ProductionSettlementRequest.OutputLine output(String sku, String batch, String qty) {
        return output(sku, batch, qty, "box");
    }

    private static ProductionSettlementRequest.OutputLine output(String sku, String batch, String qty, String unit) {
        return ProductionSettlementRequest.OutputLine.builder()
                .productTypeId(sku).batchNumber(batch).quantity(new BigDecimal(qty)).unit(unit)
                .materialNodeId("node").workflowPortId("port").build();
    }
}
