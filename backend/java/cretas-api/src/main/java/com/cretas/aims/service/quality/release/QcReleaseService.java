package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 质检放行 (缺口 11, owner 2026-10-25): 待检清单 + 放行 / 判不合格。
 *
 * <p>三种存货形态的「待检」状态见 {@link QcInspectionGuard}。放行动作:
 * <pre>
 *   半成品库行   inspectionStatus PENDING        → RELEASED / REJECTED
 *   在制批次     status INSPECTING                → AVAILABLE / DEFECTIVE
 *   成品批次     status PENDING_INSPECTION        → AVAILABLE / DEFECTIVE
 * </pre>
 * 每次决定写一条 {@link QcReleaseRecord} (结论 + 放行人 + 放行时间)。
 *
 * <p>🔴 放行 (RELEASED) 之前问 {@link QcReleaseBlocker}: 该批次/计划有未处理的 CCP 偏离就拒绝
 * ({@code QC_RELEASE_BLOCKED_BY_CCP_DEVIATION}, 缺口 13)。判不合格 (REJECTED) 不受偏离影响 ——
 * 偏离本来就是判不合格的理由之一。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcReleaseService {

    public static final String CODE_NOT_PENDING = "QC_RELEASE_NOT_PENDING";
    public static final String CODE_BLOCKED_BY_CCP = "QC_RELEASE_BLOCKED_BY_CCP_DEVIATION";
    public static final String CODE_INVALID = "QC_RELEASE_INVALID_REQUEST";
    private static final int CONCLUSION_MAX = 2000;

    private final SemiFinishedInventoryRepository semiRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final FinishedGoodsBatchRepository finishedGoodsRepo;
    private final ProductTypeRepository productTypeRepo;
    private final QcReleaseRecordRepository recordRepo;

    /** CCP 偏离挡放行 (缺口 13)。required=false: 未接 CCP 的环境 = 不挡。 */
    @Autowired(required = false)
    private QcReleaseBlocker releaseBlocker;

    /** 半成品判不合格时写 QC_REJECT 出库流水 (审查高 2: 不合格量按数量转出, 不洗白)。 */
    @Autowired(required = false)
    private com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository semiTxnRepo;

    /** 成品放行前查同计划是否有质检 FAILED 的生产批次 (审查中 5)。 */
    @Autowired(required = false)
    private com.cretas.aims.repository.ProductionBatchRepository productionBatchRepo;

    public static final String CODE_BLOCKED_BY_QC_FAILED = "QC_RELEASE_BLOCKED_BY_QC_FAILED";
    /** 这次要放的货出自报废批次 (Owner 2026-11-09: 报废批次只扣它自己的货)。 */
    public static final String CODE_BLOCKED_BY_SCRAPPED_BATCH = "QC_RELEASE_BLOCKED_BY_SCRAPPED_BATCH";
    /** 计划有报废批次而这批货分不清归属 (不猜)。 */
    public static final String CODE_OWNERSHIP_UNCLEAR = "QC_RELEASE_OWNERSHIP_UNCLEAR";

    /** 成品归属判据要读血缘边; required=false 仅为手工构造的单测, 真要判归属时没注入会直接报错 (不当「分得清」)。 */
    @Autowired(required = false)
    private com.cretas.aims.repository.lineage.BatchLineageEdgeRepository batchLineageEdgeRepository;

    /** 测试 / 装配用。 */
    public void setBatchLineageEdgeRepository(com.cretas.aims.repository.lineage.BatchLineageEdgeRepository repo) {
        this.batchLineageEdgeRepository = repo;
    }

    /** 测试 / 装配用。 */
    public void setSemiTxnRepo(com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository repo) {
        this.semiTxnRepo = repo;
    }

    /** 测试 / 装配用。 */
    public void setProductionBatchRepo(com.cretas.aims.repository.ProductionBatchRepository repo) {
        this.productionBatchRepo = repo;
    }

    /** 待检分笔账: 放行 / 判不合格后这一行所有笔清零 (第二轮审查 H3)。 */
    @Autowired(required = false)
    private SemiQcHoldLots semiQcHoldLots;

    /** 测试 / 装配用。 */
    public void setSemiQcHoldLots(SemiQcHoldLots lots) {
        this.semiQcHoldLots = lots;
    }

    /** 一条待检。 */
    public record PendingItem(
            String targetType,
            String targetId,
            /** 给人看的形态: 半成品 / 在制半成品 / 成品 */
            String formLabel,
            String batchNumber,
            String productTypeId,
            String productName,
            BigDecimal quantity,
            String unit,
            LocalDateTime producedAt,
            /** 挡放行的未处理 CCP 偏离条数 (0 = 可放行) */
            long openDeviationCount) {
    }

    /** 放行请求。 */
    public record DecisionCommand(String targetType, String targetId, String decision, String conclusion) {
    }

    @Transactional(readOnly = true)
    public List<PendingItem> listPending(String factoryId) {
        // 审查低 11: 余额为 0 / 已耗尽的不进待办 (没有货可放, 列出来只会让质检员白点)。
        List<SemiFinishedInventory> semis = semiRepo.findByFactoryIdAndInspectionStatusForRelease(
                factoryId, SemiFinishedInventory.InspectionStatus.PENDING).stream()
                .filter(s -> SemiFinishedInventory.Status.AVAILABLE.equals(s.getStatus()))
                .filter(s -> pendingQuantityOf(s).signum() > 0)
                .toList();
        List<MaterialBatch> wips = materialBatchRepo.findWipBatchesByFactoryIdAndStatus(
                factoryId, MaterialBatchStatus.INSPECTING).stream()
                .filter(w -> w.getCurrentQuantity() != null && w.getCurrentQuantity().signum() > 0)
                .toList();
        List<FinishedGoodsBatch> fgs = finishedGoodsRepo.findByFactoryIdAndStatusForRelease(
                factoryId, FinishedGoodsBatch.Status.PENDING_INSPECTION).stream()
                .filter(f -> f.getAvailableQuantity() != null && f.getAvailableQuantity().signum() > 0)
                .toList();

        Set<String> productIds = new HashSet<>();
        semis.forEach(s -> productIds.add(s.getProductTypeId()));
        wips.forEach(w -> productIds.add(w.getProductTypeId()));
        fgs.forEach(f -> productIds.add(f.getProductTypeId()));
        productIds.remove(null);
        Map<String, String> names = productIds.isEmpty() ? Map.of()
                : productTypeRepo.findByFactoryIdAndIdIn(factoryId, productIds).stream()
                        .collect(Collectors.toMap(ProductType::getId, ProductType::getName, (a, b) -> a));

        List<PendingItem> out = new ArrayList<>();
        for (SemiFinishedInventory s : semis) {
            out.add(new PendingItem(QcReleaseRecord.TargetType.SEMI_FINISHED, String.valueOf(s.getId()), "半成品",
                    s.getIntermediateBatchNo(), s.getProductTypeId(), names.get(s.getProductTypeId()),
                    pendingQuantityOf(s), s.getUnit(), s.getUpdatedAt(),
                    openDeviations(factoryId, QcReleaseRecord.TargetType.SEMI_FINISHED, s, null, null)));
        }
        for (MaterialBatch w : wips) {
            out.add(new PendingItem(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, w.getId(), "在制半成品",
                    w.getBatchNumber(), w.getProductTypeId(), names.get(w.getProductTypeId()),
                    w.getCurrentQuantity(), w.getQuantityUnit(), w.getCreatedAt(),
                    openDeviations(factoryId, QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, null, w, null)));
        }
        for (FinishedGoodsBatch f : fgs) {
            out.add(new PendingItem(QcReleaseRecord.TargetType.FINISHED_GOODS, f.getId(), "成品",
                    f.getBatchNumber(), f.getProductTypeId(),
                    f.getProductName() != null ? f.getProductName() : names.get(f.getProductTypeId()),
                    f.getAvailableQuantity(), f.getUnit(), f.getCreatedAt(),
                    openDeviations(factoryId, QcReleaseRecord.TargetType.FINISHED_GOODS, null, null, f)));
        }
        out.sort(Comparator.comparing(PendingItem::producedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    @Transactional
    public QcReleaseRecord decide(String factoryId, DecisionCommand cmd, Long userId, String userName) {
        validate(cmd, userId);
        String decision = cmd.decision().trim().toUpperCase();
        boolean release = QcReleaseRecord.Decision.RELEASED.equals(decision);
        String conclusion = cmd.conclusion().trim();

        QcReleaseRecord.QcReleaseRecordBuilder<?, ?> record = QcReleaseRecord.builder()
                .factoryId(factoryId)
                .targetType(cmd.targetType())
                .targetId(cmd.targetId())
                .decision(decision)
                .conclusion(conclusion)
                .releasedBy(userId)
                .releasedByName(userName)
                .releasedAt(LocalDateTime.now());

        switch (cmd.targetType()) {
            case QcReleaseRecord.TargetType.SEMI_FINISHED -> {
                Long id = parseLongId(cmd.targetId());
                SemiFinishedInventory s = semiRepo.findByIdAndFactoryIdForUpdate(id, factoryId)
                        .orElseThrow(() -> notFound("半成品库存行"));
                BigDecimal pending = pendingQuantityOf(s);
                if (!SemiFinishedInventory.InspectionStatus.PENDING.equals(s.getInspectionStatus())
                        || pending.signum() <= 0) {
                    throw notPending("半成品批次 " + s.getIntermediateBatchNo());
                }
                if (release) {
                    assertNoOpenDeviation(openDeviations(factoryId, cmd.targetType(), s, null, null),
                            s.getIntermediateBatchNo());
                    // 第二轮审查 M2: 与成品同一口径 —— 入过这一行的计划有批次质检 FAILED ⇒ 不能放行
                    SemiSources semiSources = semiProductionBatchIds(factoryId, s, pending);
                    Set<Long> sourceBatches = semiSources.batchIds();
                    String what = "半成品批次 " + s.getIntermediateBatchNo();
                    List<ScrappedRef> scrapped = assertPlansQcNotFailed(factoryId,
                            planIdsOf(factoryId, sourceBatches),
                            com.cretas.aims.service.foodsafety.ccp.CcpOpenDeviationLookup
                                    .planPrefixOfAnchor(s.getIntermediateBatchNo()), what);
                    if (!scrapped.isEmpty()) {
                        if (sourceBatches.isEmpty()) {
                            throw ownershipUnclear(what, "这一行待检认不出来自哪些生产批次");
                        }
                        // #1548 第二轮审查 2: 有一笔待检没记生产批次, 那一笔可能就是报废批的 —— 分不清就不放
                        if (semiSources.partlyUnknown()) {
                            throw ownershipUnclear(what, "这一行有待检数量没记来自哪个生产批次");
                        }
                        assertNotFromScrapped(scrapped, sourceBatches, what);
                    }
                } else {
                    // 审查高 2: 判不合格 = 把待检的那部分数量从可用余额里转出 (QC_REJECT 出库流水),
                    // 已放行的存量不动; 这一行回到「无待检」, 之后的新产出照常按需检进待检。
                    writeOffSemi(factoryId, s, pending, userId, "qc-reject:" + s.getId());
                }
                s.setInspectionStatus(null);
                s.setInspectionPendingQuantity(null);
                semiRepo.save(s);
                if (semiQcHoldLots != null) {
                    semiQcHoldLots.closeAll(factoryId, s.getId());   // 待检已处理完, 分笔账清零
                }
                record.batchNumber(s.getIntermediateBatchNo()).productTypeId(s.getProductTypeId())
                        .quantity(pending).unit(s.getUnit());
            }
            case QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH -> {
                MaterialBatch w = materialBatchRepo.findByIdAndFactoryIdForUpdate(cmd.targetId(), factoryId)
                        .orElseThrow(() -> notFound("在制半成品批次"));
                if (!QcInspectionGuard.WIP_SOURCE_DOC_TYPE.equals(w.getSourceDocType())
                        || w.getStatus() != MaterialBatchStatus.INSPECTING) {
                    throw notPending("在制半成品批次 " + w.getBatchNumber());
                }
                if (release) {
                    assertNoOpenDeviation(openDeviations(factoryId, cmd.targetType(), null, w, null),
                            w.getBatchNumber());
                    // 第二轮审查 M2: 在制批次所在计划有批次质检 FAILED ⇒ 不能放行
                    String what = "在制半成品批次 " + w.getBatchNumber();
                    List<ScrappedRef> scrapped =
                            assertPlansQcNotFailed(factoryId, wipPlanIds(factoryId, w), null, what);
                    if (!scrapped.isEmpty()) {
                        Long source = wipSourceBatchId(w);
                        if (source == null) {
                            throw ownershipUnclear(what, "认不出它来自哪个生产批次");
                        }
                        assertNotFromScrapped(scrapped, Set.of(source), what);
                    }
                }
                w.setStatus(release ? MaterialBatchStatus.AVAILABLE : MaterialBatchStatus.DEFECTIVE);
                materialBatchRepo.save(w);
                record.batchNumber(w.getBatchNumber()).productTypeId(w.getProductTypeId())
                        .quantity(w.getCurrentQuantity()).unit(w.getQuantityUnit());
            }
            case QcReleaseRecord.TargetType.FINISHED_GOODS -> {
                FinishedGoodsBatch f = finishedGoodsRepo.findByIdAndFactoryIdForUpdate(cmd.targetId(), factoryId)
                        .orElseThrow(() -> notFound("成品批次"));
                if (!FinishedGoodsBatch.Status.PENDING_INSPECTION.equals(f.getStatus())) {
                    throw notPending("成品批次 " + f.getBatchNumber());
                }
                if (release) {
                    assertNoOpenDeviation(openDeviations(factoryId, cmd.targetType(), null, null, f),
                            f.getBatchNumber());
                    assertPlanQcNotFailed(factoryId, f);
                }
                f.setStatus(release ? FinishedGoodsBatch.Status.AVAILABLE : FinishedGoodsBatch.Status.DEFECTIVE);
                finishedGoodsRepo.save(f);
                record.batchNumber(f.getBatchNumber()).productTypeId(f.getProductTypeId())
                        .quantity(f.getAvailableQuantity()).unit(f.getUnit());
            }
            default -> throw invalid("不支持的放行对象类型: " + cmd.targetType());
        }
        QcReleaseRecord saved = recordRepo.save(record.build());
        log.info("[质检放行] factory={} target={}:{} decision={} by={}",
                factoryId, cmd.targetType(), cmd.targetId(), decision, userId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<QcReleaseRecord> listRecords(String factoryId, String targetType, String targetId) {
        if (targetType != null && !targetType.isBlank() && targetId != null && !targetId.isBlank()) {
            return recordRepo.findByTarget(factoryId, targetType, targetId);
        }
        return recordRepo.findTop200ByFactoryIdOrderByReleasedAtDesc(factoryId);
    }

    // ==================== 内部 ====================

    private long openDeviations(String factoryId, String targetType, SemiFinishedInventory s,
                                MaterialBatch w, FinishedGoodsBatch f) {
        if (releaseBlocker == null) {
            return 0L;
        }
        return switch (targetType) {
            case QcReleaseRecord.TargetType.SEMI_FINISHED -> releaseBlocker.openDeviationsForSemi(factoryId, s);
            case QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH -> releaseBlocker.openDeviationsForWipBatch(factoryId, w);
            case QcReleaseRecord.TargetType.FINISHED_GOODS -> releaseBlocker.openDeviationsForFinishedGoods(factoryId, f);
            default -> 0L;
        };
    }

    /** 半成品行的待检数量 (封顶为余额, 不低于 0)。 */
    static BigDecimal pendingQuantityOf(SemiFinishedInventory s) {
        BigDecimal pending = s.pendingInspectionQuantity();
        return pending == null ? BigDecimal.ZERO : pending;
    }

    /** 判不合格转出 (质检放行 / 时限超时处置共用): 写 QC_REJECT 出库流水, 不改已放行的存量。 */
    public void writeOffSemi(String factoryId, SemiFinishedInventory s, BigDecimal qty, Long userId, String sourceRef) {
        if (semiTxnRepo == null) {
            throw new BusinessException(500, "半成品不合格转出缺少流水仓储, 不能判定不合格").withCode(CODE_INVALID);
        }
        s.setConsumedQuantity((s.getConsumedQuantity() == null ? BigDecimal.ZERO : s.getConsumedQuantity()).add(qty));
        BigDecimal after = s.refreshAvailableQuantity();
        if (after.signum() <= 0 && !SemiFinishedInventory.Status.RETURNED.equals(s.getStatus())) {
            s.setStatus(SemiFinishedInventory.Status.DEPLETED);
        }
        semiTxnRepo.save(com.cretas.aims.entity.SemiFinishedInventoryTransaction.builder()
                .factoryId(factoryId)
                .semiFinishedId(s.getId())
                .txnType(com.cretas.aims.entity.SemiFinishedInventoryTransaction.TxnType.OUT)
                .sourceType(com.cretas.aims.entity.SemiFinishedInventoryTransaction.SourceType.QC_REJECT)
                .sourceRef(sourceRef)
                .quantity(qty.negate())
                .unitCostAtTxn(s.getUnitCost())
                .balanceAfter(after)
                .balanceCostAfter(s.getUnitCost())
                .operatorId(userId)
                .build());
    }

    /** 审查中 5: 同计划有生产批次质检判 FAILED ⇒ 成品不能放行 (与质检失败隔离同一口径)。 */
    private void assertPlanQcNotFailed(String factoryId, FinishedGoodsBatch f) {
        if (f.getProductionPlanId() == null || f.getProductionPlanId().isBlank()) {
            return;
        }
        assertFinishedGoodsNotFromScrapped(factoryId, f,
                assertPlansQcNotFailed(factoryId, java.util.Set.of(f.getProductionPlanId()), null,
                        "成品批次 " + f.getBatchNumber()));
    }

    /**
     * 质检 FAILED 挡放行的<b>唯一判据</b> (成品 / 半成品行 / 在制批次共用, 第二轮审查 M2):
     * 这些计划 (或计划 id 以锚号里的前缀开头的计划) 里有生产批次质检判 FAILED ⇒ 拒绝放行。
     * 判不合格 (REJECTED) 不受影响 —— 质检失败的货本来就该判不合格。
     */
    private List<ScrappedRef> assertPlansQcNotFailed(
            String factoryId, java.util.Collection<String> planIds, String planIdPrefix, String what) {
        if (productionBatchRepo == null) {
            return List.of();
        }
        java.util.Set<String> plans = new java.util.LinkedHashSet<>();
        if (planIds != null) {
            planIds.stream().filter(p -> p != null && !p.isBlank()).forEach(plans::add);
        }
        boolean hasPrefix = planIdPrefix != null && !planIdPrefix.isBlank();
        if (plans.isEmpty() && !hasPrefix) {
            return List.of();
        }
        java.util.Collection<String> planArg = plans.isEmpty() ? java.util.Set.of("#NO-PLAN#") : plans;
        String prefixArg = hasPrefix ? com.cretas.aims.util.SqlLikeEscaper.escape(planIdPrefix) + "%" : "#NO-PREFIX#";
        // Owner 2026-11-09: 报废批次不挡同计划其它批次 —— 只有「不合格未解除且不是报废」的批次才整计划拒绝。
        // 一条语句数 (#1548 第二轮审查 4: 原来是「总数 − 报废清单长度」两条语句, 中间能被插进来)。
        long uncleared = productionBatchRepo.countUnclearedNotScrappedInPlans(factoryId, planArg, prefixArg);
        if (uncleared > 0) {
            throw new BusinessException(409, what + " 所在生产计划有批次质检不合格，不能放行")
                    .withCode(CODE_BLOCKED_BY_QC_FAILED)
                    .withHint("请到「质量管理 → 质检记录」复核该计划的不合格批次；确认不合格请选「不合格」")
                    .withSeverity("BLOCKING")
                    .withHintTarget("decision");
        }
        // 报废批次只用于归属判断: 这次要放的货是报废批的 (或分不清) 才拒。
        return productionBatchRepo.findScrappedInPlans(factoryId, planArg, prefixArg).stream()
                .map(ref -> new ScrappedRef(ref.getId(), ref.getBatchNumber()))
                .toList();
    }

    /** 计划范围里一个报废批次 (id + 批号)。 */
    private record ScrappedRef(Long id, String batchNumber) {
    }

    /** 这次要放的货出自报废批次 ⇒ 不放 (报废是终态, 它的货留在隔离)。 */
    private static void assertNotFromScrapped(List<ScrappedRef> scrapped,
                                              java.util.Collection<Long> sourceBatchIds, String what) {
        for (ScrappedRef b : scrapped) {
            if (sourceBatchIds.contains(b.id())) {
                throw new BusinessException(409, what + " 出自已判报废的生产批次 " + b.batchNumber() + "，不能放行")
                        .withCode(CODE_BLOCKED_BY_SCRAPPED_BATCH)
                        .withHint("报废是终态；这批货请选「不合格」，实物按废弃处理")
                        .withSeverity("BLOCKING")
                        .withHintTarget("decision");
            }
        }
    }

    /** 计划里有报废批次, 而这次要放的货分不清出自哪个批次 ⇒ 不放, 如实说原因 (不猜)。 */
    private static BusinessException ownershipUnclear(String what, String reason) {
        return new BusinessException(409, what + " 所在计划有已报废的批次，但" + reason
                + "，分不清这批货是不是报废批的，不能放行")
                .withCode(CODE_OWNERSHIP_UNCLEAR)
                .withHint("请先补齐这批货的生产批次血缘 (或核对批号), 再放行")
                .withSeverity("BLOCKING")
                .withHintTarget("decision");
    }

    /** 成品: 按计划级放行同一份归属判据 ({@link com.cretas.aims.service.quality.PlanGoodsOwnership})。 */
    private void assertFinishedGoodsNotFromScrapped(String factoryId, FinishedGoodsBatch f,
                                                    List<ScrappedRef> scrapped) {
        if (scrapped.isEmpty()) {
            return;
        }
        String what = "成品批次 " + f.getBatchNumber();
        List<com.cretas.aims.entity.ProductionBatch> planBatches = new ArrayList<>(
                productionBatchRepo.findByFactoryIdAndProductionPlanId(factoryId, f.getProductionPlanId()));
        for (ScrappedRef s : scrapped) {
            if (planBatches.stream().noneMatch(b -> java.util.Objects.equals(b.getId(), s.id()))) {
                com.cretas.aims.entity.ProductionBatch ref = new com.cretas.aims.entity.ProductionBatch();
                ref.setId(s.id());
                ref.setBatchNumber(s.batchNumber());
                planBatches.add(ref);
            }
        }
        var ownership = com.cretas.aims.service.quality.PlanGoodsOwnership.attribute(
                batchLineageEdgeRepository, factoryId, List.of(f), planBatches);
        if (ownership.outsidePlan() != null) {
            throw ownershipUnclear(what, ownership.outsidePlan());
        }
        Set<Long> sources = new HashSet<>(ownership.of(f));
        scrapped.stream().filter(s -> s.batchNumber() != null && s.batchNumber().equals(f.getBatchNumber()))
                .forEach(s -> sources.add(s.id()));
        assertNotFromScrapped(scrapped, sources, what);
        if (ownership.unknown() != null) {
            throw ownershipUnclear(what, ownership.unknown());
        }
    }

    /**
     * 这次要放行的待检来自哪些计划 (第三轮审查 H-1)。
     *
     * <p>App 半成品行按产品码跨计划、跨月一直累加: 拿这一行<b>历史上全部</b>入账的计划去判,
     * 3 月某计划质检 FAILED (那批货早已判不合格转出), 10 月的新待检就永远放不出。所以只看:
     * <ol>
     *   <li>当前未放行的分笔记下的生产批次 (分笔账在每次放行 / 判不合格时清零, 剩下的就是这次要放的);</li>
     *   <li>分笔覆盖不了全部待检时 (旧数据整行待检 / CCP 偏离整行扣留, 认不出是哪一笔), 退一步看
     *       上一次放行或判不合格<b>之后</b>、仍然有效的入账 (排除已被 REVERSE 冲掉的、已软删的报工)。</li>
     * </ol>
     * 计划 id 一次 IN 查询取出 (L-6)。逐道录入锚号行另按锚号里的计划前缀判 (调用方传入)。
     */
    private SemiSources semiProductionBatchIds(String factoryId, SemiFinishedInventory s, BigDecimal pending) {
        if (productionBatchRepo == null || s.getId() == null) {
            return new SemiSources(java.util.Set.of(), false);
        }
        java.util.Set<Long> pbIds = new java.util.LinkedHashSet<>();
        BigDecimal attributed = BigDecimal.ZERO;
        boolean partlyUnknown = false;
        if (semiQcHoldLots != null) {
            for (com.cretas.aims.entity.quality.SemiQcHoldLot lot : semiQcHoldLots.openLots(factoryId, s.getId())) {
                attributed = attributed.add(lot.getQuantityOpen());
                if (lot.getProductionBatchId() != null) {
                    pbIds.add(lot.getProductionBatchId());
                } else if (lot.getQuantityOpen() != null && lot.getQuantityOpen().signum() > 0) {
                    partlyUnknown = true;
                }
            }
        }
        if (attributed.compareTo(pending == null ? BigDecimal.ZERO : pending) < 0) {
            java.time.LocalDateTime since = recordRepo.findByTarget(factoryId,
                            QcReleaseRecord.TargetType.SEMI_FINISHED, String.valueOf(s.getId())).stream()
                    .map(QcReleaseRecord::getReleasedAt)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(java.time.LocalDateTime.of(1970, 1, 1, 0, 0));
            List<Long> live = semiRepo.findLiveProductionBatchIdsPostedToSemiSince(factoryId, s.getId(), since);
            if (live != null) {
                pbIds.addAll(live);
            }
        }
        pbIds.remove(null);
        return new SemiSources(pbIds, partlyUnknown);
    }

    /** 半成品这一行待检来自的生产批次; partlyUnknown = 有一笔待检没记生产批次 (那一笔出处不明)。 */
    private record SemiSources(java.util.Set<Long> batchIds, boolean partlyUnknown) {
    }

    /** 这些生产批次所在计划 (一次 IN 查询, L-6)。 */
    private java.util.Set<String> planIdsOf(String factoryId, java.util.Set<Long> pbIds) {
        if (productionBatchRepo == null || pbIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.LinkedHashSet<>(productionBatchRepo.findPlanIdsByIds(factoryId, pbIds));
    }

    /** 在制批次的来源生产批次 id (sourceDocId); 认不出 = null。 */
    private static Long wipSourceBatchId(MaterialBatch w) {
        if (w.getSourceDocId() == null) {
            return null;
        }
        try {
            return Long.valueOf(w.getSourceDocId().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 在制批次 (sourceDocId = 生产批次 id) 所在计划。 */
    private java.util.Set<String> wipPlanIds(String factoryId, MaterialBatch w) {
        if (productionBatchRepo == null || w.getSourceDocId() == null) {
            return java.util.Set.of();
        }
        try {
            Long pbId = Long.valueOf(w.getSourceDocId().trim());
            return productionBatchRepo.findByIdAndFactoryId(pbId, factoryId)
                    .map(com.cretas.aims.entity.ProductionBatch::getProductionPlanId)
                    .map(java.util.Set::of)
                    .orElse(java.util.Set.of());
        } catch (NumberFormatException e) {
            return java.util.Set.of();
        }
    }

    private static void assertNoOpenDeviation(long open, String batchNumber) {
        if (open > 0) {
            throw new BusinessException(409, "批次 " + batchNumber + " 还有 " + open
                    + " 条未处理的 CCP 偏离，不能放行")
                    .withCode(CODE_BLOCKED_BY_CCP)
                    .withHint("请先在「质量管理 → CCP 监控记录」逐条填写纠偏措施并标记已处理，再回来放行；"
                            + "若决定不合格，可直接选「不合格」")
                    .withSeverity("BLOCKING")
                    .withHintTarget("decision");
        }
    }

    private static void validate(DecisionCommand cmd, Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法放行").withCode(CODE_INVALID);
        }
        if (cmd == null || isBlank(cmd.targetType()) || isBlank(cmd.targetId())) {
            throw invalid("缺少放行对象 (targetType / targetId)");
        }
        if (isBlank(cmd.decision())) {
            throw invalid("请选择「放行」或「不合格」");
        }
        String d = cmd.decision().trim().toUpperCase();
        if (!QcReleaseRecord.Decision.RELEASED.equals(d) && !QcReleaseRecord.Decision.REJECTED.equals(d)) {
            throw invalid("放行决定只能是 RELEASED (放行) 或 REJECTED (不合格)");
        }
        if (isBlank(cmd.conclusion())) {
            throw new BusinessException(400, "请填写检验结论")
                    .withCode(CODE_INVALID)
                    .withHint("例如：感官正常、中心温度复测 78℃ 合格")
                    .withSeverity("BLOCKING")
                    .withHintTarget("conclusion");
        }
        if (cmd.conclusion().trim().length() > CONCLUSION_MAX) {
            throw invalid("检验结论最长 " + CONCLUSION_MAX + " 字");
        }
    }

    private static Long parseLongId(String id) {
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            throw invalid("半成品库存行 id 不合法: " + id);
        }
    }

    private static BusinessException notFound(String what) {
        return new BusinessException(404, what + "不存在或不属于本工厂")
                .withCode(CODE_NOT_PENDING)
                .withHint("请刷新待检列表");
    }

    private static BusinessException notPending(String what) {
        return new BusinessException(409, what + " 当前不是待检状态，不能重复放行")
                .withCode(CODE_NOT_PENDING)
                .withHint("可能已被其他质检员处理，请刷新待检列表")
                .withSeverity("BLOCKING");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message).withCode(CODE_INVALID).withSeverity("BLOCKING");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
