package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * material_consumptions.batch_id 是 NOT NULL + FK → material_batches(id)
 * (约束 fkpw2b6lau9b8kipm0jrg0mgsvy)。任何写进这一列的值都必须是**真实存在的批次 id**。
 *
 * <p><b>为什么这道闸存在 (prod 实测)</b>: 旧实现在"库存不足"分支写
 * {@code consumption.setBatchId("SHORTFALL")} 当缺料标记。error 流里
 * {@code Key (batch_id)=(SHORTFALL) is not present in table "material_batches"} 出现 <b>412</b> 次
 * (窗口 2026-06-19 ~ 08-19), 而 {@code source_type='AUTO_BOM'} 的行数是 <b>0</b> ——
 * 因为 {@code autoConsumeForBatch} 是 REQUIRES_NEW, 那条必失败的 INSERT 把整段扣料回滚掉了,
 * 调用方再把异常吞掉, 用户侧显示成功。
 *
 * <p><b>这道闸守的是「性质」不是「字面」</b>: 断言每一条落库的 batchId 都来自本次的
 * allocation 集合 —— 换一个新的哨兵串 ("NA" / "PENDING" / ...) 同样会红。
 * 字面量 "SHORTFALL" 只作为一条额外的阴性对照。
 *
 * <p>⚠️ 单测用 mock repository, <b>数据库外键不会在这里触发</b>。
 * 所以判据不能是"抛不抛异常", 只能是"写进那一列的值合不合法"。
 */
class BatchConsumptionShortfallContractTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 999L;
    private static final String REAL_BATCH = "3f6a1c88-0000-4000-8000-000000000001";

    private BomExpansionService bomExpansionService;
    private MaterialBatchService materialBatchService;
    private MaterialBatchRepository materialBatchRepository;
    private MaterialConsumptionRepository consumptionRepository;
    private ProductionBatchRepository productionBatchRepository;
    private RawMaterialTypeRepository rawMaterialTypeRepository;
    private WarehouseResolver warehouseResolver;

    private BatchConsumptionServiceImpl service;
    private ProductionBatch batch;

    @BeforeEach
    void setUp() {
        bomExpansionService = mock(BomExpansionService.class);
        materialBatchService = mock(MaterialBatchService.class);
        materialBatchRepository = mock(MaterialBatchRepository.class);
        consumptionRepository = mock(MaterialConsumptionRepository.class);
        productionBatchRepository = mock(ProductionBatchRepository.class);
        rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
        warehouseResolver = mock(WarehouseResolver.class);

        service = new BatchConsumptionServiceImpl(
                bomExpansionService, materialBatchService, materialBatchRepository,
                consumptionRepository, productionBatchRepository, rawMaterialTypeRepository,
                warehouseResolver);

        batch = new ProductionBatch();
        batch.setId(BATCH_ID);
        batch.setFactoryId(FACTORY);
        batch.setProductTypeId("PT-1");
        batch.setProductionPlanId("PP-1");
        batch.setActualQuantity(new BigDecimal("10"));
        batch.setSupervisorId(7L);

        // 幂等检查: 本批次此前没有 AUTO_BOM 记录
        when(consumptionRepository.findByProductionBatchIdAndSourceType(BATCH_ID, "AUTO_BOM"))
                .thenReturn(List.of());

        // BOM 展开出两种料: 一种够 (MAT-OK), 一种缺 (MAT-SHORT)
        MaterialRequirement reqOk = new MaterialRequirement();
        reqOk.setMaterialTypeId("MAT-OK");
        reqOk.setMaterialTypeName("够的料");
        reqOk.setRequiredQuantity(new BigDecimal("5"));

        MaterialRequirement reqShort = new MaterialRequirement();
        reqShort.setMaterialTypeId("MAT-SHORT");
        reqShort.setMaterialTypeName("缺的料");
        reqShort.setRequiredQuantity(new BigDecimal("8"));

        when(bomExpansionService.expandBOM(eq(FACTORY), eq("PT-1"), any(BigDecimal.class)))
                .thenReturn(List.of(reqOk, reqShort));

        // 够的那种给出真实批次 allocation; 缺的那种只进 shortfalls (镜像 BomExpansionService 的 else-if)
        MaterialAllocation alloc = new MaterialAllocation();
        alloc.setMaterialTypeId("MAT-OK");
        alloc.setMaterialBatchId(REAL_BATCH);
        alloc.setBatchNumber("MT-20260819-0001");
        alloc.setAllocatedQuantity(new BigDecimal("5"));

        MaterialShortfall shortfall = new MaterialShortfall();
        shortfall.setMaterialTypeId("MAT-SHORT");
        shortfall.setMaterialTypeName("缺的料");
        shortfall.setRequiredQuantity(new BigDecimal("8"));
        shortfall.setAvailableQuantity(new BigDecimal("2"));
        shortfall.setShortfallQuantity(new BigDecimal("6"));

        MaterialCheckResult check = new MaterialCheckResult();
        check.setAllSatisfied(false);
        check.setAllocations(List.of(alloc));
        check.setShortfalls(List.of(shortfall));
        when(bomExpansionService.checkMaterialAvailability(eq(FACTORY), any()))
                .thenReturn(check);
        // 只读口径也给同一个结果: 若扣料误调它, 流程照常跑完, 由 verify(never()) 抓出, 而不是 NPE
        org.mockito.Mockito.lenient().when(bomExpansionService.checkPickableAvailability(eq(FACTORY), any()))
                .thenReturn(check);

        MaterialBatch mb = new MaterialBatch();
        mb.setId(REAL_BATCH);
        mb.setUnitPrice(new BigDecimal("3.00"));
        when(materialBatchRepository.findById(REAL_BATCH)).thenReturn(Optional.of(mb));
        when(rawMaterialTypeRepository.findById(anyString())).thenReturn(Optional.empty());
        when(consumptionRepository.save(any(MaterialConsumption.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("缺料不得写进 batch_id —— 每条落库的 batchId 都必须是本次分配到的真实批次 id")
    void shortfallMustNotBePersistedAsAFakeMaterialBatchId() {
        service.autoConsumeForBatch(batch);

        // ⚠️ 用 atLeastOnce() 抓, **不要**用 times(1): times(1) 在变异下会先抛
        //    TooManyActualInvocations 把方法中断, 下面那条"守性质"的断言根本不会被求值 ——
        //    那样红的是计数, 不是这道闸真正要守的东西 (实测踩过)。
        ArgumentCaptor<MaterialConsumption> captor = ArgumentCaptor.forClass(MaterialConsumption.class);
        verify(consumptionRepository, atLeastOnce()).save(captor.capture());
        List<MaterialConsumption> saved = captor.getAllValues();

        // 阳性: 确实写了东西 —— 否则下面两条阴性断言在"一条都没写"时也会成立
        assertFalse(saved.isEmpty(), "一条消耗记录都没落库, 后面的断言没有意义");

        // 守性质: 落库的 batchId 只能来自本次 allocation (换任何新哨兵串同样会红)
        Set<String> legalBatchIds = Set.of(REAL_BATCH);
        Set<String> persisted = saved.stream()
                .map(MaterialConsumption::getBatchId)
                .collect(Collectors.toSet());
        assertTrue(legalBatchIds.containsAll(persisted),
                "有 batchId 不是真实物料批次 id, 会撞 FK fkpw2b6lau9b8kipm0jrg0mgsvy: " + persisted);

        // 计数放在守性质之后, 只作为补充
        assertEquals(1, saved.size(), "只应为够的那种料写 1 条消耗记录, 实得: " + saved.size());

        // 阴性对照: 那个具体的哨兵串不许回来
        assertFalse(persisted.contains("SHORTFALL"),
                "缺料哨兵 \"SHORTFALL\" 又被写进 batch_id 了 (prod 已因此失败 412 次)");

        // 缺料的那种料不产生任何消耗行 (库存一克没动, 不能记一笔没发生过的消耗)
        assertTrue(saved.stream().noneMatch(c -> "MAT-SHORT".equals(c.getMaterialTypeId())),
                "缺料物料不应产生消耗记录 —— 它没有 allocation, 也从未调用 useBatchQuantity");
        verify(materialBatchService, never()).useBatchQuantity(eq(FACTORY), eq("SHORTFALL"), any());
    }

    @Test
    @DisplayName("有缺料时, 够的那部分照常扣减并结出物料成本 (旧实现整段回滚, materialCost 永远是 null)")
    void inStockPortionStillSettlesWhenSomethingIsShort() {
        service.autoConsumeForBatch(batch);

        // 够的那部分真的扣了库存
        verify(materialBatchService, times(1))
                .useBatchQuantity(FACTORY, REAL_BATCH, new BigDecimal("5"));

        // 走到了第 5 步: 批次物料成本 = 5 * 3.00, 缺料不计入
        verify(productionBatchRepository, times(1)).save(batch);
        assertEquals(0, new BigDecimal("15.00").compareTo(batch.getMaterialCost()),
                "materialCost 应只含实际扣减的部分, 实得: " + batch.getMaterialCost());
    }

    @Test
    @DisplayName("完工自动扣料只按扣料口径 (车间仓 + 物流仓) 取批次, 不走只读的可领料口径 —— 否则会从原料仓直接扣, 没有领料 / 调拨记录")
    void autoConsumeUsesTheConsumptionScopeNotThePickableScope() {
        service.autoConsumeForBatch(batch);

        verify(bomExpansionService, atLeastOnce()).checkMaterialAvailability(eq(FACTORY), any());
        verify(bomExpansionService, never()).checkPickableAvailability(any(), any());
    }
}
