package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.dto.wage.MyWageResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.MyWageQueryService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 「我的工资」—— 车间工人只读自己工资的端点 (2026-08-25)。
 *
 * <h2>🔴 为什么必须是独立 controller, 而不是给 {@code WageController} 放宽权限</h2>
 *
 * <p>{@link WageController} 类级挂 {@code {"hr:read_write","hr:read","finance:read"}},
 * 而本仓的 {@code PermissionInterceptor} 是<b>方法级注解覆盖类级</b>(不是叠加), 所以
 * {@code /wage/payroll}、{@code /wage/payroll/{payrollId}}、
 * {@code /wage/efficiency/daily/{workerId}}、{@code /wage/efficiency/trend/{workerId}}
 * 这些没有方法级注解的端点全都吃那道 hr/finance 闸。车间工人 (operator: dashboard:read +
 * production:read_write + work_report:read_write) 打过去必然 403。
 *
 * <p>⛔ <b>但绝不能靠放宽那几个端点来解决</b>: 它们的 {@code workerId} 是<b>路径参数</b>,
 * {@code /wage/payroll} 的 {@code workerId} 是<b>查询参数</b> —— 放宽权限 = 任何工人都能
 * 把别人的 ID 填进去读别人的工资。那是数据泄露, 不是功能。
 *
 * <h2>✅ 不泄露的保证</h2>
 *
 * <p>本 controller 的<b>每一个端点都不接受任何工人标识入参</b>。workerId 只有一个来源:
 * {@code Authorization} 头里的 token → {@link MobileService#getUserFromToken}。
 * 请求里就算带上 {@code ?workerId=999}, Spring 也没有任何参数去接它, 它会被静默丢弃。
 * 这不是「校验通过了」, 是<b>结构上没有那条路</b>。
 *
 * <p>第二道: {@link #requireSelf} 还要求 token 里的 {@code factoryId} 与路径上的
 * {@code factoryId} 逐字相等, 否则 403 —— 挡住拿本厂 token 去读别厂路径。
 *
 * <p>判据在 {@code MyWageControllerSelfScopeTest}: 用 A 的 token 带着 B 的 ID 请求,
 * 断言拿到的是 A 的钱、且 B 的金额一个字节都不出现在响应里; 另有一条反射闸钉住
 * 「这里的端点只许有 factoryId / month / limit 三种入参」。
 *
 * <h2>⛔ 不做的事</h2>
 * <ul>
 *   <li><b>只读</b>: 只有 GET, 不改任何状态。</li>
 *   <li><b>不做个人排行/评级</b>: {@code efficiencyRating}(A/B/C/D)、{@code rankInTeam}、
 *       {@code efficiencyScore}、{@code comparedToStandard} 一律不投影。owner 裁定质量责任
 *       归工厂不归个人, 且奖励产出会诱导少报不良品。</li>
 *   <li><b>不兜底</b>: 拿不到的金额返回 {@code null} + reason, ⛔ 不返回 0。</li>
 * </ul>
 *
 * @see MyWageResponse 三条硬约束 (不兜底 / 两个数不合并 / 不评级) 写在 DTO 的 javadoc 里
 * @since 2026-08-25
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/wage/me")
@RequiredArgsConstructor
@Tag(name = "我的工资", description = "车间工人只读自己的工资, 不接受任何工人ID入参")
// ⚠️ 类级注解 = fail-closed 兜底: 就算以后有人删掉某个方法上的注解, 也还是这道闸,
//    不会退化成「无注解 = 放行」。本 controller 的所有方法都不再单独标注。
@RequirePermission(PermissionService.SELF_WAGE_VIEW_PERMISSION)
public class MyWageController {

    private final MobileService mobileService;
    /** 取数与组装 (与 AI 工具 my_wage_query 共用); 身份判断留在本类 {@link #requireSelf}。 */
    private final MyWageQueryService myWageQueryService;

    /** 我的工资单历史默认/最大条数。 */
    private static final int PAYROLL_HISTORY_DEFAULT_LIMIT = 12;
    private static final int PAYROLL_HISTORY_MAX_LIMIT = 36;

    // ==================== 端点 ====================

    /**
     * 我的某个月工资。
     *
     * <p>返回四块互不合并的东西: 报工汇总 / 逐条报工 / 系统试算 / 工资单。
     * 为什么不合并见 {@link MyWageResponse} 的 javadoc。
     *
     * @param factoryId 工厂ID (路径, 必须与 token 里的一致)
     * @param month     月份 yyyy-MM, 不传 = 本月
     */
    @GetMapping("/monthly")
    @Operation(summary = "我的某月工资",
            description = "只返回当前登录人自己的数据。⛔ 不接受 workerId 入参 —— 身份只从 token 推导。")
    public ApiResponse<MyWageResponse> getMyMonthlyWage(
            @PathVariable("factoryId") @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(name = "month", required = false)
            @Parameter(description = "月份 yyyy-MM, 不传=本月", example = "2026-08") String month,
            HttpServletRequest httpRequest) {

        UserDTO me = requireSelf(factoryId, httpRequest);
        YearMonth ym = parseMonth(month);

        log.info("[MyWage] 查询本人工资: factoryId={}, workerId={}, month={}", factoryId, me.getId(), ym);

        return ApiResponse.success(myWageQueryService.monthly(factoryId, me.getId(), displayName(me), ym));
    }

    /**
     * 我的工资单历史 (跨月, 按周期倒序)。
     *
     * @param factoryId 工厂ID (路径, 必须与 token 里的一致)
     * @param limit     条数上限, 默认 12, 最多 36
     */
    @GetMapping("/payrolls")
    @Operation(summary = "我的工资单历史",
            description = "只返回当前登录人自己的工资单。⛔ 不接受 workerId 入参。")
    public ApiResponse<List<MyWageResponse.Payroll>> getMyPayrollHistory(
            @PathVariable("factoryId") @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam(name = "limit", required = false)
            @Parameter(description = "条数上限, 默认12 最多36", example = "12") Integer limit,
            HttpServletRequest httpRequest) {

        UserDTO me = requireSelf(factoryId, httpRequest);
        int effectiveLimit = limit == null ? PAYROLL_HISTORY_DEFAULT_LIMIT
                : Math.max(1, Math.min(limit, PAYROLL_HISTORY_MAX_LIMIT));

        log.info("[MyWage] 查询本人工资单历史: factoryId={}, workerId={}, limit={}",
                factoryId, me.getId(), effectiveLimit);

        return ApiResponse.success(myWageQueryService.payrollHistory(factoryId, me.getId(), effectiveLimit));
    }

    // ==================== 身份 ====================

    /**
     * 解出「我是谁」, 并确认我确实属于路径上那个工厂。
     *
     * <p>🔴 这是本 controller 唯一的身份来源。⛔ 任何时候都不要加一个 workerId 参数来
     * 「顺便支持班组长看组员」—— 那需要的是另一个端点 + 另一道闸 (「他是不是我的组员」),
     * 而不是给这里开一个口子。这里开的口子是全厂工资泄露。
     */
    private UserDTO requireSelf(String factoryId, HttpServletRequest httpRequest) {
        String token = TokenUtils.extractToken(httpRequest.getHeader("Authorization"));
        UserDTO me = mobileService.getUserFromToken(token);
        if (me == null || me.getId() == null) {
            throw new BusinessException(401, "无法从令牌识别当前用户")
                    .withHint("请重新登录后再查看工资");
        }
        if (factoryId == null || !factoryId.equals(me.getFactoryId())) {
            log.warn("[MyWage] 跨工厂访问被拒: token factoryId={}, path factoryId={}, userId={}",
                    me.getFactoryId(), factoryId, me.getId());
            throw new BusinessException(403, "不能查看其它工厂的工资")
                    .withHint("请用本工厂的账号登录后再查看");
        }
        return me;
    }

    private static String displayName(UserDTO me) {
        if (me.getFullName() != null && !me.getFullName().isBlank()) {
            return me.getFullName();
        }
        return me.getUsername();
    }

    // ==================== 映射 ====================

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "月份格式不对: " + month)
                    .withHint("请用 yyyy-MM 格式, 例如 2026-08");
        }
    }

}
