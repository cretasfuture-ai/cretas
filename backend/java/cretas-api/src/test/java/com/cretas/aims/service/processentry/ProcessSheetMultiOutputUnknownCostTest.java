package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3-13 (2026-10-27 F006 PLAN-1793087672410-1DDCA467): 联产共同投入里有没有单价的原料时,
 * 报工分摊因成本未知而跳过, 但两个产出批次各自留着「已知」汇总 —— 承载投入的产出带着
 * 「原料按 0」的分项, 另一个只带自己的人工 (114.80 + 26.00, 合计与真实联产成本无关)。
 * 生产小结再按这两个数各自入成品库, 成本不守恒。
 *
 * <p>成本未知时每个产出都必须是未知, 并且扛过实体 @PreUpdate 的分项重算。</p>
 */
@DisplayName("ProcessSheet 多产出: 共同投入成本未知 ⇒ 每个产出成本都未知")
class ProcessSheetMultiOutputUnknownCostTest {

    private static final String F = "F-S313";
    private static final String PLAN = "PLAN-S313";

    private ProcessSheetServiceImpl service;
    private ProductionBatchRepository productionBatchRepo;
    private MaterialBatchRepository materialBatchRepo;
    private ProductionBatch carrier;
    private ProductionBatch sibling;
    private MaterialBatch siblingWip;

    @BeforeEach
    void setUp() {
        service = new ProcessSheetServiceImpl(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        productionBatchRepo = mock(ProductionBatchRepository.class);
        materialBatchRepo = mock(MaterialBatchRepository.class);
        ReflectionTestUtils.setField(service, "productionBatchRepo", productionBatchRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepo", materialBatchRepo);
        ReflectionTestUtils.setField(service, "rowRepo", mock(ProcessSheetRowRepository.class));

        // 首产出: 承载投入 12kg 原料 (无单价) + 包材 10.80 + 人工 104 → 物化时汇总诚实未知
        carrier = batch(10998L, "10.80", "104.00");
        carrier.setCostIncomplete(true);
        carrier.setTotalCost(null);
        // 第二产出: 无投入, 只带自己的人工 26 → 物化时汇总「已知」26.00
        sibling = batch(10999L, "0.00", "26.00");
        sibling.setTotalCost(new BigDecimal("26.00"));
        sibling.setUnitCost(new BigDecimal("1.3000"));
        siblingWip = new MaterialBatch();
        siblingWip.setUnitPrice(new BigDecimal("1.3000"));

        when(productionBatchRepo.findByIdAndFactoryId(eq(10998L), eq(F))).thenReturn(Optional.of(carrier));
        when(productionBatchRepo.findByIdAndFactoryId(eq(10999L), eq(F))).thenReturn(Optional.of(sibling));
        when(productionBatchRepo.save(any(ProductionBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.findByFactoryIdAndSourceDocTypeAndSourceDocId(F, "PRODUCTION_BATCH", "10999"))
                .thenReturn(Optional.of(siblingWip));
    }

    private static ProductionBatch batch(Long id, String material, String labor) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setMaterialCost(new BigDecimal(material));
        b.setLaborCost(new BigDecimal(labor));
        b.setActualQuantity(new BigDecimal("16"));
        return b;
    }

    @Test
    void unknownJointInputLeavesEveryOutputCostUnknownAfterTheEntityHook() throws Throwable {
        List<Object> materializations = List.of(
                materialization("grp#0", "PT-SOUP", "8", outcome(10998L, null)),
                materialization("grp#1", "PT-MARROW", "2", outcome(10999L, new BigDecimal("26.00"))));
        List<String> warnings = new ArrayList<>();

        allocate(materializations, warnings);

        for (ProductionBatch b : List.of(carrier, sibling)) {
            b.calculateMetrics();   // = 每次 save 的 @PreUpdate
            assertThat(b.getCostIncomplete()).as("批次 %s 标为成本不完整", b.getId()).isTrue();
            assertThat(b.getTotalCost()).as("批次 %s 总成本未知", b.getId()).isNull();
            assertThat(b.getUnitCost()).as("批次 %s 单位成本未知", b.getId()).isNull();
        }
        assertThat(siblingWip.getUnitPrice()).as("第二产出的在制单价同样未知").isNull();
        assertThat(warnings).anyMatch(w -> w.contains("成本都记为未知"));
    }

    @Test
    void knownJointCostIsStillSplitAndConserved() throws Throwable {
        carrier.setCostIncomplete(false);
        carrier.setTotalCost(new BigDecimal("114.80"));
        List<Object> materializations = List.of(
                materialization("grp#0", "PT-SOUP", "8", outcome(10998L, new BigDecimal("114.80"))),
                materialization("grp#1", "PT-MARROW", "2", outcome(10999L, new BigDecimal("26.00"))));

        allocate(materializations, new ArrayList<>());

        carrier.calculateMetrics();
        sibling.calculateMetrics();
        assertThat(carrier.getTotalCost().add(sibling.getTotalCost()))
                .as("成本已知时按质量分摊, 合计守恒").isEqualByComparingTo("140.80");
        assertThat(carrier.getTotalCost()).isEqualByComparingTo("112.64");   // 80%
        assertThat(sibling.getTotalCost()).isEqualByComparingTo("28.16");    // 20%
        assertThat(carrier.getCostIncomplete()).isFalse();
        assertThat(sibling.getCostIncomplete()).isFalse();
    }

    // ── reflection helpers (private records / method of ProcessSheetServiceImpl) ──

    private static Class<?> nested(String simpleName) {
        for (Class<?> c : ProcessSheetServiceImpl.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals(simpleName)) {
                return c;
            }
        }
        throw new IllegalStateException("nested type not found: " + simpleName);
    }

    private static Object outcome(Long batchId, BigDecimal rowTotalCost) throws Exception {
        Constructor<?> ctor = nested("OneOutputOutcome").getDeclaredConstructor(
                Long.class, String.class, BigDecimal.class, String.class, BigDecimal.class);
        ctor.setAccessible(true);
        return ctor.newInstance(batchId, "B-" + batchId, rowTotalCost, null, null);
    }

    private static Object materialization(String clientRowId, String productTypeId, String kg, Object outcome)
            throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setClientRowId(clientRowId);
        request.setProcessCode("aozhi");
        request.setUnit("kg");
        ProcessSheetRowRequest.OutputLine output = new ProcessSheetRowRequest.OutputLine();
        output.setProductTypeId(productTypeId);
        output.setQuantity(new BigDecimal(kg));
        output.setUnit("kg");
        Constructor<?> ctor = nested("MultiOutputMaterialization").getDeclaredConstructor(
                ProcessSheetRowRequest.class, ProcessSheetRowRequest.OutputLine.class,
                nested("OneOutputOutcome"), ProcessSheetRowResult.OutputResult.class);
        ctor.setAccessible(true);
        return ctor.newInstance(request, output, outcome, new ProcessSheetRowResult.OutputResult());
    }

    private void allocate(List<Object> materializations, List<String> warnings) throws Throwable {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "applyMultiOutputCostAllocation", String.class, String.class, List.class, Set.class, List.class);
        method.setAccessible(true);
        try {
            method.invoke(service, F, PLAN, materializations, Set.of(), warnings);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
