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
 * 排查 2026-11-10 A4 清点补出的同类入口: 报工 (POST /production-plans/{planId}/process-sheet/row,
 * {@code ProcessSheetServiceImpl.saveRow → resolveEdges}) 的原料来源原来只查是不是原料仓, 不查批次状态。
 * 真 Spring + H2 写库; 夹具照抄 {@code ProcessSheetInventoryTest}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("排查 A4 (清点补出): 报工原料来源不许是待检 / 过期 / 报废 / 不良品")
class ProcessSheetRawBatchStatusGateContractTest {

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

    private static final String FACTORY_ID   = "GATE-FACTORY";
    private static final String PRODUCT_TYPE = "GATE-PTYPE-001";
    private static final String RAW_MAT_TYPE = "GATE-MATTYPE-PORK";
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
        user.setUsername("gate_clerk_" + UUID.randomUUID().toString().substring(0, 8));
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
        rawWarehouse.setId("WH-GATE-001");
        rawWarehouse.setFactoryId(FACTORY_ID);
        rawWarehouse.setCode("WH-LOG");
        rawWarehouse.setName("测试原料仓");
        rawWarehouse.setType(WarehouseType.RAW);
        rawWarehouse.setIsActive(true);
        warehouseRepo.saveAndFlush(rawWarehouse);

        // Plan
        planId = "GATE-PLAN-" + UUID.randomUUID().toString().substring(0, 8);
        ProductionPlan plan = new ProductionPlan();
        // Unused single-product snapshots: H2 JsonBinaryType double-encodes empty JSON.
        // Real PostgreSQL refresh/snapshot coverage stays in SecondaryHoldPostgresContractTest.
        plan.setWorkflowOutputUnitsByProduct(null);
        plan.setSelectedBomRecipeIdsByProduct(null);
        plan.setSelectedBomVersionsByProduct(null);
        plan.setSourceOrderIds(null);
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("GATE-PN-" + System.currentTimeMillis() % 100000);
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
        rawBatchId = "GATE-MB-RAW-" + UUID.randomUUID().toString().substring(0, 8);
        MaterialBatch raw = new MaterialBatch();
        raw.setId(rawBatchId);
        raw.setFactoryId(FACTORY_ID);
        raw.setBatchNumber("GATE-RAW-" + System.currentTimeMillis() % 100000);
        raw.setMaterialTypeId(RAW_MAT_TYPE);
        raw.setWarehouseId("WH-GATE-001");
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

    private ProcessSheetRowResult saveXiuyou(String clientRowId) {
        RawInput r = new RawInput();
        r.setMaterialBatchId(rawBatchId);
        r.setQuantity(new BigDecimal("10"));
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId(clientRowId);
        req.setProcessCode("xiuyou");
        req.setProcessOrder(1);
        req.setProcessName("修油");
        req.setProductTypeId(PRODUCT_TYPE);
        req.setFinished(false);
        req.setOutputQuantity(new BigDecimal("9"));
        req.setInputQuantity(new BigDecimal("10"));
        req.setUnit("kg");
        req.setRawMaterialInputs(List.of(r));
        return processSheetService.saveRow(FACTORY_ID, planId, req, operatorId);
    }

    @Test
    @DisplayName("原料来源是待检 / 报废 / 不良品 → 409 带提示, 不写消耗; 阴性对照: 领出后才过期的照常保存")
    void blockedRawBatchIsRejected() {
        MaterialBatch raw = materialBatchRepo.findById(rawBatchId).orElseThrow();
        long before = consumptionRepo.count();
        for (MaterialBatchStatus st : List.of(MaterialBatchStatus.INSPECTING,
                MaterialBatchStatus.SCRAPPED, MaterialBatchStatus.DEFECTIVE)) {
            raw.setStatus(st);
            materialBatchRepo.saveAndFlush(raw);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> saveXiuyou("gate-" + st.name()))
                    .as("%s", st)
                    .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo("MATERIAL_BATCH_" + st.name());
                        assertThat(e.getActionHint()).isNotBlank();
                    });
        }
        assertThat(consumptionRepo.count()).isEqualTo(before);

        // 审查 M2: 生产仓批次凌晨被翻成已过期, 第二天补录已经发生的消耗不该被拦
        raw.setStatus(MaterialBatchStatus.EXPIRED);
        materialBatchRepo.saveAndFlush(raw);
        ProcessSheetRowResult ok = saveXiuyou("gate-ok");
        assertThat(ok).isNotNull();
        assertThat(consumptionRepo.count()).isGreaterThan(before);
    }
}
