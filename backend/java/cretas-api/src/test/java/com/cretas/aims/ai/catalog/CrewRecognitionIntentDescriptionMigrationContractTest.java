package com.cretas.aims.ai.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-17 F006 数字计划员「帮我建个生产计划：明天做 2 吨…」被决策模型判成 BATCH_UPDATE。决策模型的选项文字 =
 * 意图名 + 描述 + 前 3 个常见说法 (backend/python/ai/matcher/decision.py)。PRODUCTION_PLAN_CREATE 描述为空、
 * BATCH_UPDATE 描述写着「计划产量」。钉住迁移: 新说法放在最前 (只带前 3 个), BATCH_UPDATE 写明不用于新建。
 */
@DisplayName("迁移 V20261117_83: 建计划 / 快过期 / 出成率的意图描述与说法")
class CrewRecognitionIntentDescriptionMigrationContractTest {

    private static String sql() throws Exception {
        try (InputStream in = CrewRecognitionIntentDescriptionMigrationContractTest.class.getResourceAsStream(
                "/db/flyway/V20261117_83__crew_recognition_intent_descriptions.sql")) {
            assertThat(in).as("迁移文件在 classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void productionPlanCreateGetsDescriptionAndLeadingKeywords() throws Exception {
        String s = sql();
        assertThat(s).contains("SET description = '新建一份生产计划")
                .contains("'[\"建个生产计划\",\"建生产计划\",\"新建生产计划\"]'::jsonb\n               || COALESCE")
                .contains("WHERE intent_code = 'PRODUCTION_PLAN_CREATE'");
    }

    @Test
    void batchUpdateSaysItIsNotForNewPlans() throws Exception {
        assertThat(sql()).contains("不用于新建生产计划").contains("WHERE intent_code = 'BATCH_UPDATE'");
    }

    @Test
    void expiringAndYieldGetLeadingKeywords() throws Exception {
        String s = sql();
        assertThat(s).contains("'[\"快过期\",\"临期\",\"即将过期\"]'::jsonb")
                .contains("WHERE intent_code = 'MATERIAL_EXPIRING_ALERT'")
                .contains("'[\"出成率怎么样\",\"出成率\",\"成品率\",\"得率\"]'::jsonb")
                .contains("WHERE intent_code = 'REPORT_BATCH_YIELD_RANK'");
    }
}
