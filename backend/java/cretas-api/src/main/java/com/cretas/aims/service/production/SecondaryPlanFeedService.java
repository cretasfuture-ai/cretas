package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
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
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * 占用还剩多少看流水 ({@link #heldQuantity}), ⛔ 不按计划量推算: /create-batch 开工的计划根本没有占用。
 */
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
        return wipRepository.findById(plan.getSecondarySourceWipId())
                .filter(wip -> factoryId.equals(wip.getFactoryId()))
                .map(wip -> toSource(plan, wip));
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

    private static Source toSource(ProductionPlan plan, SemiFinishedInventory wip) {
        return new Source(plan, wip.getId(), wip.getIntermediateBatchNo(), wip.getProductTypeId(),
                plan.getPlannedQuantity(), wip.getUnit());
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

    /** 本计划在来源行上还占着多少 (开工扣下、还没转成消耗或还回去的量)。 */
    public BigDecimal heldQuantity(String factoryId, Source source) {
        String planId = source.plan().getId();
        String deduct = deductRef(source.wipId(), planId);
        String reverse = reverseRef(source.wipId(), planId);
        List<SemiFinishedInventoryTransaction> ledger = txnRepository
                .findBySemiFinishedIdOrderByCreatedAtAsc(source.wipId());
        BigDecimal held = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        for (SemiFinishedInventoryTransaction txn : ledger) {
            if (deduct.equals(txn.getSourceRef()) && txn.getQuantity() != null) {
                held = held.add(txn.getQuantity().abs());
            } else if (reverse.equals(txn.getSourceRef()) && txn.getQuantity() != null) {
                returned = returned.add(txn.getQuantity().abs());
            }
        }
        if (held.signum() == 0) {
            held = heldBeforeRefCarriedPlanId(source, ledger);
        }
        return held.subtract(returned).max(BigDecimal.ZERO);
    }

    /**
     * 流水号带上计划 id 之前开工的计划: 流水认不出是谁扣的, 按「创建到开工之间、量等于计划量」认领。
     * 新流水都带计划 id, 这条只对上线前已开工、还没结束的计划起作用。
     */
    private BigDecimal heldBeforeRefCarriedPlanId(Source source, List<SemiFinishedInventoryTransaction> ledger) {
        ProductionPlan plan = source.plan();
        LocalDateTime from = plan.getCreatedAt();
        LocalDateTime to = plan.getStartTime();
        if (from == null || to == null || source.plannedQuantity() == null) {
            return BigDecimal.ZERO;
        }
        String legacyRef = DEDUCT_REF + source.wipId();
        boolean found = ledger.stream().anyMatch(txn -> legacyRef.equals(txn.getSourceRef())
                && txn.getQuantity() != null && txn.getCreatedAt() != null
                && txn.getQuantity().abs().compareTo(source.plannedQuantity()) == 0
                && !txn.getCreatedAt().isBefore(from) && !txn.getCreatedAt().isAfter(to));
        return found ? source.plannedQuantity() : BigDecimal.ZERO;
    }

    /**
     * 把占用还回来源行。{@code quantity} 为 null = 全部还回 (结单 / 取消)。
     *
     * @return 实际还回的量; 没有占用时是 0
     */
    public BigDecimal release(String factoryId, Source source, BigDecimal quantity, Long operatorId) {
        BigDecimal held = heldQuantity(factoryId, source);
        BigDecimal take = quantity == null ? held : quantity.min(held);
        if (take.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        wipInventoryService.reverseSecondaryDeduct(source.wipId(), take, factoryId, operatorId, source.plan().getId());
        return take;
    }

    /**
     * App 报工领来源半成品: 查超量, 返回这次领用里占用能覆盖的量 (这部分不用再看库存余额)。
     * 不是这个计划的来源 ⇒ 0, 按普通领用处理。
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
        assertWithinPlan(source.get(),
                fedQuantity(factoryId, source.get(), req.getProcessCode(), req.getClientRowId(), null), thisRow);
    }

    /** 投料下拉要显示的可领量: 这个计划还能投多少 (计划量 − 已报)。 */
    public BigDecimal remainingForPlan(String factoryId, Source source) {
        return nz(source.plannedQuantity()).subtract(fedQuantity(factoryId, source, null, null, null))
                .max(BigDecimal.ZERO);
    }

    private void assertWithinPlan(Source source, BigDecimal alreadyFed, BigDecimal thisTime) {
        BigDecimal planned = nz(source.plannedQuantity());
        if (alreadyFed.add(thisTime).compareTo(planned) > 0) {
            String unit = source.unit() == null ? "" : source.unit();
            throw new BusinessException(409, "来源半成品超量: 本计划来源 " + plain(planned) + unit
                    + ", 已报 " + plain(alreadyFed) + unit + ", 本次 " + plain(thisTime) + unit)
                    .withCode("SECONDARY_SOURCE_OVER_PLAN")
                    .withHint("最多还能投 " + plain(planned.subtract(alreadyFed).max(BigDecimal.ZERO)) + unit
                            + ", 请改小本次投入量")
                    .withHintTarget("sourceWipQuantity");
        }
    }

    /** 本计划已经报到这份来源上的量 (逐道录入 + App 报工; 撤回 / 驳回的不算)。 */
    private BigDecimal fedQuantity(String factoryId, Source source, String excludeProcessCode,
                                   String excludeClientRowId, Long excludeReportId) {
        String planId = source.plan().getId();
        BigDecimal fed = BigDecimal.ZERO;
        for (ProcessSheetRow row : rowRepository.findByFactoryIdAndPlanId(factoryId, planId)) {
            if (ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus())
                    || (excludeClientRowId != null && excludeClientRowId.equals(row.getClientRowId())
                            && Objects.equals(excludeProcessCode, row.getProcessCode()))) {
                continue;
            }
            fed = fed.add(rowFeed(factoryId, source, parse(row)));
        }
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
