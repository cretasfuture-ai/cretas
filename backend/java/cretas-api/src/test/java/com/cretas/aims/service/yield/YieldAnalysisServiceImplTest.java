package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.impl.YieldAnalysisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YieldAnalysisServiceImplTest {

    @Mock
    ProductionReportRepository reportRepo;

    @Mock
    com.cretas.aims.service.processsecret.ProcessSecretAccessService processSecretAccessService;

    @Mock
    com.cretas.aims.repository.ProductionPlanRepository planRepository;

    @Mock
    com.cretas.aims.repository.bom.BomRecipeRepository recipeRepository;

    YieldAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new YieldAnalysisServiceImpl(reportRepo,
                new com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions(
                        processSecretAccessService, planRepository, recipeRepository));
    }

    /**
     * Build a result row matching the native query projection keys —
     * 每侧给「这道工序所有报工里出现过的单位」(SQL 已剔除空串/unitless、已退回工序标准);
     * null 元素 = 那条报工没有单位。
     */
    private static Map<String, Object> rowUnits(String processName, String totalInput, String totalOutput,
                                                String[] inputUnits, String[] outputUnits, Long batchCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("process_name", processName);
        m.put("total_input",  new BigDecimal(totalInput));
        m.put("total_output", new BigDecimal(totalOutput));
        putSide(m, "input", inputUnits);
        putSide(m, "output", outputUnits);
        m.put("report_count", (long) inputUnits.length);
        m.put("batch_count",  batchCount);
        return m;
    }

    private static void putSide(Map<String, Object> m, String prefix, String[] units) {
        java.util.List<String> known = java.util.Arrays.stream(units).filter(java.util.Objects::nonNull).sorted().toList();
        m.put(prefix + "_unit_min", known.isEmpty() ? null : known.get(0));
        m.put(prefix + "_unit_max", known.isEmpty() ? null : known.get(known.size() - 1));
        m.put(prefix + "_unit_kinds", known.stream().distinct().count());
        m.put(prefix + "_unit_known", (long) known.size());
    }

    /** 单条报工, 投入/产出各一个单位 (null = 没有单位)。 */
    private static Map<String, Object> row(String processName,
                                           String totalInput, String totalOutput,
                                           String inputUnit, String outputUnit,
                                           Long batchCount) {
        return rowUnits(processName, totalInput, totalOutput,
                new String[]{inputUnit}, new String[]{outputUnit}, batchCount);
    }

    @Test
    void aggregate_sameUnit_computesConversionAndWastage() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(row("处理", "300", "260", "kg", "kg", 2L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess(
                "F006",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getProcessName()).isEqualTo("处理");
        assertThat(dto.getUnitComparable()).isTrue();
        assertThat(dto.getConversionRate()).isNotNull();
        assertThat(dto.getConversionRate()).isEqualByComparingTo("86.7");
        assertThat(dto.getWastageRate()).isNotNull();
        assertThat(dto.getWastageRate()).isEqualByComparingTo("13.3");
        assertThat(dto.getBatchCount()).isEqualTo(2);
    }

    @Test
    void aggregate_crossUnit_conversionNull() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(row("末道", "998", "3184", "kg", "盒", 1L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess(
                "F006",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getUnitComparable()).isFalse();
        assertThat(dto.getConversionRate()).isNull();
        assertThat(dto.getWastageRate()).isNull();
    }

    @Test
    void aggregate_crossUnit_reasonIsUnitMismatchAndQuantitiesKept() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(row("装盒", "998", "3184", "袋", "盒", 1L)));

        ProcessYieldAggDTO dto = service.aggregateByProcess("F006", null, null, null).get(0);
        assertThat(dto.getIncomparableReason()).isEqualTo(ProcessYieldAggDTO.IncomparableReason.UNIT_MISMATCH);
        assertThat(dto.getConversionRate()).isNull();
        assertThat(dto.getInputQuantity()).isEqualByComparingTo("998");
        assertThat(dto.getInputUnit()).isEqualTo("袋");
        assertThat(dto.getOutputUnit()).isEqualTo("盒");
    }

    @Test
    void aggregate_massUnitsConvertBeforeDividing() {
        // kg 投入 → g 产出: 有可靠的科学换算, 换算后再除 (800 g / 1 kg = 80%)
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(row("切丁", "1", "800", "kg", "g", 1L)));

        ProcessYieldAggDTO dto = service.aggregateByProcess("F006", null, null, null).get(0);
        assertThat(dto.getUnitComparable()).isTrue();
        assertThat(dto.getConversionRate()).isEqualByComparingTo("80.0");
        assertThat(dto.getWastageRate()).isEqualByComparingTo("20.0");
    }

    @Test
    void aggregate_mixedUnitsWithinOneSide_noRateAndNoFakeSum() {
        // 2026-11-12 网页验收原形: 定量包装 投入 kg/袋、产出 袋/盒, 旧口径按工序标准判同单位出 333%~500%
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(rowUnits("定量包装", "12", "50",
                        new String[]{"kg", "袋"}, new String[]{"袋", "盒"}, 2L)));

        ProcessYieldAggDTO dto = service.aggregateByProcess("F006", null, null, null).get(0);
        assertThat(dto.getUnitComparable()).isFalse();
        assertThat(dto.getIncomparableReason()).isEqualTo(ProcessYieldAggDTO.IncomparableReason.MIXED_UNITS);
        assertThat(dto.getConversionRate()).isNull();
        assertThat(dto.getWastageRate()).isNull();
        assertThat(dto.getInputQuantity()).isNull();   // 12 = kg + 袋 混加, 不给
        assertThat(dto.getOutputQuantity()).isNull();
        assertThat(dto.getInputUnit()).isNull();
    }

    @Test
    void aggregate_aliasSpellingsOfOneUnitAreNotMixed() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(rowUnits("清洗", "200", "180",
                        new String[]{"kg", "千克"}, new String[]{"kg", "kg"}, 2L)));

        ProcessYieldAggDTO dto = service.aggregateByProcess("F006", null, null, null).get(0);
        assertThat(dto.getUnitComparable()).isTrue();
        assertThat(dto.getConversionRate()).isEqualByComparingTo("90.0");
    }

    @Test
    void aggregate_someReportsWithoutUnit_isMixed() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(rowUnits("腌制", "200", "180",
                        new String[]{"kg", null}, new String[]{"kg", null}, 2L)));

        ProcessYieldAggDTO dto = service.aggregateByProcess("F006", null, null, null).get(0);
        assertThat(dto.getIncomparableReason()).isEqualTo(ProcessYieldAggDTO.IncomparableReason.MIXED_UNITS);
        assertThat(dto.getInputQuantity()).isNull();
    }

    @Test
    void aggregate_noUnitAtAll_quantitiesShownButNotDivided() {
        // 2026-11-12 网页验收原形: QA-S3-1104-腌制 投入「0.2 unitless」产出「0.1 unitless」
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(List.of(row("QA-S3-1104-腌制", "0.2", "0.1", null, null, 1L)));

        ProcessYieldAggDTO dto = service.aggregateByProcess("F006", null, null, null).get(0);
        assertThat(dto.getIncomparableReason()).isEqualTo(ProcessYieldAggDTO.IncomparableReason.NO_UNIT);
        assertThat(dto.getConversionRate()).isNull();
        assertThat(dto.getInputQuantity()).isEqualByComparingTo("0.2");
        assertThat(dto.getInputUnit()).isNull();
        assertThat(dto.getUnit()).isNull();
        assertThat(dto.getOutputUnit()).isNull();
    }

    @Test
    void aggregate_nullParams_convertedToSentinels() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        List<ProcessYieldAggDTO> result = service.aggregateByProcess("F001", null, null, null);

        assertThat(result).isEmpty();
        verify(reportRepo).aggregateYieldByProcess(
                eq("F001"),
                eq(LocalDate.of(1900, 1, 1)),
                eq(LocalDate.of(2999, 12, 31)),
                eq(""),
                eq(List.of("__none__")),
                eq(List.of("__none__")));
    }

    // ---- 工艺保密 (Steve 第三问 A: 跨批汇总只给负责人和授权人) ----

    @Test
    void aggregate_excludesProtectedProductsTheCallerCannotSee() {
        when(processSecretAccessService.protectedProductTypeIds("F006")).thenReturn(java.util.Set.of("P17", "P18"));
        when(processSecretAccessService.filterPermitted(any(), eq("F006"), anyList(),
                eq(com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL), eq(false), anyString()))
                .thenReturn(List.of("P18"));
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.aggregateByProcess("F006", null, null, null);

        verify(reportRepo).aggregateYieldByProcess(eq("F006"), any(), any(), eq(""), eq(List.of("P17")), any());
    }

    @Test
    void aggregate_namedProductStillExcludesHiddenProducts() {
        // 点名受保护产品由路由闸拦; 点名别的产品 (例如多产出计划的原料锚点) 时, 无权的保密产品照样排除
        when(processSecretAccessService.protectedProductTypeIds("F006")).thenReturn(java.util.Set.of("P17"));
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.aggregateByProcess("F006", null, null, "P1");

        verify(reportRepo).aggregateYieldByProcess(eq("F006"), any(), any(), eq("P1"), eq(List.of("P17")), any());
    }

    @Test
    void aggregate_multiOutputAnchorsOfPlansWithHiddenProductsAreExcludedToo() {
        // 独立审查第三轮 A: 多产出计划的工序任务挂在原料锚点 (id 就是原料 id) 上
        when(processSecretAccessService.protectedProductTypeIds("F006")).thenReturn(java.util.Set.of("P17", "P18"));
        when(processSecretAccessService.filterPermitted(any(), eq("F006"), anyList(),
                eq(com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL), eq(false), anyString()))
                .thenReturn(List.of("P18"));
        com.cretas.aims.entity.ProductionPlan hiddenPlan = new com.cretas.aims.entity.ProductionPlan();
        hiddenPlan.setId("plan-hidden");
        hiddenPlan.setProductTypeId("RM-BEEF");
        hiddenPlan.setTargetFinishedGoodIds(List.of("Q-OPEN", "P17"));
        com.cretas.aims.entity.ProductionPlan permittedPlan = new com.cretas.aims.entity.ProductionPlan();
        permittedPlan.setProductTypeId("RM-PORK");
        permittedPlan.setTargetFinishedGoodIds(List.of("Q-OPEN", "P18"));
        com.cretas.aims.entity.ProductionPlan openPlan = new com.cretas.aims.entity.ProductionPlan();
        openPlan.setProductTypeId("Q-OPEN");
        when(planRepository.findByFactoryId("F006")).thenReturn(List.of(hiddenPlan, permittedPlan, openPlan));
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.aggregateByProcess("F006", null, null, "RM-BEEF");

        // 第四轮 M1: 整张计划也排除 —— 兄弟产出 Q-OPEN 在这张计划里的行同样不进
        verify(reportRepo).aggregateYieldByProcess(eq("F006"), any(), any(), eq("RM-BEEF"),
                eq(List.of("P17", "RM-BEEF")), eq(List.of("plan-hidden")));
    }

    @Test
    void aggregate_plansThatOnlyPinARecipeOfAHiddenProductAreExcludedToo() {
        // 第四轮 M2: 只钉了单个配方的计划, 产品只能从配方查到 (与裁剪器、配比提示同一口径)
        when(processSecretAccessService.protectedProductTypeIds("F006")).thenReturn(java.util.Set.of("P17"));
        com.cretas.aims.entity.ProductionPlan pinned = new com.cretas.aims.entity.ProductionPlan();
        pinned.setId("plan-pinned");
        pinned.setProductTypeId("RM-X");
        pinned.setSelectedBomRecipeId("r-secret");
        com.cretas.aims.entity.bom.BomRecipe recipe = new com.cretas.aims.entity.bom.BomRecipe();
        recipe.setId("r-secret");
        recipe.setFactoryId("F006");
        recipe.setProductTypeId("P17");
        com.cretas.aims.entity.bom.BomRecipe foreign = new com.cretas.aims.entity.bom.BomRecipe();
        foreign.setId("r-foreign");
        foreign.setFactoryId("F-OTHER");
        foreign.setProductTypeId("P17");
        com.cretas.aims.entity.ProductionPlan foreignPinned = new com.cretas.aims.entity.ProductionPlan();
        foreignPinned.setId("plan-foreign-recipe");
        foreignPinned.setProductTypeId("RM-Y");
        foreignPinned.setSelectedBomRecipeId("r-foreign");
        when(planRepository.findByFactoryId("F006")).thenReturn(List.of(pinned, foreignPinned));
        when(recipeRepository.findAllById(any())).thenReturn(List.of(recipe, foreign));
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection(), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.aggregateByProcess("F006", null, null, null);

        // 他厂的配方不认 (plan-foreign-recipe 不排除)
        verify(reportRepo).aggregateYieldByProcess(eq("F006"), any(), any(), eq(""),
                eq(List.of("P17", "RM-X")), eq(List.of("plan-pinned")));
    }
}
