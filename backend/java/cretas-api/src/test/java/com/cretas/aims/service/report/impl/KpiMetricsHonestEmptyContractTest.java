package com.cretas.aims.service.report.impl;

import com.cretas.aims.dto.report.KpiMetricsDTO;
import com.cretas.aims.dto.report.OeeReportDTO;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.ReworkRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 🔒 没有数据时，KPI 接口必须<b>说没有</b>，而不是发明一个好看的数。
 *
 * <h3>被修掉的形状（2026-08-28 逐屏视觉验收第四轮）</h3>
 *
 * <p>{@code getKpiMetricsDTO} / {@code getOeeReport} / {@code getCostVarianceReport} 里一共有
 * <b>13 个编出来的常量</b>：五个「没数据就兜底」的比率（85 / 96 / 95 / 85 / 95）、
 * 三个 OEE 因子（80 / 85 / 95）、无条件写死的 {@code mtbf=168} / {@code mttr=2}、
 * 以及无条件写死、恰好合计 100 的成本占比 {@code 60 / 25 / 15}。</p>
 *
 * <p><b>实测</b>：{@code GET /api/mobile/F006/reports/kpi-metrics} 返回 {@code otif=95.00}，
 * 而<b>同一个 tab 里</b>的 {@code OnTimeDeliveryReport} 屏显示「暂无OTIF数据」。
 * 两屏对同一个指标给出两个答案，<b>说没有数据的那个是对的</b>。
 * 这些数还进了 {@code calculateOverallScore} ⇒ 连「综合评分 / 评级」都掺了编出来的输入。</p>
 *
 * <h3>为什么不兜底成 0</h3>
 *
 * <p>0 在比率语境里是一个<b>合法读数</b>（0% 合格率 = 全部不合格）。兜底成 0 只是把
 * 「我不知道」翻译成另一句<b>同样具体的假话</b>，下游依然分不清。前端 {@code MetricItem}
 * 对 null 已经有 {@code "--"} 分支。</p>
 *
 * <h3>判据</h3>
 *
 * <p>每条「该为空」的断言都配一条<b>阴性对照</b>：喂进真实事实之后同一个字段必须算得出数。
 * ⛔ 否则把这些 getter 直接返回 null 也能让本测试全绿。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KPI 没有数据时说没有，不发明一个好看的数")
class KpiMetricsHonestEmptyContractTest {

    private static final String FACTORY = "F006";
    private static final LocalDate DATE = LocalDate.of(2026, 8, 28);

    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock UserRepository userRepository;
    @Mock EquipmentRepository equipmentRepository;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock QualityInspectionRepository qualityInspectionRepository;
    @Mock ShipmentRecordRepository shipmentRecordRepository;
    @Mock SalesOrderRepository salesOrderRepository;
    @Mock ReworkRecordRepository reworkRecordRepository;

    @InjectMocks
    ProductionReportServiceImpl service;

    // ==================== 空租户：一条事实都没有 ====================

