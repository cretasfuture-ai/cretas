package com.cretas.aims.service;

import com.cretas.aims.dto.wage.MyWageResponse;
import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.PieceRateRule;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 「我的工资」的取数与组装 —— {@code MyWageController} (RN「我的工资」屏) 与 AI 工具
 * {@code my_wage_query} 共用这一份, 两边看到的金额与口径不会漂 (2026-11-17 从 controller 原样抽出)。
 *
 * <p>🔴 <b>本服务只接 workerId, 不判断「这个 workerId 是不是调用者本人」</b>。那道判断在每个调用方:
 * controller 从 token 推导 ({@code MyWageController#requireSelf}), 工具从执行上下文的当前登录人取
 * ({@code MyWageQueryTool}), 两者都<b>结构上不接受</b>任何工人标识入参。⛔ 新调用方必须照此办理,
 * 不许把用户输入的 ID / 姓名传进来。
 *
 * <p>三条硬约束 (不兜底 / 已发与未发不合并 / 不评级) 见 {@link MyWageResponse} 的 javadoc。
 */
@Service
@RequiredArgsConstructor
public class MyWageQueryService {

    private final WagePolicyService wagePolicyService;
    private final WageCalculationService wageCalculationService;
    private final WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    private final WageCalculationRepository wageCalculationRepository;
    private final PayrollRecordRepository payrollRecordRepository;

    /** {@code PayrollRecord.status} 全集 —— 与 {@code WageCalculationService.PAYROLL_STATUS_*} 一致。 */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_PAID = "PAID";

    /**
     * 某个工人某个月的工资: 报工汇总 / 逐条报工 / 系统试算 / 工资单, 四块互不合并。
     *
     * @param workerId    调用方已确认是当前登录人本人的用户 ID
     * @param displayName 本人显示名
     */
    public MyWageResponse monthly(String factoryId, Long workerId, String displayName, YearMonth ym) {
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();

        List<WorkerDailyEfficiency> effs = workerDailyEfficiencyRepository
                .findByWorkerAndDateRange(factoryId, workerId, monthStart, monthEnd);

        WageMode mode = wagePolicyService.resolveModeForEmployee(factoryId, workerId);
        String mixedHint = wagePolicyService.resolveEffectivePolicy(factoryId, workerId)
                .map(p -> p.getMixedFormulaHint())
                .orElse(null);

        // --- 时薪: 只有 HOURLY / MIXED 才该有. 拿不到就说清是哪一种「没有」 ---
        MyWageResponse.HourlyRateInfo hourlyRate = null;
        String hourlyRateUnavailableReason;
        if (mode == WageMode.HOURLY || mode == WageMode.MIXED) {
            Optional<HourlyRateRule> hRule =
                    wagePolicyService.findEffectiveHourlyRate(factoryId, workerId, monthStart);
            if (hRule.isPresent()) {
                HourlyRateRule r = hRule.get();
                hourlyRate = new MyWageResponse.HourlyRateInfo(
                        r.getId(), r.getBaseHourlyRate(), r.getOvertimeMultiplier(),
                        r.getEffectiveFrom(), r.getEffectiveTo());
                hourlyRateUnavailableReason = null;
            } else {
                hourlyRateUnavailableReason = "NO_HOURLY_RATE_RULE";
            }
        } else {
            hourlyRateUnavailableReason = "NOT_APPLICABLE_FOR_MODE";
        }

        // --- 系统试算 (wage_calculation): 每月 1 号 scheduler 自动跑上月, 本月通常还没有 ---
        MyWageResponse.Calculation calculation = null;
        String calculationUnavailableReason = "NOT_CALCULATED_YET";
        Optional<WageCalculation> calcOpt = wageCalculationRepository
                .findByFactoryIdAndEmployeeIdAndPeriodMonth(factoryId, workerId, monthStart);
        if (calcOpt.isPresent()) {
            WageCalculation c = calcOpt.get();
            calculation = new MyWageResponse.Calculation(
                    c.getMode() == null ? null : c.getMode().name(),
                    c.getHourlyAmount(), c.getOvertimeAmount(), c.getPieceRateAmount(),
                    c.getTotalAmount(), c.getTotalHours(), c.getOvertimeHours(),
                    c.getTotalPieceCount(), c.getNotes(), c.getUpdatedAt());
            calculationUnavailableReason = null;
        }

        // --- 工资单 (payroll_records): 周期与该月有重叠的全部, 按状态分开汇总 ---
        List<PayrollRecord> overlapping = payrollRecordRepository
                .findByFactoryIdAndWorkerId(factoryId, workerId).stream()
                .filter(p -> p.getPeriodStart() != null && p.getPeriodEnd() != null)
                .filter(p -> !p.getPeriodStart().isAfter(monthEnd) && !p.getPeriodEnd().isBefore(monthStart))
                .sorted(Comparator.comparing(PayrollRecord::getPeriodStart).reversed())
                .toList();
        List<MyWageResponse.Payroll> payrolls = overlapping.stream().map(this::toPayroll).toList();

        return new MyWageResponse(
                factoryId,
                workerId,
                displayName,
                ym.toString(),
                monthStart,
                monthEnd,
                mode.name(),
                wageModeLabel(mode),
                mixedHint,
                toWorkTotals(effs),
                toWorkRecords(effs),
                toPieceRates(factoryId, effs, monthEnd),
                hourlyRate,
                hourlyRateUnavailableReason,
                calculation,
                calculationUnavailableReason,
                payrolls,
                payrolls.isEmpty() ? null : toPayrollTotals(overlapping),
                payrolls.isEmpty() ? "NO_PAYROLL_FOR_PERIOD" : null);
    }

    /** 某个工人的工资单历史 (跨月, 按周期倒序, 最多 {@code limit} 条)。workerId 约定同 {@link #monthly}。 */
    public List<MyWageResponse.Payroll> payrollHistory(String factoryId, Long workerId, int limit) {
        List<MyWageResponse.Payroll> history = payrollRecordRepository
                .findByFactoryIdAndWorkerId(factoryId, workerId).stream()
                .filter(p -> p.getPeriodStart() != null)
                .sorted(Comparator.comparing(PayrollRecord::getPeriodStart).reversed())
                .limit(limit)
                .map(this::toPayroll)
                .toList();

        return history;
    }

    // ==================== 映射 ====================

    private static String wageModeLabel(WageMode mode) {
        return switch (mode) {
            case PIECE_RATE -> "按件算";
            case HOURLY -> "按小时算";
            case MIXED -> "按小时 + 按件";
        };
    }

    private static MyWageResponse.WorkTotals toWorkTotals(List<WorkerDailyEfficiency> effs) {
        int pieces = 0;
        int qualified = 0;
        int defects = 0;
        int minutes = 0;
        int rowsMissingPieceCount = 0;
        Set<LocalDate> days = new java.util.HashSet<>();
        for (WorkerDailyEfficiency e : effs) {
            if (e.getTotalPieceCount() == null) {
                // ⚠️ 不静默当成 0 —— 数出来, 让前端能说「有 N 条报工没记件数, 汇总偏低」。
                rowsMissingPieceCount++;
            } else {
                pieces += e.getTotalPieceCount();
            }
            if (e.getQualifiedCount() != null) {
                qualified += e.getQualifiedCount();
            }
            if (e.getDefectCount() != null) {
                defects += e.getDefectCount();
            }
            if (e.getEffectiveWorkMinutes() != null) {
                minutes += e.getEffectiveWorkMinutes();
            }
            if (e.getWorkDate() != null) {
                days.add(e.getWorkDate());
            }
        }
        return new MyWageResponse.WorkTotals(
                effs.size(), days.size(), pieces, qualified, defects, minutes, rowsMissingPieceCount);
    }

    private static List<MyWageResponse.WorkRecord> toWorkRecords(List<WorkerDailyEfficiency> effs) {
        return effs.stream()
                .sorted(Comparator.comparing(WorkerDailyEfficiency::getWorkDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> new MyWageResponse.WorkRecord(
                        e.getId(),
                        e.getWorkDate(),
                        e.getProcessStageType(),
                        e.getProductTypeId(),
                        e.getWorkstationName(),
                        e.getTotalPieceCount(),
                        e.getQualifiedCount(),
                        e.getDefectCount(),
                        e.getEffectiveWorkMinutes(),
                        e.getSourceBatchId(),
                        e.getUpdatedAt()))
                .toList();
    }

    /**
     * 该月出现过的每道工序, 各查一次它适用的计件单价规则。
     *
     * <p>查不到规则的工序<b>照样返回一条</b>, 只是 rate 全 null + {@code NO_PIECE_RATE_RULE} ——
     * 「这道工序没配单价」正是工人最需要知道的事 (他干了活但没人给这活定过价), ⛔ 不许过滤掉。
     */
    private List<MyWageResponse.PieceRateInfo> toPieceRates(
            String factoryId, List<WorkerDailyEfficiency> effs, LocalDate asOf) {

        Map<String, Integer> piecesByStage = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (WorkerDailyEfficiency e : effs) {
            int pieces = e.getTotalPieceCount() == null ? 0 : e.getTotalPieceCount();
            piecesByStage.merge(e.getProcessStageType(), pieces, Integer::sum);
        }

        List<MyWageResponse.PieceRateInfo> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : piecesByStage.entrySet()) {
            String stage = entry.getKey();
            Optional<PieceRateRule> ruleOpt =
                    wageCalculationService.findEffectivePieceRateRule(factoryId, stage, asOf);
            if (ruleOpt.isPresent()) {
                PieceRateRule r = ruleOpt.get();
                out.add(new MyWageResponse.PieceRateInfo(
                        stage, entry.getValue(), r.getId(), r.getName(),
                        r.getTier1Threshold(), r.getTier1Rate(),
                        r.getTier2Threshold(), r.getTier2Rate(),
                        r.getTier3Threshold(), r.getTier3Rate(),
                        null));
            } else {
                out.add(new MyWageResponse.PieceRateInfo(
                        stage, entry.getValue(), null, null,
                        null, null, null, null, null, null,
                        "NO_PIECE_RATE_RULE"));
            }
        }
        return out;
    }

    private MyWageResponse.Payroll toPayroll(PayrollRecord p) {
        String status = p.getStatus();
        return new MyWageResponse.Payroll(
                p.getId(),
                p.getPeriodStart(),
                p.getPeriodEnd(),
                p.getPeriodType(),
                status,
                payrollStatusLabel(status),
                STATUS_PAID.equals(status),
                p.getBaseSalary(),
                p.getPieceRateWage(),
                p.getOvertimeWage(),
                p.getOvertimeHours(),
                p.getBonusAmount(),
                p.getDeductionAmount(),
                p.getTotalWage(),
                p.getTotalPieceCount(),
                p.getTotalWorkHours(),
                p.getApprovedAt(),
                p.getPaidAt());
    }

    private static String payrollStatusLabel(String status) {
        if (status == null) {
            return "状态未知";
        }
        return switch (status) {
            case STATUS_PENDING -> "待审核";
            case STATUS_APPROVED -> "已审核, 还没发";
            case STATUS_PAID -> "已发放";
            // 数据里出现了三态之外的值 —— 不假装认识它, 把原值透出去。
            default -> "状态未知(" + status + ")";
        };
    }

    /**
     * 按状态分开求和。⛔ 不提供跨状态的总计 —— 见
     * {@link MyWageResponse.PayrollTotals} 的 javadoc。
     */
    private static MyWageResponse.PayrollTotals toPayrollTotals(List<PayrollRecord> records) {
        Map<String, List<PayrollRecord>> byStatus = records.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getStatus() == null ? "" : p.getStatus(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<PayrollRecord> paid = byStatus.getOrDefault(STATUS_PAID, List.of());
        List<PayrollRecord> approved = byStatus.getOrDefault(STATUS_APPROVED, List.of());
        List<PayrollRecord> pending = byStatus.getOrDefault(STATUS_PENDING, List.of());

        return new MyWageResponse.PayrollTotals(
                sumOrNull(paid), paid.size(),
                sumOrNull(approved), approved.size(),
                sumOrNull(pending), pending.size());
    }

    /**
     * ⛔ 一张单都没有时返回 {@code null} 而不是 {@code ZERO} ——
     * 「这一档没有单」和「这一档合计 0 元」是两件事, 前者前端要显示「—」。
     */
    private static BigDecimal sumOrNull(List<PayrollRecord> records) {
        if (records.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PayrollRecord p : records) {
            if (p.getTotalWage() != null) {
                total = total.add(p.getTotalWage());
            }
        }
        return total;
    }
}
