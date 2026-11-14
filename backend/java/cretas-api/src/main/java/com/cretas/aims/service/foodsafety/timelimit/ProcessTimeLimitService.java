package com.cretas.aims.service.foodsafety.timelimit;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.foodsafety.ProcessTimeMark;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeMarkRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.foodsafety.timelimit.ProcessTimeLimitConfig.StartEvent;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.service.quality.release.QcReleaseService;
import com.cretas.aims.service.unit.UnitDisplayNames;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工序间与投料时限 —— 食品 DAG 缺口 14 (Steve 2026-11-04)。
 *
 * <p>配置见 {@link ProcessTimeLimitConfig}, 计时见 {@link ProcessClock}。同一份 {@link #evaluate} 既给页面算剩余时间,
 * 也在投料入口拒绝 —— 两处不会漂。
 *
 * <h3>接在哪</h3>
 * 只接<b>投料发生的那一刻</b>: 逐道录入 {@code saveRow}、App 报工投入、App 领料、二次加工开工。
 * ⛔ 不接审批过账 / 小结 / 结单: 那是已经合法投下去的料的后续记账, 在那里拦会让合法投料事后失败。
 *
 * <h3>零回归</h3>
 * 端口没配时限 ⇒ 第一步就返回, 不读流水。旧批次钉的是旧版本, 端口上本来就没有配置。
 *
 * <h3>处置</h3>
 * 被拦时写 {@link ProcessTimeLimitEvent} (独立事务), 质检复检放行 / 判不合格。放行只覆盖事件当时已有的笔,
 * 之后新入同一行的料不沾光。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessTimeLimitService {

    public static final String CODE_EXCEEDED = "TIME_LIMIT_EXCEEDED";
    public static final String CODE_START_MISSING = "TIME_LIMIT_START_MISSING";
    public static final String CODE_INVALID = "TIME_LIMIT_INVALID_REQUEST";
    public static final String CODE_EVENT_CLOSED = "TIME_LIMIT_EVENT_CLOSED";
    public static final String CODE_PAUSE_OPEN = "TIME_LIMIT_PAUSE_ALREADY_OPEN";
    /** 纪律排查 A13: 补登解冻开始时间没填原因。 */
    public static final String CODE_BACKFILL_REASON_REQUIRED = "TIME_LIMIT_BACKFILL_REASON_REQUIRED";
    /** 纪律排查 A13: 补登时间超出本厂允许的最长补登时长 (含配置为 0 = 不允许补登)。 */
    public static final String CODE_BACKFILL_TOO_EARLY = "TIME_LIMIT_BACKFILL_TOO_EARLY";
    /** 补登时允许的客户端时钟误差: 这个范围内的时间按「现在」处理, 不算补登。 */
    static final long BACKFILL_CLOCK_SKEW_MINUTES = 1;

    public static final class TargetType {
        public static final String SEMI_FINISHED = "SEMI_FINISHED";
        public static final String WIP_MATERIAL_BATCH = "WIP_MATERIAL_BATCH";
        public static final String RAW_MATERIAL_BATCH = "RAW_MATERIAL_BATCH";
        public static final String FINISHED_GOODS = "FINISHED_GOODS";

        private TargetType() {
        }
    }

    public static final class State {
        /** 起算事实还没发生 (上道没完工 / 原料没领到生产仓): 可领, 计时没开始。 */
        public static final String NOT_STARTED = "NOT_STARTED";
        /** 配了要登记解冻, 但没登记: 不能领。 */
        public static final String START_MISSING = "START_MISSING";
        public static final String OK = "OK";
        public static final String WARNING = "WARNING";
        public static final String EXPIRED = "EXPIRED";
        /** 超时后已复检放行, 还在有效期内。 */
        public static final String RELEASED = "RELEASED";

        private State() {
        }
    }

    private static final String TRANSFER_IN_DOC = "TRANSFER_IN";
    /** 质检员在哪里处置: App 首页卡片 (质检员不登网页, MOBILE_ONLY_ROLES) 或网页页签。 */
    private static final String RELEASE_PATH = "App 首页「超时处置」或网页「质量管理 → 质检放行 → 超时处置」";
    private static final int CONCLUSION_MAX = 2000;
    private static final int LINEAGE_DEPTH_CAP = 8;
    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final WorkflowTaskPortRepository portRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductionPlanRepository planRepository;
    private final ProductionWorkflowInstanceRepository instanceRepository;
    private final ProductionReportRepository reportRepository;
    private final SemiFinishedInventoryRepository semiRepository;
    private final SemiFinishedInventoryTransactionRepository semiTxnRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsRepository;
    private final BatchLineageEdgeRepository edgeRepository;
    private final ProcessTimeMarkRepository markRepository;
    private final ProcessTimeLimitEventRepository eventRepository;
    private final ProcessTimeLimitEventWriter eventWriter;
    private final QcReleaseService qcReleaseService;
    private final WarehouseResolver warehouseResolver;
    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final ObjectMapper objectMapper;
    /** 缺口 16: 时限列表 = 这道可领来源清单, 归属与领用计划不同的半成品 / 在制批不出现在这里 (领用闸同一口径)。 */
    private final IntermediateOwnershipGuard ownershipGuard;

    /**
     * 逐道录入是延迟扣减: 已录入还没小结的量账上还在。按笔判可领量时要减掉, 否则两行各领一次就把超时的料领走了。
     * {@code @Lazy}: 它反向依赖库存服务。
     */
    @Autowired(required = false)
    @Lazy
    private PendingInterimFeedService pendingInterimFeedService;

    /** 纪律排查 A13: 解冻开始最多可往前补登多久 (工厂配置)。未注入 (单测) 按默认值。 */
    @Autowired(required = false)
    private com.cretas.aims.service.discipline.FactoryDisciplinePolicy disciplinePolicy;

    /** 测试 / 装配用。 */
    public void setDisciplinePolicy(com.cretas.aims.service.discipline.FactoryDisciplinePolicy policy) {
        this.disciplinePolicy = policy;
    }

    /** 测试 / 装配用。 */
    public void setPendingInterimFeedService(PendingInterimFeedService service) {
        this.pendingInterimFeedService = service;
    }

    /** 一条来源当前的时限状态 (页面显示与写入口共用)。 */
    public record SourceStatus(
            String targetType,
            String targetId,
            String batchNumber,
            String workflowPortId,
            String processName,
            int limitMinutes,
            String startEvent,
            String state,
            LocalDateTime startAt,
            LocalDateTime deadlineAt,
            /** 还剩多少分钟; 已超时为负; 未起算为 null。 */
            Long remainingMinutes,
            String reason,
            BigDecimal balance,
            BigDecimal expiredQuantity,
            BigDecimal drawableQuantity,
            String unit,
            boolean paused,
            boolean perLot,
            /** 进行中的暂停登记 id: 页面刷新后也能结束暂停。 */
            Long pauseMarkId) {
    }

    /** 处置请求。decision = RELEASED / REJECTED。 */
    public record DecisionCommand(String decision, String conclusion) {
    }

    /** 登记请求。kind = START / PAUSE。 */
    /**
     * @param startedAt      纪律排查 A13: 只对「开始解冻」有效 —— 实际开始时间 (补登); 为空 = 现在。
     *                       早于现在即为补登: 必须填 backfillReason, 且不得早于本厂允许的最长补登时长。
     *                       冷藏暂停仍只收现在 (往前补一条暂停能把已超时的料救回来)。
     * @param backfillReason 补登原因, 落库留痕。
     */
    public record MarkCommand(String targetType, String targetId, String kind, String code, String note,
                              LocalDateTime startedAt, String backfillReason) {
    }

    /** 谁在领: 工序任务 (可能为空) + 这道的端口。 */
    private record Consumer(Long productionBatchId, WorkProcessTask task, List<WorkflowTaskPort> ports) {
        List<WorkflowTaskPort> limited() {
            return ports.stream().filter(ProcessTimeLimitService::isLimitedInput).toList();
        }
    }

    /** 被领的那批料。 */
    private record Source(String type, String id, String batchNumber, String productTypeId, String unit,
                          BigDecimal balance, SemiFinishedInventory semi, MaterialBatch material,
                          FinishedGoodsBatch finished) {
    }

    /** 一笔的计时: {@code start} 为空 = 未起算。 */
    private record LotClock(LocalDateTime start, LocalDateTime deadline, BigDecimal quantity, boolean waived) {
    }

    private record Evaluation(Source source, WorkflowTaskPort port, Long consumerBatchId, ProcessTimeLimitConfig config,
                              String processName, SourceStatus status, LocalDateTime coveredUntil,
                              LocalDateTime expiredStart, LocalDateTime expiredDeadline) {
    }

    // ===================== 写入口 =====================

    /** App 报工领半成品。 */
    public void assertSemiFeedForTask(String factoryId, WorkProcessTask task, SemiFinishedInventory semi,
                                      BigDecimal quantity, Long userId) {
        if (semi == null || task == null) {
            return;
        }
        Consumer consumer = consumerOfTask(factoryId, task);
        WorkflowTaskPort port = matchBySku(consumer, semi.getProductTypeId());
        if (port != null) {
            assertDrawable(factoryId, evaluate(factoryId, semiSource(semi), port, consumer, now()), quantity, userId);
        }
    }

    /** App 报工 / 领料投原料。 */
    public void assertRawFeedForTask(String factoryId, WorkProcessTask task, MaterialBatch batch,
                                     BigDecimal quantity, Long userId) {
        if (batch == null || task == null) {
            return;
        }
        Consumer consumer = consumerOfTask(factoryId, task);
        WorkflowTaskPort port = matchBySku(consumer, batch.getMaterialTypeId());
        if (port != null) {
            assertDrawable(factoryId, evaluate(factoryId, rawSource(batch), port, consumer, now()), quantity, userId);
        }
    }

    /** 二次加工开工领半成品: 没有工序任务上下文, 按计划的端口找。 */
    public void assertSemiFeedForPlan(String factoryId, ProductionPlan plan, SemiFinishedInventory semi,
                                      BigDecimal quantity, Long userId) {
        if (semi == null || plan == null) {
            return;
        }
        Consumer consumer = consumerOfPlan(factoryId, plan.getId());
        if (consumer == null) {
            return;
        }
        WorkflowTaskPort port = matchBySku(consumer, semi.getProductTypeId());
        if (port != null) {
            assertDrawable(factoryId, evaluate(factoryId, semiSource(semi), port, consumer, now()), quantity, userId);
        }
    }

    /**
     * 逐道录入投料 (保存与正式提交都经过)。
     *
     * @param before 这一行上次已保存的内容, 没有或还是草稿传 {@code null}。
     *               已经投下去的量不重判: 改备注重存不能因为后来超时了而失败, 只判新增的量。
     */
    public void assertWebFeed(String factoryId, String planId, ProcessSheetRowRequest req,
                              ProcessSheetRowRequest before, Long userId) {
        if (req == null) {
            return;
        }
        boolean hasUpstream = req.getUpstreamSources() != null && !req.getUpstreamSources().isEmpty();
        boolean hasRaw = req.getRawMaterialInputs() != null && !req.getRawMaterialInputs().isEmpty();
        if (!hasUpstream && !hasRaw) {
            return;
        }
        Consumer consumer = consumerOfPlan(factoryId, planId);
        if (consumer == null || consumer.limited().isEmpty()) {
            return;
        }
        LocalDateTime now = now();
        if (hasRaw) {
            Map<String, BigDecimal> prior = priorRaw(before);
            for (ProcessSheetRowRequest.RawInput input : req.getRawMaterialInputs()) {
                if (input == null || input.getMaterialBatchId() == null) {
                    continue;
                }
                BigDecimal added = added(input.getQuantity(), prior.get(input.getMaterialBatchId()));
                if (added != null && added.signum() <= 0) {
                    continue;
                }
                MaterialBatch batch = materialBatchRepository
                        .findByIdAndFactoryId(input.getMaterialBatchId(), factoryId).orElse(null);
                if (batch == null) {
                    continue;   // 不存在由后面的批次校验报错
                }
                WorkflowTaskPort port = matchRef(consumer, input.getWorkflowPortId(), input.getMaterialNodeId(),
                        batch.getMaterialTypeId(), req.getProcessOrder());
                if (port != null) {
                    assertDrawable(factoryId, evaluate(factoryId, rawSource(batch), port, consumer, now), added, userId);
                }
            }
        }
        if (hasUpstream) {
            Map<String, BigDecimal> prior = priorUpstream(before);
            for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                if (ref == null || ref.getSourceBatchNumber() == null) {
                    continue;
                }
                BigDecimal added = added(ref.getFeedQuantityKg(), prior.get(ref.getSourceBatchNumber()));
                if (added != null && added.signum() <= 0) {
                    continue;
                }
                Source source = upstreamSource(factoryId, ref);
                if (source == null) {
                    continue;   // 不存在由 resolveEdges 报错
                }
                WorkflowTaskPort port = matchRef(consumer, ref.getWorkflowPortId(), ref.getMaterialNodeId(),
                        source.productTypeId(), req.getProcessOrder());
                if (port != null) {
                    // 投料量只有和这一行同单位才能按量比; 折算不了传 null, 由 assertDrawable 按最严判
                    BigDecimal quantity = sameUnit(req, source.unit()) ? added : null;
                    assertDrawable(factoryId, evaluate(factoryId, source, port, consumer, now), quantity, userId);
                }
            }
        }
    }

    private void assertDrawable(String factoryId, Evaluation e, BigDecimal quantity, Long userId) {
        SourceStatus s = e.status();
        String label = label(e.source());
        if (State.START_MISSING.equals(s.state())) {
            throw new BusinessException(409, label + " 还没有登记开始解冻，「" + e.processName() + "」不能投料")
                    .withCode(CODE_START_MISSING)
                    .withHint("这道工序的投料时限从解冻开始算。请先在逐道录入的时限面板里登记「开始解冻」；"
                            + "没有登记就不知道从什么时候算，系统不会替你猜")
                    .withSeverity("BLOCKING")
                    .withHintTarget(e.source().batchNumber())
                    .withDiagnosis(com.cretas.aims.service.gate.GateDiagnosis.of(
                                    com.cretas.aims.service.gate.GateId.TIME_LIMIT, CODE_START_MISSING, "BATCH", e.source().batchNumber())
                            .cause("未登记开始解冻")
                            .resolve("在逐道录入的时限面板里登记批次 " + e.source().batchNumber() + " 的「开始解冻」", null).build());
        }
        BigDecimal expired = s.expiredQuantity();
        if (expired == null || expired.signum() <= 0) {
            return;
        }
        if (e.config().perLot() && quantity != null && quantity.compareTo(s.drawableQuantity()) <= 0) {
            return;
        }
        recordBlocked(factoryId, e, userId);
        throw exceeded(e, quantity, label);
    }

    private BusinessException exceeded(Evaluation e, BigDecimal quantity, String label) {
        SourceStatus s = e.status();
        String unit = UnitDisplayNames.display(s.unit());
        String message = s.reason();
        if (e.config().perLot() && s.drawableQuantity().signum() > 0 && quantity != null) {
            message = label + " 有 " + plain(s.expiredQuantity()) + unit + " 已超时，现在只能领 "
                    + plain(s.drawableQuantity()) + unit + "，本次要领 " + plain(quantity) + unit;
        }
        return new BusinessException(409, message)
                .withCode(CODE_EXCEEDED)
                .withHint("请通知质检员在 " + RELEASE_PATH + "复检放行或判不合格，处置后再领；也可以改选没有超时的批次")
                .withSeverity("BLOCKING")
                .withHintTarget(e.source().batchNumber())
                .withDiagnosis(com.cretas.aims.service.gate.GateDiagnosis.of(
                                com.cretas.aims.service.gate.GateId.TIME_LIMIT, CODE_EXCEEDED, "BATCH", e.source().batchNumber())
                        .cause("已超时 " + plain(s.expiredQuantity()) + unit)
                        .qty(quantity, s.drawableQuantity()).unit(unit)
                        .resolve("通知质检员在「质量管理 → 质检放行」复检放行或判不合格批次 " + e.source().batchNumber()
                                + "; 或改选没有超时的批次", com.cretas.aims.service.gate.GateRoutes.QC_RELEASE).build());
    }

    private void recordBlocked(String factoryId, Evaluation e, Long userId) {
        SourceStatus s = e.status();
        WorkflowTaskPort port = e.port();
        try {
            eventWriter.openIfAbsent(ProcessTimeLimitEvent.builder()
                    .factoryId(factoryId)
                    .targetType(s.targetType())
                    .targetId(s.targetId())
                    .batchNumber(s.batchNumber())
                    .productTypeId(e.source().productTypeId())
                    .productionBatchId(e.consumerBatchId())
                    .taskId(port.getTaskId())
                    .workflowPortId(port.getWorkflowPortId())
                    .processName(e.processName())
                    .limitMinutes(s.limitMinutes())
                    .startEvent(s.startEvent())
                    .mixRule(e.config().mixRule())
                    .startAt(e.expiredStart())
                    .deadlineAt(e.expiredDeadline())
                    .blockedAt(now())
                    .blockedBy(userId)
                    .expiredQuantity(s.expiredQuantity())
                    .unit(s.unit())
                    .coveredUntil(e.coveredUntil())
                    .recheckValidMinutes(e.config().recheckValidMinutes())
                    .status(ProcessTimeLimitEvent.Status.OPEN)
                    .build());
        } catch (RuntimeException ex) {
            // 留痕失败不能把「超时」这个结论吞掉: 领用照样拦, 下一次被拦时再写
            log.error("[时限] 超时留痕写入失败 factory={} target={}:{}", factoryId, s.targetType(), s.targetId(), ex);
        }
    }

    // ===================== 页面读 =====================

    /**
     * 列表 + 一句归属提示。{@code ownershipNotice} 非空 = 领用计划的归属判不出 (旧数据: 代工计划缺客户 id 等):
     * 在制批 / 半成品一律不列 (领用闸同样会拒), 原料照常列出 —— ⛔ 不因为一条归属判定把整张列表打挂。
     */
    public record SourceStatusView(List<SourceStatus> sources, String ownershipNotice) {
    }

    /** 这道工序每个配了时限的端口, 当前可选来源的状态。App 用。 */
    @Transactional(readOnly = true)
    public List<SourceStatus> statusForTask(String factoryId, Long taskId) {
        return statusViewForTask(factoryId, taskId).sources();
    }

    @Transactional(readOnly = true)
    public SourceStatusView statusViewForTask(String factoryId, Long taskId) {
        WorkProcessTask task = taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .orElseThrow(() -> new BusinessException(404, "工序任务不存在: " + taskId));
        OwnerLookup owner = lookupOwner(() -> ownershipGuard.ofProductionBatch(factoryId, task.getProductionBatchId()));
        return statusOf(factoryId, consumerOfTask(factoryId, task), null, owner);
    }

    /** 同上, 按计划 + 工序序。web 逐道录入用。 */
    @Transactional(readOnly = true)
    public List<SourceStatus> statusForPlan(String factoryId, String planId, Integer processOrder) {
        return statusViewForPlan(factoryId, planId, processOrder).sources();
    }

    @Transactional(readOnly = true)
    public SourceStatusView statusViewForPlan(String factoryId, String planId, Integer processOrder) {
        Consumer consumer = consumerOfPlan(factoryId, planId);
        if (consumer == null) {
            return new SourceStatusView(List.of(), null);
        }
        return statusOf(factoryId, consumer, processOrder, lookupOwner(() -> ownershipGuard.ofPlanId(factoryId, planId)));
    }

    /** 领用计划的归属; 判不出 ({@code INTERMEDIATE_OWNER_CONTEXT_REQUIRED}) 时 owner 为空并带上原因, 其余异常照抛。 */
    private record OwnerLookup(IntermediateOwnershipGuard.Owner owner, String failure) {
    }

    private static OwnerLookup lookupOwner(java.util.function.Supplier<IntermediateOwnershipGuard.Owner> lookup) {
        try {
            return new OwnerLookup(lookup.get(), null);
        } catch (BusinessException e) {
            if (!IntermediateOwnershipGuard.CODE_CONTEXT_REQUIRED.equals(e.getErrorCode())) {
                throw e;
            }
            return new OwnerLookup(null, e.getMessage());
        }
    }

    static final String OWNER_UNKNOWN_NOTICE_PREFIX = "本计划缺客户信息，无法判定在制品归属";

    private SourceStatusView statusOf(String factoryId, Consumer consumer, Integer processOrder, OwnerLookup owner) {
        List<WorkflowTaskPort> limited = consumer.limited();
        if (consumer.task() != null) {
            limited = limited.stream()
                    .filter(p -> Objects.equals(p.getTaskId(), consumer.task().getId())).toList();
        }
        if (processOrder != null) {
            Set<Long> taskIds = tasksAtOrder(factoryId, limited, processOrder);
            limited = limited.stream().filter(p -> taskIds.contains(p.getTaskId())).toList();
        }
        if (limited.isEmpty()) {
            return new SourceStatusView(List.of(), null);
        }
        IntermediateOwnershipGuard.Owner listOwner = owner.owner();
        String notice = null;
        if (listOwner == null && limited.stream().anyMatch(p -> !isRawPort(p))) {
            notice = OWNER_UNKNOWN_NOTICE_PREFIX + "（" + owner.failure() + "）：在制品和半成品暂不列出，原料照常显示。"
                    + "请从已审批的代工销售订单重新建计划。";
            log.warn("时限列表: 领用计划归属判不出, 只列原料: {}", owner.failure());
        }
        LocalDateTime now = now();
        List<SourceStatus> out = new ArrayList<>();
        Map<Long, Optional<IntermediateOwnershipGuard.Owner>> wipOwners = new HashMap<>();
        for (WorkflowTaskPort port : limited) {
            for (Source source : candidates(factoryId, port)) {
                if (!visibleTo(factoryId, listOwner, source, wipOwners)) {
                    continue;
                }
                out.add(evaluate(factoryId, source, port, consumer, now).status());
            }
        }
        return new SourceStatusView(out, notice);
    }

    private Set<Long> tasksAtOrder(String factoryId, List<WorkflowTaskPort> ports, Integer processOrder) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long taskId : ports.stream().map(WorkflowTaskPort::getTaskId).collect(Collectors.toSet())) {
            taskRepository.findByFactoryIdAndId(factoryId, taskId)
                    .filter(t -> processOrder.equals(t.getProcessOrder()))
                    .ifPresent(t -> ids.add(taskId));
        }
        return ids;
    }

    /**
     * 缺口 16 (2026-11-14 F006 验收): 这张列表在逐道录入里就是「这道能领哪些料」, 此前不看归属 ——
     * 客户 A 的在制批一放行就会出现在客户 B 计划的列表里 (保存时才被 {@link IntermediateOwnershipGuard} 409)。
     * 现在与领用闸、半成品投料下拉同一口径 ({@code sameOwner}): 领用计划的归属 == 料的归属才列出 ——
     * 客户所有的只给同一客户的计划, 公司自有的只给本厂自有计划 (列出注定 409 的批次只会让人选了再被拒,
     * organizer 2026-11-14 定)。
     * 在制批归属从产出它的生产批次的计划现算 (与领用闸同口径); 算不出 (批次 / 计划已不存在) ⇒ 不列出 —— 领用闸同样会拒。
     */
    private boolean visibleTo(String factoryId, IntermediateOwnershipGuard.Owner listOwner, Source source,
                              Map<Long, Optional<IntermediateOwnershipGuard.Owner>> wipOwners) {
        boolean intermediate = TargetType.SEMI_FINISHED.equals(source.type())
                || TargetType.WIP_MATERIAL_BATCH.equals(source.type());
        if (intermediate && listOwner == null) {
            return false;   // 领用计划归属判不出: 在制品 / 半成品一律不列 (列表上另有提示)
        }
        IntermediateOwnershipGuard.Owner material;
        if (TargetType.SEMI_FINISHED.equals(source.type())) {
            material = IntermediateOwnershipGuard.ofSemi(source.semi());
        } else if (TargetType.WIP_MATERIAL_BATCH.equals(source.type())) {
            Optional<IntermediateOwnershipGuard.Owner> owner = wipOwner(factoryId, source.material(), wipOwners);
            if (owner.isEmpty()) {
                return false;
            }
            material = owner.get();
        } else {
            return true;   // 原料 / 成品: 归属另有闸, 本修复不动
        }
        return IntermediateOwnershipGuard.sameOwner(listOwner, material);
    }

    private Optional<IntermediateOwnershipGuard.Owner> wipOwner(
            String factoryId, MaterialBatch batch, Map<Long, Optional<IntermediateOwnershipGuard.Owner>> cache) {
        Long productionBatchId;
        try {
            productionBatchId = Long.valueOf(batch.getSourceDocId());
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(productionBatchId, id -> {
            try {
                return Optional.of(ownershipGuard.ofProductionBatch(factoryId, id));
            } catch (BusinessException e) {
                log.warn("时限列表: 在制批 {} 的归属算不出, 不列出: {}", batch.getBatchNumber(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    private List<Source> candidates(String factoryId, WorkflowTaskPort port) {
        String sku = port.getSkuId();
        List<Source> out = new ArrayList<>();
        if (isRawPort(port)) {
            String workshopId = warehouseResolver.resolveWorkshopId(factoryId);
            materialBatchRepository.findByFactoryIdAndMaterialTypeIdAndWarehouseId(factoryId, sku, workshopId).stream()
                    .filter(b -> b.getStatus() == MaterialBatchStatus.AVAILABLE)
                    .filter(b -> b.getCurrentQuantity() != null && b.getCurrentQuantity().signum() > 0)
                    .forEach(b -> out.add(rawSource(b)));
            return out;
        }
        semiRepository.findAvailableByFactory(factoryId).stream()
                .filter(s -> Objects.equals(s.getProductTypeId(), sku))
                .filter(s -> s.computeBalance().signum() > 0)
                .forEach(s -> out.add(semiSource(s)));
        materialBatchRepository.findWipBatchesByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE).stream()
                .filter(b -> Objects.equals(b.getProductTypeId(), sku))
                .filter(b -> b.getCurrentQuantity() != null && b.getCurrentQuantity().signum() > 0)
                .forEach(b -> out.add(wipSource(b)));
        return out;
    }

    /** 待处置清单。 */
    @Transactional(readOnly = true)
    public List<ProcessTimeLimitEvent> listOpen(String factoryId) {
        return eventRepository.findByFactoryIdAndStatusOrderByBlockedAtDesc(factoryId, ProcessTimeLimitEvent.Status.OPEN);
    }

    /** 处置记录 (含已处置)。 */
    @Transactional(readOnly = true)
    public List<ProcessTimeLimitEvent> listRecent(String factoryId) {
        return eventRepository.findTop200ByFactoryIdOrderByBlockedAtDesc(factoryId);
    }

    // ===================== 处置 =====================

    @Transactional
    public ProcessTimeLimitEvent decide(String factoryId, Long eventId, DecisionCommand cmd, Long userId, String userName) {
        if (cmd == null || cmd.decision() == null || userId == null) {
            throw invalid("处置请求不完整");
        }
        String decision = cmd.decision().trim().toUpperCase();
        boolean release = ProcessTimeLimitEvent.Status.RELEASED.equals(decision);
        if (!release && !ProcessTimeLimitEvent.Status.REJECTED.equals(decision)) {
            throw invalid("处置结论只能是复检放行或判不合格");
        }
        String conclusion = cmd.conclusion() == null ? "" : cmd.conclusion().trim();
        if (conclusion.isEmpty() || conclusion.length() > CONCLUSION_MAX) {
            throw invalid("请填写复检结论（不超过 " + CONCLUSION_MAX + " 字）");
        }
        ProcessTimeLimitEvent event = eventRepository.findByIdAndFactoryIdForUpdate(eventId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "超时记录不存在: " + eventId));
        if (!ProcessTimeLimitEvent.Status.OPEN.equals(event.getStatus())) {
            throw new BusinessException(409, "批次 " + event.getBatchNumber() + " 的这条超时记录已经处置过了")
                    .withCode(CODE_EVENT_CLOSED)
                    .withHint("请刷新列表");
        }
        LocalDateTime now = now();
        if (release) {
            event.setValidUntil(event.getRecheckValidMinutes() == null
                    ? null : now.plusMinutes(event.getRecheckValidMinutes()));
        } else {
            writeOff(factoryId, event, userId);
        }
        event.setStatus(decision);
        event.setConclusion(conclusion);
        event.setDecidedBy(userId);
        event.setDecidedByName(userName);
        event.setDecidedAt(now);
        log.info("[时限] 处置 factory={} event={} target={}:{} decision={} by={}",
                factoryId, eventId, event.getTargetType(), event.getTargetId(), decision, userId);
        return eventRepository.save(event);
    }

    private void writeOff(String factoryId, ProcessTimeLimitEvent event, Long userId) {
        switch (event.getTargetType()) {
            case TargetType.SEMI_FINISHED -> {
                SemiFinishedInventory semi = semiRepository
                        .findByIdAndFactoryIdForUpdate(Long.valueOf(event.getTargetId()), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "半成品库存行不存在: " + event.getTargetId()));
                BigDecimal quantity = event.getExpiredQuantity() == null
                        ? BigDecimal.ZERO : event.getExpiredQuantity().min(semi.computeBalance().max(BigDecimal.ZERO));
                if (quantity.signum() > 0) {
                    qcReleaseService.writeOffSemi(factoryId, semi, quantity, userId,
                            ProcessClock.REJECT_REF_PREFIX + event.getId());
                    semiRepository.save(semi);
                }
            }
            case TargetType.WIP_MATERIAL_BATCH, TargetType.RAW_MATERIAL_BATCH -> {
                MaterialBatch batch = materialBatchRepository
                        .findByIdAndFactoryIdForUpdate(event.getTargetId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "批次不存在: " + event.getTargetId()));
                batch.setStatus(MaterialBatchStatus.DEFECTIVE);
                materialBatchRepository.save(batch);
            }
            case TargetType.FINISHED_GOODS -> {
                FinishedGoodsBatch batch = finishedGoodsRepository
                        .findByIdAndFactoryIdForUpdate(event.getTargetId(), factoryId)
                        .orElseThrow(() -> new BusinessException(404, "成品批次不存在: " + event.getTargetId()));
                batch.setStatus(FinishedGoodsBatch.Status.DEFECTIVE);
                finishedGoodsRepository.save(batch);
            }
            default -> throw invalid("不支持的处置对象: " + event.getTargetType());
        }
    }

    // ===================== 人工登记 =====================

    /**
     * 登记解冻开始 / 开始暂停。时刻默认取服务端当前时间。
     * 解冻重复登记返回第一条: 再点一次不能把起算点往后挪。
     *
     * <p>纪律排查 A13 (Steve 2026-11-10): 解冻开始允许补登实际开始时间 —— 早上 6 点开始解冻、8 点才登记,
     * 原来只能记成 8 点, 计时失真且偏宽。补登必须填原因并留痕 (backfill_reason + 登记时刻 created_at),
     * 最多往前补多久由工厂配置 ({@code production.time_limit.thaw_backfill_max_minutes}, 0 = 不允许补登)。
     * 补登只会把起算点往<b>前</b>挪 (更严), 不会把超时的料救回来。冷藏暂停仍只收当前时刻。
     */
    @Transactional
    public ProcessTimeMark startMark(String factoryId, MarkCommand cmd, Long userId, String userName) {
        if (cmd == null || cmd.targetType() == null || cmd.targetId() == null || userId == null) {
            throw invalid("登记请求不完整");
        }
        String kind = cmd.kind();
        boolean start = ProcessTimeMark.Kind.START.equals(kind) && ProcessTimeLimitConfig.MARK_THAW.equals(cmd.code());
        boolean pause = ProcessTimeMark.Kind.PAUSE.equals(kind)
                && ProcessTimeLimitConfig.PAUSE_COLD_STORAGE.equals(cmd.code());
        if (!start && !pause) {
            throw invalid("只能登记开始解冻或冷藏暂停");
        }
        assertTargetExists(factoryId, cmd.targetType(), cmd.targetId());
        List<ProcessTimeMark> existing = markRepository
                .findByTargets(factoryId, cmd.targetType(), List.of(cmd.targetId())).stream()
                .filter(m -> kind.equals(m.getMarkKind()) && cmd.code().equals(m.getMarkCode()))
                .toList();
        if (start && !existing.isEmpty()) {
            return existing.get(0);
        }
        if (pause && existing.stream().anyMatch(m -> m.getEndedAt() == null)) {
            throw new BusinessException(409, "这批料已经在暂停计时中")
                    .withCode(CODE_PAUSE_OPEN)
                    .withHint("请先结束上一次暂停");
        }
        LocalDateTime at = now();
        String backfillReason = null;
        if (cmd.startedAt() != null) {
            LocalDateTime claimed = cmd.startedAt();
            if (claimed.isAfter(at.plusMinutes(BACKFILL_CLOCK_SKEW_MINUTES))) {
                throw invalid("开始时间不能晚于现在");
            }
            if (claimed.isBefore(at.minusMinutes(BACKFILL_CLOCK_SKEW_MINUTES))) {
                if (pause) {
                    throw invalid("冷藏暂停只能登记当前时刻, 不能补登");
                }
                int maxMinutes = disciplinePolicy != null
                        ? disciplinePolicy.thawBackfillMaxMinutes(factoryId)
                        : com.cretas.aims.service.discipline.FactoryDisciplinePolicy.DEFAULT_THAW_BACKFILL_MAX_MINUTES_PUBLIC;
                LocalDateTime earliest = at.minusMinutes(maxMinutes);
                if (maxMinutes <= 0 || claimed.isBefore(earliest)) {
                    throw new BusinessException(409, maxMinutes <= 0
                            ? "本厂设置为解冻开始不允许补登, 只能登记当前时刻"
                            : "补登的开始解冻时间超出本厂允许的范围 (最多往前补 " + maxMinutes + " 分钟, 最早 "
                                    + earliest.format(TIME_TEXT) + ")")
                            .withCode(CODE_BACKFILL_TOO_EARLY)
                            .withHint("最长补登时长由本厂在「业务阈值配置 → 生产」里设置 (production.time_limit.thaw_backfill_max_minutes)")
                            .withHintTarget("开始时间");
                }
                if (cmd.backfillReason() == null || cmd.backfillReason().isBlank()) {
                    throw new BusinessException(409, "补登开始解冻时间必须填写补登原因")
                            .withCode(CODE_BACKFILL_REASON_REQUIRED)
                            .withHint("请说明为什么没有在开始解冻时当场登记")
                            .withHintTarget("补登原因");
                }
                at = claimed;
                String reason = cmd.backfillReason().trim();
                backfillReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
            }
        }
        return markRepository.save(ProcessTimeMark.builder()
                .factoryId(factoryId)
                .targetType(cmd.targetType())
                .targetId(cmd.targetId())
                .markKind(kind)
                .markCode(cmd.code())
                .startedAt(at)
                .startedBy(userId)
                .startedByName(userName)
                .note(cmd.note())
                .backfillReason(backfillReason)
                .build());
    }

    /** 结束暂停。 */
    @Transactional
    public ProcessTimeMark endMark(String factoryId, Long markId, Long userId) {
        ProcessTimeMark mark = markRepository.findByIdAndFactoryId(markId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "登记不存在: " + markId));
        if (!ProcessTimeMark.Kind.PAUSE.equals(mark.getMarkKind()) || mark.getEndedAt() != null) {
            throw invalid("这条登记不在暂停中");
        }
        mark.setEndedAt(now());
        mark.setEndedBy(userId);
        return markRepository.save(mark);
    }

    private void assertTargetExists(String factoryId, String type, String id) {
        boolean found = switch (type) {
            case TargetType.SEMI_FINISHED -> semiRepository.findById(parseLong(id))
                    .filter(s -> factoryId.equals(s.getFactoryId())).isPresent();
            case TargetType.WIP_MATERIAL_BATCH, TargetType.RAW_MATERIAL_BATCH ->
                    materialBatchRepository.findByIdAndFactoryId(id, factoryId).isPresent();
            default -> throw invalid("这类批次不支持登记: " + type);
        };
        if (!found) {
            throw new BusinessException(404, "批次不存在或不属于本厂: " + id);
        }
    }

    private Long parseLong(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw invalid("批次 id 不合法: " + id);
        }
    }

    // ===================== 判定 =====================

    private Evaluation evaluate(String factoryId, Source source, WorkflowTaskPort port, Consumer consumer,
                                LocalDateTime now) {
        ProcessTimeLimitConfig config = configOf(port);
        String processName = processNameOf(factoryId, port, consumer);
        // 放行只算同一端口的: 宽松工序的复检结论不能替严格工序放行
        List<ProcessTimeLimitEvent> releases = eventRepository.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(
                        factoryId, source.type(), source.id(), ProcessTimeLimitEvent.Status.RELEASED).stream()
                .filter(e -> Objects.equals(e.getWorkflowPortId(), port.getWorkflowPortId()))
                .toList();
        boolean paused;
        List<LotClock> clocks;
        boolean startMissing = false;
        if (TargetType.SEMI_FINISHED.equals(source.type())) {
            List<ProcessClock.Span> pauses = pausesOf(factoryId, source.type(), List.of(source.id()), config);
            paused = pauses.stream().anyMatch(p -> p.to() == null);
            clocks = semiClocks(factoryId, source.semi(), config, pauses, releases, consumer, port, now);
        } else if (TargetType.RAW_MATERIAL_BATCH.equals(source.type())) {
            List<MaterialBatch> chain = config.inheritsSplit()
                    ? lineageChain(factoryId, source.material()) : List.of(source.material());
            List<String> ids = chain.stream().map(MaterialBatch::getId).toList();
            List<ProcessClock.Span> pauses = pausesOf(factoryId, source.type(), ids, config);
            paused = pauses.stream().anyMatch(p -> p.to() == null);
            LocalDateTime start = rawStart(factoryId, chain, source, config);
            startMissing = start == null && StartEvent.THAW_MARKED.equals(config.startEvent());
            clocks = List.of(clock(start, config.maxMinutes(), source.balance(), config, pauses, releases, now));
        } else {
            List<ProcessClock.Span> pauses = pausesOf(factoryId, source.type(), List.of(source.id()), config);
            paused = pauses.stream().anyMatch(p -> p.to() == null);
            LocalDateTime start = wholeBatchStart(factoryId, source, config, consumer, port);
            clocks = List.of(clock(start, config.maxMinutes(), source.balance(), config, pauses, releases, now));
        }
        Long pauseMarkId = paused ? openPauseMarkId(factoryId, source, config) : null;
        return summarize(source, port, consumer.productionBatchId(), config, processName, clocks, paused,
                pauseMarkId, startMissing, claimedNotDeducted(factoryId, source, config), now);
    }

    private Evaluation summarize(Source source, WorkflowTaskPort port, Long consumerBatchId,
                                 ProcessTimeLimitConfig config,
                                 String processName, List<LotClock> clocks, boolean paused, Long pauseMarkId,
                                 boolean startMissing, BigDecimal claimed, LocalDateTime now) {
        List<LotClock> active = clocks.stream().filter(c -> !c.waived() && c.start() != null)
                .sorted(Comparator.comparing(LotClock::deadline)).toList();
        List<LotClock> overdue = active.stream().filter(c -> c.deadline().isBefore(now)).toList();
        boolean anyWaived = clocks.stream().anyMatch(LotClock::waived);

        BigDecimal balance = source.balance() == null ? BigDecimal.ZERO : source.balance().max(BigDecimal.ZERO);
        BigDecimal expired;
        LocalDateTime coveredUntil = null;
        if (overdue.isEmpty()) {
            expired = BigDecimal.ZERO;
        } else if (config.perLot()) {
            expired = overdue.stream().map(LotClock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add).min(balance);
            coveredUntil = overdue.stream().map(LotClock::start).max(Comparator.naturalOrder()).orElse(now);
        } else {
            expired = balance;
            coveredUntil = now;
        }
        BigDecimal drawable = balance.subtract(expired).subtract(claimed).max(BigDecimal.ZERO);

        LotClock shown = overdue.isEmpty() ? (active.isEmpty() ? null : active.get(0)) : overdue.get(0);
        // 按笔时还有没超时的部分: 倒计时显示下一笔, 超时量单独写
        LotClock next = active.stream().filter(c -> !c.deadline().isBefore(now)).findFirst().orElse(null);
        LotClock clockShown = config.perLot() && next != null ? next : shown;

        String state;
        if (startMissing) {
            state = State.START_MISSING;
        } else if (!overdue.isEmpty()) {
            state = State.EXPIRED;
        } else if (active.isEmpty()) {
            state = anyWaived ? State.RELEASED : State.NOT_STARTED;
        } else if (config.warnMinutes() != null
                && Duration.between(now, active.get(0).deadline()).toMinutes() <= config.warnMinutes()) {
            state = State.WARNING;
        } else {
            state = State.OK;
        }
        Long remaining = clockShown == null ? null : minutesBetween(now, clockShown.deadline());
        String reason = reason(source, config, processName, state, shown, now, paused);
        SourceStatus status = new SourceStatus(source.type(), source.id(), source.batchNumber(),
                port.getWorkflowPortId(), processName, config.maxMinutes(), config.startEvent(), state,
                clockShown == null ? null : clockShown.start(), clockShown == null ? null : clockShown.deadline(),
                remaining, reason, balance, expired, drawable, source.unit(), paused, config.perLot(), pauseMarkId);
        LotClock first = overdue.isEmpty() ? null : overdue.get(0);
        return new Evaluation(source, port, consumerBatchId, config, processName, status, coveredUntil,
                first == null ? null : first.start(), first == null ? null : first.deadline());
    }

    /** 按笔时: 这一行已被录入 / 待审批报工认领、账上还没扣的量。整批规则不看数量, 不必查。 */
    private BigDecimal claimedNotDeducted(String factoryId, Source source, ProcessTimeLimitConfig config) {
        if (!config.perLot() || !TargetType.SEMI_FINISHED.equals(source.type())) {
            return BigDecimal.ZERO;
        }
        BigDecimal pendingReports = reportRepository.sumPendingInputBySourceWipNo(factoryId, source.batchNumber(), null);
        PendingInterimFeedService.Snapshot rows = pendingInterimFeedService == null
                ? PendingInterimFeedService.Snapshot.empty()
                : pendingInterimFeedService.pendingSemiFeedSnapshot(factoryId);
        if (rows.isUnresolved(source.batchNumber())) {
            return source.balance();   // 待扣量算不出来: 不当成 0, 按全被认领算
        }
        return (pendingReports == null ? BigDecimal.ZERO : pendingReports).add(rows.pendingOf(source.batchNumber()));
    }

    private String reason(Source source, ProcessTimeLimitConfig config, String processName, String state,
                          LotClock shown, LocalDateTime now, boolean paused) {
        return switch (state) {
            case State.START_MISSING -> "还没有登记开始解冻，不能投料";
            case State.NOT_STARTED -> "还没有开始计时（" + startText(config.startEvent()) + "后开始算）";
            case State.RELEASED -> "超时后已复检放行";
            case State.EXPIRED -> label(source) + " 从 " + shown.start().format(TIME_TEXT) + "（"
                    + startText(config.startEvent()) + "）起算，「" + processName + "」限 " + duration(config.maxMinutes())
                    + "，已超时 " + duration(minutesBetween(shown.deadline(), now)) + "，不能领用";
            default -> paused ? "冷藏暂停中，暂不计时" : null;
        };
    }

    private List<LotClock> semiClocks(String factoryId, SemiFinishedInventory semi, ProcessTimeLimitConfig config,
                                      List<ProcessClock.Span> pauses, List<ProcessTimeLimitEvent> releases,
                                      Consumer consumer, WorkflowTaskPort port, LocalDateTime now) {
        List<SemiFinishedInventoryTransaction> txns = semiTxnRepository.findBySemiFinishedIdOrderByCreatedAtAsc(semi.getId());
        Map<Long, ProductionReport> reports = reportsOf(txns, config, semi);
        boolean rework = Boolean.TRUE.equals(semi.getIsRework());
        int limit = rework && config.reworkMaxMinutes() != null ? config.reworkMaxMinutes() : config.maxMinutes();
        Function<ProcessClock.Lot, LocalDateTime> startOf = lot ->
                semiLotStart(factoryId, semi, lot, config, reports, consumer, port, rework);
        List<ProcessClock.Txn> facts = txns.stream()
                .map(t -> new ProcessClock.Txn(t.getTxnType(), t.getSourceRef(), t.getReportId(),
                        t.getQuantity(), t.getCreatedAt()))
                .toList();
        List<ProcessClock.Lot> lots = ProcessClock.replay(facts, semi.computeBalance(), semi.getCreatedAt(),
                config.perLot() ? (lot, at) -> {
                    LocalDateTime start = startOf.apply(lot);
                    return start != null
                            && ProcessClock.deadline(start, limit, pauses, config.maxPauseMinutes(), at).isBefore(at)
                            && !waived(start, releases, at);
                } : null);
        return lots.stream()
                .map(lot -> clock(startOf.apply(lot), limit, lot.open(), config, pauses, releases, now))
                .toList();
    }

    private LotClock clock(LocalDateTime start, int limit, BigDecimal quantity, ProcessTimeLimitConfig config,
                           List<ProcessClock.Span> pauses, List<ProcessTimeLimitEvent> releases, LocalDateTime now) {
        if (start == null) {
            return new LotClock(null, null, quantity, false);
        }
        return new LotClock(start, ProcessClock.deadline(start, limit, pauses, config.maxPauseMinutes(), now),
                quantity, waived(start, releases, now));
    }

    /** 这一笔被复检放行覆盖着吗: 起算不晚于事件当时, 且放行还在有效期内。 */
    private static boolean waived(LocalDateTime start, List<ProcessTimeLimitEvent> releases, LocalDateTime at) {
        return releases.stream().anyMatch(e -> !start.isAfter(e.getCoveredUntil())
                && (e.getValidUntil() == null || !at.isAfter(e.getValidUntil())));
    }

    private Map<Long, ProductionReport> reportsOf(List<SemiFinishedInventoryTransaction> txns,
                                                  ProcessTimeLimitConfig config, SemiFinishedInventory semi) {
        boolean needed = !StartEvent.OUTPUT_POSTED.equals(config.startEvent()) || Boolean.TRUE.equals(semi.getIsRework());
        if (!needed) {
            return Map.of();
        }
        Set<Long> ids = txns.stream().map(SemiFinishedInventoryTransaction::getReportId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductionReport> out = new HashMap<>();
        reportRepository.findAllById(ids).forEach(r -> out.put(r.getId(), r));
        return out;
    }

    private LocalDateTime semiLotStart(String factoryId, SemiFinishedInventory semi, ProcessClock.Lot lot,
                                       ProcessTimeLimitConfig config, Map<Long, ProductionReport> reports,
                                       Consumer consumer, WorkflowTaskPort port, boolean rework) {
        ProductionReport report = lot.reportId() == null ? null : reports.get(lot.reportId());
        if (rework) {
            if (ProcessTimeLimitConfig.ReworkRule.FROM_REWORK_OUTPUT.equals(config.reworkRule())) {
                return lot.at();
            }
            Long batchId = report != null ? report.getBatchId() : semi.getBatchId();
            return earliest(lot.at(), batchStart(factoryId, batchId));
        }
        return switch (config.startEvent()) {
            case StartEvent.OUTPUT_REPORTED -> report != null && report.getCreatedAt() != null
                    ? earliest(lot.at(), report.getCreatedAt()) : lot.at();
            case StartEvent.UPSTREAM_COMPLETED -> {
                Long taskId = report != null ? report.getWorkProcessTaskId() : semi.getSourceWorkProcessTaskId();
                yield taskId != null ? taskEnd(factoryId, taskId) : upstreamEnd(factoryId, consumer, port, lot.at());
            }
            default -> lot.at();
        };
    }

    private LocalDateTime batchStart(String factoryId, Long batchId) {
        if (batchId == null) {
            return null;
        }
        return productionBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .map(b -> b.getStartTime() != null ? b.getStartTime() : b.getCreatedAt())
                .orElse(null);
    }

    private LocalDateTime taskEnd(String factoryId, Long taskId) {
        return taskRepository.findByFactoryIdAndId(factoryId, taskId)
                .map(WorkProcessTask::getActualEndAt).orElse(null);
    }

    /** 找不到这一笔是哪道工序产的: 看本批工艺图里产这种料的上游; 也没有就按入账时刻算 (不当成未起算)。 */
    private LocalDateTime upstreamEnd(String factoryId, Consumer consumer, WorkflowTaskPort port, LocalDateTime fallback) {
        List<Long> upstream = consumer.ports().stream()
                .filter(p -> p.getDirection() == WorkflowTaskPort.Direction.OUTPUT)
                .filter(p -> Objects.equals(p.getMaterialNodeId(), port.getMaterialNodeId()))
                .map(WorkflowTaskPort::getTaskId)
                .distinct().toList();
        if (upstream.isEmpty()) {
            return fallback;
        }
        LocalDateTime latest = null;
        for (Long taskId : upstream) {
            LocalDateTime end = taskEnd(factoryId, taskId);
            if (end == null) {
                return null;
            }
            latest = latest == null || end.isAfter(latest) ? end : latest;
        }
        return latest;
    }

    private LocalDateTime wholeBatchStart(String factoryId, Source source, ProcessTimeLimitConfig config,
                                          Consumer consumer, WorkflowTaskPort port) {
        LocalDateTime posted;
        if (TargetType.WIP_MATERIAL_BATCH.equals(source.type())) {
            // 重存可能重建在制批次, 但产出它的生产批次不变: 取两者更早的, 重存不重置计时
            posted = earliest(source.material().getCreatedAt(),
                    createdAtOfBatch(factoryId, source.material().getSourceDocId()));
        } else {
            posted = source.finished().getCreatedAt();
        }
        return StartEvent.UPSTREAM_COMPLETED.equals(config.startEvent())
                ? upstreamEnd(factoryId, consumer, port, posted) : posted;
    }

    private LocalDateTime createdAtOfBatch(String factoryId, String batchId) {
        if (batchId == null) {
            return null;
        }
        try {
            return productionBatchRepository.findByIdAndFactoryId(Long.valueOf(batchId), factoryId)
                    .map(ProductionBatch::getCreatedAt).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime rawStart(String factoryId, List<MaterialBatch> chain, Source source,
                                   ProcessTimeLimitConfig config) {
        return switch (config.startEvent()) {
            case StartEvent.RAW_OUTBOUND -> chain.stream()
                    .filter(b -> TRANSFER_IN_DOC.equals(b.getSourceDocType()))
                    .map(MaterialBatch::getCreatedAt)
                    .min(Comparator.naturalOrder()).orElse(null);
            case StartEvent.THAW_MARKED -> markRepository.findByTargets(factoryId, source.type(),
                            chain.stream().map(MaterialBatch::getId).toList()).stream()
                    .filter(m -> ProcessTimeMark.Kind.START.equals(m.getMarkKind()))
                    .filter(m -> ProcessTimeLimitConfig.MARK_THAW.equals(m.getMarkCode()))
                    .map(ProcessTimeMark::getStartedAt)
                    .min(Comparator.naturalOrder()).orElse(null);
            default -> chain.stream().map(MaterialBatch::getCreatedAt).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
        };
    }

    /** 这批和它调拨来源的所有上游批次 (自己在最前)。拆批不重置: 子批的起算事实可能记在母批上。 */
    private List<MaterialBatch> lineageChain(String factoryId, MaterialBatch batch) {
        Map<String, MaterialBatch> seen = new LinkedHashMap<>();
        seen.put(batch.getId(), batch);
        Deque<String> frontier = new ArrayDeque<>(List.of(batch.getId()));
        int depth = 0;
        while (!frontier.isEmpty() && depth++ < LINEAGE_DEPTH_CAP) {
            Deque<String> next = new ArrayDeque<>();
            for (String id : frontier) {
                for (BatchLineageEdge edge : edgeRepository.findByFactoryIdAndTargetIdAndTargetType(
                        factoryId, id, LineageEdgeRecorder.NODE_MATERIAL_BATCH)) {
                    if (LineageEdgeRecorder.EDGE_MATERIAL_TRANSFER.equals(edge.getEdgeType())
                            && !seen.containsKey(edge.getSourceId())) {
                        materialBatchRepository.findByIdAndFactoryId(edge.getSourceId(), factoryId).ifPresent(parent -> {
                            seen.put(parent.getId(), parent);
                            next.add(parent.getId());
                        });
                    }
                }
            }
            frontier = next;
        }
        return List.copyOf(seen.values());
    }

    /** 进行中的暂停 (原料可能登记在母批上, 按继承规则一起找)。 */
    private Long openPauseMarkId(String factoryId, Source source, ProcessTimeLimitConfig config) {
        boolean inherited = TargetType.RAW_MATERIAL_BATCH.equals(source.type()) && config.inheritsSplit();
        List<String> ids = inherited
                ? lineageChain(factoryId, source.material()).stream().map(MaterialBatch::getId).toList()
                : List.of(source.id());
        return markRepository.findByTargets(factoryId, source.type(), ids).stream()
                .filter(m -> ProcessTimeMark.Kind.PAUSE.equals(m.getMarkKind()) && m.getEndedAt() == null)
                .filter(m -> config.pauseCodes().contains(m.getMarkCode()))
                .map(ProcessTimeMark::getId)
                .findFirst().orElse(null);
    }

    private List<ProcessClock.Span> pausesOf(String factoryId, String type, List<String> ids,
                                             ProcessTimeLimitConfig config) {
        if (config.pauseCodes().isEmpty()) {
            return List.of();
        }
        return markRepository.findByTargets(factoryId, type, ids).stream()
                .filter(m -> ProcessTimeMark.Kind.PAUSE.equals(m.getMarkKind()))
                .filter(m -> config.pauseCodes().contains(m.getMarkCode()))
                .map(m -> new ProcessClock.Span(m.getStartedAt(), m.getEndedAt()))
                .toList();
    }

    // ===================== 找端口 =====================

    private Consumer consumerOfTask(String factoryId, WorkProcessTask task) {
        if (task.getWorkflowInstanceId() == null) {
            return new Consumer(task.getProductionBatchId(), task, List.of());
        }
        List<WorkflowTaskPort> ports = portRepository
                .findByFactoryIdAndWorkflowInstanceId(factoryId, task.getWorkflowInstanceId());
        return new Consumer(task.getProductionBatchId(), task, ports);
    }

    /**
     * 这个计划的工艺快照。批次还没物化时 (二次加工开工在物化之前扣半成品) 读计划钉住的工艺版本;
     * 两者都没有的旧计划返回 {@code null} (不限时)。
     */
    private Consumer consumerOfPlan(String factoryId, String planId) {
        for (ProductionBatch batch : productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId)) {
            Optional<ProductionWorkflowInstance> instance =
                    instanceRepository.findByFactoryIdAndProductionBatchId(factoryId, batch.getId());
            if (instance.isPresent()) {
                return new Consumer(batch.getId(), null,
                        portRepository.findByFactoryIdAndWorkflowInstanceId(factoryId, instance.get().getId()));
            }
        }
        return planRepository.findByIdAndFactoryId(planId, factoryId)
                .map(ProductionPlan::getSelectedWorkflowRevisionId)
                .flatMap(id -> revisionRepository.findByIdAndFactoryId(id, factoryId))
                .map(revision -> new Consumer(null, null, limitedPortsOf(factoryId, revision.getNodesJson())))
                .orElse(null);
    }

    /** 工艺版本里配了时限的投入端口 (还没物化, 没有任务 id)。 */
    private List<WorkflowTaskPort> limitedPortsOf(String factoryId, String nodesJson) {
        JsonNode nodes;
        try {
            nodes = objectMapper.readTree(nodesJson);
        } catch (JsonProcessingException e) {
            throw new BusinessException(409, "工艺版本内容读不出来，不能判断投料时限")
                    .withCode(CODE_INVALID)
                    .withHint("请联系管理员核对该计划钉住的工艺版本");
        }
        Map<String, JsonNode> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.path("id").asText(), node));
        List<WorkflowTaskPort> out = new ArrayList<>();
        for (JsonNode node : nodes) {
            for (JsonNode raw : node.path("data").path("ports")) {
                if (!raw.hasNonNull("timeLimit") || !"INPUT".equals(raw.path("direction").asText())) {
                    continue;
                }
                JsonNode material = byId.get(raw.path("materialNodeId").asText());
                WorkflowTaskPort port = new WorkflowTaskPort();
                port.setFactoryId(factoryId);
                port.setWorkflowPortId(raw.path("id").asText());
                port.setDirection(WorkflowTaskPort.Direction.INPUT);
                port.setMaterialNodeId(raw.path("materialNodeId").asText());
                port.setMaterialKind(material == null ? null : material.path("kind").asText());
                port.setSkuId(material == null ? null : material.path("data").path("skuId").asText());
                port.setTimeLimitConfig(objectMapper.convertValue(raw.get("timeLimit"),
                        new TypeReference<Map<String, Object>>() { }));
                out.add(port);
            }
        }
        return out;
    }

    private static boolean isLimitedInput(WorkflowTaskPort port) {
        return port.getDirection() == WorkflowTaskPort.Direction.INPUT && port.getTimeLimitConfig() != null;
    }

    private static boolean isRawPort(WorkflowTaskPort port) {
        return "RAW_MATERIAL".equals(port.getMaterialKind());
    }

    /**
     * 按物料找端口。任务上下文时只看这道工序; 计划上下文 (二次加工) 看全图。
     * 端口的 SKU 对不上时 (BOM 候选替换了料): 这道工序没有任何投入端口认这种料、又只有一个配了时限的非原料端口 ⇒ 算它的。
     */
    private WorkflowTaskPort matchBySku(Consumer consumer, String sku) {
        List<WorkflowTaskPort> scope = consumer.task() == null ? consumer.ports() : consumer.ports().stream()
                .filter(p -> Objects.equals(p.getTaskId(), consumer.task().getId())).toList();
        List<WorkflowTaskPort> limited = scope.stream().filter(ProcessTimeLimitService::isLimitedInput).toList();
        if (limited.isEmpty()) {
            return null;
        }
        Optional<WorkflowTaskPort> exact = limited.stream().filter(p -> Objects.equals(p.getSkuId(), sku)).findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        boolean claimed = scope.stream().anyMatch(p -> p.getDirection() == WorkflowTaskPort.Direction.INPUT
                && Objects.equals(p.getSkuId(), sku));
        List<WorkflowTaskPort> nonRaw = limited.stream().filter(p -> !isRawPort(p)).toList();
        return !claimed && consumer.task() != null && nonRaw.size() == 1 ? nonRaw.get(0) : null;
    }

    /** 逐道录入的引用带了端口 id 就按 id; 旧客户端没带时按物料节点, 再按 SKU + 工序序。 */
    private WorkflowTaskPort matchRef(Consumer consumer, String portId, String nodeId, String sku, Integer processOrder) {
        List<WorkflowTaskPort> limited = consumer.limited();
        if (portId != null && !portId.isBlank()) {
            return limited.stream().filter(p -> portId.equals(p.getWorkflowPortId())).findFirst().orElse(null);
        }
        boolean byNode = nodeId != null && !nodeId.isBlank();
        return limited.stream()
                .filter(p -> byNode ? nodeId.equals(p.getMaterialNodeId()) : Objects.equals(p.getSkuId(), sku))
                .filter(p -> atOrder(p, processOrder))
                .findFirst().orElse(null);
    }

    /** 这个端口是不是这一道的。工艺版本读出来的端口还没有任务, 不分道。 */
    private boolean atOrder(WorkflowTaskPort port, Integer processOrder) {
        if (processOrder == null || port.getTaskId() == null) {
            return true;
        }
        return taskRepository.findByFactoryIdAndId(port.getFactoryId(), port.getTaskId())
                .map(t -> processOrder.equals(t.getProcessOrder())).orElse(false);
    }

    private static ProcessTimeLimitConfig configOf(WorkflowTaskPort port) {
        return ProcessTimeLimitConfig.parse(port.getTimeLimitConfig());
    }

    private String processNameOf(String factoryId, WorkflowTaskPort port, Consumer consumer) {
        if (port.getTaskId() == null) {
            return "下一道工序";
        }
        WorkProcessTask task = consumer.task() != null && Objects.equals(consumer.task().getId(), port.getTaskId())
                ? consumer.task()
                : taskRepository.findByFactoryIdAndId(factoryId, port.getTaskId()).orElse(null);
        if (task == null || task.getWorkProcessId() == null) {
            return "下一道工序";
        }
        return workProcessRepository.findById(task.getWorkProcessId())
                .map(p -> p.getProcessName()).orElse("下一道工序");
    }

    // ===================== 来源 =====================

    private Source upstreamSource(String factoryId, ProcessSheetRowRequest.UpstreamRef ref) {
        String number = ref.getSourceBatchNumber();
        if (ref.isFinishedGoods()) {
            return finishedGoodsRepository.findByFactoryIdAndBatchNumber(factoryId, number)
                    .map(ProcessTimeLimitService::finishedSource).orElse(null);
        }
        if (ref.isSemiFinished()) {
            return semiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, number)
                    .map(ProcessTimeLimitService::semiSource).orElse(null);
        }
        return productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, number)
                .flatMap(pb -> materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                        factoryId, QcInspectionGuard.WIP_SOURCE_DOC_TYPE, pb.getId().toString()))
                .map(ProcessTimeLimitService::wipSource).orElse(null);
    }

    private static Source semiSource(SemiFinishedInventory s) {
        return new Source(TargetType.SEMI_FINISHED, String.valueOf(s.getId()), s.getIntermediateBatchNo(),
                s.getProductTypeId(), s.getUnit(), s.computeBalance(), s, null, null);
    }

    private static Source wipSource(MaterialBatch b) {
        return new Source(TargetType.WIP_MATERIAL_BATCH, b.getId(), b.getBatchNumber(), b.getProductTypeId(),
                b.getQuantityUnit(), b.getCurrentQuantity(), null, b, null);
    }

    private static Source rawSource(MaterialBatch b) {
        return new Source(TargetType.RAW_MATERIAL_BATCH, b.getId(), b.getBatchNumber(), b.getMaterialTypeId(),
                b.getQuantityUnit(), b.getCurrentQuantity(), null, b, null);
    }

    private static Source finishedSource(FinishedGoodsBatch b) {
        return new Source(TargetType.FINISHED_GOODS, String.valueOf(b.getId()), b.getBatchNumber(),
                b.getProductTypeId(), b.getUnit(), b.getAvailableQuantity(), null, null, b);
    }

    // ===================== 小工具 =====================

    private static Map<String, BigDecimal> priorRaw(ProcessSheetRowRequest before) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (before != null && before.getRawMaterialInputs() != null) {
            before.getRawMaterialInputs().stream()
                    .filter(i -> i != null && i.getMaterialBatchId() != null && i.getQuantity() != null)
                    .forEach(i -> out.merge(i.getMaterialBatchId(), i.getQuantity(), BigDecimal::add));
        }
        return out;
    }

    private static Map<String, BigDecimal> priorUpstream(ProcessSheetRowRequest before) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (before != null && before.getUpstreamSources() != null) {
            before.getUpstreamSources().stream()
                    .filter(u -> u != null && u.getSourceBatchNumber() != null && u.getFeedQuantityKg() != null)
                    .forEach(u -> out.merge(u.getSourceBatchNumber(), u.getFeedQuantityKg(), BigDecimal::add));
        }
        return out;
    }

    /** 这次比上次多投多少; 没有上次 = 全部算新增。 */
    private static BigDecimal added(BigDecimal now, BigDecimal prior) {
        if (now == null || prior == null) {
            return now;
        }
        return now.subtract(prior);
    }

    private static boolean sameUnit(ProcessSheetRowRequest req, String unit) {
        String requested = req.getInputUnit() != null ? req.getInputUnit() : req.getUnit();
        return requested != null && requested.equalsIgnoreCase(unit);
    }

    private static LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toMinutes();
    }

    private static String duration(long minutes) {
        if (minutes <= 0) {
            return "不到 1 分钟";   // 刚过期: 「已超时 0 分钟」读起来像没超时
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + " 分钟";
        }
        return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
    }

    private static String label(Source source) {
        String kind = switch (source.type()) {
            case TargetType.SEMI_FINISHED -> "半成品批次";
            case TargetType.WIP_MATERIAL_BATCH -> "在制批次";
            case TargetType.RAW_MATERIAL_BATCH -> "原料批次";
            default -> "成品批次";
        };
        return kind + " " + source.batchNumber();
    }

    private static String startText(String startEvent) {
        return switch (startEvent) {
            case StartEvent.OUTPUT_REPORTED -> "上道报工";
            case StartEvent.UPSTREAM_COMPLETED -> "上道完工";
            case StartEvent.RAW_RECEIVED -> "原料入库";
            case StartEvent.RAW_OUTBOUND -> "领到生产仓";
            case StartEvent.THAW_MARKED -> "开始解冻";
            default -> "上道产出入账";
        };
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message).withCode(CODE_INVALID);
    }

    /** 测试可覆盖。 */
    protected LocalDateTime now() {
        return LocalDateTime.now();
    }
}
