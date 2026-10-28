package com.cretas.aims.repository;

import com.cretas.aims.entity.intent.IntentOptimizationSuggestion;
import com.cretas.aims.entity.learning.AnnotationQueue;
import com.cretas.aims.entity.learning.ParameterExtractionRule;
import com.cretas.aims.repository.learning.AnnotationQueueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复审 M2/M3/M4 (2026-10-29): 参数提取规则的删除 / 清理、意图优化建议的采纳 / 拒绝、标注任务的完成,
 * 都按 (ID, 路径工厂) 改 —— 真实 JPA 上下文里跑一遍改过的 @Modifying 查询, 别厂的行一行不动。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class IntentLearningFactoryScopeRepositoryQueryValidationTest {

    static final String FA = "F-JPA-SCOPE-A";
    static final String FB = "F-JPA-SCOPE-B";

    @Autowired TestEntityManager em;
    @Autowired ParameterExtractionRuleRepository rules;
    @Autowired IntentOptimizationSuggestionRepository suggestions;
    @Autowired AnnotationQueueRepository annotations;

    private ParameterExtractionRule rule(String factoryId) {
        ParameterExtractionRule r = ParameterExtractionRule.builder()
                .factoryId(factoryId).intentCode("PRODUCTION_QUERY").paramName("p")
                .patternType(ParameterExtractionRule.PatternType.KEYWORD_AFTER)
                .hitCount(20).successCount(1).isActive(true).build();
        return em.persist(r);
    }

    @Test
    void ruleDeleteAndCleanupOnlyTouchTheUrlFactory() {
        ParameterExtractionRule a = rule(FA);
        ParameterExtractionRule b = rule(FB);
        em.flush();
        em.clear();

        LocalDateTime now = LocalDateTime.now();
        assertThat(rules.softDelete(b.getId(), FA, now)).isZero();     // 别厂的规则: 0 行
        assertThat(rules.softDelete(a.getId(), FA, now)).isEqualTo(1);  // 阴性对照: 本厂的照常删
        assertThat(rules.deactivateLowSuccessRules(FA, 10, 0.3)).isEqualTo(1);
        em.clear();
        assertThat(em.find(ParameterExtractionRule.class, b.getId()).getIsActive()).isTrue();
        assertThat(em.find(ParameterExtractionRule.class, b.getId()).getDeletedAt()).isNull();
    }

    @Test
    void suggestionApplyAndRejectOnlyTouchTheUrlFactory() {
        IntentOptimizationSuggestion s = IntentOptimizationSuggestion
                .createPromotionSuggestion(FB, "PRODUCTION_QUERY", "产量", "原因", "u");
        em.persist(s);
        em.flush();
        em.clear();

        assertThat(suggestions.applySuggestion(s.getId(), FA, LocalDateTime.now(), 7L)).isZero();
        assertThat(suggestions.rejectSuggestion(s.getId(), FA, "x")).isZero();
        assertThat(suggestions.rejectSuggestion(s.getId(), FB, "x")).isEqualTo(1);
    }

    /**
     * 复审 Low 4: IntentAnalysisController 直接调这两个 @Modifying 查询, 外面没有事务 ——
     * 这里关掉测试自带的事务模拟同样的调用环境, 查询要能自己开事务执行 (否则 TransactionRequiredException)。
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void suggestionUpdatesRunWithoutACallerTransaction() {
        IntentOptimizationSuggestion s = suggestions.save(IntentOptimizationSuggestion
                .createPromotionSuggestion(FB + "-TX", "PRODUCTION_QUERY", "产量", "原因", "u"));
        try {
            assertThat(suggestions.rejectSuggestion(s.getId(), FB + "-TX", "x")).isEqualTo(1);
            assertThat(suggestions.applySuggestion(s.getId(), FB + "-TX", LocalDateTime.now(), 7L)).isEqualTo(1);
        } finally {
            suggestions.deleteById(s.getId());
        }
    }

    @Test
    void annotationCompletionOnlyTouchesTheUrlFactory() {
        AnnotationQueue q = AnnotationQueue.builder().factoryId(FB).userInput("今天产量").build();
        em.persist(q);
        em.flush();
        em.clear();

        assertThat(annotations.completeAnnotation(q.getId(), FA, "I", "u", LocalDateTime.now(), null)).isZero();
        assertThat(annotations.completeAnnotation(q.getId(), FB, "I", "u", LocalDateTime.now(), null)).isEqualTo(1);
    }
}
