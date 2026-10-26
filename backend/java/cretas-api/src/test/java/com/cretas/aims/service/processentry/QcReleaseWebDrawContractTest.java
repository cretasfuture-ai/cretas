package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.service.quality.release.QcReleaseService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺口 11 质检放行 —— web 逐道录入 (ProcessSheetService.saveRow, 真 Spring 上下文 + H2) 的两种半成品形态:
 *
 * <ol>
 *   <li>本计划在制半成品批次 (CLK-W → WIP MaterialBatch): 需检产品产出即「质检中」, 下道选它保存被拒;
 *       放行 (QcReleaseService) 后可选; 没开需检的产品照旧 AVAILABLE、照旧可选 (阴性对照)。</li>
 *   <li>常驻半成品库 (SFI) 投料: 待检被拒 / 放行后可选 / 未需检照旧。</li>
 * </ol>
 *
 * <p>全部走产品真实入口 (saveRow → resolveEdges), 策略 / 守卫 / 放行服务都是 Spring 注入的真 Bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("质检放行 · web 逐道录入下道领用闸 (在制批次 / 常驻半成品)")
class QcReleaseWebDrawContractTest {

    private static final String FACTORY_ID = "QCW-FACTORY";
    private static final String PT_REQUIRED = "QCW-PT-SEMI-REQ";
    private static final String PT_PLAIN = "QCW-PT-SEMI-PLAIN";
    private static final String PT_FINAL = "QCW-PT-FINAL";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProcessSheetService processSheetService;
    @Autowired private QcReleaseService qcReleaseService;
    @Autowired private MaterialBatchRepository materialBatchRepo;
    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private FactoryWarehouseRepository warehouseRepo;
    @Autowired private SemiFinishedInventoryRepository sfiRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @Autowired private QcReleaseRecordRepository releaseRecordRepo;

    private Long operatorId;
    private String planId;
    private String rawBatchId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("qcw_clerk_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        operatorId = userRepo.saveAndFlush(user).getId();

        seedProductType(PT_REQUIRED, true);
        seedProductType(PT_PLAIN, false);
        seedProductType(PT_FINAL, false);

        FactoryWarehouse wh = new FactoryWarehouse();
        String whId = "QCW-WH-LOG-" + UUID.randomUUID().toString().substring(0, 8);
        wh.setId(whId);
        wh.setFactoryId(FACTORY_ID);
        wh.setCode("WH-LOG");
        wh.setName("质检放行测试物流仓");
        wh.setType(WarehouseType.LOGISTICS);
        wh.setIsActive(true);
        warehouseRepo.saveAndFlush(wh);
        // 小结成品入库落成品仓 (WarehouseResolver#resolveFinishedGoodsId 按 code=WH-FG 解析)
        FactoryWarehouse fgWh = new FactoryWarehouse();
        fgWh.setId("QCW-WH-FG-" + UUID.randomUUID().toString().substring(0, 8));
        fgWh.setFactoryId(FACTORY_ID);
        fgWh.setCode("WH-FG");
        fgWh.setName("质检放行测试成品仓");
        fgWh.setType(WarehouseType.FINISHED);
        fgWh.setIsActive(true);
        warehouseRepo.saveAndFlush(fgWh);

        planId = "QCW-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("QCW-PN-" + System.currentTimeMillis() % 100000);
        plan.setProductTypeId(PT_FINAL);
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);

        rawBatchId = "QCW-MB-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("QCW-RAW-" + System.currentTimeMillis() % 100000);
        raw.setMaterialTypeId("QCW-MATTYPE");
        raw.setWarehouseId(whId);
        raw.setReceiptQuantity(new BigDecimal("500.00"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(new BigDecimal("10.00"));
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw);
    }

