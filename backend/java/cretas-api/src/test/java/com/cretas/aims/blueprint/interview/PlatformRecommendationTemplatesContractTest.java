package com.cretas.aims.blueprint.interview;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 隐私条款第七条 (2026-10-24): 工艺流程建议仅来源于公开资料、行业标准及平台自编模板, 平台不分析、
 * 不比对任何客户的工艺流程。对话建厂的每个行业选项 (除「其他」) 都要有一份平台自编模板, 且模板:
 * 能被 {@link PlatformRecommendationLibrary} 正常加载 (含禁止来源字段检查), 不带任何得率数值,
 * 并在文件头注明来源是公开资料。
 */
class PlatformRecommendationTemplatesContractTest {

    private final PlatformRecommendationLibrary library = new PlatformRecommendationLibrary();

    @ParameterizedTest
    @ValueSource(strings = {"食品加工", "水产加工", "预制菜", "卤味熟食", "烘焙", "饮料"})
    void everyIndustryHasALoadablePlatformTemplate(String industry) throws Exception {
        PlatformRecommendationLibrary.PlatformRecommendation r = library.forIndustry(industry);
        assertThat(r).as(industry).isNotNull();
        assertThat(r.getIndustryHint()).isEqualTo(industry);
        assertThat(r.getStepCount()).isGreaterThanOrEqualTo(5);
        assertThat(r.getSuggestions()).containsKeys("step1.name", "step1.inputMaterial", "step1.output");
        for (Map<String, Object> step : r.getSteps()) {
            assertThat(step).as(industry + " " + step.get("name")).doesNotContainKey("yieldRange");
        }
        assertThat(r.getSuggestions().entrySet())
                .filteredOn(e -> e.getKey().endsWith(".yieldRate"))
                .allSatisfy(e -> assertThat(e.getValue()).doesNotContainPattern("\\d"));

        String source;
        try (InputStream in = new ClassPathResource(
                "ai/factory-interview/platform-recommendations/" + industry + ".yaml").getInputStream()) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(source).contains("仅来源于公开资料、行业标准及平台自编模板");
    }

    @ParameterizedTest
    @ValueSource(strings = {"factoryId", "derivedFrom", "sourceFactoryName", "similarity"})
    void provenanceFieldsAreRejected(String key) {
        Map<String, Object> raw = Map.of("industryHint", "x",
                "steps", List.of(Map.of("name", "y", key, "F006")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> library.rejectPrivateProvenance(raw, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
