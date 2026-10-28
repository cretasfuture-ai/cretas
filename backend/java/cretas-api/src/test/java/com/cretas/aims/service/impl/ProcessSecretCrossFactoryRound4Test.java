package com.cretas.aims.service.impl;

import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.controller.IntentAnalysisController;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.IntentOptimizationSuggestionRepository;
import com.cretas.aims.repository.ParameterExtractionRuleRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.AIIntentConfigHistoryRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.AnnotationQueueRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.consent.TrainingConsentService;
import com.cretas.aims.service.intent.IntentConfigWriteGuard;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第四轮复审 (2026-10-29): 意图启停 / 删除 / 回滚 / 版本历史 / 规则 / 建议 / 标注都只认路径工厂的行;
 * 平台级行只给平台账号且回滚出来的旧版本同样查保密产品名; 自动建意图过本厂学习规则。每组带阴性对照。
 */
class ProcessSecretCrossFactoryRound4Test {

    static final String FA = "FA";
    static final String FB = "FB";
    static final String SECRET = "秘制酱牛肉";

    ProcessSecretSampleFilter filter;
    AIIntentConfigRepository intents;
    IntentConfigWriteGuard guard;

    @BeforeEach
    void setUp() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        when(access.factoriesWithProtectedProducts()).thenReturn(Set.of(FA));
        when(access.protectedProductTypeIds(FA)).thenReturn(Set.of("pA"));
        ProductType p = new ProductType();
        p.setId("pA");
        p.setFactoryId(FA);
        p.setName(SECRET);
        when(products.findAllById(Set.of("pA"))).thenReturn(List.of(p));
        filter = new ProcessSecretSampleFilter(access, products);
        intents = mock(AIIntentConfigRepository.class);
        guard = new IntentConfigWriteGuard(intents, filter);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    static void bindFactoryAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, FA, "factory_super_admin"));
        request.setAttribute("userId", 7L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    static AIIntentConfig row(String factoryId, String keywords) {
        AIIntentConfig c = new AIIntentConfig();
        c.setId(factoryId == null ? "platform-id" : "own-id");
        c.setFactoryId(factoryId);
        c.setIntentCode("PRODUCTION_QUERY");
        c.setKeywords(keywords);
        return c;
    }

    AIIntentConfigController controller(AIIntentService service, IntentConfigRollbackService rollback) {
        AIIntentConfigController c = new AIIntentConfigController(service,
                mock(com.cretas.aims.service.IntentExecutorService.class),
                mock(com.cretas.aims.service.KeywordEffectivenessService.class),
                rollback,
                mock(com.cretas.aims.service.ParameterExtractionLearningService.class),
                mock(com.cretas.aims.utils.JwtUtil.class),
                mock(com.cretas.aims.service.intent.IntentConfigManagementService.class));
        ReflectionTestUtils.setField(c, "intentConfigWriteGuard", guard);
        return c;
    }

    // ---------------------------------------------------------------- H1 启停 / 删除

    @Test
    void enableDisableAndDeleteNeverTouchThePlatformRow() {
        IntentConfigManagementServiceImpl service = new IntentConfigManagementServiceImpl(intents);
        // 本厂没有这一行; 按代码能取到的只有平台级行
        when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row(null, "[]")));
        when(intents.findByIntentCodeAndDeletedAtIsNull("PRODUCTION_QUERY")).thenReturn(Optional.of(row(null, "[]")));

        assertThatThrownBy(() -> service.setIntentActive(FA, "PRODUCTION_QUERY", false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        service.deleteIntent(FA, "PRODUCTION_QUERY");
        verify(intents, never()).save(any());

        // 阴性对照: 本厂的行照常停用 / 删除
        AIIntentConfig own = row(FA, "[]");
        when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(own));
        service.setIntentActive(FA, "PRODUCTION_QUERY", false);
        assertThat(own.getIsActive()).isFalse();
        service.deleteIntent(FA, "PRODUCTION_QUERY");
        assertThat(own.getDeletedAt()).isNotNull();
    }

    @Test
    void controllerActiveAndDeleteUseTheUrlFactory() {
        bindFactoryAdmin();
        AIIntentService service = mock(AIIntentService.class);
        AIIntentConfigController c = controller(service, mock(IntentConfigRollbackService.class));
        AIIntentConfigController.ActiveStatusRequest req = new AIIntentConfigController.ActiveStatusRequest();
        req.setActive(false);

        c.setIntentActive(FA, "PRODUCTION_QUERY", req);
        verify(service).setIntentActive(FA, "PRODUCTION_QUERY", false);

        when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row(null, "[]")));
        assertThatThrownBy(() -> c.deleteIntent(FA, "PRODUCTION_QUERY")).isInstanceOf(BusinessException.class);
        verify(service, never()).deleteIntent(any(), anyString());
        // 工厂账号走 PLATFORM 路径 → 403
        assertThatThrownBy(() -> c.setIntentActive("PLATFORM", "PRODUCTION_QUERY", req))
                .isInstanceOf(BusinessException.class);
        verify(service, never()).setIntentActive(eq(null), anyString(), anyBoolean());
    }

    // ---------------------------------------------------------------- H2 回滚 / 历史

    @Test
    void singleRollbackAndHistoryOnlyForTheOwnRow() {
        bindFactoryAdmin();
        IntentConfigRollbackService rollback = mock(IntentConfigRollbackService.class);
        AIIntentService service = mock(AIIntentService.class);
        AIIntentConfigController c = controller(service, rollback);
        when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row(null, "[]")));
        // 旧写法 getIntentByCode 本厂没有时退回平台级行 —— 按它就会回滚 / 读出平台级行
        when(service.getIntentByCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row(null, "[]")));
        AIIntentConfigController.RollbackRequest req = new AIIntentConfigController.RollbackRequest();

        assertThatThrownBy(() -> c.rollbackIntent(FA, "PRODUCTION_QUERY", req, new MockHttpServletRequest()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> c.getVersionHistory(FA, "PRODUCTION_QUERY")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(rollback);

        // 阴性对照: 本厂的行照常回滚 / 看历史
        when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(row(FA, "[]")));
        c.getVersionHistory(FA, "PRODUCTION_QUERY");
        verify(rollback).getVersionHistory("own-id");
    }

    @Test
    void rollbackAllOnlyTouchesTheFactorysOwnRows() {
        AIIntentConfigHistoryRepository history = mock(AIIntentConfigHistoryRepository.class);
        IntentConfigRollbackService service = new IntentConfigRollbackService(intents, history, new ObjectMapper());
        AIIntentConfig own = row(FA, "[]");
        own.setPreviousSnapshot("{\"description\":\"旧\"}");
        AIIntentConfig platform = row(null, "[]");
        platform.setPreviousSnapshot("{\"description\":\"旧\"}");
        when(intents.findByFactoryIdOrPlatformLevel(FA)).thenReturn(List.of(own, platform));
        when(intents.findById("own-id")).thenReturn(Optional.of(own));
        when(intents.findById("platform-id")).thenReturn(Optional.of(platform));
        when(intents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.rollbackFactoryToLastVersion(FA, 7L, "u", "r");

        // AIIntentConfig 的 equals 只比 BaseEntity 的时间戳 (两行都为空时相等): 按对象身份核对存了哪几行
        org.mockito.ArgumentCaptor<AIIntentConfig> saved = org.mockito.ArgumentCaptor.forClass(AIIntentConfig.class);
        verify(intents, Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(r -> assertThat(r).isSameAs(own));
        assertThat(platform.getDescription()).isNull();          // 平台级行没被恢复成旧版本
        assertThat(result.toString()).doesNotContain("platform-id");
    }

    @Test
    void platformRowRollbackRestoringAProtectedNameIsRefused() {
        IntentConfigRollbackService service = new IntentConfigRollbackService(intents,
                mock(AIIntentConfigHistoryRepository.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "intentConfigWriteGuard", guard);
        AIIntentConfig platform = row(null, "[\"产量\"]");
        platform.setPreviousSnapshot("{\"keywords\":\"[\\\"" + SECRET + "\\\"]\"}");
        when(intents.findById("platform-id")).thenReturn(Optional.of(platform));

        assertThatThrownBy(() -> service.rollbackToLastVersion("platform-id", 1L, "admin", "r"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        verify(intents, never()).save(any());

        // 阴性对照: 旧版本不提保密产品 → 照常回滚
        platform.setKeywords("[\"产量\"]");
        platform.setPreviousSnapshot("{\"keywords\":\"[\\\"出货\\\"]\"}");
        when(intents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.rollbackToLastVersion("platform-id", 1L, "admin", "r").getKeywords()).isEqualTo("[\"出货\"]");
    }

    // ---------------------------------------------------------------- M2 规则

    @Test
    void ruleDeleteAndCleanupAreFactoryScoped() {
        ParameterExtractionRuleRepository rules = mock(ParameterExtractionRuleRepository.class);
        ParameterExtractionLearningServiceImpl service = Mockito.mock(ParameterExtractionLearningServiceImpl.class,
                Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "ruleRepository", rules);
        when(rules.softDelete(eq("r-b"), eq(FA), any())).thenReturn(0);

        assertThatThrownBy(() -> service.deleteRule(FA, "r-b")).isInstanceOf(BusinessException.class);
        service.cleanupLowSuccessRules(FA, 10, 0.3);
        verify(rules).deactivateLowSuccessRules(FA, 10, 0.3);
    }

    // ---------------------------------------------------------------- M3 意图优化建议

    @Test
    void suggestionApplyUsesUrlFactoryAndTokenOperator() {
        bindFactoryAdmin();
        IntentOptimizationSuggestionRepository suggestions = mock(IntentOptimizationSuggestionRepository.class);
        when(suggestions.applySuggestion(anyString(), anyString(), any(), any())).thenReturn(1);
        when(suggestions.rejectSuggestion(anyString(), anyString(), anyString())).thenReturn(1);
        IntentAnalysisController c = new IntentAnalysisController(
                mock(com.cretas.aims.service.ErrorAttributionAnalysisService.class),
                mock(com.cretas.aims.scheduler.ErrorAttributionAnalysisScheduler.class),
                mock(com.cretas.aims.repository.ErrorAttributionStatisticsRepository.class),
                mock(IntentMatchRecordRepository.class), suggestions, intents,
                mock(com.cretas.aims.service.SemanticRouterService.class), filter);

        c.applySuggestion(FA, "s1", 999L);
        verify(suggestions).applySuggestion(eq("s1"), eq(FA), any(), eq(7L));   // 操作人取 token, 不信请求参数
        c.rejectSuggestion(FA, "s1", "不需要");
        verify(suggestions).rejectSuggestion("s1", FA, "不需要");
    }

    // ---------------------------------------------------------------- M4 标注

    @Test
    void annotationCompletionIsFactoryScoped() {
        AnnotationQueueRepository annotations = mock(AnnotationQueueRepository.class);
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "annotationRepository", annotations);

        assertThatThrownBy(() -> service.completeAnnotation(FA, 5L, "I", "u", null))
                .isInstanceOf(BusinessException.class);
        verify(annotations).completeAnnotation(eq(5L), eq(FA), eq("I"), eq("u"), any(), eq(null));
    }

    // ---------------------------------------------------------------- L1 权限查询

    @Test
    void permissionCheckUsesFactoryScopedOverloads() {
        AIIntentService service = mock(AIIntentService.class);
        controller(service, mock(IntentConfigRollbackService.class)).checkPermission(FA, "PRODUCTION_QUERY", "operator");
        verify(service).hasPermission(FA, "PRODUCTION_QUERY", "operator");
        verify(service).requiresApproval(FA, "PRODUCTION_QUERY");
        verify(service).getQuotaCost(FA, "PRODUCTION_QUERY");
        verify(service, never()).hasPermission(anyString(), anyString());
    }

    // ---------------------------------------------------------------- L3 代码全库唯一

    @Test
    void creatingACodeTakenElsewhereGivesAClearConflictWithoutTheOwner() {
        when(intents.findByIntentCode("PRODUCTION_QUERY")).thenReturn(Optional.of(row(FB, "[]")));
        AIIntentConfig body = row(null, "[]");
        body.setId(null);
        assertThatThrownBy(() -> guard.prepareCreate(FA, body, Optional.of(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, 7L, FA, "factory_super_admin"))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(IntentConfigWriteGuard.CODE_TAKEN_MESSAGE)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(FB));
    }

    // ---------------------------------------------------------------- L4 自动建意图

    @Test
    void autoCreatedIntentFromAProtectedUtteranceIsNotCreated() {
        LlmIntentFallbackClientImpl client = Mockito.mock(LlmIntentFallbackClientImpl.class, Mockito.CALLS_REAL_METHODS);
        IntentOptimizationSuggestionRepository suggestions = mock(IntentOptimizationSuggestionRepository.class);
        TrainingConsentService consent = mock(TrainingConsentService.class);
        when(consent.status(anyString())).thenReturn(TrainingConsentService.Status.AUTHORIZED);
        ReflectionTestUtils.setField(client, "suggestionRepository", suggestions);
        ReflectionTestUtils.setField(client, "trainingConsentService", consent);
        ReflectionTestUtils.setField(client, "processSecretSampleFilter", filter);

        client.tryCreateIntentSuggestion(FA, SECRET + "今天做了多少", "SECRET_QUERY", "问保密产品", "r", 0.9);
        verifyNoInteractions(suggestions);

        // 阴性对照: 普通原话照常走建议流程 (查重复建议)
        client.tryCreateIntentSuggestion(FA, "今天做了多少", "OUTPUT_QUERY", "问产量", "r", 0.9);
        verify(suggestions).findPendingCreateIntentSuggestion(FA, "OUTPUT_QUERY");
    }

    // ---------------------------------------------------------------- L5 @PreAuthorize 是否生效

    /**
     * 复审 L5: AIIntentConfigController 写接口上的 {@code @PreAuthorize("hasAnyRole('FACTORY_SUPER_ADMIN', ...)")}
     * 只有开启方法安全 ({@code @EnableMethodSecurity}) 才生效; 本工程没有开启 —— 平台账号走 PLATFORM 路径不被它挡,
     * 由 IntentConfigWriteGuard 判平台账号。若将来开启方法安全, 这些注解会挡住平台账号, 本测试会红, 提醒重新评估。
     */
    @Test
    void methodSecurityIsNotEnabledSoPreAuthorizeDoesNotBlockThePlatformPath() throws IOException {
        Path main = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            List<Path> enabling = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> {
                        try {
                            String s = Files.readString(f);
                            return s.contains("@EnableMethodSecurity") || s.contains("@EnableGlobalMethodSecurity");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).toList();
            assertThat(enabling).as("method security enabling classes").isEmpty();
        }
        // 平台账号走 PLATFORM 路径能写平台级行 (阴性对照: 工厂账号 403 见 Round3)
        assertThat(guard.resolveTarget("PLATFORM", Optional.of(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "super_admin")))).isNull();
    }
}
