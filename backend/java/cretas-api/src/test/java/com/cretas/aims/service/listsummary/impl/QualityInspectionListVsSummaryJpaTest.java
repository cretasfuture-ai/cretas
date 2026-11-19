package com.cretas.aims.service.listsummary.impl;

import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MaterialBatchService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 真实 Hibernate/JPA Context: 质检记录页「列表总数」与「页脚汇总 共」在同一份数据上必须相等。
 *
 * <p>2026-11-19 LSMDEMO: 列表(JPQL, 实体 @Where deleted_at IS NULL)39 条, 页脚(原生 SQL, 漏了 deleted_at)71 条,
 * 差的 32 条是软删记录。两边都走真实入口: 列表 = repository.searchForAdminList (ProcessingServiceImpl.searchInspections
 * 的唯一查询), 汇总 = ListSummaryServiceImpl.computeSummary("qualityInspection")。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("质检记录: 列表 total == 汇总 共 (软删口径一致)")
class QualityInspectionListVsSummaryJpaTest {

    private static final String FACTORY = "F-JPA-QC-SUMMARY";

    @Autowired
    private QualityInspectionRepository repository;

    @Autowired
    private EntityManager em;

    private QualityInspection row(String result) {
        return QualityInspection.builder()
                .factoryId(FACTORY)
                .productionBatchId(1L)
                .inspectorId(1L)
                .inspectionDate(LocalDate.of(2026, 11, 18))
                .sampleSize(new BigDecimal("10"))
                .passCount(new BigDecimal("10"))
                .failCount(BigDecimal.ZERO)
                .result(result)
                // H2 无法把 jsonb custom_fields 回读成 Map (见 ProductionBatchCostIncompleteJpaTest), 存 NULL 绕开; 与口径无关
                .customFields(null)
                .build();
    }

    private ListSummaryServiceImpl summaryService() {
        ListSummaryServiceImpl service = new ListSummaryServiceImpl(
                mock(MaterialBatchService.class), mock(DataScopeResolver.class));
        ReflectionTestUtils.setField(service, "em", em);
        return service;
    }

    private static long stat(ListSummaryResponse r, String label) {
        return r.getStats().stream().filter(s -> label.equals(s.getLabel())).findFirst().orElseThrow()
                .getValue() instanceof Number n ? n.longValue() : -1;
    }

    @Test
    @DisplayName("3 条在册 + 2 条软删: 列表 total == 汇总 共 == 3, 分档合计也只数在册")
    void softDeletedRowsAreNotCountedByEitherSide() {
        // 本测只关心两条查询的口径; users / production_batches 的外键与此无关 (H2 会话级开关, 不影响别的测试)
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        repository.saveAndFlush(row("PASS"));
        repository.saveAndFlush(row("FAIL"));
        repository.saveAndFlush(row("PENDING"));
        QualityInspection gone1 = repository.saveAndFlush(row("PASS"));
        QualityInspection gone2 = repository.saveAndFlush(row("FAIL"));
        // 软删走原生 SQL (实体 @Where 会挡住 ORM 路径); 同时证明软删行确实在表里 (Y 一定发生)
        int updated = em.createNativeQuery("UPDATE quality_inspections SET deleted_at = CURRENT_TIMESTAMP WHERE id IN (:a, :b)")
                .setParameter("a", gone1.getId()).setParameter("b", gone2.getId()).executeUpdate();
        assertThat(updated).isEqualTo(2);
        em.clear();
        long physical = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM quality_inspections WHERE factory_id = :f")
                .setParameter("f", FACTORY).getSingleResult()).longValue();
        assertThat(physical).isEqualTo(5);

        long listTotal = repository.searchForAdminList(FACTORY, null, null, null, null, null, null,
                PageRequest.of(0, 20)).getTotalElements();
        ListSummaryResponse summary = summaryService().computeSummary(FACTORY, "qualityInspection",
                new ListSummaryRequest(Map.of(), null, null, null));

        assertThat(listTotal).isEqualTo(3);
        assertThat(stat(summary, "共")).isEqualTo(listTotal);
        assertThat(stat(summary, "合格")).isEqualTo(1);
        assertThat(stat(summary, "不合格")).isEqualTo(1);
        assertThat(stat(summary, "待判定")).isEqualTo(1);
    }
}
