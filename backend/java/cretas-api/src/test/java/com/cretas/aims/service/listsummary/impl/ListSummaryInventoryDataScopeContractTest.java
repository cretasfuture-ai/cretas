package com.cretas.aims.service.listsummary.impl;

import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MaterialBatchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListSummaryInventoryDataScopeContractTest {

    private EntityManager entityManager;
    private Query query;
    private MaterialBatchService materialBatchService;
    private DataScopeResolver resolver;
    private ListSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        materialBatchService = mock(MaterialBatchService.class);
        resolver = mock(DataScopeResolver.class);
        service = new ListSummaryServiceImpl(materialBatchService, resolver);
        ReflectionTestUtils.setField(service, "em", entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{2L, new BigDecimal("8"), new BigDecimal("20")});
        when(materialBatchService.getLowStockWarnings("F006")).thenReturn(List.of());
    }

    @AfterEach
    void clearScope() {
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
    }

    @Test
    void selfScopeAddsFactoryAndCreatedByPredicateAndBindsUserChain() {
        DataScopeContext context = context(DataScope.SELF, 101L);
        DataScopeContext.push(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(101L));

        ListSummaryResponse result = service.computeSummary(
                "F006", "inventory", new ListSummaryRequest(Map.of(), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("factory_id = :fid")
                .contains("created_by IN (:createdByIds)");
        verify(query).setParameter("fid", "F006");
        verify(query).setParameter("createdByIds", List.of(101L));
        assertThat(result.getStats().get(0).getValue()).isEqualTo(2L);
    }

    @Test
    void allScopePreservesUnfilteredInventorySummaryAndDoesNotResolveChain() {
        DataScopeContext context = context(DataScope.ALL, 101L);
        DataScopeContext.push(context);

        service.computeSummary("F006", "inventory", new ListSummaryRequest(Map.of(), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("factory_id = :fid")
                .doesNotContain("created_by IN");
    }

    @Test
    void emptyRestrictedChainFailsClosedInsteadOfDroppingThePredicate() {
        DataScopeContext context = context(DataScope.SELF_AND_BELOW, 101L);
        DataScopeContext.push(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of());

        service.computeSummary("F006", "inventory", new ListSummaryRequest(Map.of(), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("1 = 0");
    }

    @ParameterizedTest
    @EnumSource(value = DataScope.class, names = {"SELF_AND_BELOW", "DEPT_AND_BELOW"})
    void hierarchyScopesUseTheResolverChain(DataScope scope) {
        DataScopeContext context = context(scope, 101L);
        DataScopeContext.push(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(101L, 202L));

        service.computeSummary("F006", "inventory", new ListSummaryRequest(Map.of(), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("created_by IN (:createdByIds)");
        verify(query).setParameter("createdByIds", List.of(101L, 202L));
    }

    @Test
    void generatedRestrictedSqlExecutesAgainstPostgresModeH2AndKeepsFactoryBoundary() throws Exception {
        DataScopeContext context = context(DataScope.SELF, 101L);
        DataScopeContext.push(context);
        when(resolver.resolveCreatedByChain(context)).thenReturn(List.of(101L));

        service.computeSummary("F006", "inventory", new ListSummaryRequest(Map.of(), null, null, null));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue().replace(":fid", "'F006'")
                .replace(":createdByIds", "101");
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:r48_scope_sql;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")) {
            connection.createStatement().execute("CREATE TABLE material_batches ("
                    + "receipt_quantity DECIMAL(18,6), used_quantity DECIMAL(18,6), "
                    + "reserved_quantity DECIMAL(18,6), unit_price DECIMAL(18,6), "
                    + "factory_id VARCHAR(32), deleted_at TIMESTAMP, created_by BIGINT)");
            connection.createStatement().execute("INSERT INTO material_batches VALUES "
                    + "(10,1,0,2,'F006',NULL,101),(50,0,0,9,'F006',NULL,202),"
                    + "(70,0,0,9,'F007',NULL,101)");
            try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1L);
                assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("9");
                assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("18");
            }
        }
    }

    @Test
    void authenticatedRequestWithoutResolvedContextIsRejectedBeforeNativeQuery() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "101");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.computeSummary("F006", "inventory",
                        new ListSummaryRequest(Map.of(), null, null, null)));
        org.mockito.Mockito.verifyNoInteractions(entityManager, materialBatchService);
        RequestContextHolder.resetRequestAttributes();
    }

    private static DataScopeContext context(DataScope scope, Long userId) {
        return DataScopeContext.builder()
                .userId(userId)
                .factoryId("F006")
                .roleCode("warehouse_worker")
                .scope(scope)
                .department("warehouse")
                .column("created_by")
                .build();
    }
}
