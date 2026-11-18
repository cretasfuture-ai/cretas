package com.cretas.aims.service.listsummary.impl;

import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MaterialBatchService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListSummaryServiceImplQualityInspectionTest {

    private ListSummaryServiceImpl service;
    private Query query;

    @BeforeEach
    void setUp() {
        service = new ListSummaryServiceImpl(mock(MaterialBatchService.class), mock(DataScopeResolver.class));
        EntityManager em = mock(EntityManager.class);
        query = mock(Query.class);
        ReflectionTestUtils.setField(service, "em", em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    }

    @Test
    void footerBucketsAddUpToTotalAndPassRateIsPassOverAll() {
        // prod 演示厂实测: 共 69, 合格 54, 不合格 12 -> 剩 3 条此前不显示
        when(query.getSingleResult()).thenReturn(new Object[]{69L, 54L, 12L, 2L});

        ListSummaryResponse r = service.computeSummary("F006", "qualityInspection",
                new ListSummaryRequest(Map.of(), null, null, null));

        assertThat(r.getStats())
                .extracting(ListSummaryResponse.SummaryStat::getLabel)
                .containsExactly("共", "合格", "不合格", "有条件放行", "待判定", "合格率(合格÷全部)");
        long total = ((Number) r.getStats().get(0).getValue()).longValue();
        long parts = 0;
        for (int i = 1; i <= 4; i++) {
            parts += ((Number) r.getStats().get(i).getValue()).longValue();
        }
        assertThat(parts).isEqualTo(total);
        assertThat(r.getStats().get(3).getValue()).isEqualTo(2L);
        assertThat(r.getStats().get(4).getValue()).isEqualTo(1L);
        assertThat(r.getStats().get(5).getValue()).isEqualTo(new BigDecimal("78.3"));
    }
}
