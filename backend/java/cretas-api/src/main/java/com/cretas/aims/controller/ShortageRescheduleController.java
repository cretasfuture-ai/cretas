package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.shortage.RescheduleProposalRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.shortage.ShortageRescheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 缺料实例上的局部重排 (S4 L1 切片 3): 生成方案 (预览差异) → 确认应用。
 *
 * <p>应用时基线或按当前数据重算的结果有一处对不上就整体 409「方案已过期」, 不改任何排程。
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/shortage-cases/{caseId}/reschedule-proposals")
@Tag(name = "缺料异常", description = "局部重排")
public class ShortageRescheduleController {

    private final ShortageRescheduleService rescheduleService;
    private final RescheduleProposalRepository proposalRepository;
    private final ShortageCaseRepository caseRepository;
    private final com.cretas.aims.repository.shortage.ShortageCaseActionRepository actionRepository;
    private final com.cretas.aims.service.shortage.ShortageOutcomeCheckService outcomeChecks;

    @Data
    public static class ApplyRequest {
        @NotBlank(message = "请选择一个候选方案")
        private String candidateKey;
    }

    public record ProposalView(String id, String status, String statusLabel, List<Map<String, Object>> candidates,
                               String appliedCandidate, LocalDateTime expiresAt, LocalDateTime createdAt,
                               LocalDateTime appliedAt) {
        static ProposalView of(RescheduleProposal p) {
            return new ProposalView(p.getId(), p.getStatus().name(), label(p.getStatus()), p.getCandidates(),
                    p.getAppliedCandidate(), p.getExpiresAt(), p.getCreatedAt(), p.getAppliedAt());
        }

        private static String label(RescheduleProposal.Status s) {
            return switch (s) {
                case OPEN -> "待确认";
                case APPLIED -> "已应用";
                case STALE -> "已过期";
            };
        }
    }

    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write",
            "procurement:read", "procurement:read_write"})
    @GetMapping
    @Operation(summary = "最近的重排方案")
    public ApiResponse<List<ProposalView>> recent(@PathVariable String factoryId, @PathVariable String caseId) {
        caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
        return ApiResponse.success("获取成功", proposalRepository
                .findTop5ByFactoryIdAndCaseIdOrderByCreatedAtDesc(factoryId, caseId).stream().map(ProposalView::of).toList());
    }

    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @PostMapping
    @Operation(summary = "生成重排方案", description = "同线顺延 / 顺延并换线 / 转人工; 只预览, 不改排程")
    public ApiResponse<ProposalView> propose(@PathVariable String factoryId, @PathVariable String caseId,
                                             HttpServletRequest http) {
        RescheduleProposal p = rescheduleService.propose(factoryId, caseId, ShortageCaseActionController.actor(http, factoryId));
        return ApiResponse.success("重排方案已生成, " + ShortageRescheduleService.ttlMinutes() + " 分钟内有效", ProposalView.of(p));
    }

    @RequirePermission({"production:read_write", "scheduling:read_write"})
    @PostMapping("/{proposalId}/apply")
    @Operation(summary = "应用重排方案", description = "排程在生成方案后变过就 409, 不写任何东西")
    public ApiResponse<ProposalView> apply(@PathVariable String factoryId, @PathVariable String caseId,
                                           @PathVariable String proposalId, @Valid @RequestBody ApplyRequest request,
                                           HttpServletRequest http) {
        var actor = ShortageCaseActionController.actor(http, factoryId);
        RescheduleProposal p = rescheduleService.apply(factoryId, caseId, proposalId, request.getCandidateKey(), actor);
        // 执行后核对: 按台账这一行 (result_ref = 方案 id) 核对交期与缺口, 结论写回实例, 不默认成功
        try {
            Long actionId = actionRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                    .filter(a -> a.getActionType() == com.cretas.aims.entity.shortage.ShortageCaseAction.ActionType.RESCHEDULE_APPLIED
                            && p.getId().equals(a.getResultRef()))
                    .map(com.cretas.aims.entity.shortage.ShortageCaseAction::getId)
                    .reduce((x, y) -> y).orElse(null);
            var chk = outcomeChecks.check(factoryId, caseId,
                    com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck.Trigger.RESCHEDULE_APPLIED, actionId, actor);
            return ApiResponse.success("排程已调整; 核对结果: " + chk.getDetail(), ProposalView.of(p));
        } catch (RuntimeException checkFailure) {
            // apply has committed. Preserve that acknowledgement while making the missing
            // outcome verification explicit; never invite replay of the applied proposal.
            log.error("排程已调整但结果核对未完成: factoryId={}, caseId={}, proposalId={}",
                    factoryId, caseId, proposalId, checkFailure);
            return ApiResponse.success("排程已调整; 结果核对未完成，交期是否达成尚未确认，请核对当前排程，勿重复应用。", ProposalView.of(p));
        }
    }
}
