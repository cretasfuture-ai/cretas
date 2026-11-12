package com.cretas.aims.controller.oa;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.oa.TodoItemDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.oa.MyTodoAggregatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * OA 我的待办聚合 REST 控制器 — RN OA 待办中心。
 *
 * <p>提供两个轻量端点:
 * <ul>
 *   <li>{@code GET /api/mobile/{factoryId}/my-todos} — 按调用方角色返回待办列表</li>
 *   <li>{@code GET /api/mobile/{factoryId}/my-todos/count} — 返回待办总数（RN 徽标用）</li>
 * </ul>
 *
 * <p>角色从 {@code request.getAttribute("role")} 取（JwtAuthInterceptor 在拦截器层设置）。
 * **禁止**使用 SecurityContext — 该项目 SecurityContext 永远为空（C1 孪生坑）。
 *
 * <p>待办按权限出数（聚合层决定，Controller 仅透传; 见 MyTodoAggregatorServiceImpl#TYPE_PERMISSIONS）:
 * <ul>
 *   <li>默认 finance_manager → {采购财审, 销售财审, 价格异常, 盘点审批, 退货财审, 售价调整}（报损审批随「报损审批」权限, 默认只给厂长）</li>
 *   <li>默认 cashier → {已审付款}</li>
 *   <li>厂长 → 全部类型（含强制插单审批）; 其它岗位按自己的权限</li>
 *   <li>没有任何相关权限 → 空列表（HTTP 200，非 403）</li>
 * </ul>
 *
 * <p>🔒 红线: 权限/角色过滤在 {@link MyTodoAggregatorService} 层实现；
 * 本 Controller 不做额外过滤。0 改现有审批端点。
 *
 * <p>2026-11-11 (纪律排查 B13 线上验收): 去掉类上的 {@code @RequirePermission(finance:read / read_write)}。
 * 待办已按权限逐类出数 (强制插单审批随排产 / 生产权限、报损随「报损审批」权限), 而这道财务门槛让
 * 没有财务权限的岗位 (线上实测: F006 质量经理) 整个接口 403, 按权限聚合对他们不生效, 也和上面
 * 「没有任何相关权限 → 空列表 (200)」的约定相反。租户仍由 JwtAuthInterceptor 按路径 factoryId 校验,
 * 每一类待办能不能出, 只由聚合层的 TYPE_PERMISSIONS 决定。
 *
 * @since 2026-06-12 (OA 待办聚合 B3)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/my-todos")
@RequiredArgsConstructor
@Tag(name = "OA 我的待办", description = "OA 待办中心 — 按调用方权限聚合的待审批事项")
public class MyTodoController {

    private final MyTodoAggregatorService myTodoAggregatorService;
    private final UserRepository userRepository;

    /**
     * 去掉财务门槛后, 这里是唯一决定「按谁的权限出数」的地方 (#1611 独立审查 MEDIUM):
     * <ul>
     *   <li>工厂账号: 从库里读用户 (与 PermissionInterceptor 同一取法), 按 {@code hasPermission(user, code)} 逐类判,
     *       叠加个人权限覆盖, 岗位也用库里的, 不信 token 里可能已过期的岗位。读不到用户或用户不属于路径工厂 → 空。</li>
     *   <li>平台账号: 只有能进工厂的平台岗位 (hasFactoryBypass) 按 token 岗位走原来的岗位路径, 其余 → 空
     *       (以前这些在财务门槛处 403, 现在是 200 空列表, 同样拿不到任何数据)。</li>
     * </ul>
     */
    private List<TodoItemDTO> todosFor(String factoryId, HttpServletRequest request) {
        return forCaller(factoryId, request,
                role -> myTodoAggregatorService.listTodos(factoryId, role),
                user -> myTodoAggregatorService.listTodosForUser(factoryId, user),
                List.of());
    }

