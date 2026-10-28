package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.shortage.ShortageCaseActionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 缺料实例上的动作 (S4 L1 切片 2): 采购建议 / 生成采购草稿 / 人工决定, 以及执行台账。
 *
 * <p>操作人取 JWT 验过的身份; 工厂用户的 token 必须属于路径上的工厂。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/shortage-cases/{caseId}")
@Tag(name = "缺料异常", description = "采购交接与人工决定")
public class ShortageCaseActionController {

    private final ShortageCaseActionService actionService;
    private final ShortageCaseActionRepository actionRepository;
    private final ShortageCaseRepository caseRepository;

    @Data
    public static class DraftRequest {
        @NotBlank(message = "缺少物料")
        private String materialTypeId;
    }

    @Data
    public static class DecisionRequest {
        @NotNull(message = "缺少处理决定")
        private ShortageCaseAction.Decision decision;
        @NotBlank(message = "请写明原因")
        private String reason;
        /** 转人工时可指定处理人。 */
        private Long ownerUserId;
    }

    public record ActionView(Long id, String actionType, String materialTypeId, String status, String resultRef,
                             BigDecimal quantity, String unit, String decision, String reason, String actorType,
                             Long actorId, LocalDateTime createdAt) {
        static ActionView of(ShortageCaseAction a) {
            return new ActionView(a.getId(), a.getActionType().name(), a.getMaterialTypeId(), a.getStatus().name(),
                    a.getResultRef(), a.getQuantity(), a.getUnit(), a.getDecision() == null ? null : a.getDecision().name(),
                    a.getReason(), a.getActorType(), a.getActorId(), a.getCreatedAt());
        }
    }

    @RequirePermission({"procurement:read", "procurement:read_write", "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write", "quality:read", "quality:read_write",
            "sales:read", "sales:read_write"})
    @GetMapping("/actions")
    @Operation(summary = "执行台账", description = "生成过哪些采购草稿、做过哪些决定")
    public ApiResponse<List<ActionView>> actions(@PathVariable String factoryId, @PathVariable String caseId) {
        ShortageCase c = own(factoryId, caseId);
        return ApiResponse.success("获取成功",
                actionRepository.findByCaseIdOrderByIdAsc(c.getId()).stream().map(ActionView::of).toList());
    }

    @RequirePermission({"procurement:read", "procurement:read_write"})
    @GetMapping("/procurement-proposals")
    @Operation(summary = "采购建议", description = "缺口 − 在途, 带供应商 / 起订量 / 交期; 单位不同不换算")
    public ApiResponse<List<ShortageCaseActionService.ProcurementProposal>> proposals(@PathVariable String factoryId,
                                                                                     @PathVariable String caseId) {
        return ApiResponse.success("获取成功", actionService.proposals(factoryId, caseId));
    }

    @RequirePermission({"procurement:read_write"})
    @PostMapping("/procurement-drafts")
    @Operation(summary = "生成采购草稿", description = "走原有采购服务, 只到草稿; 同一物料已有在走的单就复用")
    public ApiResponse<ShortageCaseActionService.DraftResult> createDraft(@PathVariable String factoryId,
                                                                         @PathVariable String caseId,
                                                                         @Valid @RequestBody DraftRequest request,
                                                                         HttpServletRequest http) {
        var result = actionService.createProcurementDraft(factoryId, caseId, request.getMaterialTypeId(), actor(http, factoryId));
        return ApiResponse.success(result.reused() ? "已有在走的采购单, 未重复生成" : "采购草稿已生成", result);
    }

    @RequirePermission({"procurement:read_write", "production:read_write"})
    @PostMapping("/decision")
    @Operation(summary = "取消 / 转人工 / 关闭", description = "原因必填; 关闭只在缺口已解决时允许")
    public ApiResponse<ShortageCase.Stage> decide(@PathVariable String factoryId, @PathVariable String caseId,
                                                  @Valid @RequestBody DecisionRequest request, HttpServletRequest http) {
        ShortageCase c = actionService.decide(factoryId, caseId, request.getDecision(), request.getReason(),
                request.getOwnerUserId(), actor(http, factoryId));
        return ApiResponse.success("已处理", c.getStage());
    }

    private ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
    }

    /** JWT 验过的身份; 工厂用户必须属于路径工厂, 平台角色要有跨厂权限。 */
    static ActorRef actor(HttpServletRequest request, String factoryId) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法识别当前操作人"));
        if (actor.isPlatformAdmin()) {
            if (!actor.hasFactoryBypass()) {
                throw new BusinessException(403, "当前平台角色不能处理工厂的缺料实例");
            }
        } else if (actor.factoryId() == null || !actor.factoryId().equals(factoryId)) {
            throw new BusinessException(403, "登录账号不属于该工厂");
        }
        return new ActorRef(actor.type(), actor.id());
    }
}
