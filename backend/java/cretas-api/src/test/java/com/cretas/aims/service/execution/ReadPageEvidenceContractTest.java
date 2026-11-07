package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled provenance/safety contracts, never a real-model success estimate. */
class ReadPageEvidenceContractTest {
    @ParameterizedTest @ValueSource(strings={"第一页","第四页","第１页"})
    void currentOrdinalLiteralIsOfferedAndAcceptedWithoutNormalizingItsSpelling(String evidence) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"改看"+evidence+"，每页20条");var sales=mock(SalesService.class);
            int page=evidence.contains("四")?4:1;
            respond(h,"CORRECT","ABSOLUTE",evidence,Map.of("page",page));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(sales),"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",page).containsEntry("status","FINANCE_APPROVED");
            assertThat(ReadPageEvidence.from(request.getUserInput()).values()).contains(evidence);
            verifyNoInteractions(sales);
        }
    }

    @ParameterizedTest @ValueSource(strings={"第12页","第１２页","每页12条","2026年1月1日","第一页和第二页","第一页、第一页"})
    void modelOutputOutsideUniquePageFieldEvidenceCannotAdvanceAQuery(String input) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,input);var sales=mock(SalesService.class);
            String evidence=input.contains("和第二页")?input:input.contains("第一页")?"第一页":"1";
            respond(h,"NEW","ABSOLUTE",evidence,Map.of("page",1));
            var response=h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(sales),"ORDER_LIST"));
            assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();verifyNoInteractions(sales);
        }
    }

    @Test void wholeOrdinalSurvivesAdjacentPageSizeWithoutOpeningBareNumbers() {
        String input="看销售订单，第一页每页5条";var options=ReadPageEvidence.from(input);
        assertThat(options.values()).contains("第一页").doesNotContain("1","一","5");
        assertThat(options.allows(input,"第一页")).isTrue();
        assertThat(options.allows(input,"5")).isFalse();
    }

    @Test void sourceContractPreservesExplicitCorrectionAndFieldSpellingsWithoutChoosingItsMeaning() {
        String input="从第2页改到第4页";var options=ReadPageEvidence.from(input);
        assertThat(options.allows(input,"第4页")).isTrue();
        assertThat(options.values()).contains("第2页","第4页").doesNotContain(input);
        assertThat(options.allows(input,input)).isFalse();
        for(String input2:List.of("页码4","page 4","翻到4页","看4页"))
            assertThat(ReadPageEvidence.from(input2).allows(input2,input2)).isTrue();
    }

    @Test void pageEvidenceDoesNotOfferAWholePageAndSizeSentence() {
        String input="按第1页每页4条";var options=ReadPageEvidence.from(input);
        assertThat(options.values()).contains("按第1页","第1页").doesNotContain(input);
        assertThat(options.allows(input,"第1页")).isTrue();
        assertThat(options.allows(input,input)).isFalse();
    }

    @Test void multiNumberRelativeActionKeepsOnlyTheNarrowPageFragment() {
        String input="再往后翻一页每页4条";var options=ReadPageEvidence.from(input);
        assertThat(options.values()).contains("再往后翻一页","一页").doesNotContain(input);
        assertThat(options.allowsRelative(input,"再往后翻一页")).isTrue();
        assertThat(options.allowsRelative(input,"一页")).isFalse();
        assertThat(options.allows(input,"一页")).isTrue();
        assertThat(options.allows(input,input)).isFalse();
    }

    @Test void relativeEvidenceMustBeAWholeCurrentPhraseAndUnchangedNeedsNoEvidence() throws Exception {
        var options=ReadPageEvidence.from("再往后翻一页");
        assertThat(options.allows("再往后翻一页","再往后翻一页")).isTrue();
        assertThat(options.allows("再往后翻一页","一")).isFalse();
        assertThat(options.allowsRelative("再往后翻一页","再往后翻一页")).isTrue();
        assertThat(options.allowsRelative("再往后翻一页","一页")).isFalse();
        assertThat(ReadPageEvidence.from("继续往后翻页").allows("继续往后翻页","继续往后翻页")).isTrue();
        assertThat(ReadPageEvidence.from("继续往后翻页").allowsRelative("继续往后翻页","继续往后翻页")).isTrue();
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"条件不变再看一次");SemanticReadTurnResolverContractTest.model(h,"CONTINUE",Map.of());
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",2);
        }
    }

    @Test void excessiveSourcesAndOversizeClausesFailClosedWithoutTruncatingAQuote() {
        String crowded=String.join("，",java.util.stream.IntStream.range(0,70).mapToObj(i->"继续任务【x"+i+"】").toList());
        assertThat(ReadPageEvidence.from(crowded).allowed()).isEmpty();
        assertThat(ReadPageEvidence.from("继续".repeat(110)).allowed()).isEmpty();
    }

    @Test void dynamicSchemaIsRedactedWithTheInputAndRestoredBeforeLiteralValidation() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            String input="质检员赵铁柱13912345678要再往后翻页";var request=previous(h,input);var sales=mock(SalesService.class);
            var client=mock(DashScopeClient.class);
            when(client.chatWithTools(anyString(),anyString(),anyList())).thenAnswer(call->{
                List<Tool> tools=call.getArgument(2);
                String serialized=h.mapper.writeValueAsString(tools);
                assertThat(serialized).doesNotContain("赵铁柱","铁柱","13912345678");
                assertThat((String)call.getArgument(1)).doesNotContain("赵铁柱","13912345678");
                Map<?,?> fields=(Map<?,?>)tools.get(0).getFunction().getParameters().get("properties");
                List<?> values=(List<?>)((Map<?,?>)fields.get("pageEvidence")).get("enum");
                String evidence=values.stream().map(String::valueOf).filter(s->s.contains("再往后翻页")).findFirst().orElseThrow();
                assertThat(evidence).contains("[电话");
                return response(h,Map.of("action","CONTINUE","dateScope","UNCHANGED","parameters",Map.of(),
                        "message","继续查询","pageAction","NEXT","pageEvidence",evidence));
            });
            when(client.hasToolCalls(any())).thenReturn(true);ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",client);
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(sales),"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",3);verifyNoInteractions(sales);
        }
    }

    @Test void redactedSchemaKeepsOnlyTheSingleNumberPageFragmentForPrivateMultiNumberInput() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            String input="质检员赵铁柱13912345678要再往后翻一页";var request=previous(h,input);var sales=mock(SalesService.class);
            var client=mock(DashScopeClient.class);
            when(client.chatWithTools(anyString(),anyString(),anyList())).thenAnswer(call->{
                List<Tool> tools=call.getArgument(2);
                String serialized=h.mapper.writeValueAsString(tools);
                assertThat(serialized).doesNotContain("赵铁柱","铁柱","13912345678");
                assertThat((String)call.getArgument(1)).doesNotContain("赵铁柱","13912345678");
                Map<?,?> fields=(Map<?,?>)tools.get(0).getFunction().getParameters().get("properties");
                List<?> values=(List<?>)((Map<?,?>)fields.get("pageEvidence")).get("enum");
                assertThat(values.stream().map(String::valueOf).toList()).contains("一页")
                        .noneMatch(value->value.contains("再往后翻一页"));
                assertThat(values).allSatisfy(value->assertThat(String.valueOf(value))
                        .doesNotContain("赵铁柱","铁柱","13912345678"));
                return response(h,Map.of("action","CONTINUE","dateScope","UNCHANGED","parameters",Map.of(),
                        "message","继续查询","pageAction","NEXT","pageEvidence","一页"));
            });
            when(client.hasToolCalls(any())).thenReturn(true);ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",client);
            var result=h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(sales),"ORDER_LIST"));
            assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();verifyNoInteractions(sales);
        }
    }

    @Test void ordinaryDateRangeAndEmojiCannotExhaustQuotesOrSplitUnicode() {
        String input="查2026年10月1日到2026年10月31日的订单，😀第一页每页20条😀";
        var options=ReadPageEvidence.from(input);
        assertThat(options.values()).contains("第一页").hasSizeLessThanOrEqualTo(64);
        for(String quote:options.values()) for(int i=0;i<quote.length();i++) {
            char ch=quote.charAt(i);
            if(Character.isHighSurrogate(ch)) assertThat(i+1<quote.length() && Character.isLowSurrogate(quote.charAt(++i))).isTrue();
            else assertThat(Character.isLowSurrogate(ch)).isFalse();
        }
    }

    @Test void unchangedActionCannotSmuggleANewPageButMayRepeatTheConfirmedValue() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"改看第3页");respond(h,"CORRECT","UNCHANGED","",Map.of("page",3));
            var result=h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST"));
            assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");assertThat(request.getSemanticParameters()).isNull();
        }
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"条件不变再查一次");respond(h,"CONTINUE","UNCHANGED","",Map.of("page",2));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST"))).isNull();
            assertThat(request.getSemanticParameters()).containsEntry("page",2);
            assertThat(request.getSemanticParameterSources()).containsEntry("page","PREVIOUS_VALUE");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void onlyAnUngroundedAuthoritativeDefaultMayBeDiscardedWithoutChangingThePage(boolean explicitlyRequested) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,explicitlyRequested?"改看第1页":"条件保持不变");
            respond(h,"CORRECT","UNCHANGED","",Map.of("page",1));
            var result=h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST"));
            if(explicitlyRequested) {
                assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");assertThat(request.getSemanticParameters()).isNull();
            } else {
                assertThat(result).isNull();assertThat(request.getSemanticParameters()).containsEntry("page",2);
                assertThat(request.getSemanticParameterSources()).containsEntry("page","PREVIOUS_VALUE");
            }
        }
    }

    @Test void aCountWordIsStillNumericAndCannotGroundACopiedDifferentPage() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=previous(h,"条件不变再查一次");respond(h,"CONTINUE","UNCHANGED","",Map.of("page",1));
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST")).getStatus())
                    .isEqualTo("NEED_MORE_INFO");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void anUnmentionedDefaultCannotHideADifferentExplicitPage(boolean newTask) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=newTask?SemanticReadTurnResolverContractTest.request(h,"看第3页"):previous(h,"改看第3页");
            respond(h,newTask?"NEW":"CORRECT","UNCHANGED","",Map.of("page",1));
            var result=h.dispatch.resolveReadTurn("F006",7L,request,
                    SemanticReadTurnResolverContractTest.bind(h,new OrderListTool(mock(SalesService.class)),"ORDER_LIST"));
            assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");assertThat(request.getSemanticParameters()).isNull();
        }
        assertThat(ReadPageEvidence.hasNumericContent("page3")).isTrue();
        assertThat(ReadPageEvidence.hasNumericContent("page③")).isTrue();
    }

    private static com.cretas.aims.dto.ai.IntentExecuteRequest previous(SalesRankingSemanticContractTest.Harness h,String input) {
        SemanticReadTurnResolverContractTest.previous(h,"ORDER_LIST",Map.of("toolName","order_list","page",2,"size",3,"status","FINANCE_APPROVED"));
        return SemanticReadTurnResolverContractTest.request(h,input);
    }
    private static void respond(SalesRankingSemanticContractTest.Harness h,String action,String pageAction,String evidence,Map<String,Object> parameters)throws Exception {
        var client=mock(DashScopeClient.class);var result=response(h,Map.of("action",action,"dateScope","UNCHANGED",
                "parameters",parameters,"message","请确认条件","pageAction",pageAction,"pageEvidence",evidence));
        when(client.chatWithTools(anyString(),anyString(),anyList())).thenReturn(result);when(client.hasToolCalls(result)).thenReturn(true);
        ReflectionTestUtils.setField(h.dispatch,"dashScopeClient",client);
    }
    private static ChatCompletionResponse response(SalesRankingSemanticContractTest.Harness h,Map<String,Object> args)throws Exception {
        return h.mapper.convertValue(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("id","fixture","type","function",
                "function",Map.of("name","resolve_read_turn","arguments",h.mapper.writeValueAsString(args)))))))),ChatCompletionResponse.class);
    }
}
