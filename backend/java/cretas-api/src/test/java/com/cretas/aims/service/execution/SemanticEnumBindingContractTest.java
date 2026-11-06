package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled enum binding contract, not proof of live LLM semantics. */
class SemanticEnumBindingContractTest {
    static Map<?,?> properties() {return (Map<?,?>)new MaterialBatchQueryTool().getParametersSchema().get("properties");}
    @Test void currentValueFragmentBindsThroughTheExistingToolVocabularyInsteadOfCopyingOldCode() {
        var delta=new LinkedHashMap<String,Object>(Map.of("status","USED_UP"));
        var evidence=ToolDispatchService.bindReadEnumEvidence(properties(),delta,Map.of("status","已耗尽"),
                "改查已耗尽的", "CORRECT",Map.of("status","USED_UP"));
        assertThat(delta).containsEntry("status","DEPLETED");assertThat(evidence).containsEntry("status","DEPLETED");
    }
    @Test void aSelectedCurrentFragmentCanBindAnOmittedCode() {
        var delta=new LinkedHashMap<String,Object>();
        ToolDispatchService.bindReadEnumEvidence(properties(),delta,Map.of("status","耗尽"),"只查耗尽批次","NEW",Map.of());
        assertThat(delta).containsExactlyEntriesOf(Map.of("status","DEPLETED"));
    }
    @Test void anotherDomainUsesItsOwnDeclaredAliasesWithoutANewTable() {
        var fields=(Map<?,?>)new OrderListTool(mock(SalesService.class)).getParametersSchema().get("properties");
        var delta=new LinkedHashMap<String,Object>(Map.of("status","CONFIRMED"));
        ToolDispatchService.bindReadEnumEvidence(fields,delta,Map.of("status","财务通过"),"查财务通过的订单","NEW",Map.of());
        assertThat(delta).containsEntry("status","FINANCE_APPROVED");
    }
    @Test void unselectedNegatedMentionDoesNotChangeAnyCondition() {
        var delta=new LinkedHashMap<String,Object>(Map.of("status","USED_UP"));
        assertThat(ToolDispatchService.bindReadEnumEvidence(properties(),delta,null,
                "不要改成已耗尽，保持原状态", "CONTINUE",Map.of("status","USED_UP"))).isEmpty();
        assertThat(delta).containsEntry("status","USED_UP");
    }
    @Test void continuationCannotSilentlyTurnAValueFragmentIntoAModification() {
        var delta=new LinkedHashMap<String,Object>(Map.of("status","USED_UP"));
        assertThatThrownBy(()->ToolDispatchService.bindReadEnumEvidence(properties(),delta,Map.of("status","已耗尽"),
                "不要改成已耗尽，保持原状态", "CONTINUE",Map.of("status","USED_UP"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(delta).containsEntry("status","USED_UP");
    }
    @ParameterizedTest @ValueSource(strings={"status","factoryId","userRole"})
    void inventedOrNonBusinessEvidenceCannotBindAnything(String key) {
        var delta=new LinkedHashMap<String,Object>();
        assertThatThrownBy(()->ToolDispatchService.bindReadEnumEvidence(properties(),delta,Map.of(key,"已耗尽"),
                "继续上一查询", "CORRECT",Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(delta).isEmpty();
    }
    @Test void aPresentFragmentStillCannotBindAnUndeclaredField() {
        assertThatThrownBy(()->ToolDispatchService.bindReadEnumEvidence(properties(),new LinkedHashMap<>(),
                Map.of("userRole","厂长"),"改为厂长", "CORRECT",Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ambiguousVocabularyFailsClosed() {
        var fields=Map.of("status",Map.of("enum",List.of("ONE","TWO"),"x-status-words",Map.of("ONE",List.of("待处理"),"TWO",List.of("待处理"))));
        assertThatThrownBy(()->ToolDispatchService.bindReadEnumEvidence(fields,new LinkedHashMap<>(),Map.of("status","待处理"),
                "只看待处理", "NEW",Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unlistedParaphrasesKeepTheExistingSemanticGroundingPath() {
        var delta=new LinkedHashMap<String,Object>(Map.of("status","DEPLETED"));
        assertThat(ToolDispatchService.bindReadEnumEvidence(properties(),delta,Map.of("status","一点不剩"),
                "看看一点不剩的批次", "CORRECT",Map.of("status","USED_UP"))).isEmpty();
        assertThat(delta).containsEntry("status","DEPLETED");
    }
    @Test void actualReadResolverAppliesCurrentBindingBeforeRemovingRepeatedOldValues() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            SemanticReadTurnResolverContractTest.previous(h,"MATERIAL_BATCH_QUERY",Map.of("toolName","material_batch_query", "taskStatus","COMPLETED","page",2,"size",5,"status","USED_UP"));
            var request=SemanticReadTurnResolverContractTest.request(h,"改查已耗尽的，从第1页开始，每页仍是5条。");
            var client=mock(DashScopeClient.class);
            var args=h.mapper.writeValueAsString(Map.of("action","CORRECT","dateScope","UNCHANGED","pageAction","ABSOLUTE","pageEvidence","从第1页开始",
                    "parameters",Map.of("page",1,"size",5,"status","USED_UP"),"parameterEvidence",Map.of("status","已耗尽"),"message","查询"));
            var result=h.mapper.convertValue(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("id","fixture","type","function",
                    "function",Map.of("name","resolve_read_turn","arguments",args))))))),ChatCompletionResponse.class);
            when(client.chatWithTools(anyString(),anyString(),anyList())).thenReturn(result);when(client.hasToolCalls(result)).thenReturn(true);
            ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",client);
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,new MaterialBatchQueryTool(),"MATERIAL_BATCH_QUERY"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("status","DEPLETED").containsEntry("page",1).containsEntry("size",5);
            assertThat(request.getSemanticParameterSources()).containsEntry("status","CURRENT_EXPLICIT").containsEntry("size","PREVIOUS_VALUE");
        }
    }
}
