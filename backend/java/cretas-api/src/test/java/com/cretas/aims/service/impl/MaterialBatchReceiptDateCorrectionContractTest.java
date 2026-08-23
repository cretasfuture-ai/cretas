package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.material.MaterialBatchDateCorrection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialBatchDateCorrectionRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「更正入库日期」是入库日期的<b>唯一正路</b>，本闸守它三件事：留痕带新旧值 / 原因必填 / 权限与盘点审批同级。
 *
 * <h2>为什么会有这条路径</h2>
 *
 * <p>2026-08-20 owner 裁定「入库日期也要管」。但把 receiptDate 加进 PUT 的账实红线之后，
 * 全仓<b>没有任何</b>能更正它的接口：{@code /adjust} 的 javadoc、DTO、落库表三处一致地写着
 * 「调整批次数量」；盘点 {@code apply()} 写的也只是数量差异。⇒ 只锁不给路 = 造死路，
 * 所以锁与正路必须同一次落地。
 *
 * <h2>⚠️ 类名后缀不是随便取的</h2>
 *
 * <p>CI 在 push 上只跑 {@code *RepositoryQueryValidationTest,*StartupGuardTest,
 * FlywayVersionUniquenessTest,*ContractTest}（见 ci.yml 的 TARGET_TESTS）。
 * 姊妹类 {@code MaterialBatchServiceImplApiRoleGateTest} 以 {@code GateTest} 结尾，
 * <b>不在这个匹配面里</b> —— 它被编译，但 push 上从来不执行。本类刻意用
 * {@code ContractTest} 后缀，这样它真的会跑。
 */
