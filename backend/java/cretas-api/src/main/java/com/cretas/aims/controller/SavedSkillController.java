package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.skill.contract.SavedSkillService;
import com.cretas.aims.service.skill.contract.SkillRunStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S6-02 reusable Skills of a factory: read a run's receipt, save a successful run with its
 * parameter contract, list, switch off and rerun with new parameters.
 *
 * <p>Only this factory's own users act here. Running needs no permission of its own: every tool of
 * the Skill goes through the tool gateway with the caller's current permissions, so a saved Skill
 * can never do more than the person running it may do.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/skills")
@Tag(name = "可复用 Skill")
public class SavedSkillController {

    private final SavedSkillService savedSkills;

    public record RunRequest(Map<String, Object> params) { }

    public record EnabledRequest(Boolean enabled) { }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "查看一次 Skill 运行的回执")
    public ApiResponse<SkillRunStore.Run> run(@PathVariable String factoryId, @PathVariable String runId) {
        factoryUser(factoryId);
        return ApiResponse.success(savedSkills.run(factoryId, runId));
    }

    @PostMapping("/saved")
    @Operation(summary = "把一次成功运行保存为可复用 Skill")
    public ApiResponse<SkillRunStore.SavedSkill> save(@PathVariable String factoryId,
            @RequestBody SavedSkillService.SaveRequest body) {
        AuthenticatedActor actor = factoryUser(factoryId);
        return ApiResponse.success(savedSkills.save(factoryId, actor.id(), body));
    }

    @GetMapping("/saved")
    @Operation(summary = "列出本厂的可复用 Skill")
    public ApiResponse<List<SkillRunStore.SavedSkill>> list(@PathVariable String factoryId) {
        factoryUser(factoryId);
        return ApiResponse.success(savedSkills.list(factoryId));
    }

    @PostMapping("/saved/{savedSkillId}/enabled")
    @Operation(summary = "启用或停用一个可复用 Skill")
    public ApiResponse<SkillRunStore.SavedSkill> setEnabled(@PathVariable String factoryId,
            @PathVariable String savedSkillId, @RequestBody EnabledRequest body) {
        AuthenticatedActor actor = factoryUser(factoryId);
        if (body == null || body.enabled() == null) {
            throw new BusinessException(400, "enabled 必须填写");
        }
        SkillRunStore.SavedSkill saved = savedSkills.list(factoryId).stream()
                .filter(candidate -> candidate.id().equals(savedSkillId)).findFirst()
                .orElseThrow(() -> new BusinessException(404, "没有找到这个 Skill"));
        if (!saved.createdBy().equals(actor.id()) && !"factory_super_admin".equals(actor.role())) {
            throw new BusinessException(403, "只有保存人或本厂超级管理员可以启用或停用");
        }
        return ApiResponse.success(savedSkills.setEnabled(factoryId, savedSkillId, body.enabled()));
    }

    @PostMapping("/saved/{savedSkillId}/run")
    @Operation(summary = "用新参数运行一个可复用 Skill（参数先按契约校验，工具经工具网关执行）")
    public ApiResponse<SavedSkillService.RunResult> runSaved(@PathVariable String factoryId,
            @PathVariable String savedSkillId, @RequestBody(required = false) RunRequest body) {
        AuthenticatedActor actor = factoryUser(factoryId);
        SavedSkillService.RunResult result = savedSkills.runSaved(factoryId, actor.id(), actor.role(), savedSkillId,
                body == null ? Map.of() : body.params(), "saved-skill-" + UUID.randomUUID());
        if (!result.result().isSuccess()) {
            String message = result.result().getMessage() == null ? "Skill 未完成" : result.result().getMessage();
            throw new BusinessException(400, result.result().getRunId() == null
                    ? message : message + "（运行回执 " + result.result().getRunId() + "）");
        }
        return ApiResponse.success(result);
    }

    private static AuthenticatedActor factoryUser(String factoryId) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentRequest()
                .orElseThrow(() -> new BusinessException(401, "无法确认当前用户，请重新登录"));
        if (actor.type() != AuthenticatedActorType.FACTORY_USER || actor.id() == null
                || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "只能由本厂账号操作本厂的 Skill");
        }
        return actor;
    }
}
