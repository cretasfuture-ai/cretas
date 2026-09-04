package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.RoleDefinition;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.RoleDefinitionRepository;
import com.cretas.aims.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 根据 userId 从 DB 解析当前用户的 {@link DataScope} (RBAC 第 2 维, Sprint 5 Track G).
 *
 * <h3>解析逻辑</h3>
 * 优先级 (高 → 低):
 * <ol>
 *   <li>{@code role_definitions.data_scope} 列 (V20260519_08 migration 新加, per Role 配置;
 *       {@code V20261101_27__create_role_definitions_table.sql} 补建了表本身, 见下方 2026-09-04 记)</li>
 *   <li>「查得到表但没这一行」→ 角色硬默认: factory_super_admin / platform_admin → ALL;
 *       一线员工 (operator/warehouse_worker/quality_inspector/yield_operator) → SELF; 其他 → ALL (兼容)</li>
 *   <li>「查询本身失败」(表/列不存在、连接异常等) → <b>fail-closed, 恒为 SELF</b>
 *       (2026-09-04 起, 见下). 不再按角色区分, 因为角色硬默认本身也要靠代码维护,
 *       查询失败时没有依据判断哪个角色"应该"更宽.</li>
 * </ol>
 *
 * <h3>2026-09-04 订正: fail-open → fail-closed on 查询失败</h3>
 * 本类上线以来 (Sprint 5, 2026-05-19) {@code role_definitions} 表在
 * {@code cretas_prod_db} 上<b>从未真正存在过</b>（详见新增 migration
 * {@code V20261101_27__create_role_definitions_table.sql} 的注释）, 生产上<b>每一次</b>
 * scope 解析都在走"查询失败"分支, 表现为每隔几秒一条
 * {@code SQL error resolving role ...: PreparedStatementCallback; bad SQL grammar} WARN。
 *
 * <p>排查发现该分支此前直接落到"角色硬默认"（factory_super_admin → ALL）——
 * 对 factory_super_admin 本身不算越权 (硬默认与种子数据设计值一致), 但同时暴露了
 * 一个真实的越权缺陷: 硬默认 switch 漏收了 {@code yield_operator}（一线报工岗,
 * 与 operator 同级 30, 见 {@link FactoryUserRole#yield_operator}）, 落进 {@code default}
 * 分支拿到了 ALL 而不是 SELF —— LIUSHANMEN 工厂 9 个 {@code yield_operator} 账号
 * (该厂最大的角色群体) 因此本应只看自己创建的数据, 实际上没有被限制。
 * 已在本次一并补上 {@code yield_operator → SELF}（下方 switch）与种子数据。
 *
 * <p>无论如何, "查询失败"本身不该再走任何按角色区分的默认值 — 表/列缺失这种
 * 基础设施故障应该<b>恒定收紧到最窄的 SELF</b>, 而不是继续放行 ALL 给大多数角色。
 * 查表本身查不到某一行（{@link Optional#empty()}, 正常场景, 例如角色确实没配置）
 * 才走角色硬默认 —— 那是设计好的兼容路径, 不是故障。
 *
 * <p>WARN 日志按 role_code 节流 (见 {@link #WARN_THROTTLE}), 避免生产每几秒刷一条相同 WARN。
 *
 * @author Cretas Team (Sprint 5 Track G)
 * @since 2026-05-19
 */
@Slf4j
@Component
public class DataScopeResolver {

    /** 查询失败时的节流窗口: 同一 role_code 在窗口内只 WARN 一次, 而不是每个请求都打. */
    static final Duration WARN_THROTTLE = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final RoleDefinitionRepository roleDefinitionRepository;
    private final Clock clock;
    private final ConcurrentMap<String, Instant> lastWarnedAt = new ConcurrentHashMap<>();

    @Autowired
    public DataScopeResolver(UserRepository userRepository, RoleDefinitionRepository roleDefinitionRepository) {
        this(userRepository, roleDefinitionRepository, Clock.systemUTC());
    }

    /** Package-visible: 测试注入可控 {@link Clock}, 验证节流窗口. */
    DataScopeResolver(UserRepository userRepository, RoleDefinitionRepository roleDefinitionRepository, Clock clock) {
        this.userRepository = userRepository;
        this.roleDefinitionRepository = roleDefinitionRepository;
        this.clock = clock;
    }

    /**
     * 解析当前用户 scope. 返回 null 仅当 user 不存在 (caller 应 fallback ALL).
     *
     * @param userId 当前 user id (从 JWT). null 时返 null (匿名访问).
     * @return {@link DataScopeContext}; user 不存在返 null
     */
    public DataScopeContext resolve(Long userId) {
        if (userId == null) {
            return null;
        }
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.debug("DataScopeResolver: user {} not found, return null", userId);
            return null;
        }
        User user = userOpt.get();
        String roleCode = user.getRoleCode();
        DataScope scope = resolveScopeForRole(roleCode);

        return DataScopeContext.builder()
                .userId(userId)
                .factoryId(user.getFactoryId())
                .roleCode(roleCode)
                .scope(scope)
                .department(user.getDepartment())
                .column("created_by")
                .build();
    }

    /**
     * Lookup data_scope from {@code role_definitions} table (JPA, {@link RoleDefinitionRepository}).
     * "查得到表, 查不到这一行" 用硬编码角色默认值兜底 (设计好的兼容路径)。
     * "查询本身失败"(表/列缺失、连接异常等) fail-closed 到 {@link DataScope#SELF}, 见类注释.
     */
    private DataScope resolveScopeForRole(String roleCode) {
        if (roleCode == null || roleCode.isEmpty()) {
            return DataScope.ALL;
        }

        // 优先查 role_definitions.data_scope 列
        try {
            Optional<RoleDefinition> roleDefOpt = roleDefinitionRepository.findByRoleCode(roleCode);
            if (roleDefOpt.isPresent()) {
                String dbValue = roleDefOpt.get().getDataScope();
                if (dbValue != null && !dbValue.isEmpty()) {
                    try {
                        return DataScope.valueOf(dbValue);
                    } catch (IllegalArgumentException ex) {
                        log.warn("DataScopeResolver: role_definitions has invalid data_scope='{}' for role={}, fallback to default",
                                dbValue, roleCode);
                    }
                }
            } else {
                // role 不在 role_definitions 表, 用硬默认 (设计好的兼容路径, 非故障)
                log.debug("DataScopeResolver: role {} not in role_definitions, use hard default", roleCode);
            }
        } catch (Exception ex) {
            // 查询本身失败 (表/列不存在、连接异常等) → fail-closed: 恒为最窄的 SELF,
            // 不再走按角色区分的硬默认 (2026-09-04 订正, 见类注释).
            warnThrottled(roleCode, ex);
            return DataScope.SELF;
        }

        // Hard defaults based on role hierarchy (仅在"查得到表但没这一行"时使用)
        FactoryUserRole role = FactoryUserRole.fromRoleCode(roleCode);
        switch (role) {
            case platform_admin:
            case factory_super_admin:
                return DataScope.ALL;
            case operator:
            case warehouse_worker:
            case quality_inspector:
            case yield_operator:
                // 一线员工 → 自己创建的数据
                // ⚠️ 2026-09-04 补 yield_operator: 此前遗漏, LIUSHANMEN 9 个账号 (该厂
                // 最大角色群体) 落进 default 分支拿到了 ALL 而不是 SELF。yield_operator
                // 与 operator 同级 (Level 30, "仅负责报工写入, 无管理界面"), 应同待遇。
                return DataScope.SELF;
            default:
                return DataScope.ALL;
        }
    }

    /** role_code 维度节流: 同一角色的查询失败 WARN 在 {@link #WARN_THROTTLE} 窗口内只打一次. */
    private void warnThrottled(String roleCode, Exception ex) {
        Instant now = clock.instant();
        Instant previous = lastWarnedAt.get(roleCode);
        if (previous != null && previous.plus(WARN_THROTTLE).isAfter(now)) {
            log.debug("DataScopeResolver: SQL error resolving role {} (throttled, last WARN at {}): {}",
                    roleCode, previous, ex.getMessage());
            return;
        }
        lastWarnedAt.put(roleCode, now);
        log.warn("DataScopeResolver: SQL error resolving role {}, fail-closed to SELF: {}", roleCode, ex.getMessage());
    }

    // ==================== Sprint 6 W2-B: chain resolution ====================

    /** BFS 最大深度, 防 reportsTo 环 (理论不该有) 或意外深嵌套. */
    private static final int MAX_CHAIN_DEPTH = 10;

    /**
     * Sprint 6 W2-B — 给定 DataScopeContext, 解析 created_by IN list 的 user ids.
     *
     * <ul>
     *   <li>SELF → [userId]</li>
     *   <li>SELF_AND_BELOW → BFS reportsTo chain (含自己)</li>
     *   <li>DEPT_AND_BELOW → 本部门所有 active user (MVP, Sprint 7 加 sub-department)</li>
     *   <li>ALL / CUSTOM → empty list (caller 不应调用, defensive)</li>
     * </ul>
     *
     * <p>失败 fallback: 返 [userId] (SELF semantics, fail-closed for scope tightening).
     */
    public List<Long> resolveCreatedByChain(DataScopeContext ctx) {
        if (ctx == null || ctx.getUserId() == null || ctx.getScope() == null) {
            return Collections.emptyList();
        }
        Long selfId = ctx.getUserId();
        String factoryId = ctx.getFactoryId();
        switch (ctx.getScope()) {
            case SELF:
                return List.of(selfId);
            case SELF_AND_BELOW:
                return resolveSelfAndBelowChain(factoryId, selfId);
            case DEPT_AND_BELOW:
                return resolveDeptChain(factoryId, ctx.getDepartment(), selfId);
            case ALL:
            case CUSTOM:
            default:
                return Collections.emptyList();
        }
    }

    /**
     * BFS down reportsTo links 从 selfId 开始, 含 self.
     * MAX_CHAIN_DEPTH 防御 — 不应有环, 但容错.
     */
    private List<Long> resolveSelfAndBelowChain(String factoryId, Long selfId) {
        Set<Long> visited = new HashSet<>();
        visited.add(selfId);
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(selfId);
        int depth = 0;
        try {
            while (!frontier.isEmpty() && depth < MAX_CHAIN_DEPTH) {
                int levelSize = frontier.size();
                for (int i = 0; i < levelSize; i++) {
                    Long mgrId = frontier.poll();
                    if (mgrId == null) continue;
                    List<Long> subs = userRepository.findSubordinateIdsByReportsTo(factoryId, mgrId);
                    for (Long subId : subs) {
                        if (subId != null && visited.add(subId)) {
                            frontier.add(subId);
                        }
                    }
                }
                depth++;
            }
        } catch (Exception ex) {
            log.warn("DataScopeResolver: SELF_AND_BELOW chain resolution failed for user {}: {}, fallback [self]",
                    selfId, ex.getMessage());
            return List.of(selfId);
        }
        return new ArrayList<>(visited);
    }

    /**
     * DEPT_AND_BELOW MVP — 本部门所有 active user, 不递归 sub-department.
     * department null → fallback [selfId] (SELF semantics).
     */
    private List<Long> resolveDeptChain(String factoryId, String department, Long selfId) {
        if (department == null || department.isEmpty()) {
            log.debug("DataScopeResolver: DEPT_AND_BELOW with null department for user {}, fallback [self]", selfId);
            return List.of(selfId);
        }
        try {
            List<Long> deptUsers = userRepository.findUserIdsByDepartment(factoryId, department);
            // 总会包含 self (self 在自己部门), 但容错: ensure included
            if (!deptUsers.contains(selfId)) {
                List<Long> result = new ArrayList<>(deptUsers);
                result.add(selfId);
                return result;
            }
            return deptUsers;
        } catch (Exception ex) {
            log.warn("DataScopeResolver: DEPT_AND_BELOW chain resolution failed for user {} dept {}: {}, fallback [self]",
                    selfId, department, ex.getMessage());
            return List.of(selfId);
        }
    }
}
