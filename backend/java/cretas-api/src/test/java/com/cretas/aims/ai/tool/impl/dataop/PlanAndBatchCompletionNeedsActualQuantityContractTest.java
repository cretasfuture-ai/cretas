package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProductionPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 A6: AI 把计划 / 批次改成完工时, 没说实际产量就追问, ⛔ 不拿计划数量顶替实际产量入成品库 (禁止假数据)。
 * 纪律排查 A12 (AI 侧): 开工有「强制开工原因」通道, 用户给了才带给服务端的缺料闸。
 */
class PlanAndBatchCompletionNeedsActualQuantityContractTest {

    private final ProductionPlanService planService = mock(ProductionPlanService.class);
    private final ProcessingService processingService = mock(ProcessingService.class);
    private final PlanUpdateTool planTool = new PlanUpdateTool();
    private final BatchStatusChangeTool batchTool = new BatchStatusChangeTool();
    private final Map<String, Object> context = Map.of("userId", 7L, "factoryId", "F006");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(planTool, "productionPlanService", planService);
        ReflectionTestUtils.setField(batchTool, "processingService", processingService);
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setId("PP-1");
        plan.setPlanNumber("PP-QA-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        when(planService.getProductionPlanById("F006", "PP-1")).thenReturn(plan);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void completingAPlanWithoutAnActualQuantityAsksForIt() {
        assertThat(planTool.validateRequiredParams(params("planId", "PP-1", "status", "COMPLETED")))
                .containsExactly("actualQuantity");
        // 只给了计划数量 (计划量 ≠ 实际产量) 也要追问
        assertThat(planTool.validateRequiredParams(params("planId", "PP-1", "status", "completed", "plannedQuantity", 100)))
                .containsExactly("actualQuantity");
        assertThat(planTool.getParameterQuestion("actualQuantity")).contains("实际");
    }

    @Test
    void otherStatusChangesDoNotAskForAnActualQuantity() {
        assertThat(planTool.validateRequiredParams(params("planId", "PP-1", "status", "PAUSED"))).isEmpty();
        assertThat(planTool.validateRequiredParams(params("planId", "PP-1", "plannedQuantity", 80))).isEmpty();
    }

    @Test
    void completingAPlanPassesTheSpokenActualQuantityNotThePlannedOne() throws Exception {
        planTool.doExecute("F006", params("planId", "PP-1", "status", "COMPLETED",
                "plannedQuantity", 100, "actualQuantity", 92), context);

        verify(planService).completeProduction(eq("F006"), eq("PP-1"),
                org.mockito.ArgumentMatchers.argThat(q -> q != null && q.compareTo(new BigDecimal("92")) == 0),
                eq(7L), isNull());
    }

    @Test
    void evenIfTheQuestionWereSkippedThePlannedQuantityIsNeverUsedAsTheActualOne() throws Exception {
        // 第二道防线: 绕过 validateRequiredParams 直接执行, 也 ⛔ 不拿计划量 100 当实际产量
        planTool.doExecute("F006", params("planId", "PP-1", "status", "COMPLETED", "plannedQuantity", 100), context);

        verify(planService, never()).completeProduction(eq("F006"), eq("PP-1"),
                org.mockito.ArgumentMatchers.argThat(q -> q != null && q.compareTo(new BigDecimal("100")) == 0),
                any(), any());
    }

    @Test
    void startingAPlanCarriesTheForceStartReasonOnlyWhenTheUserGaveOne() throws Exception {
        planTool.doExecute("F006", params("planId", "PP-1", "status", "IN_PROGRESS"), context);
        verify(planService).startProduction("F006", "PP-1", 7L, null);

        planTool.doExecute("F006", params("planId", "PP-1", "status", "IN_PROGRESS",
                "forceStartReason", "客户急单"), context);
        verify(planService).startProduction("F006", "PP-1", 7L, "客户急单");
        // 通用「更新原因」不当强制开工原因用 (否则随口一句原因就绕过了缺料闸)
        planTool.doExecute("F006", params("planId", "PP-1", "status", "IN_PROGRESS", "reason", "排产调整"), context);
        verify(planService, never()).startProduction("F006", "PP-1", 7L, "排产调整");
    }

    @Test
    void completingABatchWithoutQuantitiesAsksForThem() {
        assertThat(batchTool.validateRequiredParams(params("batchId", "1885", "operation", "complete")))
                .containsExactly("actualQuantity", "goodQuantity");
        assertThat(batchTool.validateRequiredParams(params("batchId", "1885", "operation", "complete",
                "actualQuantity", 50, "goodQuantity", 49))).isEmpty();
        assertThat(batchTool.validateRequiredParams(params("batchId", "1885", "operation", "pause"))).isEmpty();
    }

    @Test
    void batchToolPassesForceStartAndOverPlanReasons() throws Exception {
        batchTool.doExecute("F006", params("batchId", "1885", "operation", "start", "supervisorId", 7,
                "forceStartReason", "缺的料下午到"), context);
        verify(processingService).startProduction("F006", "1885", 7, "缺的料下午到");

        batchTool.doExecute("F006", params("batchId", "1885", "operation", "complete", "actualQuantity", 120,
                "goodQuantity", 120, "defectQuantity", 0, "overPlanReason", "出成偏高"), context);
        verify(processingService).completeProductionWithReason(eq("F006"), eq("1885"), any(), any(), any(),
                eq("出成偏高"));
        verify(processingService, never()).completeProduction(anyString(), anyString(), any(), any(), any());
    }
}
