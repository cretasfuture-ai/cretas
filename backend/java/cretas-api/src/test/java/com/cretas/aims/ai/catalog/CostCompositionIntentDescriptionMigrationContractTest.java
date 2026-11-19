package com.cretas.aims.ai.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-19 F006「演示·酱卤牛肉250g 的成本是怎么构成的？」被决策模型判成 BOM_RECIPE_QUERY (描述写着「+ 成本」)。
 * 决策模型的选项文字 = 意图名 + 描述 + 前 3 个常见说法 (backend/python/ai/matcher/decision.py)。钉住迁移:
 * COST_QUERY 描述写明成本构成并把说法放在最前; BOM_RECIPE_QUERY 写明成本构成不归它。
 */
@DisplayName("迁移 V20261120_21: 成本构成问法归成本查询")
class CostCompositionIntentDescriptionMigrationContractTest {

    private static String sql() throws Exception {
        try (InputStream in = CostCompositionIntentDescriptionMigrationContractTest.class.getResourceAsStream(
                "/db/flyway/V20261120_21__cost_composition_intent_descriptions.sql")) {
            assertThat(in).as("迁移文件在 classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void costQueryDescribesCompositionAndLeadsWithTheSayings() throws Exception {
        assertThat(sql())
                .contains("SET description = '只读查询生产成本：期间内已完成批次的成本构成")
                .contains("「酱卤牛肉成本怎么构成」", "「演示·酱卤牛肉250g 的成本是怎么构成的」", "「酱卤牛肉成本是怎么算的」")
                .contains("'[\"成本构成\",\"成本怎么构成\",\"成本是怎么算的\"]'::jsonb\n               || COALESCE")
                .contains("WHERE intent_code = 'COST_QUERY'");
    }

    @Test
    void bomRecipeQueryNoLongerClaimsCost() throws Exception {
        String s = sql();
        assertThat(s).contains("WHERE intent_code = 'BOM_RECIPE_QUERY'")
                .contains("问成本构成、成本占比、成本怎么算的不用这个意图，用成本查询");
        assertThat(s).as("新描述不再写「+ 成本」").doesNotContain("出成率 + 成本");
    }

    @Test
    void migrationVerifiesItsOwnWrites() throws Exception {
        assertThat(sql()).contains("RAISE EXCEPTION 'COST_QUERY rows=%", "RAISE EXCEPTION 'BOM_RECIPE_QUERY rows=%");
    }
}
