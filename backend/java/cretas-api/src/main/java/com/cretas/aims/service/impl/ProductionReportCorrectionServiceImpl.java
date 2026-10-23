package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionReportCorrectionDTO;
import com.cretas.aims.dto.production.ProductionReportCorrectionRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.ProductionReportCorrection;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportCorrectionRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProductionReportCorrectionService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionReportCorrectionServiceImpl implements ProductionReportCorrectionService {
    public static final String MODULE_CODE = "PRODUCTION_REPORT_CORRECTION";

    private final ProductionReportCorrectionRepository correctionRepository;
    private final ProductionReportRepository reportRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository planRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final UserRepository userRepository;
    private final WorkflowEngineService workflowEngine;

    @Override
    @Transactional
    public ProductionReportCorrectionDTO submit(String factoryId, Long reportId,
                                                ProductionReportCorrectionRequest request, Long userId) {
        ProductionReport report = reportRepository.findByIdAndFactoryIdForUpdate(reportId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "报工记录不存在"));
        assertCorrectionableReport(report);
        if (Boolean.TRUE.equals(report.getSettled())) {
            throw new BusinessException(409, "该报工已经结单，不能发起日常修正")
                    .withCode("PRODUCTION_REPORT_ALREADY_SETTLED");
        }
        if (report.getStatus() != ProductionReport.Status.SUBMITTED
                && report.getStatus() != ProductionReport.Status.APPROVED) {
            throw new BusinessException(409, "只有已提交或已批准且未结单的报工可以修正")
                    .withCode("PRODUCTION_REPORT_NOT_CORRECTABLE");
        }
        correctionRepository.findFirstByFactoryIdAndProductionReportIdAndStatusOrderBySubmittedAtDesc(
                factoryId, reportId, ProductionReportCorrection.Status.PENDING_APPROVAL)
                .ifPresent(existing -> {
                    throw new BusinessException(409, "该报工已有待审批修正，请先完成当前审批")
                            .withCode("PRODUCTION_REPORT_CORRECTION_PENDING");
                });

        ProductionBatch batch = report.getBatchId() == null ? null
                : batchRepository.findByIdAndFactoryId(report.getBatchId(), factoryId).orElse(null);
        String planId = batch == null ? null : batch.getProductionPlanId();
        ProductionPlan plan = null;
        if (planId != null) {
            plan = planRepository.findByIdForUpdate(planId)
                    .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                    .orElseThrow(() -> new BusinessException(409, "报工关联计划不存在或不属于当前工厂"));
            assertPlanAllowsCorrection(plan);
        }

        ensureChanged(report, request);
        ProductionReportCorrection correction = ProductionReportCorrection.builder()
                .factoryId(factoryId).productionReportId(report.getId())
                .workProcessTaskId(report.getWorkProcessTaskId()).productionBatchId(report.getBatchId())
                .productionPlanId(planId).productTypeId(report.getProductTypeId() != null
                        ? report.getProductTypeId() : plan == null ? null : plan.getProductTypeId())
                .processCategory(report.getProcessCategory())
                .status(ProductionReportCorrection.Status.PENDING_APPROVAL)
                .originalOutputQuantity(report.getOutputQuantity()).proposedOutputQuantity(request.outputQuantity())
                .originalGoodQuantity(report.getGoodQuantity()).proposedGoodQuantity(request.goodQuantity())
                .originalDefectQuantity(report.getDefectQuantity()).proposedDefectQuantity(request.defectQuantity())
                .originalTotalWorkers(report.getTotalWorkers()).proposedTotalWorkers(request.totalWorkers())
                .originalTotalWorkMinutes(report.getTotalWorkMinutes()).proposedTotalWorkMinutes(request.totalWorkMinutes())
                .originalNotes(report.getNotes()).proposedNotes(trimToNull(request.notes()))
                .correctionReason(request.reason().trim()).submittedBy(userId).submittedAt(LocalDateTime.now())
                .build();
        correctionRepository.save(correction);

        Map<String, Object> context = approvalContext(correction);
        ApprovalWorkflowInstance instance = workflowEngine.startWorkflowIfConfigured(
                        factoryId, MODULE_CODE, correction.getId(), context, userId)
                .orElseThrow(() -> new BusinessException(409, "请先配置报工修正审批流程")
                        .withCode("PRODUCTION_REPORT_CORRECTION_WORKFLOW_NOT_CONFIGURED")
                        .withHintTarget("OA 工作流配置"));
        correction.setWorkflowInstanceId(instance.getId());
        if (instance.getStatus() != ApprovalWorkflowInstance.InstanceStatus.RUNNING) {
            throw new BusinessException(409, "报工修正审批流程未配置可审批节点，请先调整流程")
                    .withCode("PRODUCTION_REPORT_CORRECTION_WORKFLOW_NO_APPROVAL_NODE")
                    .withHintTarget("OA 工作流配置");
        }
        return enrich(correctionRepository.save(correction));
    }

    @Override
    @Transactional
    public ProductionReportCorrectionDTO applyWorkflowAction(
            String factoryId, String correctionId, String workflowInstanceId, Long actorId,
            String actorRole, HistoryAction action, String notes) {
        ProductionReportCorrection correction = correctionRepository.findByIdAndFactoryId(correctionId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "报工修正记录不存在"));
        if (!Objects.equals(workflowInstanceId, correction.getWorkflowInstanceId())) {
            throw new BusinessException(409, "审批实例与报工修正记录不匹配")
                    .withCode("PRODUCTION_REPORT_CORRECTION_WORKFLOW_MISMATCH");
        }
        ProductionPlan approvalPlan = null;
        if (action == HistoryAction.APPROVE) {
            ProductionReport report = reportRepository.findByIdAndFactoryIdForUpdate(
                            correction.getProductionReportId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404, "原报工记录不存在"));
            assertCorrectionableReport(report);
            approvalPlan = lockCorrectionPlan(factoryId, correction.getProductionPlanId());
            assertPlanAllowsCorrection(approvalPlan);
        }
        ApprovalWorkflowInstance updated = workflowEngine.transitionNode(
                workflowInstanceId, actorId, actorRole, action, notes);
        if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.APPROVED) {
            ProductionReport report = reportRepository.findByIdAndFactoryIdForUpdate(
                            correction.getProductionReportId(), factoryId)
                    .orElseThrow(() -> new BusinessException(404, "原报工记录不存在"));
            if (approvalPlan == null) {
                approvalPlan = lockCorrectionPlan(factoryId, correction.getProductionPlanId());
            }
            applyApproved(report, correction, actorId, notes, approvalPlan);
        } else if (updated.getStatus() == ApprovalWorkflowInstance.InstanceStatus.REJECTED) {
            correction.setStatus(ProductionReportCorrection.Status.REJECTED);
            correction.setDecidedBy(actorId);
            correction.setDecidedAt(LocalDateTime.now());
            correction.setDecisionNotes(trimToNull(notes));
        }
        return enrich(correctionRepository.save(correction));
    }

    private void applyApproved(ProductionReport report, ProductionReportCorrection correction,
                               Long actorId, String notes, ProductionPlan plan) {
        assertCorrectionableReport(report);
        assertPlanAllowsCorrection(plan);
        if (correction.getStatus() == ProductionReportCorrection.Status.APPROVED_APPLIED) return;
        if (Boolean.TRUE.equals(report.getSettled())) {
            throw new BusinessException(409, "报工已结单，不能应用待审批修正")
                    .withCode("PRODUCTION_REPORT_CORRECTION_SETTLEMENT_RACE");
        }
        report.setOutputQuantity(correction.getProposedOutputQuantity());
        report.setGoodQuantity(correction.getProposedGoodQuantity());
        report.setDefectQuantity(correction.getProposedDefectQuantity());
        report.setTotalWorkers(correction.getProposedTotalWorkers());
        report.setTotalWorkMinutes(correction.getProposedTotalWorkMinutes());
        report.setNotes(correction.getProposedNotes());
        report.setDataCorrected(true);
        report.setLastCorrectionId(correction.getId());
        reportRepository.save(report);

        if (report.getWorkProcessTaskId() != null) {
            taskRepository.findByFactoryIdAndId(report.getFactoryId(), report.getWorkProcessTaskId())
                    .ifPresent(task -> {
                        List<ProductionReport> taskReports = reportRepository
                                .findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull(
                                        report.getFactoryId(), report.getWorkProcessTaskId());
                        assertNoUnknownOutputReports(taskReports);
                        List<ProductionReport> outputReports = taskReports.stream()
                                .filter(this::isOutputOrLegacy)
                                .filter(reportRow -> reportRow.getOutputQuantity() != null)
                                .toList();
                        assertSingleOutputUnit(outputReports);
                        BigDecimal taskOutput = outputReports.stream()
                                .map(ProductionReport::getOutputQuantity).filter(Objects::nonNull)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                        int taskMinutes = taskReports.stream()
                                .map(ProductionReport::getTotalWorkMinutes).filter(Objects::nonNull)
                                .mapToInt(Integer::intValue).sum();
                        task.setActualQuantity(taskOutput);
                        task.setActualMinutes(taskMinutes);
                        taskRepository.save(task); // 状态、领料与库存字段不变。
                    });
        }
        correction.setStatus(ProductionReportCorrection.Status.APPROVED_APPLIED);
        correction.setDecidedBy(actorId);
        correction.setDecidedAt(LocalDateTime.now());
        correction.setDecisionNotes(trimToNull(notes));
        correction.setAppliedAt(LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionReportCorrectionDTO> history(String factoryId, Long reportId) {
        return correctionRepository.findByFactoryIdAndProductionReportIdOrderBySubmittedAtDesc(factoryId, reportId)
                .stream().map(this::enrich).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listCorrectable(String factoryId, Pageable pageable) {
        Page<ProductionReport> reports = reportRepository
                .findByFactoryIdAndSettledFalseAndStatusInAndDeletedAtIsNull(
                        factoryId, EnumSet.of(ProductionReport.Status.SUBMITTED, ProductionReport.Status.APPROVED), pageable);
        List<Long> ids = reports.getContent().stream().map(ProductionReport::getId).toList();
        if (ids.isEmpty()) return reports.map(report -> correctableRow(report, List.of()));
        Map<Long, List<ProductionReportCorrection>> correctionsByReport = correctionRepository
                .findByFactoryIdAndProductionReportIdInOrderBySubmittedAtDesc(factoryId, ids).stream()
                .collect(Collectors.groupingBy(ProductionReportCorrection::getProductionReportId,
                        LinkedHashMap::new, Collectors.toList()));
        return reports.map(report -> correctableRow(report,
                correctionsByReport.getOrDefault(report.getId(), List.of())));
    }

    private Map<String, Object> correctableRow(ProductionReport report,
                                               List<ProductionReportCorrection> corrections) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", report.getId());
        row.put("batchId", report.getBatchId());
        row.put("workProcessTaskId", report.getWorkProcessTaskId());
        row.put("processCategory", report.getProcessCategory());
        row.put("productName", report.getProductName());
        row.put("reporterName", report.getReporterName());
        row.put("reportDate", report.getReportDate());
        row.put("approvalStatus", report.getApprovalStatus());
        row.put("outputQuantity", report.getOutputQuantity());
        row.put("reportKind", reportKind(report));
        row.put("outputUnit", trustedOutputUnit(report));
        row.put("correctionEligible", isCorrectionableKind(report));
        row.put("correctionEligibilityReason", correctionEligibilityReason(report));
        row.put("goodQuantity", report.getGoodQuantity());
        row.put("defectQuantity", report.getDefectQuantity());
        row.put("totalWorkers", report.getTotalWorkers());
        row.put("totalWorkMinutes", report.getTotalWorkMinutes());
        row.put("notes", report.getNotes());
        row.put("dataCorrected", Boolean.TRUE.equals(report.getDataCorrected()));
        ProductionReportCorrection latest = corrections.isEmpty() ? null : corrections.get(0);
        row.put("lastCorrection", latest == null ? null : enrich(latest));
        row.put("corrections", corrections.stream().map(this::enrich).toList());
        return row;
    }

    @Override
    @Transactional(readOnly = true)
    public void assertNoPendingForPlan(String factoryId, String planId) {
        List<ProductionReportCorrection> pending = correctionRepository.findByFactoryIdAndProductionPlanIdAndStatus(
                factoryId, planId, ProductionReportCorrection.Status.PENDING_APPROVAL);
        if (!pending.isEmpty()) {
            String processes = pending.stream().map(ProductionReportCorrection::getProcessCategory)
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining("、"));
            throw new BusinessException(409, "存在待审批报工修正，暂不能结单"
                    + (processes.isBlank() ? "" : "：" + processes))
                    .withCode("PRODUCTION_SETTLEMENT_BLOCKED_PENDING_REPORT_CORRECTION")
                    .withHint("请先在 OA 审批中心完成报工修正审批")
                    .withHintTarget("报工核对/修正");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> approvalSummary(String factoryId, String correctionId) {
        ProductionReportCorrection correction = correctionRepository.findByIdAndFactoryId(correctionId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "报工修正记录不存在"));
        return approvalContext(correction);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionReportCorrectionDTO> approvedForProducts(
            String factoryId, Collection<String> productTypeIds) {
        if (productTypeIds == null || productTypeIds.isEmpty()) return List.of();
        return correctionRepository.findByFactoryIdAndProductTypeIdInAndStatusOrderByAppliedAtDesc(
                        factoryId, productTypeIds, ProductionReportCorrection.Status.APPROVED_APPLIED)
                .stream().map(this::enrich).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionReportCorrectionDTO> approvedForPlans(
            String factoryId, Collection<String> productionPlanIds) {
        if (productionPlanIds == null || productionPlanIds.isEmpty()) return List.of();
        return correctionRepository.findByFactoryIdAndProductionPlanIdInAndStatusOrderByAppliedAtDesc(
                        factoryId, productionPlanIds, ProductionReportCorrection.Status.APPROVED_APPLIED)
                .stream().map(this::enrich).toList();
    }

    private Map<String, Object> approvalContext(ProductionReportCorrection c) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("correctionId", c.getId());
        context.put("reportId", c.getProductionReportId());
        context.put("planId", c.getProductionPlanId());
        context.put("batchId", c.getProductionBatchId());
        context.put("processCategory", c.getProcessCategory());
        reportRepository.findById(c.getProductionReportId())
                .filter(report -> Objects.equals(c.getFactoryId(), report.getFactoryId()))
                .ifPresent(report -> {
                    context.put("reportKind", reportKind(report));
                    context.put("outputUnit", trustedOutputUnit(report));
                });
        context.put("reason", c.getCorrectionReason());
        context.put("before", diffValues(c, true));
        context.put("after", diffValues(c, false));
        return context;
    }

    private Map<String, Object> diffValues(ProductionReportCorrection c, boolean original) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("outputQuantity", original ? c.getOriginalOutputQuantity() : c.getProposedOutputQuantity());
        values.put("goodQuantity", original ? c.getOriginalGoodQuantity() : c.getProposedGoodQuantity());
        values.put("defectQuantity", original ? c.getOriginalDefectQuantity() : c.getProposedDefectQuantity());
        values.put("totalWorkers", original ? c.getOriginalTotalWorkers() : c.getProposedTotalWorkers());
        values.put("totalWorkMinutes", original ? c.getOriginalTotalWorkMinutes() : c.getProposedTotalWorkMinutes());
        values.put("notes", original ? c.getOriginalNotes() : c.getProposedNotes());
        return values;
    }

    private ProductionReportCorrectionDTO enrich(ProductionReportCorrection correction) {
        ProductionReportCorrectionDTO dto = ProductionReportCorrectionDTO.from(correction);
        dto.setSubmittedByName(userName(correction.getFactoryId(), correction.getSubmittedBy()));
        dto.setDecidedByName(userName(correction.getFactoryId(), correction.getDecidedBy()));
        return dto;
    }

    private String userName(String factoryId, Long userId) {
        if (userId == null) return null;
        return userRepository.findByIdAndFactoryId(userId, factoryId)
                .map(user -> user.getFullName() != null && !user.getFullName().isBlank()
                        ? user.getFullName() : user.getUsername())
                .filter(name -> name != null && !name.isBlank())
                .orElse("用户 " + userId);
    }

    private void ensureChanged(ProductionReport report, ProductionReportCorrectionRequest request) {
        boolean changed = !same(report.getOutputQuantity(), request.outputQuantity())
                || !same(report.getGoodQuantity(), request.goodQuantity())
                || !same(report.getDefectQuantity(), request.defectQuantity())
                || !Objects.equals(report.getTotalWorkers(), request.totalWorkers())
                || !Objects.equals(report.getTotalWorkMinutes(), request.totalWorkMinutes())
                || !Objects.equals(trimToNull(report.getNotes()), trimToNull(request.notes()));
        if (!changed) throw new BusinessException(400, "修正前后内容完全一致，无需提交审批")
                .withCode("PRODUCTION_REPORT_CORRECTION_NO_CHANGE");
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private void assertCorrectionableReport(ProductionReport report) {
        String kind = reportKind(report);
        if ("INPUT".equals(kind) || "SEGMENT".equals(kind)) {
            throw new BusinessException(409, "投入或过程报工不能作为产出修正")
                    .withCode("PRODUCTION_REPORT_CORRECTION_OUTPUT_ONLY")
                    .withHint("请到领退料或盘点流程核实投入报工，不要改作产出")
                    .withHintTarget("核实报工阶段");
        }
        if (kind != null && !"OUTPUT".equals(kind)) {
            throw new BusinessException(409, "报工阶段无法确认，不能作为产出修正")
                    .withCode("PRODUCTION_REPORT_CORRECTION_UNKNOWN_KIND")
                    .withHint("请核实报工阶段后再修正，系统不会猜测其产出含义")
                    .withHintTarget("核实报工阶段");
        }
    }

    private boolean isCorrectionableKind(ProductionReport report) {
        String kind = reportKind(report);
        return kind == null || "OUTPUT".equals(kind);
    }

    private String correctionEligibilityReason(ProductionReport report) {
        String kind = reportKind(report);
        if (kind == null || "OUTPUT".equals(kind)) return null;
        if ("INPUT".equals(kind) || "SEGMENT".equals(kind)) return "投入或过程报工不能作为产出修正";
        return "报工阶段无法确认，不能作为产出修正";
    }

    private boolean isOutputOrLegacy(ProductionReport report) {
        String kind = reportKind(report);
        return kind == null || "OUTPUT".equals(kind);
    }

    private void assertNoUnknownOutputReports(List<ProductionReport> reports) {
        reports.stream()
                .filter(report -> report.getOutputQuantity() != null)
                .filter(report -> {
                    String kind = reportKind(report);
                    return kind != null && !"INPUT".equals(kind) && !"SEGMENT".equals(kind) && !"OUTPUT".equals(kind);
                })
                .findFirst()
                .ifPresent(report -> {
                    throw new BusinessException(409, "报工阶段无法确认，不能汇总其产出")
                            .withCode("PRODUCTION_REPORT_CORRECTION_UNKNOWN_KIND")
                            .withHint("请核实报工阶段后再汇总，系统不会猜测其产出含义")
                            .withHintTarget("核实报工阶段");
                });
    }

    private ProductionPlan lockCorrectionPlan(String factoryId, String planId) {
        if (planId == null || planId.isBlank()) return null;
        return planRepository.findByIdForUpdate(planId)
                .filter(candidate -> factoryId.equals(candidate.getFactoryId()))
                .orElseThrow(() -> new BusinessException(409, "报工关联计划不存在或不属于当前工厂")
                        .withHint("请先核对报工关联的生产计划")
                        .withHintTarget("生产计划"));
    }

    private void assertPlanAllowsCorrection(ProductionPlan plan) {
        if (plan == null || plan.getStatus() == null) return;
        ProductionPlanStatus status = plan.getStatus();
        if (status == ProductionPlanStatus.COMPLETED
                || status == ProductionPlanStatus.CANCELLED
                || status == ProductionPlanStatus.PENDING_APPROVAL) {
            throw new BusinessException(409, "生产计划已进入终态或待审批，不能发起或应用报工修正")
                    .withCode("PRODUCTION_REPORT_CORRECTION_PLAN_LOCKED")
                    .withHint("请先核对生产计划状态，再处理报工修正")
                    .withHintTarget("生产计划状态");
        }
    }

    private String reportKind(ProductionReport report) {
        String kind = report.getReportKind();
        return kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String trustedOutputUnit(ProductionReport report) {
        String unit = report.getOutputUnit();
        return unit == null || unit.isBlank() ? null : unit.trim();
    }

    private void assertSingleOutputUnit(List<ProductionReport> reports) {
        java.util.Set<String> units = reports.stream()
                .map(this::trustedOutputUnit)
                .map(unit -> unit == null ? "__UNKNOWN__" : unit)
                .collect(java.util.stream.Collectors.toSet());
        if (units.size() > 1) {
            throw new BusinessException(409, "同一工序报工的产出单位不一致，不能汇总")
                    .withCode("PRODUCTION_REPORT_CORRECTION_MIXED_OUTPUT_UNITS")
                    .withHint("请先核对各报工的可信产出单位，系统不会猜测换算关系")
                    .withHintTarget("核对产出单位");
        }
    }
}
