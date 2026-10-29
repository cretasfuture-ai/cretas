package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.engine.AutonomousGrantIssuer;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.engine.AutonomousWriteSwitch;
import com.cretas.aims.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S6-04: a factory's super admin can switch every delegated scheduled write of the factory off (and
 * on again) in one step. The actor is re-read from the database like the grant endpoints; switching
 * off takes effect at the next fire, before any grant is consumed.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/config/v2/autonomous-writes/switch")
@Tag(name = "自动任务委托授权")
public class AutonomousWriteSwitchController {

    private final AutonomousWriteSwitch writeSwitch;
    private final AutonomousGrantIssuer issuer;

    public record SwitchRequest(Boolean writesEnabled, String reason) { }

    /** No row yet: scheduled writes have never been switched on, so they do not run (fail closed). */
    public record SwitchView(String factoryId, boolean writesEnabled, String reason, Long changedBy,
            LocalDateTime changedAt) { }

    @GetMapping
    @RequireRole({AutonomousGrantIssuer.GRANT_ROLE})
    @Operation(summary = "查看本厂自动任务写操作总开关")
    public ApiResponse<SwitchView> get(@PathVariable String factoryId) {
        issuer.currentAdmin(factoryId, AutonomousToolPolicy.requireRunAsUser(factoryId));
        return ApiResponse.success(writeSwitch.state(factoryId)
                .map(state -> new SwitchView(state.factoryId(), state.writesEnabled(), state.reason(),
                        state.changedBy(), state.changedAt()))
                .orElse(new SwitchView(factoryId, false, null, null, null)));
    }

    @PutMapping
    @RequirePermission({AutonomousGrantController.GRANT_PERMISSION})
    @RequireRole({AutonomousGrantIssuer.GRANT_ROLE})
    @Operation(summary = "打开或关闭本厂全部自动任务写操作（默认关闭；下次触发起生效，已在执行的不回滚）")
    public ApiResponse<SwitchView> set(@PathVariable String factoryId, @RequestBody SwitchRequest body) {
        Long actorId = AutonomousToolPolicy.requireRunAsUser(factoryId);
        issuer.currentAdmin(factoryId, actorId);
        if (body == null || body.writesEnabled() == null) {
            throw new BusinessException(400, "writesEnabled 必须填写");
        }
        if (body.reason() != null && body.reason().length() > 200) {
            throw new BusinessException(400, "原因最长 200 个字符");
        }
        AutonomousWriteSwitch.State state = writeSwitch.set(factoryId, body.writesEnabled(), body.reason(), actorId,
                LocalDateTime.now());
        return ApiResponse.success(new SwitchView(state.factoryId(), state.writesEnabled(), state.reason(),
                state.changedBy(), state.changedAt()));
    }
}
