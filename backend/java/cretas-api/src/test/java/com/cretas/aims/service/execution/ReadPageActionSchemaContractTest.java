package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.service.inventory.SalesService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real schema producer and existing guard evidence, not real-model success. */
class ReadPageActionSchemaContractTest {
    static Stream<Arguments> sources() {
        return Stream.of(
            Arguments.of("再看第2页，每页20条", "VALID", false, Set.of("ABSOLUTE","UNCHANGED")),
            Arguments.of("继续往后翻页", "NEW", false, Set.of("UNCHANGED")),
            Arguments.of("继续往后翻页", "MISSING_PAGE", false, Set.of("UNCHANGED")),
            Arguments.of("继续往后翻页", "VALID", false, Set.of("NEXT","PREVIOUS","UNCHANGED")),
            Arguments.of("联系电话13912345678要继续往后翻页", "VALID", false, Set.of("NEXT","PREVIOUS","UNCHANGED")),
            Arguments.of("联系电话13912345678请把页码设为2", "VALID", false, Set.of("UNCHANGED")),
            Arguments.of("第2页", "VALID", true, Set.of("UNCHANGED")),
            Arguments.of("再看第①页", "VALID", false, Set.of("UNCHANGED"))
        );
    }
    @ParameterizedTest @MethodSource("sources")
    void advertisesOnlyActionsWithToolStateAndLiteralEvidence(String text,String state,boolean noPage,Set<String> expected) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            if(!state.equals("NEW")) {
                Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("toolName","order_list");metadata.put("taskStatus","COMPLETED");
                if(state.equals("VALID"))metadata.put("page",2);
                SemanticReadTurnResolverContractTest.previous(h,"ORDER_LIST",metadata);
            }
            var request=SemanticReadTurnResolverContractTest.request(h,text);
            var client=SemanticReadTurnResolverContractTest.model(h,"CLARIFY",Map.of());
            ToolExecutor tool=noPage?h.tool:new OrderListTool(mock(SalesService.class));
            h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,tool,
                    noPage?SalesRankingParameters.INTENT:"ORDER_LIST"));
            var capture=org.mockito.ArgumentCaptor.forClass(List.class);
            verify(client).chatWithTools(anyString(),anyString(),capture.capture());
            Tool function=(Tool)capture.getValue().get(0);
            var fields=(Map<?,?>)function.getFunction().getParameters().get("properties");
            var page=(Map<?,?>)fields.get("pageAction");
            var offered=((Collection<?>)page.get("enum")).stream().map(String::valueOf).toList();
            assertThat(offered).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(request.getSemanticParameters()).isNull();
        }
    }
}
