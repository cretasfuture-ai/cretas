package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delegated grants that let one cron config or trigger-chain step run one write tool as its
 * run-as user. The grantor must be that factory's user who is the source's run-as user; the
 * parameters are taken from the stored config (never from the request), the tool must be a write
 * tool whose runtime policy admits the source, and budget/expiry are bounded.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/config/v2/autonomous-grants")
@Tag(name = "自动任务委托授权")
public class AutonomousGrantController {

    private final AutonomousGrantService grants;
    private final FactorySchedulerConfigRepository schedules;
    private final FactoryTriggerChainRepository chains;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /** sourceType SCHEDULER (sourceRef = config id) or TRIGGER (sourceRef = chainId:stepOrder). */
    public record GrantRequest(String sourceType, String sourceRef, String toolName, Integer maxRuns,
            LocalDateTime expiresAt) { }

    public record GrantView(String id, String sourceType, String sourceRef, String toolName, Long runAsUserId,
            int maxRuns, int usedRuns, LocalDateTime expiresAt, LocalDateTime grantedAt, LocalDateTime revokedAt,
            String state) {
        static GrantView of(AutonomousToolGrant grant, LocalDateTime now) {
            String state = grant.getRevokedAt() != null ? "REVOKED" : !now.isBefore(grant.getExpiresAt()) ? "EXPIRED"
                    : grant.getUsedRuns() >= grant.getMaxRuns() ? "EXHAUSTED" : "ACTIVE";
            return new GrantView(grant.getId(), grant.getSourceType(), grant.getSourceRef(), grant.getToolName(),
                    grant.getRunAsUserId(), grant.getMaxRuns(), grant.getUsedRuns(), grant.getExpiresAt(),
                    grant.getGrantedAt(), grant.getRevokedAt(), state);
        }
    }

    @GetMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "列出自动任务委托授权")
    public ApiResponse<List<GrantView>> list(@PathVariable String factoryId) {
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(grants.list(factoryId).stream().map(grant -> GrantView.of(grant, now)).toList());
    }

    @PostMapping
    @RequirePermission({"system:read_write"})
    @RequireRole({"factory_super_admin"})
    @Operation(summary = "为一个定时任务或事件链步骤签发写操作委托授权")
    public ApiResponse<GrantView> issue(@PathVariable String factoryId, @RequestBody GrantRequest body) {
        Long grantor = AutonomousToolPolicy.requireRunAsUser(factoryId);
        if (body == null || body.sourceType() == null || body.sourceRef() == null || body.toolName() == null
                || body.maxRuns() == null || body.expiresAt() == null) {
            throw bad("sourceType、sourceRef、toolName、maxRuns、expiresAt 都必须填写");
        }
        ToolExecutionSource source = switch (body.sourceType()) {
            case "SCHEDULER" -> ToolExecutionSource.SCHEDULER;
            case "TRIGGER" -> ToolExecutionSource.TRIGGER;
            default -> throw bad("sourceType 只能是 SCHEDULER 或 TRIGGER");
        };
        Bound bound = source == ToolExecutionSource.SCHEDULER
                ? scheduled(factoryId, body.sourceRef()) : chainStep(factoryId, body.sourceRef());
        if (!body.toolName().equals(bound.toolName())) {
            throw bad("授权的工具与该任务配置的工具不一致：" + bound.toolName());
        }
        if (!Objects.equals(grantor, bound.runAsUserId())) {
            throw new BusinessException(409, "只能为你自己启用（执行人是你）的任务签发授权")
                    .withHint("先以你的账号启用该定时任务或事件链，再签发授权");
        }
        ToolExecutor tool = toolRegistry.getExecutor(body.toolName())
                .orElseThrow(() -> bad("工具未注册：" + body.toolName()));
        if (!ToolAccessModes.isWrite(tool)) {
            throw bad("只读工具不需要授权：" + body.toolName());
        }
        AutonomousToolPolicy.requireSourceAllowed(body.toolName(), source);
        String digest = ToolCommandDigest.parametersHash(objectMapper.valueToTree(bound.params()));
        LocalDateTime now = LocalDateTime.now();
        try {
            return ApiResponse.success(GrantView.of(grants.issue(factoryId, source.name(), body.sourceRef(),
                    body.toolName(), grantor, digest, body.maxRuns(), body.expiresAt(), grantor, now), now));
        } catch (IllegalArgumentException invalid) {
            throw bad(invalid.getMessage());
        }
    }

    @PostMapping("/{grantId}/revoke")
    @RequirePermission({"system:read_write"})
    @RequireRole({"factory_super_admin"})
    @Operation(summary = "撤销委托授权（已开始的那次运行不回滚）")
    public ApiResponse<GrantView> revoke(@PathVariable String factoryId, @PathVariable String grantId) {
        Long by = AutonomousToolPolicy.requireRunAsUser(factoryId);
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.success(grants.revoke(factoryId, grantId, by, now)
                .map(grant -> GrantView.of(grant, now))
                .orElseThrow(() -> new BusinessException(404, "授权不存在或不属于本厂")));
    }

    private record Bound(String toolName, Long runAsUserId, Map<String, Object> params) { }

    private Bound scheduled(String factoryId, String sourceRef) {
        long id;
        try {
            id = Long.parseLong(sourceRef);
        } catch (NumberFormatException e) {
            throw bad("定时任务的 sourceRef 应为配置 id");
        }
        FactorySchedulerConfig config = schedules.findById(id)
                .filter(row -> factoryId.equals(row.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在或不属于本厂"));
        return new Bound(config.getToolOrMethod(), config.getRunAsUserId(),
                config.getParams() == null ? Map.of() : config.getParams());
    }

    @SuppressWarnings("unchecked")
    private Bound chainStep(String factoryId, String sourceRef) {
        String[] parts = sourceRef.split(":");
        if (parts.length != 2) throw bad("事件链的 sourceRef 应为 链id:步骤序号");
        long chainId;
        int order;
        try {
            chainId = Long.parseLong(parts[0]);
            order = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw bad("事件链的 sourceRef 应为 链id:步骤序号");
        }
        FactoryTriggerChain chain = chains.findById(chainId)
                .filter(row -> factoryId.equals(row.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "事件链不存在或不属于本厂"));
        Map<String, Object> step = (chain.getSteps() == null ? List.<Map<String, Object>>of() : chain.getSteps()).stream()
                .filter(row -> row.get("order") instanceof Number n && n.intValue() == order)
                .findFirst().orElseThrow(() -> new BusinessException(404, "事件链中没有第 " + order + " 步"));
        Object params = step.getOrDefault("params", Map.of());
        if (!(params instanceof Map<?, ?> map)) throw bad("该步骤的参数不是对象");
        return new Bound((String) step.get("tool"), chain.getRunAsUserId(), (Map<String, Object>) map);
    }

    private static BusinessException bad(String message) {
        return new BusinessException(400, message);
    }
}
