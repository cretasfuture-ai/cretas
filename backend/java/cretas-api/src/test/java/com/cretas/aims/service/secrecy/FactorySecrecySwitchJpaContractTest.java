package com.cretas.aims.service.secrecy;

import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.processsecret.ProcessSecretReadTracker;
import com.cretas.aims.service.processsecret.ProcessSecretReadTrackerConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工厂级保密配置 a / b / e (+ d 的全厂并集) 在真实入口上的开 / 关对照: 真实 Hibernate 打标
 * ({@link ProcessSecretReadTracker}) + 真实出口裁剪 ({@link ProcessSecretPayloadScrubber}, 与 ResponseAdvice 同一调用),
 * 同一个请求只改配置行。模式照 ProcessSecretCostContextOverfetchJpaContractTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class,
        ProcessSecretReadTrackerConfiguration.class, ProcessSecretAggregateExclusions.class,
        ProcessSecretLlmCostScope.class, ProcessSecretPayloadScrubber.class, ProcessSecretCostMask.class,
        ProcessSecretSampleFilter.class, com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard.class,
        FactorySecrecySwitchJpaContractTest.Config.class})
class FactorySecrecySwitchJpaContractTest {

    static final String F = "F-SW-A";
    static final String G = "F-SW-B";
    static final String SECRET = "PT-SW-SECRET";
    static final String HELD = "PT-SW-HELD";
    static final String G_SECRET = "PT-SW-G-SECRET";
    static final long OWNER = 9301L;
    static final long COLLEAGUE = 9302L;
    static final long GRANTEE = 9303L;

    /** 切片里读配置不另开事务 (生产是 REQUIRES_NEW 只读事务; 这里配置行写在测试事务里, 另开事务看不到)。 */
    @TestConfiguration
    static class Config {
        @Bean
        FactorySecrecyConfig factorySecrecyConfig(FactorySecrecySettingRepository repository,
                                                  com.cretas.aims.repository.FactoryRepository factories) {
            // 与生产一样查工厂是否存在 (本测试的两家工厂在工厂表里); 只是不另开读事务
            return new FactorySecrecyConfig(repository, factories,
                    (org.springframework.transaction.PlatformTransactionManager) null);
        }
    }

    @Autowired EntityManager entityManager;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductTypeRepository productTypes;
    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretGrantRepository grants;
    @Autowired FactorySecrecySettingRepository settings;
    @Autowired FactorySecrecyConfig secrecy;
    @Autowired ProcessSecretAccessService accessService;
    @Autowired ProcessSecretLlmCostScope costScope;
    @Autowired ProcessSecretPayloadScrubber scrubber;
    @Autowired ProcessSecretCostMask costMask;
    @Autowired ProcessSecretSampleFilter sampleFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AIContextServiceImpl contexts;

