package com.cretas.aims.service.outsource;

import com.cretas.aims.dto.outsource.OutsourceDtos.*;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.QualityReturnOrder;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.QualityReturnTargetType;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.outsource.OutsourceMovement;
import com.cretas.aims.entity.outsource.OutsourceOrder;
import com.cretas.aims.entity.outsource.OutsourceOrderLine;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.outsource.OutsourceMovementRepository;
import com.cretas.aims.repository.outsource.OutsourceOrderLineRepository;
import com.cretas.aims.repository.outsource.OutsourceOrderRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.quality.release.MaterialQcRejectionGuard;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 原生委外闭环: 委外单 (按委外厂) + 任务行 (工序委外 / 整单委外) + 收发明细。
 *
 * <p>设计与口径 D1–D13 见 {@code docs/specs/outsource-closed-loop-design.md}。要点:
 * <ul>
 *   <li>发料 = 搬仓到该委外厂专属 OUTSOURCE 仓, 委外仓批次状态 RESERVED (D13: 不被任何可用量 / FEFO 查询选中);
 *       ⛔ 不走调拨单 (会二次扣减)。</li>
 *   <li>退料 = 发料的精确逆操作, 回到原批次。</li>
 *   <li>收货 = 耗用委外仓批次 (MaterialConsumption OUTSOURCE_RECEIVE, ⛔ 不填 productionPlanId: 成本随产出单价传递)
 *       + 建产出批次 + 写 OUTSOURCE_PROCESS 血缘 + 合格数 × 单价挂应付。</li>
 *   <li>退货 = 扣收货批次, 转为返工待收, 加工费按待审 AP 调整冲减 (四眼); 返工回货不再耗料。</li>
 *   <li>结单 = 委外厂结存必须为 0 且无返工待收 (D8: 用退料 / 损耗对平, ⛔ 不静默吞差额)。</li>
 * </ul>
 * 所有写操作先锁委外单 ({@code findByIdAndFactoryIdForUpdate}), 同一张单的动作串行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutsourceService {

    public static final String DOC_ISSUE = "OUTSOURCE_ISSUE";
    public static final String DOC_RECEIVE = "OUTSOURCE_RECEIVE";
    public static final String SOURCE_RECEIVE = "OUTSOURCE_RECEIVE";
    public static final String SOURCE_LOSS = "OUTSOURCE_LOSS";
    public static final String AP_SOURCE_RECEIVE = "OUTSOURCE_RECEIVE";
    public static final String AP_SOURCE_GOODS_RETURN = "OUTSOURCE_GOODS_RETURN";
    /** CONSUME 明细里标记「返工回货抵扣」的 reason (不耗料, 源是被退回的收货批次)。 */
    public static final String REWORK_MARK = "REWORK";
    static final int PAYABLE_DUE_DAYS = 30;
    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final OutsourceOrderRepository orderRepository;
    private final OutsourceOrderLineRepository lineRepository;
    private final OutsourceMovementRepository movementRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final FactoryWarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WarehouseResolver warehouseResolver;
    private final QcInspectionPolicy qcInspectionPolicy;
    private final ArApService arApService;
    private final ArApTransactionRepository arApTransactionRepository;
    private final LineageEdgeRecorder lineageEdgeRecorder;

    @Autowired(required = false)
    private QcReleaseRecordRepository qcReleaseRecordRepository;
    @Autowired(required = false)
    private ProcessTimeLimitEventRepository processTimeLimitEventRepository;

    // ==================== 开单 / 下达 / 取消 ====================

    @Transactional
    public OrderDetail create(String factoryId, CreateOrderRequest req, Long userId) {
        requireActor(userId);
        if (req == null || isBlank(req.supplierId())) {
            throw bad(400, "请选择委外厂", "OUTSOURCE_SUPPLIER_REQUIRED", "supplierId");
        }
        Supplier supplier = supplierRepository.findByIdAndFactoryId(req.supplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外厂(供应商)不存在或不属于当前工厂"));
        if (req.lines() == null || req.lines().isEmpty()) {
            throw bad(400, "委外单至少要有一行任务", "OUTSOURCE_LINES_REQUIRED", "lines");
        }
        OutsourceOrder order = new OutsourceOrder();
        order.setId(UUID.randomUUID().toString());
        order.setFactoryId(factoryId);
        order.setOrderNo(nextOrderNo(factoryId));
        order.setSupplierId(supplier.getId());
        order.setStatus(OutsourceOrder.Status.DRAFT);
        order.setExpectedReturnDate(req.expectedReturnDate());
        order.setRemark(trimToNull(req.remark()));
        order.setCreatedBy(userId);
        orderRepository.save(order);

        Set<Long> seenTasks = new HashSet<>();
        int lineNo = 1;
        for (CreateLineRequest lr : req.lines()) {
            OutsourceOrderLine line = buildLine(factoryId, order, lr, lineNo++);
            if (line.getWorkProcessTaskId() != null && !seenTasks.add(line.getWorkProcessTaskId())) {
                throw bad(409, "同一道工序在本单里出现了两次", "OUTSOURCE_TASK_DUPLICATED", "lines");
            }
            lineRepository.save(line);
        }
        log.info("委外单创建: factory={} no={} supplier={} lines={}", factoryId, order.getOrderNo(),
                supplier.getId(), req.lines().size());
        return detail(factoryId, order.getId());
    }

    private OutsourceOrderLine buildLine(String factoryId, OutsourceOrder order, CreateLineRequest lr, int lineNo) {
        if (lr == null) {
            throw bad(400, "任务行不能为空", "OUTSOURCE_LINE_INVALID", "lines");
        }
        OutsourceOrderLine.OutsourceType type = parseEnum(OutsourceOrderLine.OutsourceType.class,
                lr.outsourceType(), "outsourceType", "委外类型只能是 PROCESS(工序委外) 或 WHOLE(整单委外)");
        OutsourceOrderLine.OutputKind kind = parseEnum(OutsourceOrderLine.OutputKind.class,
                lr.outputKind(), "outputKind", "产出形态只能是 FINISHED(成品) 或 SEMI(半成品)");
        if (lr.plannedQuantity() == null || lr.plannedQuantity().signum() <= 0) {
            throw bad(400, "计划数量必须大于 0", "OUTSOURCE_LINE_QTY_INVALID", "plannedQuantity");
        }
        if (lr.unitFee() == null || lr.unitFee().signum() < 0) {
            throw bad(400, "加工费单价不能为空或负数", "OUTSOURCE_LINE_FEE_INVALID", "unitFee");
        }
        OutsourceOrderLine line = new OutsourceOrderLine();
        line.setId(UUID.randomUUID().toString());
        line.setFactoryId(factoryId);
        line.setOrderId(order.getId());
        line.setLineNo(lineNo);
        line.setOutsourceType(type);
        line.setOutputKind(kind);
        line.setPlannedQuantity(lr.plannedQuantity());
        line.setUnitFee(lr.unitFee());
        line.setTaxRate(lr.taxRate());
        line.setStatus(OutsourceOrderLine.Status.OPEN);

        String productTypeId = trimToNull(lr.productTypeId());
        if (type == OutsourceOrderLine.OutsourceType.PROCESS) {
            if (isBlank(lr.productionPlanId()) || lr.workProcessTaskId() == null) {
                throw bad(400, "工序委外必须选工单和工序", "OUTSOURCE_PROCESS_LINK_REQUIRED", "workProcessTaskId");
            }
            ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(lr.productionPlanId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("工单不存在或不属于当前工厂"));
            WorkProcessTask task = taskRepository.findById(lr.workProcessTaskId())
                    .filter(t -> factoryId.equals(t.getFactoryId()))
                    .orElseThrow(() -> new ResourceNotFoundException("工序任务不存在或不属于当前工厂"));
            ProductionBatch pb = productionBatchRepository.findByIdAndFactoryId(task.getProductionBatchId(), factoryId)
                    .orElse(null);
            if (pb != null && pb.getProductionPlanId() != null && !pb.getProductionPlanId().equals(plan.getId())) {
                throw bad(409, "所选工序不属于这个工单", "OUTSOURCE_TASK_PLAN_MISMATCH", "workProcessTaskId");
            }
            assertTaskOutsourceable(task, null);
            line.setProductionPlanId(plan.getId());
            line.setProductionBatchId(task.getProductionBatchId());
            line.setWorkProcessTaskId(task.getId());
            line.setProcessName(task.getWorkProcessId());
            if (productTypeId == null) {
                productTypeId = task.getProductTypeId();
            }
        } else if (!isBlank(lr.productionPlanId())) {
            // 整单委外可以只挂工单 (不挂工序), 便于订单成本归集。
            ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(lr.productionPlanId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("工单不存在或不属于当前工厂"));
            line.setProductionPlanId(plan.getId());
        }
        if (productTypeId == null) {
            throw bad(400, "请选择委外产出的产品", "OUTSOURCE_OUTPUT_REQUIRED", "productTypeId");
        }
        ProductType pt = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("产出产品不存在或不属于当前工厂"));
        line.setProductTypeId(pt.getId());
        line.setOutputName(pt.getName());
        String unit = trimToNull(lr.unit()) != null ? lr.unit().trim() : pt.getUnit();
        if (isBlank(unit)) {
            throw bad(400, "请填写产出单位", "OUTSOURCE_UNIT_REQUIRED", "unit");
        }
        line.setUnit(unit);
        return line;
    }

    private void assertTaskOutsourceable(WorkProcessTask task, String ownLineId) {
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            throw bad(409, "这道工序已经结束(" + task.getStatus() + "), 不能再委外", "OUTSOURCE_TASK_TERMINAL",
                    "workProcessTaskId");
        }
        if (Boolean.TRUE.equals(task.getIsOutsourced()) && task.getOutsourceLineId() != null
                && !task.getOutsourceLineId().equals(ownLineId)) {
            throw bad(409, "这道工序已在另一张委外单里委外", "OUTSOURCE_TASK_ALREADY_OUTSOURCED", "workProcessTaskId");
        }
    }

    /** 下达: 解析 / 新建委外厂专属 OUTSOURCE 仓 (D2), 工序委外行同步标记工序任务 (D6)。 */
    @Transactional
    public OrderDetail release(String factoryId, String orderId, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (order.getStatus() == OutsourceOrder.Status.RELEASED) {
            return detail(factoryId, orderId);   // 幂等: 重复下达直接回读
        }
        if (order.getStatus() != OutsourceOrder.Status.DRAFT) {
            throw bad(409, "只有草稿可以下达, 当前状态: " + order.getStatus(), "OUTSOURCE_ORDER_NOT_DRAFT", null);
        }
        FactoryWarehouse wh = resolveOutsourceWarehouse(factoryId, order.getSupplierId());
        order.setOutsourceWarehouseId(wh.getId());
        for (OutsourceOrderLine line : lineRepository.findByOrderIdOrderByLineNoAsc(order.getId())) {
            if (line.getWorkProcessTaskId() == null) {
                continue;
            }
            WorkProcessTask task = taskRepository.findByFactoryIdAndIdForUpdate(factoryId, line.getWorkProcessTaskId())
                    .orElseThrow(() -> new ResourceNotFoundException("工序任务不存在: " + line.getWorkProcessTaskId()));
            assertTaskOutsourceable(task, line.getId());
            task.setIsOutsourced(Boolean.TRUE);
            task.setOutsourceLineId(line.getId());
            task.setOutsourcerSupplierId(order.getSupplierId());
            task.setOutsourceUnitFee(line.getUnitFee());
            task.setOutsourceFeeAmount(effectiveFee(factoryId, line));
            taskRepository.save(task);
        }
        order.setStatus(OutsourceOrder.Status.RELEASED);
        order.setReleasedAt(LocalDateTime.now());
        order.setReleasedBy(userId);
        orderRepository.save(order);
        return detail(factoryId, orderId);
    }

    @Transactional
    public OrderDetail cancel(String factoryId, String orderId, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (order.getStatus() != OutsourceOrder.Status.DRAFT) {
            throw bad(409, "只有草稿可以取消; 已下达的单请退料 / 登记损耗后结单", "OUTSOURCE_ORDER_NOT_DRAFT", null);
        }
        order.setStatus(OutsourceOrder.Status.CANCELLED);
        for (OutsourceOrderLine line : lineRepository.findByOrderIdOrderByLineNoAsc(order.getId())) {
            line.setStatus(OutsourceOrderLine.Status.CANCELLED);
            lineRepository.save(line);
        }
        orderRepository.save(order);
        return detail(factoryId, orderId);
    }

    FactoryWarehouse resolveOutsourceWarehouse(String factoryId, String supplierId) {
        Optional<FactoryWarehouse> existing =
                warehouseRepository.findByFactoryIdAndOutsourcerSupplierIdAndDeletedAtIsNull(factoryId, supplierId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外厂(供应商)不存在"));
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setFactoryId(factoryId);
        wh.setCode(uniqueWarehouseCode(factoryId, supplier));
        String name = "委外仓-" + (supplier.getName() != null ? supplier.getName() : supplier.getId());
        wh.setName(name.length() > 100 ? name.substring(0, 100) : name);
        wh.setType(FactoryWarehouse.WarehouseType.OUTSOURCE);
        wh.setIsActive(Boolean.TRUE);
        wh.setOutsourcerSupplierId(supplierId);
        wh.setRemarks("委外厂专属委外仓, 由委外单下达时自动建立; 批次数量只由委外收发改动");
        return warehouseRepository.save(wh);
    }

    private String uniqueWarehouseCode(String factoryId, Supplier supplier) {
        String base = supplier.getCode() != null ? supplier.getCode()
                : (supplier.getSupplierCode() != null ? supplier.getSupplierCode() : null);
        String candidate = base != null ? "WH-OS-" + base.replaceAll("[^A-Za-z0-9_-]", "") : null;
        if (candidate == null || candidate.length() > 32 || candidate.equals("WH-OS-")
                || warehouseRepository.findByFactoryIdAndCodeAndDeletedAtIsNull(factoryId, candidate).isPresent()) {
            candidate = "WH-OS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        return candidate;
    }

    private String nextOrderNo(String factoryId) {
        String prefix = "OS" + LocalDate.now().format(DATE_KEY);
        long n = orderRepository.countByFactoryIdAndOrderNoStartingWith(factoryId, prefix);
        String no;
        do {
            n++;
            no = prefix + String.format("%04d", n);
        } while (orderRepository.existsByFactoryIdAndOrderNo(factoryId, no));
        return no;
    }

    // ==================== 发料 / 退料 ====================

    /** 发料 (D3): 我方仓批次划出 → 委外仓新批次 (RESERVED, D13), 所有权 / 单价 / 效期照抄。 */
    @Transactional
    public OrderDetail issue(String factoryId, String orderId, String lineId, IssueRequest req, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (replayed(factoryId, orderId, lineId, req == null ? null : req.clientRequestId(),
                OutsourceMovement.Type.ISSUE)) {
            return detail(factoryId, orderId);
        }
        requireReleased(order);
        OutsourceOrderLine line = requireOpenLine(order, lineId);
        List<BatchQuantity> items = requireItems(req == null ? null : req.items());
        LocalDate bizDate = bizDate(req.bizDate());
        String requestId = trimToNull(req.clientRequestId());
        for (BatchQuantity it : items) {
            MaterialBatch src = materialBatchRepository.findByIdAndFactoryIdForUpdate(it.batchId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("批次不存在或不属于当前工厂: " + it.batchId()));
            assertIssuable(src);
            BigDecimal q = it.quantity();
            if (src.getCurrentQuantity().compareTo(q) < 0) {
                throw bad(409, String.format("批次 %s 结存 %s, 不够发出 %s", src.getBatchNumber(),
                        plain(src.getCurrentQuantity()), plain(q)), "OUTSOURCE_ISSUE_INSUFFICIENT_STOCK", it.batchId());
            }
            src.setUsedQuantity(nz(src.getUsedQuantity()).add(q));
            if (src.getCurrentQuantity().signum() <= 0) {
                src.setStatus(MaterialBatchStatus.whenExhausted(src.getStatus(), MaterialBatchStatus.DEPLETED));
            }
            materialBatchRepository.save(src);

            MaterialBatch os = new MaterialBatch();
            os.setId(UUID.randomUUID().toString());
            os.setFactoryId(factoryId);
            os.setBatchNumber(batchNumber(src.getBatchNumber(), "OS"));
            os.setMaterialTypeId(src.getMaterialTypeId());
            os.setProductTypeId(src.getProductTypeId());
            os.setSupplierId(src.getSupplierId());
            os.setReceiptQuantity(q);
            os.setUsedQuantity(ZERO);
            os.setReservedQuantity(ZERO);
            os.setQuantityUnit(src.getQuantityUnit());
            os.setUnitPrice(src.getUnitPrice());
            os.setReceiptDate(bizDate);
            os.setProductionDate(src.getProductionDate());
            os.setExpireDate(src.getExpireDate());
            os.setOwnership(src.getOwnership());
            os.setOwnerCustomerId(src.getOwnerCustomerId());
            os.setSourceSalesOrderId(src.getSourceSalesOrderId());
            os.setSourceSalesOrderItemId(src.getSourceSalesOrderItemId());
            os.setWarehouseId(order.getOutsourceWarehouseId());
            // D13: 委外厂手里的料 ⛔ 不能是 AVAILABLE —— 8 处不带仓库过滤的可用量 / FEFO 查询会把它算成可领。
            os.setStatus(MaterialBatchStatus.RESERVED);
            os.setCreatedBy(userId);
            os.setSourceDocType(DOC_ISSUE);
            os.setSourceDocId(line.getId());
            materialBatchRepository.save(os);

            OutsourceMovement mv = movement(order, line, OutsourceMovement.Type.ISSUE, bizDate, userId);
            mv.setMaterialTypeId(src.getMaterialTypeId());
            mv.setProductTypeId(src.getProductTypeId());
            mv.setSourceBatchId(src.getId());
            mv.setTargetBatchId(os.getId());
            mv.setTargetBatchKind(OutsourceMovement.BatchKind.MATERIAL);
            mv.setQuantity(q);
            mv.setUnit(src.getQuantityUnit());
            mv.setUnitCost(src.getUnitPrice());
            mv.setAmount(src.getUnitPrice() == null ? null : money(src.getUnitPrice().multiply(q)));
            mv.setClientRequestId(requestId);
            requestId = null;   // 一次提交多批次: 防重复键只落在第一条上
            saveRequestMovement(mv);
            lineageEdgeRecorder.recordMaterialTransfer(factoryId, src.getId(), os.getId(), q, src.getQuantityUnit(),
                    userId, "outsource-issue:" + mv.getId(), Map.of("origin", "OUTSOURCE_ISSUE",
                            "outsourceOrderNo", order.getOrderNo()));
        }
        touchTaskStarted(factoryId, line);
        return detail(factoryId, orderId);
    }

    private void assertIssuable(MaterialBatch src) {
        MaterialBatchStatus st = src.getStatus();
        if (st != null && st.blocksProduction()) {
            throw bad(409, String.format("批次 %s %s", src.getBatchNumber(), st.productionBlockedReason()),
                    "OUTSOURCE_ISSUE_BATCH_NOT_USABLE", src.getId());
        }
        if (st == MaterialBatchStatus.RESERVED || st == MaterialBatchStatus.PRODUCING_RESERVED
                || DOC_ISSUE.equals(src.getSourceDocType())) {
            throw bad(409, String.format("批次 %s 已被占用或在委外厂, 不能发出", src.getBatchNumber()),
                    "OUTSOURCE_ISSUE_BATCH_NOT_USABLE", src.getId());
        }
    }

    /** 退料: 委外仓批次 → 它的原批次 (发料的精确逆操作)。 */
    @Transactional
    public OrderDetail returnMaterial(String factoryId, String orderId, String lineId,
                                      MaterialReturnRequest req, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (replayed(factoryId, orderId, lineId, req == null ? null : req.clientRequestId(),
                OutsourceMovement.Type.MATERIAL_RETURN)) {
            return detail(factoryId, orderId);
        }
        requireReleased(order);
        OutsourceOrderLine line = requireLine(order, lineId);
        List<BatchQuantity> items = requireItems(req == null ? null : req.items());
        LocalDate bizDate = bizDate(req.bizDate());
        String requestId = trimToNull(req.clientRequestId());
        for (BatchQuantity it : items) {
            MaterialBatch os = lockOutsourceBatch(factoryId, line, it.batchId());
            BigDecimal q = it.quantity();
            assertWithinBalance(os, q, "退料");
            OutsourceMovement issue = movementRepository.findByLineIdOrderByCreatedAtAsc(line.getId()).stream()
                    .filter(m -> m.getMovementType() == OutsourceMovement.Type.ISSUE && os.getId().equals(m.getTargetBatchId()))
                    .findFirst()
                    .orElseThrow(() -> bad(409, "找不到这个委外仓批次的发料记录", "OUTSOURCE_ISSUE_RECORD_MISSING", os.getId()));
            MaterialBatch origin = materialBatchRepository.findByIdAndFactoryIdForUpdate(issue.getSourceBatchId(), factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("原批次不存在: " + issue.getSourceBatchId()));
            consumeOutsourceBatch(os, q);
            BigDecimal used = nz(origin.getUsedQuantity());
            if (used.compareTo(q) < 0) {
                throw bad(409, String.format("原批次 %s 已用量 %s 小于退回量 %s", origin.getBatchNumber(),
                        plain(used), plain(q)), "OUTSOURCE_RETURN_UNDERFLOW", origin.getId());
            }
            origin.setUsedQuantity(used.subtract(q));
            if ((origin.getStatus() == MaterialBatchStatus.DEPLETED || origin.getStatus() == MaterialBatchStatus.USED_UP)
                    && origin.getCurrentQuantity().signum() > 0) {
                origin.setStatus(qcReleaseRecordRepository != null && processTimeLimitEventRepository != null
                        ? MaterialQcRejectionGuard.statusWhenRestored(qcReleaseRecordRepository,
                                processTimeLimitEventRepository, origin)
                        : MaterialBatchStatus.AVAILABLE);
            }
            materialBatchRepository.save(origin);

            OutsourceMovement mv = movement(order, line, OutsourceMovement.Type.MATERIAL_RETURN, bizDate, userId);
            mv.setMaterialTypeId(os.getMaterialTypeId());
            mv.setProductTypeId(os.getProductTypeId());
            mv.setSourceBatchId(os.getId());
            mv.setTargetBatchId(origin.getId());
            mv.setTargetBatchKind(OutsourceMovement.BatchKind.MATERIAL);
            mv.setQuantity(q);
            mv.setUnit(os.getQuantityUnit());
            mv.setUnitCost(os.getUnitPrice());
            mv.setAmount(os.getUnitPrice() == null ? null : money(os.getUnitPrice().multiply(q)));
            mv.setParentMovementId(issue.getId());
            mv.setClientRequestId(requestId);
            requestId = null;
            saveRequestMovement(mv);
        }
        return detail(factoryId, orderId);
    }

    // ==================== 收货 ====================

    /**
     * 收货建议: 先用返工待收抵扣 (不耗料), 其余按「本次新收 / 剩余应收」比例 (封顶 1) 建议各委外仓批次的耗用量。
     */
    @Transactional(readOnly = true)
    public ReceiveSuggestion suggestReceive(String factoryId, String orderId, String lineId,
                                            BigDecimal goodQuantity, BigDecimal defectQuantity) {
        OutsourceOrder order = orderRepository.findByIdAndFactoryId(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外单不存在或不属于当前工厂"));
        OutsourceOrderLine line = requireLine(order, lineId);
        BigDecimal g = nz(goodQuantity);
        BigDecimal d = nz(defectQuantity);
        BigDecimal rework = g.min(nz(line.getReworkPendingQuantity()));
        BigDecimal newPart = g.subtract(rework).add(d);
        BigDecimal remaining = line.remainingToReceive();
        BigDecimal ratio = newPart.signum() <= 0 ? ZERO
                : (remaining.signum() <= 0 ? BigDecimal.ONE
                        : newPart.divide(remaining, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE));
        List<BatchSuggestion> out = new ArrayList<>();
        for (MaterialBatch b : outsourceBatches(factoryId, line.getId())) {
            BigDecimal cur = b.getCurrentQuantity();
            if (cur.signum() <= 0) {
                continue;
            }
            BigDecimal suggested = ratio.compareTo(BigDecimal.ONE) == 0 ? cur
                    : cur.multiply(ratio).setScale(6, RoundingMode.HALF_UP).min(cur);
            out.add(new BatchSuggestion(b.getId(), b.getBatchNumber(), null, cur, suggested, b.getQuantityUnit()));
        }
        return new ReceiveSuggestion(line.getId(), remaining, nz(line.getReworkPendingQuantity()), rework,
                newPart, ratio, out);
    }

    /**
     * 收货 (D4/D5/D7): 合格 g + 不良 d。返工待收先抵扣 (不耗料, 血缘从被退回的收货批次连过来);
     * 新收部分必须有耗用。加工费 = 新收合格数 × 单价 (返工回货不计), 同一事务里挂应付 (按收货明细 id 幂等)。
     */
    @Transactional
    public OrderDetail receive(String factoryId, String orderId, String lineId, ReceiveRequest req, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (replayed(factoryId, orderId, lineId, req == null ? null : req.clientRequestId(),
                OutsourceMovement.Type.RECEIVE)) {
            return detail(factoryId, orderId);
        }
        requireReleased(order);
        OutsourceOrderLine line = requireOpenLine(order, lineId);
        BigDecimal g = nz(req.goodQuantity());
        BigDecimal d = nz(req.defectQuantity());
        if (g.signum() < 0 || d.signum() < 0 || g.add(d).signum() <= 0) {
            throw bad(400, "合格数、不良数不能为负, 且至少有一个大于 0", "OUTSOURCE_RECEIVE_QTY_INVALID", "goodQuantity");
        }
        LocalDate bizDate = bizDate(req.bizDate());
        BigDecimal rework = g.min(nz(line.getReworkPendingQuantity()));
        BigDecimal newGood = g.subtract(rework);
        BigDecimal newTotal = newGood.add(d);
        // 新收 (合格减返工抵扣 + 不良) 不能超过剩余应收: 否则少量耗用就能建出任意大的产出批次并挂对应应付 (终审 中3)。
        BigDecimal remaining = line.remainingToReceive();
        if (newTotal.compareTo(remaining) > 0) {
            throw bad(409, String.format("本次新收 %s (合格 %s + 不良 %s, 已扣返工抵扣 %s) 超过剩余应收 %s %s",
                    plain(newTotal), plain(newGood), plain(d), plain(rework), plain(remaining), line.getUnit()),
                    "OUTSOURCE_RECEIVE_EXCEEDS_REMAINING", "goodQuantity");
        }

        // ---- 1. 新收部分的耗用 ----
        List<BatchQuantity> consumptions = req.consumptions();
        if (newTotal.signum() > 0 && (consumptions == null || consumptions.isEmpty())) {
            consumptions = suggestReceive(factoryId, orderId, lineId, g, d).consumptions().stream()
                    .map(s -> new BatchQuantity(s.batchId(), s.suggestedQuantity())).toList();
        }
        if (newTotal.signum() == 0 && consumptions != null && consumptions.stream()
                .anyMatch(c -> c != null && c.quantity() != null && c.quantity().signum() != 0)) {
            // 只收返工不耗料: 客户端传的耗用会被静默忽略, 明确拒绝 (复审 低2)。
            throw bad(400, "本次全部是返工回货, 不耗料; 请去掉耗用明细, 或把新收数量填进合格 / 不良",
                    "OUTSOURCE_REWORK_CONSUMPTION_NOT_ALLOWED", "consumptions");
        }
        List<MaterialBatch> consumedBatches = new ArrayList<>();
        List<BigDecimal> consumedQty = new ArrayList<>();
        BigDecimal consumedCost = ZERO;
        boolean costKnown = true;
        if (newTotal.signum() > 0) {
            if (outsourceBalance(factoryId, line.getId()).signum() <= 0) {
                throw bad(409, "委外厂这一行已没有结存的料, 新收货没有可耗用的料; 请先发料",
                        "OUTSOURCE_BALANCE_EMPTY", lineId);
            }
            BigDecimal total = ZERO;
            Set<String> seenBatches = new HashSet<>();
            for (BatchQuantity c : consumptions) {
                if (c == null || c.quantity() == null || c.quantity().signum() == 0) {
                    continue;
                }
                if (isBlank(c.batchId())) {
                    throw bad(400, "耗用明细缺少委外仓批次", "OUTSOURCE_CONSUMPTION_INVALID", "consumptions");
                }
                if (c.quantity().signum() < 0) {
                    throw bad(400, "耗用量不能为负", "OUTSOURCE_CONSUMPTION_INVALID", c.batchId());
                }
                // 同批两条: 每条单独对结存校验都过, 合计却超结存 → 撞 DB 约束变 500 (终审 中4)。
                if (!seenBatches.add(c.batchId())) {
                    throw bad(409, "同一委外仓批次在耗用明细里出现了两次, 请合并数量",
                            "OUTSOURCE_CONSUMPTION_DUPLICATED", c.batchId());
                }
                MaterialBatch os = lockOutsourceBatch(factoryId, line, c.batchId());
                assertWithinBalance(os, c.quantity(), "耗用");
                consumedBatches.add(os);
                consumedQty.add(c.quantity());
                total = total.add(c.quantity());
                if (os.getUnitPrice() == null) {
                    costKnown = false;
                } else {
                    consumedCost = consumedCost.add(os.getUnitPrice().multiply(c.quantity()));
                }
            }
            if (total.signum() <= 0) {
                throw bad(409, "新收货必须登记耗用了哪些料", "OUTSOURCE_RECEIVE_CONSUMPTION_REQUIRED", "consumptions");
            }
        }

        // 加工费只按新收合格数计, 返工回货不再计费 (终审 中5)。退货时那 q × 单价已挂成待审负数调整:
        // 财务批准 = 返工那部分不付费; 驳回 = 照原收货付费。两条路都只付一次, 不会因为驳回多挂一笔。
        BigDecimal fee = money(newGood.multiply(line.getUnitFee()));
        OutsourceMovement header = movement(order, line, OutsourceMovement.Type.RECEIVE, bizDate, userId);
        header.setProductTypeId(line.getProductTypeId());
        header.setQuantity(g);
        header.setDefectQuantity(d);
        header.setUnit(line.getUnit());
        header.setAmount(fee);
        header.setClientRequestId(trimToNull(req.clientRequestId()));
        saveRequestMovement(header);

        InventoryOwnership ownership = commonOwnership(consumedBatches);
        String ownerCustomer = ownership == InventoryOwnership.CUSTOMER_OWNED && !consumedBatches.isEmpty()
                ? consumedBatches.get(0).getOwnerCustomerId() : null;
        if (rework.signum() > 0) {
            // 返工回货的物权跟被退批次走, 不跟本次新收的耗用走: 只收返工时耗用为空, commonOwnership 会给出「我方」,
            // 客户料 (代加工) 退去返工回来就变成我方库存 (复审 高1)。被退批次之间、与本次新收耗用之间物权不一致 ⇒ 409。
            OwnerRef reworkOwner = reworkOwnership(factoryId, line, planRework(line, rework));
            if (!consumedBatches.isEmpty()
                    && (reworkOwner.ownership() != ownership || !Objects.equals(reworkOwner.customerId(), ownerCustomer))) {
                throw bad(409, "本次新收的料与返工回货的物权不一致 (我方 / 客户), 请把返工回货和新收分开登记",
                        "OUTSOURCE_RECEIVE_MIXED_OWNERSHIP", "goodQuantity");
            }
            if (consumedBatches.isEmpty()) {
                ownership = reworkOwner.ownership();
                ownerCustomer = reworkOwner.customerId();
            }
        }

        // ---- 2. 产出批次 ----
        String goodBatchId = null;
        if (newGood.signum() > 0) {
            BigDecimal unitCost = costKnown
                    ? consumedCost.multiply(newGood).divide(newTotal, 8, RoundingMode.HALF_UP)
                            .add(newGood.multiply(line.getUnitFee())).divide(newGood, 4, RoundingMode.HALF_UP)
                    : null;
            goodBatchId = createOutput(order, line, newGood, unitCost, false, ownership, ownerCustomer, bizDate, userId);
            linkConsumedLineage(factoryId, line, consumedBatches, consumedQty, newGood, newTotal, goodBatchId, userId,
                    header.getId());
            header.setTargetBatchId(goodBatchId);
            header.setTargetBatchKind(kindOf(line));
            header.setUnitCost(unitCost);
        }
        if (d.signum() > 0) {
            BigDecimal unitCost = costKnown ? consumedCost.divide(newTotal, 4, RoundingMode.HALF_UP) : null;
            String defectBatchId = createOutput(order, line, d, unitCost, true, ownership, ownerCustomer, bizDate, userId);
            linkConsumedLineage(factoryId, line, consumedBatches, consumedQty, d, newTotal, defectBatchId, userId,
                    header.getId());
            OutsourceMovement dm = movement(order, line, OutsourceMovement.Type.RECEIVE, bizDate, userId);
            dm.setParentMovementId(header.getId());
            dm.setProductTypeId(line.getProductTypeId());
            dm.setQuantity(ZERO);
            dm.setDefectQuantity(d);
            dm.setUnit(line.getUnit());
            dm.setUnitCost(unitCost);
            dm.setTargetBatchId(defectBatchId);
            dm.setTargetBatchKind(kindOf(line));
            dm.setReason("DEFECT");
            movementRepository.save(dm);
        }
        if (rework.signum() > 0) {
            receiveRework(factoryId, order, line, header, rework, ownership, ownerCustomer, bizDate, userId,
                    goodBatchId == null);
        }

        // ---- 3. 耗用流水 (⛔ 不填 productionPlanId: 成本随产出单价传递, 填了会被计划成本再算一次) ----
        for (int i = 0; i < consumedBatches.size(); i++) {
            MaterialBatch os = consumedBatches.get(i);
            BigDecimal q = consumedQty.get(i);
            consumeOutsourceBatch(os, q);
            writeConsumption(factoryId, os, q, SOURCE_RECEIVE, "委外收货耗用 " + order.getOrderNo(), userId);
            OutsourceMovement cm = movement(order, line, OutsourceMovement.Type.CONSUME, bizDate, userId);
            cm.setParentMovementId(header.getId());
            cm.setMaterialTypeId(os.getMaterialTypeId());
            cm.setProductTypeId(os.getProductTypeId());
            cm.setSourceBatchId(os.getId());
            cm.setQuantity(q);
            cm.setUnit(os.getQuantityUnit());
            cm.setUnitCost(os.getUnitPrice());
            cm.setAmount(os.getUnitPrice() == null ? null : money(os.getUnitPrice().multiply(q)));
            movementRepository.save(cm);
        }

        // ---- 4. 应付 (D7) ----
        if (fee.signum() > 0) {
            var ap = arApService.recordPayableFromSource(factoryId, order.getSupplierId(), AP_SOURCE_RECEIVE,
                    header.getId(), fee, bizDate.plusDays(PAYABLE_DUE_DAYS), userId,
                    "委外加工费 " + order.getOrderNo() + " 第" + line.getLineNo() + "行 合格 " + plain(newGood));
            header.setApTransactionId(ap.getId());
        }
        movementRepository.save(header);

        line.setReceivedGoodQuantity(nz(line.getReceivedGoodQuantity()).add(g));
        line.setReceivedDefectQuantity(nz(line.getReceivedDefectQuantity()).add(d));
        line.setReworkPendingQuantity(nz(line.getReworkPendingQuantity()).subtract(rework));
        line.setFeeAmount(nz(line.getFeeAmount()).add(fee));
        lineRepository.save(line);
        syncTask(factoryId, line, g);
        return detail(factoryId, orderId);
    }

    /** 返工回货: 按被退回的收货批次先进先出抵扣, 不耗料; 产出单价沿用被退批次的单价。 */
    private void receiveRework(String factoryId, OutsourceOrder order, OutsourceOrderLine line, OutsourceMovement header,
                               BigDecimal rework, InventoryOwnership ownership, String ownerCustomer,
                               LocalDate bizDate, Long userId, boolean headerTarget) {
        List<OutsourceMovement> lineMovements = movementRepository.findByLineIdOrderByCreatedAtAsc(line.getId());
        Map<String, BigDecimal> plan = planRework(lineMovements, line, rework);
        BigDecimal cost = ZERO;
        boolean known = true;
        List<String> srcIds = new ArrayList<>(plan.keySet());
        List<BigDecimal> srcQty = new ArrayList<>(plan.values());
        for (int i = 0; i < srcIds.size(); i++) {
            BigDecimal srcCost = outputUnitCost(factoryId, line, srcIds.get(i));
            if (srcCost == null) {
                known = false;
            } else {
                cost = cost.add(srcCost.multiply(srcQty.get(i)));
            }
        }
        BigDecimal unitCost = known ? cost.divide(rework, 4, RoundingMode.HALF_UP) : null;
        String batchId = createOutput(order, line, rework, unitCost, false, ownership, ownerCustomer, bizDate, userId);
        String targetType = line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED
                ? LineageEdgeRecorder.NODE_FINISHED_BATCH : LineageEdgeRecorder.NODE_MATERIAL_BATCH;
        for (int i = 0; i < srcIds.size(); i++) {
            OutsourceMovement cm = movement(order, line, OutsourceMovement.Type.CONSUME, bizDate, userId);
            cm.setParentMovementId(header.getId());
            cm.setProductTypeId(line.getProductTypeId());
            cm.setSourceBatchId(srcIds.get(i));
            cm.setTargetBatchId(batchId);
            cm.setTargetBatchKind(kindOf(line));
            cm.setQuantity(srcQty.get(i));
            cm.setUnit(line.getUnit());
            cm.setReason(REWORK_MARK);
            movementRepository.save(cm);
            if (line.getOutputKind() == OutsourceOrderLine.OutputKind.SEMI) {
                lineageEdgeRecorder.recordOutsourceProcess(factoryId, srcIds.get(i), targetType, batchId, srcQty.get(i),
                        line.getUnit(), userId, "outsource-rework:" + cm.getId(), Map.of("origin", "OUTSOURCE_REWORK"));
            } else {
                // 成品被退回时源是成品批次, 而 OUTSOURCE_PROCESS 边的源只能是物料批次 ⇒ 返工成品批次的上游
                // 改为「被退批次当初耗用的委外仓批次」(按收发明细追, 返工再返工也追到首次收货), 否则新成品批次
                // 没有上游边, 正向召回 / 反查断链 (终审 高2)。
                Map<String, BigDecimal> upstream = new LinkedHashMap<>();
                collectOutsourceUpstream(lineMovements, srcIds.get(i), srcQty.get(i), upstream, 0);
                if (upstream.isEmpty()) {
                    throw bad(409, "返工回货追不到被退批次耗用的委外仓料, 血缘会断, 请联系管理员核对",
                            "OUTSOURCE_REWORK_LINEAGE_MISSING", srcIds.get(i));
                }
                for (Map.Entry<String, BigDecimal> u : upstream.entrySet()) {
                    String unitOfOs = materialBatchRepository.findByIdAndFactoryId(u.getKey(), factoryId)
                            .map(MaterialBatch::getQuantityUnit).orElse(null);
                    lineageEdgeRecorder.recordOutsourceProcess(factoryId, u.getKey(), targetType, batchId,
                            u.getValue().setScale(6, RoundingMode.HALF_UP), unitOfOs, userId,
                            "outsource-rework:" + cm.getId() + ":" + u.getKey(),
                            Map.of("origin", "OUTSOURCE_REWORK", "reworkOf", srcIds.get(i)));
                }
            }
        }
        if (headerTarget) {
            header.setTargetBatchId(batchId);
            header.setTargetBatchKind(kindOf(line));
            header.setUnitCost(unitCost);
        }
    }

    /** 物权 + 客户编号 (CUSTOMER_OWNED 时非空)。 */
    private record OwnerRef(InventoryOwnership ownership, String customerId) {
    }

    private Map<String, BigDecimal> planRework(OutsourceOrderLine line, BigDecimal rework) {
        return planRework(movementRepository.findByLineIdOrderByCreatedAtAsc(line.getId()), line, rework);
    }

    /** 返工待收按被退回的收货批次先进先出抵扣: 被退批次 id → 本次抵扣量。对不上 ⇒ 409。 */
    private static Map<String, BigDecimal> planRework(List<OutsourceMovement> lineMovements, OutsourceOrderLine line,
                                                      BigDecimal rework) {
        Map<String, BigDecimal> pending = new LinkedHashMap<>();
        for (OutsourceMovement m : lineMovements) {
            if (m.getMovementType() == OutsourceMovement.Type.GOODS_RETURN) {
                pending.merge(m.getSourceBatchId(), m.getQuantity(), BigDecimal::add);
            } else if (m.getMovementType() == OutsourceMovement.Type.CONSUME && REWORK_MARK.equals(m.getReason())) {
                pending.merge(m.getSourceBatchId(), m.getQuantity().negate(), BigDecimal::add);
            }
        }
        BigDecimal left = rework;
        Map<String, BigDecimal> plan = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : pending.entrySet()) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = e.getValue().min(left);
            if (take.signum() <= 0) {
                continue;
            }
            plan.put(e.getKey(), take);
            left = left.subtract(take);
        }
        if (left.signum() > 0) {
            throw bad(409, "返工待收与退货记录对不上, 请联系管理员核对", "OUTSOURCE_REWORK_MISMATCH", line.getId());
        }
        return plan;
    }

    /** 返工回货的物权 = 被退批次的物权; 多个被退批次物权 (或客户) 不一致 ⇒ 409, 不替用户猜。 */
    private OwnerRef reworkOwnership(String factoryId, OutsourceOrderLine line, Map<String, BigDecimal> plan) {
        OwnerRef first = null;
        for (String srcId : plan.keySet()) {
            OwnerRef cur;
            if (line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED) {
                FinishedGoodsBatch fg = finishedGoodsBatchRepository.findById(srcId)
                        .filter(b -> factoryId.equals(b.getFactoryId()))
                        .orElseThrow(() -> new ResourceNotFoundException("被退批次不存在: " + srcId));
                cur = ownerRef(fg.getOwnership(), fg.getOwnerCustomerId());
            } else {
                MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(srcId, factoryId)
                        .orElseThrow(() -> new ResourceNotFoundException("被退批次不存在: " + srcId));
                cur = ownerRef(mb.getOwnership(), mb.getOwnerCustomerId());
            }
            if (first == null) {
                first = cur;
            } else if (!first.equals(cur)) {
                throw bad(409, "本次返工回货抵扣的被退批次物权不一致 (我方 / 客户), 请按物权分开收返工",
                        "OUTSOURCE_REWORK_MIXED_OWNERSHIP", srcId);
            }
        }
        return first != null ? first : new OwnerRef(InventoryOwnership.COMPANY_OWNED, null);
    }

    private static OwnerRef ownerRef(InventoryOwnership ownership, String customerId) {
        InventoryOwnership o = ownership != null ? ownership : InventoryOwnership.COMPANY_OWNED;
        return new OwnerRef(o, o == InventoryOwnership.CUSTOMER_OWNED ? customerId : null);
    }

    /**
     * 产出批次 {@code batchId} 的 {@code take} 个单位, 按收发明细追到它耗用的委外仓批次 (委外仓批次 id → 耗用量份额)。
     * <ul>
     *   <li>返工回货建的批次: 返工 CONSUME 行 targetBatchId = 它, 源是更早被退回的批次 ⇒ 按各行数量分摊后递归。</li>
     *   <li>首次收货建的批次: 收货主记录 targetBatchId = 它; 其下非返工的 CONSUME 行就是耗用的委外仓批次,
     *       按 take / 本次新收总数 (合格新收 + 不良) 分摊。</li>
     * </ul>
     */
    private static void collectOutsourceUpstream(List<OutsourceMovement> lineMovements, String batchId, BigDecimal take,
                                                 Map<String, BigDecimal> out, int depth) {
        if (depth > 32 || take.signum() <= 0) {
            return;
        }
        // 先看是不是返工回货建的批次 (只返工、无新收合格时收货主记录也指向它, 不能按主记录的耗用分摊)
        List<OutsourceMovement> reworkRows = lineMovements.stream()
                .filter(m -> m.getMovementType() == OutsourceMovement.Type.CONSUME && REWORK_MARK.equals(m.getReason())
                        && batchId.equals(m.getTargetBatchId()))
                .toList();
        BigDecimal reworkTotal = sum(reworkRows, OutsourceMovement::getQuantity);
        if (reworkTotal.signum() > 0) {
            for (OutsourceMovement r : reworkRows) {
                collectOutsourceUpstream(lineMovements, r.getSourceBatchId(),
                        take.multiply(nz(r.getQuantity())).divide(reworkTotal, 8, RoundingMode.HALF_UP), out, depth + 1);
            }
            return;
        }
        for (OutsourceMovement h : lineMovements) {
            if (h.getMovementType() != OutsourceMovement.Type.RECEIVE || h.getParentMovementId() != null
                    || !batchId.equals(h.getTargetBatchId())) {
                continue;
            }
            BigDecimal reworkUnder = ZERO;
            List<OutsourceMovement> consumed = new ArrayList<>();
            for (OutsourceMovement c : lineMovements) {
                if (c.getMovementType() != OutsourceMovement.Type.CONSUME || !h.getId().equals(c.getParentMovementId())) {
                    continue;
                }
                if (REWORK_MARK.equals(c.getReason())) {
                    reworkUnder = reworkUnder.add(nz(c.getQuantity()));
                } else {
                    consumed.add(c);
                }
            }
            BigDecimal newTotal = nz(h.getQuantity()).add(nz(h.getDefectQuantity())).subtract(reworkUnder);
            if (newTotal.signum() > 0) {
                for (OutsourceMovement c : consumed) {
                    out.merge(c.getSourceBatchId(),
                            nz(c.getQuantity()).multiply(take).divide(newTotal, 8, RoundingMode.HALF_UP), BigDecimal::add);
                }
                return;
            }
        }
    }

    private BigDecimal outputUnitCost(String factoryId, OutsourceOrderLine line, String batchId) {
        if (line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED) {
            return finishedGoodsBatchRepository.findById(batchId)
                    .filter(b -> factoryId.equals(b.getFactoryId()))
                    .map(FinishedGoodsBatch::getUnitCost).orElse(null);
        }
        return materialBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .map(MaterialBatch::getUnitPrice).orElse(null);
    }

    private void linkConsumedLineage(String factoryId, OutsourceOrderLine line, List<MaterialBatch> consumed,
                                     List<BigDecimal> qty, BigDecimal share, BigDecimal total, String targetBatchId,
                                     Long userId, String headerId) {
        String targetType = line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED
                ? LineageEdgeRecorder.NODE_FINISHED_BATCH : LineageEdgeRecorder.NODE_MATERIAL_BATCH;
        for (int i = 0; i < consumed.size(); i++) {
            BigDecimal edgeQty = qty.get(i).multiply(share).divide(total, 6, RoundingMode.HALF_UP);
            lineageEdgeRecorder.recordOutsourceProcess(factoryId, consumed.get(i).getId(), targetType, targetBatchId,
                    edgeQty, consumed.get(i).getQuantityUnit(), userId,
                    "outsource-receive:" + headerId + ":" + consumed.get(i).getId() + ":" + targetBatchId,
                    Map.of("origin", "OUTSOURCE_RECEIVE"));
        }
    }

    /** 建产出批次: FINISHED → 成品仓成品批次; SEMI → 车间仓半成品物料批次。返回批次 id。 */
    private String createOutput(OutsourceOrder order, OutsourceOrderLine line, BigDecimal qty, BigDecimal unitCost,
                                boolean defect, InventoryOwnership ownership, String ownerCustomer,
                                LocalDate bizDate, Long userId) {
        String factoryId = order.getFactoryId();
        ProductType pt = productTypeRepository.findByIdAndFactoryId(line.getProductTypeId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("产出产品不存在: " + line.getProductTypeId()));
        String batchNo = batchNumber(order.getOrderNo() + "-" + line.getLineNo(), defect ? "NG" : "RC");
        LocalDate expire = bizDate.plusDays(pt.getShelfLifeDays() != null ? pt.getShelfLifeDays() : 180);
        if (line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED) {
            FinishedGoodsBatch fg = new FinishedGoodsBatch();
            fg.setFactoryId(factoryId);
            fg.setBatchNumber(batchNo);
            fg.setProductTypeId(pt.getId());
            fg.setProductName(pt.getName());
            fg.setProducedQuantity(qty);
            fg.setShippedQuantity(ZERO);
            fg.setReservedQuantity(ZERO);
            fg.setUnit(line.getUnit());
            fg.setUnitPrice(pt.getUnitPrice());
            fg.setUnitCost(unitCost);
            fg.setProductionDate(bizDate);
            fg.setExpireDate(expire);
            fg.setProductionPlanId(line.getProductionPlanId());
            fg.setOwnership(ownership);
            fg.setOwnerCustomerId(ownerCustomer);
            fg.setWarehouseId(warehouseResolver.resolveFinishedGoodsId(factoryId));
            fg.setStatus(defect ? FinishedGoodsBatch.Status.DEFECTIVE
                    : qcInspectionPolicy.finishedGoodsStatusForOutput(factoryId, pt.getId(), line.getProductionPlanId(),
                            FinishedGoodsBatch.Status.AVAILABLE, batchNo));
            fg.setCreatedBy(userId);
            fg.setRemark("委外收货 " + order.getOrderNo() + (defect ? " 不良" : ""));
            return finishedGoodsBatchRepository.save(fg).getId();
        }
        MaterialBatch mb = new MaterialBatch();
        mb.setId(UUID.randomUUID().toString());
        mb.setFactoryId(factoryId);
        mb.setBatchNumber(batchNo);
        mb.setProductTypeId(pt.getId());
        mb.setSupplierId(order.getSupplierId());
        mb.setReceiptQuantity(qty);
        mb.setUsedQuantity(ZERO);
        mb.setReservedQuantity(ZERO);
        mb.setQuantityUnit(line.getUnit());
        mb.setUnitPrice(unitCost == null ? null : unitCost.setScale(2, RoundingMode.HALF_UP));
        mb.setReceiptDate(bizDate);
        mb.setProductionDate(bizDate);
        mb.setExpireDate(expire);
        mb.setOwnership(ownership);
        mb.setOwnerCustomerId(ownerCustomer);
        mb.setWarehouseId(warehouseResolver.resolveWorkshopId(factoryId));
        mb.setStatus(defect ? MaterialBatchStatus.DEFECTIVE
                : (qcInspectionPolicy.shouldHoldSemi(factoryId, QcInspectionPolicy.OutputRef.ofProduct(pt.getId()))
                        ? MaterialBatchStatus.INSPECTING : MaterialBatchStatus.AVAILABLE));
        mb.setCreatedBy(userId);
        mb.setSourceDocType(DOC_RECEIVE);
        mb.setSourceDocId(line.getId());
        return materialBatchRepository.save(mb).getId();
    }

    private static InventoryOwnership commonOwnership(List<MaterialBatch> batches) {
        if (batches.isEmpty()) {
            return InventoryOwnership.COMPANY_OWNED;
        }
        InventoryOwnership first = batches.get(0).getOwnership();
        String owner = batches.get(0).getOwnerCustomerId();
        for (MaterialBatch b : batches) {
            if (!Objects.equals(first, b.getOwnership()) || !Objects.equals(owner, b.getOwnerCustomerId())) {
                // 我方料与客户料 (或两个客户的料) 混在一次收货里: 产出归谁说不清, 不替用户猜。
                throw bad(409, "本次耗用的料物权不一致 (我方 / 客户), 请按物权分开收货",
                        "OUTSOURCE_RECEIVE_MIXED_OWNERSHIP", "consumptions");
            }
        }
        return first != null ? first : InventoryOwnership.COMPANY_OWNED;
    }

    // ==================== 退货 / 损耗 / 结单 ====================

    /**
     * 退货返工 (D9): 扣收货批次 → 返工待收; 加工费冲减挂一条<b>待审</b>负数 AP 调整 (四眼, 审批前余额不变)。
     *
     * @param qualityReturnOrderId 由质检退回单 (SUBCONTRACT) ship 触发时回填, 否则 null
     */
    @Transactional
    public OrderDetail returnGoods(String factoryId, String orderId, String lineId, GoodsReturnRequest req,
                                   Long userId, String qualityReturnOrderId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (replayed(factoryId, orderId, lineId, req == null ? null : req.clientRequestId(),
                OutsourceMovement.Type.GOODS_RETURN)) {
            return detail(factoryId, orderId);
        }
        requireReleased(order);
        OutsourceOrderLine line = requireLine(order, lineId);
        BigDecimal q = req == null ? null : req.quantity();
        if (q == null || q.signum() <= 0) {
            throw bad(400, "退货数量必须大于 0", "OUTSOURCE_GOODS_RETURN_QTY_INVALID", "quantity");
        }
        String batchId = req.sourceBatchId();
        List<OutsourceMovement> lineMovements = movementRepository.findByLineIdOrderByCreatedAtAsc(line.getId());
        // 返工回货建的批次本来就没计加工费 (receive 只按新收合格计费) ⇒ 再退它不能再冲一次加工费 (复审 中1)。
        boolean isReworkBatch = batchId != null && lineMovements.stream()
                .anyMatch(m -> m.getMovementType() == OutsourceMovement.Type.CONSUME
                        && REWORK_MARK.equals(m.getReason()) && batchId.equals(m.getTargetBatchId()));
        boolean isReceiptGood = lineMovements.stream()
                .anyMatch(m -> batchId != null && batchId.equals(m.getTargetBatchId())
                        && (m.getMovementType() == OutsourceMovement.Type.RECEIVE
                            || (m.getMovementType() == OutsourceMovement.Type.CONSUME && REWORK_MARK.equals(m.getReason())))
                        && !"DEFECT".equals(m.getReason()));
        if (!isReceiptGood) {
            throw bad(409, "只能退本行收货的合格批次", "OUTSOURCE_GOODS_RETURN_BATCH_INVALID", "sourceBatchId");
        }
        if (nz(line.getReceivedGoodQuantity()).compareTo(q) < 0) {
            throw bad(409, "退货数量超过本行已收合格数 " + plain(line.getReceivedGoodQuantity()),
                    "OUTSOURCE_GOODS_RETURN_EXCEEDS_RECEIVED", "quantity");
        }
        LocalDate bizDate = bizDate(req.bizDate());
        BigDecimal unitCost;
        String unit;
        if (line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED) {
            FinishedGoodsBatch fg = finishedGoodsBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("收货批次不存在: " + batchId));
            if (fg.getAvailableQuantity().compareTo(q) < 0) {
                throw bad(409, String.format("收货批次 %s 可退 %s, 不够退 %s (已发货或已预留的不能退)",
                        fg.getBatchNumber(), plain(fg.getAvailableQuantity()), plain(q)),
                        "OUTSOURCE_GOODS_RETURN_INSUFFICIENT", batchId);
            }
            // 这批是委外收货建的批次: 产量即实收量, 退回委外厂 = 实收减少 (不记成发货)。
            fg.setProducedQuantity(fg.getProducedQuantity().subtract(q));
            finishedGoodsBatchRepository.save(fg);
            unitCost = fg.getUnitCost();
            unit = fg.getUnit();
        } else {
            MaterialBatch mb = materialBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("收货批次不存在: " + batchId));
            if (mb.getCurrentQuantity().compareTo(q) < 0) {
                throw bad(409, String.format("收货批次 %s 结存 %s, 不够退 %s (已被领用的不能退)",
                        mb.getBatchNumber(), plain(mb.getCurrentQuantity()), plain(q)),
                        "OUTSOURCE_GOODS_RETURN_INSUFFICIENT", batchId);
            }
            mb.setUsedQuantity(nz(mb.getUsedQuantity()).add(q));
            if (mb.getCurrentQuantity().signum() <= 0) {
                mb.setStatus(MaterialBatchStatus.whenExhausted(mb.getStatus(), MaterialBatchStatus.DEPLETED));
            }
            materialBatchRepository.save(mb);
            unitCost = mb.getUnitPrice();
            unit = mb.getQuantityUnit();
        }
        BigDecimal feeBack = isReworkBatch ? money(ZERO) : money(q.multiply(line.getUnitFee()));
        OutsourceMovement mv = movement(order, line, OutsourceMovement.Type.GOODS_RETURN, bizDate, userId);
        mv.setProductTypeId(line.getProductTypeId());
        mv.setSourceBatchId(batchId);
        mv.setQuantity(q);
        mv.setUnit(unit);
        mv.setUnitCost(unitCost);
        mv.setAmount(feeBack.negate());
        mv.setReason(trimToNull(req.reason()));
        mv.setQualityReturnOrderId(qualityReturnOrderId);
        mv.setClientRequestId(trimToNull(req.clientRequestId()));
        saveRequestMovement(mv);
        if (feeBack.signum() > 0) {
            var adj = arApService.recordAdjustment(factoryId, CounterpartyType.SUPPLIER, order.getSupplierId(),
                    feeBack.negate(), userId,
                    "委外退货冲减加工费 " + order.getOrderNo() + " 第" + line.getLineNo() + "行 退 " + plain(q),
                    AP_SOURCE_GOODS_RETURN, mv.getId());
            mv.setApTransactionId(adj.getId());
            movementRepository.save(mv);
        }
        line.setReceivedGoodQuantity(nz(line.getReceivedGoodQuantity()).subtract(q));
        line.setReturnedGoodsQuantity(nz(line.getReturnedGoodsQuantity()).add(q));
        line.setReworkPendingQuantity(nz(line.getReworkPendingQuantity()).add(q));
        // ⛔ 不在这里扣 fee_amount: 冲减还是待审调整, 财务驳回就照原额付 (复审 中2)。
        // fee_amount = 已挂的收货加工费毛额; 对外展示用 effectiveFee (毛额 + 已批准的退货冲减)。
        line.setStatus(OutsourceOrderLine.Status.OPEN);
        lineRepository.save(line);
        syncTask(factoryId, line, q.negate());
        return detail(factoryId, orderId);
    }

    /** 损耗 (D8): 委外厂手里的料登记为损耗, 原因必填。 */
    @Transactional
    public OrderDetail registerLoss(String factoryId, String orderId, String lineId, LossRequest req, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (replayed(factoryId, orderId, lineId, req == null ? null : req.clientRequestId(),
                OutsourceMovement.Type.LOSS)) {
            return detail(factoryId, orderId);
        }
        requireReleased(order);
        OutsourceOrderLine line = requireLine(order, lineId);
        String reason = req == null ? null : trimToNull(req.reason());
        if (reason == null || reason.length() < 2) {
            throw bad(400, "请写明损耗原因 (至少 2 个字)", "OUTSOURCE_LOSS_REASON_REQUIRED", "reason");
        }
        List<BatchQuantity> items = requireItems(req.items());
        LocalDate bizDate = bizDate(req.bizDate());
        String requestId = trimToNull(req.clientRequestId());
        for (BatchQuantity it : items) {
            MaterialBatch os = lockOutsourceBatch(factoryId, line, it.batchId());
            assertWithinBalance(os, it.quantity(), "损耗");
            consumeOutsourceBatch(os, it.quantity());
            writeConsumption(factoryId, os, it.quantity(), SOURCE_LOSS, "委外损耗 " + order.getOrderNo() + ": " + reason, userId);
            OutsourceMovement mv = movement(order, line, OutsourceMovement.Type.LOSS, bizDate, userId);
            mv.setMaterialTypeId(os.getMaterialTypeId());
            mv.setProductTypeId(os.getProductTypeId());
            mv.setSourceBatchId(os.getId());
            mv.setQuantity(it.quantity());
            mv.setUnit(os.getQuantityUnit());
            mv.setUnitCost(os.getUnitPrice());
            mv.setAmount(os.getUnitPrice() == null ? null : money(os.getUnitPrice().multiply(it.quantity())));
            mv.setReason(reason);
            mv.setClientRequestId(requestId);
            requestId = null;
            saveRequestMovement(mv);
        }
        return detail(factoryId, orderId);
    }

    /** 结单 (D8): 委外厂结存必须为 0、无返工待收; 否则 409 并逐批列出, ⛔ 不静默吞差额。 */
    @Transactional
    public OrderDetail close(String factoryId, String orderId, Long userId) {
        requireActor(userId);
        OutsourceOrder order = lockOrder(factoryId, orderId);
        if (order.getStatus() == OutsourceOrder.Status.CLOSED) {
            return detail(factoryId, orderId);
        }
        requireReleased(order);
        List<OutsourceOrderLine> lines = lineRepository.findByOrderIdOrderByLineNoAsc(order.getId());
        List<String> problems = new ArrayList<>();
        for (OutsourceOrderLine line : lines) {
            for (MaterialBatch b : outsourceBatches(factoryId, line.getId())) {
                if (b.getCurrentQuantity().signum() > 0) {
                    problems.add(String.format("第%d行 批次 %s 还剩 %s %s", line.getLineNo(), b.getBatchNumber(),
                            plain(b.getCurrentQuantity()), nvl(b.getQuantityUnit())));
                }
            }
            if (nz(line.getReworkPendingQuantity()).signum() > 0) {
                problems.add(String.format("第%d行 返工待收 %s %s", line.getLineNo(),
                        plain(line.getReworkPendingQuantity()), line.getUnit()));
            }
        }
        if (!problems.isEmpty()) {
            throw bad(409, "委外厂还有结存, 不能结单: " + String.join("; ", problems),
                    "OUTSOURCE_CLOSE_BALANCE_NOT_ZERO", null)
                    .withHint("请先退料, 或登记损耗并写明原因; 返工待收需收回或核销后再结单");
        }
        LocalDateTime now = LocalDateTime.now();
        for (OutsourceOrderLine line : lines) {
            if (line.getStatus() == OutsourceOrderLine.Status.OPEN) {
                line.setStatus(OutsourceOrderLine.Status.COMPLETED);
                line.setCompletedAt(now);
                lineRepository.save(line);
            }
            if (line.getWorkProcessTaskId() != null) {
                taskRepository.findByFactoryIdAndIdForUpdate(factoryId, line.getWorkProcessTaskId()).ifPresent(t -> {
                    if (t.getStatus() != null && !t.getStatus().isTerminal()) {
                        t.setStatus(WorkProcessTask.Status.COMPLETED);
                        if (t.getActualStartAt() == null) {
                            t.setActualStartAt(now);
                        }
                        t.setActualEndAt(now);
                        t.setCompletedAt(now);
                        t.setCompletedBy(userId);
                        t.setActualQuantity(nz(line.getReceivedGoodQuantity()));
                        t.setOutsourceFeeAmount(effectiveFee(factoryId, line));
                        taskRepository.save(t);
                    }
                });
            }
        }
        order.setStatus(OutsourceOrder.Status.CLOSED);
        order.setClosedAt(now);
        order.setClosedBy(userId);
        orderRepository.save(order);
        return detail(factoryId, orderId);
    }

    /**
     * 质检退回单 (SUBCONTRACT) 发出时调用: 走同一个退货实现, 回填 movement。
     * 只处理<b>已关联</b>任务行的 SUBCONTRACT 单; 未关联的由调用方按原行为发出 (关联入口未接上前不收紧)。
     * 关联了任务行却没填被退批次 ⇒ 409 (半关联说不清扣哪批)。
     */
    @Transactional
    public void returnGoodsForQualityReturn(String factoryId, QualityReturnOrder qro, Long userId) {
        if (qro.getTargetType() != QualityReturnTargetType.SUBCONTRACT) {
            return;
        }
        if (isBlank(qro.getOutsourceLineId())) {
            return;
        }
        if (isBlank(qro.getSourceBatchId())) {
            throw bad(409, "退回委外的退回单关联了委外任务行, 但没有选被退的收货批次", "OUTSOURCE_LINE_REQUIRED", "sourceBatchId");
        }
        OutsourceOrderLine line = lineRepository.findByIdAndFactoryId(qro.getOutsourceLineId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外任务行不存在: " + qro.getOutsourceLineId()));
        returnGoods(factoryId, line.getOrderId(), line.getId(),
                new GoodsReturnRequest("QRO-" + qro.getId(), LocalDate.now(), qro.getSourceBatchId(),
                        qro.getQuantity(), qro.getReason()), userId, qro.getId());
    }

    // ==================== 读 ====================

    @Transactional(readOnly = true)
    public Page<OrderSummary> list(String factoryId, String supplierId, List<String> statuses, String keyword,
                                   int page, int size) {
        List<OutsourceOrder.Status> st = statuses == null ? List.of() : statuses.stream()
                .filter(s -> !isBlank(s))
                .map(s -> parseEnum(OutsourceOrder.Status.class, s, "status", "状态取值不对"))
                .toList();
        Page<OutsourceOrder> p = orderRepository.search(factoryId, trimToNull(supplierId),
                st.isEmpty() ? List.of(OutsourceOrder.Status.DRAFT) : st, st.isEmpty(), trimToNull(keyword),
                PageRequest.of(Math.max(0, page - 1), Math.max(1, Math.min(size, 200))));
        List<String> ids = p.getContent().stream().map(OutsourceOrder::getId).toList();
        Map<String, List<OutsourceOrderLine>> linesByOrder = ids.isEmpty() ? Map.of()
                : lineRepository.findByOrderIdInOrderByLineNoAsc(ids).stream()
                        .collect(Collectors.groupingBy(OutsourceOrderLine::getOrderId));
        Map<String, String> supplierNames = supplierNames(factoryId,
                p.getContent().stream().map(OutsourceOrder::getSupplierId).collect(Collectors.toSet()));
        List<OutsourceOrderLine> allLines = linesByOrder.values().stream().flatMap(List::stream).toList();
        // 一页只查一次委外仓批次 / 退货收发 / 冲减调整 (复审 低2: 原来每行各查一次)。
        Map<String, BigDecimal> balances = outsourceBalances(factoryId, allLines);
        Map<String, BigDecimal> fees = effectiveFees(factoryId, allLines, null);
        return p.map(o -> {
            List<OutsourceOrderLine> ls = linesByOrder.getOrDefault(o.getId(), List.of());
            BigDecimal balance = sum(ls, l -> balances.getOrDefault(l.getId(), ZERO));
            return new OrderSummary(o.getId(), o.getOrderNo(), o.getSupplierId(), supplierNames.get(o.getSupplierId()),
                    o.getStatus().name(), o.getExpectedReturnDate(), ls.size(),
                    sum(ls, OutsourceOrderLine::getPlannedQuantity), sum(ls, OutsourceOrderLine::getReceivedGoodQuantity),
                    balance, sum(ls, l -> fees.get(l.getId())), o.getCreatedAt());
        });
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(String factoryId, String orderId) {
        OutsourceOrder o = orderRepository.findByIdAndFactoryId(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外单不存在或不属于当前工厂"));
        List<OutsourceOrderLine> lines = lineRepository.findByOrderIdOrderByLineNoAsc(o.getId());
        Map<String, String> planNumbers = new HashMap<>();
        for (OutsourceOrderLine l : lines) {
            if (l.getProductionPlanId() != null && !planNumbers.containsKey(l.getProductionPlanId())) {
                planNumbers.put(l.getProductionPlanId(), productionPlanRepository
                        .findByIdAndFactoryId(l.getProductionPlanId(), factoryId)
                        .map(ProductionPlan::getPlanNumber).orElse(null));
            }
        }
        List<LineView> lineViews = new ArrayList<>();
        List<BalanceView> balances = new ArrayList<>();
        Map<String, BigDecimal> issuedByBatch = new HashMap<>();
        List<OutsourceMovement> movements = movementRepository.findByOrderIdOrderByCreatedAtAsc(o.getId());
        for (OutsourceMovement m : movements) {
            if (m.getMovementType() == OutsourceMovement.Type.ISSUE) {
                issuedByBatch.merge(m.getTargetBatchId(), m.getQuantity(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> fees = effectiveFees(factoryId, lines, movements);
        Map<String, String> originByBatch = movements.stream()
                .filter(m -> m.getMovementType() == OutsourceMovement.Type.ISSUE)
                .collect(Collectors.toMap(OutsourceMovement::getTargetBatchId, OutsourceMovement::getSourceBatchId,
                        (a, b) -> a));
        for (OutsourceOrderLine l : lines) {
            BigDecimal bal = ZERO;
            for (MaterialBatch b : outsourceBatches(factoryId, l.getId())) {
                bal = bal.add(b.getCurrentQuantity());
                balances.add(new BalanceView(l.getId(), b.getId(), b.getBatchNumber(), b.getMaterialTypeId(),
                        b.getProductTypeId(), null, originByBatch.get(b.getId()), issuedByBatch.get(b.getId()),
                        b.getCurrentQuantity(), b.getQuantityUnit(), b.getUnitPrice()));
            }
            lineViews.add(new LineView(l.getId(), l.getLineNo(), l.getOutsourceType().name(), l.getProductionPlanId(),
                    planNumbers.get(l.getProductionPlanId()), l.getWorkProcessTaskId(), l.getProcessName(),
                    l.getOutputKind().name(), l.getProductTypeId(), l.getOutputName(), l.getUnit(),
                    l.getPlannedQuantity(), l.getUnitFee(), l.getTaxRate(), l.getReceivedGoodQuantity(),
                    l.getReceivedDefectQuantity(), l.getReturnedGoodsQuantity(), l.getReworkPendingQuantity(),
                    l.remainingToReceive(), fees.get(l.getId()), bal, l.getStatus().name()));
        }
        Map<String, String> batchNos = batchNumbers(factoryId, movements);
        List<MovementView> mvs = movements.stream().map(m -> new MovementView(m.getId(), m.getLineId(),
                m.getMovementType().name(), m.getParentMovementId(), m.getBizDate(), m.getSourceBatchId(),
                batchNos.get(m.getSourceBatchId()), m.getTargetBatchId(), batchNos.get(m.getTargetBatchId()),
                m.getTargetBatchKind() == null ? null : m.getTargetBatchKind().name(), m.getQuantity(),
                m.getDefectQuantity(), m.getUnit(), m.getUnitCost(), m.getAmount(), m.getApTransactionId(),
                m.getQualityReturnOrderId(), m.getReason(), m.getCreatedBy(), m.getCreatedAt())).toList();
        return new OrderDetail(o.getId(), o.getOrderNo(), o.getSupplierId(),
                supplierNames(factoryId, Set.of(o.getSupplierId())).get(o.getSupplierId()), o.getStatus().name(),
                o.getOutsourceWarehouseId(), o.getExpectedReturnDate(), o.getRemark(), o.getReleasedAt(),
                o.getClosedAt(), o.getCreatedAt(), sum(lines, l -> fees.get(l.getId())), lineViews, balances, mvs);
    }

    /** 工单详情里的委外工序: 按工单列出挂在它下面的委外行。 */
    @Transactional(readOnly = true)
    public List<ProcessLink> processLinks(String factoryId, String productionPlanId) {
        if (isBlank(productionPlanId)) {
            return List.of();
        }
        List<OutsourceOrderLine> lines = lineRepository.findByFactoryIdAndProductionPlanIdIn(factoryId,
                List.of(productionPlanId));
        Map<String, OutsourceOrder> orders = new HashMap<>();
        for (OutsourceOrderLine l : lines) {
            orders.computeIfAbsent(l.getOrderId(), id -> orderRepository.findByIdAndFactoryId(id, factoryId).orElse(null));
        }
        Map<String, String> names = supplierNames(factoryId, orders.values().stream().filter(Objects::nonNull)
                .map(OutsourceOrder::getSupplierId).collect(Collectors.toSet()));
        Map<String, BigDecimal> fees = effectiveFees(factoryId, lines, null);
        List<ProcessLink> out = new ArrayList<>();
        for (OutsourceOrderLine l : lines) {
            OutsourceOrder o = orders.get(l.getOrderId());
            if (o == null || o.getStatus() == OutsourceOrder.Status.CANCELLED) {
                continue;
            }
            out.add(new ProcessLink(l.getWorkProcessTaskId(), l.getProductionPlanId(), o.getId(), o.getOrderNo(),
                    o.getStatus().name(), o.getSupplierId(), names.get(o.getSupplierId()), l.getId(),
                    l.getStatus().name(), l.getPlannedQuantity(), l.getReceivedGoodQuantity(), fees.get(l.getId())));
        }
        return out;
    }

    /** 销售订单名下计划所挂委外行的加工费合计 (D10, 只展示); 没有委外行 ⇒ null (不伪造 0)。 */
    @Transactional(readOnly = true)
    public BigDecimal processingFeeForPlans(String factoryId, Collection<String> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return null;
        }
        List<OutsourceOrderLine> lines = lineRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds).stream()
                .filter(l -> l.getStatus() != OutsourceOrderLine.Status.CANCELLED).toList();
        if (lines.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> fees = effectiveFees(factoryId, lines, null);
        return sum(lines, l -> fees.get(l.getId()));
    }

    /** 把质检退回单 (SUBCONTRACT) 关联到委外任务行与被退的收货批次, 之后 ship 才会扣库存、冲加工费。 */
    @Transactional
    public void linkQualityReturn(String factoryId, QualityReturnOrder qro, String lineId, String sourceBatchId) {
        if (qro.getTargetType() != QualityReturnTargetType.SUBCONTRACT) {
            throw bad(409, "只有「退回委外」的退回单能关联委外任务行", "OUTSOURCE_QRO_NOT_SUBCONTRACT", null);
        }
        OutsourceOrderLine line = lineRepository.findByIdAndFactoryId(lineId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外任务行不存在"));
        OutsourceOrder order = orderRepository.findByIdAndFactoryId(line.getOrderId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外单不存在"));
        if (qro.getTargetId() != null && !qro.getTargetId().equals(order.getSupplierId())) {
            throw bad(409, "退回单的接收方与委外单的委外厂不一致", "OUTSOURCE_QRO_SUPPLIER_MISMATCH", "targetId");
        }
        qro.setOutsourceLineId(line.getId());
        qro.setSourceBatchId(sourceBatchId);
    }

    // ==================== 内部 ====================

    /**
     * 有效加工费 = 已挂的收货加工费毛额 (fee_amount) + <b>已批准</b>的退货冲减调整; 待审 / 驳回的冲减不算
     * (复审 中2: 退货当时就扣行加工费, 财务驳回后行上、销售订单、工序上都少显示)。
     */
    private BigDecimal effectiveFee(String factoryId, OutsourceOrderLine line) {
        return effectiveFees(factoryId, List.of(line), null).get(line.getId());
    }

    /**
     * 多行一起算有效加工费: 退货收发一次查 (movements 传 null 时按行 id 批量查; detail 已有本单全部收发就直接用),
     * 冲减调整一次 findAllById (复审 低2)。口径与单行相同: 毛额 + 本行退货上挂的、已批准且未删的调整。
     */
    private Map<String, BigDecimal> effectiveFees(String factoryId, Collection<OutsourceOrderLine> lines,
                                                  List<OutsourceMovement> movements) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        Set<String> lineIds = lines.stream().map(OutsourceOrderLine::getId).collect(Collectors.toSet());
        List<OutsourceMovement> mvs = movements != null ? movements
                : movementRepository.findByLineIdInAndMovementTypeIn(lineIds, List.of(OutsourceMovement.Type.GOODS_RETURN));
        Map<String, List<String>> adjIdsByLine = new HashMap<>();
        for (OutsourceMovement m : mvs) {
            if (m.getMovementType() == OutsourceMovement.Type.GOODS_RETURN && m.getApTransactionId() != null
                    && lineIds.contains(m.getLineId())) {
                adjIdsByLine.computeIfAbsent(m.getLineId(), k -> new ArrayList<>()).add(m.getApTransactionId());
            }
        }
        Map<String, BigDecimal> approvedById = new HashMap<>();
        List<String> allAdjIds = adjIdsByLine.values().stream().flatMap(List::stream).distinct().toList();
        if (!allAdjIds.isEmpty()) {
            for (ArApTransaction t : arApTransactionRepository.findAllById(allAdjIds)) {
                if (factoryId.equals(t.getFactoryId()) && t.getDeletedAt() == null
                        && t.getApprovalStatus() == ArApApprovalStatus.APPROVED && t.getAmount() != null) {
                    approvedById.put(t.getId(), t.getAmount());
                }
            }
        }
        for (OutsourceOrderLine line : lines) {
            BigDecimal fee = nz(line.getFeeAmount());
            for (String adjId : adjIdsByLine.getOrDefault(line.getId(), List.of())) {
                fee = fee.add(approvedById.getOrDefault(adjId, ZERO));
            }
            out.put(line.getId(), fee);
        }
        return out;
    }

    private OutsourceOrder lockOrder(String factoryId, String orderId) {
        return orderRepository.findByIdAndFactoryIdForUpdate(orderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外单不存在或不属于当前工厂"));
    }

    /**
     * 带提交编号的那条收发: 立即 flush, 撞部分唯一索引 uk_outsource_movements_request (别的单 / 并发同号已落库)
     * 转 409, 不让 500 冒出去 (复审 低5)。其余约束错误原样抛出。
     */
    private void saveRequestMovement(OutsourceMovement mv) {
        if (mv.getClientRequestId() == null) {
            movementRepository.save(mv);
            return;
        }
        try {
            movementRepository.saveAndFlush(mv);
        } catch (DataIntegrityViolationException e) {
            String msg = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            if (msg.contains("uk_outsource_movements_request")) {
                throw bad(409, "这个提交编号已被另一次提交使用 (可能是并发重复提交), 请刷新后重试",
                        "OUTSOURCE_REQUEST_ID_CONFLICT", "clientRequestId");
            }
            throw e;
        }
    }

    private boolean replayed(String factoryId, String orderId, String lineId, String clientRequestId,
                             OutsourceMovement.Type action) {
        String key = trimToNull(clientRequestId);
        if (key == null) {
            // 收发写操作必须带提交编号: 不带就没有防重, 网络重试会重复耗料 / 重复挂应付 (终审 中6)。
            throw bad(400, "缺少提交编号 clientRequestId (每次提交生成一个, 重试时沿用)",
                    "OUTSOURCE_REQUEST_ID_REQUIRED", "clientRequestId");
        }
        if (key.length() > 64) {
            throw bad(400, "clientRequestId 太长 (最多 64)", "OUTSOURCE_REQUEST_ID_INVALID", "clientRequestId");
        }
        Optional<OutsourceMovement> hit = movementRepository.findFirstByFactoryIdAndClientRequestId(factoryId, key);
        if (hit.isEmpty()) {
            return false;
        }
        if (!orderId.equals(hit.get().getOrderId())) {
            throw bad(409, "这个提交编号已用于另一张委外单", "OUTSOURCE_REQUEST_ID_CONFLICT", "clientRequestId");
        }
        if (hit.get().getMovementType() != action) {
            // 同一编号先用于发料、再用于收货: 当成重放会静默返回、什么都不做 (复审 低4)。
            throw bad(409, "这个提交编号已用于另一种操作 (" + hit.get().getMovementType() + "), 请重新提交",
                    "OUTSOURCE_REQUEST_ID_CONFLICT", "clientRequestId");
        }
        if (!Objects.equals(lineId, hit.get().getLineId())) {
            // 同一编号、同一单、同一动作用在另一行 (第 1 行收货后拿同号收第 2 行): 当成重放会静默吃掉第 2 行 (复审 低3)。
            throw bad(409, "这个提交编号已用于本单的另一行任务, 请重新提交",
                    "OUTSOURCE_REQUEST_ID_CONFLICT", "clientRequestId");
        }
        log.info("委外收发幂等命中: factory={} order={} line={} requestId={}", factoryId, orderId, lineId, key);
        return true;
    }

    private void requireReleased(OutsourceOrder order) {
        if (order.getStatus() != OutsourceOrder.Status.RELEASED) {
            throw bad(409, "只有已下达的委外单能收发, 当前状态: " + order.getStatus(), "OUTSOURCE_ORDER_NOT_RELEASED", null);
        }
    }

    private OutsourceOrderLine requireLine(OutsourceOrder order, String lineId) {
        OutsourceOrderLine line = lineRepository.findByIdAndFactoryId(lineId, order.getFactoryId())
                .filter(l -> order.getId().equals(l.getOrderId()))
                .orElseThrow(() -> new ResourceNotFoundException("任务行不存在或不属于这张委外单"));
        if (line.getStatus() == OutsourceOrderLine.Status.CANCELLED) {
            throw bad(409, "任务行已取消", "OUTSOURCE_LINE_CANCELLED", lineId);
        }
        return line;
    }

    private OutsourceOrderLine requireOpenLine(OutsourceOrder order, String lineId) {
        OutsourceOrderLine line = requireLine(order, lineId);
        if (line.getStatus() != OutsourceOrderLine.Status.OPEN) {
            throw bad(409, "任务行已完成, 不能再发料或收货", "OUTSOURCE_LINE_NOT_OPEN", lineId);
        }
        return line;
    }

    private List<BatchQuantity> requireItems(List<BatchQuantity> items) {
        if (items == null || items.isEmpty()) {
            throw bad(400, "请至少选一个批次", "OUTSOURCE_ITEMS_REQUIRED", "items");
        }
        Set<String> seen = new HashSet<>();
        for (BatchQuantity it : items) {
            if (it == null || isBlank(it.batchId()) || it.quantity() == null || it.quantity().signum() <= 0) {
                throw bad(400, "每个批次都要有批次和大于 0 的数量", "OUTSOURCE_ITEM_INVALID", "items");
            }
            if (!seen.add(it.batchId())) {
                throw bad(400, "同一批次出现了两次, 请合并数量", "OUTSOURCE_ITEM_DUPLICATED", it.batchId());
            }
        }
        return items;
    }

    private MaterialBatch lockOutsourceBatch(String factoryId, OutsourceOrderLine line, String batchId) {
        MaterialBatch os = materialBatchRepository.findByIdAndFactoryIdForUpdate(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("委外仓批次不存在: " + batchId));
        if (!DOC_ISSUE.equals(os.getSourceDocType()) || !line.getId().equals(os.getSourceDocId())) {
            throw bad(409, "批次 " + os.getBatchNumber() + " 不是这一行发到委外厂的料", "OUTSOURCE_BATCH_NOT_IN_LINE", batchId);
        }
        return os;
    }

    private static void assertWithinBalance(MaterialBatch os, BigDecimal q, String action) {
        if (os.getCurrentQuantity().compareTo(q) < 0) {
            throw bad(409, String.format("委外厂批次 %s 结存 %s, 不够%s %s", os.getBatchNumber(),
                    plain(os.getCurrentQuantity()), action, plain(q)), "OUTSOURCE_BALANCE_INSUFFICIENT", os.getId());
        }
    }

    private void consumeOutsourceBatch(MaterialBatch os, BigDecimal q) {
        os.setUsedQuantity(nz(os.getUsedQuantity()).add(q));
        if (os.getCurrentQuantity().signum() <= 0) {
            os.setStatus(MaterialBatchStatus.DEPLETED);
        }
        materialBatchRepository.save(os);
    }

    private void writeConsumption(String factoryId, MaterialBatch os, BigDecimal q, String sourceType, String notes,
                                  Long userId) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(factoryId);
        c.setBatchId(os.getId());
        c.setMaterialTypeId(os.getMaterialTypeId());
        c.setQuantity(q);
        BigDecimal price = os.getUnitPrice() != null ? os.getUnitPrice() : ZERO;
        c.setUnitPrice(price);
        c.setTotalCost(money(price.multiply(q)));
        LocalDateTime now = LocalDateTime.now();
        c.setConsumptionTime(now);
        c.setConsumedAt(now);
        c.setRecordedBy(userId);
        c.setSourceType(sourceType);
        c.setNotes(notes);
        materialConsumptionRepository.save(c);
    }

    List<MaterialBatch> outsourceBatches(String factoryId, String lineId) {
        return materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocIdOrderByBatchNumberAsc(
                factoryId, DOC_ISSUE, lineId);
    }

    /** 多行委外结存一次查 (复审 低2)。 */
    private Map<String, BigDecimal> outsourceBalances(String factoryId, Collection<OutsourceOrderLine> lines) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (lines == null || lines.isEmpty()) {
            return out;
        }
        Set<String> lineIds = lines.stream().map(OutsourceOrderLine::getId).collect(Collectors.toSet());
        for (MaterialBatch b : materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocIdIn(
                factoryId, DOC_ISSUE, lineIds)) {
            out.merge(b.getSourceDocId(), nz(b.getCurrentQuantity()), BigDecimal::add);
        }
        return out;
    }

    BigDecimal outsourceBalance(String factoryId, String lineId) {
        BigDecimal s = ZERO;
        for (MaterialBatch b : outsourceBatches(factoryId, lineId)) {
            s = s.add(b.getCurrentQuantity());
        }
        return s;
    }

    private void touchTaskStarted(String factoryId, OutsourceOrderLine line) {
        if (line.getWorkProcessTaskId() == null) {
            return;
        }
        taskRepository.findByFactoryIdAndIdForUpdate(factoryId, line.getWorkProcessTaskId()).ifPresent(t -> {
            if (t.getStatus() == WorkProcessTask.Status.PENDING) {
                t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
                t.setActualStartAt(LocalDateTime.now());
                taskRepository.save(t);
            }
        });
    }

    private void syncTask(String factoryId, OutsourceOrderLine line, BigDecimal goodDelta) {
        if (line.getWorkProcessTaskId() == null) {
            return;
        }
        taskRepository.findByFactoryIdAndIdForUpdate(factoryId, line.getWorkProcessTaskId()).ifPresent(t -> {
            if (t.getStatus() != null && t.getStatus().isTerminal()) {
                return;
            }
            if (t.getStatus() == WorkProcessTask.Status.PENDING) {
                t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
                t.setActualStartAt(LocalDateTime.now());
            }
            BigDecimal actual = nz(t.getActualQuantity()).add(goodDelta);
            t.setActualQuantity(actual.signum() < 0 ? ZERO : actual);
            t.setOutsourceFeeAmount(effectiveFee(factoryId, line));
            taskRepository.save(t);
        });
    }

    private OutsourceMovement movement(OutsourceOrder order, OutsourceOrderLine line, OutsourceMovement.Type type,
                                       LocalDate bizDate, Long userId) {
        OutsourceMovement m = new OutsourceMovement();
        m.setId(UUID.randomUUID().toString());
        m.setFactoryId(order.getFactoryId());
        m.setOrderId(order.getId());
        m.setLineId(line.getId());
        m.setMovementType(type);
        m.setBizDate(bizDate);
        m.setCreatedBy(userId);
        return m;
    }

    private static OutsourceMovement.BatchKind kindOf(OutsourceOrderLine line) {
        return line.getOutputKind() == OutsourceOrderLine.OutputKind.FINISHED
                ? OutsourceMovement.BatchKind.FINISHED : OutsourceMovement.BatchKind.MATERIAL;
    }

    private Map<String, String> supplierNames(String factoryId, Set<String> ids) {
        Map<String, String> out = new HashMap<>();
        for (String id : ids) {
            if (id != null) {
                supplierRepository.findByIdAndFactoryId(id, factoryId).ifPresent(s -> out.put(id, s.getName()));
            }
        }
        return out;
    }

    private Map<String, String> batchNumbers(String factoryId, List<OutsourceMovement> movements) {
        Set<String> material = new HashSet<>();
        Set<String> finished = new HashSet<>();
        for (OutsourceMovement m : movements) {
            boolean targetFinished = m.getTargetBatchKind() == OutsourceMovement.BatchKind.FINISHED;
            boolean sourceFinished = m.getMovementType() == OutsourceMovement.Type.GOODS_RETURN
                    || (m.getMovementType() == OutsourceMovement.Type.CONSUME && REWORK_MARK.equals(m.getReason()));
            if (m.getSourceBatchId() != null) {
                (sourceFinished ? finished : material).add(m.getSourceBatchId());
            }
            if (m.getTargetBatchId() != null) {
                (targetFinished ? finished : material).add(m.getTargetBatchId());
            }
        }
        Map<String, String> out = new HashMap<>();
        material.addAll(finished);   // 半成品退货的源是物料批次: 两边都查一遍, 查不到的不显示
        for (MaterialBatch b : materialBatchRepository.findAllById(material)) {
            if (factoryId.equals(b.getFactoryId())) {
                out.put(b.getId(), b.getBatchNumber());
            }
        }
        for (FinishedGoodsBatch b : finishedGoodsBatchRepository.findAllById(material)) {
            if (factoryId.equals(b.getFactoryId())) {
                out.put(b.getId(), b.getBatchNumber());
            }
        }
        return out;
    }

    private static String batchNumber(String base, String tag) {
        String b = base == null || base.isBlank() ? "OS" : base;
        String candidate = b + "-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        return candidate.length() > 64 ? candidate.substring(candidate.length() - 64) : candidate;
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> f) {
        BigDecimal s = ZERO;
        for (T r : rows) {
            s = s.add(nz(f.apply(r)));
        }
        return s;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field, String message) {
        if (raw == null || raw.isBlank()) {
            throw bad(400, message, "OUTSOURCE_FIELD_INVALID", field);
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad(400, message + ": " + raw, "OUTSOURCE_FIELD_INVALID", field);
        }
    }

    private static void requireActor(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
    }

    static BusinessException bad(int status, String message, String code, String target) {
        BusinessException e = new BusinessException(status, message).withCode(code).withSeverity("BLOCKING");
        return target == null ? e : e.withHintTarget(target);
    }

    private static LocalDate bizDate(LocalDate d) {
        return d != null ? d : LocalDate.now();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : ZERO;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
