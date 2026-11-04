package com.cretas.aims.service.foodsafety.timelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺口 14 的接线: 时限只拦投料那一刻。新增一个投料入口时这里不会自动知道, 所以也钉住「故意不接」的过账路径 ——
 * 有人把闸挪进过账, 合法投料会事后失败。
 */
class ProcessTimeLimitWiringContractTest {

    private static final Path MAIN = Path.of("src/main/java/com/cretas/aims");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8);
    }

    /** 方法体: 从签名到下一个同缩进的方法注解 / 签名。够钉「这行在这个方法里」就行。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法: " + signature);
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    @Test
    @DisplayName("逐道录入: saveRow 在多产出分支之前过闸")
    void webFeedRunsBeforeMultiOutputBranch() throws IOException {
        String source = read("service/processentry/impl/ProcessSheetServiceImpl.java")
                .replace("\r\n", "\n");
        String body = methodBody(source, "public ProcessSheetRowResult saveRow(");
        int gate = body.indexOf("processTimeLimitService.assertWebFeed(");
        int multi = body.indexOf("saveMultiOutputRow(");
        assertTrue(gate >= 0 && gate < multi, "时限闸必须在多产出分支之前");
    }

    @Test
    @DisplayName("草稿不是投料: 已保存内容只认非草稿行")
    void draftRowsDoNotCountAsFed() throws IOException {
        String source = read("service/processentry/impl/ProcessSheetServiceImpl.java");
        assertTrue(source.contains(".filter(row -> !\"DRAFT\".equals(row.getRowStatus()))"));
    }

    @Test
    @DisplayName("App 报工 / App 领料 / 二次加工开工都过闸")
    void appAndSecondaryFeedsAreWired() throws IOException {
        assertTrue(read("service/yield/impl/YieldReportServiceImpl.java").contains("assertSemiFeedForTask("));
        assertTrue(read("service/production/TaskMaterialBatchPickerService.java").contains("assertRawFeedForTask("));
        assertTrue(read("service/impl/ProductionPlanServiceImpl.java").contains("assertSemiFeedForPlan("));
    }

    @Test
    @DisplayName("过账路径故意不接: 审批 / 小结 / 严格扣减里没有时限闸")
    void postingPathsStayUnguarded() throws IOException {
        for (String file : List.of(
                "service/wip/impl/WipInventoryServiceImpl.java",
                "service/yield/impl/InterimSettleServiceImpl.java",
                "service/inventory/impl/FinishedGoodsFeedServiceImpl.java")) {
            assertEquals(false, read(file).contains("ProcessTimeLimitService"), file);
        }
    }

    @Test
    @DisplayName("留痕走独立事务: 主事务回滚后还在")
    void traceWriterIsRequiresNew() throws IOException {
        assertTrue(read("service/foodsafety/timelimit/ProcessTimeLimitEventWriter.java")
                .contains("Propagation.REQUIRES_NEW"));
    }
}
