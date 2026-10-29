package com.cretas.aims.service.impl;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.consent.TrainingConsentService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工艺保密产品不许被「学走」(Steve 2026-10-29)。守的行为:
 * <ul>
 *   <li>A 厂提到 A 厂保密产品的说法不学 (新写入和重提议激活都不行); 不提保密产品的照常学 (阴性对照);</li>
 *   <li>配方 / 工艺参数、撤回授权的工厂不学 (与训练样本同一套规则);</li>
 *   <li>全局说法 (空 / "*") 只收人工预置的, 且不许提到任何一家工厂的保密产品名;</li>
 *   <li>B 厂能匹配到的全局说法里没有 A 厂的保密产品名 (历史上系统学来的无工厂行不给任何厂用)。</li>
 * </ul>
 * 保密产品名单走真实的 {@link ProcessSecretSampleFilter} (只 mock 它下面的保密策略和产品表)。
 */
class ExpressionLearningProcessSecretContractTest {

    private static final String FA = "FA";
    private static final String FB = "FB";
    private static final String SECRET = "秘制酱牛肉";

    private LearnedExpressionRepository repo;
    private TrainingConsentService consent;
    private ProcessSecretAccessService access;
    private ExpressionLearningServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(LearnedExpressionRepository.class);
        consent = mock(TrainingConsentService.class);
        when(consent.status(anyString())).thenReturn(TrainingConsentService.Status.AUTHORIZED);

