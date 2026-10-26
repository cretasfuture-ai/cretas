package com.cretas.aims.repository;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口 11 质检放行新增的实体与查询, 在真实 Hibernate + H2 (PG 兼容) 上<b>执行</b> (不只是解析):
 * 新列映射 (product_types.inspection_required 默认 FALSE / semi_finished_inventory.inspection_status)、
 * 新表 qc_release_records 的写读、待检清单三条查询的口径 (只列待检 / 只列在制批次 / 工厂隔离)、
 * 以及 CCP 偏离扣留要用的反查 (批次 → 半成品行, 含按入库流水反查跨批次累加行)。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("质检放行: 实体映射 + 待检/扣留查询 (H2 真执行)")
class QcReleaseRepositoryQueryValidationTest {

    private static final String F = "F-QCR-1";
    private static final String OTHER = "F-QCR-2";

    @Autowired private SemiFinishedInventoryRepository semiRepo;
    @Autowired private MaterialBatchRepository materialBatchRepo;
    @Autowired private FinishedGoodsBatchRepository fgRepo;
    @Autowired private QcReleaseRecordRepository recordRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @Autowired private SemiFinishedInventoryTransactionRepository txnRepo;
    @Autowired private ProductionReportRepository reportRepo;
    @Autowired private EntityManager em;
    @Autowired private com.cretas.aims.repository.quality.SemiQcHoldLotRepository lotRepo;
    @Autowired private ProductionBatchRepository productionBatchRepo;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void relaxFk() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
    }

    private SemiFinishedInventory semi(String factory, String no, String inspection, Long batchId) {
        return semiRepo.saveAndFlush(SemiFinishedInventory.builder()
                .factoryId(factory).intermediateBatchNo(no).batchId(batchId)
                .producedQuantity(BigDecimal.TEN).consumedQuantity(BigDecimal.ZERO).availableQuantity(BigDecimal.TEN)
                .unit("kg").inspectionStatus(inspection).build());
    }

    @Test
    @DisplayName("product_types.inspection_required: 不设时落 FALSE (NOT NULL 不被 null 撞), 设 TRUE 读回 TRUE")
    void productInspectionSwitchMapping() {
        ProductType plain = new ProductType();
        plain.setId("PT-QCR-PLAIN");
        plain.setFactoryId(F);
        plain.setCode("PT-QCR-PLAIN");
        plain.setName("plain");
        plain.setUnit("kg");
        productTypeRepo.saveAndFlush(plain);
        ProductType req = new ProductType();
        req.setId("PT-QCR-REQ");
        req.setFactoryId(F);
        req.setCode("PT-QCR-REQ");
        req.setName("req");
        req.setUnit("kg");
        req.setInspectionRequired(true);
        productTypeRepo.saveAndFlush(req);
        em.clear();

        Boolean plainDb = jdbc.queryForObject(
                "SELECT inspection_required FROM product_types WHERE id = 'PT-QCR-PLAIN'", Boolean.class);
        assertThat(plainDb).isFalse();
        assertThat(productTypeRepo.findByIdAndFactoryId("PT-QCR-REQ", F).orElseThrow().getInspectionRequired())
                .isTrue();
    }

    @Test
    @DisplayName("半成品待检清单: 只列本厂 PENDING; 加锁按 id+工厂取; 扣留按批次 id / 批号 / 计划锚前缀")
    void semiQueries() {
        SemiFinishedInventory pending = semi(F, "SFI-P", SemiFinishedInventory.InspectionStatus.PENDING, 501L);
        semi(F, "SFI-R", SemiFinishedInventory.InspectionStatus.RELEASED, 502L);
        semi(F, "SFI-N", null, 503L);
        semi(OTHER, "SFI-OTHER", SemiFinishedInventory.InspectionStatus.PENDING, 501L);
        SemiFinishedInventory anchor = semi(F, "CLK-SEMI-PLAN1234-PT000001", null, null);
        em.clear();

        assertThat(semiRepo.findByFactoryIdAndInspectionStatusForRelease(F, SemiFinishedInventory.InspectionStatus.PENDING))
                .extracting(SemiFinishedInventory::getIntermediateBatchNo).containsExactly("SFI-P");
        assertThat(semiRepo.findByIdAndFactoryIdForUpdate(pending.getId(), F)).isPresent();
        assertThat(semiRepo.findByIdAndFactoryIdForUpdate(pending.getId(), OTHER)).isEmpty();
        assertThat(semiRepo.findByFactoryIdAndBatchIdInForHold(F, List.of(501L, 503L)))
                .extracting(SemiFinishedInventory::getIntermediateBatchNo)
                .containsExactlyInAnyOrder("SFI-P", "SFI-N");
        assertThat(semiRepo.findByFactoryIdAndBatchNoOrAnchorPrefixForHold(F, List.of("SFI-R"), "CLK-SEMI-PLAN1234-"))
                .extracting(SemiFinishedInventory::getId)
                .containsExactlyInAnyOrder(anchor.getId(),
                        semiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, "SFI-R").orElseThrow().getId());
    }

    @Test
    @DisplayName("按入库流水反查: 这些生产批次的报工入过哪些半成品行 (跨批次累加行)")
    void semiIdsPostedByProductionBatches() {
        SemiFinishedInventory shared = semi(F, "SEMI-CODE-X", null, 900L);   // 行上 batchId 是第一次建行的批次
        ProductionReport report = reportRepo.saveAndFlush(ProductionReport.builder()
                .factoryId(F).batchId(901L).workerId(1L).reportType("YIELD").reportDate(LocalDate.now()).build());
        txnRepo.saveAndFlush(SemiFinishedInventoryTransaction.builder()
                .factoryId(F).semiFinishedId(shared.getId())
                .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                .sourceRef("SEMI-CODE-X").quantity(BigDecimal.ONE).reportId(report.getId()).build());
        em.clear();

        assertThat(semiRepo.findSemiIdsPostedByProductionBatches(F, List.of(901L))).containsExactly(shared.getId());
        assertThat(semiRepo.findSemiIdsPostedByProductionBatches(F, List.of(999L))).isEmpty();
        assertThat(semiRepo.findSemiIdsPostedByProductionBatches(OTHER, List.of(901L))).isEmpty();
        // 审查高 3: 反方向 —— 这一行被哪些生产批次入过账 (行上 batchId=900 只是第一次建行的批次)
        assertThat(semiRepo.findProductionBatchIdsPostedToSemi(F, shared.getId())).containsExactly(901L);
        assertThat(semiRepo.findProductionBatchIdsPostedToSemi(OTHER, shared.getId())).isEmpty();
    }

    @Test
    @DisplayName("审查高 2/中 8: 待检数量列读写; 偏离扣留的加锁读取只取本厂未删除行")
    void pendingQuantityAndHoldLocks() {
        SemiFinishedInventory partial = semi(F, "SFI-PART", SemiFinishedInventory.InspectionStatus.PENDING, 601L);
        partial.setInspectionPendingQuantity(new BigDecimal("3"));
        semiRepo.saveAndFlush(partial);
        SemiFinishedInventory other = semi(OTHER, "SFI-PART-O", null, 601L);
        em.clear();

        SemiFinishedInventory back = semiRepo.findById(partial.getId()).orElseThrow();
        assertThat(back.getInspectionPendingQuantity()).isEqualByComparingTo("3");
        assertThat(back.drawableBalance()).isEqualByComparingTo("7");
        assertThat(semiRepo.lockForDeviationHold(F, List.of(partial.getId(), other.getId())))
                .extracting(SemiFinishedInventory::getId).containsExactly(partial.getId());
        // 审查 L2: 加锁读按 id 升序 (固定加锁顺序防死锁)
        SemiFinishedInventory second = semi(F, "SFI-PART-2", null, 602L);
        em.clear();
        assertThat(semiRepo.lockForDeviationHold(F, List.of(second.getId(), partial.getId())))
                .extracting(SemiFinishedInventory::getId)
                .containsExactly(Math.min(partial.getId(), second.getId()), Math.max(partial.getId(), second.getId()));
    }

    @Test
    @DisplayName("第二轮审查 H2/H3/M1: 待检分笔账三条查询 —— 只取未放行的笔, 按报工 / 凭据认笔, 新的在前; 工厂隔离")
    void holdLotQueries() {
        java.util.function.Function<com.cretas.aims.entity.quality.SemiQcHoldLot, com.cretas.aims.entity.quality.SemiQcHoldLot>
                save = lotRepo::saveAndFlush;
        var a = save.apply(com.cretas.aims.entity.quality.SemiQcHoldLot.builder().factoryId(F).semiFinishedId(77L)
                .holdRef("PS:P:A").productionBatchId(303L)
                .quantityHeld(new BigDecimal("100")).quantityOpen(new BigDecimal("100")).build());
        var b = save.apply(com.cretas.aims.entity.quality.SemiQcHoldLot.builder().factoryId(F).semiFinishedId(77L)
                .holdRef("PS:P:A").quantityHeld(new BigDecimal("20")).quantityOpen(new BigDecimal("20")).build());
        save.apply(com.cretas.aims.entity.quality.SemiQcHoldLot.builder().factoryId(F).semiFinishedId(77L)
                .reportId(901L).quantityHeld(new BigDecimal("40")).quantityOpen(new BigDecimal("40")).build());
        save.apply(com.cretas.aims.entity.quality.SemiQcHoldLot.builder().factoryId(F).semiFinishedId(77L)
                .reportId(902L).quantityHeld(new BigDecimal("5")).quantityOpen(BigDecimal.ZERO).build());   // 已放行
        save.apply(com.cretas.aims.entity.quality.SemiQcHoldLot.builder().factoryId(OTHER).semiFinishedId(77L)
                .holdRef("PS:P:A").quantityHeld(BigDecimal.ONE).quantityOpen(BigDecimal.ONE).build());
        em.clear();

        assertThat(lotRepo.findOpenBySemi(F, 77L)).hasSize(3)
                .anySatisfy(l -> assertThat(l.getProductionBatchId()).isEqualTo(303L));   // H-1 新列读写
        assertThat(lotRepo.findOpenBySemiAndRef(F, 77L, "PS:P:A"))
                .extracting(com.cretas.aims.entity.quality.SemiQcHoldLot::getId).containsExactly(b.getId(), a.getId());
        assertThat(lotRepo.findOpenBySemiAndReport(F, 77L, 901L)).singleElement()
                .satisfies(l -> assertThat(l.getQuantityOpen()).isEqualByComparingTo("40"));
        assertThat(lotRepo.findOpenBySemiAndReport(F, 77L, 902L)).isEmpty();
        assertThat(lotRepo.findOpenBySemiAndRef(OTHER, 77L, "PS:P:A")).hasSize(1);
        assertThat(lotRepo.findOpenBySemiAndRef(F, 78L, "PS:P:A")).isEmpty();
    }

    private void productionBatch(String factory, String plan, com.cretas.aims.entity.enums.QualityStatus qs) {
        com.cretas.aims.entity.ProductionBatch b = new com.cretas.aims.entity.ProductionBatch();
        b.setFactoryId(factory);
        b.setBatchNumber("QCF-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        b.setProductTypeId("PT-QCF");
        b.setQuantity(new BigDecimal("10"));
        b.setUnit("kg");
        b.setStatus(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS);
        b.setProductionPlanId(plan);
        b.setQualityStatus(qs);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        productionBatchRepo.saveAndFlush(b);
    }

    @Test
    @DisplayName("第二轮审查 M2: 计划质检 FAILED 计数 —— 按计划 id 或计划前缀; PASSED 不算; 工厂隔离; 哨兵不命中")
    void qcFailedCount() {
        productionBatch(F, "PLANAAAA-0001", com.cretas.aims.entity.enums.QualityStatus.FAILED);
        productionBatch(F, "PLANBBBB-0002", com.cretas.aims.entity.enums.QualityStatus.PASSED);
        productionBatch(OTHER, "PLANCCCC-0003", com.cretas.aims.entity.enums.QualityStatus.FAILED);
        em.clear();
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("PLANAAAA-0001"), "#NO-PREFIX#")).isEqualTo(1L);
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("PLANBBBB-0002"), "#NO-PREFIX#")).isZero();
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("#NO-PLAN#"), "PLANAAAA%")).isEqualTo(1L);
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("#NO-PLAN#"), "PLANBBBB%")).isZero();
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("PLANCCCC-0003"), "#NO-PREFIX#")).isZero();
        // 第三轮审查 L-1: 前缀转义后 _ 只配字面量; 不转义时 _ 是通配符, 多配一条 (阴性对照)
        productionBatch(F, "PLAN_D-1", com.cretas.aims.entity.enums.QualityStatus.FAILED);
        productionBatch(F, "PLANXD-2", com.cretas.aims.entity.enums.QualityStatus.FAILED);
        em.clear();
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("#NO-PLAN#"),
                com.cretas.aims.util.SqlLikeEscaper.escape("PLAN_D") + "%")).isEqualTo(1L);
        assertThat(productionBatchRepo.countQcFailedInPlans(F, List.of("#NO-PLAN#"), "PLAN_D%")).isEqualTo(2L);
    }

    @Test
    @DisplayName("第三轮审查 L-6: 一次 IN 查询取出这些批次的计划 id (去重, 跳过无计划, 工厂隔离)")
    void planIdsByBatchIds() {
        productionBatch(F, "PLAN-L6-A", null);
        productionBatch(F, "PLAN-L6-A", null);
        productionBatch(F, null, null);
        productionBatch(OTHER, "PLAN-L6-OTHER", null);
        em.clear();
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM production_batches WHERE factory_id IN ('" + F + "','" + OTHER + "')", Long.class);
        assertThat(productionBatchRepo.findPlanIdsByIds(F, ids)).containsExactly("PLAN-L6-A");
    }

    @Test
    @DisplayName("第三轮审查 H-1: 上次决定之后仍有效的入账批次 —— 排除决定之前的、被 REVERSE 冲掉的、报工已软删的")
    void liveBatchesPostedSince() {
        SemiFinishedInventory row = semi(F, "SEMI-LIVE", null, null);
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        java.util.function.BiFunction<Long, Boolean, ProductionReport> report = (batchId, deleted) -> {
            ProductionReport r = reportRepo.saveAndFlush(ProductionReport.builder().factoryId(F).batchId(batchId)
                    .workerId(1L).reportType("YIELD").reportDate(LocalDate.now()).build());
            if (deleted) {
                r.setDeletedAt(LocalDateTime.now());
                reportRepo.saveAndFlush(r);
            }
            return r;
        };
        ProductionReport old = report.apply(1001L, false);       // 上次决定之前入的
        ProductionReport live = report.apply(1002L, false);      // 有效
        ProductionReport reversed = report.apply(1003L, false);  // 已被整单撤回冲掉
        ProductionReport deleted = report.apply(1004L, true);    // 报工已软删
        java.util.function.BiConsumer<ProductionReport, String> in = (r, type) -> txnRepo.saveAndFlush(
                SemiFinishedInventoryTransaction.builder().factoryId(F).semiFinishedId(row.getId())
                        .txnType(type).sourceType("IN".equals(type)
                                ? SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT
                                : SemiFinishedInventoryTransaction.SourceType.REVERSAL)
                        .sourceRef("SEMI-LIVE").quantity("IN".equals(type) ? BigDecimal.TEN : BigDecimal.TEN.negate())
                        .reportId(r.getId()).build());
        in.accept(old, SemiFinishedInventoryTransaction.TxnType.IN);
        jdbc.update("UPDATE semi_finished_inventory_transactions SET created_at = ? WHERE report_id = ?",
                since.minusDays(200), old.getId());
        in.accept(live, SemiFinishedInventoryTransaction.TxnType.IN);
        in.accept(reversed, SemiFinishedInventoryTransaction.TxnType.IN);
        in.accept(reversed, SemiFinishedInventoryTransaction.TxnType.REVERSE);
        in.accept(deleted, SemiFinishedInventoryTransaction.TxnType.IN);
        em.clear();

        assertThat(semiRepo.findLiveProductionBatchIdsPostedToSemiSince(F, row.getId(), since)).containsExactly(1002L);
        // 阴性对照: 起点放到很早 → 决定之前那笔也算进来 (证明起点在起作用)
        assertThat(semiRepo.findLiveProductionBatchIdsPostedToSemiSince(F, row.getId(), since.minusDays(365)))
                .containsExactlyInAnyOrder(1001L, 1002L);
        assertThat(semiRepo.findLiveProductionBatchIdsPostedToSemiSince(OTHER, row.getId(), since.minusDays(365)))
                .isEmpty();
    }

    private void material(String id, String sourceDocType, String sourceDocId, MaterialBatchStatus status) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(F);
        mb.setBatchNumber("BN-" + id);
        mb.setProductTypeId("PT-WIP");
        mb.setWarehouseId("WH-WKS-QCR");
        mb.setReceiptDate(LocalDate.now());
        mb.setReceiptQuantity(BigDecimal.TEN);
        mb.setQuantityUnit("kg");
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setReservedQuantity(BigDecimal.ZERO);
        mb.setStatus(status);
        mb.setSourceDocType(sourceDocType);
        mb.setSourceDocId(sourceDocId);
        mb.setCreatedBy(1L);
        materialBatchRepo.saveAndFlush(mb);
    }

    @Test
    @DisplayName("在制批次待检: 只列 sourceDocType=PRODUCTION_BATCH 的 INSPECTING (原料质检中不进); 按生产批次反查")
    void wipBatchQueries() {
        material("WIP-INSP", "PRODUCTION_BATCH", "701", MaterialBatchStatus.INSPECTING);
        material("WIP-OK", "PRODUCTION_BATCH", "702", MaterialBatchStatus.AVAILABLE);
        material("RAW-INSP", "PURCHASE_RECEIVE", "PO-1", MaterialBatchStatus.INSPECTING);

        assertThat(materialBatchRepo.findWipBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.INSPECTING))
                .extracting(MaterialBatch::getId).containsExactly("WIP-INSP");
        assertThat(materialBatchRepo.findWipBatchesByFactoryIdAndSourceDocIdIn(F, List.of("701", "702", "PO-1")))
                .extracting(MaterialBatch::getId).containsExactlyInAnyOrder("WIP-INSP", "WIP-OK");
        // 审查中 8: 偏离扣留的加锁读取 —— 只取在制批次 (原料 PO-1 不进)
        assertThat(materialBatchRepo.lockWipBatchesForDeviationHold(F, List.of("701", "702", "PO-1")))
                .extracting(MaterialBatch::getId).containsExactlyInAnyOrder("WIP-INSP", "WIP-OK");
    }

    private void fg(String id, String status) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber("FGN-" + id);
        b.setProductTypeId("PT-FG");
        b.setProducedQuantity(BigDecimal.TEN);
        b.setUnit("kg");
        b.setWarehouseId("WH-1");
        b.setStatus(status);
        fgRepo.saveAndFlush(b);
    }

    @Test
    @DisplayName("成品待检清单只列 PENDING_INSPECTION; 按批号反查; 可售查询天然排除待检")
    void finishedGoodsQueries() {
        fg("FG-PEND", FinishedGoodsBatch.Status.PENDING_INSPECTION);
        fg("FG-AVAIL", FinishedGoodsBatch.Status.AVAILABLE);
        em.clear();

        assertThat(fgRepo.findByFactoryIdAndStatusForRelease(F, FinishedGoodsBatch.Status.PENDING_INSPECTION))
                .extracting(FinishedGoodsBatch::getId).containsExactly("FG-PEND");
        assertThat(fgRepo.findByFactoryIdAndBatchNumberInForHold(F, List.of("FGN-FG-PEND", "FGN-FG-AVAIL")))
                .hasSize(2);
        // 审查中 8: 偏离扣留的加锁读取 —— 批号命中 或 同一计划
        FinishedGoodsBatch planned = fgRepo.findById("FG-AVAIL").orElseThrow();
        planned.setProductionPlanId("PLAN-QCR");
        fgRepo.saveAndFlush(planned);
        assertThat(fgRepo.lockForDeviationHold(F, List.of("FGN-FG-PEND"), List.of("PLAN-QCR")))
                .extracting(FinishedGoodsBatch::getId).containsExactlyInAnyOrder("FG-PEND", "FG-AVAIL");
        assertThat(fgRepo.lockForDeviationHold(F, List.of("#NO-BATCH#"), List.of("PLAN-QCR")))
                .extracting(FinishedGoodsBatch::getId).containsExactly("FG-AVAIL");
        assertThat(fgRepo.lockForDeviationHold(OTHER, List.of("FGN-FG-PEND"), List.of("PLAN-QCR"))).isEmpty();
        // 待检批次不进任何 status='AVAILABLE' 的候选 (这是选状态方案的理由, 钉住它)
        assertThat(fgRepo.findAvailableBatches(F, "PT-FG"))
                .extracting(FinishedGoodsBatch::getId).containsExactly("FG-AVAIL");
    }

    @Test
    @DisplayName("qc_release_records: 写入后按对象倒序读回, 工厂最近 N 条")
    void releaseRecordRoundTrip() {
        recordRepo.saveAndFlush(record("T1", "RELEASED", LocalDateTime.now().minusMinutes(5)));
        recordRepo.saveAndFlush(record("T1", "REJECTED", LocalDateTime.now()));
        recordRepo.saveAndFlush(record("T2", "RELEASED", LocalDateTime.now()));
        em.clear();

        List<QcReleaseRecord> t1 = recordRepo.findByTarget(F, QcReleaseRecord.TargetType.SEMI_FINISHED, "T1");
        assertThat(t1).extracting(QcReleaseRecord::getDecision).containsExactly("REJECTED", "RELEASED");
        assertThat(t1.get(0).getConclusion()).isEqualTo("结论-T1");
        assertThat(t1.get(0).getReleasedBy()).isEqualTo(9L);
        assertThat(recordRepo.findTop200ByFactoryIdOrderByReleasedAtDesc(F)).hasSize(3);
    }

    private static QcReleaseRecord record(String targetId, String decision, LocalDateTime at) {
        return QcReleaseRecord.builder()
                .factoryId(F).targetType(QcReleaseRecord.TargetType.SEMI_FINISHED).targetId(targetId)
                .batchNumber("BN").decision(decision).conclusion("结论-" + targetId)
                .releasedBy(9L).releasedByName("质检员").releasedAt(at).build();
    }
}
