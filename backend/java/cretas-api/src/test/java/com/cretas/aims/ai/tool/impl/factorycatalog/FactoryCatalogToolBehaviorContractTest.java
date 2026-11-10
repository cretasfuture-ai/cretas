package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.dto.factory.StocktakeDiffPreviewDTO;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.RawMaterialTypeDTO;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.RawMaterialTypeService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import com.cretas.aims.service.intent.catalog.FactoryInvoiceTaxQueryService;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FactoryCatalogToolBehaviorContractTest {

    @Test
    void freeGift_createsZeroCostSourceLineIdempotentBatch() {
        MaterialBatchService batches = mock(MaterialBatchService.class);
        RawMaterialTypeService materials = mock(RawMaterialTypeService.class);
        SupplierService suppliers = mock(SupplierService.class);
        RawMaterialTypeDTO material = new RawMaterialTypeDTO();
        material.setUnit("kg");
        when(materials.getMaterialTypeById("F1", "MAT-1")).thenReturn(material);
        MaterialBatchDTO dto = new MaterialBatchDTO();
        dto.setId("B-1");
        dto.setBatchNumber("MB-1");
        dto.setReceiptQuantity(new BigDecimal("5"));
        dto.setQuantityUnit("箱");
        when(batches.createMaterialBatch(eq("F1"), any(CreateMaterialBatchRequest.class), eq(9L)))
                .thenReturn(dto);

        Map<String, Object> params = Map.of("materialTypeId", "MAT-1", "supplierId", "SUP-1",
                "quantity", 5, "totalWeight", 50, "unit", "箱", "sourceDocId", "DOC-1",
                "sourceLineNo", "L1", "notes", "供应商赠送");
        Map<String, Object> result = new TestGiftTool(batches, materials, suppliers)
                .invoke("F1", params, Map.of("userId", 9L));

        assertThat(result.get("totalValue")).isEqualTo(BigDecimal.ZERO);
        var captor = org.mockito.ArgumentCaptor.forClass(CreateMaterialBatchRequest.class);
        verify(batches).createMaterialBatch(eq("F1"), captor.capture(), eq(9L));
        assertThat(captor.getValue().getSourceDocType()).isEqualTo("FREE_GIFT");
        assertThat(captor.getValue().getSourceLineNo()).isEqualTo("L1");
        assertThat(captor.getValue().getSourceEventKey()).isEqualTo("DOC-1#line:L1");
        assertThat(captor.getValue().getTotalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getTotalWeight()).isEqualByComparingTo("50");
        assertThat(captor.getValue().getQuantityUnit()).isEqualTo("箱");
    }

    @Test
    void rankingToolUsesDateRangeAndRealSalesOrderSource() {
        ProductSalesRankingService ranking = mock(ProductSalesRankingService.class);
        SalesOrderItemRepository.SalesRankingRow row = mock(SalesOrderItemRepository.SalesRankingRow.class);
        when(row.getProductTypeId()).thenReturn("SKU-1");
        when(row.getProductName()).thenReturn("产品一");
        when(row.getQuantity()).thenReturn(new BigDecimal("12"));
        when(row.getUnit()).thenReturn("kg");
        when(ranking.rank(eq("F1"), eq(java.time.LocalDate.of(2026, 1, 1)),
                eq(java.time.LocalDate.of(2026, 1, 31)))).thenReturn(List.of(row));

        Map<String, Object> result = new TestRankingTool(ranking).invoke("F1", Map.of(
                "startDate", "2026-01-01", "endDate", "2026-01-31"), Map.of());
        assertThat(result.get("source")).isEqualTo("SALES_ORDER_ITEMS");
        assertThat(((List<?>) result.get("ranking")).get(0).toString()).contains("SKU-1");
        verify(ranking).rank("F1", java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(2026, 1, 31));
    }

    @Test
    void inventoryGain_appliesOnlyApprovedPositiveStocktake() {
        FactoryStocktakeService service = mock(FactoryStocktakeService.class);
        StocktakeDTO detail = new StocktakeDTO();
        detail.setId("ST-1");
        detail.setStatus("APPROVED");
        StocktakeDTO.StocktakeItemDTO item = new StocktakeDTO.StocktakeItemDTO();
        item.setId("ITEM-1");
        item.setSystemQty(new BigDecimal("10"));
        item.setActualQty(new BigDecimal("12"));
        detail.setItems(List.of(item));
        StocktakeDiffPreviewDTO diff = new StocktakeDiffPreviewDTO();
        StocktakeDiffPreviewDTO.DiffLine line = new StocktakeDiffPreviewDTO.DiffLine();
        line.setItemId("ITEM-1");
        line.setSystemQty(new BigDecimal("10"));
        line.setActualQty(new BigDecimal("12"));
        line.setDifferenceType("SURPLUS");
        diff.setDiffLines(List.of(line));
        diff.setSurplusCount(1);
        diff.setShortageCount(0);
        when(service.getDetail("ST-1", "F1")).thenReturn(detail);
        when(service.previewDiff("ST-1", "F1")).thenReturn(diff);

        new TestGainTool(service).invoke("F1", Map.of("stocktakeId", "ST-1"), Map.of("userId", 9L));

        verify(service).apply("ST-1", "F1", 9L);
        verify(service, never()).submitForApproval(anyString(), anyString(), anyLong());
    }

    @Test
    void allocation_rejectsModelProductOrQuantityDifferentFromDeliveryLine() {
        SalesDeliveryBatchAllocationService allocation = mock(SalesDeliveryBatchAllocationService.class);
        SalesDeliveryItemRepository repository = mock(SalesDeliveryItemRepository.class);
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setProductTypeId("PRODUCT-A");
        item.setDeliveredQuantity(new BigDecimal("5"));
        when(repository.findByIdAndDeliveryRecordFactoryId(12L, "F1")).thenReturn(Optional.of(item));

        TestAllocationTool tool = new TestAllocationTool(allocation, repository);
        assertThatThrownBy(() -> tool.invoke("F1", Map.of(
                "deliveryItemId", "12", "allocationMode", "AUTO_FIFO",
                "productTypeId", "PRODUCT-B", "requiredQty", 5), Map.of()))
                .hasMessageContaining("不一致");
        verify(allocation, never()).autoAllocateFifo(anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    /** #1548 review finding 4: 模型不能代人确认包装规格换算 (否则留痕会记成登录用户确认的)。 */
    @Test
    void explicitAllocationRejectsModelSuppliedPackagingConfirmation() {
        SalesDeliveryBatchAllocationService allocation = mock(SalesDeliveryBatchAllocationService.class);
        TestAllocationTool tool = explicitAllocationTool(allocation);
        Map<String, Object> confirmed = new java.util.LinkedHashMap<>(Map.of(
                "finishedGoodsBatchId", "FG-1", "allocatedQty", 5, "packagingConversionConfirmed", true));

        assertThatThrownBy(() -> tool.invoke("F1", Map.of("deliveryItemId", "12", "allocationMode", "EXPLICIT",
                "allocations", List.of(confirmed)), Map.of("userId", 7L)))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("AI 不能代为确认");
                    assertThat(e.getActionHint()).contains("分配批次");
                });
        verifyNoInteractions(allocation);
    }

    @Test
    void explicitAllocationWithoutConfirmationStillAllocates() {
        for (Map<String, Object> row : List.<Map<String, Object>>of(
                Map.of("finishedGoodsBatchId", "FG-1", "allocatedQty", 5, "packagingConversionConfirmed", false),
                Map.of("finishedGoodsBatchId", "FG-1", "allocatedQty", 5))) {
            SalesDeliveryBatchAllocationService allocation = mock(SalesDeliveryBatchAllocationService.class);
            TestAllocationTool tool = explicitAllocationTool(allocation);

            var result = tool.invoke("F1", Map.of("deliveryItemId", "12", "allocationMode", "EXPLICIT",
                    "allocations", List.of(row)), Map.of("userId", 7L));

            assertThat(result).containsEntry("status", "ALLOCATED");
            verify(allocation).allocateBatchesAsUser(eq("F1"), eq("12"), anyList(), eq(7L));
        }
    }

    private static TestAllocationTool explicitAllocationTool(SalesDeliveryBatchAllocationService allocation) {
        SalesDeliveryItemRepository repository = mock(SalesDeliveryItemRepository.class);
        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setProductTypeId("PRODUCT-A");
        item.setDeliveredQuantity(new BigDecimal("5"));
        item.setUnit("kg");
        when(repository.findByIdAndDeliveryRecordFactoryId(12L, "F1")).thenReturn(Optional.of(item));
        TestAllocationTool tool = new TestAllocationTool(allocation, repository);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        return tool;
    }

    @Test
    void allocationKeepsDistinctInventoryCountUnitsWithTheRealUnitContract() {
        for (String[] pair : java.util.List.of(new String[]{"只", "件"}, new String[]{"个", "件"}, new String[]{"只", "个"})) {
            var allocation = mock(SalesDeliveryBatchAllocationService.class);
            var repository = mock(SalesDeliveryItemRepository.class);
            var item = new SalesDeliveryItem();
            item.setProductTypeId("PRODUCT-A"); item.setDeliveredQuantity(new BigDecimal("5"));
            item.setUnit(pair[0]);
            when(repository.findByIdAndDeliveryRecordFactoryId(12L, "F1")).thenReturn(Optional.of(item));
            var tool = new TestAllocationTool(allocation, repository);
            ReflectionTestUtils.setField(tool, "unitContractService",
                    com.cretas.aims.service.unit.TestUnitContractFactory.contract());
            assertThatThrownBy(() -> tool.invoke("F1", Map.of("deliveryItemId", "12", "allocationMode", "AUTO_FIFO",
                    "productTypeId", "PRODUCT-A", "requiredQty", 5, "unit", pair[1]), Map.of()))
                    .hasMessageContaining("单位或来源仓库与发货明细不一致");
            verifyNoInteractions(allocation);
        }
    }

    @Test
    void allocationAcceptsMassAliasButPassesTheTrustedDeliveryUnit() {
        var allocation = mock(SalesDeliveryBatchAllocationService.class);
        var repository = mock(SalesDeliveryItemRepository.class);
        var item = new SalesDeliveryItem();
        item.setProductTypeId("PRODUCT-A"); item.setDeliveredQuantity(new BigDecimal("5"));
        item.setUnit("kg"); item.setSourceWarehouseCode("WH-A");
        when(repository.findByIdAndDeliveryRecordFactoryId(12L, "F1")).thenReturn(Optional.of(item));
        when(allocation.autoAllocateFifo(eq("F1"), eq("12"), eq("PRODUCT-A"), any(), eq("kg"), eq("WH-A")))
                .thenReturn(true);
        var tool = new TestAllocationTool(allocation, repository);
        ReflectionTestUtils.setField(tool, "unitContractService",
                com.cretas.aims.service.unit.TestUnitContractFactory.contract());
        var result = tool.invoke("F1", Map.of("deliveryItemId", "12", "allocationMode", "AUTO_FIFO",
                "productTypeId", "PRODUCT-A", "requiredQty", 5, "unit", "公斤"), Map.of());
        assertThat(result).containsEntry("status", "ALLOCATED");
        verify(allocation).autoAllocateFifo(eq("F1"), eq("12"), eq("PRODUCT-A"), any(), eq("kg"), eq("WH-A"));
    }

    @Test
    void taxToolExecute_readsAutomaticInvoiceStatus() throws Exception {
        TaxGroupInvoiceTool tool = new TaxGroupInvoiceTool();
        tool.wire(new ObjectMapper());
        FactoryInvoiceTaxQueryService query = mock(FactoryInvoiceTaxQueryService.class);
        when(query.query("F1", "SO-1")).thenReturn(Map.of(
                "status", "PENDING", "salesOrderId", "SO-1", "generated", false));
        ReflectionTestUtils.setField(tool, "queryService", query);
        String response = tool.execute(ToolCall.of("1", tool.getToolName(),
                "{\"salesOrderId\":\"SO-1\"}"), Map.of("factoryId", "F1", "userId", 9L));
        assertThat(response).contains("\"success\":true")
                .contains("\"status\":\"PENDING\"")
                .contains("\"generated\":false");
        verify(query).query("F1", "SO-1");
    }

    @Test
    void traceToolReadsTheSharedFixedPolicyWithoutGeneratingCode() {
        Map<String, Object> result = new TestTraceTool().invoke("F1", Map.of(), Map.of());
        assertThat(result).containsEntry("configured", true)
                .containsEntry("template", "TRACE-<factoryId>-<yyyyMMdd>-<6位>")
                .containsEntry("source", "SYSTEM_TRACE_CODE_POLICY");
    }

    private static final class TestGainTool extends InventoryGainInboundTool {
        TestGainTool(FactoryStocktakeService service) { super(service); }
        Map<String, Object> invoke(String factoryId, Map<String, Object> params,
                                   Map<String, Object> context) { return doExecute(factoryId, params, context); }
    }

    private static final class TestAllocationTool extends SalesDeliveryBatchAllocateTool {
        TestAllocationTool(SalesDeliveryBatchAllocationService allocation,
                           SalesDeliveryItemRepository repository) { super(allocation, repository); }
        Map<String, Object> invoke(String factoryId, Map<String, Object> params,
                                   Map<String, Object> context) { return doExecute(factoryId, params, context); }
    }

    private static final class TestGiftTool extends FreeGiftInboundTool {
        TestGiftTool(MaterialBatchService batches, RawMaterialTypeService materials,
                     SupplierService suppliers) { super(batches, materials, suppliers); }
        Map<String, Object> invoke(String factoryId, Map<String, Object> params,
                                   Map<String, Object> context) { return doExecute(factoryId, params, context); }
    }

    private static final class TestRankingTool extends ProductSalesRankingTool {
        TestRankingTool(ProductSalesRankingService ranking) { super(ranking); }
        Map<String, Object> invoke(String factoryId, Map<String, Object> params,
                                   Map<String, Object> context) { return doExecute(factoryId, params, context); }
    }

    private static final class TestTraceTool extends TraceCodeFormatTool {
        Map<String, Object> invoke(String factoryId, Map<String, Object> params,
                                   Map<String, Object> context) { return doExecute(factoryId, params, context); }
    }

    private static final class TaxGroupInvoiceTool extends com.cretas.aims.ai.tool.impl.factorycatalog.TaxGroupInvoiceTool {
        void wire(ObjectMapper mapper) { this.objectMapper = mapper; }
    }
}
