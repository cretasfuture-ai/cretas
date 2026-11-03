package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 食品 DAG 缺口 10 (Steve 2026-11-03): 联产工序在工艺版本上选「固定比例」, 报工时自动带出, 仍可改, 改了留记录;
 * 默认按重量 (现状)。⛔ 不做按售价比例。
 */
class MultiOutputFixedRatioAllocationContractTest {

    private static final Map<String, BigDecimal> FIXED_80_20 =
            Map.of("out-main", new BigDecimal("80"), "out-side", new BigDecimal("20"));

    @Test
    void fixedRatio8020SplitsJointCostOf1000Into800And200() {
        List<String> warnings = new ArrayList<>();
        // 产出重量 50kg / 50kg: 按重量会是 500/500 —— 固定比例必须压过重量。
        ProcessSheetServiceImpl.CostAllocationPlan plan = ProcessSheetServiceImpl.resolveCostAllocationPlan(
                List.of(item("out-main", "50", "kg", null), item("out-side", "50", "kg", null)),
                FIXED_80_20, warnings);

        assertEquals("FIXED_RATIO", plan.basis());
        List<BigDecimal> money = ProcessSheetServiceImpl.allocateMoney(new BigDecimal("1000"), plan.ratios());
        assertEquals(0, new BigDecimal("800").compareTo(money.get(0)), money.toString());
        assertEquals(0, new BigDecimal("200").compareTo(money.get(1)), money.toString());
        assertEquals(List.of(new BigDecimal("80"), new BigDecimal("20")), plan.workflowShares());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void prefilledSharesFromTheReportPageStayFixedRatio() {
        ProcessSheetServiceImpl.CostAllocationPlan plan = ProcessSheetServiceImpl.resolveCostAllocationPlan(
                List.of(item("out-main", "50", "kg", "80"), item("out-side", "3", "盒", "20")),
                FIXED_80_20, new ArrayList<>());
        assertEquals("FIXED_RATIO", plan.basis());
    }

    @Test
    void editedSharesAtReportTimeBecomeExplicitAndKeepTheWorkflowShares() {
        List<String> warnings = new ArrayList<>();
        ProcessSheetServiceImpl.CostAllocationPlan plan = ProcessSheetServiceImpl.resolveCostAllocationPlan(
                List.of(item("out-main", "50", "kg", "70"), item("out-side", "50", "kg", "30")),
                FIXED_80_20, warnings);

        assertEquals("EXPLICIT_RATIO", plan.basis());
        List<BigDecimal> money = ProcessSheetServiceImpl.allocateMoney(new BigDecimal("1000"), plan.ratios());
        assertEquals(0, new BigDecimal("700").compareTo(money.get(0)));
        assertEquals(0, new BigDecimal("300").compareTo(money.get(1)));
        // 改了留记录: 每行记下工艺原份额, 并提示
        assertEquals(List.of(new BigDecimal("80"), new BigDecimal("20")), plan.workflowShares());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("手动修改了工艺上的固定分摊比例")), warnings.toString());
    }

    @Test
    void editedSharesStillMustTotalHundred() {
        assertThrows(BusinessException.class, () -> ProcessSheetServiceImpl.resolveCostAllocationPlan(
                List.of(item("out-main", "50", "kg", "70"), item("out-side", "50", "kg", "20")),
                FIXED_80_20, new ArrayList<>()));
    }

    @Test
    void withoutFixedRatioItStaysWeightBased() {
        ProcessSheetServiceImpl.CostAllocationPlan plan = ProcessSheetServiceImpl.resolveCostAllocationPlan(
                List.of(item("out-main", "30", "kg", null), item("out-side", "10", "kg", null)),
                Map.of(), new ArrayList<>());
        assertEquals("MASS", plan.basis());
        assertNull(plan.workflowShares());
        List<BigDecimal> money = ProcessSheetServiceImpl.allocateMoney(new BigDecimal("1000"), plan.ratios());
        assertEquals(0, new BigDecimal("750").compareTo(money.get(0)));
    }

    @Test
    void readsFixedSharesOnlyForTheReportedProcess() {
        WorkflowClerkSheetConfigDTO.PortDescriptor main = WorkflowClerkSheetConfigDTO.PortDescriptor.builder()
                .workflowPortId("out-main").fixedCostShare(new BigDecimal("80")).build();
        WorkflowClerkSheetConfigDTO.PortDescriptor side = WorkflowClerkSheetConfigDTO.PortDescriptor.builder()
                .workflowPortId("out-side").fixedCostShare(new BigDecimal("20")).build();
        WorkflowClerkSheetConfigDTO.ProcessDescriptor process = WorkflowClerkSheetConfigDTO.ProcessDescriptor.builder()
                .processOrder(2).outputs(List.of(main, side)).build();
        WorkflowClerkSheetConfigDTO config = WorkflowClerkSheetConfigDTO.builder().processes(List.of(process)).build();

        assertEquals(FIXED_80_20, ProcessSheetServiceImpl.fixedCostShares(config, 2));
        assertTrue(ProcessSheetServiceImpl.fixedCostShares(config, 1).isEmpty());
        assertTrue(ProcessSheetServiceImpl.fixedCostShares(null, 2).isEmpty());
    }

    private static ProcessSheetServiceImpl.MultiOutputMaterialization item(
            String portId, String quantity, String unit, String enteredRatio) {
        ProcessSheetRowRequest.OutputLine output = new ProcessSheetRowRequest.OutputLine();
        output.setWorkflowPortId(portId);
        output.setProductTypeId("SKU-" + portId);
        output.setQuantity(new BigDecimal(quantity));
        output.setUnit(unit);
        output.setCostAllocationRatio(enteredRatio == null ? null : new BigDecimal(enteredRatio));
        return new ProcessSheetServiceImpl.MultiOutputMaterialization(
                new ProcessSheetRowRequest(), output,
                new ProcessSheetServiceImpl.OneOutputOutcome(null, null, null, null, null),
                new ProcessSheetRowResult.OutputResult());
    }
}
