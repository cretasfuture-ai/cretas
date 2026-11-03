package com.cretas.aims.service.secrecy;

import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.consent.LearningContentRules;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.consent.TrainingConsentService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阴性对照: 两家工厂的<b>每一项</b>保密配置都关着, B 厂账号读 A 厂数据照样被拒 —— AI 工具身份、入口工厂校验、
 * 共享缓存键、学习到的说法、工厂不明的外发。导出按规则所属工厂查在 ExportExecutorSecrecyNegativeContractTest。
 * (这些闸不读配置, 由 FactorySecrecyGatesContractTest 的源码扫描钉住; 这里是行为上的读数。)
 */
class FactorySecrecyCrossFactoryNegativeContractTest {

    static final String A = "F-NEG-A";
    static final String B = "F-NEG-B";
    static final long B_USER = 77L;

    final List<FactorySecrecySetting> rows = new ArrayList<>();
    FactorySecrecyConfig config;

    @BeforeEach
    void everyItemOffForBothFactories() {
        for (String factory : new String[]{A, B}) {
            for (SecrecyItem item : SecrecyItem.values()) {
                rows.add(FactorySecrecySetting.builder().factoryId(factory).itemCode(item.name()).enabled(false)
                        .updatedBy(1L).build());
            }
        }
        FactorySecrecySettingRepository repository = mock(FactorySecrecySettingRepository.class);
        when(repository.findByFactoryId(anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getFactoryId().equals(inv.getArgument(0))).toList());
        config = new FactorySecrecyConfig(repository, (org.springframework.transaction.PlatformTransactionManager) null);
        for (SecrecyItem item : SecrecyItem.values()) {
            assertThat(config.isOn(A, item)).as("前提: A 的 " + item + " 关着").isFalse();
            assertThat(config.isOn(B, item)).as("前提: B 的 " + item + " 关着").isFalse();
        }
    }

    @Test
    void anAiToolPrincipalOfFactoryBCannotActForFactoryA() {
        User bUser = new User();
        bUser.setId(B_USER);
        bUser.setFactoryId(B);
        bUser.setIsActive(true);
        bUser.setRoleCode("factory_super_admin");
        UserRepository users = mock(UserRepository.class);
        when(users.findById(B_USER)).thenReturn(Optional.of(bUser));
        FactoryRepository factories = mock(FactoryRepository.class);
        Factory a = new Factory();
        a.setId(A);
        a.setIsActive(true);
        when(factories.findById(A)).thenReturn(Optional.of(a));
        ToolPrincipalPolicy policy = new ToolPrincipalPolicy(users, factories, mock(PermissionService.class));

        com.cretas.aims.ai.tool.gateway.ExecutionPrincipal asserted = new com.cretas.aims.ai.tool.gateway.ExecutionPrincipal(
                A, "FACTORY", String.valueOf(B_USER), com.cretas.aims.ai.tool.gateway.PrincipalType.USER,
                Set.of("factory_super_admin"), Set.of(), Set.of());
        assertThat(policy.rehydrate(asserted)).as("B 厂账号自称 A 厂: 工具身份重建失败").isEmpty();
    }

    @Test
    void theEntryFactoryCheckRefusesFactoryBOnFactoryAData() {
        User bUser = new User();
        bUser.setId(B_USER);
        bUser.setFactoryId(B);
        bUser.setRoleCode("factory_super_admin");
        UserRepository users = mock(UserRepository.class);
        when(users.findById(B_USER)).thenReturn(Optional.of(bUser));
        FactoryAccessValidator validator = new FactoryAccessValidator(users);
        assertThatThrownBy(() -> validator.validateAccess(A, B_USER)).hasMessageContaining("无权访问该工厂数据");
        validator.validateAccess(B, B_USER);   // 阳性对照: 本厂照常
    }

    @Test
    void sharedCacheKeysStayPerFactory() throws Exception {
        ProcessSecretAccessService access = new ProcessSecretAccessService(mock(ProcessSecretPolicyRepository.class),
                mock(ProcessSecretGrantRepository.class), mock(ProcessSecretAuditRecorder.class));
        access.setFactorySecrecyConfig(config);
        ProcessSecretLlmCostScope scope = new ProcessSecretLlmCostScope(mock(ProcessSecretAggregateExclusions.class), access);
        String keyExpression = cacheKey();
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver((ctx, name) -> scope);
        context.setVariable("factoryId", A);
        Object keyA = new SpelExpressionParser().parseExpression(keyExpression).getValue(context);
        context.setVariable("factoryId", B);
        Object keyB = new SpelExpressionParser().parseExpression(keyExpression).getValue(context);
        assertThat(scope.accessTier(A)).as("全关: 两家都没有保密档位").isEmpty();
        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(String.valueOf(keyA)).startsWith(A);
    }

    private static String cacheKey() {
        for (java.lang.reflect.Method m : com.cretas.aims.service.report.impl.DashboardStatisticsServiceImpl.class.getDeclaredMethods()) {
            Cacheable c = m.getAnnotation(Cacheable.class);
            if (c != null && c.key().contains("accessTier")) return c.key();
        }
        throw new AssertionError("DashboardStatisticsServiceImpl 上找不到带保密档位的缓存键");
    }

    @Test
    void systemLearnedGlobalExpressionsAreNeverServedAndFactoryLearningNeverWritesGlobal() {
        LearnedExpression global = new LearnedExpression();
        global.setFactoryId(null);
        global.setSourceType(LearnedExpression.SourceType.LLM_FALLBACK);
        assertThat(LearningContentRules.servable(global)).as("系统学来的全局行不给任何工厂").isFalse();
        LearnedExpression ownA = new LearnedExpression();
        ownA.setFactoryId(A);
        ownA.setSourceType(global.getSourceType());
        assertThat(LearningContentRules.servable(ownA)).isTrue();

        TrainingConsentService consent = mock(TrainingConsentService.class);
        when(consent.status(anyString())).thenReturn(TrainingConsentService.Status.AUTHORIZED);
        ProcessSecretSampleFilter filter = new ProcessSecretSampleFilter(mock(ProcessSecretAccessService.class),
                mock(com.cretas.aims.repository.ProductTypeRepository.class));
        filter.setFactorySecrecyConfig(config);
        for (String global2 : new String[]{null, "", "*"}) {
            assertThat(LearningContentRules.factoryLearnable(consent, filter, global2, "查一下今天的产量"))
                    .as("训练过滤全关也不往全局学").isFalse();
        }
    }

    @Test
    void egressWithAnUnknownFactoryIsRefused() {
        ProcessSecretSampleFilter names = mock(ProcessSecretSampleFilter.class);
        when(names.protectedProductNames(anyString())).thenReturn(Set.of());
        LlmEgressGuard guard = new LlmEgressGuard(new ObjectMapper(), names);
        guard.setFactorySecrecyConfig(config);
        assertThat(guard.structured("neg", null, Map.of("customerName", "张记食品有限公司")).refused()).isTrue();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin"))) {
            assertThat(guard.texts("neg", null, "张记食品有限公司 13812345678").refused())
                    .as("平台账号没指定工厂").isTrue();
        }
        // 阳性对照: 工厂已知 (B 自己) 且 c 关 → 原样发
        assertThat(guard.structured("neg", B, Map.of("customerName", "张记食品有限公司")).payload())
                .contains("张记食品有限公司");
    }
}
