package com.cretas.aims.service;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.inventory.FinishedGoodsAdjustmentLog;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.DisposalRecordRepository;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import com.cretas.aims.service.inventory.ReturnedGoodsCostResolver;
import com.cretas.aims.service.finance.DisposalCostAccountingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 报废记录服务层实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisposalRecordService implements IDisposalRecordService {

    private final DisposalRecordRepository disposalRecordRepository;

    /** 报损审批通过时扣减原料批次库存 + 写 MaterialBatchAdjustment 留痕。 */
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;

    /** 报损审批通过时扣减成品(生产)批次库存。 */
    private final ProductionBatchRepository productionBatchRepository;

    /**
     * R12: 成品报损扣减可售成品库存 (FinishedGoodsBatch) + 写调整日志留痕。
     * 镜像 SalesServiceImpl.adjustFinishedGoodsQuantity 的 SCRAP 调整语义 (减 producedQuantity)。
     */
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;

    /** 唯一有实物扣减与费用过账口径的处置方式 (approveDisposal / DisposalCostAccountingService 同一集合)。 */
    private static final Set<String> PHYSICAL_DISPOSAL_TYPES = Set.of("SCRAP", "DESTROY");

    /**
     * 客户自有成品退还客户 (Steve 2026-10-28 裁定: 召回/退回的客户货可退还客户, 也可经客户同意报废)。
     * 与报废同一条「申请 → 审批 → 扣库」链, 但只用于客户自有成品, 不挂应收、不过成本凭证。
     */
    static final String RETURN_TO_CUSTOMER = "RETURN_TO_CUSTOMER";

    private static final Set<String> SUPPORTED_DISPOSAL_TYPES = Set.of("SCRAP", "DESTROY", RETURN_TO_CUSTOMER);

    /** Damaged/expired/QC-held goods can be scrapped while physical free stock remains. */
    private static final Set<String> DISPOSAL_ELIGIBLE_FG_STATUSES = Set.of(
            FinishedGoodsBatch.Status.AVAILABLE, FinishedGoodsBatch.Status.DEFECTIVE,
            FinishedGoodsBatch.Status.EXPIRED, FinishedGoodsBatch.Status.FROZEN);

    /** SP12 §5.3: 可选工作流引擎（无配置时降级运行，不抛 NPE）。 */
    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;

    /** Optional to preserve lightweight service tests; required in the live Spring context. */
    @Autowired(required = false)
    private ReturnedGoodsCostResolver returnedGoodsCostResolver;

    /** SCRAP/DESTROY 的库存与费用凭证边界；可选仅为保持旧单测的轻量构造兼容。 */
    @Autowired
    private DisposalCostAccountingService disposalCostAccountingService;

    /**
     * 创建报废记录
     */
    @Override
    @Transactional
    public DisposalRecord createDisposalRecord(DisposalRecord record) {
        // 批准只认 SCRAP/DESTROY (实物扣减与费用过账口径); 其它值建得出来却永远批不了,
        // web 表单曾把「过期/损坏/质量问题」当处置方式提交, prod 上整条报废链从没走通。
        assertSupportedDisposalType(record.getDisposalType());
        assertSingleInventoryTarget(record);
        if (record.getFinishedGoodsBatchId() != null && !record.getFinishedGoodsBatchId().isBlank()) {
            assertFinishedGoodsDisposalBalance(record);
        } else if (RETURN_TO_CUSTOMER.equals(record.getDisposalType())) {
            throw returnToCustomerOnlyForCustomerGoods();
        }
        // 设置默认值
        if (record.getDisposalDate() == null) {
            record.setDisposalDate(LocalDateTime.now());
        }
        if (record.getIsApproved() == null) {
            record.setIsApproved(false);
        }
        log.info("创建报废记录: 工厂={}, 类型={}", record.getFactoryId(), record.getDisposalType());
        return hydrateReadback(disposalRecordRepository.save(record));
    }

    /** The selector uses the same free-stock definition as approval: produced - shipped - reserved. */
    @Transactional(readOnly = true)
    public List<EligibleFinishedGoodsBatch> getEligibleFinishedGoods(String factoryId) {
        return finishedGoodsBatchRepository.findDisposalEligibleByFactoryId(
                        factoryId, DISPOSAL_ELIGIBLE_FG_STATUSES).stream()
                .map(batch -> new EligibleFinishedGoodsBatch(batch.getId(), batch.getBatchNumber(),
                        batch.getProductName(), batch.getUnit(), batch.getAvailableQuantity(),
                        batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED))
                .toList();
    }

    /** customerOwned: 前端据此切换处置方式 (退还客户 / 客户同意报废) 并要求客户确认。 */
    public record EligibleFinishedGoodsBatch(String id, String batchNumber, String productName,
                                              String unit, BigDecimal availableQuantity,
                                              boolean customerOwned) {}

    private static void assertSupportedDisposalType(String disposalType) {
        // 批准只认 SCRAP/DESTROY/RETURN_TO_CUSTOMER; 其它值建得出来却永远批不了 ——
        // web 表单曾把「过期/损坏/质量问题」当处置方式提交, prod 上整条报废链从没走通。
        if (disposalType == null || !SUPPORTED_DISPOSAL_TYPES.contains(disposalType)) {
            throw new BusinessException(400, "处置方式只支持报废、销毁或退还客户")
                    .withCode("DISPOSAL_TYPE_UNSUPPORTED")
                    .withHint("请选择处置方式「报废」或「销毁」(客户自有货还可「退还客户」)，过期、损坏、质量问题等原因写在废弃原因里")
                    .withHintTarget("disposalType");
        }
    }

    private static BusinessException returnToCustomerOnlyForCustomerGoods() {
        return new BusinessException(400, "只有客户自有成品可以退还客户")
                .withCode("DISPOSAL_RETURN_TO_CUSTOMER_COMPANY_GOODS")
                .withHint("公司自有库存请选择「报废」或「销毁」")
                .withHintTarget("disposalType");
    }

    /**
     * 客户自有货: 退还需要签收、报废/销毁需要客户同意 —— 三项缺一不可 (人、日期、凭证)。
     * 公司自有货: 不允许退还客户。
     */
    private static void assertOwnershipDisposalRules(DisposalRecord record, FinishedGoodsBatch batch) {
        boolean customerOwned = batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED;
        if (!customerOwned) {
            if (RETURN_TO_CUSTOMER.equals(record.getDisposalType())) throw returnToCustomerOnlyForCustomerGoods();
            return;
        }
        if (FinishedGoodsBatch.Status.AVAILABLE.equals(batch.getStatus())) {
            throw new BusinessException(409, "客户自有的可售成品请走代加工发货")
                    .withCode("DISPOSAL_CUSTOMER_GOODS_SELLABLE")
                    .withHint("只有退回、不良、过期或冻结的客户货才在这里退还或报废；可售的代加工成品发货时才会收加工费");
        }
        boolean returning = RETURN_TO_CUSTOMER.equals(record.getDisposalType());
        String what = returning ? "客户签收" : "客户同意报废";
        if (isBlank(record.getCustomerConfirmedBy())) {
            throw customerConfirmationRequired(what + "人", "customerConfirmedBy");
        }
        if (record.getCustomerConfirmedAt() == null) {
            throw customerConfirmationRequired(what + "日期", "customerConfirmedAt");
        }
        if (isBlank(record.getCustomerConfirmationEvidence())) {
            throw customerConfirmationRequired(returning ? "签收单照片" : "客户同意凭证", "customerConfirmationEvidence");
        }
    }

    private static BusinessException customerConfirmationRequired(String field, String target) {
        return new BusinessException(400, "客户自有成品缺少" + field)
                .withCode("DISPOSAL_CUSTOMER_CONFIRMATION_REQUIRED")
                .withHint("客户自有货只能在客户确认后处置：请补「" + field + "」")
                .withHintTarget(target);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void assertFinishedGoodsDisposalBalance(DisposalRecord record) {
        FinishedGoodsBatch batch = finishedGoodsBatchRepository.findById(record.getFinishedGoodsBatchId())
                .orElseThrow(() -> new BusinessException(404, "报损成品批次不存在"));
        if (record.getFactoryId() == null || !record.getFactoryId().equals(batch.getFactoryId())) {
            throw new BusinessException(403, "无权操作该成品批次");
        }
        BigDecimal quantity = record.getDisposalQuantity();
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessException(422, "报损数量必须大于0");
        }
        BigDecimal available = batch.getAvailableQuantity();
        assertOwnershipDisposalRules(record, batch);
        if (batch.getStatus() == null || !DISPOSAL_ELIGIBLE_FG_STATUSES.contains(batch.getStatus())
                || available == null || available.signum() <= 0
                || quantity.compareTo(available) > 0) {
            throw new BusinessException(409, "成品批次可报损余量不足，已发货和已预留数量不可报损")
                    .withHint("请刷新批次列表并只选择未发货、未预留的余量")
                    .withHintTarget("disposalQuantity");
        }
    }

    private void assertSingleInventoryTarget(DisposalRecord record) {
        if (record.getMaterialBatchId() != null && !record.getMaterialBatchId().isBlank()
                && record.getFinishedGoodsBatchId() != null && !record.getFinishedGoodsBatchId().isBlank()) {
            throw new BusinessException(422, "报废申请不能同时关联原料和成品批次");
        }
    }

    private DisposalRecord findForMutation(String factoryId, Long id) {
        Optional<DisposalRecord> locked = disposalRecordRepository.findByIdAndFactoryIdForUpdate(id, factoryId);
        if (locked.isPresent()) {
            return locked.get();
        }
        disposalRecordRepository.findById(id).ifPresent(record -> assertSameFactory(record, factoryId));
        throw new BusinessException(404, "报废记录不存在: " + id);
    }

    /**
     * 多租户隔离守卫: 报废记录必须属于路径 factoryId, 否则 403。
     *
     * <p>修复 (审计 round2): 这些按 id 加载记录的写/读方法此前不校验记录归属工厂 →
     * JwtAuthInterceptor 只验用户拥有路径 factoryId, 但记录 id 可枚举(Long 自增) →
     * F006 用户可读/改/审批/删除/提交其它工厂的报废记录 (跨租户)。同
     * feedback_interceptor_excluded_path_factory_guard_gap。</p>
     */
    private void assertSameFactory(DisposalRecord record, String factoryId) {
        if (record.getFactoryId() == null || !record.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该报废记录")
                    .withHint("该记录不属于当前工厂");
        }
    }

    /**
     * 报废审批角色守卫 (F-BUG-5)。
     *
     * <p>转录硬约束: 仓库报损/报废须财务审批。此前 controller 仅 {@code @RequirePermission
     * ("warehouse:read_write")} → 仓管员可自批报废扣库存。改为强制财务/工厂管理员角色,
     * 对齐 {@link com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl}。
     *
     * <p>角色码为小写 (JWT/request attribute "role" 携带 factory_super_admin 等)。
     */
    private static final java.util.Set<String> DISPOSAL_APPROVAL_ROLES = java.util.Set.of(
            "finance_manager", "factory_super_admin", "platform_super_admin", "platform_admin");

    private void assertApprovalRole(String requestRole) {
        String normalized = requestRole == null ? "" : requestRole.toLowerCase();
        if (!DISPOSAL_APPROVAL_ROLES.contains(normalized)) {
            throw new BusinessException(403,
                    "报废审批需要财务经理或工厂管理员角色，当前角色：" + requestRole)
                    .withHint("请联系财务经理 (finance_manager) 或工厂超管审批");
        }
    }

    /**
     * 审批报废记录 (通过 → APPROVED, <b>真实扣减库存 + 记损失金额</b>)。
     *
     * <p>转录硬约束 (6.9 行337 "报损成本增加" / 行520 "所有库存变动必须财务审批"):
     * 报损审批通过应真扣对应批次库存并写入实际损失金额 {@code actualLoss}。此前仅置
     * {@code approvalStatus=APPROVED} (前端 dialog 与 javadoc 声称的 "扣减库存" 是 aspirational)。
     *
     * <p>按关联批次类型分流扣减:
     * <ul>
     *   <li>{@code materialBatchId} 非空 (原料报损) → MaterialBatch 按可用量 currentQuantity
     *       (= receiptQuantity - usedQuantity - reservedQuantity) 把关, 增 usedQuantity 扣减
     *       (与正常领料 {@code MaterialBatchServiceImpl.useBatchQuantity} 一致) +
     *       写 MaterialBatchAdjustment 负数留痕, 单价取 batch.unitPrice。</li>
     *   <li>{@code finishedGoodsBatchId} 非空 (成品报损, R12) → FinishedGoodsBatch 按可用量
     *       getAvailableQuantity() (= producedQuantity - shippedQuantity - reservedQuantity) 把关,
     *       减 producedQuantity 扣减 (镜像 SalesServiceImpl.adjustFinishedGoodsQuantity 的 SCRAP
     *       调整语义) + 写 FinishedGoodsAdjustmentLog SCRAP 留痕, 成本取 batch.unitCost, 扣空标 DEPLETED。</li>
     *   <li>{@code productionBatchId} 非空但无 {@code finishedGoodsBatchId} (旧数据) → H2 安全阻止
     *       (422): ProductionBatch.quantity 是产出额定值非可售库存, 无法可靠定位 FG 批次 →
     *       不扣错表, 抛 422 明确阻止 (向后兼容)。</li>
     * </ul>
     * 库存不足 → 409 阻止审批 (不允许扣成负)。幂等: 仅 PENDING 可审批, 扣库存只发生一次。
     *
     * <p>F-BUG-4: 审批人 {@code approverId} / {@code approverName} 由调用方 (controller)
     * 从 JWT token 取, 不再信任请求体。F-BUG-5: 强制财务/工厂管理员角色。
     */
    @Override
    @Transactional
    public DisposalRecord approveDisposal(String factoryId, Long id, Integer approverId,
            String approverName, String requestRole) {
        assertApprovalRole(requestRole);  // F-BUG-5: 财务角色守卫
        disposalCostAccountingService.lockFactory(factoryId);
        DisposalRecord record = findForMutation(factoryId, id);
        assertSingleInventoryTarget(record);

        if (record.getDisposalType() == null || !SUPPORTED_DISPOSAL_TYPES.contains(record.getDisposalType())) {
            throw new BusinessException(409, "仅报废、销毁或退还客户支持实物扣减；其它处置方式需先确定财务口径")
                    .withCode("DISPOSAL_ACCOUNTING_POLICY_REQUIRED")
                    .withHint("请由财务确认处置方式的过账口径，实际报废或销毁的记录可从报损审批继续");
        }
        if (RETURN_TO_CUSTOMER.equals(record.getDisposalType())
                && (record.getFinishedGoodsBatchId() == null || record.getFinishedGoodsBatchId().isBlank())) {
            throw returnToCustomerOnlyForCustomerGoods();
        }

        // 幂等防重 (2026-06-12, Codex Gate2): 已审批/已拒绝 (终态) 不可重复操作.
        // 审批触发库存扣减, 重复 approve 会重复扣库存. 返 409.
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "报废记录已处理 (" + record.getStatus() + "), 请勿重复操作")
                    .withHint("查看记录状态").withHintTarget("status");
        }

        // 真实扣减库存 + 计算实际损失 (同一事务, 库存不足抛 409 阻止审批)
        BigDecimal actualLoss = applyDisposalToInventory(record, approverId, requestRole);
        record.setActualLoss(actualLoss);

        // 客户自有货 (退还客户 / 客户同意报废) 不是本厂资产: 只扣库存, 不过报废损失凭证。
        // 公司货与库存扣减处于同一事务；成本/期间/凭证任一失败都会回滚前面的库存写入。
        if (!isCustomerOwnedFinishedGoods(record)) {
            disposalCostAccountingService.postApprovedDisposal(record,
                    actualLoss, approverId == null ? null : approverId.longValue());
        }

        record.approve(approverId, approverName);
        log.info("审批报废记录: id={}, 审批人={} ({}), 实际损失={}", id, approverName, approverId, actualLoss);
        return hydrateReadback(disposalRecordRepository.save(record));
    }

    /**
     * 按关联批次类型扣减对应库存, 返回实际损失金额 (数量 × 批次单价)。
     *
     * <p>红线: 复用现有扣减写法 (WastageReportServiceImpl.applyWastageToInventory)。
     * 库存不足 → 409 阻止审批。成品批次必须有真实 unitCost（历史销售退货只接受精确来源证据补齐），
     * 未知成本 → 409 且不扣库存。无关联批次 (仅质检/返工关联, 无 material/production 批次)
     * → 不扣库存, actualLoss 取估损 estimatedLoss 兜底。
     */
    private BigDecimal applyDisposalToInventory(DisposalRecord record, Integer approverId,
            String requestRole) {
        BigDecimal qty = record.getDisposalQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(422, "报损数量必须大于0")
                    .withHint("请核实报损数量").withHintTarget("disposalQuantity");
        }
        BigDecimal disposalQty = qty.setScale(2, RoundingMode.HALF_UP);

        // 原料报损 → 扣 MaterialBatch (按可用量 currentQuantity 把关, 增 usedQuantity 扣减) + 写 adjustment 负数
        if (record.getMaterialBatchId() != null && !record.getMaterialBatchId().isBlank()) {
            MaterialBatch batch = materialBatchRepository.findById(record.getMaterialBatchId())
                    .orElseThrow(() -> new BusinessException(404,
                            "报损批次不存在: " + record.getMaterialBatchId()));
            // 跨租户: 批次必须属于同工厂
            if (batch.getFactoryId() == null || !batch.getFactoryId().equals(record.getFactoryId())) {
                throw new BusinessException(403, "无权操作该批次")
                        .withHint("批次不属于当前工厂");
            }

            // H1 (审计 round2): 库存不足判定 + 扣减必须用真实可用量
            // currentQuantity = receiptQuantity - usedQuantity - reservedQuantity (MaterialBatch.getCurrentQuantity),
            // 不能只看 receiptQuantity —— 否则会扣进别人已领用(usedQuantity)/已预留(reservedQuantity)的量,
            // 把 currentQuantity 扣成负数(库存错乱). 扣减方式与正常领料一致(MaterialBatchServiceImpl.useBatchQuantity):
            // 增 usedQuantity 而非减 receiptQuantity, 使 currentQuantity 正确反映 + 与领料模型一致.
            // 注: WastageReportServiceImpl 此前有同款 receiptQuantity-only gate 隐患, 已修
            //     (applyWastageToInventory 改用 currentQuantity 把关 + 增 usedQuantity, 与本范式一致).
            BigDecimal available = batch.getCurrentQuantity() != null
                    ? batch.getCurrentQuantity() : BigDecimal.ZERO;
            if (available.subtract(disposalQty).signum() < 0) {
                throw new BusinessException(409,
                        "报损数量 " + disposalQty + " 超过批次可用库存 " + available + ", 无法报损")
                        .withHint("请核实报损数量，当前批次可用库存: " + available)
                        .withHintTarget("disposalQuantity");
            }

            BigDecimal usedBefore = batch.getUsedQuantity() != null
                    ? batch.getUsedQuantity() : BigDecimal.ZERO;
            BigDecimal usedAfter = usedBefore.add(disposalQty);
            BigDecimal availableAfter = available.subtract(disposalQty);

            // adjustment 留痕: before/after 取可用量(currentQuantity), 负数变更, 与扣减语义一致
            MaterialBatchAdjustment adj = new MaterialBatchAdjustment();
            adj.setId(UUID.randomUUID().toString());
            adj.setMaterialBatchId(batch.getId());
            adj.setAdjustmentType("DISPOSAL");
            adj.setQuantityBefore(available.setScale(2, RoundingMode.HALF_UP));
            adj.setAdjustmentQuantity(disposalQty.negate()); // 负数
            adj.setQuantityAfter(availableAfter.setScale(2, RoundingMode.HALF_UP));
            adj.setReason("报损审批 [id=" + record.getId() + "] 类型: " + record.getDisposalType()
                    + (record.getDisposalReason() != null ? " - " + record.getDisposalReason() : ""));
            adj.setAdjustmentTime(LocalDateTime.now());
            adj.setAdjustedBy(approverId != null ? approverId.longValue() : null);
            adj.setNotes("disposalRecordId=" + record.getId() + " approverRole=" + requestRole);
            materialBatchAdjustmentRepository.save(adj);

            batch.setUsedQuantity(usedAfter.setScale(2, RoundingMode.HALF_UP));
            batch.setLastUsedAt(LocalDateTime.now());
            if (availableAfter.compareTo(BigDecimal.ZERO) <= 0) {
                batch.setStatus(MaterialBatchStatus.USED_UP);
            }
            materialBatchRepository.save(batch);

            return valuate(disposalQty, batch.getUnitPrice(), record.getEstimatedLoss());
        }

        // 成品报损 (R12) → 优先用 finishedGoodsBatchId 扣减 FinishedGoodsBatch 可售库存.
        //
        // 扣减方式: 镜像 SalesServiceImpl.adjustFinishedGoodsQuantity 的 SCRAP 调整语义 ——
        // 减 producedQuantity (而非加 shippedQuantity). 理由: shippedQuantity 语义是【已发货/已售】,
        // 报损品从未售出, 加 shipped 会虚增发货/销售分析口径; 减 producedQuantity = 成品库存"凭空消失"
        // 的正确语义 (同 adjust SCRAP), 且写 FinishedGoodsAdjustmentLog(referenceType=SCRAP) 可审计.
        // 可用量 getAvailableQuantity() = producedQuantity - shippedQuantity - reservedQuantity 把关,
        // 不足 → 409 (不扣成负, 不动已发/已预留部分). 扣空 → 标 DEPLETED.
        if (record.getFinishedGoodsBatchId() != null && !record.getFinishedGoodsBatchId().isBlank()) {
            Optional<FinishedGoodsBatch> lockedBatch = finishedGoodsBatchRepository
                    .findByIdAndFactoryIdForUpdate(record.getFinishedGoodsBatchId(), record.getFactoryId());
            if (lockedBatch.isEmpty()) {
                finishedGoodsBatchRepository.findById(record.getFinishedGoodsBatchId()).ifPresent(batch -> {
                    if (!record.getFactoryId().equals(batch.getFactoryId())) {
                        throw new BusinessException(403, "无权操作该成品批次");
                    }
                });
                throw new BusinessException(404, "报损成品批次不存在: " + record.getFinishedGoodsBatchId());
            }
            FinishedGoodsBatch fgBatch = lockedBatch.get();
            // 跨租户: 批次必须属于同工厂
            if (fgBatch.getFactoryId() == null || !fgBatch.getFactoryId().equals(record.getFactoryId())) {
                throw new BusinessException(403, "无权操作该成品批次")
                        .withHint("批次不属于当前工厂");
            }

            boolean customerOwned = fgBatch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED;
            // 审批时按锁定后的批次再核一次归属规则 (申请后批次归属不会变, 但审批是最后一道闸)。
            assertOwnershipDisposalRules(record, fgBatch);
            if (fgBatch.getStatus() == null || !DISPOSAL_ELIGIBLE_FG_STATUSES.contains(fgBatch.getStatus())) {
                throw new BusinessException(409, "成品批次当前不可报损，请刷新库存状态")
                        .withHint("请重新选择仍有可报损余量的成品批次");
            }

            BigDecimal available = fgBatch.getAvailableQuantity() != null
                    ? fgBatch.getAvailableQuantity() : BigDecimal.ZERO;
            if (available.subtract(disposalQty).signum() < 0) {
                throw new BusinessException(409,
                        "报损数量 " + disposalQty + " 超过成品批次可用库存 " + available + ", 无法报损")
                        .withHint("请核实报损数量，当前成品批次可用库存: " + available)
                        .withHintTarget("disposalQuantity");
            }

            BigDecimal beforeProduced = fgBatch.getProducedQuantity() != null
                    ? fgBatch.getProducedQuantity() : BigDecimal.ZERO;
            BigDecimal afterProduced = beforeProduced.subtract(disposalQty);

            BigDecimal priorUnitCost = fgBatch.getUnitCost();
            BigDecimal unitCost = priorUnitCost;
            ReturnedGoodsCostResolver.ResolvedCost resolvedCost = null;
            if (customerOwned) {
                // 客户货不计本厂损失, 也不要求本厂成本: 只留扣减痕迹。
                // 召回后续按 "报损审批 [id=" 前缀 + referenceType 认处置证据, 两种处置都沿用该前缀。
                String reason = "报损审批 [id=" + record.getId() + "] 类型: " + record.getDisposalType()
                        + (record.getDisposalReason() != null ? " - " + record.getDisposalReason() : "")
                        + "; 客户自有; 客户确认=" + record.getCustomerConfirmedBy()
                        + " " + record.getCustomerConfirmedAt() + "; unit=" + fgBatch.getUnit();
                finishedGoodsAdjustmentLogRepository.save(FinishedGoodsAdjustmentLog.builder()
                        .factoryId(fgBatch.getFactoryId())
                        .batchId(fgBatch.getId())
                        .adjustmentQuantity(disposalQty.negate())
                        .beforeProduced(beforeProduced)
                        .afterProduced(afterProduced)
                        .reason(reason)
                        .referenceType(RETURN_TO_CUSTOMER.equals(record.getDisposalType())
                                ? RETURN_TO_CUSTOMER : "SCRAP")
                        .operatorId(approverId != null ? approverId.longValue() : null)
                        .build());
                deductFinishedGoods(fgBatch, afterProduced);
                return BigDecimal.ZERO;
            }
            if (unitCost == null && returnedGoodsCostResolver != null) {
                resolvedCost = returnedGoodsCostResolver.resolve(record.getFactoryId(), fgBatch).orElse(null);
                if (resolvedCost != null) {
                    unitCost = resolvedCost.unitCost();
                }
            }
            if (unitCost == null || unitCost.signum() < 0) {
                throw new BusinessException(409, "成品批次缺少真实单位成本，无法报损")
                        .withCode("FINISHED_GOODS_UNIT_COST_REQUIRED")
                        .withHint("请先补齐该成品批次或已完成销售退货的真实 unitCost，系统不会用售价或估价代替");
            }
            if (resolvedCost != null) {
                // Persist only a validated non-negative recovered cost in the
                // same transaction as the first disposal adjustment.
                fgBatch.setUnitCost(unitCost);
            }

            // 调整日志留痕 (referenceType=SCRAP, 负数变更, before/after 取 producedQuantity, 与扣减语义一致)
            String reason = "报损审批 [id=" + record.getId() + "] 类型: " + record.getDisposalType()
                    + (record.getDisposalReason() != null ? " - " + record.getDisposalReason() : "")
                    + "; unitCostBefore=" + priorUnitCost + "; unitCostAfter=" + unitCost
                    + "; unit=" + fgBatch.getUnit();
            if (resolvedCost != null) {
                reason += "; costSourceBatchId=" + resolvedCost.sourceBatchId()
                        + "; costSourceBatch=" + resolvedCost.sourceBatchNumber()
                        + "; returnNumber=" + resolvedCost.returnNumber()
                        + "; returnItemIndex=" + resolvedCost.itemIndex();
            }
            FinishedGoodsAdjustmentLog logEntry = FinishedGoodsAdjustmentLog.builder()
                    .factoryId(fgBatch.getFactoryId())
                    .batchId(fgBatch.getId())
                    .adjustmentQuantity(disposalQty.negate()) // 负数
                    .beforeProduced(beforeProduced)
                    .afterProduced(afterProduced)
                    .reason(reason)
                    .referenceType("SCRAP")
                    .operatorId(approverId != null ? approverId.longValue() : null)
                    .build();
            finishedGoodsAdjustmentLogRepository.save(logEntry);
            deductFinishedGoods(fgBatch, afterProduced);
            return valuateFinishedGoodsCost(disposalQty, unitCost);
        }

        // 成品报损 → H2 (审计 round2): 仅有 productionBatchId 无 finishedGoodsBatchId → 安全阻止, 不扣错表.
        //
        // 此前扣 ProductionBatch.quantity, 但那是生产批次的【产出额定值】(创建时设, 从不被发货/销售扣减,
        // 还有 @Positive 校验, 扣到 0 触发 bean validation 失败). 真正可售成品库存在 FinishedGoodsBatch
        // (producedQuantity/shippedQuantity/reservedQuantity), 发货/调拨扣的是它.
        // 扣 ProductionBatch.quantity = 成品报损不减可售库存 + 污染生产分析(yield-rate 分母) + @Positive 风险.
        //
        // DisposalRecord 仅有 productionBatchId (→ ProductionBatch.id), 无 finishedGoodsBatchId 字段;
        // FinishedGoodsBatch 只经 productionPlanId(计划级, 一对多) 关联, 无法从单个 productionBatch 可靠定位
        // 唯一 FG 批次 (一个计划产出多个 FG 批次, 跨产品/仓库). 可靠定位需 schema 改 (加 finishedGoodsBatchId
        // 字段 + UI 选 FG 批次), 属更大 feature. 当前路径先安全阻止 —— 绝不扣错表污染生产分析.
        if (record.getProductionBatchId() != null && !record.getProductionBatchId().isBlank()) {
            // 校验生产批次存在 + 归属(明确错误信息), 但不扣其 quantity
            Long pbId;
            try {
                pbId = Long.valueOf(record.getProductionBatchId());
            } catch (NumberFormatException e) {
                throw new BusinessException(404,
                        "生产批次ID非法: " + record.getProductionBatchId());
            }
            ProductionBatch batch = productionBatchRepository.findById(pbId)
                    .orElseThrow(() -> new BusinessException(404,
                            "报损生产批次不存在: " + record.getProductionBatchId()));
            if (batch.getFactoryId() == null || !batch.getFactoryId().equals(record.getFactoryId())) {
                throw new BusinessException(403, "无权操作该生产批次")
                        .withHint("批次不属于当前工厂");
            }
            throw new BusinessException(422,
                    "成品报损暂不支持自动扣减库存, 请联系管理员")
                    .withHint("成品报损批次定位待完善 (需关联成品库存批次 FinishedGoodsBatch)")
                    .withHintTarget("productionBatchId");
        }

        // 无关联批次 (仅质检/返工关联) → 不扣库存, 取估损兜底
        log.info("报损记录 id={} 无关联批次, 不扣库存, actualLoss 取估损", record.getId());
        return record.getEstimatedLoss();
    }

    /**
     * 损失金额 = 数量 × 单价; 单价缺失 (含 @PriceSensitive 脱敏或未录) 时回退估损。
     */
    private BigDecimal valuate(BigDecimal qty, BigDecimal unitPrice, BigDecimal estimatedLoss) {
        if (unitPrice == null) {
            throw new BusinessException(409, "批次缺少真实单位成本，无法生成处置费用凭证")
                    .withCode("DISPOSAL_COST_REQUIRED")
                    .withHint("请通过现有批次成本更正流程补齐成品批次成本，再回到报损审批重新提交");
        }
        return qty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal valuateFinishedGoodsCost(BigDecimal qty, BigDecimal unitCost) {
        return qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
    }

    /** 扣 producedQuantity; 可用为 0 标 DEPLETED (预留量仍在实物上, 不能因为可用为 0 就藏起来)。 */
    private void deductFinishedGoods(FinishedGoodsBatch fgBatch, BigDecimal afterProduced) {
        fgBatch.setProducedQuantity(afterProduced);
        BigDecimal shipped = fgBatch.getShippedQuantity() != null
                ? fgBatch.getShippedQuantity() : BigDecimal.ZERO;
        if (afterProduced.subtract(shipped).signum() <= 0) {
            fgBatch.setStatus(FinishedGoodsBatch.Status.DEPLETED);
        }
        finishedGoodsBatchRepository.save(fgBatch);
    }

    private boolean isCustomerOwnedFinishedGoods(DisposalRecord record) {
        if (record.getFinishedGoodsBatchId() == null || record.getFinishedGoodsBatchId().isBlank()) return false;
        return finishedGoodsBatchRepository.findById(record.getFinishedGoodsBatchId())
                .map(batch -> batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED)
                .orElse(false);
    }

    /**
     * 拒绝报废记录 (→ REJECTED, <b>不</b>扣减库存)。
     *
     * <p>F-BUG-2: 此前无 reject 端点, 前端 "拒绝" 错误地走 approve → 反而批准+扣库存。
     * F-BUG-4/5: 审批人从 token 取 + 财务角色守卫。
     */
    @Override
    @Transactional
    public DisposalRecord rejectDisposal(String factoryId, Long id, Integer approverId,
            String approverName, String reason, String requestRole) {
        assertApprovalRole(requestRole);  // F-BUG-5: 财务角色守卫
        DisposalRecord record = findForMutation(factoryId, id);

        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "报废记录已处理 (" + record.getStatus() + "), 请勿重复操作")
                    .withHint("查看记录状态").withHintTarget("status");
        }

        record.reject(approverId, approverName, reason);
        log.info("拒绝报废记录: id={}, 审批人={} ({}), 原因={}", id, approverName, approverId, reason);
        return hydrateReadback(disposalRecordRepository.save(record));
    }

    /**
     * 根据ID获取报废记录 (按工厂过滤, 跨租户返空 → 404)
     */
    @Override
    public Optional<DisposalRecord> getById(String factoryId, Long id) {
        return disposalRecordRepository.findById(id)
                .filter(r -> factoryId != null && factoryId.equals(r.getFactoryId()))
                .map(this::hydrateReadback);
    }

    @Transactional(readOnly = true)
    public List<com.cretas.aims.entity.finance.Voucher> getAccountingHistory(String factoryId, Long id) {
        getById(factoryId,id).orElseThrow(() -> new BusinessException(404,"报废记录不存在或不属于当前工厂"));
        return disposalCostAccountingService.getAccountingHistory(factoryId,id);
    }

    /**
     * 分页查询工厂报废记录
     */
    @Override
    public Page<DisposalRecord> getByFactoryId(String factoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "disposalDate"));
        return hydrateReadback(disposalRecordRepository.findByFactoryId(factoryId, pageable));
    }

    /**
     * 按报废类型分页查询
     */
    @Override
    public Page<DisposalRecord> getByFactoryIdAndType(String factoryId, String disposalType, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "disposalDate"));
        return hydrateReadback(disposalRecordRepository.findByFactoryIdAndDisposalType(factoryId, disposalType, pageable));
    }

    /**
     * Server-side filtered disposal list.  All predicates are applied before
     * pagination so totalElements and page boundaries remain truthful.
     */
    public Page<DisposalRecord> getByFactoryIdWithFilters(String factoryId, String disposalType,
                                                            String status, String keyword,
                                                            String finishedGoodsBatchId,
                                                            int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("disposalDate"), Sort.Order.desc("id")));
        Specification<DisposalRecord> specification = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.equal(root.get("factoryId"), factoryId));
            if (disposalType != null && !disposalType.isBlank()) {
                predicates.add(cb.equal(root.get("disposalType"), disposalType));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("approvalStatus"), status));
            }
            if (finishedGoodsBatchId != null && !finishedGoodsBatchId.isBlank()) {
                predicates.add(cb.equal(root.get("finishedGoodsBatchId"), finishedGoodsBatchId));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("disposalReason").as(String.class)), pattern),
                        cb.like(cb.lower(root.get("disposalMethod").as(String.class)), pattern),
                        cb.like(cb.lower(root.get("notes").as(String.class)), pattern),
                        cb.like(cb.lower(root.get("finishedGoodsBatchId").as(String.class)), pattern),
                        cb.like(cb.lower(root.get("productionBatchId").as(String.class)), pattern),
                        cb.like(cb.lower(root.get("materialBatchId").as(String.class)), pattern)));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return hydrateReadback(disposalRecordRepository.findAll(specification, pageable));
    }

    /**
     * 获取待审批的报废记录
     */
    @Override
    public List<DisposalRecord> getPendingApprovals(String factoryId) {
        return hydrateReadback(disposalRecordRepository.findPendingApprovals(factoryId));
    }

    /**
     * 按日期范围查询
     */
    @Override
    public List<DisposalRecord> getByDateRange(String factoryId, LocalDateTime startDate, LocalDateTime endDate) {
        return hydrateReadback(disposalRecordRepository.findByDateRange(factoryId, startDate, endDate));
    }

    /**
     * 获取报废统计
     */
    @Override
    public Map<String, Object> getDisposalStats(String factoryId, LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalCount", disposalRecordRepository.countByFactoryId(factoryId));
        stats.put("pendingCount", disposalRecordRepository.countByFactoryIdAndApprovalStatus(factoryId, "PENDING"));
        
        Double totalQuantity = disposalRecordRepository.calculateTotalDisposalQuantity(factoryId, startDate, endDate);
        stats.put("totalQuantity", totalQuantity != null ? totalQuantity : 0.0);
        
        Double totalLoss = disposalRecordRepository.calculateTotalLoss(factoryId, startDate, endDate);
        stats.put("totalLoss", totalLoss != null ? totalLoss : 0.0);
        
        Double recoveryValue = disposalRecordRepository.calculateTotalRecoveryValue(factoryId, startDate, endDate);
        stats.put("recoveryValue", recoveryValue != null ? recoveryValue : 0.0);
        
        Double netLoss = disposalRecordRepository.calculateNetLoss(factoryId, startDate, endDate);
        stats.put("netLoss", netLoss != null ? netLoss : 0.0);

        return stats;
    }

    /**
     * 按类型统计
     */
    @Override
    public List<Object[]> getStatsByType(String factoryId, LocalDateTime startDate, LocalDateTime endDate) {
        return disposalRecordRepository.getDisposalStatsByType(factoryId, startDate, endDate);
    }

    /**
     * 获取可回收报废记录
     */
    @Override
    public List<DisposalRecord> getRecyclableDisposals(String factoryId) {
        return hydrateReadback(disposalRecordRepository.findRecyclableDisposals(factoryId));
    }

    /**
     * 更新报废记录
     */
    @Override
    @Transactional
    public DisposalRecord updateDisposalRecord(String factoryId, Long id, DisposalRecord updateData) {
        DisposalRecord existing = findForMutation(factoryId, id);

        // 只能更新未审批的记录
        if (!"PENDING".equals(existing.getStatus())) {
            throw new BusinessException(409, "已处理的报废记录不能修改")
                    .withHint("请查看审批结果，终态记录不能修改");
        }

        if (updateData.getDisposalQuantity() != null) {
            existing.setDisposalQuantity(updateData.getDisposalQuantity());
        }
        if (updateData.getDisposalType() != null) {
            assertSupportedDisposalType(updateData.getDisposalType());
            existing.setDisposalType(updateData.getDisposalType());
        }
        if (updateData.getCustomerConfirmedBy() != null) {
            existing.setCustomerConfirmedBy(updateData.getCustomerConfirmedBy());
        }
        if (updateData.getCustomerConfirmedAt() != null) {
            existing.setCustomerConfirmedAt(updateData.getCustomerConfirmedAt());
        }
        if (updateData.getCustomerConfirmationEvidence() != null) {
            existing.setCustomerConfirmationEvidence(updateData.getCustomerConfirmationEvidence());
        }
        if (updateData.getDisposalReason() != null) {
            existing.setDisposalReason(updateData.getDisposalReason());
        }
        if (updateData.getDisposalMethod() != null) {
            existing.setDisposalMethod(updateData.getDisposalMethod());
        }
        if (updateData.getEstimatedLoss() != null) {
            existing.setEstimatedLoss(updateData.getEstimatedLoss());
        }
        if (updateData.getRecoveryValue() != null) {
            existing.setRecoveryValue(updateData.getRecoveryValue());
        }
        if (updateData.getEvidenceImages() != null) {
            existing.setEvidenceImages(updateData.getEvidenceImages());
        }
        if (updateData.getNotes() != null) {
            existing.setNotes(updateData.getNotes());
        }

        if (existing.getFinishedGoodsBatchId() != null && !existing.getFinishedGoodsBatchId().isBlank()) {
            assertFinishedGoodsDisposalBalance(existing);
        }

        log.info("更新报废记录: id={}", id);
        return hydrateReadback(disposalRecordRepository.save(existing));
    }

    private DisposalRecord hydrateReadback(DisposalRecord record) {
        hydrateReadback(Collections.singletonList(record));
        return record;
    }

    private Page<DisposalRecord> hydrateReadback(Page<DisposalRecord> records) {
        hydrateReadback(records.getContent());
        return records;
    }

    /**
     * Populate display-only fields for finished-goods disposal records in one repository read.
     * A referenced batch from another factory is deliberately ignored to preserve tenant isolation.
     */
    private <T extends Collection<DisposalRecord>> T hydrateReadback(T records) {
        List<String> ids = records.stream()
                .map(DisposalRecord::getFinishedGoodsBatchId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return records;
        }
        Map<String, FinishedGoodsBatch> batches = finishedGoodsBatchRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(FinishedGoodsBatch::getId, Function.identity()));
        records.forEach(record -> {
            FinishedGoodsBatch batch = batches.get(record.getFinishedGoodsBatchId());
            if (batch == null || record.getFactoryId() == null
                    || !record.getFactoryId().equals(batch.getFactoryId())) {
                return;
            }
            record.setBatchNumber(batch.getBatchNumber());
            record.setBatchDisplayName(batch.getProductName());
            record.setQuantityUnit(batch.getUnit());
        });
        return records;
    }

    /**
     * 删除报废记录（软删除）
     */
    @Override
    @Transactional
    public void deleteDisposalRecord(String factoryId, Long id) {
        DisposalRecord record = findForMutation(factoryId, id);

        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "已处理的报废记录不能删除")
                    .withHint("请查看审批结果，终态记录不能删除");
        }

        record.softDelete();
        disposalRecordRepository.save(record);
        log.info("删除报废记录: id={}", id);
    }

    /**
     * SP12 §5.3: 提交报废记录至审批工作流。
     */
    @Override
    @Transactional
    public String submitForApproval(Long id, String factoryId, Long userId) {
        DisposalRecord record = findForMutation(factoryId, id);
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "已处理的报废记录不能提交审批")
                    .withHint("请查看审批结果，终态记录无需再次提交");
        }

        // 幂等防重：已在工作流中则 409
        if (record.getWorkflowInstanceId() != null) {
            throw new BusinessException(409, "报废记录已提交工作流: " + record.getWorkflowInstanceId());
        }

        String instanceId = null;
        if (workflowEngine != null) {
            try {
                Map<String, Object> context = Map.of(
                        "disposalId", id,
                        "factoryId", factoryId,
                        "disposalType", record.getDisposalType() != null ? record.getDisposalType() : ""
                );
                ApprovalWorkflowInstance instance = workflowEngine.startWorkflow(
                        factoryId, "DISPOSAL_APPROVAL", String.valueOf(id), context, userId);
                if (instance != null) {
                    instanceId = instance.getId();
                    record.setWorkflowInstanceId(instanceId);
                }
            } catch (Exception e) {
                log.warn("启动报废审批工作流失败 (id={}), 降级处理: {}", id, e.getMessage());
            }
        }

        disposalRecordRepository.save(record);
        log.info("报废记录已提交审批: id={}, instanceId={}", id, instanceId);
        return instanceId;
    }
}
