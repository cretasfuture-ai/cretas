package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.engine.AutonomousGrantIssuer;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
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
 * <p>The request's typed factory actor is re-read from the database by {@link AutonomousGrantIssuer}
 * on every call (active, same factory, factory_super_admin now; the JWT role claim alone is not
 * trusted). A factory super admin holds every permission by the existing semantics, so grants are
 * stopped by revoking them, deactivating the account or changing its role, not by permission
 * overrides; the grantor's status and role are re-read from the database at every scheduled
 * consumption ({@link AutonomousGrantIssuer#consumeForScheduledFire}), so the next fire after such a
 * change is refused. Issuing also requires the grantor to be the config's run-as user, a write tool whose
 * runtime policy admits SCHEDULER, no other active grant for the same binding, and binds the grant
 * to the stored parameters, config revision and the tool's current descriptor version.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/config/v2/autonomous-grants")
@Tag(name = "自动任务委托授权")
public class AutonomousGrantController {

    static final String GRANT_PERMISSION = "system:read_write";

    private final AutonomousGrantService grants;
    private final AutonomousGrantIssuer issuer;
    private final ToolRegistry toolRegistry;

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
    @RequireRole({AutonomousGrantIssuer.GRANT_ROLE})
    @Operation(summary = "列出自动任务委托授权")
    public ApiResponse<List<GrantView>> list(@PathVariable String factoryId) {
        issuer.currentAdmin(factoryId, AutonomousToolPolicy.requireRunAsUser(factoryId));
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(grants.list(factoryId).stream().map(grant -> GrantView.of(grant, now)).toList());
    }

    @PostMapping
    @RequirePermission({GRANT_PERMISSION})
    @RequireRole({AutonomousGrantIssuer.GRANT_ROLE})
    @Operation(summary = "为一个定时任务签发写操作委托授权")
    public ApiResponse<GrantView> issue(@PathVariable String factoryId, @RequestBody GrantRequest body) {
        Long actorId = AutonomousToolPolicy.requireRunAsUser(factoryId);
        if (body == null || body.sourceType() == null || body.sourceRef() == null || body.toolName() == null
                || body.maxRuns() == null || body.expiresAt() == null) {
            throw bad("sourceType、sourceRef、toolName、maxRuns、expiresAt 都必须填写");
        }
        if (!"SCHEDULER".equals(body.sourceType())) {
            throw bad("目前只有定时任务可以签发写操作授权（sourceType 只能是 SCHEDULER）");
        }
        long configId;
        try {
            configId = Long.parseLong(body.sourceRef());
        } catch (NumberFormatException e) {
            throw bad("定时任务的 sourceRef 应为配置 id");
        }
        ToolExecutor tool = toolRegistry.getExecutor(body.toolName())
                .orElseThrow(() -> bad("工具未注册：" + body.toolName()));
        if (!ToolAccessModes.isWrite(tool)) {
            throw bad("只读工具不需要授权：" + body.toolName());
        }
        AutonomousToolPolicy.requireSourceAllowed(body.toolName(), ToolExecutionSource.SCHEDULER);
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(GrantView.of(issuer.issue(factoryId, actorId, configId, body.toolName(),
                tool.getVersion(), body.maxRuns(), body.expiresAt(), now), now));
    }

    @PostMapping("/{grantId}/revoke")
    @RequirePermission({GRANT_PERMISSION})
    @RequireRole({AutonomousGrantIssuer.GRANT_ROLE})
    @Operation(summary = "撤销委托授权（已开始的那次运行不回滚）")
    public ApiResponse<GrantView> revoke(@PathVariable String factoryId, @PathVariable String grantId) {
        // Any current factory super admin may stop a grant, not only its grantor.
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(GrantView.of(
                issuer.revoke(factoryId, AutonomousToolPolicy.requireRunAsUser(factoryId), grantId, now), now));
    }

    private static BusinessException bad(String message) {
        return new BusinessException(400, message);
    }
}
