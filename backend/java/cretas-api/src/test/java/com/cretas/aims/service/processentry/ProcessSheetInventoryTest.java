package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * SP-F Task 2.1 — ProcessSheetService.getInventory 集成测试 (真 H2 写库)。
 *
 * <p>覆盖:
 * <ol>
 *   <li>produced / used / remaining 正确派生</li>
 *   <li>remaining = 0 → status DEPLETED</li>
 *   <li>DRAFT 行 (batchId null) 不出现在库存列表</li>
 *   <li>plan 范围隔离: 其他 planId 的行不进入结果</li>
 * </ol>
 *
 * <p>cross-tenant 🔒 注: findByFactoryIdAndBatchId 含 factory 过滤, @Where deleted_at IS NULL
 * 排除软删边; 这两条合起来保证: (a) 其他工厂的消耗边不混入 used; (b) re-save/delete 软删的
 * 旧边不被 double-count。完整跨租户测在 ProcessSheetServiceImplTest#saveRow_crossTenantPlan_throws403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("ProcessSheetInventoryTest - SP-F Task 2.1 getInventory")
class ProcessSheetInventoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProcessSheetService processSheetService;

    @Autowired
    private MaterialBatchRepository materialBatchRepo;

    @Autowired
    private MaterialConsumptionRepository consumptionRepo;

    @Autowired
    private ProductionPlanRepository planRepo;

    @Autowired
    private ProcessSheetRowRepository rowRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private FactoryWarehouseRepository warehouseRepo;

    @Autowired
    private ProductTypeRepository productTypeRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String FACTORY_ID   = "INV-FACTORY";
    private static final String PRODUCT_TYPE = "INV-PTYPE-001";
    private static final String RAW_MAT_TYPE = "INV-MATTYPE-PORK";
    private static final BigDecimal RAW_PRICE = new BigDecimal("10.00"); // ¥10/kg

    private Long   operatorId;
    private String planId;
    private String rawBatchId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");

        // User
        User user = new User();
        user.setFactoryId(FACTORY_ID);
        user.setUsername("inv_clerk_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("$2a$10$DUMMYHASHFORTESTPLACEHOLDERONLY1");
        user.setIsActive(true);
        user = userRepo.saveAndFlush(user);
        operatorId = user.getId();

        ProductType product = new ProductType();
        product.setId(PRODUCT_TYPE);
        product.setFactoryId(FACTORY_ID);
        product.setCode(PRODUCT_TYPE);
        product.setName(PRODUCT_TYPE);
        product.setUnit("kg");
        product.setCategory("SEMI_FINISHED");
        product.setProductCategory("SEMI_FINISHED");
        product.setIsActive(true);
        product.setCreatedBy(operatorId);
        productTypeRepo.saveAndFlush(product);

        FactoryWarehouse rawWarehouse = new FactoryWarehouse();
        rawWarehouse.setId("WH-INV-001");
        rawWarehouse.setFactoryId(FACTORY_ID);
        rawWarehouse.setCode("WH-LOG");
        rawWarehouse.setName("测试原料仓");
        rawWarehouse.setType(WarehouseType.RAW);
        rawWarehouse.setIsActive(true);
        warehouseRepo.saveAndFlush(rawWarehouse);

        // Plan
        planId = "INV-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("INV-PN-" + System.currentTimeMillis() % 100000);
        plan.setProductTypeId(PRODUCT_TYPE);
        plan.setPlannedQuantity(new BigDecimal("200"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(operatorId);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.save(plan);

        // Raw material batch
        rawBatchId = "INV-MB-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("INV-RAW-" + System.currentTimeMillis() % 100000);
        raw.setMaterialTypeId(RAW_MAT_TYPE);
        raw.setWarehouseId("WH-INV-001");
        raw.setReceiptQuantity(new BigDecimal("200.00"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(RAW_PRICE);
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw);
    }

    // ─────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────

    private RawInput rawInput(String mbId, String qty) {
        RawInput r = new RawInput();
        r.setMaterialBatchId(mbId);
        r.setQuantity(new BigDecimal(qty));
        return r;
    }

    private UpstreamRef upstreamRef(String batchNumber, String feedKg) {
        UpstreamRef u = new UpstreamRef();
        u.setSourceBatchNumber(batchNumber);
        u.setFeedQuantityKg(new BigDecimal(feedKg));
        return u;
    }

    private ProcessSheetRowResult saveXiuyou(String clientRowId, String rawQty, String output,
                                             String targetPlanId) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("xiuyou");
        req.setProcessOrder(1);
        req.setProcessName("修油");
        req.setProductTypeId(PRODUCT_TYPE);
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal(output));
        req.setInputQuantity(new BigDecimal(rawQty));
        req.setUnit("kg");
        req.setRawMaterialInputs(List.of(rawInput(rawBatchId, rawQty)));
        return processSheetService.saveRow(FACTORY_ID, targetPlanId, req, operatorId);
    }

    private ProcessSheetRowResult saveXiuyou(String clientRowId, String rawQty, String output) {
        return saveXiuyou(clientRowId, rawQty, output, planId);
    }

    /** Saves a downstream 焯水 row consuming the given upstream WIP batchNumber. */
    private ProcessSheetRowResult saveZhaoshui(String clientRowId, String upstreamBatchNumber,
                                               String feedKg, String output) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("zhaoshui");
        req.setProcessOrder(2);
        req.setProcessName("焯水");
        req.setProductTypeId(PRODUCT_TYPE);
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal(output));
        req.setInputQuantity(new BigDecimal(feedKg));
        req.setUnit("kg");
        req.setUpstreamSources(List.of(upstreamRef(upstreamBatchNumber, feedKg)));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    // ─────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INV-1: 修油 produced=100, 焯水消耗 30 → used=30, remaining=70, status=ACTIVE, unitPrice 正确")
    void getInventory_derivesProducedUsedRemaining() {
        // 修油: 输入 100kg → 产出 80kg WIP (unitPrice = 100×¥10/80 = ¥12.5)
        ProcessSheetRowResult xiuyou = saveXiuyou("inv1-xiuyou", "100", "80");
        assertThat(xiuyou.isMaterialized()).isTrue();

        // 焯水: 消耗 修油 WIP 30kg → 产出 28kg
        saveZhaoshui("inv1-zhaoshui", xiuyou.getBatchNumber(), "30", "28");

        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "xiuyou", null);

        assertThat(inventory).hasSize(1);
        ProcessSheetInventoryItem item = inventory.get(0);
        assertThat(item.getBatchNumber()).isEqualTo(xiuyou.getBatchNumber());
        assertThat(item.getProduced()).isEqualByComparingTo("80");
        assertThat(item.getUsed()).isEqualByComparingTo("30");
        assertThat(item.getRemaining()).isEqualByComparingTo("50");
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
        assertThat(item.getUnit()).isEqualTo("kg");
        // unitPrice = rowTotalCost/output = (100×10)/80 = 12.5
        assertThat(item.getUnitPrice()).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("INV-2: 焯水消耗全部 produced=80 → remaining=0, status=DEPLETED")
    void getInventory_depletedWhenFullyConsumed() {
        ProcessSheetRowResult xiuyou = saveXiuyou("inv2-xiuyou", "100", "80");
        // consume all 80 kg
        saveZhaoshui("inv2-zhaoshui", xiuyou.getBatchNumber(), "80", "70");

        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "xiuyou", null);

        assertThat(inventory).hasSize(1);
        ProcessSheetInventoryItem item = inventory.get(0);
        assertThat(item.getProduced()).isEqualByComparingTo("80");
        assertThat(item.getUsed()).isEqualByComparingTo("80");
        assertThat(item.getRemaining()).isEqualByComparingTo("0");
        assertThat(item.getStatus()).isEqualTo("DEPLETED");
    }

    @Test
    @DisplayName("INV-3: DRAFT 行 (outputQty=0, batchId=null) 不出现在库存列表")
    void getInventory_skipsDraftRows() {
        // DRAFT row: output=0
        ProcessSheetRowResult draft = saveXiuyou("inv3-draft", "100", "0");
        assertThat(draft.getBatchId()).as("output=0 → DRAFT, no batchId").isNull();

        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "xiuyou", null);

        assertThat(inventory).as("DRAFT 行不出现在库存列表").isEmpty();
    }

    @Test
    @DisplayName("INV-4: 其他 planId 的行不进入本 plan 的库存结果")
    void getInventory_scopedToPlan_excludesOtherPlans() {
        // 本 plan 的修油行
        ProcessSheetRowResult thisPlan = saveXiuyou("inv4-this", "80", "60", planId);
        assertThat(thisPlan.isMaterialized()).isTrue();

        // 另一个 plan (同 factory)
        String otherPlanId = "INV-PLAN-OTHER-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan other = new ProductionPlan();
        other.setId(otherPlanId);
        other.setFactoryId(FACTORY_ID);
        other.setPlanNumber("INV-OPN-" + System.currentTimeMillis() % 100000);
        other.setProductTypeId(PRODUCT_TYPE);
        other.setPlannedQuantity(new BigDecimal("100"));
        other.setPlannedUnit("kg");
        other.setStatus(ProductionPlanStatus.PENDING);
        other.setCreatedBy(operatorId);
        other.setIsLocked(false);
        other.setSkipProcessReporting(false);
        other.setCreatedAt(LocalDateTime.now());
        other.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(other);
        // Save a separate raw batch to avoid UK conflict on rawBatchId re-use
        String rawBatch2 = "INV-MB-RAW2-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw2 = new MaterialBatch();
        raw2.setId(rawBatch2);
        raw2.setFactoryId(FACTORY_ID);
        raw2.setBatchNumber("INV-RAW2-" + System.currentTimeMillis() % 100000);
        raw2.setMaterialTypeId(RAW_MAT_TYPE);
        raw2.setWarehouseId("WH-INV-001");
        raw2.setReceiptQuantity(new BigDecimal("100.00"));
        raw2.setQuantityUnit("kg");
        raw2.setUsedQuantity(BigDecimal.ZERO);
        raw2.setReservedQuantity(BigDecimal.ZERO);
        raw2.setUnitPrice(RAW_PRICE);
        raw2.setStatus(MaterialBatchStatus.AVAILABLE);
        raw2.setReceiptDate(LocalDate.now());
        raw2.setCreatedBy(operatorId);
        materialBatchRepo.saveAndFlush(raw2);

        // row in other plan using raw2
        ProcessSheetRowRequest otherReq = new ProcessSheetRowRequest();
        otherReq.setClientRowId("inv4-other");
        otherReq.setProcessCode("xiuyou");
        otherReq.setProcessOrder(1);
        otherReq.setProcessName("修油");
        otherReq.setProductTypeId(PRODUCT_TYPE);
        otherReq.setFinished(false);
        otherReq.setOutputQuantity(new BigDecimal("55"));
        otherReq.setInputQuantity(new BigDecimal("70"));
        otherReq.setUnit("kg");
        otherReq.setRawMaterialInputs(List.of(
                new ProcessSheetRowRequest.RawInput() {{
                    setMaterialBatchId(rawBatch2);
                    setQuantity(new BigDecimal("70"));
                }}
        ));
        ProcessSheetRowResult otherPlanRow = processSheetService.saveRow(
                FACTORY_ID, otherPlanId, otherReq, operatorId);
        assertThat(otherPlanRow.isMaterialized()).isTrue();

        // Query planId — should only see this plan's row
        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "xiuyou", null);

        assertThat(inventory).hasSize(1);
        assertThat(inventory.get(0).getBatchNumber())
                .as("只返回本 plan 的批次")
                .isEqualTo(thisPlan.getBatchNumber());
    }

    // ─────────────────────────────────────────────────────────────
    // 缺陷 E (2026-09-02): 「本工序投料来源」下拉 (getInventory) 与「双出成率总览」
    // (getInventoryYieldCard) 对同一批次的生产日期/成本必须读到同一个答案。
    //
    // 直接构造 ProcessSheetRow + WIP MaterialBatch (绕开 saveRow, 因为正常报工链路
    // 总能把 unitPrice/productionDate 算出来 —— 这里要复现的正是「算不出来」那个态,
    // 即 costKnown=false / WIP 从未 setProductionDate 的真实生产形状)。
    // ─────────────────────────────────────────────────────────────

    private ProcessSheetRow buildWipRow(String clientRowId, String batchNumber, Long batchId,
                                         LocalDate payloadProcessDate) throws Exception {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("pocui");
        req.setProcessOrder(1);
        req.setProcessName("破碎");
        req.setProductTypeId(PRODUCT_TYPE);
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal("40"));
        req.setUnit("kg");
        req.setProcessDate(payloadProcessDate);

        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(planId);
        row.setProcessCode("pocui");
        row.setProcessOrder(1);
        row.setClientRowId(clientRowId);
        row.setBatchId(batchId);
        row.setBatchNumber(batchNumber);
        row.setRowPayload(objectMapper.writeValueAsString(req));
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        return row;
    }

    private MaterialBatch buildWipBatch(String id, String batchNumber, Long sourceDocId,
                                        BigDecimal unitPrice, LocalDate productionDate) {
        MaterialBatch wip = new MaterialBatch();
        wip.setId(id);
        wip.setFactoryId(FACTORY_ID);
        wip.setBatchNumber(batchNumber);
        wip.setWarehouseId("WH-INV-001");
        wip.setReceiptDate(LocalDate.now());
        wip.setReceiptQuantity(new BigDecimal("40.00"));
        wip.setQuantityUnit("kg");
        wip.setStatus(MaterialBatchStatus.AVAILABLE);
        wip.setSourceDocType("PRODUCTION_BATCH");
        wip.setSourceDocId(sourceDocId.toString());
        wip.setUnitPrice(unitPrice);
        wip.setProductionDate(productionDate);
        wip.setCreatedBy(operatorId);
        return wip;
    }

    @Test
    @DisplayName("E-1: WIP 自身没有 productionDate/unitPrice → 回退到行 payload 的 processDate; "
            + "成本诚实传 null (不伪造成 0)，与总览 (getInventoryYieldCard) 同源")
    void getInventory_fallsBackToPayloadProcessDate_andKeepsUnknownCostNull() throws Exception {
        Long batchId = 900001L;
        String batchNumber = "CLK-W-20260902-E1";
        ProcessSheetRow row = buildWipRow("inv-e1", batchNumber, batchId, LocalDate.of(2026, 9, 2));
        rowRepo.saveAndFlush(row);

        // WIP 自身 productionDate=null, unitPrice=null —— 正是 costKnown=false /
        // 从未 setProductionDate 的真实物化路径 (本文件里 wip.setProductionDate 从未被调用过)。
        MaterialBatch wip = buildWipBatch("INV-E1-WIP-" + UUID.randomUUID(), batchNumber, batchId, null, null);
        materialBatchRepo.saveAndFlush(wip);

        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "pocui", 1);

        assertThat(inventory).hasSize(1);
        ProcessSheetInventoryItem item = inventory.get(0);
        assertThat(item.getBatchNumber()).isEqualTo(batchNumber);
        // 缺陷 E 断言 1: 生产日期回退到 payload 的 processDate, 不再显示"无生产日期"。
        assertThat(item.getProductionDate())
                .as("wip 自身没有生产日期时应回退到该行提交时的 processDate")
                .isEqualTo(LocalDate.of(2026, 9, 2));
        // 缺陷 E 断言 2: 未知成本必须是 null (前端 costText() 渲染"成本未知"), 不是伪造的 0。
        assertThat(item.getUnitPrice())
                .as("未知成本不许被 nz() 静默改写成 0")
                .isNull();
    }

    @Test
    @DisplayName("E-2: WIP 自身已有 productionDate/unitPrice → 直接用它, 不被 payload 覆盖")
    void getInventory_prefersWipsOwnDateAndPrice_whenPresent() throws Exception {
        Long batchId = 900002L;
        String batchNumber = "CLK-W-20260902-E2";
        // payload 里故意塞一个不同的日期, 断言它不会覆盖 wip 自己的权威值。
        ProcessSheetRow row = buildWipRow("inv-e2", batchNumber, batchId, LocalDate.of(2020, 1, 1));
        rowRepo.saveAndFlush(row);

        MaterialBatch wip = buildWipBatch("INV-E2-WIP-" + UUID.randomUUID(), batchNumber, batchId,
                new BigDecimal("3.50"), LocalDate.of(2026, 9, 2));
        materialBatchRepo.saveAndFlush(wip);

        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "pocui", 1);

        assertThat(inventory).hasSize(1);
        ProcessSheetInventoryItem item = inventory.get(0);
        assertThat(item.getProductionDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(item.getUnitPrice()).isEqualByComparingTo("3.50");
    }

    /**
     * E-3 (阳性对照): 真实的 0 成本 (例如客供料, 工厂不承担原料成本) 必须仍然显示成 0,
     * 不能被这次「未知成本传 null」的修复连坐误伤成 null。
     *
     * <p>「未知」和「确实是 0」是两个不同的态 —— E-1 断言前者传 null, 这条断言后者原样传 0。
     * 缺了这一条, E-1 那条断言可能是在偷懒地把「传 null」写成了「什么都不传/永远传 null」这种
     * 过度修正, 而不是真的只处理了 wip.getUnitPrice() 恰好是 null 的那一种情况。
     */
    @Test
    @DisplayName("E-3 (阳性对照): 真实的 0 成本 (如客供料) 仍然显示 0, 不会被误伤成 null")
    void getInventory_genuineZeroCost_staysZero_notCoercedToNull() throws Exception {
        Long batchId = 900003L;
        String batchNumber = "CLK-W-20260902-E3";
        ProcessSheetRow row = buildWipRow("inv-e3", batchNumber, batchId, LocalDate.of(2026, 9, 2));
        rowRepo.saveAndFlush(row);

        // 客供料: 工厂不承担原料成本, wip.unitPrice 按口径就是 BigDecimal.ZERO —— 这是真值, 不是缺失。
        MaterialBatch wip = buildWipBatch("INV-E3-WIP-" + UUID.randomUUID(), batchNumber, batchId,
                BigDecimal.ZERO, LocalDate.of(2026, 9, 2));
        materialBatchRepo.saveAndFlush(wip);

        List<ProcessSheetInventoryItem> inventory =
                processSheetService.getInventory(FACTORY_ID, planId, "pocui", 1);

        assertThat(inventory).hasSize(1);
        ProcessSheetInventoryItem item = inventory.get(0);
        assertThat(item.getUnitPrice())
                .as("真实的 0 成本(客供料)不该被「未知成本传 null」的修复误伤")
                .isNotNull()
                .isEqualByComparingTo("0");
    }
}
