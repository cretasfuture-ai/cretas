package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.wage.MyWageResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MyWageQueryService;
import com.cretas.aims.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「我的工资」只读工具 (意图 HR_MY_WAGE_QUERY, 2026-11-17 v3 复核缺口)。
 *
 * <p>复核原话:「这个月的工资条能提前给我看看吗?」「把我工资算下呗」。以前落到考勤统计 / 员工档案,
 * 两个强模型都找不到合适意图。
 *
 * <h2>🔴 只能看本人</h2>
 * <ul>
 *   <li>身份<b>只</b>来自执行上下文的当前登录人 ({@code context.userId}), 与 {@code MyWageController}
 *       从 token 推导同一口径; 参数表里<b>没有</b>任何工人标识。</li>
 *   <li>模型若仍塞进他人标识 ({@link #OTHER_PERSON_KEYS}), 且不是本人, <b>整次拒绝</b>, 不查、不返回任何金额 ——
 *       而不是静默忽略后返回本人工资 (那会让「查张三工资」看起来像查到了张三)。</li>
 *   <li>当前登录人必须属于本厂 ({@code findByIdAndFactoryId}), 否则拒绝: 挡跨厂。</li>
 *   <li>取数与组装复用 {@link MyWageQueryService} (与 App「我的工资」屏同一份), 不另算一套。</li>
 * </ul>
 *
 * <p>⛔ 这不是全厂人工成本 ({@code WAGE_COST_SUMMARY}, hr 读权限), 也不是工资方案 ({@code WAGE_POLICY_QUERY})。
 * 权限码沿用 App 端 {@link PermissionService#SELF_WAGE_VIEW_PERMISSION} (已激活账号都有, 不越权靠的是上面的「只看本人」)。
 */
@Slf4j
@Component
public class MyWageQueryTool extends AbstractBusinessTool {

    /** 模型可能用来指代「别人」的参数名。出现且不是本人 ⇒ 拒绝。 */
    static final List<String> OTHER_PERSON_KEYS = List.of(
            "workerId", "employeeId", "userId", "staffId",
            "workerName", "employeeName", "userName", "name", "person", "targetUser");

    @Autowired
    private MyWageQueryService myWageQueryService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public String getToolName() {
        return "my_wage_query";
    }

    @Override
    public String getDescription() {
        return "查看我自己的工资 (工资条): 某月的报工件数与工时、系统试算金额、工资单 (待审核/已审核/已发放分开列)。"
                + "只能看当前登录人本人, 不能查别人; 全厂工资成本请用工资成本汇总。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("month", Map.of(
                "type", "string",
                "description", "月份 yyyy-MM, 不传 = 本月"));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        Long me = getUserId(context);
        if (me == null) {
            throw new BusinessException(401, "无法识别当前登录人, 不能查看工资")
                    .withHint("请重新登录后再查看");
        }
        User self = userRepository.findByIdAndFactoryId(me, factoryId).orElse(null);
        if (self == null) {
            log.warn("[my_wage_query] 当前登录人不属于本厂: userId={}, factoryId={}", me, factoryId);
            throw new BusinessException(403, "不能查看其它工厂的工资")
                    .withHint("请用本工厂的账号登录后再查看");
        }

        String otherPerson = otherPersonMentioned(params, self);
        if (otherPerson != null) {
            log.info("[my_wage_query] 拒绝查他人工资: userId={}, factoryId={}, asked={}", me, factoryId, otherPerson);
            Map<String, Object> denied = new LinkedHashMap<>();
            denied.put("status", "DENIED");
            denied.put("reason", "SELF_ONLY");
            return buildSimpleResult("「我的工资」只能看你本人的工资, 不能查别人的。"
                    + "需要看全厂工资成本, 请让有人事或财务权限的同事查工资成本汇总。", denied);
        }

        YearMonth ym = parseMonth(getString(params, "month"));
        String displayName = self.getFullName() != null && !self.getFullName().isBlank()
                ? self.getFullName() : self.getUsername();
        MyWageResponse wage = myWageQueryService.monthly(factoryId, me, displayName, ym);
        log.info("[my_wage_query] 查询本人工资: factoryId={}, workerId={}, month={}", factoryId, me, ym);
        return buildSimpleResult(summarize(wage), wage);
    }

    /** 参数里出现的「别人」; 都是本人或没出现则返回 null。 */
    static String otherPersonMentioned(Map<String, Object> params, User self) {
        if (params == null) {
            return null;
        }
        for (String key : OTHER_PERSON_KEYS) {
            Object raw = params.get(key);
            if (raw == null) {
                continue;
            }
            String value = raw.toString().trim();
            if (value.isEmpty() || isSelf(value, self)) {
                continue;
            }
            return value;
        }
        return null;
    }

    private static boolean isSelf(String value, User self) {
        if (value.equals(String.valueOf(self.getId()))) {
            return true;
        }
        if (Set.of("我", "我自己", "本人", "自己").contains(value)) {
            return true;
        }
        return value.equals(self.getFullName()) || value.equals(self.getUsername());
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "月份格式不对: " + month)
                    .withHint("请用 yyyy-MM 格式, 例如 2026-11");
        }
    }

    /** 回复文案: 试算与工资单分开说, 工资单按状态分开说, 拿不到就说拿不到 (⛔ 不说成 0)。 */
    static String summarize(MyWageResponse w) {
        StringBuilder sb = new StringBuilder();
        sb.append(w.workerName()).append(" ").append(w.month()).append(" 的工资 (")
                .append(w.wageModeLabel()).append("):\n");
        MyWageResponse.WorkTotals work = w.work();
        if (work != null) {
            sb.append("报工 ").append(work.workedDays()).append(" 天, 共 ").append(work.totalPieceCount())
                    .append(" 件, 有效工时 ").append(work.totalWorkMinutes() / 60).append(" 小时 ")
                    .append(work.totalWorkMinutes() % 60).append(" 分");
            if (work.rowsMissingPieceCount() > 0) {
                sb.append(" (有 ").append(work.rowsMissingPieceCount()).append(" 条报工没记件数, 件数偏低)");
            }
            sb.append("。\n");
        }
        MyWageResponse.Calculation calc = w.calculation();
        if (calc != null && calc.totalAmount() != null) {
            sb.append("系统试算: ").append(money(calc.totalAmount())).append(" 元 (试算不是发到手的数)");
            if (calc.notes() != null && !calc.notes().isBlank()) {
                sb.append(", 说明: ").append(calc.notes());
            }
            sb.append("。\n");
        } else {
            sb.append("系统试算: 本月还没算 (每月 1 号算上个月)。\n");
        }
        MyWageResponse.PayrollTotals totals = w.payrollTotals();
        if (totals == null) {
            sb.append("工资单: 这个月还没有出工资单。");
        } else {
            sb.append("工资单 (按状态分开, 不相加): ");
            sb.append("已发放 ").append(totals.paidCount()).append(" 张")
                    .append(totals.paidTotal() == null ? "" : " " + money(totals.paidTotal()) + " 元");
            sb.append("; 已审核未发 ").append(totals.approvedCount()).append(" 张")
                    .append(totals.approvedTotal() == null ? "" : " " + money(totals.approvedTotal()) + " 元");
            sb.append("; 待审核 ").append(totals.pendingCount()).append(" 张")
                    .append(totals.pendingTotal() == null ? "" : " " + money(totals.pendingTotal()) + " 元");
            sb.append("。");
        }
        return sb.toString();
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /** 与 App「我的工资」({@code MyWageController} 类级门) 同一个码; 只看本人由 doExecute 保证。 */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of(PermissionService.SELF_WAGE_VIEW_PERMISSION);
    }
}
