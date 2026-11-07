package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled protocol/parameter proofs; live model and actual business evidence are separate. */
class ReadQueryContinuationContractTest {
    @Test void absentBusinessGoalCannotUseModelMessageToClaimAQueryWasExecuted() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"改查另一种状态");
            response(h,"CLARIFY","UNCHANGED","",Map.of(),"已切换查询并完成结果展示");
            var result=h.dispatch.resolveReadTurn("F006",7L,request,IntentMatchResult.empty("改查另一种状态"));
            assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(result.getMessage()).contains("尚未执行查询").doesNotContain("已切换查询并完成结果展示");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }
    @Test void repeatedConfirmedPaginationIsNotANewUnsupportedNumericClaim() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"再查一遍");response(h,"CONTINUE","UNCHANGED","",Map.of("page",2,"size",3,"status","FINANCE_APPROVED"));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",2).containsEntry("size",3).containsEntry("status","FINANCE_APPROVED");
            assertThat(request.getSemanticParameterSources()).containsEntry("page","PREVIOUS_VALUE").containsEntry("size","PREVIOUS_VALUE");
        }
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"接着查");response(h,"CONTINUE","UNCHANGED","",Map.of("page",3,"size",3));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST")).getStatus()).isEqualTo("NEED_MORE_INFO");
        }
    }
    @Test void actualToolDefaultsAndNormalizedStatusRemainAvailableToTheNextTurn() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"查可用原料批次，每页3条");
            var tool=new com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool();
            response(h,"SWITCH","UNCHANGED","",Map.of("size",3));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,tool,"MATERIAL_BATCH_QUERY"))).isNull();
            assertThat(h.support.rememberResolvedRead("material_batch_query","F006",7L,request,request.getSemanticParameters())).isTrue();
            var actual=com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("SUCCESS").resultData(Map.of(
                    "currentPage",1,"size",3,"queryConditions",Map.of("status","AVAILABLE"))).build();
            var effective=ToolDispatchService.effectiveReadParameters(tool,actual);
            assertThat(effective).containsEntry("page",1).containsEntry("size",3).containsEntry("status","AVAILABLE");
            assertThat(h.support.finishRead("F006",7L,request,"SUCCESS",List.of(),Map.of(),effective)).isTrue();
            var followup=SemanticReadTurnResolverContractTest.request(h,"往后翻");
            assertThat(followup.getSemanticPreviousRead().get("parameters"))
                    .isEqualTo(Map.of("status","AVAILABLE","page",1,"size",3));
        }
    }
    @Test void failedResultsAndUnknownStatusCannotBecomeConfirmedState() {
        var tool=new OrderListTool(mock(SalesService.class));
        var failed=com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("FAILED").resultData(Map.of("page",2,"size",3)).build();
        assertThat(ToolDispatchService.effectiveReadParameters(tool,failed)).isEmpty();
        var invalid=com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("SUCCESS").resultData(Map.of("page",-1,"size",101,"appliedStatusFilter",Map.of("code","INVENTED"))).build();
        assertThat(ToolDispatchService.effectiveReadParameters(tool,invalid)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"CONTEXT_CONTINUE","PAGINATION_NEXT","PAGINATION_PREVIOUS"})
    void conversationalRoutingUsesOwnedCompletedBusinessTaskWithoutMutatingMatch(String alias) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"接着查");var target=target();
            var original=alias(alias,false);
            var rebound=ToolDispatchService.bindReadContinuation(original,request,code->Optional.of(target));
            assertThat(rebound.getBestMatch()).isSameAs(target);
            assertThat(original.getBestMatch().getIntentCode()).isEqualTo(alias);
            assertThat(rebound.getConfidence()).isEqualTo(original.getConfidence());
            assertThat(request.getSemanticContext()).containsEntry("continuationAlias",alias);
        }
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRED","UNKNOWN","NEW"})
    void unavailableStateCannotBeRebound(String state) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"接着查");request.setSemanticContext(Map.of("contextStatus",state));
            var match=alias("CONTEXT_CONTINUE",false);
            assertThat(ToolDispatchService.bindReadContinuation(match,request,code->{throw new AssertionError("No lookup");})).isSameAs(match);
        }
    }
    @Test void confidenceAndChangedBindingAreNotBypassed() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"接着查");var uncertain=alias("CONTEXT_CONTINUE",true);
            assertThat(ToolDispatchService.bindReadContinuation(uncertain,request,code->{throw new AssertionError("No lookup");})).isSameAs(uncertain);
            var match=alias("CONTEXT_CONTINUE",false);var wrong=target();wrong.setToolName("material_batch_query");
            assertThat(ToolDispatchService.bindReadContinuation(match,request,code->Optional.of(wrong))).isSameAs(match);
        }
    }
    @ParameterizedTest @ValueSource(strings={"NEXT","PREVIOUS"})
    void relativePageIsCalculatedFromStateAndExecutedWithPreservedSizeAndStatus(String direction) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"接着往后翻");var sales=mock(SalesService.class);var tool=new OrderListTool(sales);
            var match=SemanticReadTurnResolverContractTest.bind(h,tool,"ORDER_LIST");
            response(h,"CONTINUE",direction,"接着往后翻",Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,match)).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",direction.equals("NEXT")?3:1)
                    .containsEntry("size",3).containsEntry("status","FINANCE_APPROVED");
            assertThat(request.getSemanticParameterSources()).containsEntry("page","SERVER_RELATIVE_PAGE");
            verifyNoInteractions(sales);
        }
    }
    @Test void explicitPageAndSizeReachActualToolWhileStatusSurvives() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"改看第4页，每页7条，状态不变");
            var sales=mock(SalesService.class);var tool=new OrderListTool(sales);
            response(h,"CORRECT","ABSOLUTE","第4页",Map.of("page",4,"size",7));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,tool,"ORDER_LIST"))).isNull();
            var parameters=new LinkedHashMap<String,Object>(request.getSemanticParameters());
            when(sales.getSalesOrdersFiltered(eq("F006"),any(),isNull(),isNull(),eq(4),eq(7)))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(List.of(),4,7,0L));
            ReflectionTestUtils.setField(tool,"objectMapper",h.mapper);
            tool.execute(com.cretas.aims.ai.dto.ToolCall.of("fixture","order_list",h.mapper.writeValueAsString(parameters)),Map.of("factoryId","F006","userId",7L,"userRole","sales_manager"));
            verify(sales).getSalesOrdersFiltered(eq("F006"),eq(com.cretas.aims.entity.enums.SalesOrderStatus.FINANCE_APPROVED),isNull(),isNull(),eq(4),eq(7));
        }
    }
    @ParameterizedTest @ValueSource(strings={"","不存在的本轮依据"})
    void missingOrInventedRelativePageEvidenceDoesNotGuess(String evidence) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"接着查");response(h,"CONTINUE","NEXT",evidence,Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST")).getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }
    @Test void aliasCannotForceAChangedGoalOrConflictingPage() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"改查原料库存");
            var match=ToolDispatchService.bindReadContinuation(alias("CONTEXT_CONTINUE",false),request,code->Optional.of(target()));
            response(h,"SWITCH","UNCHANGED","",Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),match.getBestMatch().getIntentCode())).getStatus()).isEqualTo("NEED_MORE_INFO");
        }
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"往后翻");response(h,"CONTINUE","NEXT","往后翻",Map.of("page",99));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST")).getStatus()).isEqualTo("NEED_MORE_INFO");
        }
    }
    @ParameterizedTest @ValueSource(strings={"第4页","第四页","第１页"})
    void absolutePageBindsSelectedCurrentSpanWhenCanonicalValueIsMissing(String evidence) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"改看"+evidence+"，其他条件不变");
            var sales=mock(SalesService.class);
            response(h,"CORRECT","ABSOLUTE",evidence,Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(sales),"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",evidence.equals("第１页")?1:4)
                    .containsEntry("size",3).containsEntry("status","FINANCE_APPROVED");
            assertThat(request.getSemanticParameterSources()).containsEntry("page","CURRENT_EXPLICIT")
                    .containsEntry("status","PREVIOUS_VALUE");
            verifyNoInteractions(sales);
        }
    }
    @ParameterizedTest @ValueSource(strings={"","第3页","第0页","第-1页","第2.5页","第2147483648页","第3页或第4页","末页"})
    void ambiguousInvalidOrForgedAbsolutePageCannotSilentlyReuseOldPage(String evidence) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var input=evidence.equals("第3页")?"再看看结果":"改看"+evidence;
            var request=previous(h,input);var sales=mock(SalesService.class);
            response(h,"CONTINUE","ABSOLUTE",evidence,Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(sales),"ORDER_LIST")).getStatus())
                    .isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
            verifyNoInteractions(sales);
        }
    }
    @Test void absolutePageCannotContradictItsSelectedEvidence() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"看看第4页");response(h,"CORRECT","ABSOLUTE","第4页",Map.of("page",2));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST")).getStatus())
                    .isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }
    private static IntentExecuteRequest previous(SalesRankingSemanticContractTest.Harness h,String input) {
        SemanticReadTurnResolverContractTest.previous(h,"ORDER_LIST",Map.of("toolName","order_list","taskStatus","COMPLETED","page",2,"size",3,"status","FINANCE_APPROVED"));
        return SemanticReadTurnResolverContractTest.request(h,input);
    }
    private static AIIntentConfig target(){return AIIntentConfig.builder().intentCode("ORDER_LIST").toolName("order_list").isActive(true).build();}
    private static IntentMatchResult alias(String code,boolean uncertain){return IntentMatchResult.builder().bestMatch(AIIntentConfig.builder().intentCode(code).toolName("context_continue").build()).confidence(.9).requiresConfirmation(uncertain).build();}
    private static void response(SalesRankingSemanticContractTest.Harness h,String action,String pageAction,String evidence,Map<String,Object> delta)throws Exception {
        response(h,action,pageAction,evidence,delta,"请说明条件");
    }
    private static void response(SalesRankingSemanticContractTest.Harness h,String action,String pageAction,String evidence,Map<String,Object> delta,String message)throws Exception {
        var client=mock(DashScopeClient.class);
        var args=h.mapper.writeValueAsString(Map.of("action",action,"dateScope","UNCHANGED","parameters",delta,"message",message,"pageAction",pageAction,"pageEvidence",evidence));
        var result=h.mapper.convertValue(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("id","fixture","type","function","function",Map.of("name","resolve_read_turn","arguments",args))))))),ChatCompletionResponse.class);
        when(client.chatWithTools(anyString(),anyString(),anyList())).thenReturn(result);when(client.hasToolCalls(result)).thenReturn(true);
        ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",client);
    }
}