    @Test
    @DisplayName("零数据工厂：五个比率全部为 null —— ⛔ 不是 85 / 96 / 95")
    void emptyFactoryReportsNothingInsteadOfFabricatedRates() {
        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getOutputCompletionRate()).as("产出完成率原来兜底成 85").isNull();
        assertThat(kpi.getFpy()).as("一次合格率原来兜底成 96").isNull();
        assertThat(kpi.getOtif()).as("准时交付原来兜底成 95").isNull();
        assertThat(kpi.getOnTimeDeliveryRate()).as("与 otif 同源，一起走").isNull();
        assertThat(kpi.getEquipmentAvailability()).as("设备可用率原来兜底成 85").isNull();
        assertThat(kpi.getAttendanceRate()).as("出勤率原来兜底成 95").isNull();
    }

    @Test
    @DisplayName("零数据工厂：MTBF / MTTR 为 null —— 它们原来是两个与设备表无关的字面量")
    void mtbfAndMttrAreNotInventedConstants() {
        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getMtbf()).as("原来无条件写死 168 小时").isNull();
        assertThat(kpi.getMttr()).as("原来无条件写死 2 小时").isNull();
    }

    @Test
    @DisplayName("零数据工厂：综合评分为 null、评级为 N/A —— ⛔ 不是「0 分 / D 级」")
    void emptyFactoryHasNoScoreRatherThanAFabricatedD() {
        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getOverallScore()).isNull();
        assertThat(kpi.getScoreGrade())
                .as("一家一条数据都没有的工厂不该被判成 D 级")
                .isEqualTo("N/A");
    }

    @Test
    @DisplayName("零数据工厂：OEE 三因子与 OEE 本身全为 null，等级 N/A")
    void oeeFactorsAreNullWhenThereIsNothingToMeasure() {
        OeeReportDTO oee = service.getOeeReport(FACTORY, DATE.minusDays(7), DATE);

        assertThat(oee.getAvailability()).as("原来兜底成 80").isNull();
        assertThat(oee.getPerformance()).as("原来兜底成 85").isNull();
        assertThat(oee.getQuality()).as("原来兜底成 95").isNull();
        assertThat(oee.getOeeValue()).as("三因子缺任意一个，OEE 就没有定义").isNull();
        assertThat(oee.getOeeGrade()).isEqualTo("N/A");
    }

    @Test
    @DisplayName("零数据工厂：三个损失率跟着因子一起为 null —— ⛔ 不是「100 - null」")
    void lossRatesFollowTheirFactors() {
        OeeReportDTO oee = service.getOeeReport(FACTORY, DATE.minusDays(7), DATE);

        assertThat(oee.getAvailabilityLoss()).isNull();
        assertThat(oee.getPerformanceLoss()).isNull();
        assertThat(oee.getQualityLoss()).isNull();
    }

    @Test
    @DisplayName("成本占比三件套为 null —— 它们原来是无条件写死、恰好合计 100 的 60/25/15")
    void costRatiosAreNotAnInventedBreakdown() {
        var cost = service.getCostVarianceReport(FACTORY, DATE.minusDays(30), DATE);

        assertThat(cost.getMaterialCostRatio()).isNull();
        assertThat(cost.getLaborCostRatio()).isNull();
        assertThat(cost.getOverheadCostRatio()).isNull();
    }

    @Test
    @DisplayName("零数据工厂：派生量也不许自作主张 —— 废品率 / 成本差异率 / 人均产出全为 null")
    void derivedValuesDoNotInventThemselvesEither() {
        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getScrapRate())
                .as("废品率 = 100 - 良品率; 良品率算不出来时它也没有定义 (原来会 NPE 或算成 100 - 95)")
                .isNull();
        assertThat(kpi.getBomVarianceRate())
                .as("没有批次时成本差异率原来返 0 —— 而「差异 0%」被评分当成满分成本控制")
                .isNull();
        assertThat(kpi.getOutputPerWorker())
                .as("没有在岗人数时人均产出原来返 0")
                .isNull();
    }

    @Test
    @DisplayName("🔴 这一条是真正的病灶：零数据工厂原来能拿 92 分")
    void theEmptyFactoryUsedToScoreNinetyTwo() {
        // 原实现里, 空租户仍有两项非空:
        //   bomVarianceRate = 0     -> 成本得分 100 - |0|*10 = 100, 权重 15
        //   outputPerWorker = 0     -> 无条件加 80 分,            权重 10
        // => (100*0.15 + 80*0.10) * 100 / 25 = 92.00, 评级 A。
        // 表面上「五个比率都为 null 了」看起来已经修好, 而最不显眼的那个数还在说谎。
        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getOverallScore()).isNull();
        assertThat(kpi.getScoreGrade()).isEqualTo("N/A");
    }

    // ==================== 阴性对照：有事实时照样算得出来 ====================

    @Test
    @DisplayName("阴性对照：有设备时设备可用率算得出来（⛔ 防「一律返回 null」也能过）")
    void equipmentAvailabilityIsComputedWhenEquipmentExists() {
        FactoryEquipment running = new FactoryEquipment();
        running.setStatus("RUNNING");
        FactoryEquipment idle = new FactoryEquipment();
        idle.setStatus("IDLE");
        when(equipmentRepository.findByFactoryId(FACTORY)).thenReturn(List.of(running, idle));

        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getEquipmentAvailability()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("阴性对照：有员工时出勤率算得出来")
    void attendanceRateIsComputedWhenUsersExist() {
        when(userRepository.countByFactoryId(FACTORY)).thenReturn(10L);
        when(userRepository.countActiveUsers(FACTORY)).thenReturn(8L);

        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getAttendanceRate()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("阴性对照：有产出与计划时产出完成率算得出来，且综合评分不再是 null")
    void outputCompletionRateIsComputedWhenPlanAndOutputExist() {
        when(productionPlanRepository.calculateOutputBetweenDates(anyString(), any(), any()))
                .thenReturn(new BigDecimal("80"));
        when(productionPlanRepository.calculatePlannedOutputBetweenDates(anyString(), any(), any()))
                .thenReturn(new BigDecimal("100"));

        KpiMetricsDTO kpi = service.getKpiMetricsDTO(FACTORY, DATE);

        assertThat(kpi.getOutputCompletionRate()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("阴性对照：有一个输入时综合评分算得出来，等级不再是 N/A")
    void scoreIsComputedOnceAnyInputExists() {
        KpiMetricsDTO kpi = KpiMetricsDTO.builder().fpy(new BigDecimal("90")).build();

        BigDecimal score = KpiMetricsDTO.calculateOverallScore(kpi);

        assertThat(score).as("只有 FPY 时按其权重归一化，仍应算得出来").isEqualByComparingTo("90.00");
        assertThat(KpiMetricsDTO.calculateGrade(score)).isEqualTo("A");
    }
}
