package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.engine.SchedulerGrantBinding;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delegated grants that let one cron config run one write tool as its run-as user.
 *
 * <p>Who: the HTTP request's typed factory actor, re-read from the database on every call (active
 * account, same factory, current role and current {@code system:read_write} permission) — the
 * JWT's role claim alone is not trusted. Issuing additionally requires the grantor to be the
 * config's run-as user. What: the stored config's tool, parameters (never the request's), config
 * revision and the tool's current descriptor version; the tool must be a write tool whose runtime
 * policy admits SCHEDULER. Budget and expiry are bounded. Event chains get no grants (see
 * {@link com.cretas.aims.engine.AutonomousToolRunner}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/config/v2/autonomous-grants")
@Tag(name = "自动任务委托授权")
public class AutonomousGrantController {

    static final String GRANT_ROLE = "factory_super_admin";
    static final String GRANT_PERMISSION = "system:read_write";

    private final AutonomousGrantService grants;
    private final FactorySchedulerConfigRepository schedules;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final UserRepository users;
    private final PermissionService permissions;

    /** sourceType SCHEDULER, sourceRef = the scheduler config id. */
    public record GrantRequest(String sourceType, String sourceRef, String toolName, Integer maxRuns,
            LocalDateTime expiresAt) { }

    public record GrantView(String id, String sourceType, String sourceRef, String toolName, Long runAsUserId,
            String descriptorVersion, int maxRuns, int usedRuns, LocalDateTime expiresAt, Long grantedBy,
            LocalDateTime grantedAt, LocalDateTime revokedAt, Long revokedBy, String state) {
        static GrantView of(AutonomousToolGrant grant, LocalDateTime now) {
            String state = grant.getRevokedAt() != null ? "REVOKED" : !now.isBefore(grant.getExpiresAt()) ? "EXPIRED"
                    : grant.getUsedRuns() >= grant.getMaxRuns() ? "EXHAUSTED" : "ACTIVE";
            return new GrantView(grant.getId(), grant.getSourceType(), grant.getSourceRef(), grant.getToolName(),
                    grant.getRunAsUserId(), grant.getDescriptorVersion(), grant.getMaxRuns(), grant.getUsedRuns(),
                    grant.getExpiresAt(), grant.getGrantedBy(), grant.getGrantedAt(), grant.getRevokedAt(),
                    grant.getRevokedBy(), state);
        }
    }

    @GetMapping
    @RequireRole({GRANT_ROLE})
    @Operation(summary = "列出自动任务委托授权")
    public ApiResponse<List<GrantView>> list(@PathVariable String factoryId) {
        currentGrantAdmin(factoryId);
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(grants.list(factoryId).stream().map(grant -> GrantView.of(grant, now)).toList());
    }

    @PostMapping
    @RequirePermission({GRANT_PERMISSION})
    @RequireRole({GRANT_ROLE})
    @Operation(summary = "为一个定时任务签发写操作委托授权")
    public ApiResponse<GrantView> issue(@PathVariable String factoryId, @RequestBody GrantRequest body) {
        User grantor = currentGrantAdmin(factoryId);
        if (body == null || body.sourceType() == null || body.sourceRef() == null || body.toolName() == null
                || body.maxRuns() == null || body.expiresAt() == null) {
            throw bad("sourceType、sourceRef、toolName、maxRuns、expiresAt 都必须填写");
        }
        if (!"SCHEDULER".equals(body.sourceType())) {
            throw bad("目前只有定时任务可以签发写操作授权（sourceType 只能是 SCHEDULER）");
        }
        FactorySchedulerConfig config = scheduled(factoryId, body.sourceRef());
        if (!body.toolName().equals(config.getToolOrMethod())) {
            throw bad("授权的工具与该任务配置的工具不一致：" + config.getToolOrMethod());
        }
        if (!Objects.equals(grantor.getId(), config.getRunAsUserId())) {
            throw new BusinessException(409, "只能为你自己启用（执行人是你）的任务签发授权")
                    .withHint("先以你的账号启用该定时任务，再签发授权");
        }
        ToolExecutor tool = toolRegistry.getExecutor(body.toolName())
                .orElseThrow(() -> bad("工具未注册：" + body.toolName()));
        if (!ToolAccessModes.isWrite(tool)) {
            throw bad("只读工具不需要授权：" + body.toolName());
        }
        AutonomousToolPolicy.requireSourceAllowed(body.toolName(), ToolExecutionSource.SCHEDULER);
        LocalDateTime now = LocalDateTime.now();
        try {
            return ApiResponse.success(GrantView.of(grants.issue(binding(config, tool), body.maxRuns(),
                    body.expiresAt(), grantor.getId(), now), now));
        } catch (IllegalArgumentException invalid) {
            throw bad(invalid.getMessage());
        }
    }

    @PostMapping("/{grantId}/revoke")
    @RequirePermission({GRANT_PERMISSION})
    @RequireRole({GRANT_ROLE})
    @Operation(summary = "撤销委托授权（已开始的那次运行不回滚）")
    public ApiResponse<GrantView> revoke(@PathVariable String factoryId, @PathVariable String grantId) {
        // Any current factory super admin may stop a grant, not only its grantor.
        User by = currentGrantAdmin(factoryId);
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(grants.revoke(factoryId, grantId, by.getId(), now)
                .map(grant -> GrantView.of(grant, now))
                .orElseThrow(() -> new BusinessException(404, "授权不存在或不属于本厂")));
    }

    /**
     * What a run of this config will be checked against: the scheduler builds the same binding from
     * the config it fires, so the two must stay one computation.
     */
    public AutonomousGrantService.Binding binding(FactorySchedulerConfig config, ToolExecutor tool) {
        return SchedulerGrantBinding.of(config, tool.getVersion(), objectMapper);
    }

    /**
     * The typed factory actor of this request, re-read from the database: the account must exist,
     * be active, belong to this factory, hold the grant role now and have the grant permission now.
     */
    private User currentGrantAdmin(String factoryId) {
        Long actorId = AutonomousToolPolicy.requireRunAsUser(factoryId);
        User user = users.findById(actorId)
                .filter(row -> Boolean.TRUE.equals(row.getIsActive()) && factoryId.equals(row.getFactoryId()))
                .orElseThrow(() -> new BusinessException(403, "当前账号已停用或不属于本厂，不能管理自动任务授权")
                        .withHint("请重新登录后再试"));
        if (!GRANT_ROLE.equalsIgnoreCase(user.getRoleCode()) || !permissions.hasAllPermissions(user, GRANT_PERMISSION)) {
            throw new BusinessException(403, "当前账号已没有管理自动任务授权的角色或权限")
                    .withCode("AUTONOMOUS_GRANT_ADMIN_REQUIRED")
                    .withHint("需要本厂超级管理员且具备系统读写权限；权限刚被调整的话以数据库为准");
        }
        return user;
    }

    private FactorySchedulerConfig scheduled(String factoryId, String sourceRef) {
        long id;
        try {
            id = Long.parseLong(sourceRef);
        } catch (NumberFormatException e) {
            throw bad("定时任务的 sourceRef 应为配置 id");
        }
        return schedules.findById(id)
                .filter(row -> factoryId.equals(row.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在或不属于本厂"));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(400, message);
    }
}
