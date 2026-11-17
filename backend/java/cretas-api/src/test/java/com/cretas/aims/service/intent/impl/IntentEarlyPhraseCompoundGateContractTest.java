package com.cretas.aims.service.intent.impl;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1813 终审 L1: 流式管线的复合句闸只挡了 SSE 短语快捷; 决策层关 / 异常 / 空时回落到旧管线, 以及决策层之前的
 * 「质检 / 趋势」放行入口, 都经 {@code IntentRecognitionPipelineServiceImpl.tryEarlyPhraseMatch} 再匹配一次同一张短语表。
 * 这里配真实短语表, 证明那一处同样不再把带条件 / 多个子问的句子截走, 无条件的短语照旧命中。
 */
@DisplayName("旧管线早期短语: 与流式同一道复合句闸")
class IntentEarlyPhraseCompoundGateContractTest {

    private static final String FACTORY = "F006";

    private IntentRecognitionPipelineServiceImpl pipeline;
    private IntentKnowledgeBase knowledge;

    @BeforeEach
    void setUp() {
        pipeline = new IntentRecognitionPipelineServiceImpl(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null);
        knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
        IntentConfigManagementService configs = mock(IntentConfigManagementService.class);
        when(configs.getAllIntents(anyString())).thenAnswer(inv -> allPhraseIntents());
        ReflectionTestUtils.setField(pipeline, "knowledgeBase", knowledge);
        ReflectionTestUtils.setField(pipeline, "configService", configs);
    }

    /** 短语表里出现的每个意图都配一条可用的配置 —— 「没命中」只能是闸挡的, 不会是配置缺。 */
    private List<AIIntentConfig> allPhraseIntents() {
        return knowledge.getPhraseToIntentMapping().values().stream().distinct()
                .map(code -> AIIntentConfig.builder().id(code).intentCode(code).intentName(code)
                        .toolName(code.toLowerCase()).isActive(true).businessType("FACTORY").build())
                .toList();
    }

    private Optional<IntentMatchResult> early(String input) {
        return ReflectionTestUtils.invokeMethod(pipeline, "tryEarlyPhraseMatch", input, FACTORY, "FACTORY",
                Collections.<String>emptySet());
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {
            "周五要交 2 吨酱牛肉急单，原料够不够，产线排不排得开？",
            "现在牛前腱还有多少库存？",
            "这个月销售额多少，哪个客户买得最多？"})
    @DisplayName("带条件 / 多个子问 → 早期短语不截走 (交给后面的识别)")
    void compoundSentenceIsNotTakenByTheEarlyPhrase(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).as("前提: 真实短语表会命中这句").isPresent();
        assertThat(early(input)).isEmpty();
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"库存查询", "本月营收"})
    @DisplayName("阳性对照: 无条件的短语 (含时间词开头的短语原样说出) 照旧命中")
    void unconditionedPhraseStillMatches(String input) {
        Optional<IntentMatchResult> result = early(input);
        assertThat(result).isPresent();
        assertThat(result.get().getBestMatch().getIntentCode())
                .isEqualTo(knowledge.matchPhrase(input, "FACTORY").orElseThrow());
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {"现在牛前腱还有多少库存？"})
    @DisplayName("放行入口 (expectedCodes 非空) 也过同一道闸")
    void expectedCodeEntryIsGatedToo(String input) {
        String code = knowledge.matchSpecificPhrase(input, "FACTORY").orElse(null);
        assertThat(code).as("前提: 放行入口的短语表也命中这句").isNotNull();
        Optional<IntentMatchResult> result = ReflectionTestUtils.invokeMethod(pipeline, "tryEarlyPhraseMatch",
                input, FACTORY, "FACTORY", Set.of(code));
        assertThat(result).isEmpty();
    }
}
