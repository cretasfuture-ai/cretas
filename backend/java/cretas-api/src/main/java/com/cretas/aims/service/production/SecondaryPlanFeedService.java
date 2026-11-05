package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 返工计划 (二次加工) 的来源半成品怎么接进工序 (D-S315-01)。
 *
 * <h3>口径 (Steve 2026-11-04)</h3>
 * <ul>
 *   <li>来源半成品算作「上游已产出」, 解锁接这种料的那道工序。只对这个计划、这份来源、物料对得上的端口生效。</li>
 *   <li>料接不住的计划在开工时拦住, 不扣库存。</li>
 *   <li>重复消耗和超量继续拦: 报工累计不能超过计划量。</li>
 * </ul>
 *
 * <h3>库存账: 开工占用, 用掉时转消耗</h3>
 * 开工把计划量从来源行扣下来 (占用, 流水 {@code secondary-plan-deduct})。这笔还不是消耗:
 * 真正的消耗走既有路径 —— App 报工过账时 / 结单时 —— 才有报工号、血缘和成本。所以在消耗发生的那一刻
 * 先把对应的占用还回来 ({@link #release}), 再走原来的扣减。这样同一批料不会扣两次,
 * 撤回报工只还到库存, 没用完的占用在结单或取消时全部还回。
 * 占用还剩多少看流水 ({@link #heldQuantity}), ⛔ 不按计划量推算: 这个规则上线前从 /create-batch 开工的计划没有占用。
 *
 * <h3>并发</h3>
 * 报工和取消 / 结单都先锁计划再锁来源行 (同一顺序, 不互相死锁): 拿到计划锁之后再读状态,
 * 所以报工不会落在刚取消的计划上, 取消也不会漏看刚提交的报工。
 *
 * <h3>上线前开工的计划</h3>
 * 旧流水不带计划号, 认不出是谁占的。⛔ 不猜: 不自动还, 记到计划备注让人核对。
 */
@Slf4j
@Service
public class SecondaryPlanFeedService {

    private static final String DEDUCT_REF = "secondary-plan-deduct:";
    private static final String REVERSE_REF = "secondary-plan-reverse:";

    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final SemiFinishedInventoryRepository wipRepository;
    private final SemiFinishedInventoryTransactionRepository txnRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkflowTaskPortRepository portRepository;
    private final ProcessSheetRowRepository rowRepository;
    private final ProductionReportRepository reportRepository;
    private final WipInventoryService wipInventoryService;
    private final ObjectMapper objectMapper;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public SecondaryPlanFeedService(ProductionPlanRepository planRepository,
                                    ProductionBatchRepository batchRepository,
                                    SemiFinishedInventoryRepository wipRepository,
                                    SemiFinishedInventoryTransactionRepository txnRepository,
                                    WorkProcessTaskRepository taskRepository,
                                    WorkflowTaskPortRepository portRepository,
                                    ProcessSheetRowRepository rowRepository,
                                    ProductionReportRepository reportRepository,
                                    @Lazy WipInventoryService wipInventoryService,
                                    ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.batchRepository = batchRepository;
        this.wipRepository = wipRepository;
        this.txnRepository = txnRepository;
        this.taskRepository = taskRepository;
        this.portRepository = portRepository;
        this.rowRepository = rowRepository;
        this.reportRepository = reportRepository;
        this.wipInventoryService = wipInventoryService;
        this.objectMapper = objectMapper;
    }

    /** 一个返工计划和它的来源半成品。 */
    public record Source(ProductionPlan plan, Long wipId, String batchNo, String productTypeId,
                         BigDecimal plannedQuantity, String unit) {

        /** 这个投入端口接的是不是这份来源: 物料 (SKU) 对得上才算。 */
        public boolean feeds(WorkflowTaskPort port) {
            return port != null && port.getDirection() == WorkflowTaskPort.Direction.INPUT
                    && productTypeId != null && productTypeId.equals(port.getSkuId());
        }
    }

    /** 开工时扣下的占用流水号; 带计划 id, 占用才能按计划算。 */
    public static String deductRef(Long wipId, String planId) {
        return DEDUCT_REF + wipId + ":" + planId;
    }

    public static String reverseRef(Long wipId, String planId) {
        return REVERSE_REF + wipId + ":" + planId;
    }

    public Optional<Source> forPlan(String factoryId, ProductionPlan plan) {
        if (plan == null || !"SECONDARY".equals(plan.getPlanSourceType()) || plan.getSecondarySourceWipId() == null) {
            return Optional.empty();
        }
        // ⛔ 这里不能用 findById: 见 findSourceRefById 的说明, 解锁判定之后同一事务还要锁这一行扣账
        return wipRepository.findSourceRefById(plan.getSecondarySourceWipId())
                .filter(ref -> factoryId.equals(ref.getFactoryId()))
                .map(ref -> toSource(plan, ref));
    }

    public Optional<Source> forPlanId(String factoryId, String planId) {
        if (planId == null) {
            return Optional.empty();
        }
        return planRepository.findByIdAndFactoryId(planId, factoryId).flatMap(plan -> forPlan(factoryId, plan));
    }

    public Optional<Source> forBatch(String factoryId, Long productionBatchId) {
        if (productionBatchId == null) {
            return Optional.empty();
        }
        return batchRepository.findById(productionBatchId)
                .filter(batch -> factoryId.equals(batch.getFactoryId()))
                .map(ProductionBatch::getProductionPlanId)
                .flatMap(planId -> forPlanId(factoryId, planId));
    }

    private static Source toSource(ProductionPlan plan, SemiFinishedInventoryRepository.SourceRef ref) {
        return new Source(plan, ref.getId(), ref.getIntermediateBatchNo(), ref.getProductTypeId(),
                plan.getPlannedQuantity(), ref.getUnit());
    }

    /**
     * 开工保护: 工艺图上没有工序接这份来源 ⇒ 409。必须在扣库存之前调用。
     * 没有工艺图快照的旧式任务没有端口可比, 不在此列。
     */
    public void assertAcceptedBy(String factoryId, ProductionPlan plan, Long productionBatchId) {
        if (!"SECONDARY".equals(plan.getPlanSourceType()) || plan.getSecondarySourceWipId() == null) {
            return;
        }
        Source source = forPlan(factoryId, plan).orElseThrow(() -> new BusinessException(409,
                "返工计划的来源半成品已不存在, 不能开工")
                .withCode("SECONDARY_SOURCE_NOT_FOUND")
                .withHint("请取消本计划, 重新选择仍有库存的半成品创建返工计划")
                .withHintTarget("wipId"));
        List<WorkProcessTask> tasks = taskRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, productionBatchId);
        Set<Long> instanceIds = tasks.stream().map(WorkProcessTask::getWorkflowInstanceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (instanceIds.isEmpty()) {
            return;
        }
        Set<Long> taskIds = tasks.stream().map(WorkProcessTask::getId).collect(Collectors.toSet());
        boolean accepted = instanceIds.stream()
                .flatMap(id -> portRepository.findByFactoryIdAndWorkflowInstanceId(factoryId, id).stream())
                .anyMatch(port -> taskIds.contains(port.getTaskId()) && source.feeds(port));
        if (!accepted) {
            throw new BusinessException(409, "目标工艺图没有工序接这份半成品 (" + source.batchNo()
                    + "), 开工后料接不住, 已拦下, 库存没有扣减")
                    .withCode("SECONDARY_SOURCE_NOT_ACCEPTED")
                    .withHint("请换一个工艺图里有这种半成品投入的目标产品, 或在工艺图上给接料工序加这种半成品的投入后重新发布")
                    .withHintTarget("productTypeId");
        }
    }

    /** 这道工序是不是接这份来源的那道。 */
    public boolean receives(String factoryId, Source source, WorkProcessTask task) {
        if (task.getWorkflowInstanceId() == null) {
            return false;
        }
        return portRepository.findByFactoryIdAndWorkflowInstanceId(factoryId, task.getWorkflowInstanceId()).stream()
                .anyMatch(port -> Objects.equals(port.getTaskId(), task.getId()) && source.feeds(port));
    }

    /** 接料工序里最靠前的工序序; 这份计划没有接料工序时返回 {@link Integer#MIN_VALUE}。 */
    public int firstReceivingOrder(String factoryId, ProductionPlan plan, List<WorkProcessTask> tasks) {
        Optional<Source> source = forPlan(factoryId, plan);
        if (source.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        return tasks.stream()
                .filter(task -> task.getProcessOrder() != null && receives(factoryId, source.get(), task))
                .mapToInt(WorkProcessTask::getProcessOrder)
                .min()
                .orElse(Integer.MIN_VALUE);
    }

    /**
     * 开工占用: 所有开工入口都走这里 (/start、/create-batch、逐道录入翻状态)。
     * 同一计划只占一次: 流水里有过本计划的占用就不再扣。
     */
    public void takeHold(String factoryId, ProductionPlan plan, Long operatorId) {
        if (plan == null || plan.getId() == null || factoryId == null
                || !factoryId.equals(plan.getFactoryId())) {
            throw new BusinessException(409, "返工计划不属于当前工厂，不能占用来源半成品")
                    .withCode("SECONDARY_PLAN_FACTORY_MISMATCH")
                    .withHint("请重新打开本厂计划后再开工");
        }
        // Clerk entry may have read a PENDING plan without a lock. Serialize all
        // entry points on plan -> source, then re-read the ledger after the lock.
        ProductionPlan lockedPlan = planRepository.findByIdForUpdate(plan.getId())
                .orElseThrow(() -> new BusinessException(409, "返工计划已不存在，不能占用来源半成品")
                        .withCode("SECONDARY_PLAN_NOT_FOUND")
                        .withHint("请刷新计划列表并重新选择计划"));
        if (entityManager != null) {
            entityManager.refresh(lockedPlan);
        }
        if (!factoryId.equals(lockedPlan.getFactoryId()) || lockedPlan.getDeletedAt() != null) {
            throw new BusinessException(409, "返工计划不属于当前工厂或已删除，不能占用来源半成品")
                    .withCode("SECONDARY_PLAN_FACTORY_MISMATCH")
                    .withHint("请重新打开本厂有效计划后再开工");
        }
        if (!"SECONDARY".equals(lockedPlan.getPlanSourceType()) || lockedPlan.getSecondarySourceWipId() == null) {
            return;
        }
        Long wipId = lockedPlan.getSecondarySourceWipId();
        String deduct = deductRef(wipId, lockedPlan.getId());
        boolean alreadyHeld = txnRepository.findBySemiFinishedIdOrderByCreatedAtAsc(wipId).stream()
                .anyMatch(txn -> deduct.equals(txn.getSourceRef()));
        if (alreadyHeld) {
            return;
        }
        wipInventoryService.deductForSecondaryPlan(wipId,
                lockedPlan.getPlannedQuantity() != null ? lockedPlan.getPlannedQuantity() : BigDecimal.ZERO,
                factoryId, operatorId, IntermediateOwnershipGuard.ofPlan(lockedPlan), lockedPlan.getId());
        log.info("SP2 二次加工开工占用: planId={}, wipId={}", lockedPlan.getId(), wipId);
    }

    /** 本计划在来源行上还占着多少 (开工扣下、还没转成消耗或还回去的量)。只认带本计划号的流水。 */
    public BigDecimal heldQuantity(String factoryId, Source source) {
        String planId = source.plan().getId();
        String deduct = deductRef(source.wipId(), planId);
        String reverse = reverseRef(source.wipId(), planId);
        BigDecimal held = BigDecimal.ZERO;
        for (SemiFinishedInventoryTransaction txn : txnRepository.findBySemiFinishedIdOrderByCreatedAtAsc(source.wipId())) {
            if (txn.getQuantity() == null) {
                continue;
            }
            if (deduct.equals(txn.getSourceRef())) {
                held = held.add(txn.getQuantity().abs());
            } else if (reverse.equals(txn.getSourceRef())) {
                held = held.subtract(txn.getQuantity().abs());
            }
        }
        return held.max(BigDecimal.ZERO);
    }

    /**
     * 把占用还回来源行。{@code quantity} 为 null = 全部还回。先锁行再读占用, 并发时不会两边都按旧余额还。
     *
     * @return 实际还回的量; 没有占用时是 0
     */
    public BigDecimal release(String factoryId, Source source, BigDecimal quantity, Long operatorId) {
        lockSource(source);
        BigDecimal held = heldQuantity(factoryId, source);
        BigDecimal take = quantity == null ? held : quantity.min(held);
        if (take.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        wipInventoryService.reverseSecondaryDeduct(source.wipId(), take, factoryId, operatorId, source.plan().getId());
        return take;
    }

    /** 结单: 占用全部还回, 实际投料随后按结单的原路径严格扣减。 */
    public void settleHold(String factoryId, ProductionPlan plan, Long operatorId) {
        Optional<Source> source = forPlan(factoryId, plan);
        if (source.isEmpty()) {
            noteUnresolvedHold(plan, "来源半成品记录已不存在");
            return;
        }
        release(factoryId, source.get(), null, operatorId);
        noteIfHoldUnattributable(source.get());
    }

    /**
     * 不走结单就结束的路径 (取消 / 直接完工 / 批次完工): 还回没用掉的占用。
     * 已提交的逐道投料这些路径不会再扣, 这部分留在占用里就是它的消耗, ⛔ 不能一起还, 否则料用了库存却回来了。
     * 必须在调用方保存计划之前调用, 认不准时要写计划备注。
     *
     * @return 实际还回的量
     */
    public BigDecimal closeHold(String factoryId, ProductionPlan plan, Long operatorId) {
        Optional<Source> source = forPlan(factoryId, plan);
        if (source.isEmpty()) {
            noteUnresolvedHold(plan, "来源半成品记录已不存在");
            return BigDecimal.ZERO;
        }
        planRepository.findByIdForUpdate(plan.getId());   // 与报工同一把锁, 同一顺序
        lockSource(source.get());
        BigDecimal unused = heldQuantity(factoryId, source.get())
                .subtract(rowsFed(factoryId, source.get(), null, null));
        BigDecimal released = unused.signum() <= 0 ? BigDecimal.ZERO
                : release(factoryId, source.get(), unused, operatorId);
        noteIfHoldUnattributable(source.get());
        return released;
    }

    /** 这行流水是上线前的格式: 有开工扣减, 却没有一条带本计划号。认不出是不是本计划占的, 不动账, 记下来给人核对。 */
    private void noteIfHoldUnattributable(Source source) {
        String planId = source.plan().getId();
        String tagged = deductRef(source.wipId(), planId);
        String legacyDeduct = DEDUCT_REF + source.wipId();
        String legacyReverse = REVERSE_REF + source.wipId();
        BigDecimal legacyOpen = BigDecimal.ZERO;
        for (SemiFinishedInventoryTransaction txn : txnRepository.findBySemiFinishedIdOrderByCreatedAtAsc(source.wipId())) {
            if (tagged.equals(txn.getSourceRef())) {
                return;
            }
            if (txn.getQuantity() == null) {
                continue;
            }
            if (legacyDeduct.equals(txn.getSourceRef())) {
                legacyOpen = legacyOpen.add(txn.getQuantity().abs());
            } else if (legacyReverse.equals(txn.getSourceRef())) {
                legacyOpen = legacyOpen.subtract(txn.getQuantity().abs());
            }
        }
        if (legacyOpen.signum() > 0) {
            noteUnresolvedHold(source.plan(), "来源 " + source.batchNo() + " 上有 " + plain(legacyOpen)
                    + " 的旧格式开工扣减认不出属于哪个计划");
        }
    }

    private void noteUnresolvedHold(ProductionPlan plan, String reason) {
        String note = "【待人工核对】" + reason + ", 系统没有自动还回, 请核对来源半成品库存后用半成品盘点校正";
        plan.setNotes(plan.getNotes() == null ? note : plan.getNotes() + "\n" + note);
        log.warn("[secondary-hold] UNRESOLVED planId={} wipId={} reason={}",
                plan.getId(), plan.getSecondarySourceWipId(), reason);
    }

    /**
     * App 报工领来源半成品: 锁住计划和来源, 确认计划还开着、没超量,
     * 返回这次领用里占用能覆盖的量 (这部分不用再看库存余额)。不是这个计划的来源 ⇒ 0, 按普通领用处理。
     */
    public BigDecimal coveredByHold(String factoryId, Long productionBatchId, String sourceWipNo,
                                    BigDecimal quantity, Long excludeReportId) {
        if (quantity == null) {
            return BigDecimal.ZERO;
        }
        Optional<Source> source = forBatch(factoryId, productionBatchId)
                .filter(candidate -> candidate.batchNo().equals(sourceWipNo));
        if (source.isEmpty()) {
            return BigDecimal.ZERO;
        }
        lockPlanAndSource(factoryId, source.get());
        assertWithinPlan(source.get(), fedQuantity(factoryId, source.get(), null, null, excludeReportId), quantity);
        return heldQuantity(factoryId, source.get()).min(quantity);
    }

    /** 逐道录入投来源半成品: 同一行重存时先扣掉它自己之前的量。 */
    public void assertRowWithinPlan(String factoryId, String planId, ProcessSheetRowRequest req) {
        Optional<Source> source = forPlanId(factoryId, planId);
        if (source.isEmpty() || req.getUpstreamSources() == null) {
            return;
        }
        BigDecimal thisRow = rowFeed(factoryId, source.get(), req);
        if (thisRow.signum() <= 0) {
            return;
        }
        lockPlanAndSource(factoryId, source.get());
        assertWithinPlan(source.get(),
                fedQuantity(factoryId, source.get(), req.getProcessCode(), req.getClientRowId(), null), thisRow);
    }

    /**
     * 投料下拉要显示的可领量: 计划还能投多少, 且不超过真实库存 (本计划占着的 + 来源现在能领的)。
     * 只给读接口用: 这里读了实体, 不能和扣账放在同一事务。
     */
    public BigDecimal remainingForPlan(String factoryId, Source source) {
        BigDecimal planLeft = nz(source.plannedQuantity()).subtract(fedQuantity(factoryId, source, null, null, null));
        BigDecimal drawable = wipRepository.findById(source.wipId())
                .map(SemiFinishedInventory::drawableBalance)
                .orElse(BigDecimal.ZERO);
        return planLeft.min(heldQuantity(factoryId, source).add(drawable)).max(BigDecimal.ZERO);
    }

    /** 先计划后来源, 和取消 / 结单同一顺序。拿到计划锁之后读状态, 读的是别人提交之后的值。 */
    private void lockPlanAndSource(String factoryId, Source source) {
        String planId = source.plan().getId();
        planRepository.findByIdForUpdate(planId);
        Object status = planRepository.findPlanStatusesByFactoryIdAndIdIn(factoryId, List.of(planId)).stream()
                .filter(tuple -> tuple != null && tuple.length > 1)
                .map(tuple -> tuple[1])
                .findFirst()
                .orElse(null);
        if (status != ProductionPlanStatus.IN_PROGRESS && status != ProductionPlanStatus.PAUSED) {
            throw new BusinessException(409, "返工计划已结束, 不能再投来源半成品")
                    .withCode("SECONDARY_PLAN_NOT_ACTIVE")
                    .withHint("请刷新生产计划列表查看最新状态")
                    .withHintTarget("productionPlan");
        }
        lockSource(source);
    }

    private void lockSource(Source source) {
        SemiFinishedInventory locked = wipRepository.findByIdForUpdate(source.wipId())
                .orElseThrow(() -> new BusinessException(409, "来源半成品记录已不存在: " + source.batchNo())
                        .withCode("SECONDARY_SOURCE_NOT_FOUND")
                        .withHint("请联系主管核对该批半成品")
                        .withHintTarget("wipId"));
        if (entityManager != null) {
            entityManager.refresh(locked);   // 同一事务先前读过这行时, 锁查询返回的是旧值; 查询本身已先 flush
        }
    }

    private void assertWithinPlan(Source source, BigDecimal alreadyFed, BigDecimal thisTime) {
        BigDecimal planned = nz(source.plannedQuantity());
        if (alreadyFed.add(thisTime).compareTo(planned) > 0) {
            String shown = source.unit() == null ? ""
                    : com.cretas.aims.service.unit.UnitDisplayNames.display(source.unit());
            throw new BusinessException(409, "来源半成品超量: 本计划来源 " + plain(planned) + shown
                    + ", 已报 " + plain(alreadyFed) + shown + ", 本次 " + plain(thisTime) + shown)
                    .withCode("SECONDARY_SOURCE_OVER_PLAN")
                    .withHint("最多还能投 " + plain(planned.subtract(alreadyFed).max(BigDecimal.ZERO)) + shown
                            + ", 请改小本次投入量")
                    .withHintTarget("sourceWipQuantity");
        }
    }

    /** 本计划已经报到这份来源上的量 (逐道录入 + App 报工; 撤回 / 驳回的不算)。 */
    private BigDecimal fedQuantity(String factoryId, Source source, String excludeProcessCode,
                                   String excludeClientRowId, Long excludeReportId) {
        String planId = source.plan().getId();
        BigDecimal fed = rowsFed(factoryId, source, excludeProcessCode, excludeClientRowId);
        for (ProductionBatch batch : batchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId)) {
            for (ProductionReport report : reportRepository.findYieldReportsByBatch(factoryId, batch.getId())) {
                if (source.batchNo().equals(report.getSourceWipNo())
                        && !Objects.equals(excludeReportId, report.getId())
                        && !"REJECTED".equals(report.getApprovalStatus())) {
                    fed = fed.add(reportFeed(report));
                }
            }
        }
        return fed;
    }

    /** 已提交的逐道行投到这份来源上的量。这部分要到结单才真正扣减。 */
    private BigDecimal rowsFed(String factoryId, Source source, String excludeProcessCode, String excludeClientRowId) {
        BigDecimal fed = BigDecimal.ZERO;
        for (ProcessSheetRow row : rowRepository.findByFactoryIdAndPlanId(factoryId, source.plan().getId())) {
            if (ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus())
                    || (excludeClientRowId != null && excludeClientRowId.equals(row.getClientRowId())
                            && Objects.equals(excludeProcessCode, row.getProcessCode()))) {
                continue;
            }
            fed = fed.add(rowFeed(factoryId, source, parse(row)));
        }
        return fed;
    }

    private BigDecimal rowFeed(String factoryId, Source source, ProcessSheetRowRequest req) {
        BigDecimal fed = BigDecimal.ZERO;
        if (req.getUpstreamSources() == null) {
            return fed;
        }
        for (ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
            if (ref.isSemiFinished() && !ref.isStockConsumedAtSubmit()
                    && source.batchNo().equals(ref.getSourceBatchNumber())
                    && ref.getFeedQuantityKg() != null && ref.getFeedQuantityKg().signum() > 0) {
                BigDecimal inSourceUnit = wipInventoryService.resolveSemiFeedQtyInSourceUnit(
                        factoryId, source.batchNo(), ref.getFeedQuantityKg());
                if (inSourceUnit == null) {
                    throw new BusinessException(409, "半成品批次 " + source.batchNo() + " 无法折算投料量, 不能判断是否超量")
                            .withCode("SFI_NO_GRAMS_PER_UNIT")
                            .withHint("请先在产品配置为该半成品设置标准克重(每盒克重), 再重试投料")
                            .withHintTarget(source.batchNo());
                }
                fed = fed.add(inSourceUnit);
            }
        }
        return fed;
    }

    private static BigDecimal reportFeed(ProductionReport report) {
        Object recorded = report.getCustomFields() == null ? null : report.getCustomFields().get("sourceWipQuantity");
        if (recorded != null) {
            return new BigDecimal(String.valueOf(recorded));
        }
        return nz(report.getInputQuantity());
    }

    private ProcessSheetRowRequest parse(ProcessSheetRow row) {
        try {
            return objectMapper.readValue(row.getRowPayload(), ProcessSheetRowRequest.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(409, "逐道报工行内容无法解析, 不能判断来源半成品用量")
                    .withCode("PROCESS_SHEET_PAYLOAD_INVALID")
                    .withHint("请联系管理员核对该报工行后再提交")
                    .withHintTarget("processSheetRows");
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
