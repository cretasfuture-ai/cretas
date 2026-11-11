package com.cretas.aims.service.oa;

import com.cretas.aims.dto.oa.TodoItemDTO;

import java.util.List;

/**
 * 我的待办聚合服务接口 — RN OA 待办中心后端。
 *
 * <p>按调用方角色 fan-out 到 5 个已有 pending 查询，角色映射:
 * <ul>
 *   <li>finance_manager → {采购财审, 销售财审, 价格异常, 盘点审批}</li>
 *   <li>cashier → {已审付款（PAYMENT_DISBURSE）}</li>
 *   <li>其他 → 空</li>
 * </ul>
 *
 * <p>阈值: {@code cretas.oa.todo.detail-threshold}（默认 ¥5000），amount > 阈值 → needDetail=true。
 *
 * <p>fail-soft: 单类型域查询异常时 log warn + 该类型返空，不阻断整体请求。
 *
 * @since 2026-06-12 (OA 待办聚合 B2)
 */
public interface MyTodoAggregatorService {

    /**
     * 按工厂 ID + 调用方角色返回待办列表，按提交时间倒序。
     *
     * @param factoryId  工厂 ID（租户隔离）
     * @param callerRole 调用方角色（从 request.getAttribute("role") 取，非 SecurityContext）
     * @return 按 submittedAt 倒序的待办列表；角色无匹配或所有域为空时返回空列表（非 null）
     */
    List<TodoItemDTO> listTodos(String factoryId, String callerRole);

    /**
     * 返回待办总数（徽标）。实现等价于 {@code listTodos(...).size()}。
     *
     * @param factoryId  工厂 ID
     * @param callerRole 调用方角色
     * @return 待办数量
     */
    int countTodos(String factoryId, String callerRole);

    /**
     * 按<b>具体用户</b>出待办: 每一类用 {@code PermissionService.hasPermission(user, code)} 判, 叠加个人权限覆盖
     * (平台对单个员工收回某模块时, 这一类就不出); 岗位取库里的 {@code user.getRoleCode()}, 不信 token 里的旧岗位。
     * {@code user == null} → 空列表。HTTP 入口 ({@code MyTodoController}) 对工厂账号一律走这条。
     */
    List<TodoItemDTO> listTodosForUser(String factoryId, com.cretas.aims.entity.User user);

    /** {@link #listTodosForUser} 的条数。 */
    int countTodosForUser(String factoryId, com.cretas.aims.entity.User user);
}
