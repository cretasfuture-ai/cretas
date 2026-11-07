package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.impl.crm.OrderQueryTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled real-result contract; simulated decision transport is not live-LLM evidence. */
class SemanticOrderResultReferenceContractTest {
    @Test void onlySuccessfulServerResultRecordingMarksResultProvenance() {
        for(boolean successful:List.of(false,true)) try(var h=new SalesRankingSemanticContractTest.Harness()) {
            priorLegacy(h,"SO-OLD");
            var request=SemanticReadTurnResolverContractTest.request(h,"读取对象集合");
            assertThat(h.support.reserveRead("ORDER_LIST","F006",7L,request)).isTrue();
            request.setSemanticParameters(Map.of());
            assertThat(h.support.finishRead("F006",7L,request,successful?"SUCCESS":"FAILED",results("SO-NEW"))).isTrue();
            var next=SemanticReadTurnResolverContractTest.request(h,"对象详情");
            assertThat(next.getSemanticPreviousRead().get("resultProvenance"))
                    .isEqualTo(successful?"SERVER_TOOL_RESULT":"UNKNOWN");
            assertThat(next.getSemanticContext().toString()).doesNotContain("SO-NEW","SO-OLD");
            if(successful) assertThat(next.getSemanticContext().toString()).contains("NO_STORED_PARAMETERS");
        }
    }

