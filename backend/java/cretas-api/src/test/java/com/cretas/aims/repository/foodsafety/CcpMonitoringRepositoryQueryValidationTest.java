package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口 13 新增的实体列与查询在真实 Hibernate + H2 (PG 兼容) 上<b>执行</b>:
 * 工序挂 CCP 查询、未处理偏离计数 (批号 / 计划 / 计划前缀, 已处理不算、合格不算、跨厂不算)、
 * 批次视图 (批号 ∪ 计划)、防双击查重、新列 (快照 / 处理人) 的写读往返。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("CCP 监控: 实体映射 + 偏离/视图查询 (H2 真执行)")
class CcpMonitoringRepositoryQueryValidationTest {

    private static final String F = "F-CCPR-1";

    @Autowired private HaccpCheckpointRepository checkpointRepo;
    @Autowired private HaccpMonitoringRecordRepository recordRepo;
    @Autowired private EntityManager em;

    private HaccpCheckpoint cp(String code, String wp, boolean active) {
        return checkpointRepo.saveAndFlush(HaccpCheckpoint.builder()
                .factoryId(F).checkpointCode(code).name(code).hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("75")).criticalLimitMax(new BigDecimal("100")).unit("℃")
                .workProcessId(wp).active(active).build());
    }

    private HaccpMonitoringRecord rec(String factory, String batch, String plan, boolean deviation, boolean handled,
                                      LocalDateTime at) {
        return recordRepo.saveAndFlush(HaccpMonitoringRecord.builder()
                .factoryId(factory).checkpointId(1L).batchNumber(batch).productionPlanId(plan)
                .monitoringTime(at).measuredValue(new BigDecimal("70")).operatorUserId(5L)
                .isDeviation(deviation)
                .deviationHandledAt(handled ? at.plusMinutes(1) : null)
                .deviationHandledBy(handled ? 6L : null)
                .criticalLimitMinSnapshot(new BigDecimal("75")).criticalLimitMaxSnapshot(new BigDecimal("100"))
                .unitSnapshot("℃").source("APP_STEP_REPORT").processOrder(2).workProcessId("WP-1")
                .build());
    }

    @Test
    @DisplayName("工序挂 CCP: 只列本工序启用项, 按编码排序")
    void checkpointsByWorkProcess() {
        cp("CCP-2", "WP-COOK", true);
        cp("CCP-1", "WP-COOK", true);
        cp("CCP-3", "WP-COOK", false);
        cp("CCP-4", "WP-OTHER", true);
        em.clear();
        assertThat(checkpointRepo.findByFactoryIdAndWorkProcessIdAndActiveTrueOrderByCheckpointCodeAsc(F, "WP-COOK"))
                .extracting(HaccpCheckpoint::getCheckpointCode).containsExactly("CCP-1", "CCP-2");
    }

    @Test
    @DisplayName("未处理偏离计数: 按批号 / 计划 / 计划前缀; 已处理、合格、别的工厂都不算")
    void openDeviationCounts() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 26, 9, 0);
        rec(F, "B-1", "plan-aaaa1111-x", true, false, t);        // 未处理
        rec(F, "B-1", "plan-aaaa1111-x", true, true, t);         // 已处理
        rec(F, "B-1", "plan-aaaa1111-x", false, false, t);       // 合格
        rec(F, "B-2", "plan-bbbb2222-y", true, false, t);        // 未处理, 另一计划
        rec("F-OTHER", "B-1", "plan-aaaa1111-x", true, false, t); // 别厂
        em.clear();

        assertThat(recordRepo.countOpenDeviationsByBatchNumbers(F, Set.of("B-1"))).isEqualTo(1);
        assertThat(recordRepo.countOpenDeviationsByBatchNumbers(F, Set.of("B-1", "B-2"))).isEqualTo(2);
        assertThat(recordRepo.countOpenDeviationsByPlanIds(F, Set.of("plan-aaaa1111-x"))).isEqualTo(1);
        assertThat(recordRepo.countOpenDeviationsByPlanIdPrefix(F, "plan-bbb")).isEqualTo(1);
        assertThat(recordRepo.countOpenDeviationsByPlanIdPrefix(F, "zzz")).isZero();
    }

    @Test
    @DisplayName("批次视图 = 批号 ∪ 计划, 时间正序; 新列写读往返")
    void batchViewAndRoundTrip() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 26, 9, 0);
        rec(F, "CLK-B-9", "plan-c", false, false, t.plusMinutes(10));
        rec(F, "CLK-W-1", "plan-c", true, true, t);
        rec(F, "CLK-W-X", "plan-d", true, false, t);
        em.clear();
        List<HaccpMonitoringRecord> view = recordRepo.findForBatchView(F, Set.of("CLK-B-9"), Set.of("plan-c"));
        assertThat(view).extracting(HaccpMonitoringRecord::getBatchNumber).containsExactly("CLK-W-1", "CLK-B-9");
        HaccpMonitoringRecord first = view.get(0);
        assertThat(first.getDeviationHandledBy()).isEqualTo(6L);
        assertThat(first.getCriticalLimitMinSnapshot()).isEqualByComparingTo("75");
        assertThat(first.getSource()).isEqualTo("APP_STEP_REPORT");
        assertThat(first.getProcessOrder()).isEqualTo(2);
        assertThat(first.isOpenDeviation()).isFalse();
        assertThat(recordRepo.findByIdAndFactoryIdForUpdate(first.getId(), F)).isPresent();
        assertThat(recordRepo.findByIdAndFactoryIdForUpdate(first.getId(), "F-OTHER")).isEmpty();
    }

    @Test
    @DisplayName("防双击查重: 同批同点同人同值且在窗口内 → 命中; 换值 / 超窗 → 不命中")
    void duplicateLookup() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 26, 9, 0);
        rec(F, "B-DUP", null, true, false, t);
        em.clear();
        assertThat(recordRepo.findRecentDuplicates(F, "B-DUP", 1L, 5L, new BigDecimal("70.0000"), t.minusMinutes(1)))
                .hasSize(1);
        assertThat(recordRepo.findRecentDuplicates(F, "B-DUP", 1L, 5L, new BigDecimal("71"), t.minusMinutes(1)))
                .isEmpty();
        assertThat(recordRepo.findRecentDuplicates(F, "B-DUP", 1L, 5L, new BigDecimal("70"), t.plusMinutes(1)))
                .isEmpty();
    }
}
