package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.DeliveryRiskCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.shortage.DeliveryRiskCaseStore;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交付风险异常: 扫描本厂未交订单, 有风险的落成处理实例 (与缺料同一种实例), 走 指派 → 处理 → 写结果 → 核对。
 * 不改排程、不下采购。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/delivery-risks")
@Tag(name = "交付风险", description = "可能延期的销售订单: 证据、影响、处理建议、负责人、处理结果")
public class DeliveryRiskController {

    private final DeliveryRiskService riskService;
    private final DeliveryRiskCaseStore store;
    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final ShortageCaseActionRepository actionRepository;
    private final ShortageCaseOutcomeCheckRepository checkRepository;
    private final DeliveryRiskCaseDetailRepository detailRepository;
    private final SalesOrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public record CaseView(String id, String salesOrderId, String orderNumber, String customerName, LocalDate dueDate,
                           String riskLevel, String stage, String stageLabel, String ownerRole, Long ownerUserId,
                           String ownerName, LocalDateTime assessedAt, LocalDateTime closedAt, LocalDateTime createdAt) {
    }

    public record Assignee(Long id, String name, String role) {
    }

    public record AssignRequest(Long ownerUserId, String task) {
    }

    public record FinishRequest(ShortageCaseAction.Decision decision, String result) {
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write"})
    @GetMapping
    @Operation(summary = "本厂交付风险实例", description = "默认只看未结束的")
    public ApiResponse<List<CaseView>> list(@PathVariable String factoryId,
                                            @RequestParam(defaultValue = "true") boolean openOnly,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        var rows = caseRepository.findForFactoryBySources(factoryId, openOnly, List.of(ShortageCase.SourceType.DELIVERY_RISK),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return ApiResponse.success("获取成功", rows.getContent().stream().map(this::view).toList());
    }

    @RequirePermission({"production:read_write", "sales:read_write"})
    @PostMapping("/scan")
    @Operation(summary = "扫描未交订单", description = "有风险的建实例; 返回每张订单的结论, 含无法判断的")
    public ApiResponse<DeliveryRiskService.ScanResult> scan(@PathVariable String factoryId, HttpServletRequest http) {
        return ApiResponse.success("扫描完成", riskService.scan(factoryId, ShortageCaseActionController.actor(http, factoryId)));
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write"})
    @GetMapping("/assignees")
    @Operation(summary = "可指派的本厂用户")
    public ApiResponse<List<Assignee>> assignees(@PathVariable String factoryId) {
        return ApiResponse.success("获取成功", userRepository.findByFactoryIdAndIsActive(factoryId, true).stream()
                .map(u -> new Assignee(u.getId(), u.getFullName() != null ? u.getFullName() : u.getUsername(), u.getRoleCode()))
                .toList());
    }

    @RequirePermission({"production:read", "production:read_write", "sales:read", "sales:read_write",
            "procurement:read", "procurement:read_write"})
    @GetMapping("/{caseId}")
    @Operation(summary = "实例详情", description = "最近一次评估、处理任务、留痕、核对记录")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String factoryId, @PathVariable String caseId) {
        ShortageCase c = store.own(factoryId, caseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("case", view(c));
        out.put("assessment", assessment(factoryId, caseId));
        out.put("actions", actionRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(ShortageCaseActionController.ActionView::of).toList());
        out.put("events", eventRepository.findByCaseIdOrderByIdAsc(caseId).stream()
                .map(ShortageCaseController.EventView::of).toList());
        out.put("checks", checkRepository.findByFactoryIdAndCaseIdOrderByIdAsc(factoryId, caseId).stream()
                .map(ShortageCaseActionController.OutcomeCheckView::of).toList());
        return ApiResponse.success("获取成功", out);
    }

    @RequirePermission({"production:read_write", "sales:read_write"})
    @PostMapping("/{caseId}/assign")
    @Operation(summary = "指派处理任务", description = "只记录负责人和任务, 不改排程或采购")
    public ApiResponse<CaseView> assign(@PathVariable String factoryId, @PathVariable String caseId,
                                        @RequestBody AssignRequest request, HttpServletRequest http) {
        ShortageCase c = store.assign(factoryId, caseId, request.ownerUserId(), request.task(),
                ShortageCaseActionController.actor(http, factoryId));
        return ApiResponse.success("已指派", view(c));
    }

    @RequirePermission({"production:read_write", "sales:read_write"})
    @PostMapping("/{caseId}/recheck")
    @Operation(summary = "按当前数据复核")
    public ApiResponse<DeliveryRiskService.OrderAssessment> recheck(@PathVariable String factoryId, @PathVariable String caseId,
                                                                    HttpServletRequest http) {
        return ApiResponse.success("已复核", riskService.recheck(factoryId, caseId,
                ShortageCaseActionController.actor(http, factoryId)));
    }

    @RequirePermission({"production:read_write", "sales:read_write"})
    @PostMapping("/{caseId}/finish")
    @Operation(summary = "写处理结果并结束", description = "结束前按当前数据核对一次; 风险未解除也如实记录")
    public ApiResponse<ShortageCaseActionController.OutcomeCheckView> finish(@PathVariable String factoryId,
                                                                             @PathVariable String caseId,
                                                                             @RequestBody FinishRequest request,
                                                                             HttpServletRequest http) {
        var actor = ShortageCaseActionController.actor(http, factoryId);
        var check = store.finish(factoryId, caseId, request.decision(), request.result(),
                riskService.assessCase(factoryId, caseId), actor);
        return ApiResponse.success(check.getVerdict().label(), ShortageCaseActionController.OutcomeCheckView.of(check));
    }

    private JsonNode assessment(String factoryId, String caseId) {
        var d = detailRepository.findByCaseIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "实例没有评估记录"));
        try {
            return objectMapper.readTree(d.getAssessmentJson());
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "评估记录损坏");
        }
    }

    private CaseView view(ShortageCase c) {
        var order = orderRepository.findByIdAndFactoryId(c.getSourceId(), c.getFactoryId()).orElse(null);
        var d = detailRepository.findByCaseIdAndFactoryId(c.getId(), c.getFactoryId()).orElse(null);
        String owner = c.getOwnerUserId() == null ? null : userRepository.findByIdAndFactoryId(c.getOwnerUserId(), c.getFactoryId())
                .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername()).orElse(null);
        return new CaseView(c.getId(), c.getSourceId(), order == null ? null : order.getOrderNumber(),
                order == null ? null : order.getCustomerName(), d == null ? null : d.getDueDate(),
                d == null ? null : d.getRiskLevel(), c.getStage().name(), c.getStage().label(), c.getOwnerRole(),
                c.getOwnerUserId(), owner, d == null ? null : d.getAssessedAt(), c.getClosedAt(), c.getCreatedAt());
    }
}
