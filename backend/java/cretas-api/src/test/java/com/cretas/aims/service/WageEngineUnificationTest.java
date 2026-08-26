package com.cretas.aims.service;

import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.PieceRateRule;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.HourlyRateRuleRepository;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.PieceRateRuleRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.repository.WagePolicyRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两套工资引擎收敛成一个算法 —— 本轮的核心断言在
 * {@link EngineParity#bothEnginesAgree_forWorkerAcrossThreeProcessesWithDifferentRates()}.
 *
 * <p><b>被修的缺陷</b>: {@code generatePayroll} 过去用
 * 「全期总件数 × <b>出现最多那道工序</b>的规则, 只套一档」;
 * {@code calculateMonthly} 用「<b>逐道工序</b>分别套各自规则再求和」。
 * 工人切三道工序时, 第一个会把三道的件数全按干得最多那道的单价算 —— 不是口径差异, 是算错了。
 *
 * <p><b>测试数据</b> (F006 卤制品, 三道工序单价<b>互不相同</b>):
 * <pre>
 *   去头  100 件 @ ¥1.00  = ¥100      (1 天)
 *   剥壳  200 件 @ ¥2.50  = ¥500      (1 天)
 *   分级  300 件 @ ¥4.00  = ¥1200     (3 天 ← 出现次数最多)
 *   ----------------------------------------------
 *   逐工序 (正确)              = ¥1800.00
 *   全按「分级」单价 (收敛前的错法) = 600 × ¥4.00 = ¥2400.00
 * </pre>
 * 单价必须互不相同, 否则两种算法算出同一个数, 断言恒真 —— 见
 * {@link EngineParity#processRatesMustDiffer_otherwiseTheParityAssertionIsVacuous()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工资引擎收敛 — generatePayroll 与 calculateMonthly 必须算出同一个数")
class WageEngineUnificationTest {

    @Mock private PieceRateRuleRepository pieceRateRuleRepository;
    @Mock private PayrollRecordRepository payrollRecordRepository;
    @Mock private WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    @Mock private UserRepository userRepository;
    @Mock private WagePolicyRepository wagePolicyRepository;
    @Mock private HourlyRateRuleRepository hourlyRateRuleRepository;
    @Mock private WageCalculationRepository wageCalculationRepository;

    private WageCalculationService service;

    private static final String FACTORY_ID = "F006";
    private static final Long WORKER_ID = 42L;
    private static final YearMonth MONTH = YearMonth.of(2026, 5);
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);

    private static final String STAGE_A = "去头";
    private static final String STAGE_B = "剥壳";
    private static final String STAGE_C = "分级";
    private static final BigDecimal RATE_A = new BigDecimal("1.0000");
    private static final BigDecimal RATE_B = new BigDecimal("2.5000");
    private static final BigDecimal RATE_C = new BigDecimal("4.0000");

    /** 逐工序 (正确): 100×1 + 200×2.5 + 300×4 = 1800. */
    private static final BigDecimal EXPECTED_PER_PROCESS = new BigDecimal("1800.00");
    /** 全期 600 件全按「出现最多那道工序」的 ¥4/件 = 2400 (收敛前 generatePayroll 的错法). */
    private static final BigDecimal LEGACY_MOST_FREQUENT_PROCESS = new BigDecimal("2400.00");

    private User worker;

    @BeforeEach
    void setUp() {
        service = new WageCalculationService(
                pieceRateRuleRepository,
                payrollRecordRepository,
                workerDailyEfficiencyRepository,
                userRepository);
        WagePolicyService wagePolicyService =
                new WagePolicyService(wagePolicyRepository, hourlyRateRuleRepository);
        ReflectionTestUtils.setField(service, "wagePolicyService", wagePolicyService);
        ReflectionTestUtils.setField(service, "wageCalculationRepository", wageCalculationRepository);
        ReflectionTestUtils.setField(service, "hourlyRateRuleRepository", hourlyRateRuleRepository);

        worker = new User();
        worker.setId(WORKER_ID);
        worker.setUsername("worker_42");
        worker.setFullName("张三");
        // monthlySalary / hourlyRate 默认 null —— 各用例按需单独设
    }

    // ==================== 核心: 两套引擎一致 ====================

    @Nested
    @DisplayName("两套引擎一致")
    class EngineParity {

        @Test
        @DisplayName("🔴 跨三道工序 (单价各不同) — 两个引擎必须算出同一个金额")
        void bothEnginesAgree_forWorkerAcrossThreeProcessesWithDifferentRates() {
            mockCommonLookups();
            mockPieceRatePolicy();
            mockThreeProcessEfficiencies();
            mockThreeProcessRules();

            PayrollRecord payroll = service.generatePayroll(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END);
            WageCalculation calc = service.calculateMonthly(FACTORY_ID, MONTH, WORKER_ID);

            BigDecimal payrollTotal = payroll.getTotalWage();
            BigDecimal calcTotal = calc.getTotalAmount();

            assertEquals(0, payrollTotal.compareTo(calcTotal), () ->
                    "两套引擎金额不等 — "
                            + "generatePayroll(PayrollRecord.totalWage) = ¥" + payrollTotal
                            + " vs calculateMonthly(WageCalculation.totalAmount) = ¥" + calcTotal);

            // 而且这个共同的数必须是「逐工序求和」的那个, 不是「出现最多那道工序」的那个
            assertEquals(0, EXPECTED_PER_PROCESS.compareTo(payrollTotal), () ->
                    "逐工序应得 ¥" + EXPECTED_PER_PROCESS + ", 实际 ¥" + payrollTotal);
            assertEquals(0, EXPECTED_PER_PROCESS.compareTo(calcTotal), () ->
                    "逐工序应得 ¥" + EXPECTED_PER_PROCESS + ", 实际 ¥" + calcTotal);
        }

        @Test
        @DisplayName("阴性对照: 结果不许等于「全按出现最多那道工序的单价」")
        void resultMustNotEqualLegacyMostFrequentProcessAmount() {
            mockPayrollLookups();
            mockPieceRatePolicy();
            mockThreeProcessEfficiencies();
            mockThreeProcessRules();

            PayrollRecord payroll = service.generatePayroll(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END);

            assertTrue(payroll.getPieceRateWage().compareTo(LEGACY_MOST_FREQUENT_PROCESS) != 0,
                    () -> "计件工资 ¥" + payroll.getPieceRateWage()
                            + " 等于「600 件全按分级 ¥4/件」的 ¥" + LEGACY_MOST_FREQUENT_PROCESS
                            + " —— 说明还在用出现最多那道工序的单价");
        }

        @Test
        @DisplayName("防恒真: 三道工序的单价必须互不相同, 且两种算法的期望值必须不同")
        void processRatesMustDiffer_otherwiseTheParityAssertionIsVacuous() {
            long distinctRates = List.of(RATE_A, RATE_B, RATE_C).stream()
                    .map(BigDecimal::stripTrailingZeros)
                    .distinct()
                    .count();
            assertEquals(3, distinctRates,
                    "三道工序单价必须互不相同 —— 单价相同时逐工序与单一工序算出同一个数, "
                            + "一致性断言会恒真");

            assertTrue(EXPECTED_PER_PROCESS.compareTo(LEGACY_MOST_FREQUENT_PROCESS) != 0,
                    "逐工序期望值与旧算法期望值必须不同, 否则这条测试测不出算法差异");
        }

        @Test
        @DisplayName("WageMode=HOURLY — 两个引擎一致 (工时 ¥4800 + 加班 ¥720 = ¥5520)")
        void bothEnginesAgree_forHourlyMode() {
            mockCommonLookups();
            mockPerEmployeePolicy(WageMode.HOURLY);

            // 19 天 × 8h + 1 天 24h = 176h; standard = 20 × 8 = 160h; OT = 16h
            List<WorkerDailyEfficiency> effs = new ArrayList<>();
            for (int day = 1; day <= 19; day++) {
                effs.add(newEfficiency(PERIOD_START.withDayOfMonth(day), 0, 480, null));
            }
            effs.add(newEfficiency(PERIOD_START.withDayOfMonth(20), 0, 1440, null));
            mockEfficiencies(effs);

            HourlyRateRule hourlyRule = HourlyRateRule.builder()
                    .id(50L).factoryId(FACTORY_ID).employeeId(WORKER_ID)
                    .baseHourlyRate(new BigDecimal("30.00"))
                    .overtimeMultiplier(new BigDecimal("1.5"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).isActive(true).build();
            when(hourlyRateRuleRepository.findEffectiveRules(FACTORY_ID, WORKER_ID, PERIOD_START))
                    .thenReturn(List.of(hourlyRule));

            PayrollRecord payroll = service.generatePayroll(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END);
            WageCalculation calc = service.calculateMonthly(FACTORY_ID, MONTH, WORKER_ID);

            assertEquals(0, payroll.getTotalWage().compareTo(calc.getTotalAmount()), () ->
                    "HOURLY 两套引擎金额不等 — payroll=¥" + payroll.getTotalWage()
                            + " calc=¥" + calc.getTotalAmount());
            assertEquals(0, new BigDecimal("5520.00").compareTo(payroll.getTotalWage()),
                    "160h × ¥30 + 16h × ¥30 × 1.5 = ¥5520");
            assertEquals(WageMode.HOURLY, payroll.getWageMode());
            assertEquals(0, new BigDecimal("4800.00").compareTo(payroll.getHourlyWage()),
                    "工时工资必须落到 PayrollRecord.hourlyWage 上, 不能凭空消失");
            assertEquals(0, new BigDecimal("720.00").compareTo(payroll.getOvertimeWage()));
        }

        @Test
        @DisplayName("WageMode=MIXED — 两个引擎一致 (工时 ¥240 + 计件 ¥500 = ¥740)")
        void bothEnginesAgree_forMixedMode() {
            mockCommonLookups();
            mockPerEmployeePolicy(WageMode.MIXED);
            mockEfficiencies(List.of(newEfficiency(PERIOD_START, 100, 480, STAGE_A)));

            HourlyRateRule hourlyRule = HourlyRateRule.builder()
                    .id(60L).factoryId(FACTORY_ID).employeeId(WORKER_ID)
                    .baseHourlyRate(new BigDecimal("30.00"))
                    .overtimeMultiplier(new BigDecimal("1.5"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).isActive(true).build();
            when(hourlyRateRuleRepository.findEffectiveRules(FACTORY_ID, WORKER_ID, PERIOD_START))
                    .thenReturn(List.of(hourlyRule));
            mockStageRule(STAGE_A, 1L, new BigDecimal("5.0000"));

            PayrollRecord payroll = service.generatePayroll(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END);
            WageCalculation calc = service.calculateMonthly(FACTORY_ID, MONTH, WORKER_ID);

            assertEquals(0, payroll.getTotalWage().compareTo(calc.getTotalAmount()), () ->
                    "MIXED 两套引擎金额不等 — payroll=¥" + payroll.getTotalWage()
                            + " calc=¥" + calc.getTotalAmount());
            assertEquals(0, new BigDecimal("740.00").compareTo(payroll.getTotalWage()),
                    "8h × ¥30 + 100 件 × ¥5 = ¥740");
            assertEquals(WageMode.MIXED, payroll.getWageMode());
        }

        @Test
        @DisplayName("WageMode=PIECE_RATE + 加班 — generatePayroll 独有的 User.hourlyRate 加班费不能丢")
        void bothEnginesAgree_forPieceRateWithOvertimeFromUserHourlyRate() {
            worker.setHourlyRate(new BigDecimal("20.00"));
            mockCommonLookups();
            mockPieceRatePolicy();
            // 1 天 10h (600 min); standard = 1 × 8h → OT = 2h
            mockEfficiencies(List.of(newEfficiency(PERIOD_START, 100, 600, STAGE_A)));
            mockStageRule(STAGE_A, 1L, new BigDecimal("5.0000"));

            PayrollRecord payroll = service.generatePayroll(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END);
            WageCalculation calc = service.calculateMonthly(FACTORY_ID, MONTH, WORKER_ID);

            assertEquals(0, new BigDecimal("60.00").compareTo(payroll.getOvertimeWage()),
                    "2h × ¥20 × 1.5 = ¥60 —— 计件工人的加班费只有旧 generatePayroll 发得出来, 收敛时保留");
            assertEquals(0, payroll.getTotalWage().compareTo(calc.getTotalAmount()), () ->
                    "PIECE_RATE+OT 两套引擎金额不等 — payroll=¥" + payroll.getTotalWage()
                            + " calc=¥" + calc.getTotalAmount());
            assertEquals(0, new BigDecimal("560.00").compareTo(payroll.getTotalWage()),
                    "计件 ¥500 + 加班 ¥60 = ¥560");
        }

        @Test
        @DisplayName("基本工资是 PayrollRecord 独有的一项: payroll.totalWage = calc.totalAmount + baseSalary")
        void baseSalaryIsPayrollOnly_andReconciles() {
            worker.setMonthlySalary(new BigDecimal("3100.00"));
            mockCommonLookups();
            mockPieceRatePolicy();
            mockThreeProcessEfficiencies();
            mockThreeProcessRules();

            PayrollRecord payroll = service.generatePayroll(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END);
            WageCalculation calc = service.calculateMonthly(FACTORY_ID, MONTH, WORKER_ID);

            // 5/1~5/31 = 31 天, 五月 31 天 → 全额
            assertEquals(0, new BigDecimal("3100.00").compareTo(payroll.getBaseSalary()));
            assertEquals(0,
                    payroll.getTotalWage().compareTo(calc.getTotalAmount().add(payroll.getBaseSalary())),
                    () -> "对账不上 — payroll.totalWage=¥" + payroll.getTotalWage()
                            + ", calc.totalAmount=¥" + calc.getTotalAmount()
                            + ", baseSalary=¥" + payroll.getBaseSalary());
        }
    }

    // ==================== 历史数据处置 ====================

    @Nested
    @DisplayName("历史数据: PAID/APPROVED 冻结, PENDING 可重算")
    class HistoricalRecords {

        @Test
        @DisplayName("PAID 冻结: 重算被拒, 金额不变, 一次 save 都不发生")
        void paidRecordIsFrozen() {
            PayrollRecord paid = existingPayroll(PAYROLL_ID, "PAID", LEGACY_MOST_FREQUENT_PROCESS);
            when(payrollRecordRepository.findById(PAYROLL_ID)).thenReturn(Optional.of(paid));
            // lenient: 闸在时这条用不到。放着是为了让「拆掉闸」的变异能一路跑通 —
            // 那时红出来的才是「该抛的没抛」, 而不是「工人查不到」这种半路的别的错。
            lenient().when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.recalculatePayroll(FACTORY_ID, PAYROLL_ID, OPERATOR_ID));

            assertTrue(ex.getMessage().contains("PAID"), () -> "报错该点名状态: " + ex.getMessage());
            assertEquals(0, LEGACY_MOST_FREQUENT_PROCESS.compareTo(paid.getTotalWage()),
                    "已发放金额必须原封不动");
            verify(payrollRecordRepository, never()).save(any(PayrollRecord.class));
        }

        @Test
        @DisplayName("APPROVED 冻结: 重算被拒, 金额不变, 一次 save 都不发生")
        void approvedRecordIsFrozen() {
            PayrollRecord approved = existingPayroll(PAYROLL_ID, "APPROVED", LEGACY_MOST_FREQUENT_PROCESS);
            when(payrollRecordRepository.findById(PAYROLL_ID)).thenReturn(Optional.of(approved));
            lenient().when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.recalculatePayroll(FACTORY_ID, PAYROLL_ID, OPERATOR_ID));

            assertTrue(ex.getMessage().contains("APPROVED"), () -> "报错该点名状态: " + ex.getMessage());
            assertEquals(0, LEGACY_MOST_FREQUENT_PROCESS.compareTo(approved.getTotalWage()),
                    "已审核金额必须原封不动");
            verify(payrollRecordRepository, never()).save(any(PayrollRecord.class));
        }

        @Test
        @DisplayName("PENDING 草稿: 重算后金额确实变了, 且变更前后都留痕给 HR 看")
        void pendingDraftIsRecalculated_andTheChangeIsVisibleToHr() {
            PayrollRecord pending = existingPayroll(PAYROLL_ID, "PENDING", LEGACY_MOST_FREQUENT_PROCESS);
            when(payrollRecordRepository.findById(PAYROLL_ID)).thenReturn(Optional.of(pending));
            when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
            when(payrollRecordRepository.save(any(PayrollRecord.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            mockPieceRatePolicy();
            mockThreeProcessEfficiencies();
            mockThreeProcessRules();

            PayrollRecord result = service.recalculatePayroll(FACTORY_ID, PAYROLL_ID, OPERATOR_ID);

            assertEquals(0, EXPECTED_PER_PROCESS.compareTo(result.getTotalWage()),
                    "草稿应被重算成逐工序的 ¥1800.00");
            assertEquals(0, LEGACY_MOST_FREQUENT_PROCESS.compareTo(result.getPreviousTotalWage()),
                    "变更前的金额必须留下来");
            assertEquals(OPERATOR_ID, result.getRecalculatedBy(), "谁重算的要留下来");
            assertNotNull(result.getRecalculatedAt(), "什么时候重算的要留下来");
            assertEquals(1, result.getRecalcCount());
            assertNotNull(result.getRecalcLog(), "重算日志不能为空 —— 不许悄悄改数");
            assertTrue(result.getRecalcLog().contains("2400.00")
                            && result.getRecalcLog().contains("1800.00"),
                    () -> "重算日志要能看到变更前后两个数, 实际: " + result.getRecalcLog());
        }

        @Test
        @DisplayName("⛔ 重算是显式触发: generatePayroll 撞到已有记录直接拒绝, 不会顺手改草稿")
        void generatePayrollNeverSilentlyRecalculates() {
            when(payrollRecordRepository.existsByFactoryIdAndWorkerIdAndPeriodStartAndPeriodEnd(
                    FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END)).thenReturn(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.generatePayroll(FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END));

            assertTrue(ex.getMessage().contains("已存在"), () -> ex.getMessage());
            verify(payrollRecordRepository, never()).save(any(PayrollRecord.class));
        }

        @Test
        @DisplayName("批量重算: 冻结的逐条跳过并计数, 只有草稿被改")
        void batchRecalculation_skipsFrozenAndCountsThem() {
            PayrollRecord pending = existingPayroll(1L, "PENDING", LEGACY_MOST_FREQUENT_PROCESS);
            PayrollRecord approved = existingPayroll(2L, "APPROVED", LEGACY_MOST_FREQUENT_PROCESS);
            PayrollRecord paid = existingPayroll(3L, "PAID", LEGACY_MOST_FREQUENT_PROCESS);

            when(payrollRecordRepository.findByPeriod(FACTORY_ID, PERIOD_START, PERIOD_END))
                    .thenReturn(List.of(pending, approved, paid));
            when(payrollRecordRepository.findById(1L)).thenReturn(Optional.of(pending));
            when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
            when(payrollRecordRepository.save(any(PayrollRecord.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            mockPieceRatePolicy();
            mockThreeProcessEfficiencies();
            mockThreeProcessRules();

            Map<String, Object> summary = service.recalculatePendingPayrolls(
                    FACTORY_ID, PERIOD_START, PERIOD_END, OPERATOR_ID);

            assertEquals(1, summary.get("recalculatedCount"));
            assertEquals(1, summary.get("changedCount"));
            assertEquals(1, summary.get("frozenApprovedCount"));
            assertEquals(1, summary.get("frozenPaidCount"));
            assertEquals(0, summary.get("failedCount"));

            assertEquals(0, LEGACY_MOST_FREQUENT_PROCESS.compareTo(approved.getTotalWage()),
                    "已审核记录不许被批量重算碰到");
            assertEquals(0, LEGACY_MOST_FREQUENT_PROCESS.compareTo(paid.getTotalWage()),
                    "已发放记录不许被批量重算碰到");
            assertEquals(0, EXPECTED_PER_PROCESS.compareTo(pending.getTotalWage()),
                    "草稿应被重算");
        }
    }

    // ==================== helpers ====================

    private static final Long PAYROLL_ID = 777L;
    private static final Long OPERATOR_ID = 9L;

    /** 只跑 generatePayroll 时用. */
    private void mockPayrollLookups() {
        when(userRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        when(payrollRecordRepository.existsByFactoryIdAndWorkerIdAndPeriodStartAndPeriodEnd(
                FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END)).thenReturn(false);
        when(payrollRecordRepository.save(any(PayrollRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** 两个引擎都跑时用. */
    private void mockCommonLookups() {
        mockPayrollLookups();
        when(wageCalculationRepository.findByFactoryIdAndEmployeeIdAndPeriodMonth(
                anyString(), anyLong(), any(LocalDate.class))).thenReturn(Optional.empty());
        when(wageCalculationRepository.save(any(WageCalculation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void mockPieceRatePolicy() {
        when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(FACTORY_ID, WORKER_ID))
                .thenReturn(Optional.empty());
        when(wagePolicyRepository.findFactoryDefault(FACTORY_ID)).thenReturn(Optional.of(
                WagePolicy.builder().factoryId(FACTORY_ID).mode(WageMode.PIECE_RATE)
                        .isActive(true).build()));
    }

    private void mockPerEmployeePolicy(WageMode mode) {
        when(wagePolicyRepository.findByFactoryIdAndEmployeeIdAndIsActiveTrue(FACTORY_ID, WORKER_ID))
                .thenReturn(Optional.of(WagePolicy.builder()
                        .factoryId(FACTORY_ID).employeeId(WORKER_ID).mode(mode).isActive(true).build()));
    }

    private void mockEfficiencies(List<WorkerDailyEfficiency> effs) {
        when(workerDailyEfficiencyRepository.findByWorkerAndDateRange(
                FACTORY_ID, WORKER_ID, PERIOD_START, PERIOD_END)).thenReturn(effs);
    }

    /** 5 天: 去头 1 天 100 件 / 剥壳 1 天 200 件 / 分级 3 天 共 300 件 (分级出现次数最多). */
    private void mockThreeProcessEfficiencies() {
        mockEfficiencies(List.of(
                newEfficiency(PERIOD_START.withDayOfMonth(1), 100, 480, STAGE_A),
                newEfficiency(PERIOD_START.withDayOfMonth(2), 200, 480, STAGE_B),
                newEfficiency(PERIOD_START.withDayOfMonth(3), 100, 480, STAGE_C),
                newEfficiency(PERIOD_START.withDayOfMonth(4), 100, 480, STAGE_C),
                newEfficiency(PERIOD_START.withDayOfMonth(5), 100, 480, STAGE_C)));
    }

    private void mockThreeProcessRules() {
        mockStageRule(STAGE_A, 101L, RATE_A);
        mockStageRule(STAGE_B, 102L, RATE_B);
        mockStageRule(STAGE_C, 103L, RATE_C);
    }

    private void mockStageRule(String stage, Long ruleId, BigDecimal rate) {
        PieceRateRule rule = PieceRateRule.builder()
                .id(ruleId).factoryId(FACTORY_ID).processStageType(stage)
                .tier1Threshold(0).tier1Rate(rate).maxTierCount(1).isActive(true)
                .build();
        when(pieceRateRuleRepository.findEffectiveRulesByProcessStage(
                eq(FACTORY_ID), eq(stage), any(LocalDate.class))).thenReturn(List.of(rule));
    }

    private WorkerDailyEfficiency newEfficiency(LocalDate date, int pieceCount,
            int workMinutes, String stage) {
        return WorkerDailyEfficiency.builder()
                .factoryId(FACTORY_ID)
                .workerId(WORKER_ID)
                .workerName("张三")
                .workDate(date)
                .processStageType(stage)
                .productTypeId(stage)
                .totalPieceCount(pieceCount)
                .qualifiedCount(pieceCount)
                .defectCount(0)
                .effectiveWorkMinutes(workMinutes)
                .build();
    }

    private PayrollRecord existingPayroll(Long id, String status, BigDecimal totalWage) {
        return PayrollRecord.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .workerId(WORKER_ID)
                .workerName("张三")
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .periodType("MONTHLY")
                .totalPieceCount(600)
                .pieceRateWage(totalWage)
                .baseSalary(BigDecimal.ZERO)
                .overtimeWage(BigDecimal.ZERO)
                .overtimeHours(BigDecimal.ZERO)
                .bonusAmount(BigDecimal.ZERO)
                .deductionAmount(BigDecimal.ZERO)
                .totalWage(totalWage)
                .status(status)
                .build();
    }
}
