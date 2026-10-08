package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.*;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchUseTool;
import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.mapper.MaterialBatchMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.cretas.aims.service.execution.SseFactoryFollowUpContractTest.codes;

class ConfirmedWriteFollowUpContractTest {
    static final String DIGEST="b".repeat(64);

    IntentExecuteResponse confirm(SseFactoryFollowUpContractTest.Harness h,String code,Map<String,Object> params,
                                  Object suggestions,boolean resolve) {
        var dispatch=(ToolDispatchService)ReflectionTestUtils.getField(h.sse,"toolDispatchService");
        var orchestrator=new IntentExecutionOrchestrator(h.intents,null,null,null,null,null,h.mapper,
            null,null,null,null,h.registry,null,null,
            mock(AgentOrchestrator.class),mock(AgenticRAGRouterService.class),mock(ResultValidatorService.class),
            dispatch,mock(DynamicToolSelectionService.class),mock(QueryPreprocessorService.class));
        var tokens=mock(PreviewTokenService.class);
        var intent=h.configs.get(code);
        var token=IntentPreviewToken.builder().token("local-proof").factoryId("F006").tenantId("F006").userId(7L)
            .intentCode(code).toolName(intent.getToolName()).descriptorVersion("1.0.0")
            .executionMode(ToolExecutionMode.EXECUTE).status(IntentPreviewToken.TokenStatus.EXECUTING)
            .expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        when(tokens.claimToken("local-proof","F006",7L,DIGEST))
            .thenReturn(PreviewTokenService.ClaimResult.success(token,"local-claim",params));
        when(tokens.resolveClaim(eq("local-proof"),eq("local-claim"),anyBoolean(),any())).thenReturn(resolve);
        ReflectionTestUtils.setField(orchestrator,"previewTokenService",tokens);
        ReflectionTestUtils.setField(orchestrator,"writeGuardService",h.guard);
        if (org.springframework.util.ReflectionUtils.findField(IntentExecutionOrchestrator.class,"factoryFollowUpService") != null)
            ReflectionTestUtils.setField(orchestrator,"factoryFollowUpService",suggestions);
        var response=orchestrator.confirm("F006",new ConfirmationProof("local-proof",DIGEST,Instant.now().plusSeconds(300)),7L,h.role);
        System.out.println("OBS confirm role="+h.role+" status="+response.getStatus()+" followUps="+response.getFollowUps());
        return response;
    }

    Map<String,Object> qualityParams() { return Map.of("qualityInspectionId","Q-D","defectType","APPEARANCE","quantity",2); }

    @Test void qualityWriteActuallyPersistsAndConfirmationOffersRoleFilteredReads() throws Exception {
        try(var h=new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            var response=confirm(h,"QUALITY_DEFECT_RECORD",qualityParams(),h.suggestions,true);
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            verify(h.defects).save(any(QualityDefect.class));
            var chips=h.mapper.valueToTree(response.getFollowUps());
            assertThat(codes(chips)).containsExactly("QUALITY_CHECK_QUERY","RECEIVE_QUALITY_CHECK_TODAY","QUALITY_STATS");
            h.assertExecutableReads(chips);
        }
    }