    @BeforeEach
    void seed() {
        for (String id : new String[]{F, G}) {
            Factory factory = new Factory();
            factory.setId(id);
            factory.setName("Secrecy Switch " + id);
            entityManager.persist(factory);
        }
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("secrecy-switch-user");
        user.setPasswordHash("test");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        productTypes.saveAndFlush(product(F, SECRET, "SW保密卤牛肉", user.getId()));
        productTypes.saveAndFlush(product(F, HELD, "SW客户侧酱鸭", user.getId()));
        productTypes.saveAndFlush(product(G, G_SECRET, "SW乙厂秘制酱", user.getId()));
        batches.saveAndFlush(batch(F, "B-SW-SECRET", SECRET, "7777.77", "100"));
        batches.saveAndFlush(batch(F, "B-SW-HELD", HELD, "5555.55", "100"));
        entityManager.createNativeQuery("UPDATE production_batches SET selected_bom_recipe_ids_by_product = NULL, "
                + "selected_bom_versions_by_product = NULL, workflow_output_units_by_product = NULL, "
                + "target_finished_good_ids = NULL, custom_fields = NULL, ai_invocation_metadata = NULL").executeUpdate();
        policies.saveAndFlush(policy(F, SECRET, ProcessSecretStorageMode.CLOUD_RESTRICTED));
        policies.saveAndFlush(policy(F, HELD, ProcessSecretStorageMode.CUSTOMER_HELD));
        policies.saveAndFlush(policy(G, G_SECRET, ProcessSecretStorageMode.CLOUD_RESTRICTED));
        grants.saveAndFlush(ProcessSecretGrant.builder().factoryId(F).productTypeId(SECRET)
                .granteeType(ProcessSecretGranteeType.FACTORY_USER).granteeId(GRANTEE)
                .canViewFull(true).canExport(false).canEdit(false).purpose("对账")
                .expiresAt(LocalDateTime.now().plusDays(3)).grantedBy(OWNER).createdAt(LocalDateTime.now()).build());

        BomService bom = mock(BomService.class);
        when(bom.calculateProductCost(eq(F), anyString())).thenReturn(BomCostSummaryDTO.builder()
                .totalCost(new BigDecimal("6.41")).materialCostTotal(new BigDecimal("6.41")).build());
        contexts = new AIContextServiceImpl(bom, mock(ReportService.class), batches, productTypes, costScope);
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ a

    @Test
    void processSecretOffShowsTheColleagueTheFullCostAndTurningItBackOnRestoresThePolicyAndGrants() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        JsonNode on = costContext(actor(COLLEAGUE), SECRET);
        assertThat(on.path("avgActualUnitCost").isNull() || on.path("avgActualUnitCost").isMissingNode()).isTrue();
        assertThat(on.path("processSecretNote").asText()).contains("保密");
        assertThat(costContext(actor(GRANTEE), SECRET).path("avgActualUnitCost").decimalValue())
                .as("有授权的人照常看").isEqualByComparingTo("77.7777");

        set(F, SecrecyItem.PROCESS_SECRET, false);
        JsonNode off = costContext(actor(COLLEAGUE), SECRET);
        assertThat(off.path("avgActualUnitCost").decimalValue()).isEqualByComparingTo("77.7777");
        assertThat(off.findValues(ProcessSecretPayloadScrubber.MARKER)).isEmpty();
        assertThat(accessService.protectedProductTypeIds(F)).doesNotContain(SECRET);
        // 策略与授权都还在库里, 只是不生效
        assertThat(policies.findByFactoryIdAndProductTypeId(F, SECRET)).isPresent();
        assertThat(grants.findAll()).hasSize(1);

        set(F, SecrecyItem.PROCESS_SECRET, true);
        JsonNode again = costContext(actor(COLLEAGUE), SECRET);
        assertThat(again.path("avgActualUnitCost").isNull() || again.path("avgActualUnitCost").isMissingNode())
                .as("重新开启: 原策略恢复生效").isTrue();
        assertThat(costContext(actor(GRANTEE), SECRET).path("avgActualUnitCost").decimalValue())
                .as("重新开启: 原授权恢复生效").isEqualByComparingTo("77.7777");
    }

    @Test
    void theRowFactoryDecidesNotTheRequestFactory() {
        // F 开着、G 关着; 一份平台输出里两家的行都有
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
        set(G, SecrecyItem.PROCESS_SECRET, false);
        AuthenticatedActor platform = platform();
        // 前提 (一定成立的读数): F 的保密产品生效、平台对它无权; G 没有生效的保密产品
        assertThat(accessService.protectedProductTypeIds(F)).contains(SECRET);
        assertThat(accessService.protectedProductTypeIds(G)).isEmpty();
        assertThat(accessService.decide(platform, F, SECRET,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL).permitted()).isFalse();

        ArrayNode viaG = rows();
        boolean changedViaG = scrubber.scrub(viaG, platform, G, Set.of(), "GET /test", false);
        assertThat(changedViaG).as("请求工厂 G 关着, F 的行照样裁").isTrue();
        assertThat(viaG.get(0).path("conversionRate").isNull()).as("F 行的配比被去掉").isTrue();
        assertThat(viaG.get(0).has(ProcessSecretPayloadScrubber.MARKER)).isTrue();
        assertThat(viaG.get(1).path("conversionRate").asDouble()).as("G 行照常").isEqualTo(0.7);
        assertThat(viaG.get(1).has(ProcessSecretPayloadScrubber.MARKER)).isFalse();

        // 反过来: 请求工厂 F 开着, G 的行也不因此被裁
        ArrayNode viaF = rows();
        scrubber.scrub(viaF, platform, F, Set.of(), "GET /test", false);
        assertThat(viaF.get(0).path("conversionRate").isNull()).isTrue();
        assertThat(viaF.get(1).path("conversionRate").asDouble()).isEqualTo(0.7);
    }