@DisplayName("入库日期更正: 留痕(带新旧值) + 原因必填 + 与盘点审批同级")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchReceiptDateCorrectionContractTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    @Mock private MaterialBatchDateCorrectionRepository correctionRepository;

    private MaterialBatchServiceImpl service;

    private static final String FACTORY = "F006";
    private static final String BATCH_ID = "MB-DATE-001";
    private static final Long OPERATOR = 42L;
    /** 现存批次登记的入库日期 (登记错了的那个)。 */
    private static final LocalDate STORED_DATE = LocalDate.of(2026, 8, 10);
    /** 实际到货日期 (要更正成的那个)。 */
    private static final LocalDate CORRECTED_DATE = LocalDate.of(2026, 8, 1);

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
        ReflectionTestUtils.setField(service, "materialBatchDateCorrectionRepository", correctionRepository);
        when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MaterialBatch existingBatch() {
        MaterialBatch b = new MaterialBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(FACTORY);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setReceiptQuantity(BigDecimal.valueOf(100));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setReceiptDate(STORED_DATE);
        when(materialBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(b));
        return b;
    }

    // ============================================================
    // 留痕: 这条路径存在的全部理由
    // ============================================================

    @Test
    @DisplayName("更正成功 → 台账里有 old / new / reason / 操作人 / 当时角色, 且批次真的改了")
    void correct_writesTraceWithOldAndNewValue() {
        MaterialBatch batch = existingBatch();

        assertThatCode(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, "收货当天漏登记, 送货单显示 8-01 到仓",
                OPERATOR, "factory_super_admin"))
                .doesNotThrowAnyException();

        ArgumentCaptor<MaterialBatchDateCorrection> captor =
                ArgumentCaptor.forClass(MaterialBatchDateCorrection.class);
        verify(correctionRepository, times(1)).save(captor.capture());
        MaterialBatchDateCorrection trace = captor.getValue();

        // ⚠️ 这几条才是「留痕」的定义。@Loggable(action=UPDATE) 只记「谁更新了哪个批次」,
        // 不记字段级新旧值 —— 那不算留痕, 所以本闸逐个字段钉。
        assertThat(trace.getOldValue()).as("改前的日期").isEqualTo(STORED_DATE);
        assertThat(trace.getNewValue()).as("改后的日期").isEqualTo(CORRECTED_DATE);
        assertThat(trace.getReason()).contains("送货单显示 8-01 到仓");
        assertThat(trace.getCorrectedBy()).isEqualTo(OPERATOR);
        assertThat(trace.getCorrectedByRole()).isEqualTo("factory_super_admin");
        assertThat(trace.getFieldName()).isEqualTo(MaterialBatchDateCorrection.FIELD_RECEIPT_DATE);
        assertThat(trace.getFactoryId()).isEqualTo(FACTORY);
        assertThat(trace.getMaterialBatchId()).isEqualTo(BATCH_ID);

        // 阳性对照: 不是"只写了审计行、批次没动"
        assertThat(batch.getReceiptDate()).isEqualTo(CORRECTED_DATE);
        verify(materialBatchRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("被拒时一行审计都不写、批次也不动 (拒绝路径上零写入)")
    void correct_rejected_writesNothing() {
        MaterialBatch batch = existingBatch();

        assertThatThrownBy(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, "理由", OPERATOR, "warehouse_worker"))
                .isInstanceOf(BusinessException.class);

        verify(correctionRepository, never()).save(any());
        verify(materialBatchRepository, never()).save(any());
        assertThat(batch.getReceiptDate()).isEqualTo(STORED_DATE);
    }

    @Test
    @DisplayName("⛔ 不复用数量台账: 更正日期不写 MaterialBatchAdjustment (那张表三列数量都是 NOT NULL)")
    void correct_doesNotWriteQuantityLedger() {
        existingBatch();

        service.correctReceiptDate(FACTORY, BATCH_ID, CORRECTED_DATE, "补登", OPERATOR, "finance_manager");

        // 写进数量台账就只能是三个 0 —— 数量审计里从此躺着一批「0 → 0」的行。
        verify(materialBatchAdjustmentRepository, never()).save(any());
    }

    // ============================================================
    // 原因必填
    // ============================================================

    @Test
    @DisplayName("原因为空 / 纯空白 → 400, 不写库")
    void correct_blankReason_rejected() {
        existingBatch();

        for (String blank : new String[] {null, "", "   ", "\t\n"}) {
            assertThatThrownBy(() -> service.correctReceiptDate(
                    FACTORY, BATCH_ID, CORRECTED_DATE, blank, OPERATOR, "factory_super_admin"))
                    .as("reason=[%s] 应被拒", String.valueOf(blank))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode())
                    .isEqualTo(400);
        }
        verify(correctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("原因超 255 字 → 400 (⛔ 不截断: 审计原因被砍掉一半比报错更糟)")
    void correct_overlongReason_rejectedNotTruncated() {
        existingBatch();
        String tooLong = "补".repeat(256);

        assertThatThrownBy(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, tooLong, OPERATOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
        verify(correctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("刚好 255 字 → 放行 (阴性对照: 长度闸不是把所有原因都拒了)")
    void correct_exactly255Reason_passes() {
        existingBatch();

        assertThatCode(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, "补".repeat(255), OPERATOR, "factory_super_admin"))
                .doesNotThrowAnyException();
        verify(correctionRepository, times(1)).save(any());
    }

    // ============================================================
    // 权限: 与盘点审批同级
    // ============================================================

    @Test
    @DisplayName("普通仓管 / 仓储主管 / 未知角色 / null → 403 (null 也拦: 本动作没有内部调用方)")
    void correct_nonApprovalRoles_blocked() {
        existingBatch();

        for (String role : new String[] {
                "warehouse_worker", "operator", "warehouse_manager", "viewer", "", null}) {
            assertThatThrownBy(() -> service.correctReceiptDate(
                    FACTORY, BATCH_ID, CORRECTED_DATE, "理由", OPERATOR, role))
                    .as("role=[%s] 不该能改入库日期", String.valueOf(role))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode())
                    .isEqualTo(403);
        }
        verify(correctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("财务经理 / 工厂超管 / 平台管理员 → 放行 (阳性对照: 不是靠拒绝所有人变绿)")
    void correct_approvalRoles_pass() {
        for (String role : new String[] {"finance_manager", "factory_super_admin", "platform_admin"}) {
            existingBatch();
            assertThatCode(() -> service.correctReceiptDate(
                    FACTORY, BATCH_ID, CORRECTED_DATE, "理由", OPERATOR, role))
                    .as("role=[%s] 应放行", role)
                    .doesNotThrowAnyException();
        }
        verify(correctionRepository, times(3)).save(any());
    }

    @Test
    @DisplayName("角色码大小写不敏感 (JWT 里的 role 大小写不由本模块决定)")
    void correct_roleIsCaseInsensitive() {
        existingBatch();

        assertThatCode(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, "理由", OPERATOR, "Factory_Super_Admin"))
                .doesNotThrowAnyException();
    }

    /**
     * 形态 D′：抽不成一份，就用闸钉住两份一致。
     *
     * <p>{@code RECEIPT_DATE_CORRECTION_ROLES} 与
     * {@code FactoryStocktakeServiceImpl.STOCKTAKE_APPROVAL_ROLES} 是同一个判断的两份副本
     * （「谁有资格让一次库存更正真的生效」）。抽成一份要么把两个 service 焊死、要么新造一个
     * 常量类去改另一个模块的文件 —— 而并发 session 正在改那一侧。⇒ 留两份，钉一致。
     *
     * <p>⚠️ 这条断言不是「各自都对」（那是两条独立断言，同时绿也不排除两份内容不同），
     * 而是<b>逐字相等</b>。
     */
    @Test
    @DisplayName("更正日期的角色集合与盘点审批逐字相同 (两份必须一起漂)")
    @SuppressWarnings("unchecked")
    void correct_roleSetMatchesStocktakeApproval() throws Exception {
        Set<String> mine = (Set<String>) readPrivateStatic(
                MaterialBatchServiceImpl.class, "RECEIPT_DATE_CORRECTION_ROLES");
        Set<String> stocktake = (Set<String>) readPrivateStatic(
                FactoryStocktakeServiceImpl.class, "STOCKTAKE_APPROVAL_ROLES");

        // 前提断言: 抓不到就抛。反射拿到空集会让下面那条恒真 (本仓「变异不红的第五种成因」)。
        assertThat(mine).as("反射没拿到 RECEIPT_DATE_CORRECTION_ROLES —— 本闸的解析器坏了").isNotEmpty();
        assertThat(stocktake).as("反射没拿到 STOCKTAKE_APPROVAL_ROLES —— 常量被改名了?").isNotEmpty();

        assertThat(mine)
                .as("「更正入库日期」的门槛与「盘点审批」漂开了。两者都是"
                        + "「让一次库存更正真的生效」, 门槛不该不一样 —— 要改就两边一起改")
                .isEqualTo(stocktake);
    }

    private static Object readPrivateStatic(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // ============================================================
    // 其它输入闸
    // ============================================================

    @Test
    @DisplayName("日期没变 → 400, 不写一条 old = new 的噪声审计行")
    void correct_sameDate_rejected() {
        existingBatch();

        assertThatThrownBy(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, STORED_DATE, "理由", OPERATOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
        verify(correctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("日期为空 → 400")
    void correct_nullDate_rejected() {
        existingBatch();

        assertThatThrownBy(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, null, "理由", OPERATOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("未来日期 → 400 (货不可能在将来收到); 今天 → 放行")
    void correct_futureDate_rejected_todayAllowed() {
        existingBatch();

        assertThatThrownBy(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, LocalDate.now().plusDays(1), "理由", OPERATOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(400);

        // 阴性对照: 边界是「晚于今天」, 不是「早于今天」—— 今天入库是最常见的情形
        existingBatch();
        assertThatCode(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, LocalDate.now(), "今天到的货, 之前填错成上周", OPERATOR, "factory_super_admin"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("跨工厂 → 403")
    void correct_crossFactory_blocked() {
        existingBatch();

        assertThatThrownBy(() -> service.correctReceiptDate(
                "F001", BATCH_ID, CORRECTED_DATE, "理由", OPERATOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(403);
        verify(correctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("已耗尽/已过期的批次也能更正 (⛔ 不限 AVAILABLE —— 那会造出第二条死路)")
    void correct_nonAvailableBatch_passes() {
        MaterialBatch b = existingBatch();
        b.setStatus(MaterialBatchStatus.DEPLETED);

        // 需要更正日期的恰恰常常是已经消耗掉的老批次(账做完了才发现登记错)。
        // PUT 那条路要求 AVAILABLE, 若本路径照抄, 老批次就永远改不了。
        assertThatCode(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, "月结时发现入库日期登记错了", OPERATOR, "finance_manager"))
                .doesNotThrowAnyException();
        verify(correctionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("台账仓库缺失 → 500 且不改批次 (留痕器不在就不许改)")
    void correct_missingLedger_refusesToChange() {
        MaterialBatch batch = existingBatch();
        ReflectionTestUtils.setField(service, "materialBatchDateCorrectionRepository", null);

        assertThatThrownBy(() -> service.correctReceiptDate(
                FACTORY, BATCH_ID, CORRECTED_DATE, "理由", OPERATOR, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(500);

        verify(materialBatchRepository, never()).save(any());
        assertThat(batch.getReceiptDate()).isEqualTo(STORED_DATE);
    }
}