    private void seedProductType(String id, boolean inspectionRequired) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId(FACTORY_ID);
        product.setCode(id);
        product.setName(id);
        product.setUnit("kg");
        product.setCategory("SEMI_FINISHED");
        product.setProductCategory("SEMI_FINISHED");
        product.setIsActive(true);
        product.setInspectionRequired(inspectionRequired);
        product.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(product);
    }

    private ProcessSheetRowRequest baseReq(String clientRowId, String processCode, int order, String output,
                                           String productTypeId) {
        ProcessSheetRowRequest r = new ProcessSheetRowRequest();
        r.setClientRowId(clientRowId);
        r.setProcessCode(processCode);
        r.setProcessOrder(order);
        r.setProcessName(processCode);
        r.setProductTypeId(productTypeId);
        r.setFinished(false);
        r.setOutputQuantity(new BigDecimal(output));
        r.setUnit("kg");
        return r;
    }

    /** 首道 (原料 → 在制半成品), 产出产品 = outputProductTypeId。 */
    private ProcessSheetRowResult saveFirstStep(String clientRowId, String outputProductTypeId) {
        ProcessSheetRowRequest req = baseReq(clientRowId, "xiuyou", 1, "80", outputProductTypeId);
        req.setInputQuantity(new BigDecimal("100"));
        RawInput ri = new RawInput();
        ri.setMaterialBatchId(rawBatchId);
        ri.setQuantity(new BigDecimal("100"));
        req.setRawMaterialInputs(List.of(ri));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    /** 下道 (选上一道在制批次投料)。 */
    private ProcessSheetRowResult saveSecondStep(String clientRowId, String upstreamBatchNumber) {
        ProcessSheetRowRequest req = baseReq(clientRowId, "shuzhi", 2, "70", PT_FINAL);
        req.setInputQuantity(new BigDecimal("80"));
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber(upstreamBatchNumber);
        u.setFeedQuantityKg(new BigDecimal("80"));
        req.setUpstreamSources(List.of(u));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    private MaterialBatch wipOf(ProcessSheetRowResult result) {
        return materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(result.getBatchId())).orElseThrow();
    }

    // ───────────────────────── 在制批次 (CLK-W) ─────────────────────────

    @Test
    @DisplayName("需检半成品的在制批次: 产出即质检中 → 下道保存被拒 (QC_WIP_PENDING_INSPECTION) → 放行后可保存")
    void requiredWipIsHeldUntilReleased() {
        ProcessSheetRowResult up = saveFirstStep("qcw-up-req", PT_REQUIRED);
        MaterialBatch wip = wipOf(up);
        System.out.println("WIP_STATUS_AFTER_OUTPUT=" + wip.getStatus());
        assertThat(wip.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);

        assertThatThrownBy(() -> saveSecondStep("qcw-down-req", up.getBatchNumber()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.WIP_PENDING);
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains(up.getBatchNumber());
                });

        // 放行出现在待检清单里, 放行后下道照常保存
        assertThat(qcReleaseService.listPending(FACTORY_ID))
                .anySatisfy(item -> {
                    assertThat(item.targetType()).isEqualTo(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH);
                    assertThat(item.targetId()).isEqualTo(wip.getId());
                });
        QcReleaseRecord record = qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, wip.getId(), "RELEASED", "感官正常, 放行"),
                operatorId, "质检员甲");
        assertThat(record.getReleasedBy()).isEqualTo(operatorId);
        assertThat(record.getReleasedAt()).isNotNull();
        assertThat(record.getConclusion()).isEqualTo("感官正常, 放行");
        assertThat(materialBatchRepo.findById(wip.getId()).orElseThrow().getStatus())
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
        assertThat(releaseRecordRepo.findByTarget(FACTORY_ID,
                QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, wip.getId())).hasSize(1);

        ProcessSheetRowResult down = saveSecondStep("qcw-down-req-2", up.getBatchNumber());
        assertThat(down.isMaterialized()).isTrue();
    }

    @Test
    @DisplayName("阴性对照: 没开需检的半成品 → 在制批次照旧 AVAILABLE, 下道照常保存")
    void plainWipIsUnaffected() {
        ProcessSheetRowResult up = saveFirstStep("qcw-up-plain", PT_PLAIN);
        assertThat(wipOf(up).getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        assertThat(saveSecondStep("qcw-down-plain", up.getBatchNumber()).isMaterialized()).isTrue();
    }

    @Test
    @DisplayName("判不合格: 在制批次 → DEFECTIVE, 下道被拒 (QC_WIP_REJECTED); 已处理过的不能再放行")
    void rejectedWipCannotBeDrawn() {
        ProcessSheetRowResult up = saveFirstStep("qcw-up-rej", PT_REQUIRED);
        MaterialBatch wip = wipOf(up);
        qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, wip.getId(), "REJECTED", "异味, 不合格"),
                operatorId, null);
        assertThatThrownBy(() -> saveSecondStep("qcw-down-rej", up.getBatchNumber()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.WIP_REJECTED));
        assertThatThrownBy(() -> qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, wip.getId(), "RELEASED", "改判"), operatorId, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_NOT_PENDING));
    }

    // ───────────────────────── 常驻半成品 (SFI) 投料 ─────────────────────────

    private SemiFinishedInventory seedSfi(String no, String inspectionStatus) {
        return sfiRepo.saveAndFlush(SemiFinishedInventory.builder()
                .factoryId(FACTORY_ID)
                .intermediateBatchNo(no)
                .productTypeId(PT_REQUIRED)
                .producedQuantity(new BigDecimal("50"))
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("50"))
                .unit("kg")
                .processOrder(1)
                .inspectionStatus(inspectionStatus)
                .build());
    }

    private ProcessSheetRowResult saveSfiFeed(String clientRowId, String sfiNo) {
        ProcessSheetRowRequest req = baseReq(clientRowId, "qidiao", 5, "40", PT_FINAL);
        req.setFinished(true);
        req.setInputQuantity(new BigDecimal("45"));
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber(sfiNo);
        u.setFeedQuantityKg(new BigDecimal("45"));
        u.setSemiFinished(true);
        req.setUpstreamSources(List.of(u));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    @Test
    @DisplayName("SFI 待检 → 保存被拒 (QC_SEMI_PENDING_INSPECTION); 放行后可保存; 未需检 (null) 照旧")
    void sfiFeedHonoursInspection() {
        SemiFinishedInventory pending = seedSfi("QCW-SFI-P", SemiFinishedInventory.InspectionStatus.PENDING);
        assertThatThrownBy(() -> saveSfiFeed("qcw-sfi-p", "QCW-SFI-P"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.SEMI_PENDING));

        qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.SEMI_FINISHED, String.valueOf(pending.getId()), "RELEASED", "合格"),
                operatorId, null);
        assertThat(sfiRepo.findById(pending.getId()).orElseThrow().getInspectionStatus())
                .isEqualTo(SemiFinishedInventory.InspectionStatus.RELEASED);
        assertThat(saveSfiFeed("qcw-sfi-p2", "QCW-SFI-P").isMaterialized()).isTrue();

        seedSfi("QCW-SFI-LEGACY", null);
        assertThat(saveSfiFeed("qcw-sfi-legacy", "QCW-SFI-LEGACY").isMaterialized()).isTrue();
    }

    // ───────────────────────── 成品入库 (小结) 扣留: 真实入口 ─────────────────────────

    private String safetyStockPlan(String productTypeId) {
        String id = "QCW-SPLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("QCW-SPN-" + UUID.randomUUID().toString().substring(0, 6));
        plan.setProductTypeId(productTypeId);
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setSourceType(com.cretas.aims.entity.enums.PlanSourceType.SAFETY_STOCK);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);
        return id;
    }

    @Autowired private com.cretas.aims.service.yield.InterimSettleService interimSettleService;
    @Autowired private com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository finishedGoodsBatchRepo;

    private String settleFinishedRow(String productTypeId, boolean required, String sfiNo) {
        ProductType fg = new ProductType();
        fg.setId(productTypeId);
        fg.setFactoryId(FACTORY_ID);
        fg.setCode(productTypeId);
        fg.setName(productTypeId);
        fg.setUnit("kg");
        fg.setProductCategory("FINISHED_PRODUCT");
        fg.setIsActive(true);
        fg.setInspectionRequired(required);
        fg.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(fg);
        String plan = safetyStockPlan(productTypeId);
        seedSfi(sfiNo, null);
        ProcessSheetRowRequest req = baseReq("qcw-fin-" + sfiNo, "qidiao", 5, "40", productTypeId);
        req.setFinished(true);
        req.setInputQuantity(new BigDecimal("45"));
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber(sfiNo);
        u.setFeedQuantityKg(new BigDecimal("45"));
        u.setSemiFinished(true);
        req.setUpstreamSources(List.of(u));
        processSheetService.saveRow(FACTORY_ID, plan, req, operatorId);
        interimSettleService.interimSettle(FACTORY_ID, plan, operatorId);
        List<com.cretas.aims.entity.inventory.FinishedGoodsBatch> fgs =
                finishedGoodsBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY_ID, plan);
        assertThat(fgs).as("小结应产出 1 个成品批次").hasSize(1);
        return fgs.get(0).getStatus();
    }

    @Test
    @DisplayName("小结成品入库: 需检成品 → PENDING_INSPECTION; 阴性对照: 未需检 → AVAILABLE")
    void interimSettleHoldsRequiredFinishedGoods() {
        String held = settleFinishedRow("QCW-PT-FG-REQ", true, "QCW-SFI-FG1");
        System.out.println("INTERIM_FG_STATUS_REQUIRED=" + held);
        assertThat(held).isEqualTo(com.cretas.aims.entity.inventory.FinishedGoodsBatch.Status.PENDING_INSPECTION);
        assertThat(settleFinishedRow("QCW-PT-FG-OFF", false, "QCW-SFI-FG2"))
                .isEqualTo(com.cretas.aims.entity.inventory.FinishedGoodsBatch.Status.AVAILABLE);
    }

    @Test
    @DisplayName("放行必须写结论: 空结论 → 400, 状态不变")
    void conclusionIsRequired() {
        SemiFinishedInventory pending = seedSfi("QCW-SFI-NC", SemiFinishedInventory.InspectionStatus.PENDING);
        assertThatThrownBy(() -> qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.SEMI_FINISHED, String.valueOf(pending.getId()), "RELEASED", "  "),
                operatorId, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThat(sfiRepo.findById(pending.getId()).orElseThrow().getInspectionStatus())
                .isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
    }
}
