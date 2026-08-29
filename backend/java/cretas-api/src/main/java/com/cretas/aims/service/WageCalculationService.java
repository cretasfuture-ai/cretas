package com.cretas.aims.service;

import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.PieceRateRule;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.PieceRateRuleRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工资计算引擎服务
 * 负责计件工资计算、日效率统计、工资单生成
 *
 * 主要功能:
 * - 阶梯计件工资计算
 * - 工人日效率记录和统计
 * - 工资单生成和汇总
 * - 人力成本分析
 *
 * 效率评级标准:
 * - A: 优秀 (效率 >= 120%)
 * - B: 良好 (效率 >= 100%)
 * - C: 合格 (效率 >= 80%)
 * - D: 待提升 (效率 < 80%)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WageCalculationService {

    private final PieceRateRuleRepository pieceRateRuleRepository;
    private final PayrollRecordRepository payrollRecordRepository;
    private final WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    private final UserRepository userRepository;

    /**
     * Sprint 6 Track W4-B: optional injection (向后兼容 PR #57 E — 这些 bean 是 Sprint 6 新增).
     * 通过 @Autowired(required=false) 而非构造函数, 让 PR #57 E unit test 仍然 pass.
     * 生产环境 Spring 自动注入; 测试用 ReflectionTestUtils.setField 注入 mock.
     */
    @Autowired(required = false)
    private WagePolicyService wagePolicyService;

    @Autowired(required = false)
    private WageCalculationRepository wageCalculationRepository;

    /** Sprint 6 Track W4-B: WageMonthlyScheduler 调用 calculateMonthlyForFactory 时的 worker query repo. */
    @Autowired(required = false)
    private com.cretas.aims.repository.HourlyRateRuleRepository hourlyRateRuleRepository;

    // ==================== 常量定义 ====================

    /** 工资记录状态: 待审核 */
    public static final String PAYROLL_STATUS_PENDING = "PENDING";
    /** 工资记录状态: 已审核 */
    public static final String PAYROLL_STATUS_APPROVED = "APPROVED";
    /** 工资记录状态: 已发放 */
    public static final String PAYROLL_STATUS_PAID = "PAID";

    /** 结算周期: 日结 */
    public static final String PERIOD_TYPE_DAILY = "DAILY";
    /** 结算周期: 周结 */
    public static final String PERIOD_TYPE_WEEKLY = "WEEKLY";
    /** 结算周期: 月结 */
    public static final String PERIOD_TYPE_MONTHLY = "MONTHLY";

    /** 效率趋势: 上升 */
    public static final String TREND_UP = "UP";
    /** 效率趋势: 下降 */
    public static final String TREND_DOWN = "DOWN";
    /** 效率趋势: 稳定 */
    public static final String TREND_STABLE = "STABLE";

    /** 标准工时 (小时/天) */
    private static final BigDecimal STANDARD_WORK_HOURS_PER_DAY = new BigDecimal("8");
    /** 加班工资倍率 */
    private static final BigDecimal OVERTIME_RATE_MULTIPLIER = new BigDecimal("1.5");
    /** 周末加班倍率 */
    private static final BigDecimal WEEKEND_OVERTIME_MULTIPLIER = new BigDecimal("2.0");

    // ==================== 计件工资计算 ====================

    /**
     * 计算计件工资
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param pieceCount 完成件数
     * @param processStageType 工序类型
     * @param date 日期
     * @return 计件工资金额
     */
    public BigDecimal calculatePieceRateWage(String factoryId, Long workerId,
            int pieceCount, String processStageType, LocalDate date) {

        if (pieceCount <= 0) {
            log.debug("计件数为0，返回0工资: factoryId={}, workerId={}", factoryId, workerId);
            return BigDecimal.ZERO;
        }

        // 1. 查找适用的计件规则 (按优先级)
        Optional<PieceRateRule> ruleOpt = findApplicableRule(factoryId, processStageType, null, date);

        if (ruleOpt.isEmpty()) {
            log.warn("未找到适用的计件规则: factoryId={}, processStageType={}, date={}",
                    factoryId, processStageType, date);
            return BigDecimal.ZERO;
        }

        PieceRateRule rule = ruleOpt.get();
        log.debug("使用计件规则: id={}, name={}, tier1Rate={}",
                rule.getId(), rule.getName(), rule.getTier1Rate());

        // 2. 使用规则的 calculateWage 方法计算阶梯工资
        BigDecimal wage = rule.calculateWage(pieceCount);

        log.info("计件工资计算完成: factoryId={}, workerId={}, pieceCount={}, wage={}",
                factoryId, workerId, pieceCount, wage);

        return wage;
    }

    /**
     * 计算计件工资 (带产品类型)
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param pieceCount 完成件数
     * @param processStageType 工序类型
     * @param productTypeId 产品类型ID
     * @param date 日期
     * @return 计件工资金额
     */
    public BigDecimal calculatePieceRateWage(String factoryId, Long workerId,
            int pieceCount, String processStageType, String productTypeId, LocalDate date) {

        if (pieceCount <= 0) {
            return BigDecimal.ZERO;
        }

        Optional<PieceRateRule> ruleOpt = findApplicableRule(factoryId, processStageType, productTypeId, date);

        if (ruleOpt.isEmpty()) {
            log.warn("未找到适用的计件规则: factoryId={}, processStageType={}, productTypeId={}",
                    factoryId, processStageType, productTypeId);
            return BigDecimal.ZERO;
        }

        return ruleOpt.get().calculateWage(pieceCount);
    }

    /**
     * 只读: 查某道工序在某天适用的计件规则 —— 给「我的工资」屏展示单价档位用。
     *
     * <p>⛔ 这里<b>只是委托</b>给下面那个 private 的 {@link #findApplicableRule}, 不是第二份实现。
     * 展示给工人看的单价, 必须和结算时真正套用的规则是<b>同一个来源</b>;
     * 在 controller 里另写一遍 {@code pieceRateRuleRepository} 查询就会漂 ——
     * 屏上写着 ¥0.8/件、结算按 ¥0.6 发, 那比不显示单价更糟。
     *
     * @see com.cretas.aims.controller.MyWageController
     */
    @Transactional(readOnly = true)
    public Optional<PieceRateRule> findEffectivePieceRateRule(String factoryId,
            String processStageType, LocalDate date) {
        return findApplicableRule(factoryId, processStageType, null, date);
    }

    /**
     * 查找适用的计件规则
     */
    private Optional<PieceRateRule> findApplicableRule(String factoryId,
            String processStageType, String productTypeId, LocalDate date) {

        // 优先查找完全匹配的规则
        if (processStageType != null && productTypeId != null) {
            Optional<PieceRateRule> exactMatch = pieceRateRuleRepository
                    .findBestMatchingRule(factoryId, processStageType, productTypeId, date);
            if (exactMatch.isPresent()) {
                return exactMatch;
            }
        }

        // 其次查找工序匹配的规则
        if (processStageType != null) {
            List<PieceRateRule> processRules = pieceRateRuleRepository
                    .findEffectiveRulesByProcessStage(factoryId, processStageType, date);
            if (!processRules.isEmpty()) {
                return Optional.of(processRules.get(0));
            }
        }

        // 最后查找工厂通用规则
        List<PieceRateRule> factoryRules = pieceRateRuleRepository.findEffectiveRules(factoryId, date);
        return factoryRules.isEmpty() ? Optional.empty() : Optional.of(factoryRules.get(0));
    }

    // ==================== 日效率记录 ====================

    /**
     * 记录工人日效率
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param workDate 工作日期
     * @param pieceCount 完成件数
     * @param workMinutes 工作时长(分钟)
     * @param processStageType 工序类型
     * @return 效率记录
     */
    @Transactional
    public WorkerDailyEfficiency recordDailyEfficiency(String factoryId, Long workerId,
            LocalDate workDate, int pieceCount, int workMinutes, String processStageType) {

        log.info("记录日效率: factoryId={}, workerId={}, date={}, pieces={}, minutes={}",
                factoryId, workerId, workDate, pieceCount, workMinutes);

        // 1. 获取工人信息
        User worker = userRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("工人不存在: " + workerId));

        // 2. 查找或创建当日效率记录
        WorkerDailyEfficiency efficiency = workerDailyEfficiencyRepository
                .findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                        factoryId, workerId, workDate, processStageType)
                .orElse(WorkerDailyEfficiency.builder()
                        .factoryId(factoryId)
                        .workerId(workerId)
                        .workerName(worker.getFullName())
                        .workDate(workDate)
                        .processStageType(processStageType)
                        .totalPieceCount(0)
                        .effectiveWorkMinutes(0)
                        .qualifiedCount(0)
                        .defectCount(0)
                        .build());

        // 3. 更新计件数据
        int newPieceCount = (efficiency.getTotalPieceCount() != null ? efficiency.getTotalPieceCount() : 0) + pieceCount;
        int newWorkMinutes = (efficiency.getEffectiveWorkMinutes() != null ? efficiency.getEffectiveWorkMinutes() : 0) + workMinutes;

        efficiency.setTotalPieceCount(newPieceCount);
        efficiency.setEffectiveWorkMinutes(newWorkMinutes);

        // 4. 假设全部合格 (可以后续调整)
        efficiency.setQualifiedCount(newPieceCount);

        // 5. 计算效率指标 (会在 @PrePersist/@PreUpdate 中自动计算)
        // piecesPerHour, averageTimePerPiece 等

        // 6. 计算效率趋势
        calculateEfficiencyTrend(efficiency, factoryId, workerId, workDate);

        // 7. 获取标准效率并设置对比基准
        setStandardEfficiency(efficiency, factoryId, processStageType);

        // 8. 保存并返回
        efficiency = workerDailyEfficiencyRepository.save(efficiency);
        log.info("日效率记录完成: id={}, piecesPerHour={}", efficiency.getId(), efficiency.getPiecesPerHour());

        return efficiency;
    }

    /**
     * 更新效率记录 (追加计件)
     */
    @Transactional
    public WorkerDailyEfficiency updateDailyEfficiency(Long efficiencyId,
            int additionalPieces, int additionalMinutes) {

        WorkerDailyEfficiency efficiency = workerDailyEfficiencyRepository.findById(efficiencyId)
                .orElseThrow(() -> new RuntimeException("效率记录不存在: " + efficiencyId));

        int newPieceCount = (efficiency.getTotalPieceCount() != null ? efficiency.getTotalPieceCount() : 0) + additionalPieces;
        int newWorkMinutes = (efficiency.getEffectiveWorkMinutes() != null ? efficiency.getEffectiveWorkMinutes() : 0) + additionalMinutes;

        efficiency.setTotalPieceCount(newPieceCount);
        efficiency.setEffectiveWorkMinutes(newWorkMinutes);
        efficiency.setQualifiedCount(newPieceCount);

        return workerDailyEfficiencyRepository.save(efficiency);
    }

    /**
     * 计算效率趋势
     */
    private void calculateEfficiencyTrend(WorkerDailyEfficiency current,
            String factoryId, Long workerId, LocalDate workDate) {

        // 获取前一天的效率
        LocalDate previousDate = workDate.minusDays(1);
        Optional<WorkerDailyEfficiency> previousOpt = workerDailyEfficiencyRepository
                .findByFactoryIdAndWorkerIdAndWorkDate(factoryId, workerId, previousDate);

        if (previousOpt.isEmpty() || previousOpt.get().getPiecesPerHour() == null) {
            current.setEfficiencyTrend(TREND_STABLE);
            return;
        }

        BigDecimal previousRate = previousOpt.get().getPiecesPerHour();
        BigDecimal currentRate = current.getPiecesPerHour();

        if (currentRate == null) {
            current.setEfficiencyTrend(TREND_STABLE);
            return;
        }

        // 计算变化百分比
        BigDecimal changePercent = currentRate.subtract(previousRate)
                .divide(previousRate, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (changePercent.compareTo(BigDecimal.valueOf(5)) > 0) {
            current.setEfficiencyTrend(TREND_UP);
        } else if (changePercent.compareTo(BigDecimal.valueOf(-5)) < 0) {
            current.setEfficiencyTrend(TREND_DOWN);
        } else {
            current.setEfficiencyTrend(TREND_STABLE);
        }
    }

    /**
     * 设置标准效率
     */
    private void setStandardEfficiency(WorkerDailyEfficiency efficiency,
            String factoryId, String processStageType) {

        // 获取工厂该工序的平均效率作为标准
        BigDecimal avgEfficiency = workerDailyEfficiencyRepository
                .avgPiecesPerHourByFactoryAndDate(factoryId, efficiency.getWorkDate());

        if (avgEfficiency != null) {
            efficiency.setStandardPiecesPerHour(avgEfficiency);
        } else {
            // 默认标准: 60件/小时
            efficiency.setStandardPiecesPerHour(new BigDecimal("60"));
        }
    }

    // ==================== 统一工资算法 (唯一来源) ====================

    /**
     * 分组键: 没有工序类型的效率行.
     *
     * <p>这些行的件数按<b>工厂通用规则</b>计价 —— 收敛前 {@code generatePayroll} 会
     * 把它们算进去 (findApplicableRule 的第三层兜底), 而 {@code calculateMonthly}
     * 直接把它们从分组里 filter 掉, 件数计进 totalPieceCount 却一分钱不发。
     * 用一个哨兵键把这一组单独留出来, 两边行为一致。
     */
    private static final String NO_PROCESS_STAGE = "\0__NO_PROCESS_STAGE__";

    /**
     * 一次工资计算的全部中间量 —— {@link #generatePayroll} 与 {@link #calculateMonthly}
     * 的<b>共同产物</b>.
     *
     * <p>两个入口不再各自算钱: 都调 {@link #computeWageBreakdown}, 拿到同一个 breakdown,
     * 各自把它<b>落到自己的载体</b>上 (PayrollRecord 走审批/支付生命周期,
     * WageCalculation 只是计算明细)。
     */
    @Getter
    @Builder
    public static class WageBreakdown {
        /** 解析出的工资模式. */
        private final WageMode mode;
        /** 有效率记录的天数 (标准工时 = workDays × 8h). */
        private final int workDays;
        /** 有效工作分钟数合计. */
        private final int totalWorkMinutes;
        /** 计件数合计 (含无工序类型的行). */
        private final int totalPieceCount;
        /** 总工时 (小时). */
        private final BigDecimal totalHours;
        /** 常规工时 = totalHours - overtimeHours. */
        private final BigDecimal regularHours;
        /** 加班工时. */
        private final BigDecimal overtimeHours;
        /** 常规工时工资 (PIECE_RATE 模式恒为 0). */
        private final BigDecimal hourlyAmount;
        /** 加班工资. */
        private final BigDecimal overtimeAmount;
        /** 计件工资 (逐工序分别套各自规则再求和). */
        private final BigDecimal pieceRateAmount;
        /** 平均效率 (件/小时). */
        private final BigDecimal averageEfficiency;
        /** 命中的时薪规则 ID. */
        private final Long hourlyRuleId;
        /** 命中的第一条计件规则 ID (audit linkno). */
        private final Long pieceRuleId;
        /** 缺规则等异常说明; 无异常为 null. */
        private final String notes;

        /**
         * 该周期「干活挣到的钱」.
         *
         * <p>与 {@link WageCalculation#getTotalAmount()} <b>同一个口径函数</b>
         * ({@link WageCalculation#totalOf}) —— 不是两份实现。
         */
        public BigDecimal getWorkWage() {
            return WageCalculation.totalOf(mode, hourlyAmount, overtimeAmount, pieceRateAmount);
        }
    }

    /**
     * 统一工资算法: 算一个工人在一段周期内挣到的钱, 拆成各分项.
     *
     * <p><b>计件</b>: 按 {@code processStageType} <b>逐工序</b>汇总件数, 每道工序各自
     * 查 {@link PieceRateRule} 套自己的阶梯, 再求和。
     * ⛔ 不是「全期总件数 × 出现最多那道工序的单价」—— 工人切三道工序时那个算法是错的。
     *
     * <p><b>计时</b>: 常规工时 × {@link HourlyRateRule#getBaseHourlyRate()},
     * 仅 HOURLY / MIXED 模式。
     *
     * <p><b>加班</b>: 加班工时 × 费率 × 倍率。费率优先取 HourlyRateRule,
     * 取不到时<b>兜底取 {@code User.hourlyRate} × 1.5</b> —— 这是收敛前
     * {@code generatePayroll} 独有的能力 (计件工人的加班费只有那一侧发得出来),
     * 收敛时不能丢, 所以三个模式都适用。
     *
     * @param factoryId   工厂ID
     * @param workerId    工人ID
     * @param periodStart 周期开始 (含)
     * @param periodEnd   周期结束 (含)
     * @param worker      工人实体 (可为 null; 仅用于加班费率兜底)
     * @return 各分项拆开的计算结果
     */
    public WageBreakdown computeWageBreakdown(String factoryId, Long workerId,
            LocalDate periodStart, LocalDate periodEnd, User worker) {

        List<WorkerDailyEfficiency> efficiencies = workerDailyEfficiencyRepository
                .findByWorkerAndDateRange(factoryId, workerId, periodStart, periodEnd);

        StringBuilder notesBuilder = new StringBuilder();

        // 1. 解析 mode (per-employee policy → factory default → PIECE_RATE 兜底)
        WageMode mode;
        if (wagePolicyService != null) {
            mode = wagePolicyService.resolveModeForEmployee(factoryId, workerId);
        } else {
            // WagePolicyService 是 @Autowired(required=false); 生产环境恒注入。
            // 未注入时等价于「没有配置任何 policy」, 与 resolveModeForEmployee 的兜底同一档。
            mode = WageMode.PIECE_RATE;
            log.warn("[computeWageBreakdown] WagePolicyService 未注入, 按 PIECE_RATE 计算: " +
                    "factoryId={}, workerId={}", factoryId, workerId);
            notesBuilder.append("WagePolicyService 未注入, 按 PIECE_RATE 计算; ");
        }

        // 2. 工时 / 件数汇总
        int totalWorkMinutes = efficiencies.stream()
                .mapToInt(e -> e.getEffectiveWorkMinutes() != null ? e.getEffectiveWorkMinutes() : 0)
                .sum();
        int totalPieceCount = efficiencies.stream()
                .mapToInt(e -> e.getTotalPieceCount() != null ? e.getTotalPieceCount() : 0)
                .sum();
        BigDecimal totalHours = BigDecimal.valueOf(totalWorkMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        int workDays = efficiencies.size();
        BigDecimal standardHours = STANDARD_WORK_HOURS_PER_DAY.multiply(BigDecimal.valueOf(workDays));
        BigDecimal overtimeHours = totalHours.compareTo(standardHours) > 0
                ? totalHours.subtract(standardHours).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal regularHours = totalHours.subtract(overtimeHours).setScale(2, RoundingMode.HALF_UP);

        BigDecimal hourlyAmount = BigDecimal.ZERO;
        BigDecimal overtimeAmount = BigDecimal.ZERO;
        BigDecimal pieceRateAmount = BigDecimal.ZERO;
        Long hourlyRuleId = null;
        Long pieceRuleId = null;

        boolean needHourly = (mode == WageMode.HOURLY || mode == WageMode.MIXED);
        boolean needPieceRate = (mode == WageMode.PIECE_RATE || mode == WageMode.MIXED);

        // 3. 加班费率来源: HourlyRateRule 优先, 取不到时兜底 User.hourlyRate
        BigDecimal overtimeRate = null;
        BigDecimal overtimeMultiplier = OVERTIME_RATE_MULTIPLIER;

        // 3a. HOURLY / MIXED: 常规工时工资
        if (needHourly) {
            Optional<HourlyRateRule> hRuleOpt = wagePolicyService != null
                    ? wagePolicyService.findEffectiveHourlyRate(factoryId, workerId, periodStart)
                    : Optional.empty();
            if (hRuleOpt.isPresent()) {
                HourlyRateRule hRule = hRuleOpt.get();
                hourlyRuleId = hRule.getId();
                hourlyAmount = regularHours.multiply(hRule.getBaseHourlyRate())
                        .setScale(2, RoundingMode.HALF_UP);
                overtimeRate = hRule.getBaseHourlyRate();
                if (hRule.getOvertimeMultiplier() != null) {
                    overtimeMultiplier = hRule.getOvertimeMultiplier();
                }
            } else {
                log.warn("[computeWageBreakdown] factory={}, worker={}, {}~{}: 缺 HourlyRateRule, " +
                        "hourlyAmount=0", factoryId, workerId, periodStart, periodEnd);
                notesBuilder.append("缺 HourlyRateRule(").append(periodStart).append("); ");
            }
        }

        // 3b. 加班工资 (三个模式都适用 — 收敛前 generatePayroll 独有能力, 不能丢)
        if (overtimeRate == null && worker != null && worker.getHourlyRate() != null) {
            overtimeRate = worker.getHourlyRate();
            overtimeMultiplier = OVERTIME_RATE_MULTIPLIER;
        }
        if (overtimeRate != null && overtimeHours.signum() > 0) {
            overtimeAmount = overtimeHours.multiply(overtimeRate)
                    .multiply(overtimeMultiplier)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 3c. PIECE_RATE / MIXED: 逐工序计件
        if (needPieceRate && totalPieceCount > 0) {
            // LinkedHashMap: 保持遍历顺序确定 (pieceRuleId 取第一条命中规则, 不能随 hash 漂)
            Map<String, Integer> piecesByStage = new LinkedHashMap<>();
            for (WorkerDailyEfficiency e : efficiencies) {
                Integer pieces = e.getTotalPieceCount();
                if (pieces == null || pieces <= 0) {
                    continue;
                }
                String key = e.getProcessStageType() != null ? e.getProcessStageType() : NO_PROCESS_STAGE;
                piecesByStage.merge(key, pieces, Integer::sum);
            }

            BigDecimal accumPiece = BigDecimal.ZERO;
            for (Map.Entry<String, Integer> entry : piecesByStage.entrySet()) {
                String stage = NO_PROCESS_STAGE.equals(entry.getKey()) ? null : entry.getKey();
                int pieces = entry.getValue();
                Optional<PieceRateRule> ruleOpt = findApplicableRule(factoryId, stage, null, periodEnd);
                if (ruleOpt.isPresent()) {
                    PieceRateRule rule = ruleOpt.get();
                    accumPiece = accumPiece.add(rule.calculateWage(pieces));
                    if (pieceRuleId == null) {
                        pieceRuleId = rule.getId();
                    }
                } else {
                    log.warn("[computeWageBreakdown] factory={}, worker={}, stage={}: 缺 PieceRateRule, skip",
                            factoryId, workerId, stage);
                    notesBuilder.append("缺 PieceRateRule(")
                            .append(stage != null ? stage : "无工序类型")
                            .append("); ");
                }
            }
            if (!piecesByStage.isEmpty()) {
                pieceRateAmount = accumPiece.setScale(2, RoundingMode.HALF_UP);
            }
        }

        // 4. 平均效率 (件/小时)
        BigDecimal averageEfficiency = BigDecimal.ZERO;
        if (totalWorkMinutes > 0) {
            averageEfficiency = BigDecimal.valueOf(totalPieceCount * 60.0 / totalWorkMinutes)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return WageBreakdown.builder()
                .mode(mode)
                .workDays(workDays)
                .totalWorkMinutes(totalWorkMinutes)
                .totalPieceCount(totalPieceCount)
                .totalHours(totalHours)
                .regularHours(regularHours)
                .overtimeHours(overtimeHours)
                .hourlyAmount(hourlyAmount)
                .overtimeAmount(overtimeAmount)
                .pieceRateAmount(pieceRateAmount)
                .averageEfficiency(averageEfficiency)
                .hourlyRuleId(hourlyRuleId)
                .pieceRuleId(pieceRuleId)
                .notes(notesBuilder.length() > 0 ? notesBuilder.toString().trim() : null)
                .build();
    }

    /**
     * 把 breakdown 落到 PayrollRecord 上 (算法产出 → 审批载体).
     *
     * <p>只动<b>算法算出来的</b>那些字段。status / approvedBy / paidAt 等生命周期字段不碰。
     * baseSalary 是 PayrollRecord 独有的一项 (WageCalculation 没有这个概念), 在这里按天数摊。
     */
    private void applyBreakdown(PayrollRecord payroll, User worker, WageBreakdown bd,
            LocalDate periodStart, LocalDate periodEnd) {

        payroll.setWageMode(bd.getMode());
        payroll.setTotalPieceCount(bd.getTotalPieceCount());
        payroll.setPieceRateWage(bd.getPieceRateAmount());
        payroll.setPieceRuleId(bd.getPieceRuleId());
        payroll.setHourlyWage(bd.getHourlyAmount());
        payroll.setHourlyRuleId(bd.getHourlyRuleId());
        payroll.setOvertimeWage(bd.getOvertimeAmount());
        payroll.setOvertimeHours(bd.getOvertimeHours());
        payroll.setBaseSalary(prorateBaseSalary(worker, periodStart, periodEnd));
        payroll.setAverageEfficiency(bd.getAverageEfficiency());
        payroll.setTotalWorkHours(bd.getTotalHours());
        payroll.setEfficiencyRating(determineEfficiencyRating(
                bd.getAverageEfficiency(), payroll.getFactoryId(), periodStart, periodEnd));
        payroll.setNotes(bd.getNotes());

        // @PrePersist/@PreUpdate 也会算一次; 这里先算是为了调用方能立刻读到新的 totalWage
        // (重算要拿它跟旧值比), calculateTotalWage 幂等。
        payroll.calculateTotalWage();
    }

    /**
     * 基本工资按周期天数摊 (PayrollRecord 独有, WageCalculation 无此概念).
     */
    private BigDecimal prorateBaseSalary(User worker, LocalDate periodStart, LocalDate periodEnd) {
        BigDecimal monthlySalary = worker != null ? worker.getMonthlySalary() : null;
        if (monthlySalary == null) {
            return BigDecimal.ZERO;
        }
        long totalDays = periodEnd.toEpochDay() - periodStart.toEpochDay() + 1;
        long daysInMonth = periodStart.lengthOfMonth();
        return monthlySalary.multiply(BigDecimal.valueOf(totalDays))
                .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
    }

    // ==================== 工资单生成 ====================

    /**
     * 生成工资单
     *
     * <p>算法走 {@link #computeWageBreakdown} (与 {@link #calculateMonthly} 同一套),
     * 生命周期沿用 PayrollRecord 的 PENDING → APPROVED → PAID。
     *
     * <p>⛔ 已存在的记录一律拒绝, <b>不会</b>在这里悄悄重算。要按最新规则重算草稿走
     * {@link #recalculatePayroll}。
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param periodStart 周期开始
     * @param periodEnd 周期结束
     * @return 工资记录
     */
    @Transactional
    public PayrollRecord generatePayroll(String factoryId, Long workerId,
            LocalDate periodStart, LocalDate periodEnd) {

        log.info("生成工资单: factoryId={}, workerId={}, period={} to {}",
                factoryId, workerId, periodStart, periodEnd);

        // 检查是否已存在工资记录
        if (payrollRecordRepository.existsByFactoryIdAndWorkerIdAndPeriodStartAndPeriodEnd(
                factoryId, workerId, periodStart, periodEnd)) {
            throw new RuntimeException("该周期的工资记录已存在 (需要按最新规则重算请走重算接口, "
                    + "只有 PENDING 草稿可重算)");
        }

        // 1. 获取工人信息
        User worker = userRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("工人不存在: " + workerId));

        // 2. 统一算法
        WageBreakdown bd = computeWageBreakdown(factoryId, workerId, periodStart, periodEnd, worker);

        // 3. 创建工资记录 (生命周期字段在这里定, 金额字段由 applyBreakdown 落)
        PayrollRecord payroll = PayrollRecord.builder()
                .factoryId(factoryId)
                .workerId(workerId)
                .workerName(worker.getFullName())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .periodType(determinePeriodType(periodStart, periodEnd))
                .bonusAmount(BigDecimal.ZERO)
                .deductionAmount(BigDecimal.ZERO)
                .status(PAYROLL_STATUS_PENDING)
                .build();

        applyBreakdown(payroll, worker, bd, periodStart, periodEnd);

        payroll = payrollRecordRepository.save(payroll);

        log.info("工资单生成完成: id={}, mode={}, totalWage={}, hourlyWage={}, pieceRateWage={}, overtimeWage={}",
                payroll.getId(), payroll.getWageMode(), payroll.getTotalWage(),
                payroll.getHourlyWage(), payroll.getPieceRateWage(), payroll.getOvertimeWage());

        return payroll;
    }

    // ==================== 草稿重算 (历史数据处置) ====================

    /**
     * 重算一张<b>待审核草稿</b>的工资单.
     *
     * <p>处置规则 (owner 裁定):
     * <ul>
     *   <li>{@code PAID} — 冻结不动。钱已经发了, 倒推是对账事故。</li>
     *   <li>{@code APPROVED} — 冻结不动。HR 已签字, 不能背着他改。</li>
     *   <li>{@code PENDING} — 可重算。还没人认过。</li>
     * </ul>
     *
     * <p><b>触发点是显式的</b>: 只有调用这个方法 (对应 POST 重算接口) 才会改草稿金额。
     * 查询 / 列表 / {@link #generatePayroll} 都不会 —— generatePayroll 撞到已有记录直接抛错。
     *
     * <p><b>金额变化 HR 看得见</b>: {@code previousTotalWage} 留变更前,
     * {@code totalWage} 是变更后, {@code recalcLog} 逐次追加一行人可读记录,
     * 另有 {@code recalculatedAt} / {@code recalculatedBy} / {@code recalcCount}。
     *
     * @param factoryId  工厂ID (租户校验)
     * @param payrollId  工资单ID
     * @param operatorId 操作人ID
     * @return 重算后的工资记录
     * @throws IllegalStateException 状态不是 PENDING (已审核/已发放冻结)
     */
    @Transactional
    public PayrollRecord recalculatePayroll(String factoryId, Long payrollId, Long operatorId) {
        PayrollRecord payroll = payrollRecordRepository.findById(payrollId)
                .orElseThrow(() -> new RuntimeException("工资记录不存在: " + payrollId));

        if (!payroll.getFactoryId().equals(factoryId)) {
            throw new RuntimeException("工资单不属于该工厂: payrollId=" + payrollId);
        }

        if (!PAYROLL_STATUS_PENDING.equals(payroll.getStatus())) {
            throw new IllegalStateException(
                    "只有待审核(PENDING)草稿可以重算; 当前状态=" + payroll.getStatus()
                            + " —— 已审核/已发放的工资单冻结, 不倒推历史金额");
        }

        User worker = userRepository.findById(payroll.getWorkerId())
                .orElseThrow(() -> new RuntimeException("工人不存在: " + payroll.getWorkerId()));

        BigDecimal before = payroll.getTotalWage() != null ? payroll.getTotalWage() : BigDecimal.ZERO;

        WageBreakdown bd = computeWageBreakdown(factoryId, payroll.getWorkerId(),
                payroll.getPeriodStart(), payroll.getPeriodEnd(), worker);
        applyBreakdown(payroll, worker, bd, payroll.getPeriodStart(), payroll.getPeriodEnd());

        BigDecimal after = payroll.getTotalWage() != null ? payroll.getTotalWage() : BigDecimal.ZERO;

        payroll.setPreviousTotalWage(before);
        payroll.setRecalculatedAt(LocalDateTime.now());
        payroll.setRecalculatedBy(operatorId);
        payroll.setRecalcCount((payroll.getRecalcCount() != null ? payroll.getRecalcCount() : 0) + 1);
        payroll.setRecalcLog(appendRecalcLog(payroll.getRecalcLog(), before, after, operatorId));

        PayrollRecord saved = payrollRecordRepository.save(payroll);

        log.info("工资单重算完成: id={}, operator={}, 总额 {} → {}", payrollId, operatorId, before, after);

        return saved;
    }

    /**
     * 批量重算一个周期内的<b>待审核草稿</b>.
     *
     * <p>APPROVED / PAID 逐条跳过并计数 —— 跳过多少条要说出来, 不能悄悄少算。
     *
     * @return 汇总: recalculatedCount / changedCount / unchangedCount /
     *         frozenApprovedCount / frozenPaidCount / failedCount / changes / failures
     */
    @Transactional
    public Map<String, Object> recalculatePendingPayrolls(String factoryId,
            LocalDate periodStart, LocalDate periodEnd, Long operatorId) {

        List<PayrollRecord> records = payrollRecordRepository.findByPeriod(factoryId, periodStart, periodEnd);

        int recalculated = 0;
        int changed = 0;
        int unchanged = 0;
        int frozenApproved = 0;
        int frozenPaid = 0;
        int failed = 0;
        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (PayrollRecord record : records) {
            if (PAYROLL_STATUS_APPROVED.equals(record.getStatus())) {
                frozenApproved++;
                continue;
            }
            if (PAYROLL_STATUS_PAID.equals(record.getStatus())) {
                frozenPaid++;
                continue;
            }
            if (!PAYROLL_STATUS_PENDING.equals(record.getStatus())) {
                // 未知状态一律不动 (宁可不算, 不要猜)
                failed++;
                failures.add("payrollId=" + record.getId() + ": 未知状态 " + record.getStatus());
                continue;
            }

            BigDecimal before = record.getTotalWage() != null ? record.getTotalWage() : BigDecimal.ZERO;
            try {
                PayrollRecord updated = recalculatePayroll(factoryId, record.getId(), operatorId);
                recalculated++;
                BigDecimal after = updated.getTotalWage() != null ? updated.getTotalWage() : BigDecimal.ZERO;
                if (after.compareTo(before) != 0) {
                    changed++;
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("payrollId", updated.getId());
                    change.put("workerId", updated.getWorkerId());
                    change.put("workerName", updated.getWorkerName());
                    change.put("previousTotalWage", before);
                    change.put("totalWage", after);
                    change.put("delta", after.subtract(before));
                    changes.add(change);
                } else {
                    unchanged++;
                }
            } catch (Exception e) {
                failed++;
                failures.add("payrollId=" + record.getId() + ": " + e.getMessage());
                log.error("重算失败: payrollId={}, error={}", record.getId(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factoryId", factoryId);
        result.put("periodStart", periodStart);
        result.put("periodEnd", periodEnd);
        result.put("totalRecords", records.size());
        result.put("recalculatedCount", recalculated);
        result.put("changedCount", changed);
        result.put("unchangedCount", unchanged);
        result.put("frozenApprovedCount", frozenApproved);
        result.put("frozenPaidCount", frozenPaid);
        result.put("failedCount", failed);
        result.put("changes", changes);
        if (!failures.isEmpty()) {
            result.put("failures", failures);
        }

        log.info("批量重算完成: factoryId={}, period={}~{}, 重算={}, 金额变化={}, 冻结(已审核)={}, 冻结(已发放)={}",
                factoryId, periodStart, periodEnd, recalculated, changed, frozenApproved, frozenPaid);

        return result;
    }

    /**
     * 追加一行重算审计记录 (append-only).
     */
    private String appendRecalcLog(String existing, BigDecimal before, BigDecimal after, Long operatorId) {
        BigDecimal delta = after.subtract(before);
        String line = String.format("[重算] %s by user %s: 总额 ¥%s → ¥%s (%s¥%s)",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                operatorId != null ? operatorId.toString() : "-",
                before.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                after.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                delta.signum() < 0 ? "-" : "+",
                delta.abs().setScale(2, RoundingMode.HALF_UP).toPlainString());
        return (existing == null || existing.isBlank()) ? line : existing + "\n" + line;
    }

    /**
     * 批量生成工资单 (按工厂)
     *
     * @param factoryId 工厂ID
     * @param periodStart 周期开始
     * @param periodEnd 周期结束
     * @return 工资记录列表
     */
    @Transactional
    public List<PayrollRecord> generateFactoryPayroll(String factoryId,
            LocalDate periodStart, LocalDate periodEnd) {

        log.info("批量生成工资单: factoryId={}, period={} to {}", factoryId, periodStart, periodEnd);

        // 获取有效率记录的所有工人
        List<WorkerDailyEfficiency> allEfficiencies = workerDailyEfficiencyRepository
                .findByDateRange(factoryId, periodStart, periodEnd);

        Set<Long> workerIds = allEfficiencies.stream()
                .map(WorkerDailyEfficiency::getWorkerId)
                .collect(Collectors.toSet());

        List<PayrollRecord> payrolls = new ArrayList<>();
        int successCount = 0;
        int skipCount = 0;

        for (Long workerId : workerIds) {
            try {
                // 检查是否已存在
                if (payrollRecordRepository.existsByFactoryIdAndWorkerIdAndPeriodStartAndPeriodEnd(
                        factoryId, workerId, periodStart, periodEnd)) {
                    log.debug("工资记录已存在，跳过: workerId={}", workerId);
                    skipCount++;
                    continue;
                }

                PayrollRecord payroll = generatePayroll(factoryId, workerId, periodStart, periodEnd);
                payrolls.add(payroll);
                successCount++;
            } catch (Exception e) {
                log.error("生成工资单失败: workerId={}, error={}", workerId, e.getMessage());
            }
        }

        log.info("批量生成工资单完成: 成功={}, 跳过={}, 总计={}", successCount, skipCount, workerIds.size());

        return payrolls;
    }

    /**
     * 确定效率评级
     */
    private String determineEfficiencyRating(BigDecimal efficiency, String factoryId,
            LocalDate periodStart, LocalDate periodEnd) {

        // 获取工厂平均效率作为基准
        BigDecimal avgEfficiency = payrollRecordRepository
                .avgEfficiencyByPeriod(factoryId, periodStart, periodEnd);

        BigDecimal baseline = avgEfficiency != null ? avgEfficiency : new BigDecimal("60");

        // 计算相对效率百分比
        BigDecimal relativePercent = efficiency.divide(baseline, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (relativePercent.compareTo(BigDecimal.valueOf(120)) >= 0) {
            return "A"; // 优秀
        } else if (relativePercent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return "B"; // 良好
        } else if (relativePercent.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "C"; // 合格
        } else {
            return "D"; // 待提升
        }
    }

    /**
     * 确定周期类型
     */
    private String determinePeriodType(LocalDate periodStart, LocalDate periodEnd) {
        long days = periodEnd.toEpochDay() - periodStart.toEpochDay() + 1;

        if (days == 1) {
            return PERIOD_TYPE_DAILY;
        } else if (days <= 7) {
            return PERIOD_TYPE_WEEKLY;
        } else {
            return PERIOD_TYPE_MONTHLY;
        }
    }

    // ==================== 效率排名和趋势 ====================

    /**
     * 获取工人效率排名
     *
     * @param factoryId 工厂ID
     * @param date 日期
     * @return 效率排名列表
     */
    public List<WorkerDailyEfficiency> getWorkerEfficiencyRanking(String factoryId, LocalDate date) {
        List<WorkerDailyEfficiency> ranking = workerDailyEfficiencyRepository
                .findDailyRanking(factoryId, date);

        // 设置排名
        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).setRankInTeam(i + 1);
        }

        return ranking;
    }

    /**
     * 获取工序效率排名
     */
    public List<WorkerDailyEfficiency> getProcessEfficiencyRanking(String factoryId,
            LocalDate date, String processStageType) {

        List<WorkerDailyEfficiency> ranking = workerDailyEfficiencyRepository
                .findDailyRankingByProcess(factoryId, date, processStageType);

        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).setRankInTeam(i + 1);
        }

        return ranking;
    }

    /**
     * 获取工人效率趋势
     *
     * @param workerId 工人ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 日期 -> 效率 的映射
     */
    public Map<LocalDate, BigDecimal> getWorkerEfficiencyTrend(Long workerId,
            LocalDate startDate, LocalDate endDate) {

        List<Object[]> trendData = workerDailyEfficiencyRepository
                .getWorkerTrend(workerId, startDate, endDate);

        Map<LocalDate, BigDecimal> trend = new LinkedHashMap<>();
        for (Object[] row : trendData) {
            LocalDate date = (LocalDate) row[0];
            BigDecimal efficiency = (BigDecimal) row[1];
            trend.put(date, efficiency != null ? efficiency : BigDecimal.ZERO);
        }

        return trend;
    }

    /**
     * 获取工厂效率趋势
     */
    public List<Map<String, Object>> getFactoryEfficiencyTrend(String factoryId,
            LocalDate startDate, LocalDate endDate) {

        List<Object[]> trendData = workerDailyEfficiencyRepository
                .getDailyTrend(factoryId, startDate, endDate);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : trendData) {
            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("date", row[0]);
            dayData.put("avgEfficiency", row[1]);
            dayData.put("totalPieces", row[2]);
            dayData.put("workerCount", row[3]);
            result.add(dayData);
        }

        return result;
    }

    // ==================== 人力成本分析 ====================

    /**
     * 人力成本分析
     *
     * @param factoryId 工厂ID
     * @param periodStart 周期开始
     * @param periodEnd 周期结束
     * @return 分析结果
     */
    public Map<String, Object> analyzeLaborCost(String factoryId,
            LocalDate periodStart, LocalDate periodEnd) {

        log.info("人力成本分析: factoryId={}, period={} to {}", factoryId, periodStart, periodEnd);

        Map<String, Object> analysis = new LinkedHashMap<>();

        // 1. 总工资支出
        BigDecimal totalWage = payrollRecordRepository.sumTotalWageByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("totalWage", totalWage != null ? totalWage : BigDecimal.ZERO);

        // 2. 计件工资总额
        BigDecimal totalPieceRateWage = payrollRecordRepository.sumPieceRateWageByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("totalPieceRateWage", totalPieceRateWage != null ? totalPieceRateWage : BigDecimal.ZERO);

        // 3. 总计件数
        Integer totalPieceCount = payrollRecordRepository.sumPieceCountByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("totalPieceCount", totalPieceCount != null ? totalPieceCount : 0);

        // 4. 单件人工成本
        // 🔴 2026-08-29 真机走查: F006 的人效概览显示「¥0.00 单件成本 / 0.0 件每小时」,
        //    而库里 wage_calculation 与 piece_rate_rules **都是 0 条** —— 这家工厂根本没启用计件。
        //    0 件时的单件成本**没有定义**, 兜成 ¥0 之后屏上读起来是「做一件不要钱」。
        //    ⚠️ 与计数类区别开: totalPieceCount / workerCount 的 0 是**合法读数**(确实没人计件),
        //    所以它们照旧兜 0; 由它们做分母的那几个派生量才必须是 null。
        BigDecimal costPerPiece = null;
        if (totalPieceCount != null && totalPieceCount > 0 && totalWage != null) {
            costPerPiece = totalWage.divide(BigDecimal.valueOf(totalPieceCount), 4, RoundingMode.HALF_UP);
        }
        analysis.put("costPerPiece", costPerPiece);

        // 5. 平均效率 —— 没有记录时 AVG 返 null, ⛔ 不兜 0(「效率 0%」是个真实读数)
        BigDecimal avgEfficiency = payrollRecordRepository.avgEfficiencyByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("averageEfficiency", avgEfficiency);

        // 6. 按效率评级统计
        List<Object[]> ratingStats = payrollRecordRepository.countByEfficiencyRating(factoryId, periodStart, periodEnd);
        Map<String, Map<String, Object>> byRating = new LinkedHashMap<>();
        for (Object[] row : ratingStats) {
            String rating = (String) row[0];
            if (rating != null) {
                Map<String, Object> ratingData = new LinkedHashMap<>();
                ratingData.put("count", row[1]);
                ratingData.put("totalWage", row[2]);
                byRating.put(rating, ratingData);
            }
        }
        analysis.put("byEfficiencyRating", byRating);

        // 7. 按工序统计
        List<Object[]> processStats = workerDailyEfficiencyRepository.statsByProcessStage(factoryId, periodStart, periodEnd);
        Map<String, Map<String, Object>> byProcess = new LinkedHashMap<>();
        for (Object[] row : processStats) {
            String processStage = (String) row[0];
            if (processStage != null) {
                Map<String, Object> processData = new LinkedHashMap<>();
                processData.put("avgEfficiency", row[1]);
                processData.put("totalPieces", row[2]);
                byProcess.put(processStage, processData);
            }
        }
        analysis.put("byProcessStage", byProcess);

        // 8. 工人数量
        long workerCount = workerDailyEfficiencyRepository.countWorkersByDate(factoryId, periodEnd);
        analysis.put("workerCount", workerCount);

        // 9. 人均产出 —— 分母是 workerCount, 为 0 时算不出来。⛔ 不兜 0。
        BigDecimal avgPiecesPerWorker = null;
        if (workerCount > 0 && totalPieceCount != null) {
            avgPiecesPerWorker = BigDecimal.valueOf(totalPieceCount)
                    .divide(BigDecimal.valueOf(workerCount), 2, RoundingMode.HALF_UP);
        }
        analysis.put("avgPiecesPerWorker", avgPiecesPerWorker);

        // 10. 人均工资 —— 同上。
        BigDecimal avgWagePerWorker = null;
        if (workerCount > 0 && totalWage != null) {
            avgWagePerWorker = totalWage.divide(BigDecimal.valueOf(workerCount), 2, RoundingMode.HALF_UP);
        }
        analysis.put("avgWagePerWorker", avgWagePerWorker);

        log.info("人力成本分析完成: totalWage={}, costPerPiece={}, avgEfficiency={}",
                totalWage, costPerPiece, avgEfficiency);

        return analysis;
    }

    // ==================== 工资单审核和发放 ====================

    /**
     * 审核工资单
     */
    @Transactional
    public PayrollRecord approvePayroll(Long payrollId, Long approverId) {
        PayrollRecord payroll = payrollRecordRepository.findById(payrollId)
                .orElseThrow(() -> new RuntimeException("工资记录不存在: " + payrollId));

        if (!PAYROLL_STATUS_PENDING.equals(payroll.getStatus())) {
            throw new RuntimeException("只能审核待审核状态的工资记录");
        }

        payroll.setStatus(PAYROLL_STATUS_APPROVED);
        payroll.setApprovedBy(approverId);
        payroll.setApprovedAt(LocalDateTime.now());

        log.info("工资单审核通过: id={}, approverId={}", payrollId, approverId);

        return payrollRecordRepository.save(payroll);
    }

    /**
     * 标记工资已发放
     */
    @Transactional
    public PayrollRecord markAsPaid(Long payrollId) {
        PayrollRecord payroll = payrollRecordRepository.findById(payrollId)
                .orElseThrow(() -> new RuntimeException("工资记录不存在: " + payrollId));

        if (!PAYROLL_STATUS_APPROVED.equals(payroll.getStatus())) {
            throw new RuntimeException("只能发放已审核的工资记录");
        }

        payroll.setStatus(PAYROLL_STATUS_PAID);
        payroll.setPaidAt(LocalDateTime.now());

        log.info("工资单已发放: id={}", payrollId);

        return payrollRecordRepository.save(payroll);
    }

    /**
     * 批量审核
     */
    @Transactional
    public int batchApprove(List<Long> payrollIds, Long approverId) {
        int count = 0;
        for (Long id : payrollIds) {
            try {
                approvePayroll(id, approverId);
                count++;
            } catch (Exception e) {
                log.error("批量审核失败: id={}, error={}", id, e.getMessage());
            }
        }
        return count;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取工人工资历史
     */
    public List<PayrollRecord> getWorkerPayrollHistory(Long workerId, int limit) {
        return payrollRecordRepository.findByWorkerId(workerId).stream()
                .sorted(Comparator.comparing(PayrollRecord::getPeriodStart).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取待审核工资单数量
     */
    public long countPendingPayrolls(String factoryId) {
        return payrollRecordRepository.countByFactoryIdAndStatus(factoryId, PAYROLL_STATUS_PENDING);
    }

    /**
     * 获取工资排行榜
     */
    public List<PayrollRecord> getTopEarners(String factoryId, LocalDate periodStart,
            LocalDate periodEnd, int limit) {

        return payrollRecordRepository.findTopEarners(
                factoryId, periodStart, periodEnd,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    // ==================== Sprint 6 Track W4-B: 按 mode 月度工资计算 ====================

    /**
     * Sprint 6 Track W4-B: 按 mode 计算员工月度工资.
     *
     * <p>算法本身在 {@link #computeWageBreakdown} —— 与 {@link #generatePayroll}
     * <b>同一个方法</b>。这里只负责把结果落到 WageCalculation 上 (无审批态, 纯计算明细)。
     *
     * <p>分支逻辑:
     * <ul>
     *   <li>{@link WageMode#PIECE_RATE}: 逐工序 PieceRateRule 阶梯计件 (+ 加班费)</li>
     *   <li>{@link WageMode#HOURLY}: 常规工时 × baseHourlyRate + 加班工时 × baseHourlyRate × overtimeMultiplier</li>
     *   <li>{@link WageMode#MIXED}: HOURLY 全额 (含 OT) + PIECE_RATE 全额 (per spec §W4-B 简化求和)</li>
     * </ul>
     *
     * <p>幂等性: 同 (factoryId, employeeId, periodMonth) upsert 已有 WageCalculation 行
     * (idx_wage_calc_employee_period UNIQUE constraint 保护). 重复 call 仅 update.
     *
     * <p>BigDecimal scale=2, ROUND_HALF_UP per CN 财务标准.
     *
     * @param factoryId  工厂 ID
     * @param month      计算月份 (YearMonth, 例 YearMonth.of(2026, 5))
     * @param employeeId 员工 ID
     * @return WageCalculation 实体 (含 mode + 各分项 + totalAmount derived)
     */
    @Transactional
    public WageCalculation calculateMonthly(String factoryId, YearMonth month, Long employeeId) {
        if (wagePolicyService == null) {
            throw new IllegalStateException(
                    "WagePolicyService 未注入 — Sprint 6 W4-B feature 需要 wage_policy schema");
        }
        if (wageCalculationRepository == null) {
            throw new IllegalStateException("WageCalculationRepository 未注入");
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        // 1. 员工信息 (用于 employee_name 冗余 + 加班费率兜底)
        Optional<User> workerOpt = userRepository.findById(employeeId);
        String employeeName = workerOpt
                .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                .orElse("未知员工");

        // 2. 统一算法 (与 generatePayroll 同一个方法, 不是两份实现)
        WageBreakdown bd = computeWageBreakdown(factoryId, employeeId, monthStart, monthEnd,
                workerOpt.orElse(null));

        // 3. idempotent upsert
        WageCalculation calc = wageCalculationRepository
                .findByFactoryIdAndEmployeeIdAndPeriodMonth(factoryId, employeeId, monthStart)
                .orElseGet(() -> WageCalculation.builder()
                        .factoryId(factoryId)
                        .employeeId(employeeId)
                        .periodMonth(monthStart)
                        .build());

        calc.setEmployeeName(employeeName);
        calc.setMode(bd.getMode());
        calc.setHourlyAmount(bd.getHourlyAmount());
        calc.setOvertimeAmount(bd.getOvertimeAmount());
        calc.setPieceRateAmount(bd.getPieceRateAmount());
        calc.setTotalHours(bd.getTotalHours());
        calc.setOvertimeHours(bd.getOvertimeHours());
        calc.setTotalPieceCount(bd.getTotalPieceCount());
        calc.setHourlyRuleId(bd.getHourlyRuleId());
        calc.setPieceRuleId(bd.getPieceRuleId());
        if (bd.getNotes() != null) {
            calc.setNotes(bd.getNotes());
        }

        WageCalculation saved = wageCalculationRepository.save(calc);
        log.info("[calculateMonthly] factory={}, employee={}, month={}, mode={}, " +
                        "total={}, hourly={}, ot={}, piece={}",
                factoryId, employeeId, month, bd.getMode(),
                saved.getTotalAmount(), bd.getHourlyAmount(), bd.getOvertimeAmount(),
                bd.getPieceRateAmount());
        return saved;
    }

    /**
     * Sprint 6 Track W4-B: 工厂级批量月度计算 (月底 WageMonthlyScheduler 调用).
     *
     * <p>遍历所有 (worker_daily_efficiency.worker_id) 跑 {@link #calculateMonthly}.
     * 错误不抛 — 单个员工失败不阻塞其他.
     *
     * @return Map containing successCount / failedCount / month / factoryId
     */
    @Transactional
    public Map<String, Object> calculateMonthlyForFactory(String factoryId, YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        List<WorkerDailyEfficiency> allEffs = workerDailyEfficiencyRepository
                .findByDateRange(factoryId, monthStart, monthEnd);
        Set<Long> workerIds = allEffs.stream()
                .map(WorkerDailyEfficiency::getWorkerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int successCount = 0;
        int failedCount = 0;
        List<String> failedReasons = new ArrayList<>();

        for (Long workerId : workerIds) {
            try {
                calculateMonthly(factoryId, month, workerId);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                String reason = "employee=" + workerId + ": " + e.getMessage();
                failedReasons.add(reason);
                log.error("[calculateMonthlyForFactory] factory={}, employee={}, month={}: 计算失败: {}",
                        factoryId, workerId, month, e.getMessage(), e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factoryId", factoryId);
        result.put("month", month.toString());
        result.put("workerCount", workerIds.size());
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        if (!failedReasons.isEmpty()) {
            result.put("failedReasons", failedReasons);
        }

        log.info("[calculateMonthlyForFactory] factory={}, month={}, workers={}, success={}, failed={}",
                factoryId, month, workerIds.size(), successCount, failedCount);
        return result;
    }
}