        access = mock(ProcessSecretAccessService.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        when(access.factoriesWithProtectedProducts()).thenReturn(Set.of(FA));
        when(access.protectedProductTypeIds(FA)).thenReturn(Set.of("pA"));
        ProductType p = new ProductType();
        p.setId("pA");
        p.setFactoryId(FA);
        p.setName(SECRET);
        when(products.findAllById(Set.of("pA"))).thenReturn(List.of(p));
        ProcessSecretSampleFilter filter = new ProcessSecretSampleFilter(access, products);

        service = new ExpressionLearningServiceImpl(repo, null, null, null, null, consent, filter);
        ReflectionTestUtils.setField(service, "self", mock(ExpressionLearningService.class));
        when(repo.findOneByHashAndFactory(anyString(), any())).thenReturn(Optional.empty());
        when(repo.save(any(LearnedExpression.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- 本厂说法 ----------

    @Test
    void factoryExpressionNamingItsOwnProtectedProductIsNotLearned() {
        assertThat(service.learnExpression(FA, "PRODUCTION_QUERY", SECRET + "今天做了多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK)).isNull();
        verify(repo, never()).save(any());
        // 闸在去重之前: 连已有行都不查 (重提议激活这条路同样走不到)
        verify(repo, never()).findOneByHashAndFactory(anyString(), any());
    }

    @Test
    void plainFactoryExpressionIsStillLearned() {
        LearnedExpression saved = service.learnExpression(FA, "PRODUCTION_QUERY", "今天做了多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK);
        assertThat(saved).isNotNull();
        assertThat(saved.getFactoryId()).isEqualTo(FA);
        verify(repo).save(any());
    }

    @Test
    void stagedRowNamingAProtectedProductIsNotPromotedOnReProposal() {
        LearnedExpression staged = new LearnedExpression();
        staged.setFactoryId(FA);
        staged.setIntentCode("PRODUCTION_QUERY");
        staged.setExpression(SECRET + "今天做了多少");
        staged.setIsActive(false);
        staged.setProposalCount(2);
        when(repo.findOneByHashAndFactory(anyString(), eq(FA))).thenReturn(Optional.of(staged));

        assertThat(service.learnExpression(FA, "PRODUCTION_QUERY", SECRET + "今天做了多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK)).isNull();
        assertThat(staged.getIsActive()).isFalse();
        assertThat(staged.getProposalCount()).isEqualTo(2);
        verify(repo, never()).save(any());
    }

    @Test
    void recipeParameterContentIsNotLearned() {
        assertThat(service.learnExpression(FA, "BOM_RECIPE_QUERY", "这个产品配方里盐放多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK)).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void withdrawnFactoryDoesNotLearn() {
        when(consent.status(FB)).thenReturn(TrainingConsentService.Status.WITHDRAWN);
        assertThat(service.learnExpression(FB, "PRODUCTION_QUERY", "今天做了多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK)).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void withoutTheProcessSecretFilterNothingIsLearned() {
        ExpressionLearningServiceImpl bare = new ExpressionLearningServiceImpl(repo, null, null, null, null, consent, null);
        assertThat(bare.learnExpression(FA, "PRODUCTION_QUERY", "今天做了多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK)).isNull();
        verify(repo, never()).save(any());
    }

    // ---------- 全局说法 ----------

    @Test
    void systemLearnedExpressionCannotBeWrittenAsGlobal() {
        assertThat(service.learnExpression(null, "PRODUCTION_QUERY", "今天做了多少", 0.95,
                LearnedExpression.SourceType.LLM_FALLBACK)).isNull();
        assertThat(service.learnExpression("*", "PRODUCTION_QUERY", "今天做了多少", 0.95,
                LearnedExpression.SourceType.USER_FEEDBACK)).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void manualGlobalSeedNamingAnyFactorysProtectedProductIsNotWritten() {
        // 写入方没有工厂 (全局), 但 A 厂把这个名字设成了保密: 全局对 B 厂可见, 不许写
        assertThat(service.learnExpression("*", "PRODUCTION_QUERY", SECRET + "今天做了多少", 1.0,
                LearnedExpression.SourceType.MANUAL)).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void plainManualGlobalSeedIsStillWritten() {
        LearnedExpression saved = service.learnExpression("*", "PRODUCTION_QUERY", "今天做了多少", 1.0,
                LearnedExpression.SourceType.MANUAL);
        assertThat(saved).isNotNull();
        assertThat(saved.getFactoryId()).isEqualTo("*");
    }

    @Test
    void globalExpressionsVisibleToFactoryBContainNoProtectedNamesOfFactoryA() {
        LearnedExpression legacyLearned = row("g1", null, LearnedExpression.SourceType.LLM_FALLBACK,
                SECRET.toLowerCase() + "今天做了多少");
        LearnedExpression manualGlobal = row("g2", null, LearnedExpression.SourceType.MANUAL, "今天做了多少");
        LearnedExpression own = row("b1", FB, LearnedExpression.SourceType.LLM_FALLBACK, "b厂自己的说法");
        when(repo.findByIntentCode("PRODUCTION_QUERY", FB)).thenReturn(List.of(legacyLearned, manualGlobal, own));
        when(repo.findByExpressionHash(anyString(), eq(FB))).thenReturn(List.of(legacyLearned));
        when(repo.findActiveByFactory(FB)).thenReturn(List.of(legacyLearned));
        when(repo.findCandidatesForSimilarMatch(eq(FB), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(legacyLearned, manualGlobal));

        List<LearnedExpression> visible = service.getExpressionsByIntent(FB, "PRODUCTION_QUERY");
        assertThat(visible).extracting(LearnedExpression::getId).containsExactly("g2", "b1");
        assertThat(visible).allSatisfy(e -> assertThat(e.getExpression()).doesNotContain(SECRET.toLowerCase()));

        // 精确 / 模糊 / 相似匹配同样拿不到那条历史行
        assertThat(service.matchExactExpression(FB, SECRET + "今天做了多少")).isEmpty();
        assertThat(service.matchSimilarExpressions(FB, SECRET + "今天做了多少", 5, 0.0))
                .extracting(ExpressionLearningService.ExpressionMatchResult::getExpressionId)
                .containsExactly("g2");
    }

    private static LearnedExpression row(String id, String factoryId, LearnedExpression.SourceType source,
                                         String expression) {
        LearnedExpression e = new LearnedExpression();
        e.setId(id);
        e.setFactoryId(factoryId);
        e.setIntentCode("PRODUCTION_QUERY");
        e.setExpression(expression);
        e.setSourceType(source);
        e.setIsActive(true);
        e.setHitCount(1);
        e.setIsVerified(false);
        return e;
    }
}
