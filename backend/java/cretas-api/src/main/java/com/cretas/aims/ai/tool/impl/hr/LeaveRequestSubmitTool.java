package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.WritePreviewSupport;
import com.cretas.aims.entity.hr.LeaveBalance;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.hr.LeaveBalanceService;
import com.cretas.aims.service.hr.LeaveRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 提交请假申请工具 (AIChat).
 *
 * <p>WRITE Tool with TCC preview. Wraps {@link LeaveRequestService#create} +
 * {@link LeaveRequestService#submit} (DRAFT → SUBMITTED). 审批通过后由 Service 自动
 * 扣减 {@link LeaveBalance}.
 *
 * <p>防呆设计:
 * <ul>
 *   <li>R1 (max 显示): doPreview 调用 LeaveBalanceService.getBalance 显示已获/已用/剩余,
 *       计算 requestedHours, 给出 canDo 判断 (仅 ANNUAL/SICK/COMPTIME/OTHER 跟踪余额).</li>
 *   <li>R2 (context): 返回 message 含 用户 ID + 请假类型 + 起止日期 + 时长.</li>
 *   <li>R4 (幂等): Service.create 已做 时段重叠 check (BusinessException 409).
 *       Tool catch 后返回 DUPLICATE 状态 + actionHint 提示查看现有申请.</li>
 *   <li>4位一体 error: BusinessException.message 透传 (含具体冲突上下文),
 *       通过 throw 让上层 GlobalExceptionHandler 处理 sticky toast.</li>
 * </ul>
 *
 * Intent Code: HR_LEAVE_SUBMIT
 *
 * @author Cretas Team — Sprint 5 Tool 4
 * @since 2026-05-17
 */
@Slf4j
@Component
public class LeaveRequestSubmitTool extends AbstractBusinessTool {

    /** Standard workday hours, used to compute requested hours from day-only inputs. */
    private static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");

    /** Leave types that consume LeaveBalance (must mirror LeaveRequestServiceImpl.BALANCE_TRACKED_TYPES). */
    private static final Set<LeaveType> BALANCE_TRACKED_TYPES =
            EnumSet.of(LeaveType.ANNUAL, LeaveType.SICK, LeaveType.COMPTIME, LeaveType.OTHER);

    @Autowired
    private LeaveRequestService leaveRequestService;

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Override
    public String getToolName() {
        return "hr_leave_submit";
    }

    @Override
    public String getDescription() {
        return "提交请假申请 — 支持类型: 年假(ANNUAL)/病假(SICK)/事假(PERSONAL)/调休(COMPTIME)/"
                + "婚假(MARRIAGE)/丧假(FUNERAL)/产假(MATERNITY)/陪产假(PATERNITY)/其它(OTHER). "
                + "自动检查余额 (跟踪型类型) 并触发审批流. "
                + "用户说 '我要请 3 天年假' / '提交病假申请' / '请假从 X 日到 Y 日' 时调用. "
                + "Preview 阶段显示余额对比, Execute 阶段创建+提交申请.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("leaveType", Map.of(
                "type", "string",
                "description", "请假类型: ANNUAL/SICK/PERSONAL/COMPTIME/MARRIAGE/FUNERAL/MATERNITY/PATERNITY/OTHER",
                "enum", List.of("ANNUAL", "SICK", "PERSONAL", "COMPTIME",
                        "MARRIAGE", "FUNERAL", "MATERNITY", "PATERNITY", "OTHER")));
        properties.put("startDate", Map.of(
                "type", "string", "format", "date",
                "description", "起始日期 YYYY-MM-DD"));
        properties.put("endDate", Map.of(
                "type", "string", "format", "date",
                "description", "结束日期 YYYY-MM-DD (包含当日)"));
        properties.put("durationHours", Map.of(
                "type", "number",
                "description", "请假时长 (小时). 不传则按 (endDate - startDate + 1) * 8 计算"));
        properties.put("reason", Map.of(
                "type", "string",
                "description", "请假原因 (可选)"));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("leaveType", "startDate", "endDate"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("leaveType", "startDate", "endDate");
    }

    @Override
    public boolean supportsPreview() {
        return true;
    }

    /**
     * Override naming convention default (returns READ for non-_create/_update/_delete suffix).
     * This Tool genuinely creates + submits a LeaveRequest → WRITE semantics.
     */
    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    /** WRITE Tool with preview gate → MEDIUM risk (mirror AbstractBusinessTool default for WRITE). */
    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    /**
     * 只读预览 —— 不创建任何 LeaveRequest。给确认卡看的字段全是人话: 中文假别 / 日期区间 / 小时数 / 余额,
     * 不回显用户编号与枚举码。
     *
     * <p>镜像 execute 的拒绝条件, 同一个顺序: 假别认得出 → 日期格式对 → 结束不早于开始 →
     * 同期间没有在途 / 已批准的申请 ({@link LeaveRequestService#findOverlappingActive}, 与
     * {@code create} 同一个方法) → (跟踪型假别) 月度余额够。
     *
     * <p>⚠️ 凡是「办不了」都必须带 {@code canDo=false}: {@code ToolDispatchService} 只认它来决定要不要铸确认 token。
     * 以前这里的 PREVIEW_INVALID 分支没带 canDo, 于是「日期写反」「假别不认识」也会拿到一张可确认的票。
     *
     * <p>R1 防呆: 查 LeaveBalance 显示已获 / 已用 / 可用, 计算 requestedHours。
     */
    @Override
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long userId = getUserId(context);
        if (userId == null) {
            return WritePreviewSupport.blocked("PREVIEW_ERROR",
                    "没有识别到当前登录的人，请重新登录后再试。", null);
        }

        LeaveType leaveType;
        LocalDate startDate;
        LocalDate endDate;
        try {
            leaveType = parseLeaveType(params);
            startDate = LocalDate.parse(getString(params, "startDate"));
            endDate = LocalDate.parse(getString(params, "endDate"));
        } catch (BusinessException e) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID", String.format(
                    "不认识的请假类型「%s」。可选：%s。",
                    getString(params, "leaveType"), leaveTypeOptions()), Map.of("code", e.getCode() != null ? e.getCode() : 400));
        } catch (java.time.format.DateTimeParseException e) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID",
                    "日期格式不对，请按「年-月-日」说，比如 2026-10-08。", Map.of("code", 400));
        }

        // 基础校验 — 提前在 Preview 暴露, 避免 Execute 才报错
        if (endDate.isBefore(startDate)) {
            return WritePreviewSupport.blocked("PREVIEW_INVALID", String.format(
                    "结束日期（%s）不能早于开始日期（%s）。请重新告诉我请假的起止日期。", endDate, startDate), null);
        }

        BigDecimal requestedHours = computeDurationHours(params, startDate, endDate);
        long requestedDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        String typeName = leaveType.getDisplayName();
        String period = String.format("%s ~ %s（%d 天，%s 小时）", startDate, endDate, requestedDays,
                requestedHours.stripTrailingZeros().toPlainString());

        // 与 create() 同一个撞期判断 (只读)
        List<LeaveRequest> overlapping = leaveRequestService.findOverlappingActive(
                factoryId, userId, startDate, endDate);
        if (!overlapping.isEmpty()) {
            LeaveRequest existing = overlapping.get(0);
            Map<String, Object> dup = new LinkedHashMap<>();
            dup.put("actionHint", "先在「我的请假」里撤回原申请，或换一个日期");
            return WritePreviewSupport.blocked("DUPLICATE", String.format(
                    "你在 %s ~ %s 已经有一条请假申请（%s），不能重复提交。请先撤回原申请，或换一个日期。",
                    existing.getStartDate(), existing.getEndDate(),
                    existing.getStatus() != null ? existing.getStatus().getDisplayName() : "处理中"), dup);
        }

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("status", "PREVIEW");
        preview.put("userId", userId);
        preview.put("leaveType", leaveType.name());
        preview.put("leaveTypeName", typeName);
        preview.put("startDate", startDate.toString());
        preview.put("endDate", endDate.toString());
        preview.put("requestedDays", requestedDays);
        preview.put("requestedHours", requestedHours);
        preview.put("reason", getString(params, "reason", ""));

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("请假类型", typeName);
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("请假类型", typeName);
        next.put("请假时间", period);
        if (WritePreviewSupport.notBlank(getString(params, "reason"))) {
            next.put("请假原因", getString(params, "reason"));
        }
        next.put("提交后", "进入审批流程（没有设置审批流时会自动批准）");
        preview.put("currentValues", current);
        preview.put("newValues", next);

        // R1 防呆: 余额仅对 跟踪型 leaveType 有意义
        if (BALANCE_TRACKED_TYPES.contains(leaveType)) {
            String yearMonth = YearMonth.from(startDate).toString();  // mirror Service's approve logic
            String monthText = String.format("%d 年 %d 月", startDate.getYear(), startDate.getMonthValue());
            Optional<LeaveBalance> bOpt = leaveBalanceService.getBalance(
                    factoryId, userId, leaveType, yearMonth);
            if (bOpt.isPresent()) {
                LeaveBalance b = bOpt.get();
                BigDecimal balanceHours = b.getBalanceHours() != null
                        ? b.getBalanceHours()
                        : b.getEarnedHours().subtract(b.getUsedHours());
                boolean canDo = balanceHours.compareTo(requestedHours) >= 0;
                preview.put("yearMonth", yearMonth);
                preview.put("earnedHours", b.getEarnedHours());
                preview.put("usedHours", b.getUsedHours());
                preview.put("availableHours", balanceHours);
                preview.put("canDo", canDo);
                current.put(monthText + "可用余额", hoursText(balanceHours));
                String msg = canDo
                        ? String.format("确认提交%s申请？%s。%s可用余额 %s（已获 %s，已用 %s），够用。提交后进入审批流程（没有设置审批流时会自动批准）。",
                                typeName, period, monthText + typeName,
                                hoursText(balanceHours), hoursText(b.getEarnedHours()), hoursText(b.getUsedHours()))
                        : String.format("%s申请 %s，超过 %s 的可用余额 %s，还差 %s。请缩短请假天数，或先累计调休余额。",
                                typeName, hoursText(requestedHours), monthText,
                                hoursText(balanceHours), hoursText(requestedHours.subtract(balanceHours)));
                preview.put("message", msg);
            } else {
                // 无余额记录 — 通常意味未发放 (年假) 或月初零余额
                preview.put("yearMonth", yearMonth);
                preview.put("earnedHours", BigDecimal.ZERO);
                preview.put("usedHours", BigDecimal.ZERO);
                preview.put("availableHours", BigDecimal.ZERO);
                preview.put("canDo", false);
                preview.put("message", String.format(
                        "你在 %s 还没有%s的余额记录，申请 %s 很可能被审批拒绝。请先联系 HR 确认%s的发放情况。",
                        monthText, typeName, hoursText(requestedHours), typeName));
            }
        } else {
            // 非跟踪型 (PERSONAL/MARRIAGE/FUNERAL/MATERNITY/PATERNITY) — 仅记录, 不查余额
            preview.put("canDo", true);
            preview.put("message", String.format(
                    "确认提交%s申请？%s。这类假不占用月度余额，提交后进入审批流程（没有设置审批流时会自动批准）。",
                    typeName, period));
        }

        log.info("LeaveRequestSubmit PREVIEW: factory={} user={} type={} {}~{} hours={} canDo={}",
                factoryId, userId, leaveType, startDate, endDate, requestedHours, preview.get("canDo"));
        return preview;
    }

    private static String hoursText(BigDecimal hours) {
        return hours.stripTrailingZeros().toPlainString() + " 小时";
    }

    private static String leaveTypeOptions() {
        return java.util.Arrays.stream(LeaveType.values())
                .map(LeaveType::getDisplayName)
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    /**
     * Execute: create DRAFT + submit (DRAFT → SUBMITTED). Service 内部:
     * 1. {@link LeaveRequestService#create} 做 时段重叠 check + DRAFT 保存
     * 2. {@link LeaveRequestService#submit} 触发审批流; 若无审批配置则自动 APPROVED + 扣余额
     *
     * R4 防呆: 捕获 BusinessException(409) 重叠错误, 转 DUPLICATE 状态 + actionHint.
     */
    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long userId = getUserId(context);
        if (userId == null) {
            return Map.of(
                    "status", "ERROR",
                    "message", "无法识别当前用户, 请重新登录后再试");
        }

        LeaveType leaveType;
        LocalDate startDate;
        LocalDate endDate;
        BigDecimal durationHours;
        String reason;
        // R4位一体: friendly validation errors at param-parse stage (mirrors doPreview)
        try {
            leaveType = parseLeaveType(params);
            startDate = LocalDate.parse(getString(params, "startDate"));
            endDate = LocalDate.parse(getString(params, "endDate"));
            durationHours = computeDurationHours(params, startDate, endDate);
            reason = getString(params, "reason");
        } catch (BusinessException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "ERROR");
            err.put("message", "⚠️ " + e.getMessage());
            err.put("actionHint", "请检查输入参数后重试");
            err.put("code", e.getCode() != null ? e.getCode() : 400);
            log.warn("LeaveRequestSubmit param-parse ERROR: factory={} user={} msg={}",
                    factoryId, userId, e.getMessage());
            return err;
        } catch (java.time.format.DateTimeParseException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "ERROR");
            err.put("message", "⚠️ 日期格式无效, 期望 YYYY-MM-DD: " + e.getParsedString());
            err.put("actionHint", "请检查 startDate / endDate 格式后重试");
            err.put("code", 400);
            log.warn("LeaveRequestSubmit date-parse ERROR: factory={} user={} parsed={}",
                    factoryId, userId, e.getParsedString());
            return err;
        }

        try {
            // 1. Create DRAFT (Service does 时段重叠 check)
            LeaveRequest draft = leaveRequestService.create(
                    factoryId, userId, leaveType, startDate, endDate, durationHours, reason);
            log.info("LeaveRequestSubmit CREATE: id={} factory={} user={} type={} {}~{} hours={}",
                    draft.getId(), factoryId, userId, leaveType, startDate, endDate, durationHours);

            // 2. Submit (DRAFT → SUBMITTED, 若无审批链则自动 APPROVED)
            LeaveRequest submitted = leaveRequestService.submit(factoryId, userId, draft.getId());

            // R2 context: 含 ID + 用户 + 类型 + 起止 + 时长 + 终态
            String statusMsg = submitted.getStatus() == HrRequestStatus.APPROVED
                    ? "已自动批准 (无需审批)"
                    : "待审批";
            String msg = String.format(
                    "✓ 请假申请已提交 (id=%s): 用户 %d %s %s ~ %s (%sh), 状态=%s, %s",
                    submitted.getId(), userId, leaveType.name(),
                    startDate, endDate, durationHours,
                    submitted.getStatus().name(), statusMsg);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", submitted.getId());
            data.put("userId", submitted.getUserId());
            data.put("leaveType", submitted.getLeaveType().name());
            data.put("startDate", submitted.getStartDate().toString());
            data.put("endDate", submitted.getEndDate().toString());
            data.put("durationHours", submitted.getDurationHours());
            data.put("status", submitted.getStatus().name());
            data.put("submittedAt", submitted.getSubmittedAt() != null
                    ? submitted.getSubmittedAt().toString() : null);

            log.info("LeaveRequestSubmit SUBMITTED: id={} status={}",
                    submitted.getId(), submitted.getStatus());
            return buildSimpleResult(msg, data);

        } catch (BusinessException e) {
            // R4 防呆: 409 = 时段重叠 (Service 抛出, 含现有申请信息)
            if (e.getCode() != null && e.getCode() == 409) {
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("status", "DUPLICATE");
                dup.put("message", "⚠️ " + e.getMessage());
                dup.put("actionHint", "查看现有申请详情, 如需修改请先撤回原申请");
                log.warn("LeaveRequestSubmit DUPLICATE: factory={} user={} type={} {}~{}: {}",
                        factoryId, userId, leaveType, startDate, endDate, e.getMessage());
                return dup;
            }
            // 其他 BusinessException (e.g. 400 from Service.validateDates) — 4位一体: surface
            // friendly message instead of falling through to AbstractBusinessTool generic sanitize
            // (which strips it to "操作执行失败，请稍后重试" — defeats fool-proof R2).
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "ERROR");
            err.put("message", "⚠️ " + e.getMessage());
            err.put("actionHint", "请检查申请信息或联系管理员");
            err.put("code", e.getCode() != null ? e.getCode() : 400);
            log.warn("LeaveRequestSubmit ERROR: factory={} user={} type={} {}~{}: code={} msg={}",
                    factoryId, userId, leaveType, startDate, endDate, e.getCode(), e.getMessage());
            return err;
        }
    }

    /** Helper: 解析 leaveType, 友好错误信息 (取代默认 IllegalArgumentException). */
    private LeaveType parseLeaveType(Map<String, Object> params) {
        String raw = getString(params, "leaveType");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("请假类型 (leaveType) 必填");
        }
        try {
            return LeaveType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("不支持的请假类型: " + raw
                    + ". 可选: ANNUAL/SICK/PERSONAL/COMPTIME/MARRIAGE/FUNERAL/MATERNITY/PATERNITY/OTHER");
        }
    }

    /** Helper: 计算 durationHours — 优先用参数, 否则按 (endDate - startDate + 1) * 8. */
    private BigDecimal computeDurationHours(Map<String, Object> params,
                                            LocalDate startDate, LocalDate endDate) {
        BigDecimal explicit = getBigDecimal(params, "durationHours");
        if (explicit != null && explicit.signum() > 0) {
            return explicit;
        }
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        return new BigDecimal(days).multiply(HOURS_PER_DAY);
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "leaveType", "请问您要申请什么类型的请假？可选：" + leaveTypeOptions() + "。",
                "startDate", "请问请假从哪一天开始？比如 2026-10-08。",
                "endDate", "请问请假到哪一天结束？比如 2026-10-10（包含这一天）。"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
                "leaveType", "请假类型",
                "startDate", "起始日期",
                "endDate", "结束日期",
                "durationHours", "请假时长(小时)",
                "reason", "请假原因"
        );
        return names.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
