package com.cretas.aims.service.scheduling;

import com.cretas.aims.dto.scheduling.SchedulingReadinessResponse;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.service.scheduling.impl.SchedulingAIServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Tenant-scoped, non-mutating validation for an explicitly supplied scenario. */
@Service
public class SchedulingReadinessService {
    private final ProductionLineRepository lineRepository;
    private final ProductionBatchRepository batchRepository;
    private final SchedulingAIServiceImpl schedulingAIService;

    @Autowired
    public SchedulingReadinessService(ProductionLineRepository lineRepository, ProductionBatchRepository batchRepository,
                                      SchedulingAIServiceImpl schedulingAIService) {
        this.lineRepository = lineRepository;
        this.batchRepository = batchRepository;
        this.schedulingAIService = schedulingAIService;
    }

    public SchedulingReadinessService(ProductionLineRepository lineRepository, ProductionBatchRepository batchRepository) {
        this(lineRepository, batchRepository, null);
    }

    public SchedulingReadinessResponse check(String factoryId, String productionLineId, Long batchId,
                                             LocalDateTime start, LocalDateTime end) {
        return check(factoryId, productionLineId, batchId, start, end, null);
    }

    public SchedulingReadinessResponse check(String factoryId, String productionLineId, Long batchId,
                                             LocalDateTime start, LocalDateTime end, String scheduleId) {
        List<String> missing = new ArrayList<>();
        String requestMode = scheduleId == null || scheduleId.isBlank() ? "EXPLICIT_RESOURCES" : "EXISTING_SCHEDULE";
        if (scheduleId != null && !scheduleId.isBlank()
                && (productionLineId != null || batchId != null || start != null || end != null)) {
            return SchedulingReadinessResponse.builder().status("INCOMPLETE").requestMode("EXISTING_SCHEDULE")
                    .scheduleId(scheduleId).readyForAutomation(false).reason("已有排程模式不能混入显式资源或时间输入")
                    .productionLine(resource(null, "产线")).batch(resource(null, "批次"))
                    .timeWindow(SchedulingReadinessResponse.TimeWindow.builder().valid(false).reason("请求模式冲突").build())
                    .probability(SchedulingReadinessResponse.ProbabilityProvenance.builder().origin("UNAVAILABLE")
                            .reason("未执行排程诊断；请求模式冲突").optimization(false).build())
                    .missingInputs(List.of("requestMode:mixed_schedule_and_explicit_inputs")).build();
        }
        if (scheduleId != null && !scheduleId.isBlank() && schedulingAIService != null) {
            var diagnostic = schedulingAIService.diagnoseCompletionProbability(factoryId, scheduleId);
            String diagnosticReason = diagnostic.getPredictionReason();
            String status = "DETERMINISTIC_IMPOSSIBLE_WINDOW".equals(diagnosticReason) ? "INFEASIBLE"
                    : ("COMPLETED".equals(diagnosticReason) ? "INPUTS_VALID_UNCONFIRMED" : "INCOMPLETE");
            String message = switch (diagnosticReason == null ? "" : diagnosticReason) {
                case "COMPLETED" -> "已有排程已完成；资源仍需现场确认";
                case "DETERMINISTIC_IMPOSSIBLE_WINDOW" -> "已有排程尚未完成且结束时间已过";
                case "TENANT_SCOPED_RESOURCE_MISSING" -> "已有排程引用的产线或批次不可用，请核对当前工厂资源";
                case "INVALID_NUMERIC_INPUT" -> "已有排程的数量或人数无效，请先核对输入";
                case "VERIFIED_MODEL_FEATURES_UNAVAILABLE" -> "缺少已确认的人员、设备或产品特征，无法计算概率";
                default -> "已有排程缺少可验证输入，无法计算概率";
            };
            return SchedulingReadinessResponse.builder().status(status).requestMode("EXISTING_SCHEDULE")
                    .scheduleId(scheduleId).readyForAutomation(false).reason(message)
                    .productionLine(existingResource("产线")).batch(existingResource("批次"))
                    .timeWindow(SchedulingReadinessResponse.TimeWindow.builder().valid(false).reason("沿用已有排程窗口；未重复声明时间").build())
                    .probability(SchedulingReadinessResponse.ProbabilityProvenance.builder().origin(diagnostic.getPredictionOrigin())
                            .reason(diagnostic.getPredictionReason()).predictionMode(diagnostic.getPredictionMode())
                            .modelVersion(diagnostic.getModelVersion()).value(diagnostic.getProbability()).optimization(false).build())
                    .missingInputs("INCOMPLETE".equals(status) ? List.of("scheduleId:diagnostic_inputs") : List.of()).build();
        }
        SchedulingReadinessResponse.ResourceReference line = resource(productionLineId, "产线");
        SchedulingReadinessResponse.ResourceReference batch = resource(batchId == null ? null : String.valueOf(batchId), "批次");

        if (productionLineId == null || productionLineId.isBlank()) {
            missing.add("productionLineId");
        } else if (lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(productionLineId, factoryId).isEmpty()) {
            line = SchedulingReadinessResponse.ResourceReference.builder().id(productionLineId).selected(true)
                    .confirmed(false).source("TENANT_VALIDATION").reason("产线不存在或不属于当前工厂").build();
            missing.add("productionLineId:tenant_mismatch_or_missing");
        } else {
            line = selected(productionLineId, "产线引用已按当前工厂校验");
        }

        if (batchId == null) {
            missing.add("batchId");
        } else if (batchRepository.findByIdAndFactoryId(batchId, factoryId).isEmpty()) {
            batch = SchedulingReadinessResponse.ResourceReference.builder().id(String.valueOf(batchId)).selected(true)
                    .confirmed(false).source("TENANT_VALIDATION").reason("批次不存在或不属于当前工厂").build();
            missing.add("batchId:tenant_mismatch_or_missing");
        } else {
            batch = selected(String.valueOf(batchId), "批次引用已按当前工厂校验");
        }


        SchedulingReadinessResponse.TimeWindow window;
        if (start == null || end == null) {
            missing.add("timeWindow");
            window = SchedulingReadinessResponse.TimeWindow.builder().start(text(start)).end(text(end))
                    .valid(false).reason("必须明确提供开始和结束时间").build();
        } else if (!end.isAfter(start)) {
            window = SchedulingReadinessResponse.TimeWindow.builder().start(start.toString()).end(end.toString())
                    .valid(false).reason("结束时间必须晚于开始时间").build();
        } else {
            window = SchedulingReadinessResponse.TimeWindow.builder().start(start.toString()).end(end.toString())
                    .valid(true).reason("时间窗口可解析").build();
        }

        String status = !missing.isEmpty() ? "INCOMPLETE" : (!window.isValid() ? "INFEASIBLE" : "INPUTS_VALID_UNCONFIRMED");
        String reason = !missing.isEmpty() ? "缺少或无法验证明确输入" : window.getReason();
        SchedulingReadinessResponse.ProbabilityProvenance provenance = SchedulingReadinessResponse.ProbabilityProvenance.builder()
                .origin("UNAVAILABLE").reason("排产引擎不可用；本检查不执行 APS 优化，local 概率仅为估算").optimization(false).build();
        return SchedulingReadinessResponse.builder().status(status).requestMode(requestMode).scheduleId(scheduleId).readyForAutomation(false).reason(
                status.equals("INPUTS_VALID_UNCONFIRMED") ? "输入完整但资源尚未物理确认，不能自动执行" : reason)
                .productionLine(line).batch(batch)
                .timeWindow(window)
                .probability(provenance)
                .missingInputs(List.copyOf(missing)).build();
    }

    private SchedulingReadinessResponse.ResourceReference resource(String id, String label) {
        return SchedulingReadinessResponse.ResourceReference.builder().id(id).selected(false).confirmed(false)
                .source("MISSING_INPUT").reason("未提供明确" + label + "引用；不会使用 LINE001 或存储产能作为默认值").build();
    }

    private SchedulingReadinessResponse.ResourceReference selected(String id, String reason) {
        return SchedulingReadinessResponse.ResourceReference.builder().id(id).selected(true).confirmed(false)
                .source("USER_REFERENCE").reason(reason + "；物理确认仍需现场流程").build();
    }

    private SchedulingReadinessResponse.ResourceReference existingResource(String label) {
        return SchedulingReadinessResponse.ResourceReference.builder().selected(false).confirmed(false)
                .source("EXISTING_SCHEDULE_NOT_EXPOSED").reason("已有排程模式不展开" + label + "引用").build();
    }

    private String text(LocalDateTime value) { return value == null ? null : value.toString(); }
}
