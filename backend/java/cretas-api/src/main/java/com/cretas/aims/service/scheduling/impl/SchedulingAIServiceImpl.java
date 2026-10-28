package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.scheduling.*;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.rules.DroolsRule;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.FeatureEngineeringService;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 功能：排产生成、工人优化、完成概率预测、自动触发排产、自动化配置、车间主任任务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulingAIServiceImpl implements SchedulingAIService {

    private final SchedulingPlanRepository planRepository;
    private final LineScheduleRepository scheduleRepository;
    private final WorkerAssignmentRepository assignmentRepository;
    private final ProductionLineRepository lineRepository;
    private final SchedulingAlertRepository alertRepository;
    private final SchedulingPredictionRepository predictionRepository;
    private final UserRepository userRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final DroolsRuleRepository droolsRuleRepository;
    private final RestTemplate restTemplate;
    private final FeatureEngineeringService featureEngineeringService;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;
    @Lazy @Autowired
    private SchedulingPlanCrudService schedulingPlanCrudService;
    @Lazy @Autowired
    private WorkerAssignmentService workerAssignmentService;
    @Lazy @Autowired
    private com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator dailyCapacityScheduleGenerator;

    @Value("${cretas.ai.service.url:http://localhost:8083}")
    private String aiServiceUrl;

    @Value("${ml.hybrid-predict.enabled:true}")
    private boolean hybridPredictEnabled;

    // R33 P1 — default 改 false (新工厂 fallback 安全 OFF). 旧工厂由 V20260427_02 migration
    // 显式 backfill auto_trigger_enabled=true 保留原行为. 任何新创建工厂从此默认不自动排产.
    @Value("${cretas.scheduling.auto-trigger.enabled:false}")
    private boolean autoSchedulingEnabled;

    @Value("${cretas.scheduling.auto-trigger.low-risk-threshold:0.85}")
    private double lowRiskThreshold;

    @Value("${cretas.scheduling.auto-trigger.medium-risk-threshold:0.70}")
    private double mediumRiskThreshold;

    // ==================== AI 排产 ====================

    @Override
    @Transactional
    public SchedulingPlanDTO generateSchedule(String factoryId, GenerateScheduleRequest request, Long userId) {
        // APS v2 (Steve 2026-10-28): 按产品日产能按天排; 人工优先、经验参考, 两样都没有时返回需补清单让人当场填; 不自动派工。
        return dailyCapacityScheduleGenerator.generate(factoryId, request, userId);
    }

    @Override
    public List<WorkerAssignmentDTO> optimizeWorkers(String factoryId, OptimizeWorkersRequest request) {
        planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(request.getPlanId(), factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", request.getPlanId()));

        // No optimizer is implemented: returning existing assignments would report a false success.
        throw new BusinessException("自动人员优化暂不可用，本次未调整派工，请手动指派");
    }

    @Override
    public CompletionProbabilityResponse calculateCompletionProbability(String factoryId, String scheduleId) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);

        CompletionProbabilityResponse response = new CompletionProbabilityResponse();
        response.setScheduleId(scheduleId);

        var scopedLine = schedule.getProductionLineId() == null ? Optional.<ProductionLine>empty()
                : lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(schedule.getProductionLineId(), factoryId);
        var scopedBatch = schedule.getBatchId() == null ? Optional.<ProductionBatch>empty()
                : batchRepository.findByIdAndFactoryId(schedule.getBatchId(), factoryId);
        if (scopedLine.isEmpty() || (schedule.getBatchId() != null && scopedBatch.isEmpty())) {
            response.setPredictionOrigin("BUSINESS_INPUT");
            response.setPredictionReason("TENANT_SCOPED_RESOURCE_MISSING");
            return response;
        }
        response.setProductionLineName(scopedLine.get().getName());
        scopedBatch.ifPresent(batch -> response.setBatchNumber(batch.getBatchNumber()));

        int planned = schedule.getPlannedQuantity() != null ? schedule.getPlannedQuantity() : 0;
        int completed = schedule.getCompletedQuantity() != null ? schedule.getCompletedQuantity() : 0;
        int remaining = planned - completed;
        int workers = schedule.getAssignedWorkers() != null ? schedule.getAssignedWorkers() : 1;

        response.setRemainingQuantity(remaining);
        response.setCurrentWorkers(workers);

        double deadlineHours = 8.0;
        if (schedule.getPlannedEndTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            if (schedule.getPlannedEndTime().isAfter(now)) {
                deadlineHours = Duration.between(now, schedule.getPlannedEndTime()).toMinutes() / 60.0;
            } else {
                deadlineHours = 0;
            }
        }
        response.setDeadlineHours(deadlineHours);

        if (hybridPredictEnabled && remaining > 0) {
            try {
                return callHybridPredictService(factoryId, schedule, response, remaining, deadlineHours, workers, true);
            } catch (AdapterReportedProviderFailure e) {
                response.setPredictionOrigin("LOCAL_ESTIMATE");
                response.setPredictionReason("PYTHON_ADAPTER_REPORTED_" + e.getMessage());
            } catch (RestClientResponseException e) {
                response.setPredictionOrigin("LOCAL_ESTIMATE");
                response.setPredictionReason(e.getStatusCode().value() == 404
                        ? "REMOTE_ADAPTER_ROUTE_MISSING"
                        : "PYTHON_ADAPTER_HTTP_ERROR_" + e.getStatusCode().value());
                log.warn("混合预测服务响应失败，使用本地估算: status={}, message={}", e.getStatusCode(), e.getMessage());
            } catch (IllegalStateException e) {
                response.setPredictionOrigin("LOCAL_ESTIMATE");
                response.setPredictionReason(e.getMessage() != null && e.getMessage().startsWith("FEATURES_")
                        ? "JAVA_FEATURE_READ_FAILURE" : e.getMessage() != null && e.getMessage().startsWith("REMOTE_RESPONSE_INVALID")
                        ? "REMOTE_RESPONSE_INVALID" : "JAVA_EXECUTION_FAILURE_UNKNOWN");
                log.warn("混合预测服务返回无效响应，使用本地估算: {}", e.getMessage());
            } catch (org.springframework.web.client.RestClientException e) {
                response.setPredictionOrigin("LOCAL_ESTIMATE");
                response.setPredictionReason(e instanceof org.springframework.web.client.ResourceAccessException
                        ? "PYTHON_ADAPTER_TRANSPORT_ERROR" : "REMOTE_RESPONSE_INVALID");
                log.warn("混合预测服务不可读取，使用本地估算: {}", e.getMessage());
            } catch (Exception e) {
                response.setPredictionOrigin("LOCAL_ESTIMATE");
                response.setPredictionReason("JAVA_EXECUTION_FAILURE_UNKNOWN");
                log.warn("混合预测服务调用失败，使用本地计算: {}", e.getMessage());
            }
        }
        CompletionProbabilityResponse local = calculateLocalProbability(response, planned, completed, remaining, deadlineHours, workers);
        if (local.getPredictionOrigin() == null) local.setPredictionOrigin("LOCAL_ESTIMATE");
        if (deadlineHours <= 0) local.setPredictionReason("DETERMINISTIC_IMPOSSIBLE_WINDOW");
        else if (local.getPredictionReason() == null) local.setPredictionReason("LOCAL_ESTIMATE_NOT_OPTIMIZATION");
        return local;
    }

    /** Strict, read-only readiness diagnostic. Missing verified model features never become estimates. */
    public CompletionProbabilityResponse diagnoseCompletionProbability(String factoryId, String scheduleId) {
        LineSchedule schedule = findScopedSchedule(factoryId, scheduleId);
        CompletionProbabilityResponse response = new CompletionProbabilityResponse();
        response.setScheduleId(scheduleId);
        if (schedule.getPlannedQuantity() == null || schedule.getCompletedQuantity() == null
                || schedule.getAssignedWorkers() == null || schedule.getPlannedEndTime() == null) {
            response.setPredictionOrigin("BUSINESS_INPUT");
            response.setPredictionReason("INPUTS_INCOMPLETE");
            return response;
        }
        if (schedule.getProductionLineId() == null
                || lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(schedule.getProductionLineId(), factoryId).isEmpty()
                || schedule.getBatchId() == null
                || batchRepository.findByIdAndFactoryId(schedule.getBatchId(), factoryId).isEmpty()) {
            response.setPredictionOrigin("BUSINESS_INPUT");
            response.setPredictionReason("TENANT_SCOPED_RESOURCE_MISSING");
            return response;
        }
        int planned = schedule.getPlannedQuantity();
        int completed = schedule.getCompletedQuantity();
        if (planned <= 0 || completed < 0 || completed > planned || schedule.getAssignedWorkers() <= 0) {
            response.setPredictionOrigin("BUSINESS_INPUT");
            response.setPredictionReason("INVALID_NUMERIC_INPUT");
            return response;
        }
        if (completed >= planned) {
            response.setProbability(BigDecimal.ONE);
            response.setRiskLevel("low");
            response.setPredictionOrigin("BUSINESS_CONSTRAINT");
            response.setPredictionReason("COMPLETED");
            return response;
        }
        if (!schedule.getPlannedEndTime().isAfter(LocalDateTime.now())) {
            response.setProbability(BigDecimal.ZERO);
            response.setRiskLevel("critical");
            response.setPredictionOrigin("BUSINESS_CONSTRAINT");
            response.setPredictionReason("DETERMINISTIC_IMPOSSIBLE_WINDOW");
            return response;
        }
        response.setPredictionOrigin("BUSINESS_INPUT");
        response.setPredictionReason("VERIFIED_MODEL_FEATURES_UNAVAILABLE");
        return response;
    }

    @Override
    public List<CompletionProbabilityResponse> calculateBatchProbabilities(String factoryId, String planId) {
        planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", planId));
        List<LineSchedule> schedules = scheduleRepository.findByPlanId(planId);
        return schedules.stream()
            .map(s -> calculateCompletionProbability(factoryId, s.getId()))
            .collect(Collectors.toList());
    }

    private LineSchedule findScopedSchedule(String factoryId, String scheduleId) {
        LineSchedule schedule = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionSchedule", scheduleId));
        String planId = schedule.getPlanId();
        if (planId == null || planId.isBlank()) {
            throw new EntityNotFoundException("ProductionSchedule", scheduleId);
        }
        planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionSchedule", scheduleId));
        return schedule;
    }

    @Override
    @Transactional
    public SchedulingPlanDTO reschedule(String factoryId, RescheduleRequest request, Long userId) {
        SchedulingPlan plan = planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(request.getPlanId(), factoryId)
            .orElseThrow(() -> new EntityNotFoundException("ProductionPlan", request.getPlanId()));

        plan.setNotes((plan.getNotes() != null ? plan.getNotes() + "\n" : "")
            + "重新调度原因: " + request.getReason() + " @ " + LocalDateTime.now());
        planRepository.save(plan);

        return schedulingPlanCrudService.enrichPlanDTO(SchedulingPlanDTO.fromEntity(plan));
    }

    @Override
    @Async
    @Transactional
    public CompletableFuture<BigDecimal> calculatePlanProbability(ProductionPlan plan) {
        try {
            BigDecimal probability = BigDecimal.ZERO;

            BigDecimal crScore = calculateCrScore(plan.getCrValue());
            probability = probability.add(crScore.multiply(new BigDecimal("0.40")));

            BigDecimal materialScore = calculateMaterialScore(plan);
            probability = probability.add(materialScore.multiply(new BigDecimal("0.30")));

            BigDecimal aiScore = calculateAiScore(plan.getAiConfidence());
            probability = probability.add(aiScore.multiply(new BigDecimal("0.20")));

            BigDecimal mixedBatchScore = calculateMixedBatchScore(plan.getIsMixedBatch());
            probability = probability.add(mixedBatchScore.multiply(new BigDecimal("0.10")));

            if (probability.compareTo(BigDecimal.ONE) > 0) {
                probability = BigDecimal.ONE;
            }
            if (probability.compareTo(BigDecimal.ZERO) < 0) {
                probability = BigDecimal.ZERO;
            }

            plan.setCurrentProbability(probability);
            plan.setProbabilityUpdatedAt(LocalDateTime.now());
            productionPlanRepository.save(plan);

            log.debug("计划 {} 概率计算完成: CR={}, 材料={}, AI={}, 混批={}, 最终={}",
                     plan.getPlanNumber(), crScore, materialScore, aiScore,
                     mixedBatchScore, probability);

            return CompletableFuture.completedFuture(probability);

        } catch (Exception e) {
            log.error("计算计划 {} 概率失败", plan.getPlanNumber(), e);
            return CompletableFuture.completedFuture(new BigDecimal("0.5"));
        }
    }

    // ==================== 自动触发排产 ====================

    @Override
    @Async
    public void onProductionPlanCreated(String factoryId, String planId, String planNumber, Long userId) {
        log.info("生产计划创建事件触发: factoryId={}, planId={}, planNumber={}", factoryId, planId, planNumber);

        if (!isAutoSchedulingEnabled(factoryId)) {
            log.info("工厂 {} 未启用自动排产，跳过", factoryId);
            return;
        }

        String mode = getAutoSchedulingMode(factoryId);
        if ("DISABLED".equals(mode)) {
            log.info("工厂 {} 排产自动化已禁用，跳过", factoryId);
            return;
        }

        Optional<ProductionPlan> planOpt = productionPlanRepository.findById(planId);
        if (!planOpt.isPresent()) {
            log.warn("生产计划不存在: planId={}", planId);
            return;
        }

        ProductionPlan plan = planOpt.get();

        if (plan.getStatus() != ProductionPlanStatus.PENDING) {
            log.info("生产计划 {} 状态为 {}，跳过自动排产", planNumber, plan.getStatus());
            return;
        }

        try {
            CompletableFuture<BigDecimal> probabilityFuture = calculatePlanProbability(plan);
            BigDecimal probability = probabilityFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);

            if (probability == null) {
                probability = new BigDecimal("0.5");
            }

            double prob = probability.doubleValue();
            double lowThreshold = getLowRiskThreshold(factoryId);
            double mediumThreshold = getMediumRiskThreshold(factoryId);

            log.info("生产计划 {} 完成概率计算完成: probability={}, lowThreshold={}, mediumThreshold={}",
                planNumber, prob, lowThreshold, mediumThreshold);

            if (prob >= lowThreshold) {
                handleLowRiskPlan(factoryId, planId, planNumber, prob, mode);
            } else if (prob >= mediumThreshold) {
                handleMediumRiskPlan(factoryId, planId, planNumber, prob, mode);
            } else {
                handleHighRiskPlan(factoryId, planId, planNumber, prob, mediumThreshold);
            }

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("自动排产概率计算超时: planId={}", planId);
        } catch (Exception e) {
            log.error("自动排产失败: planId={}, error={}", planId, e.getMessage(), e);
        }
    }

    @Override
    public boolean isAutoSchedulingEnabled(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "auto_trigger_enabled");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            // R33 P3 — 用 strict parser, 拒绝静默 false on "yes"/"1" 等
            return parseStrictBoolean(factoryRule.get().getRuleContent(),
                factoryId, "auto_trigger_enabled", autoSchedulingEnabled);
        }

        Optional<DroolsRule> systemRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName("SYSTEM", "scheduling", "auto_trigger_enabled");

        if (systemRule.isPresent() && systemRule.get().getEnabled()) {
            return parseStrictBoolean(systemRule.get().getRuleContent(),
                "SYSTEM", "auto_trigger_enabled", autoSchedulingEnabled);
        }

        return autoSchedulingEnabled;
    }

    // ==================== 排产自动化配置 ====================

    @Override
    public SchedulingSettingsDTO getSchedulingSettings(String factoryId) {
        log.info("获取排产自动化设置: factoryId={}", factoryId);

        SchedulingSettingsDTO settings = new SchedulingSettingsDTO();
        settings.setAutoSchedulingMode(getAutoSchedulingMode(factoryId));
        settings.setLowRiskThreshold(getLowRiskThreshold(factoryId));
        settings.setMediumRiskThreshold(getMediumRiskThreshold(factoryId));
        settings.setEnableNotifications(getNotificationsEnabled(factoryId));
        settings.setAutoTriggerEnabled(isAutoSchedulingEnabled(factoryId));

        log.info("排产自动化设置: mode={}, lowRisk={}, mediumRisk={}, notifications={}, autoTrigger={}",
            settings.getAutoSchedulingMode(),
            settings.getLowRiskThreshold(),
            settings.getMediumRiskThreshold(),
            settings.getEnableNotifications(),
            settings.getAutoTriggerEnabled());

        return settings;
    }

    @Override
    @Transactional
    public SchedulingSettingsDTO updateSchedulingSettings(String factoryId, SchedulingSettingsDTO settings, Long userId) {
        log.info("更新排产自动化设置: factoryId={}, settings={}, userId={}", factoryId, settings, userId);

        if (settings.getAutoSchedulingMode() != null) {
            saveOrUpdateRule(factoryId, "auto_scheduling_mode", settings.getAutoSchedulingMode(),
                "自动排产模式配置", userId);
        }

        // R32 P4 (I7) — 跨字段一致性校验: low 必须 > medium (high < medium <= low <= 1)
        // 同 update 内若两个字段都给, 用新值; 若只给一个, 用 DB 中已存的另一个值
        Double effectiveLow = settings.getLowRiskThreshold() != null
            ? settings.getLowRiskThreshold()
            : getLowRiskThreshold(factoryId);
        Double effectiveMedium = settings.getMediumRiskThreshold() != null
            ? settings.getMediumRiskThreshold()
            : getMediumRiskThreshold(factoryId);
        if (settings.getLowRiskThreshold() != null || settings.getMediumRiskThreshold() != null) {
            if (effectiveLow <= effectiveMedium) {
                throw new IllegalArgumentException(
                    String.format("低风险阈值必须严格大于中风险阈值 (low=%s, medium=%s)",
                        effectiveLow, effectiveMedium));
            }
        }

        if (settings.getLowRiskThreshold() != null) {
            // R32 P2 (C4) — NaN/Infinity guard: NaN < 0 是 false 会绕过 0-1 检查, 显式拒绝
            Double low = settings.getLowRiskThreshold();
            if (low.isNaN() || low.isInfinite() || low < 0 || low > 1) {
                throw new IllegalArgumentException("低风险阈值必须在 0-1 之间且为有限数值");
            }
            saveOrUpdateRule(factoryId, "auto_trigger_low_risk_threshold",
                String.valueOf(low),
                "自动排产低风险阈值配置", userId);
        }

        if (settings.getMediumRiskThreshold() != null) {
            Double medium = settings.getMediumRiskThreshold();
            if (medium.isNaN() || medium.isInfinite() || medium < 0 || medium > 1) {
                throw new IllegalArgumentException("中风险阈值必须在 0-1 之间且为有限数值");
            }
            saveOrUpdateRule(factoryId, "auto_trigger_medium_risk_threshold",
                String.valueOf(medium),
                "自动排产中风险阈值配置", userId);
        }

        if (settings.getEnableNotifications() != null) {
            saveOrUpdateRule(factoryId, "auto_scheduling_notifications_enabled",
                String.valueOf(settings.getEnableNotifications()),
                "自动排产通知开关配置", userId);
        }

        if (settings.getAutoTriggerEnabled() != null) {
            saveOrUpdateRule(factoryId, "auto_trigger_enabled",
                String.valueOf(settings.getAutoTriggerEnabled()),
                "自动排产总开关 (R31): 控制 SO→PP→排程链是否自动触发", userId);
        }

        log.info("排产自动化设置更新完成: factoryId={}", factoryId);
        return getSchedulingSettings(factoryId);
    }

    // ==================== 车间主任任务 ====================

    @Override
    public List<SupervisorTaskDTO> getSupervisorTasks(String factoryId, Long supervisorId, String statusFilter) {
        log.info("查询车间主任排程任务: factoryId={}, supervisorId={}, statusFilter={}", factoryId, supervisorId, statusFilter);

        List<LineSchedule.ScheduleStatus> statuses = Arrays.stream(statusFilter.split(","))
            .map(String::trim)
            .map(s -> {
                try {
                    return LineSchedule.ScheduleStatus.valueOf(s.toLowerCase());
                } catch (IllegalArgumentException e) {
                    log.warn("无效的排程状态: {}", s);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (statuses.isEmpty()) {
            statuses = Arrays.asList(
                LineSchedule.ScheduleStatus.pending,
                LineSchedule.ScheduleStatus.in_progress
            );
        }

        List<LineSchedule> schedules = scheduleRepository.findBySupervisorAndStatuses(factoryId, supervisorId, statuses);

        if (schedules.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> productionLineIds = schedules.stream()
            .map(LineSchedule::getProductionLineId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        Set<Long> batchIds = schedules.stream()
            .map(LineSchedule::getBatchId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        Map<String, ProductionLine> lineMap = productionLineIds.isEmpty() ? Collections.emptyMap() :
            lineRepository.findAllById(productionLineIds).stream()
                .collect(Collectors.toMap(ProductionLine::getId, Function.identity()));

        Map<Long, ProductionBatch> batchMap = batchIds.isEmpty() ? Collections.emptyMap() :
            batchRepository.findAllById(batchIds).stream()
                .collect(Collectors.toMap(ProductionBatch::getId, Function.identity()));

        return schedules.stream().map(schedule -> {
            SupervisorTaskDTO dto = SupervisorTaskDTO.builder()
                .scheduleId(schedule.getId())
                .planId(schedule.getPlanId())
                .productionLineId(schedule.getProductionLineId())
                .batchId(schedule.getBatchId())
                .plannedQuantity(schedule.getPlannedQuantity())
                .plannedStartTime(schedule.getPlannedStartTime())
                .plannedEndTime(schedule.getPlannedEndTime())
                .assignedWorkers(schedule.getAssignedWorkers())
                .status(schedule.getStatus() != null ? schedule.getStatus().name() : null)
                .build();

            ProductionLine line = lineMap.get(schedule.getProductionLineId());
            if (line != null) {
                dto.setProductionLineName(line.getName());
                dto.setWorkshopLocation(line.getLineType() != null ? line.getLineType() : "主车间");
            }

            if (schedule.getBatchId() != null) {
                ProductionBatch batch = batchMap.get(schedule.getBatchId());
                if (batch != null) {
                    dto.setBatchNumber(batch.getBatchNumber());
                    dto.setProductName(batch.getProductName());
                }
            }

            if (schedule.getPlannedStartTime() != null) {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime urgentThreshold = now.plusHours(2);
                dto.setUrgent(schedule.getPlannedStartTime().isBefore(urgentThreshold) ||
                             schedule.getPlannedStartTime().isBefore(now));
            }

            return dto;
        })
        .sorted((a, b) -> {
            if (a.isUrgent() != b.isUrgent()) {
                return a.isUrgent() ? -1 : 1;
            }
            if (a.getPlannedStartTime() != null && b.getPlannedStartTime() != null) {
                return a.getPlannedStartTime().compareTo(b.getPlannedStartTime());
            }
            return 0;
        })
        .collect(Collectors.toList());
    }

    // ==================== 私有方法: AI 调度 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callAISchedulingService(
            String factoryId, List<ProductionBatch> batches,
            List<ProductionLine> lines, GenerateScheduleRequest request) {

        try {
            Map<String, Object> aiRequest = new HashMap<>();
            aiRequest.put("factory_id", factoryId);

            List<Map<String, Object>> batchData = batches.stream().map(b -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", b.getId());
                m.put("batch_number", b.getBatchNumber());
                m.put("quantity", b.getPlannedQuantity() != null ? b.getPlannedQuantity().intValue() : 100);
                m.put("product_type", b.getProductTypeId());
                m.put("priority", 1);
                return m;
            }).collect(Collectors.toList());
            aiRequest.put("batches", batchData);

            List<Map<String, Object>> lineData = lines.stream().map(l -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", l.getId());
                m.put("name", l.getName());
                m.put("capacity", l.getHourlyCapacity() != null ? l.getHourlyCapacity().doubleValue() : 50);
                m.put("min_workers", l.getMinWorkers());
                m.put("max_workers", l.getMaxWorkers());
                return m;
            }).collect(Collectors.toList());
            aiRequest.put("production_lines", lineData);

            aiRequest.put("plan_date", request.getPlanDate().toString());
            aiRequest.put("priority_strategy", request.getPriorityStrategy());
            aiRequest.put("target_probability", request.getTargetProbability());

            String url = aiServiceUrl + "/scheduling/generate";
            log.debug("调用 AI 调度服务: {}", url);

            Map<String, Object> response = restTemplate.postForObject(url, aiRequest, Map.class);

            if (response != null && response.get("schedules") != null) {
                return (List<Map<String, Object>>) response.get("schedules");
            }
        } catch (Exception e) {
            log.warn("AI 调度服务调用失败: {}", e.getMessage());
        }

        return null;
    }

    private LineSchedule createScheduleFromAISuggestion(
            SchedulingPlan plan, Map<String, Object> suggestion, List<ProductionLine> lines) {

        try {
            LineSchedule schedule = new LineSchedule();
            schedule.setPlan(plan);

            String lineId = (String) suggestion.get("line_id");
            if (lineId == null && suggestion.get("line_index") != null) {
                int index = ((Number) suggestion.get("line_index")).intValue();
                lineId = lines.get(index % lines.size()).getId();
            }
            schedule.setProductionLineId(lineId);

            if (suggestion.get("batch_id") != null) {
                schedule.setBatchId(((Number) suggestion.get("batch_id")).longValue());
            }

            if (suggestion.get("start_time") != null) {
                schedule.setPlannedStartTime(LocalDateTime.parse((String) suggestion.get("start_time")));
            }
            if (suggestion.get("end_time") != null) {
                schedule.setPlannedEndTime(LocalDateTime.parse((String) suggestion.get("end_time")));
            }

            if (suggestion.get("quantity") != null) {
                schedule.setPlannedQuantity(((Number) suggestion.get("quantity")).intValue());
            }

            if (suggestion.get("predicted_efficiency") != null) {
                schedule.setPredictedEfficiency(toBigDecimal(suggestion.get("predicted_efficiency")));
            }
            if (suggestion.get("completion_probability") != null) {
                schedule.setPredictedCompletionProb(toBigDecimal(suggestion.get("completion_probability")));
            }

            schedule.setStatus(LineSchedule.ScheduleStatus.pending);
            return schedule;
        } catch (Exception e) {
            log.warn("解析 AI 排程建议失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPredictionForSchedule(
            String factoryId, ProductionBatch batch, ProductionLine line) {

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("factory_id", factoryId);
            request.put("remaining_quantity", batch.getPlannedQuantity() != null ?
                batch.getPlannedQuantity().intValue() : 100);
            request.put("deadline_hours", 8.0);
            request.put("available_workers", line.getMinWorkers() != null ? line.getMinWorkers() : 5);

            Map<String, Object> features = new HashMap<>();
            LocalDateTime now = LocalDateTime.now();
            features.put("hour_of_day", 8);
            features.put("day_of_week", now.getDayOfWeek().getValue());
            features.put("worker_count", line.getMinWorkers() != null ? line.getMinWorkers() : 5);
            features.put("product_complexity", 5);
            request.put("features", features);

            String url = aiServiceUrl + "/scheduling/hybrid-predict";
            Map<String, Object> result = restTemplate.postForObject(url, request, Map.class);

            if (result != null && result.get("efficiency_prediction") instanceof Map) {
                Map<String, Object> effPred = (Map<String, Object>) result.get("efficiency_prediction");
                Map<String, Object> prediction = new HashMap<>();
                prediction.put("predicted_efficiency", effPred.get("prediction"));
                prediction.put("probability", result.get("probability"));
                return prediction;
            }
        } catch (Exception e) {
            log.debug("预测服务调用失败: {}", e.getMessage());
        }

        return null;
    }

    // ==================== 私有方法: 概率计算 ====================

    @SuppressWarnings("unchecked")
    private CompletionProbabilityResponse callHybridPredictService(
            String factoryId, LineSchedule schedule, CompletionProbabilityResponse context,
            int remaining, double deadlineHours, int workers, boolean persist) {

        // Commit no remote fields to the caller's DTO until every field has passed validation.
        CompletionProbabilityResponse response = new CompletionProbabilityResponse();
        response.setScheduleId(context.getScheduleId());
        response.setProductionLineName(context.getProductionLineName());
        response.setBatchNumber(context.getBatchNumber());
        response.setRemainingQuantity(context.getRemainingQuantity());
        response.setCurrentWorkers(context.getCurrentWorkers());
        response.setDeadlineHours(context.getDeadlineHours());

        Map<String, Object> request = new HashMap<>();
        request.put("factory_id", factoryId);
        request.put("remaining_quantity", remaining);
        request.put("deadline_hours", deadlineHours);
        request.put("available_workers", workers);

        Map<String, Object> features;
        try {
            features = buildPredictionFeatures(factoryId, schedule, workers);
        } catch (Exception e) {
            throw new IllegalStateException("FEATURES_UNAVAILABLE", e);
        }
        request.put("features", features);

        String url = aiServiceUrl + "/scheduling/hybrid-predict";
        log.debug("调用混合预测服务: {}", url);

        Map<String, Object> result = restTemplate.postForObject(url, request, Map.class);

        if (result == null) {
            throw new IllegalStateException("REMOTE_RESPONSE_INVALID_EMPTY");
        }
        if (result.containsKey("success") && !Boolean.TRUE.equals(result.get("success"))) {
            if (Boolean.FALSE.equals(result.get("success")) && result.get("error") instanceof Map<?, ?> error
                    && "MODEL_PROVIDER".equals(error.get("source")) && error.get("code") instanceof String code
                    && Set.of("PROVIDER_UNAVAILABLE", "PROVIDER_TIMEOUT", "PROVIDER_ERROR").contains(code)) {
                throw new AdapterReportedProviderFailure(code);
            }
            throw new IllegalStateException("REMOTE_RESPONSE_INVALID_ERROR_ENVELOPE");
        }
        if (result != null) {
            for (String numericField : List.of("probability", "mean_hours", "std_hours", "percentile_90")) {
                if (result.containsKey(numericField) && result.get(numericField) == null) {
                    throw new IllegalStateException("REMOTE_RESPONSE_INVALID_NUMERIC");
                }
            }
            if (result.get("probability") != null) {
                response.setProbability(parseRemoteDecimal(result.get("probability")));
            }
            if (result.get("mean_hours") != null) {
                response.setMeanHours(parseRemoteDecimal(result.get("mean_hours")));
            }
            if (result.get("std_hours") != null) {
                response.setStdHours(parseRemoteDecimal(result.get("std_hours")));
            }
            if (result.get("percentile_90") != null) {
                response.setPercentile90(parseRemoteDecimal(result.get("percentile_90")));
            }
            for (String field : List.of("mean_hours", "std_hours", "percentile_90")) {
                if (result.get(field) != null && parseRemoteDecimal(result.get(field)).signum() < 0) {
                    throw new IllegalStateException("REMOTE_RESPONSE_INVALID_NUMERIC_RANGE");
                }
            }

            if (result.containsKey("confidence_interval") && result.get("confidence_interval") == null) {
                throw new IllegalStateException("REMOTE_RESPONSE_INVALID_CONFIDENCE_INTERVAL");
            }
            if (result.get("confidence_interval") != null) {
                Object ci = result.get("confidence_interval");
                if (ci instanceof List) {
                    List<?> ciList = (List<?>) ci;
                    if (ciList.size() != 2) throw new IllegalStateException("REMOTE_RESPONSE_INVALID_CONFIDENCE_INTERVAL");
                    response.setConfidenceLower(parseRemoteDecimal(ciList.get(0)));
                    response.setConfidenceUpper(parseRemoteDecimal(ciList.get(1)));
                    if (response.getConfidenceLower() == null || response.getConfidenceUpper() == null
                            || response.getConfidenceLower().compareTo(BigDecimal.ZERO) < 0
                            || response.getConfidenceUpper().compareTo(BigDecimal.ONE) > 0
                            || response.getConfidenceLower().compareTo(response.getConfidenceUpper()) > 0) {
                        throw new IllegalStateException("REMOTE_RESPONSE_INVALID_CONFIDENCE_RANGE");
                    }
                }
            }

            if (result.get("mode") != null && !(result.get("mode") instanceof String)
                    || result.get("risk_analysis") != null && !(result.get("risk_analysis") instanceof String)
                    || result.get("efficiency_prediction") != null && !(result.get("efficiency_prediction") instanceof Map)
                    || result.get("confidence_interval") != null && !(result.get("confidence_interval") instanceof List)) {
                throw new IllegalStateException("REMOTE_RESPONSE_INVALID_FIELD_TYPE");
            }
            response.setPredictionMode((String) result.get("mode"));
            if (result.get("efficiency_prediction") instanceof Map) {
                Map<String, Object> effPred = (Map<String, Object>) result.get("efficiency_prediction");
                if (effPred.get("model_version") != null && !(effPred.get("model_version") instanceof String)
                        || effPred.get("explanation") != null && !(effPred.get("explanation") instanceof String)) {
                    throw new IllegalStateException("REMOTE_RESPONSE_INVALID_FIELD_TYPE");
                }
                response.setModelVersion((String) effPred.get("model_version"));
                for (String field : List.of("confidence", "prediction")) {
                    if (effPred.containsKey(field) && effPred.get(field) == null) {
                        throw new IllegalStateException("REMOTE_RESPONSE_INVALID_NUMERIC");
                    }
                }
                if (effPred.get("confidence") != null) {
                    response.setConfidence(parseRemoteDecimal(effPred.get("confidence")));
                if (response.getConfidence() == null || response.getConfidence().compareTo(BigDecimal.ZERO) < 0 || response.getConfidence().compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalStateException("REMOTE_RESPONSE_INVALID_CONFIDENCE_RANGE");
                }
                }
                if (effPred.get("prediction") != null) {
                    response.setPredictedEfficiency(parseRemoteDecimal(effPred.get("prediction")));
                }
                response.setExplanation((String) effPred.get("explanation"));
            }

            response.setRiskAnalysis((String) result.get("risk_analysis"));

            if (response.getProbability() == null || response.getProbability().signum() < 0
                    || response.getProbability().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException("REMOTE_RESPONSE_INVALID_MISSING_PROBABILITY");
            }
            response.setPredictionOrigin("REMOTE_MODEL_ESTIMATED_INPUTS");
            response.setPredictionReason("REMOTE_MODEL_RESPONSE_WITH_LEGACY_ESTIMATED_FEATURES_NOT_OPTIMIZATION");

            double prob = response.getProbability() != null ? response.getProbability().doubleValue() : 0.5;
            setRiskLevelAndSuggestion(response, prob);

            if (persist) {
                savePrediction(schedule.getId(), "completion_prob", response.getProbability(),
                        response.getConfidenceLower(), response.getConfidenceUpper(),
                        response.getModelVersion(), features);
            }
        }

        return response;
    }

    private Map<String, Object> buildPredictionFeatures(String factoryId, LineSchedule schedule, int workers) {
        Map<String, Object> features = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        features.put("hour_of_day", now.getHour());
        features.put("day_of_week", now.getDayOfWeek().getValue());
        features.put("is_overtime", now.getHour() >= 18 || now.getHour() < 6);
        features.put("worker_count", workers);

        List<Long> workerIds = getWorkerIdsForSchedule(schedule);

        if (!workerIds.isEmpty()) {
            Map<String, Object> workerGroupFeatures =
                    featureEngineeringService.extractWorkerGroupFeatures(factoryId, workerIds);
            features.putAll(workerGroupFeatures);
        } else {
            features.put("avg_worker_experience_days", 90);
            features.put("avg_skill_level", 3.0);
            features.put("temporary_worker_ratio", 0.1);
            features.put("avg_recent_efficiency", 0.8);
        }

        String productTypeId = null;
        if (schedule.getBatchId() != null) {
            Optional<ProductionBatch> batchOpt = batchRepository.findByIdAndFactoryId(schedule.getBatchId(), factoryId);
            if (batchOpt.isEmpty()) throw new IllegalStateException("FEATURES_TENANT_SCOPED_BATCH_MISSING");
            if (batchOpt.isPresent()) {
                ProductionBatch batch = batchOpt.get();
                productTypeId = batch.getProductTypeId();
                if (productTypeId != null) {
                    features.put("product_type", productTypeId);
                    int complexity = featureEngineeringService.getProductComplexity(factoryId, productTypeId);
                    features.put("product_complexity", complexity);
                }
            }
        }
        if (!features.containsKey("product_complexity")) {
            features.put("product_complexity", 3);
        }

        if (schedule.getProductionLineId() != null) {
            List<String> equipmentIds = getEquipmentIdsForLine(schedule.getProductionLineId());
            if (!equipmentIds.isEmpty()) {
                Map<String, Object> equipmentFeatures =
                        featureEngineeringService.extractEquipmentFeatures(factoryId, equipmentIds);
                features.putAll(equipmentFeatures);
            } else {
                features.put("equipment_age_days", 365);
                features.put("equipment_utilization", 0.7);
            }
        } else {
            features.put("equipment_age_days", 365);
            features.put("equipment_utilization", 0.7);
        }

        return features;
    }

    private static final class AdapterReportedProviderFailure extends RuntimeException {
        private AdapterReportedProviderFailure(String code) { super(code); }
    }

    private List<Long> getWorkerIdsForSchedule(LineSchedule schedule) {
        if (schedule.getId() == null) {
            return Collections.emptyList();
        }
        List<WorkerAssignment> assignments = assignmentRepository.findByScheduleId(schedule.getId());
        return assignments.stream().map(WorkerAssignment::getUserId).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private List<String> getEquipmentIdsForLine(String productionLineId) {
        return Collections.emptyList();
    }

    private CompletionProbabilityResponse calculateLocalProbability(
            CompletionProbabilityResponse response, int planned, int completed,
            int remaining, double deadlineHours, int workers) {

        if (planned <= 0) {
            response.setProbability(BigDecimal.ONE);
            response.setRiskLevel("low");
            response.setSuggestion("无生产任务");
            response.setPredictionMode("local");
            response.setPredictionOrigin("LOCAL_ESTIMATE");
            response.setPredictionReason("LOCAL_ESTIMATE_NOT_OPTIMIZATION");
            return response;
        }

        if (workers <= 0) {
            response.setProbability(BigDecimal.ZERO);
            response.setMeanHours(null);
            response.setRiskLevel("critical");
            response.setSuggestion("尚未分配工人，请先分配生产人员");
            response.setPredictionMode("local");
            response.setPredictionOrigin("LOCAL_ESTIMATE");
            response.setPredictionReason("LOCAL_ESTIMATE_NOT_OPTIMIZATION");
            return response;
        }

        double progress = (double) completed / planned;
        double estimatedEfficiency = 15.0;
        double estimatedHours = remaining / (estimatedEfficiency * workers);

        double probability;
        if (deadlineHours <= 0) {
            probability = 0;
        } else if (estimatedHours <= deadlineHours * 0.8) {
            probability = 0.9 + progress * 0.1;
        } else if (estimatedHours <= deadlineHours) {
            probability = 0.7 + progress * 0.2;
        } else {
            probability = Math.max(0.1, 0.5 - (estimatedHours - deadlineHours) / deadlineHours * 0.3);
        }

        response.setProbability(BigDecimal.valueOf(probability).setScale(4, RoundingMode.HALF_UP));
        response.setMeanHours(BigDecimal.valueOf(estimatedHours).setScale(2, RoundingMode.HALF_UP));
        response.setPredictionMode("local");

        setRiskLevelAndSuggestion(response, probability);

        return response;
    }

    private void setRiskLevelAndSuggestion(CompletionProbabilityResponse response, double probability) {
        if (probability >= 0.85) {
            response.setRiskLevel("low");
            response.setSuggestion("进度正常，按计划继续执行");
        } else if (probability >= 0.7) {
            response.setRiskLevel("medium");
            response.setSuggestion("建议密切关注进度，必要时增加人手");
        } else if (probability >= 0.5) {
            response.setRiskLevel("high");
            response.setSuggestion("风险较高，建议立即增加人手或延长工作时间");
        } else {
            response.setRiskLevel("critical");
            response.setSuggestion("风险极高，建议重新调度或调整交付计划");
        }
    }

    private void savePrediction(String scheduleId, String predictionType, BigDecimal predictedValue,
                                  BigDecimal confidenceLower, BigDecimal confidenceUpper,
                                  String modelVersion, Map<String, Object> features) {
        try {
            SchedulingPrediction prediction = new SchedulingPrediction();
            prediction.setScheduleId(scheduleId);
            prediction.setPredictionType(SchedulingPrediction.PredictionType.valueOf(predictionType));
            prediction.setPredictedValue(predictedValue);
            prediction.setConfidenceLower(confidenceLower);
            prediction.setConfidenceUpper(confidenceUpper);
            prediction.setModelVersion(modelVersion);

            if (features != null) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    prediction.setFeaturesJson(mapper.writeValueAsString(features));
                } catch (Exception e) {
                    log.warn("序列化特征数据失败: {}", e.getMessage());
                }
            }

            predictionRepository.save(prediction);
        } catch (Exception e) {
            log.warn("保存预测记录失败: {}", e.getMessage());
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(value.toString()).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseRemoteDecimal(Object value) {
        if (value == null) throw new IllegalStateException("REMOTE_RESPONSE_INVALID_NUMERIC");
        BigDecimal parsed = toBigDecimal(value);
        if (parsed == null) throw new IllegalStateException("REMOTE_RESPONSE_INVALID_NUMERIC");
        return parsed;
    }

    // ==================== 私有方法: 概率评分 ====================

    private BigDecimal calculateCrScore(BigDecimal crValue) {
        if (crValue == null) {
            return new BigDecimal("0.5");
        }
        if (crValue.compareTo(new BigDecimal("1.5")) >= 0) {
            return BigDecimal.ONE;
        }
        if (crValue.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return crValue.divide(new BigDecimal("1.5"), 4, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMaterialScore(ProductionPlan plan) {
        if (plan.getPlannedQuantity() == null ||
            plan.getPlannedQuantity().compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal("0.5");
        }
        BigDecimal allocated = plan.getAllocatedQuantity() != null
            ? plan.getAllocatedQuantity()
            : BigDecimal.ZERO;
        BigDecimal score = allocated.divide(plan.getPlannedQuantity(), 4,
                                           java.math.RoundingMode.HALF_UP);
        return score.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : score;
    }

    private BigDecimal calculateAiScore(Integer aiConfidence) {
        if (aiConfidence == null) {
            return new BigDecimal("0.5");
        }
        return new BigDecimal(aiConfidence).divide(new BigDecimal("100"), 4,
                                                   java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMixedBatchScore(Boolean isMixedBatch) {
        if (isMixedBatch != null && isMixedBatch) {
            return new BigDecimal("0.70");
        }
        return BigDecimal.ONE;
    }

    // ==================== 私有方法: 自动触发排产 ====================

    private double getLowRiskThreshold(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "auto_trigger_low_risk_threshold");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            try {
                double value = Double.parseDouble(factoryRule.get().getRuleContent());
                // R32 P2 (C4) — 即使 DB 中存了 NaN/Infinity (历史脏数据), 解析后仍 fallback
                if (Double.isFinite(value)) {
                    return value;
                }
                log.warn("工厂 {} 的低风险阈值是非有限数值: {}, 使用默认值", factoryId, value);
            } catch (NumberFormatException e) {
                log.warn("工厂 {} 的低风险阈值配置格式错误: {}", factoryId, factoryRule.get().getRuleContent());
            }
        }

        return lowRiskThreshold;
    }

    private double getMediumRiskThreshold(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "auto_trigger_medium_risk_threshold");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            try {
                double value = Double.parseDouble(factoryRule.get().getRuleContent());
                if (Double.isFinite(value)) {
                    return value;
                }
                log.warn("工厂 {} 的中风险阈值是非有限数值: {}, 使用默认值", factoryId, value);
            } catch (NumberFormatException e) {
                log.warn("工厂 {} 的中风险阈值配置格式错误: {}", factoryId, factoryRule.get().getRuleContent());
            }
        }

        return mediumRiskThreshold;
    }

    private String getAutoSchedulingMode(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "auto_scheduling_mode");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            String mode = factoryRule.get().getRuleContent();
            if ("FULLY_AUTO".equals(mode) || "MANUAL_CONFIRM".equals(mode) || "DISABLED".equals(mode)) {
                return mode;
            }
            log.warn("工厂 {} 的自动排产模式配置无效: {}, 使用默认值 MANUAL_CONFIRM", factoryId, mode);
        }

        return "MANUAL_CONFIRM";
    }

    private void handleLowRiskPlan(String factoryId, String planId, String planNumber, double probability, String mode) {
        log.info("低风险计划处理: planNumber={}, probability={}%, mode={}", planNumber, (int)(probability * 100), mode);

        boolean isFullyAuto = "FULLY_AUTO".equals(mode);
        boolean autoActivated = false;

        SchedulingPlanDTO schedulingPlan = null;
        try {
            Optional<ProductionPlan> planOpt = productionPlanRepository.findById(planId);
            if (planOpt.isPresent()) {
                ProductionPlan plan = planOpt.get();

                GenerateScheduleRequest scheduleRequest = new GenerateScheduleRequest();
                LocalDate planDate = plan.getExpectedCompletionDate() != null
                    ? plan.getExpectedCompletionDate()
                    : (plan.getStartTime() != null ? plan.getStartTime().toLocalDate() : LocalDate.now());
                scheduleRequest.setPlanDate(planDate);

                schedulingPlan = generateSchedule(factoryId, scheduleRequest, plan.getCreatedBy());

                if (isFullyAuto && schedulingPlan != null && schedulingPlan.getId() != null) {
                    schedulingPlanCrudService.confirmPlan(factoryId, schedulingPlan.getId(), plan.getCreatedBy());
                    autoActivated = true;
                    log.info("低风险计划已自动生成并确认排产: planNumber={}, schedulingPlanId={}",
                        planNumber, schedulingPlan.getId());
                } else if (schedulingPlan != null) {
                    log.info("低风险计划已生成草稿排产（等待人工确认）: planNumber={}, schedulingPlanId={}",
                        planNumber, schedulingPlan.getId());
                }
            }
        } catch (Exception e) {
            log.error("自动生成排产计划失败: planNumber={}, error={}", planNumber, e.getMessage(), e);
        }

        String title;
        String content;
        if (schedulingPlan != null && autoActivated) {
            title = "AI排产已自动完成";
            content = String.format("计划 %s 完成概率 %d%%，已自动生成并激活排产计划", planNumber, (int)(probability * 100));
        } else if (schedulingPlan != null) {
            title = "排产计划已生成，请确认后激活";
            content = String.format("计划 %s 完成概率 %d%%，排产草稿已生成，请审核后激活", planNumber, (int)(probability * 100));
        } else {
            title = "AI排产建议已生成";
            content = String.format("计划 %s 完成概率 %d%%，点击查看排产建议", planNumber, (int)(probability * 100));
        }

        try {
            notificationService.sendToAllUsers(
                factoryId, title, content,
                com.cretas.aims.entity.enums.NotificationType.INFO,
                "AUTO_SCHEDULING", planId
            );
            log.info("低风险计划通知已发送: planNumber={}", planNumber);
        } catch (Exception e) {
            log.error("发送低风险计划通知失败: planNumber={}", planNumber, e);
        }

        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "auto_scheduling_completed");
            pushData.put("planId", planId);
            pushData.put("schedulingPlanId", schedulingPlan != null ? schedulingPlan.getId() : null);
            pushData.put("probability", probability);
            pushData.put("riskLevel", "low");
            pushData.put("autoActivated", autoActivated);
            pushData.put("mode", mode);
            pushData.put("screen", autoActivated ? "SchedulingPlanDetail" : (schedulingPlan != null ? "SchedulingPlanDetail" : "ProductionPlanDetail"));

            pushNotificationService.sendToFactory(factoryId, title, content, pushData);
            log.info("低风险计划推送通知已发送: planNumber={}", planNumber);
        } catch (Exception e) {
            log.error("发送低风险计划推送通知失败: planNumber={}", planNumber, e);
        }
    }

    private void handleMediumRiskPlan(String factoryId, String planId, String planNumber, double probability, String mode) {
        log.info("中风险计划处理: planNumber={}, probability={}%, mode={}", planNumber, (int)(probability * 100), mode);

        SchedulingPlanDTO schedulingPlan = null;
        try {
            Optional<ProductionPlan> planOpt = productionPlanRepository.findById(planId);
            if (planOpt.isPresent()) {
                ProductionPlan plan = planOpt.get();

                GenerateScheduleRequest scheduleRequest = new GenerateScheduleRequest();
                LocalDate planDate = plan.getExpectedCompletionDate() != null
                    ? plan.getExpectedCompletionDate()
                    : (plan.getStartTime() != null ? plan.getStartTime().toLocalDate() : LocalDate.now());
                scheduleRequest.setPlanDate(planDate);

                schedulingPlan = generateSchedule(factoryId, scheduleRequest, plan.getCreatedBy());

                if (schedulingPlan != null) {
                    log.info("中风险计划已生成草稿排产: planNumber={}, schedulingPlanId={}",
                        planNumber, schedulingPlan.getId());
                }
            }
        } catch (Exception e) {
            log.error("生成草稿排产计划失败: planNumber={}, error={}", planNumber, e.getMessage(), e);
        }

        String title = schedulingPlan != null ? "排产草稿已生成，需确认" : "排产需要关注";
        String content = schedulingPlan != null
            ? String.format("计划 %s 完成概率 %d%%，已生成排产草稿，请审核后激活", planNumber, (int)(probability * 100))
            : String.format("计划 %s 完成概率较低 %d%%，建议检查资源配置", planNumber, (int)(probability * 100));

        try {
            notificationService.sendToAllUsers(
                factoryId, title, content,
                com.cretas.aims.entity.enums.NotificationType.WARNING,
                "AUTO_SCHEDULING", planId
            );
            log.info("中风险计划通知已发送: planNumber={}", planNumber);
        } catch (Exception e) {
            log.error("发送中风险计划通知失败: planNumber={}", planNumber, e);
        }

        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "auto_scheduling_draft_ready");
            pushData.put("planId", planId);
            pushData.put("schedulingPlanId", schedulingPlan != null ? schedulingPlan.getId() : null);
            pushData.put("probability", probability);
            pushData.put("riskLevel", "medium");
            pushData.put("requiresConfirmation", true);
            pushData.put("mode", mode);
            pushData.put("screen", schedulingPlan != null ? "SchedulingPlanDetail" : "ProductionPlanDetail");

            pushNotificationService.sendToFactory(factoryId, title, content, pushData);
            log.info("中风险计划推送通知已发送: planNumber={}", planNumber);
        } catch (Exception e) {
            log.error("发送中风险计划推送通知失败: planNumber={}", planNumber, e);
        }
    }

    private void handleHighRiskPlan(String factoryId, String planId, String planNumber,
                                     double probability, double threshold) {
        log.warn("高风险计划处理: planNumber={}, probability={}%", planNumber, (int)(probability * 100));

        SchedulingAlert alert = new SchedulingAlert();
        alert.setFactoryId(factoryId);
        alert.setPlanId(planId);
        alert.setAlertType(SchedulingAlert.AlertType.low_probability);
        alert.setSeverity(SchedulingAlert.Severity.critical);
        alert.setMessage(String.format(
            "生产计划 %s 完成概率过低，仅 %d%%（阈值 %d%%）",
            planNumber, (int)(probability * 100), (int)(threshold * 100)
        ));
        alert.setSuggestedAction("建议调整资源配置、增加人员或延期交付日期");
        alert.setIsResolved(false);

        try {
            alertRepository.save(alert);
            log.info("高风险计划告警已创建: planNumber={}, alertId={}", planNumber, alert.getId());
        } catch (Exception e) {
            log.error("创建高风险计划告警失败: planNumber={}", planNumber, e);
        }

        String title = "紧急告警 - 完成概率过低";
        String content = String.format(
            "计划 %s 完成概率仅 %d%%，建议调整资源或延期",
            planNumber, (int)(probability * 100)
        );

        try {
            notificationService.sendToAllUsers(
                factoryId, title, content,
                com.cretas.aims.entity.enums.NotificationType.ALERT,
                "AUTO_SCHEDULING", planId
            );
            log.info("高风险计划告警通知已发送: planNumber={}", planNumber);
        } catch (Exception e) {
            log.error("发送高风险计划告警通知失败: planNumber={}", planNumber, e);
        }

        try {
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("type", "auto_scheduling_critical");
            pushData.put("planId", planId);
            pushData.put("alertId", alert.getId());
            pushData.put("probability", probability);
            pushData.put("riskLevel", "high");
            pushData.put("screen", "SchedulingAlertScreen");

            pushNotificationService.sendToFactory(factoryId, title, content, pushData);
            log.info("高风险计划紧急推送通知已发送: planNumber={}", planNumber);
        } catch (Exception e) {
            log.error("发送高风险计划紧急推送通知失败: planNumber={}", planNumber, e);
        }
    }

    // ==================== 私有方法: 配置规则 ====================

    /**
     * R33 P3 — 严格解析 boolean 字符串. Boolean.parseBoolean 对 "yes"/"1"/"on" 静默返 false,
     * 在配置场景下让"看似配置成功实际失效"的 silent 错误难以排查.
     * 此 helper 接受常见 truthy/falsy 字符串并对未识别值返 fallback (而非默认 false).
     */
    private boolean parseStrictBoolean(String value, String factoryId, String ruleName, boolean fallback) {
        if (value == null) return fallback;
        String v = value.trim().toLowerCase();
        // R33 reviewer I3: 不接受单字符 y/n (typo 风险), 只接受全词 + 数字
        if ("true".equals(v) || "yes".equals(v) || "1".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "no".equals(v) || "0".equals(v) || "off".equals(v)) {
            return false;
        }
        log.warn("工厂 {} 的 {} 配置无法识别为布尔值 (rule_content={}), 使用 fallback={}",
            factoryId, ruleName, value, fallback);
        return fallback;
    }

    private void saveOrUpdateRule(String factoryId, String ruleName, String ruleContent,
                                   String description, Long userId) {
        Optional<DroolsRule> existingRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", ruleName);

        DroolsRule rule;
        if (existingRule.isPresent()) {
            rule = existingRule.get();
            rule.setRuleContent(ruleContent);
            rule.setVersion(rule.getVersion() + 1);
            rule.setUpdatedBy(userId);
            rule.setUpdatedAt(LocalDateTime.now());
        } else {
            rule = new DroolsRule();
            rule.setId(UUID.randomUUID().toString());
            rule.setFactoryId(factoryId);
            rule.setRuleGroup("scheduling");
            rule.setRuleName(ruleName);
            rule.setRuleDescription(description);
            rule.setRuleContent(ruleContent);
            rule.setEnabled(true);
            rule.setPriority(0);
            rule.setVersion(1);
            rule.setCreatedBy(userId);
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(LocalDateTime.now());
        }

        droolsRuleRepository.save(rule);
        log.debug("规则已保存/更新: factoryId={}, ruleName={}, content={}", factoryId, ruleName, ruleContent);
    }

    private boolean getNotificationsEnabled(String factoryId) {
        Optional<DroolsRule> factoryRule = droolsRuleRepository
            .findByFactoryIdAndRuleGroupAndRuleName(factoryId, "scheduling", "auto_scheduling_notifications_enabled");

        if (factoryRule.isPresent() && factoryRule.get().getEnabled()) {
            // R33 P3 — 用 strict parser, "yes"/"1" 等 truthy 字符串不再被静默 false
            return parseStrictBoolean(factoryRule.get().getRuleContent(),
                factoryId, "auto_scheduling_notifications_enabled", true);
        }

        return true;
    }
}