    /** 「按谁出数」的唯一判定, 列表 / 条数 / 能办类型三个端点共用, 不各写一份。 */
    private <T> T forCaller(String factoryId, HttpServletRequest request,
                            java.util.function.Function<String, T> byPlatformRole,
                            java.util.function.Function<User, T> byFactoryUser,
                            T none) {
        AuthenticatedActor actor = AuthenticatedActor.current(request).orElse(null);
        if (actor != null && actor.isPlatformAdmin()) {
            if (!actor.hasFactoryBypass()) {
                return none;
            }
            return byPlatformRole.apply((String) request.getAttribute("role"));
        }
        Long userId = actor != null ? actor.id() : toLong(request.getAttribute("userId"));
        if (userId == null) {
            return none;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !Objects.equals(factoryId, user.getFactoryId())) {
            log.warn("[MyTodo] 用户读不到或不属于路径工厂, 返回空待办: userId={} factoryId={}", userId, factoryId);
            return none;
        }
        log.debug("[MyTodo] factoryId={} userId={} role={}", factoryId, userId, user.getRoleCode());
        return byFactoryUser.apply(user);
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Long l) return l;
        if (raw instanceof Integer i) return i.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ─── 待办列表 ──────────────────────────────────────────────────────────────

    /**
     * 返回调用方角色对应的全部待办项，按提交时间倒序。
     *
     * <p>角色无匹配（如 operator）时返回空列表（200 OK），非 403。
     * 各域查询 fail-soft: 单域异常不影响其他域正常返回。
     *
     * @param factoryId 工厂 ID（租户隔离）
     * @param request   HTTP 请求（用于从 attribute 读取 role）
     * @return 待办列表（可为空，从不为 null）
     */
    @GetMapping
    @Operation(
            summary = "获取我的待办列表",
            description = "按调用方角色（finance_manager / cashier）返回对应域的待审批待办。"
                    + " fail-soft: 单域查询异常不影响其他域。"
                    + " 角色无匹配时返回空列表（200），而非 403。")
    public ApiResponse<List<TodoItemDTO>> listMyTodos(
            @PathVariable @NotBlank String factoryId,
            HttpServletRequest request) {
        // ⚠️ C1 孪生坑规避: 角色必须从 request attribute 取，SecurityContext 永空
        List<TodoItemDTO> todos = todosFor(factoryId, request);
        return ApiResponse.success("待办查询成功", todos);
    }

    // ─── 待办数量（徽标） ─────────────────────────────────────────────────────

    /**
     * 返回调用方待办总数，用于 RN 导航 tab 徽标显示。
     *
     * <p>等价于 {@code listMyTodos().data.size()}，独立端点避免 RN 为拿徽标反序列化整个列表。
     *
     * @param factoryId 工厂 ID
     * @param request   HTTP 请求（读取 role）
     * @return 待办总数（0 表示无待办）
     */
    @GetMapping("/count")
    @Operation(
            summary = "获取我的待办数量",
            description = "返回调用方待办总数，用于 RN 导航 tab 的数字徽标（badge）显示。"
                    + " fail-soft 同列表端点。角色无匹配时返回 0。")
    public ApiResponse<Integer> countMyTodos(
            @PathVariable @NotBlank String factoryId,
            HttpServletRequest request) {
        int count = todosFor(factoryId, request).size();
        return ApiResponse.success("待办数量查询成功", count);
    }

    // ─── 能办的待办类型 (App 待办入口) ─────────────────────────────────────────

    /**
     * 返回调用方「能办」的待办类型 (B4 App 按权限拼界面)。App 只凭它决定显不显示「待办」入口:
     * 非空 = 显示 (此刻 0 条也显示, 入口不随条数忽隐忽现), 空 = 不显示。App 里不再抄一份岗位 / 权限映射。
     *
     * <p>与列表同一个按谁出数的判定 ({@link #forCaller}), 同一张权限表; 强制插单另要求岗位被插单审批画布点名。
     */
    @GetMapping("/types")
    @Operation(
            summary = "获取我能办的待办类型",
            description = "按调用方权限返回能办的待办类型 (不查条数), App 用来决定是否显示待办入口。"
                    + " 没有任何相关权限时返回空数组 (200)。")
    public ApiResponse<List<TodoItemDTO.TodoType>> listMyTodoTypes(
            @PathVariable @NotBlank String factoryId,
            HttpServletRequest request) {
        java.util.Set<TodoItemDTO.TodoType> types = forCaller(factoryId, request,
                role -> myTodoAggregatorService.availableTypes(factoryId, role),
                user -> myTodoAggregatorService.availableTypesForUser(factoryId, user),
                java.util.Set.of());
        return ApiResponse.success("待办类型查询成功", List.copyOf(types == null ? java.util.Set.of() : types));
    }
}