    @Test void legacyResultReferencesRemainOfUnknownProvenance() {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            priorLegacy(h,"SO-OLD");
            var request=SemanticReadTurnResolverContractTest.request(h,"对象详情");
            assertThat(request.getSemanticPreviousRead().get("resultProvenance")).isEqualTo("UNKNOWN");
            assertThat(h.support.reserveRead("ORDER_STATUS","F006",7L,request)).isTrue();
            assertThat(h.owner.getSlot(EntitySlot.SlotType.READ_QUERY).getMetadata()).doesNotContainKey("resultProvenance");
        }
    }

    @Test void unknownLegacyResultCannotSilentlyBindAnObject() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            priorLegacy(h,"SO-OLD");
            var request=SemanticReadTurnResolverContractTest.request(h,"查看刚才那条订单详情");
            model(h,Map.of("objectReference","order-result-1"));
            var match=SemanticReadTurnResolverContractTest.bind(h,new OrderQueryTool(mock(SalesService.class),mock(SalesOrderRepository.class)),"ORDER_STATUS");
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,match).getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    static List<Map<String,Object>> results(String... ids) {
        return LastActionContract.orderResultReferences("order_list",Map.of("orders",Arrays.stream(ids)
                .map(id -> Map.of("id",id,"customerName","PRIVATE CUSTOMER","price",12345)).toList()));
    }

    @Test void onlyActualResultIdsBecomeBoundedNonRankingReferences() {
        var refs = results("SO-REAL-1","SO-REAL-2");
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0)).containsEntry("id","SO-REAL-1").containsEntry("ordinal",1).containsEntry("ranked",false);
        assertThat(refs.toString()).doesNotContain("PRIVATE","12345");
        assertThat(LastActionContract.orderResultReferences("order_list",Map.of("orders",List.of(Map.of("orderNumber","NAME-ONLY")))))
                .isEmpty();
        assertThat(LastActionContract.orderResultReferences("unrelated_tool",Map.of("orders",List.of(Map.of("id","SO-FAKE")))))
                .isEmpty();
        String[] many = java.util.stream.IntStream.rangeClosed(1,25).mapToObj(i -> "SO-"+i).toArray(String[]::new);
        assertThat(results(many)).hasSize(LastActionContract.MAX_LIST_IDS);
    }

    @Test void modelGetsOpaqueReferencesButTheActualIdStaysLocal() {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            prior(h,"SO-PRIVATE-1");
            var request = SemanticReadTurnResolverContractTest.request(h,"查看刚才那条订单详情");
            assertThat(request.getSemanticContext().toString()).contains("order-result-1").doesNotContain("SO-PRIVATE-1","PRIVATE CUSTOMER");
            assertThat(request.getSemanticPreviousRead().toString()).contains("SO-PRIVATE-1");
        }
    }

    @Test void semanticReferenceBindsTheServerResultIdInsteadOfModelInventedId() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            prior(h,"SO-REAL-1");
            var request = SemanticReadTurnResolverContractTest.request(h,"查看刚才那条订单详情");
            model(h,Map.of("objectReference","order-result-1"));
            var match = SemanticReadTurnResolverContractTest.bind(h,
                    new OrderQueryTool(mock(SalesService.class),mock(SalesOrderRepository.class)),"ORDER_STATUS");
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,match)).isNull();
            assertThat(request.getSemanticParameters()).containsExactly(Map.entry("orderId","SO-REAL-1"));
            assertThat(request.getSemanticParameterSources()).containsEntry("orderId","SERVER_RESULT_REFERENCE");
        }
    }

    @Test void invalidTokenOrWrongEntityToolCannotBindAnOrderId() throws Exception {
        for (boolean wrongTool : List.of(false,true)) try (var h = new SalesRankingSemanticContractTest.Harness()) {
            prior(h,"SO-REAL-1");
            var request = SemanticReadTurnResolverContractTest.request(h,"看看刚才那条");
            model(h,Map.of("objectReference",wrongTool ? "order-result-1" : "order-result-19"));
            var match = wrongTool ? SemanticReadTurnResolverContractTest.bind(h,new MaterialBatchQueryTool(),"MATERIAL_BATCH_QUERY")
                    : SemanticReadTurnResolverContractTest.bind(h,new OrderQueryTool(mock(SalesService.class),mock(SalesOrderRepository.class)),"ORDER_STATUS");
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,match).getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    @Test void multipleResultsWithoutExplicitOrdinalCannotSilentlyPickOne() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            prior(h,"SO-REAL-1","SO-REAL-2");
            var request = SemanticReadTurnResolverContractTest.request(h,"查看刚才那条订单详情");
            model(h,Map.of("objectReference","order-result-1"));
            var match = SemanticReadTurnResolverContractTest.bind(h,new OrderQueryTool(mock(SalesService.class),mock(SalesOrderRepository.class)),"ORDER_STATUS");
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,match).getStatus()).isEqualTo("NEED_MORE_INFO");
        }
    }

    @Test void explicitOrdinalUsesExistingGroundingAndActualReturnedOrder() throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            prior(h,"SO-REAL-1","SO-REAL-2");
            var request = SemanticReadTurnResolverContractTest.request(h,"查看第二条订单详情");
            model(h,Map.of("objectReference","order-result-2","selectionOrdinal",2,"selectionEvidence","第二条"));
            var match = SemanticReadTurnResolverContractTest.bind(h,new OrderQueryTool(mock(SalesService.class),mock(SalesOrderRepository.class)),"ORDER_STATUS");
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,match)).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("orderId","SO-REAL-2");
        }
    }

    static void prior(SalesRankingSemanticContractTest.Harness h,String... ids) {
        var data=Map.of("orders",Arrays.stream(ids).map(id->Map.of("id",id)).toList(),"total",ids.length);
        h.owner.setSlot(EntitySlot.SlotType.READ_QUERY,EntitySlot.builder().id("ORDER_LIST").metadata(Map.of(
                "toolName","order_list","taskStatus","COMPLETED","resultReferences",results(ids),
                "resultProvenance","SERVER_TOOL_RESULT","resultSummary",LastActionContract.orderResultSummary("order_list",data))).build());
    }
    static void priorLegacy(SalesRankingSemanticContractTest.Harness h,String... ids) {
        h.owner.setSlot(EntitySlot.SlotType.READ_QUERY,EntitySlot.builder().id("ORDER_LIST").metadata(Map.of(
                "toolName","order_list","taskStatus","COMPLETED","resultReferences",results(ids))).build());
    }

    @Test void boundedReferencesCannotImplyThatTheFullReturnedPageHasOnlyOneObject() throws Exception {
        var rows=java.util.stream.IntStream.rangeClosed(1,25).mapToObj(i->Map.of("id","SO-"+i)).toList();
        var data=Map.of("orders",rows,"total",40);
        var refs=LastActionContract.orderResultReferences("order_list",data);
        var summary=LastActionContract.orderResultSummary("order_list",data);
        assertThat(refs).hasSize(20);
        assertThat(refs.get(1)).containsEntry("ordinal",2).containsEntry("reference","order-result-2");
        assertThat(summary).containsEntry("returnedCount",25).containsEntry("referenceCount",20)
                .containsEntry("referencesComplete",false).containsEntry("totalCount",40L);
        for(boolean explicit:List.of(false,true)) try(var h=new SalesRankingSemanticContractTest.Harness()) {
            h.owner.setSlot(EntitySlot.SlotType.READ_QUERY,EntitySlot.builder().id("ORDER_LIST").metadata(Map.of(
                    "toolName","order_list","taskStatus","COMPLETED","resultReferences",refs,
                    "resultProvenance","SERVER_TOOL_RESULT","resultSummary",summary)).build());
            var request=SemanticReadTurnResolverContractTest.request(h,explicit?"查看第二条订单详情":"查看那条订单详情");
            model(h,explicit?Map.of("objectReference","order-result-2","selectionOrdinal",2,"selectionEvidence","第二条")
                    :Map.of("objectReference","order-result-2"));
            var match=SemanticReadTurnResolverContractTest.bind(h,new OrderQueryTool(mock(SalesService.class),mock(SalesOrderRepository.class)),"ORDER_STATUS");
            var response=h.dispatch.resolveReadTurn("F006",7L,request,match);
            if(explicit){assertThat(response).isNull();assertThat(request.getSemanticParameters()).containsEntry("orderId","SO-2");}
            else assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
        }
        var invalid=Map.of("orders",List.of(Map.of("orderNumber","NO-ID"),Map.of("id","SO-SECOND")));
        assertThat(LastActionContract.orderResultReferences("order_list",invalid)).isEmpty();
        assertThat(LastActionContract.orderResultSummary("order_list",invalid)).containsEntry("referencesComplete",false);
    }
    static void model(SalesRankingSemanticContractTest.Harness h,Map<String,Object> extra) throws Exception {
        var args = new LinkedHashMap<String,Object>(Map.of("action","SWITCH","dateScope","UNCHANGED","parameters",Map.of(),"message","请选择订单。"));
        args.putAll(extra);
        var response = h.mapper.convertValue(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of(
                "id","fixture","type","function","function",Map.of("name","resolve_read_turn","arguments",h.mapper.writeValueAsString(args)))))))),ChatCompletionResponse.class);
        var client = mock(DashScopeClient.class);
        when(client.chatWithTools(anyString(),anyString(),anyList())).thenReturn(response);
        when(client.hasToolCalls(response)).thenReturn(true);
        ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",client);
    }
}
