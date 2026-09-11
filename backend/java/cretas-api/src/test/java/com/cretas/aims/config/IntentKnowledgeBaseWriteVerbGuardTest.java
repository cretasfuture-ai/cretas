package com.cretas.aims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短语直通的动词守卫：输入带写动词时，指向只读意图的名词短语不算命中。
 * 阴性对照：纯查询句、以及写动词 + 写意图，守卫都不介入。
 */
class IntentKnowledgeBaseWriteVerbGuardTest {

    @Test
    @DisplayName("只读意图形态识别：后缀 / 前缀")
    void readOnlyIntentShapes() {
        assertTrue(IntentKnowledgeBase.isReadOnlyIntent("QUALITY_CHECK_QUERY"));
        assertTrue(IntentKnowledgeBase.isReadOnlyIntent("REPORT_FINANCE"));
        assertTrue(IntentKnowledgeBase.isReadOnlyIntent("SUPPLIER_RANKING"));
        assertTrue(IntentKnowledgeBase.isReadOnlyIntent("ATTENDANCE_STATS_BY_DEPT"));
        // 阴性对照：写意图不是只读
        assertFalse(IntentKnowledgeBase.isReadOnlyIntent("MATERIAL_BATCH_CREATE"));
        assertFalse(IntentKnowledgeBase.isReadOnlyIntent("QUALITY_CHECK_EXECUTE"));
        assertFalse(IntentKnowledgeBase.isReadOnlyIntent("SHIPMENT_UPDATE"));
        assertFalse(IntentKnowledgeBase.isReadOnlyIntent(null));
    }

    @Test
    @DisplayName("写动词检测：删除/创建/录入 命中，查/看/统计 不命中")
    void writeVerbDetection() {
        assertTrue(IntentKnowledgeBase.hasWriteVerb("帮我删除这条质检记录"));
        assertTrue(IntentKnowledgeBase.hasWriteVerb("创建入库记录，数量-100公斤"));
        assertTrue(IntentKnowledgeBase.hasWriteVerb("帮我录入供应商评价"));
        assertTrue(IntentKnowledgeBase.hasWriteVerb("批量创建200个新的原料批次"));
        // 阴性对照
        assertFalse(IntentKnowledgeBase.hasWriteVerb("查一下冷链配送的发货记录"));
        assertFalse(IntentKnowledgeBase.hasWriteVerb("这个月的质检统计"));
        assertFalse(IntentKnowledgeBase.hasWriteVerb("帮我对比一下这两个月的出货数据"));
    }
}
