package com.cretas.aims.service.pot;

import com.cretas.aims.controller.ProcessSheetController;
import com.cretas.aims.controller.ProductionPotController;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.pot.ProductionPot;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.pot.ProductionPotRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3-16 锅次 —— 真 Spring 上下文 + H2, 走产品真实入口:
 * 逐工序表报工 ({@link ProcessSheetController#saveRow}, 随行带 pots) → 锅次落库并分摊到批次真实节点 →
 * 按锅追溯 ({@link ProductionPotController}) / 召回影响面按锅收窄 ({@link LineageGraphAssembler#assembleImpact})。
 *
 * <p>覆盖: 记录锅次; 从原料批追到用了它的锅和这些锅出到的批次/成品; 只有分摊完整时才收窄 (阴性对照);
 * 摄像候选时间不算精确、确认后才算; 更正留痕; 工厂隔离 (别厂 404)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("S3-16 锅次: 报工随行记锅 → 按锅追溯 / 召回收窄 → 更正 / 确认 / 工厂隔离")
class ProductionPotContractTest {

    private static final String F = "POTT-FACTORY";
    private static final String OTHER = "POTT-OTHER";
    private static final String PT_SEMI = "POTT-PT-SEMI";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProcessSheetController processSheetController;
    @Autowired private ProductionPotController potController;
    @Autowired private LineageGraphAssembler lineageGraphAssembler;
    @Autowired private MaterialBatchRepository materialBatchRepo;
    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private FactoryWarehouseRepository warehouseRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @Autowired private BatchLineageEdgeRepository edgeRepo;
    @Autowired private BatchLineageClosureRepository closureRepo;
    @Autowired private ProductionPotRepository potRepo;

    private Long operatorId;
    private String planId;
    private String rawA;
    private String rawB;
    private String warehouseId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("pott_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        operatorId = userRepo.saveAndFlush(user).getId();

        ProductType p = new ProductType();
        p.setId(PT_SEMI);
        p.setFactoryId(F);
        p.setCode(PT_SEMI);
        p.setName("卤牛肉半成品");
        p.setUnit("kg");
        p.setProductCategory("SEMI_FINISHED");
        p.setIsActive(true);
        p.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(p);

        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("POTT-WH-" + UUID.randomUUID().toString().substring(0, 6));
        wh.setFactoryId(F);
        wh.setCode("WH-LOG");
        wh.setName("WH-LOG");
        wh.setType(WarehouseType.LOGISTICS);
        wh.setIsActive(true);
        warehouseId = warehouseRepo.saveAndFlush(wh).getId();

        planId = "POTT-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(F);
        plan.setPlanNumber("POTT-PN-" + UUID.randomUUID().toString().substring(0, 6));
        plan.setProductTypeId(PT_SEMI);
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);

        rawA = raw("A");
        rawB = raw("B");
    }

    private String raw(String tag) {
        MaterialBatch raw = new MaterialBatch();
        raw.setId("POTT-RAW-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        raw.setFactoryId(F);
        raw.setBatchNumber("POTT-RAWNO-" + tag + "-" + UUID.randomUUID().toString().substring(0, 4));
        raw.setMaterialTypeId("POTT-MAT");
        raw.setWarehouseId(warehouseId);
        raw.setReceiptQuantity(new BigDecimal("500"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(new BigDecimal("10"));
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        return materialBatchRepo.saveAndFlush(raw).getId();
    }

    private static ProductionPotService.PotLink mat(String id, String qty) {
        return new ProductionPotService.PotLink(LineageEdgeRecorder.NODE_MATERIAL_BATCH, id, new BigDecimal(qty), "kg");
    }

    private static ProcessSheetPotEntry pot(int n, String out, List<ProductionPotService.PotLink> inputs) {
        ProcessSheetPotEntry e = new ProcessSheetPotEntry();
        e.setPotNumber(n);
        e.setEquipmentLabel(n + " 号夹层锅");
        e.setStartedAt(LocalDateTime.of(2026, 10, 28, 8 + n, 0));
        e.setEndedAt(LocalDateTime.of(2026, 10, 28, 8 + n, 45));
        e.setOutputQuantity(new BigDecimal(out));
        e.setProcessParams(Map.of("temperatureC", 98, "durationMin", 45));
        e.setInputs(inputs);
        return e;
    }

    /** 卤煮一行: A 60kg + B 40kg → 在制 80kg, 随行两锅。 */
    private ProcessSheetRowRequest cookRow(String clientRowId, List<ProcessSheetPotEntry> pots) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId(clientRowId);
        r.setProcessCode("lzhu");
        r.setProcessOrder(1);
        r.setProcessName("卤煮");
        r.setProductTypeId(PT_SEMI);
        r.setFinished(false);
        r.setInputQuantity(new BigDecimal("100"));
        r.setOutputQuantity(new BigDecimal("80"));
        r.setUnit("kg");
        ProcessSheetRowRequest.RawInput a = new ProcessSheetRowRequest.RawInput();
        a.setMaterialBatchId(rawA);
        a.setQuantity(new BigDecimal("60"));
        ProcessSheetRowRequest.RawInput b = new ProcessSheetRowRequest.RawInput();
        b.setMaterialBatchId(rawB);
        b.setQuantity(new BigDecimal("40"));
        r.setRawMaterialInputs(List.of(a, b));
        r.setPots(pots);
        return r;
    }

    private ProcessSheetRowResult save(ProcessSheetRowRequest req) {
        return processSheetController.saveRow(F, planId, operatorId, "卤煮甲", req).getData();
    }

    private String wipOf(ProcessSheetRowResult row) {
        return materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                F, "PRODUCTION_BATCH", String.valueOf(row.getBatchId())).orElseThrow().getId();
    }

    private void edge(String type, String sourceType, String sourceId, String targetType, String targetId, String qty) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(F);
        e.setEdgeType(type);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(new BigDecimal(qty));
        e.setUnit("kg");
        e.setEventTime(LocalDateTime.now());
        edgeRepo.saveAndFlush(e);
    }

    private void closure(String aType, String aId, String dType, String dId, int depth) {
        BatchLineageClosure c = new BatchLineageClosure();
        c.setId(UUID.randomUUID().toString());
        c.setFactoryId(F);
        c.setAncestorType(aType);
        c.setAncestorId(aId);
        c.setDescendantType(dType);
        c.setDescendantId(dId);
        c.setDepth(depth);
        closureRepo.saveAndFlush(c);
    }

    @Test
    @DisplayName("报工随行记两锅: 锅次落库、投入/产出分摊到批次真实节点、人工工时不在锅上、时长按确认的时间算")
    void processSheetRecordsPotsInTheSameReport() {
        ProcessSheetRowResult row = save(cookRow("pott-1", List.of(
                pot(1, "50", List.of(mat(rawA, "60"))),
                pot(2, "30", List.of(mat(rawB, "40"))))));
        assertThat(row.getBatchId()).isNotNull();

        ProductionPotService.BatchPotsView view = potController.listForBatch(F, row.getBatchId()).getData();
        System.out.println("POT_READING pots=" + view.potCount() + " inputs=" + view.inputAllocation()
                + " outputs=" + view.outputAllocation());
        assertThat(view.potCount()).isEqualTo(2);
        assertThat(view.pendingCount()).isZero();
        ProductionPotService.PotView p1 = view.pots().get(0);
        assertThat(p1.potNumber()).isEqualTo(1);
        assertThat(p1.source()).isEqualTo(ProductionPot.SOURCE_PROCESS_SHEET);
        assertThat(p1.processSheetRowId()).isNotNull();
        assertThat(p1.durationMinutes()).isEqualTo(45L);
        assertThat(p1.processParams()).containsEntry("temperatureC", 98);
        assertThat(p1.inputs()).singleElement().satisfies(i -> {
            assertThat(i.nodeId()).isEqualTo(rawA);
            assertThat(i.quantity()).isEqualByComparingTo("60");
        });
        String wip = wipOf(row);
        assertThat(p1.outputs()).singleElement().satisfies(o -> assertThat(o.nodeId()).isEqualTo(wip));
        // 批次级: 两个投入节点都已全部分到锅上; 产出 50+30 = 80 = 批次产出
        assertThat(view.inputAllocation()).hasSize(2).allSatisfy(a -> assertThat(a.complete()).isTrue());
        assertThat(view.outputAllocation()).singleElement().satisfies(a -> {
            assertThat(a.allocatedQuantity()).isEqualByComparingTo("80");
            assertThat(a.complete()).isTrue();
        });

        // 旧契约 upsert 重放同一组锅号: 不重记
        save(cookRow("pott-1", List.of(
                pot(1, "50", List.of(mat(rawA, "60"))),
                pot(2, "30", List.of(mat(rawB, "40"))))));
        assertThat(potRepo.findByFactoryIdAndProductionBatchIdIn(F, List.of(row.getBatchId()))).hasSize(2);
    }

    @Test
    @DisplayName("报工里的锅次不合法 ⇒ 整笔报工回滚 (投入不是这一行的批次 / 各锅之和超批次量)")
    void invalidPotRejectsTheWholeReport() {
        String foreignRaw = raw("C");
        assertThatThrownBy(() -> save(cookRow("pott-bad1", List.of(pot(1, "40", List.of(mat(foreignRaw, "10")))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ProductionPotService.CODE_NOT_A_BATCH_NODE));
        assertThatThrownBy(() -> save(cookRow("pott-bad2", List.of(
                pot(1, "40", List.of(mat(rawA, "50"))),
                pot(2, "40", List.of(mat(rawA, "20")))))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ProductionPotService.CODE_OVER_ALLOCATED));
        // 草稿不收锅次 (不静默丢)
        assertThatThrownBy(() -> processSheetController.saveDraft(F, planId, operatorId,
                cookRow("pott-draft", List.of(pot(1, "40", List.of())))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("草稿不保存锅次");
    }

    @Test
    @DisplayName("按锅追溯: 原料 A 只进了第 1 锅 → 收窄到 1 / 2 锅; 只来自第 2 锅的成品被排除; 下游成品可见")
    void traceFromMaterialNarrowsToPotsAndFinishedGoods() {
        ProcessSheetRowResult row = save(cookRow("pott-2", List.of(
                pot(1, "50", List.of(mat(rawA, "60"))),
                pot(2, "30", List.of(mat(rawB, "40"))))));
        Long pb = row.getBatchId();
        String wip = wipOf(row);
        // 这个批次另外直接装出两个成品批: FG1 只来自第 1 锅, FG2 只来自第 2 锅
        String fg1 = "POTT-FG1-" + UUID.randomUUID().toString().substring(0, 6);
        String fg2 = "POTT-FG2-" + UUID.randomUUID().toString().substring(0, 6);
        edge(LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED, LineageEdgeRecorder.NODE_PRODUCTION_BATCH,
                String.valueOf(pb), LineageEdgeRecorder.NODE_FINISHED_BATCH, fg1, "20");
        edge(LineageEdgeRecorder.EDGE_PRODUCTION_TO_FINISHED, LineageEdgeRecorder.NODE_PRODUCTION_BATCH,
                String.valueOf(pb), LineageEdgeRecorder.NODE_FINISHED_BATCH, fg2, "10");
        List<ProductionPotService.PotView> pots = potController.listForBatch(F, pb).getData().pots();
        potController.correct(F, pots.get(0).id(), new ProductionPotService.CorrectionCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(new ProductionPotService.PotLink(LineageEdgeRecorder.NODE_MATERIAL_BATCH, wip, new BigDecimal("50"), "kg"),
                        new ProductionPotService.PotLink(LineageEdgeRecorder.NODE_FINISHED_BATCH, fg1, new BigDecimal("20"), "kg")),
                null, "补录装箱去向"), operatorId, "主管乙");
        potController.correct(F, pots.get(1).id(), new ProductionPotService.CorrectionCommand(
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(new ProductionPotService.PotLink(LineageEdgeRecorder.NODE_MATERIAL_BATCH, wip, new BigDecimal("30"), "kg"),
                        new ProductionPotService.PotLink(LineageEdgeRecorder.NODE_FINISHED_BATCH, fg2, new BigDecimal("10"), "kg")),
                null, "补录装箱去向"), operatorId, "主管乙");
        // 在制批再往下做成成品 FG3 (闭包)
        String fg3 = "POTT-FG3-" + UUID.randomUUID().toString().substring(0, 6);
        closure(LineageEdgeRecorder.NODE_MATERIAL_BATCH, wip, LineageEdgeRecorder.NODE_FINISHED_BATCH, fg3, 2);

        PotTraceService.MaterialPotTrace trace = potController.consumers(F, "MATERIAL_BATCH", rawA).getData();
        System.out.println("POT_TRACE_READING " + trace);
        assertThat(trace.batches()).singleElement().satisfies(s -> {
            assertThat(s.productionBatchId()).isEqualTo(pb);
            assertThat(s.potCount()).isEqualTo(2);
            assertThat(s.narrowed()).isTrue();
            assertThat(s.affectedPots()).extracting(PotTraceService.PotBrief::potNumber).containsExactly(1);
            assertThat(s.affectedOutputs()).extracting(PotTraceService.NodeRef::nodeId).containsExactlyInAnyOrder(wip, fg1);
            assertThat(s.excludedOutputs()).extracting(PotTraceService.NodeRef::nodeId).containsExactly(fg2);
            assertThat(s.downstreamFinishedGoods()).extracting(PotTraceService.NodeRef::nodeId)
                    .containsExactlyInAnyOrder(fg1, fg3);
        });

        // 一锅 → 出到哪 / 最终成品
        PotTraceService.PotForwardTrace fwd = potController.forward(F, pots.get(1).id()).getData();
        assertThat(fwd.finishedGoods()).extracting(PotTraceService.NodeRef::nodeId).containsExactlyInAnyOrder(fg2, fg3);

        // 在制批来自哪几锅: 两锅都出到了它 (已全部分完)
        PotTraceService.OutputPotTrace producers = potController.producers(F, "MATERIAL_BATCH", wip).getData();
        assertThat(producers.narrowed()).isTrue();
        assertThat(producers.pots()).extracting(ProductionPotService.PotView::potNumber).containsExactly(1, 2);

        // 召回影响面 (产品真实入口的组装器) 带上按锅收窄
        TraceabilityDTO.ImpactResponse impact = lineageGraphAssembler.assembleImpact(F, "MATERIAL_BATCH", rawA, 0);
        assertThat(impact.getPotScopes()).isNotNull().singleElement().satisfies(s -> {
            assertThat(s.narrowed()).isTrue();
            assertThat(s.affectedPots()).extracting(PotTraceService.PotBrief::potNumber).containsExactly(1);
        });
    }

    @Test
    @DisplayName("阴性对照: 原料没全部分到锅上 ⇒ 不收窄, 全部锅都算受影响, 也不排除任何产出")
    void incompleteAllocationNeverNarrows() {
        ProcessSheetRowResult row = save(cookRow("pott-3", List.of(
                pot(1, "50", List.of(mat(rawA, "30"))),     // A 只分了 30 / 60
                pot(2, "30", List.of()))));                 // 第 2 锅不知道用了哪批料
        assertThat(row.getWarnings()).anyMatch(w -> w.contains("没有填写用了哪批料"));
        PotTraceService.MaterialPotTrace trace = potController.consumers(F, "MATERIAL_BATCH", rawA).getData();
        assertThat(trace.batches()).singleElement().satisfies(s -> {
            assertThat(s.narrowed()).isFalse();
            assertThat(s.reason()).contains("未分完");
            assertThat(s.affectedPots()).extracting(PotTraceService.PotBrief::potNumber).containsExactly(1, 2);
            assertThat(s.excludedOutputs()).isEmpty();
        });
        // 没有锅次的批次: 直接查原料也要说明「按整批」, 不能是空
        ProcessSheetRowRequest noPots = cookRow("pott-3b", null);
        noPots.setRawMaterialInputs(List.of(noPots.getRawMaterialInputs().get(1)));
        noPots.setInputQuantity(new BigDecimal("40"));
        noPots.setOutputQuantity(new BigDecimal("30"));
        save(noPots);
        PotTraceService.MaterialPotTrace traceB = potController.consumers(F, "MATERIAL_BATCH", rawB).getData();
        assertThat(traceB.batches()).anySatisfy(s -> {
            assertThat(s.potCount()).isZero();
            assertThat(s.narrowed()).isFalse();
            assertThat(s.reason()).contains("没有锅次记录");
        });
    }

    @Test
    @DisplayName("摄像候选时间不算精确: 待确认、不算时长; 主管确认后才算, 原来源留在更正记录里")
    void cameraCandidateNeedsConfirmation() {
        ProcessSheetRowResult row = save(cookRow("pott-4", null));
        ProductionPotService.PotView p = potController.record(F, new ProductionPotService.PotCommand(
                row.getBatchId(), null, null, "lzhu", "卤煮", 1, "1 号锅",
                LocalDateTime.of(2026, 10, 28, 9, 0), LocalDateTime.of(2026, 10, 28, 9, 40),
                "CAMERA_CANDIDATE", "PENDING_CONFIRMATION", null, null, new BigDecimal("80"), "kg",
                Map.of(), List.of(mat(rawA, "60"), mat(rawB, "40")), List.of(), null), operatorId).getData();
        assertThat(p.timeConfirmed()).isFalse();
        assertThat(p.durationMinutes()).isNull();
        assertThat(potController.listForBatch(F, row.getBatchId()).getData().pendingCount()).isEqualTo(1);

        assertThatThrownBy(() -> potController.confirm(F, p.id(),
                new ProductionPotService.ConfirmCommand(null, null, " "), operatorId, "主管乙"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原因");
        ProductionPotService.PotView c = potController.confirm(F, p.id(), new ProductionPotService.ConfirmCommand(
                null, LocalDateTime.of(2026, 10, 28, 9, 35), "看了监控与锅边记录"), operatorId, "主管乙").getData();
        assertThat(c.timeConfirmed()).isTrue();
        assertThat(c.timeSource()).isEqualTo("MANUAL");
        assertThat(c.bindingStatus()).isEqualTo("CONFIRMED");
        assertThat(c.durationMinutes()).isEqualTo(35L);
        assertThat(c.corrections()).extracting(ProductionPotService.CorrectionView::fieldName)
                .contains("endedAt", "bindingStatus", "timeSource", "timeConfirmed");
        assertThat(c.corrections()).anySatisfy(x -> {
            assertThat(x.fieldName()).isEqualTo("timeSource");
            assertThat(x.oldValue()).isEqualTo("CAMERA_CANDIDATE");
            assertThat(x.action()).isEqualTo("CONFIRM");
        });
    }

    @Test
    @DisplayName("更正保留原值; 没写原因拒绝; 作废后让出锅号、不再计入")
    void correctionsArePreserved() {
        ProcessSheetRowResult row = save(cookRow("pott-5", List.of(pot(1, "80", List.of()))));
        Long potId = potController.listForBatch(F, row.getBatchId()).getData().pots().get(0).id();
        assertThatThrownBy(() -> potController.correct(F, potId, new ProductionPotService.CorrectionCommand(
                null, null, null, null, null, null, null, new BigDecimal("78"), null, null, null, null, null, null),
                operatorId, "x")).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ProductionPotService.CODE_REASON_REQUIRED));
        ProductionPotService.PotView v = potController.correct(F, potId, new ProductionPotService.CorrectionCommand(
                null, null, null, null, null, null, null, new BigDecimal("78"), null, null, null, null, null, "复秤"),
                operatorId, "主管乙").getData();
        assertThat(v.outputQuantity()).isEqualByComparingTo("78");
        assertThat(v.corrections()).singleElement().satisfies(c -> {
            assertThat(c.oldValue()).isEqualTo("80");
            assertThat(c.newValue()).isEqualTo("78");
            assertThat(c.reason()).isEqualTo("复秤");
        });
        potController.voidPot(F, potId, Map.of("reason", "录错批次"), operatorId, "主管乙");
        assertThat(potController.listForBatch(F, row.getBatchId()).getData().potCount()).isZero();
        assertThatThrownBy(() -> potController.correct(F, potId, new ProductionPotService.CorrectionCommand(
                2, null, null, null, null, null, null, null, null, null, null, null, null, "x"), operatorId, "x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ProductionPotService.CODE_VOIDED));
        // 作废的锅让出锅号
        potController.record(F, new ProductionPotService.PotCommand(row.getBatchId(), null, null, "lzhu", null, 1, null,
                null, null, null, null, null, null, null, null, null, null, null, null), operatorId);
        assertThat(potController.listForBatch(F, row.getBatchId()).getData().potCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("工厂隔离: 别厂查批次锅次 / 追溯 / 录入 / 更正一律 404")
    void otherFactoryCannotSeeOrWrite() {
        ProcessSheetRowResult row = save(cookRow("pott-6", List.of(pot(1, "80", List.of(mat(rawA, "60"))))));
        Long potId = potController.listForBatch(F, row.getBatchId()).getData().pots().get(0).id();
        assertThatThrownBy(() -> potController.listForBatch(OTHER, row.getBatchId()))
                .isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThatThrownBy(() -> potController.consumers(OTHER, "MATERIAL_BATCH", rawA))
                .isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThatThrownBy(() -> potController.forward(OTHER, potId))
                .isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThatThrownBy(() -> potController.record(OTHER, new ProductionPotService.PotCommand(row.getBatchId(), null,
                null, "lzhu", null, 2, null, null, null, null, null, null, null, null, null, null, null, null, null), operatorId))
                .isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThatThrownBy(() -> potController.correct(OTHER, potId, new ProductionPotService.CorrectionCommand(
                2, null, null, null, null, null, null, null, null, null, null, null, null, "x"), operatorId, "x"))
                .isInstanceOf(BusinessException.class).satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThat(potController.listForBatches(OTHER, String.valueOf(row.getBatchId())).getData()).isEmpty();
    }
}
