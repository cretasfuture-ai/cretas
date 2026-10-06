package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.ai.tool.PreviewToolTestSupport.assertPlainChinese;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.call;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.ctx;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.data;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.injectMapper;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.needMoreInfoQuestions;
import static com.cretas.aims.ai.tool.PreviewToolTestSupport.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 领用 (material_batch_use) 只读预览。
 *
 * <p>两层:
 * <ol>
 *   <li>工具真实入口 + 桩掉的 {@link MaterialBatchService}: 验证确认卡内容 / 每条拒绝 / 缺参追问 /
 *       「除了读批次之外对 service 一次别的调用都没有」(verifyNoMoreInteractions 覆盖 service 上全部写方法);</li>
 *   <li>{@link ParityWithRealService}: 同一份批次数据, 一边走预览、一边走<b>真实的</b>
 *       {@code MaterialBatchServiceImpl#useBatchMaterial}, 逐格比较「预览说能领」是否等于「真实写路径放行」
 *       —— 状态 × 数量两个维度, 防止预览与写路径各说各话。</li>
 * </ol>
 */
class MaterialBatchUsePreviewToolTest {

    private static final String FACTORY = "F006";
    private static final String BATCH_ID = "b-1";

    private MaterialBatchService service;
    private ProductionPlanRepository planRepo;
    private MaterialBatchUseTool tool;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(MaterialBatchService.class);
        planRepo = mock(ProductionPlanRepository.class);
        tool = new MaterialBatchUseTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", service);
        ReflectionTestUtils.setField(tool, "productionPlanRepository", planRepo);
        injectMapper(tool);
        when(service.getMaterialBatchById(FACTORY, BATCH_ID)).thenReturn(dto(MaterialBatchStatus.AVAILABLE, "100"));
    }

    @Test
    @DisplayName("领用: 数量够 → canDo=true, 确认卡写批次号+原料名、剩余前后、本次领用; 只读了批次, service 上没有任何别的调用")
    void allowed() throws Exception {
        Map<String, Object> d = data(tool.preview(call("material_batch_use", args("30")), ctx(FACTORY, 42L)));

        assertThat(tool.supportsPreview()).isTrue();
        assertThat(d.get("canDo")).isEqualTo(true);
        assertThat(d.get("status")).isEqualTo("PREVIEW");
        assertThat(rows(d, "currentValues")).containsEntry("原料批次", "B20261006-01（带鱼）")
                .containsEntry("剩余数量", "100 kg").containsEntry("批次状态", "可用");
        assertThat(rows(d, "newValues")).containsEntry("剩余数量", "70 kg").containsEntry("本次领用", "30 kg")
                .containsEntry("批次状态", "可用");
        assertThat(String.valueOf(d.get("message"))).contains("从 B20261006-01（带鱼） 领用 30 kg").contains("剩余 100 kg → 70 kg")
                .doesNotContain(BATCH_ID);
        assertPlainChinese(String.valueOf(d.get("message")));
        // 写路径不落库的 reason 也不能出现在确认卡里 (确认卡只写真会写进去的东西)
        assertThat(rows(d, "newValues")).doesNotContainKey("原因");
        assertNoWrite();
    }

    @Test
    @DisplayName("领用: 恰好领完 → 确认卡预告批次变「已用完」; 比剩余多一点点 → canDo=false (边界)")
    void edgeOfRemaining() throws Exception {
        Map<String, Object> exact = data(tool.preview(call("material_batch_use", args("100")), ctx(FACTORY, 42L)));
        assertThat(exact.get("canDo")).isEqualTo(true);
        assertThat(rows(exact, "newValues")).containsEntry("批次状态", "已用完").containsEntry("剩余数量", "0 kg");
        assertThat(String.valueOf(exact.get("message"))).contains("已用完");

        Map<String, Object> over = data(tool.preview(call("material_batch_use", args("100.001")), ctx(FACTORY, 42L)));
        assertThat(over.get("canDo")).isEqualTo(false);
        assertThat(over.get("status")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(String.valueOf(over.get("message"))).contains("只剩 100 kg").contains("不够领用 100.001 kg");
        assertThat(over).doesNotContainKey("newValues");
        assertNoWrite();
    }

    @Test
    @DisplayName("领用: 数量 0 / 负数 / 不是数字 → canDo=false; 数量没给 → NEED_MORE_INFO")
    void quantityValidation() throws Exception {
        for (Object bad : new Object[]{0, -5, "abc"}) {
            Map<String, Object> a = args("1");
            a.put("quantity", bad);
            Map<String, Object> d = data(tool.preview(call("material_batch_use", a), ctx(FACTORY, 42L)));
            assertThat(d.get("canDo")).as(String.valueOf(bad)).isEqualTo(false);
            assertThat(String.valueOf(d.get("message"))).contains("领用数量必须大于 0");
        }
        Map<String, Object> noQuantity = args("1");
        noQuantity.remove("quantity");
        assertThat(needMoreInfoQuestions(tool.preview(call("material_batch_use", noQuantity), ctx(FACTORY, 42L))))
                .containsExactly("请问需要消耗多少数量？");
        Map<String, Object> noBatch = args("1");
        noBatch.remove("batchId");
        assertThat(needMoreInfoQuestions(tool.preview(call("material_batch_use", noBatch), ctx(FACTORY, 42L))))
                .containsExactly("请问您要消耗哪个批次的原料？说批次号就行");
        // 数量校验在读批次之前 (与写路径同序), 缺参 / 数量非法时一次读都没有
        verify(service, never()).getMaterialBatchById(anyString(), anyString());
        assertNoWriteAfterReads();
    }

    @Test
    @DisplayName("领用: 批次状态禁止投产 (已过期/已报废/不良品/质检中) → canDo=false, 原因取自枚举 (与写路径同一份); 其余状态放行")
    void statusesThatBlockProduction() throws Exception {
        int blocked = 0;
        int allowed = 0;
        for (MaterialBatchStatus st : MaterialBatchStatus.values()) {
            when(service.getMaterialBatchById(FACTORY, BATCH_ID)).thenReturn(dto(st, "100"));
            Map<String, Object> d = data(tool.preview(call("material_batch_use", args("1")), ctx(FACTORY, 42L)));
            boolean shouldBlock = st.blocksProduction();
            assertThat(d.get("canDo")).as(st.name()).isEqualTo(!shouldBlock);
            if (shouldBlock) {
                blocked++;
                assertThat(String.valueOf(d.get("message"))).as(st.name())
                        .contains(st.getDisplayName()).contains("不可用于生产").contains(st.productionBlockedHint())
                        .doesNotContain(st.name());
            } else {
                allowed++;
            }
        }
        // 两侧都真的出现过 (参数化那一维不是空转)
        assertThat(blocked).isEqualTo(4);
        assertThat(allowed).isEqualTo(MaterialBatchStatus.values().length - 4);
        assertNoWriteAfterReads();
    }

    @Test
    @DisplayName("领用: 批次不存在 / 别厂的批次 (403) → 同一句「找不到」, 不泄露存在性、不回显 id; 对照: 本厂批次可领")
    void batchNotVisible() throws Exception {
        when(service.getMaterialBatchById(FACTORY, "b-missing")).thenThrow(new ResourceNotFoundException("原材料批次", "id", "b-missing"));
        when(service.getMaterialBatchById(FACTORY, "b-other")).thenThrow(new BusinessException(403, "无权查看该批次"));
        for (String id : new String[]{"b-missing", "b-other"}) {
            Map<String, Object> a = args("1");
            a.put("batchId", id);
            Map<String, Object> d = data(tool.preview(call("material_batch_use", a), ctx(FACTORY, 42L)));
            assertThat(d.get("canDo")).as(id).isEqualTo(false);
            assertThat(d.get("status")).isEqualTo("BATCH_NOT_FOUND");
            assertThat(String.valueOf(d.get("message"))).contains("找不到这个原料批次").doesNotContain(id).doesNotContain("无权");
        }
        assertThat(data(tool.preview(call("material_batch_use", args("1")), ctx(FACTORY, 42L))).get("canDo")).isEqualTo(true);
        assertNoWriteAfterReads();
    }

    @Test
    @DisplayName("领用: 指定生产计划 → 写出计划号; 计划不存在 → canDo=false (与写路径 validateProductionPlanRef 同一判断)")
    void productionPlan() throws Exception {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PP-20261006-01");
        when(planRepo.findById("plan-1")).thenReturn(Optional.of(plan));
        when(planRepo.findById("plan-x")).thenReturn(Optional.empty());

        Map<String, Object> withPlan = args("5");
        withPlan.put("productionPlanId", "plan-1");
        Map<String, Object> ok = data(tool.preview(call("material_batch_use", withPlan), ctx(FACTORY, 42L)));
        assertThat(ok.get("canDo")).isEqualTo(true);
        assertThat(rows(ok, "newValues")).containsEntry("用于生产计划", "PP-20261006-01");

        Map<String, Object> badPlan = args("5");
        badPlan.put("productionPlanId", "plan-x");
        Map<String, Object> d = data(tool.preview(call("material_batch_use", badPlan), ctx(FACTORY, 42L)));
        assertThat(d.get("canDo")).isEqualTo(false);
        assertThat(d.get("status")).isEqualTo("PLAN_NOT_FOUND");
        assertThat(String.valueOf(d.get("message"))).contains("找不到你指定的生产计划").doesNotContain("plan-x");

        Map<String, Object> noPlan = data(tool.preview(call("material_batch_use", args("5")), ctx(FACTORY, 42L)));
        assertThat(rows(noPlan, "newValues")).doesNotContainKey("用于生产计划");
        assertNoWriteAfterReads();
    }

    // ==================== 与真实写路径逐格比对 ====================

    @Test
    @DisplayName("与真实 MaterialBatchServiceImpl 逐格比对: 状态 × 数量 (低于 / 等于 / 高于剩余), 预览 canDo == 真实写路径放行")
    void parityWithRealService() throws Exception {
        MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
        MaterialConsumptionRepository consumptionRepo = mock(MaterialConsumptionRepository.class);
        MaterialBatchServiceImpl real = new MaterialBatchServiceImpl(
                batchRepo, mock(MaterialBatchAdjustmentRepository.class), mock(RawMaterialTypeRepository.class),
                mock(MaterialBatchMapper.class), consumptionRepo, mock(ProductionPlanBatchUsageRepository.class),
                mock(ExcelUtil.class), mock(FuturePlanMatchingService.class));
        when(batchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(real, "inventoryLowStockEventPublisher",
                mock(com.cretas.aims.service.alerts.InventoryLowStockEventPublisher.class));

        int agreedAllow = 0;
        int agreedRefuse = 0;
        for (MaterialBatchStatus st : MaterialBatchStatus.values()) {
            for (String qty : new String[]{"99.5", "100", "100.5"}) {
                MaterialBatch entity = entity(st, "100");
                when(batchRepo.findByIdAndFactoryId(BATCH_ID, FACTORY)).thenReturn(Optional.of(entity));
                when(service.getMaterialBatchById(FACTORY, BATCH_ID)).thenReturn(dto(st, "100"));

                boolean previewSaysYes = Boolean.TRUE.equals(
                        data(tool.preview(call("material_batch_use", args(qty)), ctx(FACTORY, 42L))).get("canDo"));
                boolean realAllows;
                try {
                    real.useBatchMaterial(FACTORY, BATCH_ID, new BigDecimal(qty), null, 42L);
                    realAllows = true;
                } catch (BusinessException e) {
                    realAllows = false;
                }
                assertThat(previewSaysYes).as("状态=%s 数量=%s", st, qty).isEqualTo(realAllows);
                if (realAllows) {
                    agreedAllow++;
                } else {
                    agreedRefuse++;
                }
            }
        }
        // 两种判定都真的出现过: 否则「全一致」可能只是两边都恒拒 / 恒放
        assertThat(agreedAllow).isGreaterThan(0);
        assertThat(agreedRefuse).isGreaterThan(0);
        // 真实写路径在放行的格子里确实落库了 (必发生), 而预览那一侧从头到尾没碰 save
        verify(consumptionRepo, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    // ==================== helpers ====================

    private void assertNoWrite() throws Exception {
        verify(service, org.mockito.Mockito.atLeastOnce()).getMaterialBatchById(FACTORY, BATCH_ID);   // 「必发生」: 预览确实读了批次
        assertNoWriteAfterReads();
    }

    private void assertNoWriteAfterReads() {
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any());
        verify(service, never()).useBatchMaterial(anyString(), anyString(), any(), any(), any(), any());
        // 兜底: 预览期间 service 上除了「读批次」没有任何别的调用 (这个接口上的所有写方法一并覆盖);
        // atLeast(0) 只是把「读批次」标成已核对, 好让 verifyNoMoreInteractions 只剩别的调用可抓
        verify(service, org.mockito.Mockito.atLeast(0)).getMaterialBatchById(anyString(), anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(service);
    }

    private static Map<String, Object> args(String quantity) {
        Map<String, Object> a = new HashMap<>();
        a.put("batchId", BATCH_ID);
        a.put("quantity", new BigDecimal(quantity));
        return a;
    }

    private static MaterialBatchDTO dto(MaterialBatchStatus status, String remaining) {
        return MaterialBatchDTO.builder()
                .id(BATCH_ID)
                .factoryId(FACTORY)
                .batchNumber("B20261006-01")
                .materialName("带鱼")
                .quantityUnit("kg")
                .currentQuantity(new BigDecimal(remaining))
                .status(status)
                .build();
    }

    private static MaterialBatch entity(MaterialBatchStatus status, String remaining) {
        MaterialBatch b = new MaterialBatch();
        b.setId(BATCH_ID);
        b.setFactoryId(FACTORY);
        b.setBatchNumber("B20261006-01");
        b.setReceiptQuantity(new BigDecimal(remaining));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setStatus(status);
        return b;
    }
}
