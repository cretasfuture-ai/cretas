package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.quality.release.QcReleaseService;
import com.cretas.aims.service.yield.InterimSettleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺口 13 CCP 监控 —— 真 Spring 上下文 + H2, 全部走产品真实入口:
 * 逐道录入保存 (saveRow) 产出在制批次 → 该工序报工录 CCP ({@link CcpMonitoringService#record}) →
 * 质检放行 ({@link QcReleaseService#decide}) / 小结成品入库 ({@link InterimSettleService})。
 *
 * <ul>
 *   <li>限值内 → 无偏离, 产出不受影响 (没开需检的产品照旧可用);</li>
 *   <li>超限 → 自动生成偏离, 已有产出立刻待检, 之后入账的产出 (含成品) 也待检, 放行被拒;</li>
 *   <li>偏离处理后 → 放行成功;</li>
 *   <li>少录一项 → 拒绝 (CCP_READING_MISSING), 一条都不写;</li>
 *   <li>批次导出包含整条计划链的记录。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("CCP 监控: 录入 → 偏离 → 待检/挡放行 → 处理 → 放行")
class CcpMonitoringContractTest {

    private static final String FACTORY_ID = "CCPT-FACTORY";
    private static final String WP_COOK = "CCPT-WP-COOK";
    private static final String PT_SEMI = "CCPT-PT-SEMI";
    private static final String PT_FG = "CCPT-PT-FG";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProcessSheetService processSheetService;
    @Autowired private CcpMonitoringService ccpService;
    @Autowired private QcReleaseService qcReleaseService;
    @Autowired private InterimSettleService interimSettleService;
    @Autowired private MaterialBatchRepository materialBatchRepo;
    @Autowired private ProductionPlanRepository planRepo;
    @Autowired private UserRepository userRepo;
    @Autowired private FactoryWarehouseRepository warehouseRepo;
    @Autowired private SemiFinishedInventoryRepository sfiRepo;
    @Autowired private ProductTypeRepository productTypeRepo;
    @Autowired private HaccpCheckpointRepository checkpointRepo;
    @Autowired private HaccpMonitoringRecordRepository recordRepo;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsRepo;
    @Autowired private CcpReportFacade ccpReportFacade;
    @Autowired private com.cretas.aims.controller.YieldReportController yieldReportController;
    @Autowired private com.cretas.aims.controller.ProcessSheetController processSheetController;

    private Long operatorId;
    private String planId;
    private String rawBatchId;
    private Long cpTempId;
    private Long cpTimeId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("ccpt_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        operatorId = userRepo.saveAndFlush(user).getId();

        product(PT_SEMI, "SEMI_FINISHED");
        product(PT_FG, "FINISHED_PRODUCT");
        warehouse("WH-LOG", WarehouseType.LOGISTICS);
        String fgWh = warehouse("WH-FG", WarehouseType.FINISHED);
        assertThat(fgWh).isNotBlank();

        planId = "CCPT-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("CCPT-PN-" + UUID.randomUUID().toString().substring(0, 6));
        plan.setProductTypeId(PT_FG);
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

        rawBatchId = "CCPT-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("CCPT-RAWNO-" + UUID.randomUUID().toString().substring(0, 6));
        raw.setMaterialTypeId("CCPT-MAT");
        raw.setWarehouseId(warehouseRepo.findAll().stream()
                .filter(w -> FACTORY_ID.equals(w.getFactoryId()) && "WH-LOG".equals(w.getCode()))
                .findFirst().orElseThrow().getId());
        raw.setReceiptQuantity(new BigDecimal("500"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(new BigDecimal("10"));
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw);

        // 卤煮工序挂两个 CCP: 中心温度 ≥75 (上限 100), 保温时间 15~60 min
        cpTempId = checkpointRepo.saveAndFlush(checkpoint("CCP-T", "中心温度", "75", "100", "℃")).getId();
        cpTimeId = checkpointRepo.saveAndFlush(checkpoint("CCP-H", "保温时间", "15", "60", "min")).getId();
        // 另一个工序的 CCP 不该被要求
        HaccpCheckpoint other = checkpoint("CCP-X", "金检灵敏度", "0", "1", "mm");
        other.setWorkProcessId("CCPT-WP-OTHER");
        checkpointRepo.saveAndFlush(other);
    }

    private void product(String id, String category) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(FACTORY_ID);
        p.setCode(id);
        p.setName(id);
        p.setUnit("kg");
        p.setProductCategory(category);
        p.setIsActive(true);
        p.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(p);   // 不开需检 —— 待检完全来自 CCP 偏离
    }

    private String warehouse(String code, WarehouseType type) {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId("CCPT-" + code + "-" + UUID.randomUUID().toString().substring(0, 6));
        wh.setFactoryId(FACTORY_ID);
        wh.setCode(code);
        wh.setName(code);
        wh.setType(type);
        wh.setIsActive(true);
        return warehouseRepo.saveAndFlush(wh).getId();
    }

    private HaccpCheckpoint checkpoint(String code, String name, String min, String max, String unit) {
        return HaccpCheckpoint.builder()
                .factoryId(FACTORY_ID).checkpointCode(code).name(name).hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal(min)).criticalLimitMax(new BigDecimal(max)).unit(unit)
                .correctiveAction("继续加热 5 分钟后复测").workProcessId(WP_COOK).active(true).build();
    }

    private ProcessSheetRowResult saveCookRow(String clientRowId) {
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
        ProcessSheetRowRequest.RawInput ri = new ProcessSheetRowRequest.RawInput();
        ri.setMaterialBatchId(rawBatchId);
        ri.setQuantity(new BigDecimal("100"));
        r.setRawMaterialInputs(List.of(ri));
        return processSheetService.saveRow(FACTORY_ID, planId, r, operatorId);
    }

    private CcpMonitoringService.RecordResult recordCcp(ProcessSheetRowResult row, String temp, String hold) {
        return ccpService.record(FACTORY_ID, new CcpMonitoringService.RecordCommand(
                row.getBatchNumber(), row.getBatchId(), null, WP_COOK, 1, CcpMonitoringService.SOURCE_WEB,
                List.of(new CcpMonitoringService.Reading(cpTempId, new BigDecimal(temp), null),
                        new CcpMonitoringService.Reading(cpTimeId, new BigDecimal(hold), "计时器"))),
                operatorId, "操作员甲");
    }

    private MaterialBatch wipOf(ProcessSheetRowResult row) {
        return materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY_ID, "PRODUCTION_BATCH", String.valueOf(row.getBatchId())).orElseThrow();
    }

    @Test
    @DisplayName("审查中 7 接线: 两个报工控制器在真实上下文里拿到的是同一个带事务代理的 CCP 报工门面")
    void reportControllersAreWiredToTheTransactionalFacade() {
        Object app = org.springframework.test.util.ReflectionTestUtils.getField(
                org.springframework.aop.support.AopUtils.isAopProxy(yieldReportController)
                        ? org.springframework.test.util.AopTestUtils.getUltimateTargetObject(yieldReportController)
                        : yieldReportController, "ccpReportFacade");
        Object web = org.springframework.test.util.ReflectionTestUtils.getField(
                org.springframework.aop.support.AopUtils.isAopProxy(processSheetController)
                        ? org.springframework.test.util.AopTestUtils.getUltimateTargetObject(processSheetController)
                        : processSheetController, "ccpReportFacade");
        System.out.println("FACADE_WIRED app=" + (app != null) + " web=" + (web != null)
                + " proxy=" + org.springframework.aop.support.AopUtils.isAopProxy(ccpReportFacade));
        assertThat(app).isSameAs(ccpReportFacade);
        assertThat(web).isSameAs(ccpReportFacade);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(ccpReportFacade))
                .as("@Transactional 生效 = 报工与 CCP 同一事务").isTrue();
    }

    @Test
    @DisplayName("本工序要录的 CCP: 只返回挂在本工序的启用项, 带限值")
    void stepCheckpointsAreScopedToTheStep() {
        List<CcpMonitoringService.StepCheckpoint> cps = ccpService.checkpointsForStep(FACTORY_ID, WP_COOK);
        assertThat(cps).extracting(CcpMonitoringService.StepCheckpoint::checkpointCode)
                .containsExactly("CCP-H", "CCP-T");
        assertThat(cps.get(1).criticalLimitMin()).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("限值内 → 无偏离, 在制批次照旧可用 (阴性对照)")
    void inLimitCreatesNoDeviation() {
        ProcessSheetRowResult row = saveCookRow("ccpt-ok");
        CcpMonitoringService.RecordResult res = recordCcp(row, "78", "30");
        assertThat(res.deviationCount()).isZero();
        assertThat(res.records()).hasSize(2).allSatisfy(v -> assertThat(v.result()).isEqualTo("OK"));
        assertThat(wipOf(row).getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        assertThat(recordRepo.countOpenDeviationsByPlanIds(FACTORY_ID, List.of(planId))).isZero();
    }

    @Test
    @DisplayName("超限 → 偏离 + 已有产出待检 + 放行被拒; 之后入账的产出 (在制 / 成品) 也待检; 处理后放行成功")
    void outOfLimitHoldsAndBlocksReleaseUntilHandled() {
        ProcessSheetRowResult row = saveCookRow("ccpt-dev");
        assertThat(wipOf(row).getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);

        CcpMonitoringService.RecordResult res = recordCcp(row, "70", "30");   // 70℃ < 75℃
        System.out.println("CCP_DEVIATION_COUNT=" + res.deviationCount() + " HELD=" + res.heldOutputs());
        assertThat(res.deviationCount()).isEqualTo(1);
        assertThat(res.records()).filteredOn(v -> "DEVIATION_OPEN".equals(v.result()))
                .singleElement().satisfies(v -> {
                    assertThat(v.checkpointName()).isEqualTo("中心温度");
                    assertThat(v.criticalLimitMin()).isEqualByComparingTo("75");
                });
        MaterialBatch wip = wipOf(row);
        assertThat(wip.getStatus()).as("偏离把已有的在制批次扣成待检").isEqualTo(MaterialBatchStatus.INSPECTING);

        // 放行被拒, 错误码是本功能的
        assertThatThrownBy(() -> qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, wip.getId(), "RELEASED", "复测合格"), operatorId, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_BLOCKED_BY_CCP));
        assertThat(qcReleaseService.listPending(FACTORY_ID))
                .anySatisfy(p -> {
                    assertThat(p.targetId()).isEqualTo(wip.getId());
                    assertThat(p.openDeviationCount()).isPositive();
                });

        // 偏离之后才入账的产出: 同计划再报一行 → 新在制批次直接待检 (产品并没开需检)
        ProcessSheetRowResult later = saveCookRow("ccpt-later");
        assertThat(wipOf(later).getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);

        // 同计划的成品入库 (小结) → 待检
        sfiRepo.saveAndFlush(SemiFinishedInventory.builder().factoryId(FACTORY_ID).intermediateBatchNo("CCPT-SFI-1")
                .productTypeId(PT_SEMI).producedQuantity(new BigDecimal("50")).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("50")).unit("kg").processOrder(1).build());
        ProcessSheetRowRequest fin = new ProcessSheetRowRequest();
        fin.setClientRowId("ccpt-fin");
        fin.setProcessCode("qidiao");
        fin.setProcessOrder(5);
        fin.setProcessName("包装");
        fin.setProductTypeId(PT_FG);
        fin.setFinished(true);
        fin.setInputQuantity(new BigDecimal("45"));
        fin.setOutputQuantity(new BigDecimal("40"));
        fin.setUnit("kg");
        ProcessSheetRowRequest.UpstreamRef u = new ProcessSheetRowRequest.UpstreamRef();
        u.setSourceBatchNumber("CCPT-SFI-1");
        u.setFeedQuantityKg(new BigDecimal("45"));
        u.setSemiFinished(true);
        fin.setUpstreamSources(List.of(u));
        processSheetService.saveRow(FACTORY_ID, planId, fin, operatorId);
        interimSettleService.interimSettle(FACTORY_ID, planId, operatorId);
        List<FinishedGoodsBatch> fgs = finishedGoodsRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                FACTORY_ID, planId);
        assertThat(fgs).singleElement().satisfies(f ->
                assertThat(f.getStatus()).isEqualTo(FinishedGoodsBatch.Status.PENDING_INSPECTION));

        // 处理偏离 (纠偏措施必填) → 放行成功
        Long devId = res.records().stream().filter(v -> "DEVIATION_OPEN".equals(v.result()))
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> ccpService.handleDeviation(FACTORY_ID, devId, " ", operatorId, "质检员"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        HaccpMonitoringRecord handled = ccpService.handleDeviation(FACTORY_ID, devId,
                "继续加热 5 分钟, 复测 79℃ 合格", operatorId, "质检员");
        assertThat(handled.getDeviationHandledAt()).isNotNull();
        assertThat(CcpOpenDeviationLookup.isOpenDeviation(handled)).isFalse();

        qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, wip.getId(), "RELEASED", "纠偏后复测合格"),
                operatorId, null);
        assertThat(materialBatchRepo.findById(wip.getId()).orElseThrow().getStatus())
                .isEqualTo(MaterialBatchStatus.AVAILABLE);
        qcReleaseService.decide(FACTORY_ID, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.FINISHED_GOODS, fgs.get(0).getId(), "RELEASED", "纠偏后放行"),
                operatorId, null);
        assertThat(finishedGoodsRepo.findById(fgs.get(0).getId()).orElseThrow().getStatus())
                .isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);

        // 处理过的偏离不能再处理一次
        assertThatThrownBy(() -> ccpService.handleDeviation(FACTORY_ID, devId, "再处理", operatorId, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CcpMonitoringService.CODE_NOT_DEVIATION));
    }

    @Test
    @DisplayName("少录一项 → 400 CCP_READING_MISSING, 一条都不写")
    void missingReadingIsRejected() {
        ProcessSheetRowResult row = saveCookRow("ccpt-miss");
        long before = recordRepo.count();
        assertThatThrownBy(() -> ccpService.record(FACTORY_ID, new CcpMonitoringService.RecordCommand(
                row.getBatchNumber(), row.getBatchId(), null, WP_COOK, 1, CcpMonitoringService.SOURCE_WEB,
                List.of(new CcpMonitoringService.Reading(cpTempId, new BigDecimal("80"), null))), operatorId, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CcpMonitoringService.CODE_MISSING);
                    assertThat(e.getMessage()).contains("保温时间");
                });
        assertThat(recordRepo.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("按批次导出: 成品批号 → 同计划整条链的 CCP 记录; CSV 带表头与偏离状态")
    void exportCoversThePlanChain() {
        ProcessSheetRowResult row = saveCookRow("ccpt-exp");
        recordCcp(row, "70", "30");
        String csv = new String(ccpService.exportBatchCsv(FACTORY_ID, row.getBatchNumber()), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿监控时间,批次号");
        assertThat(csv).contains("中心温度").contains("偏离-未处理").contains("保温时间").contains("合格");
        assertThat(csv.lines().count()).isEqualTo(3);   // 表头 + 2 条
    }
}
