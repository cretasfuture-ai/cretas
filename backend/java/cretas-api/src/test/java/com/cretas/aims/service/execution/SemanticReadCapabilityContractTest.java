package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled contract evidence only; real LLM semantic behavior is evaluated separately. */
class SemanticReadCapabilityContractTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void sourceAndTargetAreDistinctInBothSwitchDirectionsAndOldStatusCannotLeak(boolean toExpiry) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            String source=toExpiry?"MATERIAL_BATCH_QUERY":"MATERIAL_EXPIRED_QUERY";
            String target=toExpiry?"MATERIAL_EXPIRED_QUERY":"MATERIAL_BATCH_QUERY";
            String sourceTool=toExpiry?"material_batch_query":"material_expired_query";
            Map<String,Object> old=new LinkedHashMap<>(Map.of("toolName",sourceTool,"page",1,"size",6));
            if(toExpiry) old.put("status","EXPIRED");
            SemanticReadTurnResolverContractTest.previous(h,source,old);
            var request=SemanticReadTurnResolverContractTest.request(h,"更换查询，第1页，每页6条，目标状态EXPIRED");
            Map<String,Object> delta=new LinkedHashMap<>(Map.of("page",1,"size",6));
            if(!toExpiry) delta.put("status","EXPIRED");
            var client=SemanticReadTurnResolverContractTest.model(h,"SWITCH",delta);
            var tool=toExpiry?new MaterialExpiredQueryTool():new MaterialBatchQueryTool();
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,tool,target))).isNull();
            var input=ArgumentCaptor.forClass(String.class);
            verify(client).chatWithTools(anyString(),input.capture(),anyList());
            var transition=h.mapper.readTree(input.getValue()).path("readTransition");
            assertThat(transition.path("source").path("intent").asText()).isEqualTo(source);
            assertThat(transition.path("target").path("intent").asText()).isEqualTo(target);
            assertThat(transition.path("canInherit").asBoolean()).isFalse();
            if(toExpiry) {
                assertThat(transition.path("source").path("confirmedParameters").path("status").asText()).isEqualTo("EXPIRED");
                assertThat(transition.path("target").path("parameters").path("properties").has("status")).isFalse();
                assertThat(request.getSemanticParameters()).doesNotContainKey("status");
            } else assertThat(request.getSemanticParameters()).containsEntry("status","EXPIRED");
            assertThat(h.owner.getSlot(com.cretas.aims.dto.conversation.EntitySlot.SlotType.READ_QUERY).getId()).isEqualTo(source);
        }
    }

    @Test void actualToolOwnsHumanScopeAndBothEntriesShowIdenticalChoices() {
        var registry=mock(ToolRegistry.class);
        var batch=new MaterialBatchQueryTool();var expiry=new MaterialExpiredQueryTool();
        when(registry.getExecutor("material_batch_query")).thenReturn(Optional.of(batch));
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(expiry));
        String status=ClarificationCandidateService.readCapabilityDescription(registry,"material_batch_query","旧泛化描述");
        String date=ClarificationCandidateService.readCapabilityDescription(registry,"material_expired_query","旧泛化描述");
        assertThat(status).contains("已经保存的批次状态");
        assertThat(date).contains("到期日期早于今天","尚未标记");
        assertThat(ClarificationButtonText.subtitle(status)).isEqualTo(status);
        assertThat(ClarificationButtonText.subtitle(date)).isEqualTo(date);
        var choices=List.of(ClarificationCandidateService.toSelectAction("F006","A","状态清单",status,.55),
                ClarificationCandidateService.toSelectAction("F006","B","日期清单",date,.44));
        String normal=ReflectionTestUtils.invokeMethod(mock(IntentExecutionOrchestrator.class,CALLS_REAL_METHODS),"buildChoicesLine",choices);
        String sse=ReflectionTestUtils.invokeMethod(mock(SseStreamingService.class,CALLS_REAL_METHODS),"streamChoicesLine",choices);
        assertThat(sse).isEqualTo(normal).contains(status,date).doesNotContain("置信度");
        assertThat(choices).allSatisfy(c->assertThat(c.getParameters()).containsOnlyKeys("intentCode"));
    }

    @Test void unavailableMetadataDoesNotInventCapabilityOrPermission() {
        var registry=mock(ToolRegistry.class);
        when(registry.getExecutor("missing")).thenReturn(Optional.empty());
        assertThat(ClarificationCandidateService.readCapabilityDescription(registry,"missing","原说明")).isEqualTo("原说明");
        assertThat(ClarificationCandidateService.readCapabilityDescription(null,"missing",null)).isNull();
    }

    @ParameterizedTest @ValueSource(ints={1,2})
    void actualResponseBuildersShareScopeMessageWithoutAddingDefaultsOrStaleQuestion(int count) {
        var registry=mock(ToolRegistry.class);
        when(registry.getExecutor("material_batch_query")).thenReturn(Optional.of(new MaterialBatchQueryTool()));
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(new MaterialExpiredQueryTool()));
        var configs=mock(com.cretas.aims.service.intent.IntentConfigManagementService.class);
        when(configs.resolveBusinessDomain("F006")).thenReturn("FACTORY");
        var status=com.cretas.aims.entity.config.AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY")
                .intentName("状态清单").toolName("material_batch_query").businessType("FACTORY").isActive(true).build();
        var date=com.cretas.aims.entity.config.AIIntentConfig.builder().intentCode("MATERIAL_EXPIRED_QUERY")
                .intentName("日期清单").toolName("material_expired_query").businessType("FACTORY").isActive(true).build();
        when(configs.getIntentByCode("F006","MATERIAL_BATCH_QUERY")).thenReturn(Optional.of(status));
        when(configs.getIntentByCode("F006","MATERIAL_EXPIRED_QUERY")).thenReturn(Optional.of(date));
        var candidates=new ArrayList<com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent>();
        candidates.add(com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent.builder()
                .intentCode(status.getIntentCode()).intentName(status.getIntentName()).confidence(.55).build());
        if(count==2)candidates.add(com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent.builder()
                .intentCode(date.getIntentCode()).intentName(date.getIntentName()).confidence(.44).build());
        var match=com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(status).confidence(.55)
                .topCandidates(candidates).clarificationQuestion("历史拒绝方向，不是本轮口径").requiresConfirmation(true).build();
        var actions=candidates.stream().map(c->ClarificationCandidateService.toSelectAction("F006",c.getIntentCode(),c.getIntentName(),
                ClarificationCandidateService.readCapabilityDescription(registry,c.getIntentCode().equals(status.getIntentCode())?
                        status.getToolName():date.getToolName(),null),c.getConfidence())).toList();
        var ordinary=mock(IntentExecutionOrchestrator.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(ordinary,"configService",configs);ReflectionTestUtils.setField(ordinary,"toolRegistry",registry);
        var stream=mock(SseStreamingService.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(stream,"toolRegistry",registry);
        var service=mock(ClarificationCandidateService.class);
        when(service.buildCandidateActions(eq(match),eq("F006"),eq(7L),anyString(),anyString())).thenReturn(actions);
        ReflectionTestUtils.setField(stream,"clarificationCandidateService",service);
        var normal=ordinary.buildClarificationResponse(match,"F006");
        IntentExecuteResponse sse=ReflectionTestUtils.invokeMethod(stream,"buildClarificationResponseForStream",match,"F006",7L,"factory_super_admin","READ");
        assertThat(sse.getMessage()).isEqualTo(normal.getMessage()).contains(actions.get(0).getDescription())
                .doesNotContain("历史拒绝方向","默认");
        assertThat(sse.getStatus()).isEqualTo("NEED_CLARIFICATION");
        if(count==1)assertThat(sse.getMessage()).doesNotContain("2.");
    }
}
