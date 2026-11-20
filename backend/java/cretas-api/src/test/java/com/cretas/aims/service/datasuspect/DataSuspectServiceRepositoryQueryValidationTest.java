package com.cretas.aims.service.datasuspect;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移 P5 在真实 JPA 映射上跑 (H2 PG-compat): 新列 tare_weight / expired_on_create_reason 能存能回读;
 * 数据疑点只列有疑点的、只看本厂、只看窗口内、不改原值; 逐笔称重按任务列出并带皮重。
 * JSONB 列 (laborSegments 等) 留 null (H2 限制), 起止相同用报工单的开始 / 结束时间覆盖。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({DataSuspectService.class, com.cretas.aims.service.unit.impl.UnitContractServiceImpl.class})
class DataSuspectServiceRepositoryQueryValidationTest {

    private static final String F1 = "F-SUSP-1";
    private static final String F2 = "F-SUSP-2";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    @Autowired private DataSuspectService service;
    @Autowired private ProductionReportRepository reportRepository;
    @Autowired private MaterialBatchRepository materialBatchRepository;
    @Autowired private TestEntityManager em;

    private ProductionReport weighing(String factory, long task, LocalDate day, String in, String out, String tare) {
        return reportRepository.saveAndFlush(ProductionReport.builder()
                .factoryId(factory).batchId(500L).workerId(9L).reportType("YIELD").reportDate(day)
                .workProcessTaskId(task).processOrder(1)
                .inputQuantity(new BigDecimal(in)).inputUnit("kg")
                .outputQuantity(new BigDecimal(out)).outputUnit("kg")
                .tareWeight(tare == null ? null : new BigDecimal(tare))
                .build());
    }

    @Test
    void suspectsAreListedReadOnlyAndScopedToFactoryAndWindow() {
        ProductionReport over = weighing(F1, 1, TODAY, "100", "105", "2.5");          // 出成率 105% ⇒ 疑点
        ProductionReport normal = weighing(F1, 1, TODAY, "100", "80", null);          // 正常
        ProductionReport same = weighing(F1, 2, TODAY.minusDays(1), "100", "70", null);
        same.setProductionStartTime(LocalTime.of(9, 0));
        same.setProductionEndTime(LocalTime.of(9, 0));                             // 起止相同 ⇒ 疑点
        reportRepository.saveAndFlush(same);
        weighing(F1, 3, TODAY.minusDays(30), "100", "150", null);                     // 窗口外
        weighing(F2, 4, TODAY, "100", "200", null);                                   // 别厂
        em.clear();

        DataSuspectService.SuspectList list = service.listSuspects(F1, 14, TODAY);

        assertThat(list.scanned()).as("窗口内本厂称重报工 3 条 (必然读数)").isEqualTo(3);
        assertThat(list.truncated()).isFalse();
        assertThat(list.items()).extracting(DataSuspectService.WeighingEntry::reportId)
                .containsExactlyInAnyOrder(over.getId(), same.getId());
        DataSuspectService.WeighingEntry o = list.items().stream().filter(e -> e.reportId().equals(over.getId())).findFirst().orElseThrow();
        assertThat(o.suspects()).containsExactly("出成率 105.00%，达到或超过 100%");
        assertThat(o.whoToCheck()).isEqualTo(DataSuspectRules.WHO_TO_CHECK);
        assertThat(o.tareWeight()).isEqualByComparingTo("2.50");

        // 不改原值
        em.clear();
        assertThat(reportRepository.findById(over.getId()).orElseThrow().getOutputQuantity()).isEqualByComparingTo("105");
        assertThat(normal.getId()).isNotNull();
    }

    @Test
    void taskWeighingsListEveryEntryWithTare() {
        weighing(F1, 7, TODAY, "50", "40", "1.20");
        weighing(F1, 7, TODAY, "50", "51", null);
        weighing(F2, 7, TODAY, "50", "40", null);   // 别厂同号任务不出现
        em.clear();

        List<DataSuspectService.WeighingEntry> rows = service.listTaskWeighings(F1, 7L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).tareWeight()).isEqualByComparingTo("1.20");
        assertThat(rows.get(0).suspects()).isEmpty();
        assertThat(rows.get(1).tareWeight()).isNull();
        assertThat(rows.get(1).suspects()).hasSize(1);
    }

    @Test
    void expiredOnCreateReasonRoundTrips() {
        MaterialBatch b = new MaterialBatch();
        b.setId(UUID.randomUUID().toString());
        b.setFactoryId(F1);
        b.setBatchNumber("CMA-P5-1");
        b.setMaterialTypeId("RM-1");
        b.setReceiptDate(TODAY);
        b.setExpireDate(TODAY.minusDays(2));
        b.setWarehouseId("WH-RAW");
        b.setReceiptQuantity(BigDecimal.TEN);
        b.setQuantityUnit("kg");
        b.setCreatedBy(1L);
        b.setExpiredOnCreateReason("客户坚持送来，退货前暂存");
        // 只验列映射: 批次的外键 (建档人 / 物料 / 仓库) 与本列无关, 在 H2 测试库里临时关掉外键检查
        em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            materialBatchRepository.saveAndFlush(b);
        } finally {
            em.getEntityManager().createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
        em.clear();

        // 直接读列 (不经实体回读: H2 对批次上无关的 jsonb 列回读有限制)
        Object stored = em.getEntityManager()
                .createNativeQuery("SELECT expired_on_create_reason FROM material_batches WHERE id = ?1")
                .setParameter(1, b.getId()).getSingleResult();
        assertThat(stored).isEqualTo("客户坚持送来，退货前暂存");
    }
}
