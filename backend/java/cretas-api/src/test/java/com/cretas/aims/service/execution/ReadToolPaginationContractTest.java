package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.impl.material.MaterialExpiredQueryTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Tool/service behavior, controlled data; never proof of real-model understanding. */
class ReadToolPaginationContractTest {
    static class ExposedExpired extends MaterialExpiredQueryTool {
        Map<String,Object> query(Map<String,Object> params) throws Exception {return doExecute("F006",params,Map.of());}
    }
    static MaterialBatchDTO batch(String id,int days) {
        var b=new MaterialBatchDTO();b.setId(id);b.setExpireDate(LocalDate.now().minusDays(days));
        b.setStatus(MaterialBatchStatus.AVAILABLE);return b;
    }
    static ExposedExpired expired() {
        var service=mock(MaterialBatchService.class);
        when(service.getExpiredBatches("F006")).thenReturn(List.of(batch("c",1),batch("b",2),batch("a",2)));
        var tool=new ExposedExpired();ReflectionTestUtils.setField(tool,"materialBatchService",service);return tool;
    }
    @Test void legacyUnpagedCallKeepsAllAuthorizedResultsAndNoPaginationDefaults() throws Exception {
        var tool=expired();var result=tool.query(Map.of());
        assertThat(result).containsEntry("total",3).doesNotContainKeys("page","size");
        assertThat((List<?>)result.get("batches")).hasSize(3);
        var properties=(Map<?,?>)tool.getParametersSchema().get("properties");
        assertThat(((Map<?,?>)properties.get("page")).containsKey("default")).isFalse();
        assertThat(((Map<?,?>)result.get("queryConditions")).get("basis")).isEqualTo("EXPIRY_DATE");
    }
    @Test void equalExpiryDatesHaveStableIdOrderWithoutDuplicatesAndTotalIsNotPageCount() throws Exception {
        var tool=expired();var one=tool.query(Map.of("page",1,"size",2));var two=tool.query(Map.of("page",2,"size",2));
        assertThat(((List<?>)one.get("batches")).stream().map(x->String.valueOf(((Map<?,?>)x).get("id"))).toList()).containsExactly("a","b");
        assertThat(((List<?>)two.get("batches")).stream().map(x->String.valueOf(((Map<?,?>)x).get("id"))).toList()).containsExactly("c");
        assertThat(two).containsEntry("total",3).containsEntry("totalPages",2).containsEntry("page",2).containsEntry("size",2);
        assertThat((List<?>)tool.query(Map.of("page",Integer.MAX_VALUE,"size",100)).get("batches")).isEmpty();
    }
    @Test void invalidPaginationCannotReachAnAccidentalFullQuery() {
        assertThatThrownBy(()->expired().query(Map.of("page",0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->expired().query(Map.of("size",101))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"material_expired_query","receive_quality_check_today"})
    void newlyConnectedReadsUseExistingSemanticParameterValidation(String name) throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var tool=name.equals("material_expired_query")?new MaterialExpiredQueryTool():new com.cretas.aims.ai.tool.impl.workdesk.ReceiveQualityCheckTodayTool();
            var request=SemanticReadTurnResolverContractTest.request(h,"改成状态可用");
            SemanticReadTurnResolverContractTest.model(h,"NEW",Map.of("status","AVAILABLE"));
            var result=h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,tool,name.toUpperCase()));
            assertThat(result.getStatus()).isEqualTo("NEED_MORE_INFO");
            assertThat(result.getMessage()).contains("无法校验的查询条件");
            assertThat(request.getSemanticParameters()).isNull();
        }
    }
    @Test void expiredPaginationIsRememberedByExistingReadStateWithEffectiveToolValues() throws Exception {
        try(var h=new SalesRankingSemanticContractTest.Harness()) {
            var request=SemanticReadTurnResolverContractTest.request(h,"按到期日查过期批次，第1页每页2条");
            SemanticReadTurnResolverContractTest.model(h,"NEW",Map.of("page",1,"size",2));
            var tool=expired();
            assertThat(h.dispatch.resolveReadTurn("F006",7L,request,SemanticReadTurnResolverContractTest.bind(h,tool,"MATERIAL_EXPIRED_QUERY"))).isNull();
            assertThat(h.support.rememberResolvedRead(tool.getToolName(),"F006",7L,request,request.getSemanticParameters())).isTrue();
            var result=com.cretas.aims.dto.ai.IntentExecuteResponse.builder().status("SUCCESS").resultData(tool.query(Map.of("page",1,"size",2))).build();
            assertThat(ToolDispatchService.effectiveReadParameters(tool,result)).containsEntry("page",1).containsEntry("size",2);
        }
    }
    @Test void allThreeSalesListEntrypointsCarryTheSameStableSecondaryKey() throws Exception {
        var repository=mock(SalesOrderRepository.class);
        var service=SalesRankingEntryParityContractTest.Entry.construct(SalesServiceImpl.class,new HashMap<>(Map.of(SalesOrderRepository.class,repository)));
        when(repository.findByFactoryIdOrderByCreatedAtDesc(eq("F006"),any())).thenReturn(Page.<SalesOrder>empty());
        when(repository.findByFactoryIdAndStatusOrderByCreatedAtDesc(eq("F006"),eq(SalesOrderStatus.COMPLETED),any())).thenReturn(Page.<SalesOrder>empty());
        when(repository.searchFiltered(eq("F006"),isNull(),eq(SalesOrderStatus.COMPLETED),isNull(),isNull(),any())).thenReturn(Page.<SalesOrder>empty());
        service.getSalesOrders("F006",1,4);service.getSalesOrdersByStatus("F006",SalesOrderStatus.COMPLETED,2,4);
        service.getSalesOrdersFiltered("F006",SalesOrderStatus.COMPLETED,null,null,2,4);
        var pages=org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByFactoryIdOrderByCreatedAtDesc(eq("F006"),pages.capture());
        verify(repository).findByFactoryIdAndStatusOrderByCreatedAtDesc(eq("F006"),eq(SalesOrderStatus.COMPLETED),pages.capture());
        verify(repository).searchFiltered(eq("F006"),isNull(),eq(SalesOrderStatus.COMPLETED),isNull(),isNull(),pages.capture());
        assertThat(pages.getAllValues()).allSatisfy(p->assertThat(p.getSort().toList()).extracting(x->x.getProperty()+":"+x.getDirection()).containsExactly("createdAt:DESC","id:ASC"));
    }
}
