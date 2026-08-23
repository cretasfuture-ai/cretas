package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4 红线 (#686 残留-r2): MaterialBatch PUT/DELETE API 层角色守卫验证。
 *
 * <p>转录铁律 (张权 F006 仓管员): "做仓管的纯操作员无库存自主权, 库存变动必须单据+审批"。
 * Fable 审计抓到: 即便 #686 修了 RN 直改库存 + 接了入库守卫, API 层 PUT /material-batches/{id}
 * (warehouse:read_write 即可) 仍允许仓管经 MaterialBatchMapper.updateEntity 改 receiptQuantity
 * (影响台账/出成率分母/成本); DELETE 允许仓管删未消耗批次 (=无单据移除库存)。
 *
 * <p><b>2026-08-20 内控收窄 (owner 裁定)</b>: PUT 侧的账实守卫<b>不再分角色</b>。
 * owner 原话: "入库与批次, 我点击编辑是可以去修改所有的信息的, 这个是完全不能允许仓储去做的事情
 * …包括所有其他权限、财务、工厂总监, 都应该是到盘点与期初库存去盘点的时候才能改,
 * 不能够直接编辑批次。" prod 实测普通仓储主管正好带 warehouse=rw / inventory=rw,
 * 也就是说原来的"特权角色"分级在真实权限配置下几乎不构成约束。
 *
 * <p>本测试验证 service 守卫:
 * <ul>
 *   <li>账实字段 (receiptQuantity / quantityUnit / weightPerUnit / totalWeight / totalValue /
 *       unitPrice / materialTypeId / supplierId / <b>receiptDate</b>) → <b>任何角色</b> 403,
 *       含 factory_super_admin / warehouse_manager / platform_admin / callerRole=null 内部路径</li>
 * <li><b>2026-08-20 第二轮</b>: receiptDate 纳入账实红线。它不是描述而是 FIFO/FEFO 的排序键
 *     与期间归属。上一轮不锁它的理由是"盘点改不了日期, 锁了是 dead-end" —— 成立, 所以
 *     本轮同时给出正路 {@code correctReceiptDate} (原因必填 + 写带新旧值的审计台账),
 *     403 的 hint 按字段指过去, ⛔ 不再统一指向盘点。</li>
 *   <li>描述性字段 (storageLocation / notes / boxCount / factoryNumber / originPlace 等) → 放行</li>
 *   <li>回传与现值相同的账实字段 (partial body 回显) → 放行 (判据是"值真的变了")</li>
 *   <li>DELETE 侧维持原有角色分级: 仓管 → 403, 管理员/null → 放行 (owner 本轮只裁定了 PUT)</li>
 * </ul>
 */
@DisplayName("W4 红线: MaterialBatch PUT/DELETE API 层角色守卫")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchServiceImplApiRoleGateTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    private MaterialBatchServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String BATCH_ID = "MB-W4-001";

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(
                materialBatchRepository,
                materialBatchAdjustmentRepository,
                materialTypeRepository,
                materialBatchMapper,
                materialConsumptionRepository,
                productionPlanBatchUsageRepository,
                excelUtil,
                futurePlanMatchingService);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
        when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 现存批次: 入库量 100kg, 单价 10, 重量 100, 总价 1000, AVAILABLE, currentQty=receiptQty (可删)。 */
    private MaterialBatch existingBatch() {
        MaterialBatch b = new MaterialBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(FACTORY);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setReceiptQuantity(BigDecimal.valueOf(100));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnitPrice(BigDecimal.valueOf(10));
        b.setWeightPerUnit(BigDecimal.ONE);
        when(materialBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(b));
        return b;
    }

    // ============================================================
    // PUT — 仓管改库存字段 → 403
    // ============================================================

    @Test
    @DisplayName("warehouse_worker 改 receiptQuantity → 403 (拦截), 不落库")
    void put_warehouseWorker_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130)); // 改入库量 (放大出成率分母)

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);

        verify(materialBatchMapper, never()).updateEntity(any(), any());
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("operator 改 unitPrice → 403 (拦截)")
    void put_operator_changeUnitPrice_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setUnitPrice(BigDecimal.valueOf(99));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("warehouse_worker 发 totalValue (间接改成本) → 403")
    void put_warehouseWorker_changeTotalValue_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setTotalValue(BigDecimal.valueOf(5000));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // ============================================================
    // PUT — 仓管改非库存字段 → 放行 (向后兼容合法编辑)
    // ============================================================

    @Test
    @DisplayName("warehouse_worker 改 storageLocation (非库存字段) → 放行")
    void put_warehouseWorker_changeStorageLocation_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setStorageLocation("A区-02货架");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();

        verify(materialBatchMapper, times(1)).updateEntity(any(), any());
        verify(materialBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("warehouse_worker 改 notes (非库存字段) → 放行")
    void put_warehouseWorker_changeNotes_passes() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setNotes("位置调整说明");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("warehouse_worker 发与现值相同的 receiptQuantity (no-op) → 放行 (无实际变更)")
    void put_warehouseWorker_sameReceiptQuantity_passes() {
        existingBatch(); // receiptQuantity = 100
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(100)); // 同值, scale 不同也算相等
        req.setStorageLocation("A区-03货架");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // PUT — 账实字段: 任何角色都拦 (2026-08-20 owner 内控收窄)
    //
    // ⚠️ 下面这 5 条原本断言的是"管理员/内部路径改 receiptQuantity → 放行"。
    // 那些断言没坏、也不是恒真式 —— 它们守的是一个**我们已经不想要**的行为
    // (owner: "包括所有其他权限、财务、工厂总监…不能够直接编辑批次")。
    // 按本仓纪律: 需求变了就改断言, 但**不删断言** —— 全部翻面成阴性对照,
    // 这样"哪天有人把 bypass 加回来"仍然会红。
    // ============================================================

    @Test
    @DisplayName("factory_super_admin (工厂总监) 改 receiptQuantity → 403, 不再有特权")
    void put_factorySuperAdmin_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("warehouse_manager (仓储主管) 改 receiptQuantity → 403, 不再有特权")
    void put_warehouseManager_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("platform_admin 改 receiptQuantity → 403 (平台管理员 bypass 也去掉了)")
    void put_platformAdmin_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "platform_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("callerRole=null (内部/AI-tool 路径) 改 receiptQuantity → 403, 内部路径也没有豁免")
    void put_nullRole_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        // AI 的 MaterialUpdateTool 只暴露 storageLocation / notes, 够不到这条路;
        // 留这个豁免只会让它哪天被别的内部调用方用上。
        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("无角色重载 (旧签名) 改 receiptQuantity → 403 (delegate null 也拦)")
    void put_legacyOverload_changeReceiptQuantity_blocked() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    // ============================================================
    // PUT — 2026-08-20 新纳入账实红线的三个字段: 单位 / 原料类型 / 供应商
    // ============================================================

    @Test
    @DisplayName("改 quantityUnit (箱→kg 同一个数字改写账实) → 403")
    void put_changeQuantityUnit_blocked() {
        MaterialBatch b = existingBatch();
        b.setQuantityUnit("箱");
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setQuantityUnit("kg");

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("改 materialTypeId (把库存从 A 品类搬到 B 品类) → 403")
    void put_changeMaterialTypeId_blocked() {
        MaterialBatch b = existingBatch();
        b.setMaterialTypeId("MT-鲈鱼");
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setMaterialTypeId("MT-带鱼");

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("改 supplierId (应付归属 + 溯源链源头) → 403")
    void put_changeSupplierId_blocked() {
        MaterialBatch b = existingBatch();
        b.setSupplierId("SUP-001");
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setSupplierId("SUP-002");

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("报错文案点名是哪个字段, 并指向盘点与期初库存 (不是 dead-end)")
    void put_blockedError_namesFieldAndPointsToStocktake() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    // ⛔ 不要吐 DTO 属性名给仓管
                    org.assertj.core.api.Assertions.assertThat(be.getMessage())
                            .contains("入库数量")
                            .doesNotContain("receiptQuantity");
                    org.assertj.core.api.Assertions.assertThat(be.getActionHint())
                            .contains("盘点与期初库存");
                    org.assertj.core.api.Assertions.assertThat(be.getHintTarget())
                            .isEqualTo("receiptQuantity");
                });
    }

    // ============================================================
    // PUT — 描述性字段仍然放行 (阳性对照: 不是靠"整个 PUT 拒绝"变绿的)
    // ============================================================

    @Test
    @DisplayName("任何角色改 boxCount / factoryNumber / originPlace (描述性信息) → 放行")
    void put_descriptiveFields_pass() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setBoxCount(12);
        req.setFactoryNumber("3701");
        req.setOriginPlace("山东寿光");
        req.setNotes("补录厂号与产地");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("回传与现值相同的账实字段 (前端 partial body / RN 回显) → 放行, 判据是值真的变了")
    void put_sameInventoryValues_pass() {
        MaterialBatch b = existingBatch(); // receiptQuantity = 100
        b.setQuantityUnit("kg");
        b.setMaterialTypeId("MT-鲈鱼");
        b.setSupplierId("SUP-001");

        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(100.00)); // 同值, scale 不同
        req.setQuantityUnit("kg");
        req.setMaterialTypeId("MT-鲈鱼");
        req.setSupplierId("SUP-001");
        req.setNotes("只改备注");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // PUT — 2026-08-20 第二轮: 入库日期纳入账实红线
    //
    // ⚠️ 上一轮把 receiptDate 归进"描述性字段"并明确不锁, 理由是"盘点改不了日期,
    // 锁了是 dead-end" —— 那条理由成立。所以本轮不是"再加一个字段到锁死清单",
    // 而是**锁 + 修**: 正路是 correctReceiptDate (见
    // MaterialBatchReceiptDateCorrectionContractTest), 403 的 hint 指向它。
    // 下面第一条断言(改日期 → 403)与上一轮"receiptDate 可直接改"的行为相反,
    // 上一轮没有为那个行为写过断言, 所以没有断言被删 —— 只有新增。
    // ============================================================

    @Test
    @DisplayName("任何角色改 receiptDate (FIFO 排序键 + 期间归属) → 403")
    void put_changeReceiptDate_blocked() {
        MaterialBatch b = existingBatch();
        b.setReceiptDate(java.time.LocalDate.of(2026, 8, 1));
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptDate(java.time.LocalDate.of(2026, 7, 1));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("入库日期被拦时, hint 指向「更正入库日期」而不是盘点 —— 盘点改不了日期")
    void put_blockedReceiptDate_pointsToCorrectionNotStocktake() {
        MaterialBatch b = existingBatch();
        b.setReceiptDate(java.time.LocalDate.of(2026, 8, 1));
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptDate(java.time.LocalDate.of(2026, 7, 1));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getMessage())
                            .contains("入库日期")
                            .doesNotContain("receiptDate");
                    org.assertj.core.api.Assertions.assertThat(be.getActionHint())
                            .as("只 disable 不说去哪 = dead-end; 而指向盘点是**错的**去处")
                            .contains("更正入库日期");
                    // 阴性对照: 这一支绝不能出现"盘点"那句 —— 那会让用户走一趟改不了的路
                    org.assertj.core.api.Assertions.assertThat(be.getActionHint())
                            .doesNotContain("盘点与期初库存");
                    org.assertj.core.api.Assertions.assertThat(be.getHintTarget())
                            .isEqualTo("receiptDate");
                });
    }

    @Test
    @DisplayName("数量被拦时 hint 仍指向盘点 (阳性对照: 不是把所有 hint 都改成了日期那句)")
    void put_blockedQuantity_stillPointsToStocktake() {
        existingBatch();
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptQuantity(BigDecimal.valueOf(130));

        assertThatThrownBy(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_manager"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    org.assertj.core.api.Assertions.assertThat(be.getActionHint())
                            .contains("盘点与期初库存")
                            .doesNotContain("更正入库日期");
                });
    }

    @Test
    @DisplayName("回传与现值相同的 receiptDate → 放行 (判据仍是「值真的变了」, 不是「带了这个字段」)")
    void put_sameReceiptDate_passes() {
        MaterialBatch b = existingBatch();
        b.setReceiptDate(java.time.LocalDate.of(2026, 8, 1));

        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setReceiptDate(java.time.LocalDate.of(2026, 8, 1)); // 同值
        req.setNotes("只改备注");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("完全不发 receiptDate 的 partial body (RN 改库位/备注那 4 个调用方) → 放行")
    void put_absentReceiptDate_passes() {
        MaterialBatch b = existingBatch();
        b.setReceiptDate(java.time.LocalDate.of(2026, 8, 1));

        // RN WHPutawayScreen 只发 { storageLocation }; AlertDetail/WHExpireHandle 只发 { notes }。
        // 判据若写成"请求里带了这个字段", 这些照样过 —— 所以这条不是恒真式的替身,
        // 它守的是"不发 = 不动", 与上一条(发原值 = 不动)是两种不同的输入。
        UpdateMaterialBatchRequest req = new UpdateMaterialBatchRequest();
        req.setStorageLocation("A区-05货架");

        assertThatCode(() ->
                service.updateMaterialBatch(FACTORY, BATCH_ID, req, "warehouse_worker"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).save(any());
    }

    // ============================================================
    // DELETE — 仓管删 → 403; 管理员/null 删 → 放行
    // ============================================================

    @Test
    @DisplayName("warehouse_worker 删批次 → 403 (拦截), 不删除")
    void delete_warehouseWorker_blocked() {
        existingBatch();

        assertThatThrownBy(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "warehouse_worker"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);

        verify(materialBatchRepository, never()).delete(any());
    }

    @Test
    @DisplayName("operator 删批次 → 403 (拦截)")
    void delete_operator_blocked() {
        existingBatch();

        assertThatThrownBy(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("factory_super_admin 删未消耗批次 → 放行 (执行删除)")
    void delete_factorySuperAdmin_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "factory_super_admin"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).delete(any());
    }

    @Test
    @DisplayName("warehouse_manager 删未消耗批次 → 放行")
    void delete_warehouseManager_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "warehouse_manager"))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).delete(any());
    }

    @Test
    @DisplayName("callerRole=null (内部路径) 删 → 放行 (向后兼容)")
    void delete_nullRole_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, null))
                .doesNotThrowAnyException();
        verify(materialBatchRepository, times(1)).delete(any());
    }

    @Test
    @DisplayName("无角色重载 (旧签名) 删 → 放行 (delegate null)")
    void delete_legacyOverload_passes() {
        existingBatch();

        assertThatCode(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("管理员删但批次已部分消耗 → 409 (现有业务规则不被角色守卫绕过)")
    void delete_admin_consumedBatch_still409() {
        MaterialBatch b = existingBatch();
        b.setUsedQuantity(BigDecimal.valueOf(30)); // currentQty != receiptQty → 已消耗

        assertThatThrownBy(() ->
                service.deleteMaterialBatch(FACTORY, BATCH_ID, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(409);
    }
}
