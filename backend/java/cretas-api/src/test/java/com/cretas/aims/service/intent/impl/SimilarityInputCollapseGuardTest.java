package com.cretas.aims.service.intent.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 塌缩残句不做相似度：≤4 字且不足原句一半时改用原句；短问句和轻量归一化照旧。 */
class SimilarityInputCollapseGuardTest {

    @Test
    @DisplayName("长句被压成「记录记录」→ 用原句")
    void collapsedUsesOriginal() {
        String original = "帮我登记一条安全巡检记录：今天的巡检发现消防通道有物品堆放，已要求整改";
        assertEquals(original, IntentRecognitionPipelineServiceImpl.pickSimilarityInput("记录记录", original));
        assertEquals("删除已作废的供应商记录",
                IntentRecognitionPipelineServiceImpl.pickSimilarityInput("删除记录", "删除已作废的供应商记录"));
    }

    @Test
    @DisplayName("阴性对照：本来就短的问句、或只做了轻量归一化的输入，照旧用处理后的")
    void shortOrLightlyNormalizedKeepsProcessed() {
        assertEquals("查库存", IntentRecognitionPipelineServiceImpl.pickSimilarityInput("查库存", "查查库存"));
        assertEquals("分析一下原因", IntentRecognitionPipelineServiceImpl.pickSimilarityInput("分析一下原因", "分析一下原因"));
        assertEquals("查询员工信息", IntentRecognitionPipelineServiceImpl.pickSimilarityInput("查询员工信息", "查询员工张三的信息"));
        assertEquals(null, IntentRecognitionPipelineServiceImpl.pickSimilarityInput(null, "x"));
    }
}
