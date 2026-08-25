package com.cretas.aims.dto.wage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * RN「我的工资」屏的只读载荷 (2026-08-25)。
 *
 * <h2>⛔ 这份 DTO 的三条硬约束</h2>
 *
 * <ol>
 *   <li><b>不兜底</b>: 拿不到的金额一律 {@code null} + 一个 {@code *UnavailableReason},
 *       ⛔ 绝不写 {@code BigDecimal.ZERO}。把「我还没查到」显示成「0 元」在工资这件事上是最糟的错误
 *       —— 工人会以为自己白干了一个月。前端看到 null 显示「—」并把 reason 说出来。</li>
 *   <li><b>两个数不合并</b>: {@link #calculation} (系统自动试算, <b>没经过审批</b>) 与
 *       {@link #payroll} (工资单, <b>有 待审核/已审核/已发放 状态</b>) 是<b>两套引擎</b>算出来的
 *       两个数, 各自独立呈现。⛔ 不许求和、不许取其一冒充「我这个月的工资」。
 *       <p>它们真的会不一样, 这不是 bug 而是当前系统的事实:
 *       <ul>
 *         <li>{@code WageCalculationService.calculateMonthly} → {@code WageCalculation}:
 *             每月 1 号 01:00 scheduler 自动跑上月; 计件按<b>逐工序</b>分别套规则再求和;
 *             支持 HOURLY / MIXED。</li>
 *         <li>{@code WageCalculationService.generatePayroll} → {@code PayrollRecord}:
 *             HR 手动触发; 计件拿<b>全期总件数</b>套<b>出现次数最多的那道工序</b>的规则 (一档);
 *             另加 基本工资 / 加班 / 奖金 / 扣款。</li>
 *       </ul>
 *       工人横跨多道工序时两者<b>必然</b>对不上。工资单是发到手的那个数, 试算是参考。
 *       这个差额恰恰是工人该看到的信息 —— 藏起来才是扯皮的来源。</li>
 *   <li><b>不做个人评级 / 排行</b>: {@code PayrollRecord.efficiencyRating} (A/B/C/D)、
 *       {@code WorkerDailyEfficiency.rankInTeam} / {@code efficiencyScore} /
 *       {@code comparedToStandard} <b>一律不投影到这个 DTO</b>。owner 裁定质量责任归工厂不归个人,
 *       且奖励产出会诱导少报不良品。这一屏只讲钱, 不给人打分。</li>
 * </ol>
 *
 * @since 2026-08-25
 */
@Schema(description = "我的工资 (只读, 仅本人)")
public record MyWageResponse(

        @Schema(description = "工厂ID", example = "F001")
        String factoryId,

        @Schema(description = "工人ID (从 token 推导, 不接受入参)", example = "101")
        Long workerId,

        @Schema(description = "工人姓名", example = "张三")
        String workerName,

        @Schema(description = "查询月份 yyyy-MM", example = "2026-08")
        String month,

        @Schema(description = "该月第一天")
        LocalDate periodStart,

        @Schema(description = "该月最后一天")
        LocalDate periodEnd,

        @Schema(description = "工资模式 PIECE_RATE / HOURLY / MIXED", example = "PIECE_RATE")
        String wageMode,

        @Schema(description = "工资模式中文 (给低技术素养用户看的)", example = "按件算")
        String wageModeLabel,

        @Schema(description = "MIXED 模式的口径说明, 仅展示不参与计算; 无则 null")
        String mixedFormulaHint,

        @Schema(description = "该月报工汇总 (一定非 null, 但可能全是 0 条)")
        WorkTotals work,

        @Schema(description = "该月逐条报工记录 (按天 × 工序), 可能为空数组")
        List<WorkRecord> records,

        @Schema(description = "该月涉及到的计件单价规则 (一道工序一条), 可能为空数组")
        List<PieceRateInfo> pieceRates,

        @Schema(description = "时薪规则 (只有 HOURLY / MIXED 才查); 查不到时为 null")
        HourlyRateInfo hourlyRate,

        @Schema(description = "时薪查不到的原因: NOT_APPLICABLE_FOR_MODE / NO_HOURLY_RATE_RULE; 拿到了则为 null")
        String hourlyRateUnavailableReason,

        @Schema(description = "系统自动试算 (未经审批); 该月还没跑过则为 null")
        Calculation calculation,

        @Schema(description = "试算拿不到的原因: NOT_CALCULATED_YET; 拿到了则为 null")
        String calculationUnavailableReason,

        @Schema(description = "周期与该月有重叠的**全部**工资单 (日结/周结会有多张), 可能为空数组")
        List<Payroll> payrolls,

        @Schema(description = "工资单金额**按状态分开**汇总; 一张都没有时为 null")
        PayrollTotals payrollTotals,

        @Schema(description = "工资单拿不到的原因: NO_PAYROLL_FOR_PERIOD; 拿到了则为 null")
        String payrollUnavailableReason
) {

    /** 该月报工汇总。件数/工时是计数类, 空集合的 0 是合法读数, 与「查不到」不同。 */
    @Schema(description = "该月报工汇总")
    public record WorkTotals(
            @Schema(description = "报工记录条数 (按 天×工序 计)") int recordCount,
            @Schema(description = "有报工的天数 (去重)") int workedDays,
            @Schema(description = "总件数") int totalPieceCount,
            @Schema(description = "合格件数") int qualifiedCount,
            @Schema(description = "不合格件数") int defectCount,
            @Schema(description = "有效工时 (分钟)") int totalWorkMinutes,
            @Schema(description = "件数字段为空的行数 —— >0 说明有报工没记件数, 汇总会偏低")
            int rowsMissingPieceCount
    ) {
    }

    /**
     * 一条报工记录 = {@code worker_daily_efficiency} 的一行 = (工人, 日期, 工序) 三元组。
     *
     * <p>⚠️ <b>这不是「一次报工」</b>。同一天同一道工序报 5 次, 累加成<b>同一行</b>
     * ({@code WageRecordTriggerService.recordPieceWage} 是 upsert 累加)。所以竞品那种
     * 「26 笔报工逐笔对账」在当前数据模型下<b>做不到</b>, 最细只到「天 × 工序」。
     *
     * <p>⚠️ <b>这里没有金额字段, 是故意的</b>。计件单价是<b>阶梯</b>的
     * ({@code PieceRateRule.calculateWage} 按累计件数分三档), 所以「这一行值多少钱」
     * 取决于它在整月累计里的位置 —— 逐行金额<b>加起来不等于</b>月度金额。
     * 编一个逐行金额出来会让工人自己一加, 发现跟工资单对不上, 反而制造扯皮。
     * 单价规则单独放在 {@link MyWageResponse#pieceRates}, 让工人看到档位本身。
     */
    @Schema(description = "一条报工记录 (天 × 工序)")
    public record WorkRecord(
            @Schema(description = "记录ID") Long id,
            @Schema(description = "工作日期") LocalDate workDate,
            @Schema(description = "工序类型") String processStageType,
            @Schema(description = "产品类型ID") String productTypeId,
            @Schema(description = "工位名称") String workstationName,
            @Schema(description = "件数; null = 这条没记件数, ⛔ 不是 0") Integer totalPieceCount,
            @Schema(description = "合格件数") Integer qualifiedCount,
            @Schema(description = "不合格件数") Integer defectCount,
            @Schema(description = "有效工时 (分钟)") Integer effectiveWorkMinutes,
            @Schema(description = "来源生产批次ID (可反查是哪一批)") Long sourceBatchId,
            @Schema(description = "最后更新时间") LocalDateTime updatedAt
    ) {
    }

    /** 计件单价规则 (阶梯)。查不到规则时所有 rate 为 null + unavailableReason 非空。 */
    @Schema(description = "计件单价规则")
    public record PieceRateInfo(
            @Schema(description = "工序类型") String processStageType,
            @Schema(description = "该工序本月件数") int pieceCountInMonth,
            @Schema(description = "规则ID") Long ruleId,
            @Schema(description = "规则名称") String ruleName,
            @Schema(description = "第一档上限 (件)") Integer tier1Threshold,
            @Schema(description = "第一档单价 (元/件)") BigDecimal tier1Rate,
            @Schema(description = "第二档上限 (件)") Integer tier2Threshold,
            @Schema(description = "第二档单价 (元/件)") BigDecimal tier2Rate,
            @Schema(description = "第三档上限 (件)") Integer tier3Threshold,
            @Schema(description = "超出部分单价 (元/件)") BigDecimal tier3Rate,
            @Schema(description = "NO_PIECE_RATE_RULE = 这道工序没配单价规则; 有规则则为 null")
            String unavailableReason
    ) {
    }

    /** 时薪规则。 */
    @Schema(description = "时薪规则")
    public record HourlyRateInfo(
            @Schema(description = "规则ID") Long ruleId,
            @Schema(description = "基础时薪 (元/小时)") BigDecimal baseHourlyRate,
            @Schema(description = "加班倍率") BigDecimal overtimeMultiplier,
            @Schema(description = "生效起") LocalDate effectiveFrom,
            @Schema(description = "生效止 (null = 长期有效)") LocalDate effectiveTo
    ) {
    }

    /**
     * 系统自动试算 ({@code wage_calculation} 一行)。
     *
     * <p>⚠️ <b>没有审批状态</b> —— 它是机器算的, 不代表财务认可, 更不代表已经发。
     * 前端必须标成「系统试算 · 未审批」, ⛔ 不许显示成「我的工资」。
     */
    @Schema(description = "系统自动试算 (未经审批)")
    public record Calculation(
            @Schema(description = "计算模式") String mode,
            @Schema(description = "计时部分") BigDecimal hourlyAmount,
            @Schema(description = "加班部分") BigDecimal overtimeAmount,
            @Schema(description = "计件部分") BigDecimal pieceRateAmount,
            @Schema(description = "合计 (按 mode 派生)") BigDecimal totalAmount,
            @Schema(description = "总工时 (小时)") BigDecimal totalHours,
            @Schema(description = "加班工时 (小时)") BigDecimal overtimeHours,
            @Schema(description = "总件数") Integer totalPieceCount,
            @Schema(description = "异常说明, 例「缺 PieceRateRule(CUTTING)」—— 有值说明这个数是不全的")
            String notes,
            @Schema(description = "算出来的时间") LocalDateTime calculatedAt
    ) {
    }

    /**
     * 工资单 ({@code payroll_records} 一行) —— <b>发到手的就是这个数</b>。
     *
     * <p>{@link #status}: {@code PENDING} 待审核 / {@code APPROVED} 已审核 / {@code PAID} 已发放。
     * 这三态是 {@code WageCalculationService.PAYROLL_STATUS_*} 的全集, 没有草稿态。
     */
    @Schema(description = "工资单 (有审批状态)")
    public record Payroll(
            @Schema(description = "工资单ID") Long id,
            @Schema(description = "周期起") LocalDate periodStart,
            @Schema(description = "周期止") LocalDate periodEnd,
            @Schema(description = "周期类型 DAILY/WEEKLY/MONTHLY") String periodType,
            @Schema(description = "状态 PENDING/APPROVED/PAID") String status,
            @Schema(description = "状态中文") String statusLabel,
            @Schema(description = "是否已经发到手") boolean paid,
            @Schema(description = "基本工资") BigDecimal baseSalary,
            @Schema(description = "计件工资") BigDecimal pieceRateWage,
            @Schema(description = "加班工资") BigDecimal overtimeWage,
            @Schema(description = "加班时长 (小时)") BigDecimal overtimeHours,
            @Schema(description = "奖金") BigDecimal bonusAmount,
            @Schema(description = "扣款") BigDecimal deductionAmount,
            @Schema(description = "合计") BigDecimal totalWage,
            @Schema(description = "总件数") Integer totalPieceCount,
            @Schema(description = "总工时 (小时)") BigDecimal totalWorkHours,
            @Schema(description = "审核时间") LocalDateTime approvedAt,
            @Schema(description = "发放时间") LocalDateTime paidAt
    ) {
    }

    /**
     * 工资单金额<b>按状态分开</b>汇总。
     *
     * <p>⛔ <b>刻意不提供「总计」字段。</b>「已发放 ¥3200 + 待审核 ¥800」不是「¥4000」——
     * 后面那 800 财务还没点头, 甚至可能被驳回。把它们加成一个数, 正是「预估和已核准混在一起」
     * 这个错误本身。三档各自成立, 前端也必须分三行显示。
     *
     * <p>每个 total 在对应 count 为 0 时是 {@code null} 而不是 {@code ZERO} ——
     * 「这一档一张都没有」和「这一档合计 0 元」是两回事。
     */
    @Schema(description = "工资单金额按状态分开汇总 (⛔ 没有总计, 三档不许相加)")
    public record PayrollTotals(
            @Schema(description = "已发放合计; 没有已发放的单时为 null") BigDecimal paidTotal,
            @Schema(description = "已发放单数") int paidCount,
            @Schema(description = "已审核未发放合计; 没有则为 null") BigDecimal approvedTotal,
            @Schema(description = "已审核未发放单数") int approvedCount,
            @Schema(description = "待审核合计; 没有则为 null") BigDecimal pendingTotal,
            @Schema(description = "待审核单数") int pendingCount
    ) {
    }
}