    @Test void warehouseUseActuallyPersistsAndConfirmationHasInventoryReads() throws Exception {
        try(var h=new SseFactoryFollowUpContractTest.Harness("warehouse_worker")) {
            MaterialBatch batch=new MaterialBatch();batch.setId("MB-D");batch.setFactoryId("F006");
            batch.setBatchNumber("MT-D");batch.setMaterialTypeId("RMT-D");batch.setQuantityUnit("kg");
            batch.setReceiptQuantity(new BigDecimal("10"));batch.setUsedQuantity(BigDecimal.ZERO);
            batch.setReservedQuantity(BigDecimal.ZERO);batch.setStatus(MaterialBatchStatus.AVAILABLE);
            when(h.materials.findByIdAndFactoryId("MB-D","F006")).thenReturn(Optional.of(batch));
            when(h.materials.findById("MB-D")).thenReturn(Optional.of(batch));
            when(h.materials.save(any(MaterialBatch.class))).thenAnswer(call->call.getArgument(0));
            var consumptions=mock(MaterialConsumptionRepository.class);
            var service=new MaterialBatchServiceImpl(h.materials,null,null,new MaterialBatchMapper(),null,null,null,null);
            ReflectionTestUtils.setField(service,"materialConsumptionRepository",consumptions);
            ReflectionTestUtils.setField(service,"inventoryLowStockEventPublisher",
                mock(com.cretas.aims.service.alerts.InventoryLowStockEventPublisher.class));
            MaterialBatchUseTool tool=new MaterialBatchUseTool();
            ReflectionTestUtils.setField(tool,"materialBatchService",service);
            ReflectionTestUtils.setField(tool,"materialBatchRepository",h.materials);
            h.register("MATERIAL_BATCH_USE",tool,"warehouse_worker");
            var response=confirm(h,"MATERIAL_BATCH_USE",Map.of("batchId","MB-D","quantity",1),h.suggestions,true);
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            verify(consumptions).save(any(MaterialConsumption.class));
            assertThat(batch.getUsedQuantity()).isEqualByComparingTo("1");
            var chips=h.mapper.valueToTree(response.getFollowUps());
            assertThat(codes(chips)).contains("MATERIAL_BATCH_QUERY","MATERIAL_TODAY_RECEIVING_QUERY");
            h.assertExecutableReads(chips);
        }
    }

    @Test void emptyCandidatesStayEmptyWhileWriteStillPersists() throws Exception {
        try(var h=new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            h.configs.keySet().removeAll(List.of("QUALITY_CHECK_QUERY","RECEIVE_QUALITY_CHECK_TODAY","QUALITY_STATS"));
            var response=confirm(h,"QUALITY_DEFECT_RECORD",qualityParams(),h.suggestions,true);
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getFollowUps()).isEmpty();
            verify(h.defects).save(any(QualityDefect.class));
        }
    }

    @Test void writeBoundCandidateIsExcludedWithRemainingReadPositive() throws Exception {
        try(var h=new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            h.configs.get("QUALITY_CHECK_QUERY").setToolName("quality_defect_record");
            var response=confirm(h,"QUALITY_DEFECT_RECORD",qualityParams(),h.suggestions,true);
            assertThat(codes(h.mapper.valueToTree(response.getFollowUps())))
                .contains("RECEIVE_QUALITY_CHECK_TODAY").doesNotContain("QUALITY_CHECK_QUERY");
            verify(h.defects).save(any(QualityDefect.class));
        }
    }

    @Test void followUpDependencyFailureDoesNotLoseSuccessfulWrite() throws Exception {
        try(var h=new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            var unavailable=mock(FactoryFollowUpService.class);
            when(unavailable.suggest(anyString(),anyLong(),anyString(),any(),anyString())).thenThrow(new IllegalStateException("offline"));
            var response=confirm(h,"QUALITY_DEFECT_RECORD",qualityParams(),unavailable,true);
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getFollowUps()).isEmpty();
            verify(h.defects).save(any(QualityDefect.class));
        }
    }

    @Test void unresolvedConfirmationDoesNotOfferAnyReadChips() throws Exception {
        try(var h=new SseFactoryFollowUpContractTest.Harness("quality_inspector")) {
            var suggestions=spy((FactoryFollowUpService)h.suggestions);
            var response=confirm(h,"QUALITY_DEFECT_RECORD",qualityParams(),suggestions,false);
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getFollowUps()).isNullOrEmpty();
            verify(suggestions,never()).suggest(anyString(),anyLong(),anyString(),any(),anyString());
            verify(h.defects).save(any(QualityDefect.class));
        }
    }
}