    // ------------------------------------------------------------------ b

    @Test
    void platformRecipeBlockOffLetsThePlatformSeeTheFactorysRecipeOnlyWhileProcessSecretIsOn() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
        JsonNode on = costContext(platform(), SECRET);
        assertThat(on.path("avgActualUnitCost").isNull() || on.path("avgActualUnitCost").isMissingNode()).isTrue();
        assertThat(quantitiesHiddenFor(platform())).as("b 开: 平台连数量也看不到").isTrue();

        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, false);
        JsonNode off = costContext(platform(), SECRET);
        assertThat(off.path("avgActualUnitCost").decimalValue()).isEqualByComparingTo("77.7777");
        assertThat(quantitiesHiddenFor(platform())).as("b 关: 平台看得到").isFalse();
        // 本厂无权账号照旧被遮
        JsonNode colleague = costContext(actor(COLLEAGUE), SECRET);
        assertThat(colleague.path("avgActualUnitCost").isNull() || colleague.path("avgActualUnitCost").isMissingNode()).isTrue();
        // B 档 (e 开) 平台照样看不到
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        assertThat(accessService.decide(platform(), F, HELD,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL).permitted()).isFalse();
    }

    // ------------------------------------------------------------------ e

    @Test
    void customerHeldIsIndependentOfProcessSecret() {
        set(F, SecrecyItem.PROCESS_SECRET, false);
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        JsonNode on = costContext(actor(OWNER), HELD);
        assertThat(on.path("avgActualUnitCost").isNull() || on.path("avgActualUnitCost").isMissingNode())
                .as("a 关、e 开: B 档照旧, 连负责人都不给").isTrue();
        assertThat(accessService.protectedProductTypeIds(F)).containsExactly(HELD);

        set(F, SecrecyItem.CUSTOMER_HELD, false);
        JsonNode off = costContext(actor(COLLEAGUE), HELD);
        assertThat(off.path("avgActualUnitCost").decimalValue()).as("a、e 都关: 不保护").isEqualByComparingTo("55.5555");

        set(F, SecrecyItem.PROCESS_SECRET, true);
        JsonNode aOnly = costContext(actor(OWNER), HELD);
        assertThat(aOnly.path("avgActualUnitCost").decimalValue())
                .as("e 关、a 开: B 档退回 A 档, 负责人看得到").isEqualByComparingTo("55.5555");
    }

    // ------------------------------------------------------------------ 全厂并集 (共享内容) 与 d

    /**
     * 写给所有工厂的共享内容 (平台级意图、全局说法、共享短语表) 比对的全厂并集每次都算, <b>不看任何开关</b>
     * (第四 / 五轮复审: 跨厂保护不受开关影响; 原来按 d 剔工厂的并集已删); d 只管本厂的训练样本。
     */
    @Test
    void theAllFactoriesUnionIsAlwaysComputedAndIgnoresEverySwitch() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(G, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.TRAINING_FILTER, false);
        set(G, SecrecyItem.TRAINING_FILTER, true);
        // 共享内容的并集不看开关: d 关的 F 也在
        assertThat(sharedTerms().mentionsAny("给我看看SW乙厂秘制酱")).isTrue();
        assertThat(sharedTerms().mentionsEach("SW乙厂秘制酱", "SW保密卤牛肉")).isTrue();
        // 本厂 (d 关) 不按保密名剔训练样本; G (d 开) 照剔
        assertThat(sampleFilter.mentionsProtectedProduct(F, "SW保密卤牛肉卖了多少")).isFalse();
        assertThat(sampleFilter.mentionsProtectedProduct(G, "SW乙厂秘制酱卖了多少")).isTrue();
        // 全局说法 / 共享短语表的保密词并集同样不看开关 (d 关的 F 也在); 本厂训练样本按各自的 d
        assertThat(inTx().checkSharedContent("t", "给我看看SW乙厂秘制酱")).isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
        assertThat(inTx().checkSharedContent("t", "给我看看SW保密卤牛肉")).isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(sampleFilter.allFactoriesPolicyTermsForSharedContent(), "SW乙厂秘制酱")).isTrue();
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(sampleFilter.allFactoriesPolicyTermsForSharedContent(), "SW保密卤牛肉")).isTrue();
        assertThat(inTx().checkTrainingSample("t", F, "SW保密卤牛肉卖了多少")).isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.CLEAR);
        assertThat(inTx().checkTrainingSample("t", G, "SW乙厂秘制酱卖了多少")).isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.PROTECTED);

        set(F, SecrecyItem.TRAINING_FILTER, true);
        assertThat(sharedTerms().mentionsEach("SW乙厂秘制酱", "SW保密卤牛肉")).isTrue();
        assertThat(sampleFilter.mentionsProtectedProduct(F, "SW保密卤牛肉卖了多少")).isTrue();
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(sampleFilter.allFactoriesPolicyTermsForSharedContent(), "SW保密卤牛肉")).isTrue();
        assertThat(inTx().checkTrainingSample("t", F, "SW保密卤牛肉卖了多少")).isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
    }

    /**
     * 第四轮复审第 1 条 (跨厂保护不受任何开关影响): 某厂把所有保密项都关了, 它的保密产品名照样不能写进平台级意图
     * (所有工厂都看得到) —— 经真的 IntentConfigWriteGuard + 真的过滤器 / 访问服务 / 开关配置。
     */
    @Test
    void sharedIntentConfigRefusesAProtectedNameEvenWithEveryItemOffForThatFactory() {
        for (SecrecyItem item : SecrecyItem.values()) {
            set(G, item, false);
            set(F, item, false);
        }
        assertThat(accessService.protectedProductTypeIds(G)).as("前提: G 没有生效中的保密产品").isEmpty();
        assertThat(sampleFilter.trainingFilterOff(G)).as("前提: G 的 d 关着").isTrue();
        com.cretas.aims.service.intent.IntentConfigWriteGuard guard = new com.cretas.aims.service.intent.IntentConfigWriteGuard(
                org.mockito.Mockito.mock(com.cretas.aims.repository.config.AIIntentConfigRepository.class), inTx());
        com.cretas.aims.entity.config.AIIntentConfig platformIntent = new com.cretas.aims.entity.config.AIIntentConfig();
        platformIntent.setIntentCode("SW_SHARED_PROBE");
        platformIntent.setKeywords("[\"SW乙厂秘制酱\"]");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.requireNoProtectedNames(platformIntent))
                .isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(409));
        // 批量 (回滚) 用的同一份并集同样含它
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(
                guard.allFactoriesPolicyTermsForSharedContent(), "SW乙厂秘制酱")).isTrue();
        // 第六轮: 共享内容只有一份并集 (全名 / 编码 / ID / 专属别名) —— G 登记的专属别名同样不能写进平台级意图
        com.cretas.aims.entity.processsecret.ProcessSecretPolicy gPolicy =
                policies.findByFactoryIdAndProductTypeId(G, G_SECRET).orElseThrow();
        gPolicy.setAliases("[\"乙厂秘方酱\"]");
        policies.saveAndFlush(gPolicy);
        com.cretas.aims.entity.config.AIIntentConfig aliasIntent = new com.cretas.aims.entity.config.AIIntentConfig();
        aliasIntent.setIntentCode("SW_SHARED_ALIAS");
        aliasIntent.setKeywords("[\"乙厂秘方酱今天发了多少\"]");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.requireNoProtectedNames(aliasIntent))
                .as("别名进平台级意图").isInstanceOfSatisfying(com.cretas.aims.exception.BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(409));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.cretas.aims.service.intent.IntentConfigWriteGuard
                        .requireNoProtectedNames(aliasIntent, guard.allFactoriesPolicyTermsForSharedContent()))
                .as("批量回滚同样拦别名").isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        // 对照: 没提保密名的照常放行
        com.cretas.aims.entity.config.AIIntentConfig plain = new com.cretas.aims.entity.config.AIIntentConfig();
        plain.setIntentCode("SW_SHARED_PLAIN");
        plain.setKeywords("[\"今天产量\"]");
        guard.requireNoProtectedNames(plain);

        // 第五轮复审: 全局人工说法 (每家工厂都匹配得到) 同样不看开关 —— 提到 G 的保密名的那条不写, 无关的照写
        com.cretas.aims.repository.learning.LearnedExpressionRepository repo =
                org.mockito.Mockito.mock(com.cretas.aims.repository.learning.LearnedExpressionRepository.class);
        org.mockito.Mockito.when(repo.findOneByHashAndFactory(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any(
                com.cretas.aims.entity.learning.LearnedExpression.class))).thenAnswer(i -> i.getArgument(0));
        com.cretas.aims.service.impl.ExpressionLearningServiceImpl expressions =
                new com.cretas.aims.service.impl.ExpressionLearningServiceImpl(repo, null, null, null, null,
                        storableConsent(), inTx());
        assertThat(expressions.learnExpressions("*", "PRODUCTION_QUERY", java.util.List.of("SW乙厂秘制酱产量", "今天产量"),
                1.0, com.cretas.aims.entity.learning.LearnedExpression.SourceType.MANUAL))
                .as("全局人工说法: 提到 G 的保密名的不写").isEqualTo(1);
        // 共享短语表 (学习建议) 同样不看开关: F 的建议提到 G 的保密名 → 不能应用
        com.cretas.aims.service.impl.ActiveLearningServiceImpl active = org.mockito.Mockito.mock(
                com.cretas.aims.service.impl.ActiveLearningServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(active, "processSecretSampleFilter", inTx());
        org.springframework.test.util.ReflectionTestUtils.setField(active, "trainingConsentService", storableConsent());
        String refused = org.springframework.test.util.ReflectionTestUtils.invokeMethod(active, "suggestionNotApplicable",
                F, "SW乙厂秘制酱怎么卖");
        assertThat(refused).as("共享短语建议").contains("shared phrase table");
        assertThat((String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(active, "suggestionNotApplicable",
                F, "今天卖了多少")).as("对照: 无关的建议照常").isNull();
    }

    /**
     * Steve 2026-11-04: b 关时平台账号对该厂保密产品的所有动作 (看 / 导出 / 编辑) 都放行; 每一次放行都写一条
     * ALLOW 审计 (动作 + 平台身份)。走真实写入口用的那道闸 (ProcessSecretRecipeGuard.requireProduct, 工艺图发布 /
     * 建厂应用 / BOM 草稿转正 / BOM 与工艺接口都经它或同一个 require)。b 开时编辑拒绝, 同样留 DENY。
     */
    @Test
    void platformRecipeBlockOffLetsThePlatformEditAndEveryEditIsAudited() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, false);
        com.cretas.aims.entity.processsecret.ProcessSecretAction edit = com.cretas.aims.entity.processsecret.ProcessSecretAction.EDIT;
        assertThat(accessService.decide(platform(), F, SECRET,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL).permitted()).isTrue();
        assertThat(accessService.decide(platform(), F, SECRET,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.EXPORT).permitted()).isTrue();
        assertThat(accessService.decide(platform(), F, SECRET, edit).permitted()).isTrue();

        String ref = "platform-edit-" + java.util.UUID.randomUUID();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(platform())) {
            recipeGuard.requireProduct(F, SECRET, edit, ref);
            recipeGuard.requireProduct(F, SECRET, edit, ref);   // 每一次都记, 不去重
        }
        assertThat(events(ref)).hasSize(2).allSatisfy(e -> {
            assertThat(e.getDecision()).isEqualTo("ALLOW");
            assertThat(e.getAction()).isEqualTo(edit);
            assertThat(e.getActorType()).isEqualTo("PLATFORM_ADMIN");
            assertThat(e.getActorId()).isEqualTo(platform().id());
            assertThat(e.getProductTypeId()).isEqualTo(SECRET);
        });

        // b 开: 编辑拒绝 (403), 也留 DENY
        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
        String denyRef = "platform-edit-deny-" + java.util.UUID.randomUUID();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(platform())) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> recipeGuard.requireProduct(F, SECRET, edit, denyRef))
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                    .satisfies(e -> assertThat(((com.cretas.aims.exception.BusinessException) e).getCode()).isEqualTo(403));
        }
        assertThat(events(denyRef)).singleElement().satisfies(e -> assertThat(e.getDecision()).isEqualTo("DENY"));
    }

    private java.util.List<com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent> events(String ref) {
        return accessEvents.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(F, SECRET,
                        org.springframework.data.domain.PageRequest.of(0, 200)).stream()
                .filter(e -> ref.equals(e.getResourceRef())).toList();
    }

    @Autowired com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard recipeGuard;
    @Autowired com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository accessEvents;

    /**
     * 第五轮复审: 共享内容的全厂并集含<b>已软删除</b>的保密产品 (原生查询, 与训练名单同一个) —— 产品软删除了,
     * 历史数据里的名字仍是保密的, 不能因此写进平台级意图 / 全局说法 ({@code findAllById} 会被软删除过滤掉)。
     */
    @Test
    void theSharedUnionKeepsSoftDeletedProtectedProducts() {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE product_types SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                .setParameter("id", G_SECRET).executeUpdate();
        entityManager.clear();
        sampleFilter.invalidateNameCache();     // 名字缓存 10 秒: 别的测试先读过 G 的名字, 不清就测不出软删除那条路径
        assertThat(productTypes.findById(G_SECRET)).as("前提: 实体查询已看不到它").isEmpty();
        assertThat(sharedTerms().mentionsEach("SW乙厂秘制酱")).isTrue();
        assertThat(sharedTerms().mentionsAny("给我看看SW乙厂秘制酱")).isTrue();
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(
                inTx().allFactoriesPolicyTermsForSharedContent(), "SW乙厂秘制酱")).isTrue();
    }

    /** Q1=A (Steve 2026-11-03): 训练剔除保密产品名只看 d —— a 关、d 开照样剔; a 关、d 关不剔。 */
    @Test
    void trainingNameFilteringFollowsTheTrainingFilterOnlyNotProcessSecret() {
        set(F, SecrecyItem.PROCESS_SECRET, false);
        set(F, SecrecyItem.TRAINING_FILTER, true);
        assertThat(accessService.protectedProductTypeIds(F)).as("前提: a 关, 没有生效的 A 档保密产品").doesNotContain(SECRET);
        assertThat(sampleFilter.mentionsProtectedProduct(F, "SW保密卤牛肉卖了多少")).as("a 关、d 开: 照样剔").isTrue();
        assertThat(sharedTerms().mentionsEach("SW保密卤牛肉")).as("共享内容的全厂并集也在").isTrue();
        // #1377 的收样本判断 (保密词 + 人员 + 客户) 与训练并集同一口径: a 关、d 开照样拦
        assertThat(inTx().checkTrainingSample("t", F, "SW保密卤牛肉卖了多少")).as("收样本: a 关、d 开 照样拦")
                .isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(sampleFilter.allFactoriesPolicyTermsForSharedContent(), "SW保密卤牛肉")).isTrue();
        assertThat(com.cretas.aims.service.consent.LearningContentRules.factoryLearnable(storableConsent(), inTx(),
                F, "SW保密卤牛肉卖了多少")).as("学习入口同样不学").isFalse();
        // 出外脱敏的名单仍按生效中的保密产品 (c 的事, 不在 Q1 里)
        assertThat(sampleFilter.protectedProductNames(F)).doesNotContain("SW保密卤牛肉");

        set(F, SecrecyItem.TRAINING_FILTER, false);
        assertThat(sampleFilter.mentionsProtectedProduct(F, "SW保密卤牛肉卖了多少")).as("a 关、d 关: 不剔").isFalse();
        // 共享内容的全厂并集不看 d (跨厂保护), 只有本厂训练样本按 d 放行
        assertThat(sharedTerms().mentionsEach("SW保密卤牛肉")).isTrue();
        assertThat(com.cretas.aims.service.consent.LearningContentRules.factoryLearnable(storableConsent(), inTx(),
                F, "SW保密卤牛肉卖了多少")).isTrue();
        assertThat(inTx().checkTrainingSample("t", F, "SW保密卤牛肉卖了多少")).as("收样本: d 关 不拦")
                .isEqualTo(com.cretas.aims.service.consent.ProcessSecretSampleFilter.SampleVerdict.CLEAR);
        assertThat(com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(sampleFilter.allFactoriesPolicyTermsForSharedContent(), "SW保密卤牛肉")).isTrue();
    }

    /**
     * 训练名单的判断方法 (#1377) 以 NOT_SUPPORTED 挂起调用方事务 (过滤规则第 8 条: 名单查询出错不能把调用方事务标成只能回滚),
     * 所以看不到本测试事务里还没提交的夹具; 直接调目标对象, 让查询加入测试事务 (生产上数据早已提交)。
     */
    private ProcessSecretSampleFilter inTx() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(sampleFilter);
    }

    /** 共享内容的那一份并集 (全名 / 编码 / ID / 别名, 已折叠), 按词规则比对。 */
    private SharedTerms sharedTerms() {
        return new SharedTerms(inTx().allFactoriesPolicyTermsForSharedContent());
    }

    private record SharedTerms(java.util.Set<String> terms) {
        boolean mentionsAny(String text) {
            return com.cretas.aims.service.consent.ProcessSecretTerms.mentionsAny(terms, text);
        }

        boolean mentionsEach(String... names) {
            for (String n : names) {
                if (!mentionsAny(n)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static com.cretas.aims.service.consent.TrainingConsentService storableConsent() {
        com.cretas.aims.service.consent.TrainingConsentService consent =
                mock(com.cretas.aims.service.consent.TrainingConsentService.class);
        when(consent.status(anyString())).thenReturn(com.cretas.aims.service.consent.TrainingConsentService.Status.AUTHORIZED);
        return consent;
    }

    // ------------------------------------------------------------------ helpers

    private void set(String factoryId, SecrecyItem item, Boolean enabled) {
        settings.saveAndFlush(FactorySecrecySetting.builder().factoryId(factoryId).itemCode(item.name())
                .enabled(enabled).updatedAt(LocalDateTime.now()).updatedBy(1L).build());
        secrecy.invalidate(factoryId);
        accessService.invalidate(factoryId);
    }

    private JsonNode costContext(AuthenticatedActor actor, String productTypeId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        entityManager.clear();
        try {
            CostAIContext context = contexts.buildCostContextForCaller(F, productTypeId, 10);
            JsonNode tree = mapper.valueToTree(context);
            scrubber.scrub(tree, actor, F, ProcessSecretReadTracker.reads(),
                    "GET /api/mobile/" + F + "/ai/context/cost-analysis", false);
            return tree;
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private boolean quantitiesHiddenFor(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return costMask.quantitiesHidden(F);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private ArrayNode rows() {
        ArrayNode rows = mapper.createArrayNode();
        ObjectNode f = rows.addObject();
        f.put("factoryId", F);
        f.put("productTypeId", SECRET);
        f.put("conversionRate", 0.8);
        ObjectNode g = rows.addObject();
        g.put("factoryId", G);
        g.put("productTypeId", G_SECRET);
        g.put("conversionRate", 0.7);
        return rows;
    }

    static AuthenticatedActor actor(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static AuthenticatedActor platform() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");
    }

    static ProcessSecretPolicy policy(String factoryId, String productTypeId, ProcessSecretStorageMode mode) {
        return ProcessSecretPolicy.builder().factoryId(factoryId).productTypeId(productTypeId).storageMode(mode)
                .ownerUserId(OWNER).createdBy(OWNER).build();
    }

    static ProductType product(String factoryId, String id, String name, Long createdBy) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setCode(id);
        p.setName(name);
        p.setCategory("finished");
        p.setUnit("kg");
        p.setIsActive(true);
        p.setCreatedBy(createdBy);
        return p;
    }

    static ProductionBatch batch(String factoryId, String number, String productTypeId, String totalCost, String quantity) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(factoryId);
        b.setBatchNumber(number);
        b.setProductTypeId(productTypeId);
        b.setProductName(productTypeId);
        b.setQuantity(new BigDecimal(quantity));
        b.setActualQuantity(new BigDecimal(quantity));
        b.setUnit("kg");
        b.setStatus(ProductionBatchStatus.COMPLETED);
        b.setTotalCost(new BigDecimal(totalCost));
        b.setMaterialCost(new BigDecimal(totalCost).multiply(new BigDecimal("0.6")));
        b.setLaborCost(new BigDecimal(totalCost).multiply(new BigDecimal("0.3")));
        b.setEquipmentCost(new BigDecimal(totalCost).multiply(new BigDecimal("0.1")));
        return b;
    }
}
