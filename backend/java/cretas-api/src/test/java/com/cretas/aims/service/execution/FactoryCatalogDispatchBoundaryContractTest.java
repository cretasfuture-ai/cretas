package com.cretas.aims.service.execution;

import com.cretas.aims.ai.catalog.FactoryIntentCatalog;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.impl.factorycatalog.*;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.*;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactoryCatalogDispatchBoundaryContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PreviewTokenService tokens = mock(PreviewTokenService.class);

    private ToolDispatchService dispatch() {
        var registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        var dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "previewTokenService", tokens);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        var rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        return dispatch;
    }

    @Test
    void nonExecutableInvoicePolicyNeverAsksUserToConfirmAWrite() {
        var tool = new TaxGroupInvoiceTool();
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        var old = AIIntentConfig.builder().intentCode("TAX_GROUP_INVOICE").toolName("old_invoice")
                .sensitivityLevel("HIGH").requiresApproval(true).build();
        var intent = FactoryIntentCatalog.project(old);
        assertThat(intent.getSensitivityLevel()).isEqualTo("LOW");
        assertThat(intent.getRequiresApproval()).isFalse();
        var response = dispatch().executeWithTool(tool, "A", IntentExecuteRequest.builder()
                .userInput("按税率开票").context(Map.of("salesOrderId", "SO-1", "action", "ISSUE"))
                .build(), intent, 1L, "finance_manager", null);
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("不支持AI手工开票");
        assertThat(response.getConfirmableAction()).isNull();
        verifyNoInteractions(tokens);
    }

    @Test
    void unsupportedExplicitBatchPreviewCannotMintAConfirmToken() {
        var allocation = mock(SalesDeliveryBatchAllocationService.class);
        var repository = mock(SalesDeliveryItemRepository.class);
        var row = new SalesDeliveryItem();
        row.setProductTypeId("SKU-A"); row.setUnit("箱");
        when(repository.findByIdAndDeliveryRecordFactoryId(12L, "A")).thenReturn(Optional.of(row));
        var tool = new SalesDeliveryBatchAllocateTool(allocation, repository);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        var context = new HashMap<String,Object>();
        context.put("deliveryItemId", "12"); context.put("allocationMode", "EXPLICIT");
        context.put("allocations", List.of(Map.of("finishedGoodsBatchId", "batch-A", "allocatedQty", 5)));
        var response = dispatch().executeToolPreview(tool, "A", IntentExecuteRequest.builder()
                .userInput("指定批次").context(context).previewOnly(true).build(),
                AIIntentConfig.builder().intentCode("SALES_DELIVERY_BATCH_ALLOCATE")
                        .toolName(tool.getToolName()).build(), 1L, "sales_manager");
        assertThat(response.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(response.getConfirmableAction()).isNull();
        verifyNoInteractions(tokens, allocation);
    }
}
