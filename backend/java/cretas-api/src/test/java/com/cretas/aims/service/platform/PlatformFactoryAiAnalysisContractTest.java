package com.cretas.aims.service.platform;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.audit.PlatformDataAccessLogWriter;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.aspect.RateLimitAspect;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.DemoReadOnlyInterceptor;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.config.WebMvcConfig;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.controller.AIController;
import com.cretas.aims.controller.BomRecipeController;
import com.cretas.aims.controller.platform.PlatformAiAnalysisGrantController;
import com.cretas.aims.controller.platform.PlatformFactoryAiAnalysisController;
import com.cretas.aims.dto.AIRequestDTO;
import com.cretas.aims.dto.bom.BomSeasoningResponse;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.entity.AIQuotaUsage;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrant;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrantAudit;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.exception.RateLimitExceededException;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.AIAuditLogRepository;
import com.cretas.aims.repository.AIQuotaUsageRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.config.AIQuotaConfigRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.repository.platform.PlatformAiAnalysisGrantAuditRepository;
import com.cretas.aims.repository.platform.PlatformAiAnalysisGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.security.ProcessSecretResponseAdvice;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.bom.BomSeasoningWorkspaceService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import com.cretas.aims.service.impl.ProcessingServiceImpl;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.cretas.aims.service.report.impl.ProductionReportServiceImpl;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.groups.Tuple;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台 AI 分析入口 (Steve 2026-11-04) 走真实链路: {@link WebMvcConfig} 真实登记的拦截器 → 真实 controller →
 * 真实 service → 真实 {@link AIEnterpriseService} / {@link AIAnalysisService} / {@link LlmEgressGuard} →
 * 真实 {@link PythonSmartBIClient} 发到 MockWebServer。配置经真实的 {@link FactorySecrecyConfig} (仓库是内存里的行);
 * 批次行由真实的 {@link ProcessingServiceImpl#getTimeRangeBatchesCostAnalysis} 从实体拼出来, 不手写行的形状。
 * 被替换的只有最外面的仓库、留痕落库 ({@link ProcessSecretAuditRecorder}) 和模型本身。
 *
 * <p>同一个平台账号走既有的配方 / 成本分析接口 ({@code existing}) 用的是真实的 {@link BomRecipeController} 和
 * {@link AIController}, 用来证明入口的放行没有外溢。
 */
class PlatformFactoryAiAnalysisContractTest {

    static final String F1 = "F-PLAT-1";
    static final String F2 = "F-PLAT-2";
    static final long ADMIN = 7L;
    static final long STAFF = 8L;
    static final String ROUTE = "/ai-analysis/cost/time-range";
    static final String GRANTS = "/api/platform/ai-analysis/grants";
    static final String SUPPLIER = "张记食品有限公司";
    static final String PHONE = "13812345678";
    static final String QUESTION = "请问" + SUPPLIER + "的来料为什么贵 联系 " + PHONE;
    static final String ANSWER = "成本结构正常，没有明显异常。";
    static final String PLAIN_PRODUCT = "普通牛肉片";
    static final String SECRET_PRODUCT = "秘制酱牛肉";
    static final String HELD_PRODUCT = "客户保管酱料";
    static final String SECRET_RECIPE_LINE = "秘方香料";
    /** 批次放在这一天; 请求的时间段包住它, 且都在最近 30 天里。 */
    static final LocalDate RANGE_START = LocalDate.now().minusDays(20);
    static final LocalDate RANGE_END = LocalDate.now().minusDays(10);
    static final LocalDateTime IN_RANGE = LocalDate.now().minusDays(15).atTime(8, 0);
    static final LocalDateTime RECENT_ONLY = LocalDate.now().minusDays(3).atTime(8, 0);
    /** 98 kg 总成本 7777 → 单位成本 79.3571: 这个读数只可能来自 30 天统计的实际成本列。 */
    static final String SECRET_UNIT_COST = "¥79.3571";
    static final String HELD_UNIT_COST = "¥56.6837";
    static final String SECRET_ROW_WITHHELD = "| " + "秘制酱牛肉" + " | 98 kg | 保密 | 保密 |";
    static final String HELD_ROW_WITHHELD = "| " + "客户保管酱料" + " | 98 kg | 保密 | 保密 |";
    static final String HELD_RANGE_NOTICE = "说明：该时间段内 1 个客户侧保管产品的批次无法读取（核心工艺不在云端），这些批次和同计划的批次未计入。";
    static final String HELD_CONTEXT_NOTICE = contextNotice(1);
    static final AuthenticatedActor PLATFORM_ACTOR =
            new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, ADMIN, "PLATFORM", "super_admin");

    record AuditRow(String factoryId, String productTypeId, AuthenticatedActor actor, ProcessSecretAction action,
                    String decision, String reason, String resourceRef) { }

    final List<FactorySecrecySetting> settingRows = new ArrayList<>();
    final List<ProcessSecretPolicy> policies = new ArrayList<>();
    final List<AuditRow> audit = new ArrayList<>();
    final Map<String, Factory> factoryRows = new HashMap<>();
    final Map<Long, PlatformAdmin> adminRows = new HashMap<>();
    final Map<String, List<ProductionBatch>> batchRows = new HashMap<>();
    final Set<Long> brokenBatches = new java.util.HashSet<>();
    final Map<String, ProductType> productTypes = new HashMap<>();
    final Map<String, PlatformAiAnalysisGrant> grantRows = new LinkedHashMap<>();
    final List<PlatformAiAnalysisGrantAudit> grantAudit = new ArrayList<>();

    final FactorySecrecySettingRepository settings = mock(FactorySecrecySettingRepository.class);
    final FactoryRepository factories = mock(FactoryRepository.class);
    final PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final ProcessSecretPolicyRepository policyRepository = mock(ProcessSecretPolicyRepository.class);
    final ProcessSecretAuditRecorder recorder = mock(ProcessSecretAuditRecorder.class);
    final ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
    final PythonLLMClient pythonLlm = mock(PythonLLMClient.class);
    final AIAnalysisResultRepository results = mock(AIAnalysisResultRepository.class);
    final AIQuotaUsageRepository quotaUsage = mock(AIQuotaUsageRepository.class);
    final AIQuotaConfigRepository quotaConfig = mock(AIQuotaConfigRepository.class);
    final AIAuditLogRepository aiAuditLog = mock(AIAuditLogRepository.class);
    final PlatformDataAccessLogWriter accessLog = mock(PlatformDataAccessLogWriter.class);
    final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    final PlatformAiAnalysisGrantRepository grantRepository = mock(PlatformAiAnalysisGrantRepository.class);
    final PlatformAiAnalysisGrantAuditRepository grantAuditRepository = mock(PlatformAiAnalysisGrantAuditRepository.class);
    final BomRecipeRepository recipeRepository = mock(BomRecipeRepository.class);
    final BomRecipeService recipeService = mock(BomRecipeService.class);

    FactorySecrecyConfig config;
    ProcessSecretAccessService access;
    ProcessSecretLlmCostScope costScope;
    LlmEgressGuard guard;
    AIAnalysisService basicAi;
    ProcessingServiceImpl processing;
    AIEnterpriseService enterprise;
    AIContextServiceImpl aiContext;
    ProductionBatchRepository batches;
    PythonSmartBIClient python;
    MockWebServer server;
    JwtUtil jwt;
    JwtAuthInterceptor jwtInterceptor;
    PlatformDataAccessLogInterceptor accessLogInterceptor;
    PlatformAdmin admin;
    MockMvc mvc;
    MockMvc existing;
    PlatformFactoryAiAnalysisService platformService;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        factoryRows.put(F1, factory(F1, true));
        factoryRows.put(F2, factory(F2, true));
        when(factories.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(factoryRows.get(inv.<String>getArgument(0))));
        when(factories.existsById(anyString())).thenAnswer(inv -> factoryRows.containsKey(inv.<String>getArgument(0)));
        when(settings.findByFactoryId(anyString())).thenAnswer(inv -> settingRows.stream()
                .filter(r -> r.getFactoryId().equals(inv.getArgument(0))).toList());
        config = new FactorySecrecyConfig(settings, factories, (PlatformTransactionManager) null);

        admin = PlatformAdmin.builder().id(ADMIN).platformRole(PlatformRole.super_admin).status(Status.active).build();
        adminRows.put(ADMIN, admin);
        adminRows.put(STAFF, PlatformAdmin.builder().id(STAFF).platformRole(PlatformRole.operation_admin).status(Status.active).build());
        when(admins.findById(any())).thenAnswer(inv -> Optional.ofNullable(adminRows.get(inv.<Long>getArgument(0))));

        access = accessService();
        costScope = new ProcessSecretLlmCostScope(new ProcessSecretAggregateExclusions(access,
                planRepository, mock(BomRecipeRepository.class)), access);
        ProcessSecretAiAccessAudit aiAudit = new ProcessSecretAiAccessAudit(access, costScope);
        aiAudit.setFactorySecrecyConfig(config);

        when(productTypeRepository.findAllById(any())).thenAnswer(inv -> {
            List<ProductType> found = new ArrayList<>();
            inv.<Iterable<String>>getArgument(0).forEach(id -> {
                if (productTypes.containsKey(id)) found.add(productTypes.get(id));
            });
            return found;
        });
        ProcessSecretSampleFilter names = new ProcessSecretSampleFilter(access, productTypeRepository,
                mock(FactoryNameDirectoryRepository.class));
        guard = new LlmEgressGuard(new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS), names);
        guard.setFactorySecrecyConfig(config);

        python = spy(pythonClient());
        basicAi = new AIAnalysisService();
        ReflectionTestUtils.setField(basicAi, "pythonSmartBIClient", python);
        ReflectionTestUtils.setField(basicAi, "pythonLLMClient", pythonLlm);
        ReflectionTestUtils.setField(basicAi, "llmEgressGuard", guard);
        ReflectionTestUtils.setField(basicAi, "llmCostScope", costScope);
        ReflectionTestUtils.setField(basicAi, "aiAccessAudit", aiAudit);

        processing = realProcessingService();
        aiContext = new AIContextServiceImpl(mock(com.cretas.aims.service.BomService.class), realProductionReport(), batches,
                productTypeRepository, costScope);
        ReflectionTestUtils.setField(aiContext, "aiAccessAudit", aiAudit);
        ReflectionTestUtils.setField(basicAi, "aiContextService", aiContext);
        enterprise = new AIEnterpriseService();
        ReflectionTestUtils.setField(enterprise, "basicAIService", basicAi);
        ReflectionTestUtils.setField(enterprise, "processingService", processing);
        ReflectionTestUtils.setField(enterprise, "analysisResultRepository", results);
        ReflectionTestUtils.setField(enterprise, "quotaUsageRepository", quotaUsage);
        ReflectionTestUtils.setField(enterprise, "quotaConfigRepository", quotaConfig);
        ReflectionTestUtils.setField(enterprise, "auditLogRepository", aiAuditLog);
        ReflectionTestUtils.setField(enterprise, "llmCostScope", costScope);
        ReflectionTestUtils.setField(enterprise, "aiAccessAudit", aiAudit);
        ReflectionTestUtils.setField(enterprise, "llmEgressGuard", guard);

        PlatformFactoryControlAuthority authority = new PlatformFactoryControlAuthority(factories, admins,
                mock(FactoryFeatureMenuWriteLock.class));
        wireGrantRepositories();
        PlatformAiAnalysisGrantService grantService = new PlatformAiAnalysisGrantService(grantRepository,
                grantAuditRepository, admins, authority);
        platformService = new PlatformFactoryAiAnalysisService(grantService, authority,
                factories, access, enterprise);

        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        jwtInterceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(jwtInterceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(jwtInterceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        ReflectionTestUtils.setField(jwtInterceptor, "webLoginSessions", mock(WebLoginSessionService.class));
        ReflectionTestUtils.setField(jwtInterceptor, "factoryRepository", factories);
        ReflectionTestUtils.setField(jwtInterceptor, "userRepository", users);
        accessLogInterceptor = new PlatformDataAccessLogInterceptor(accessLog);

        ObjectMapper webMapper = Jackson2ObjectMapperBuilder.json().build();
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipeRepository,
                mock(ProductionPlanRepository.class),
                ProcessSecretTestHooks.scrubber(access, recipeRepository,
                        mock(ProductionWorkflowInstanceRepository.class), mock(ProductionPlanRepository.class)),
                webMapper);
        mvc = MockMvcBuilders.standaloneSetup(new PlatformFactoryAiAnalysisController(platformService),
                        new PlatformAiAnalysisGrantController(grantService))
                .addInterceptors(registeredFor(webMapper).toArray(new HandlerInterceptor[0]))
                .setControllerAdvice(new GlobalExceptionHandler(), advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(webMapper))
                .build();
        existing = existingEndpoints(advice, webMapper);

        addBatch(F1, 11L, "P-PLAIN", PLAIN_PRODUCT);
        addBatch(F2, 21L, "P-OTHER", "别厂的产品");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ 放行

    @Test
    void aSuperAdminNeedsNoGrantAndTheUseIsAuditedBeforeAnyDataOrTheModel() throws Exception {
        answerWithAnalysis();

        analyse(F1, superAdminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.analysis").value(ANSWER))
                .andExpect(jsonPath("$.data.notice").doesNotExist())
                .andExpect(jsonPath("$.data.quotaConsumed").value(0));

        assertThat(audit).hasSize(1);
        AuditRow row = audit.get(0);
        assertThat(row.factoryId()).isEqualTo(F1);
        assertThat(row.productTypeId()).isNull();
        assertThat(row.actor()).isEqualTo(PLATFORM_ACTOR);
        assertThat(row.action()).isEqualTo(ProcessSecretAction.AI_ACCESS);
        assertThat(row.decision()).isEqualTo("ALLOW");
        assertThat(row.resourceRef()).isEqualTo("ai:platform-analysis");
        assertThat(row.reason()).contains(RANGE_START.toString(), RANGE_END.toString(), "最近 30 天")
                .doesNotContain("来料", PHONE, SUPPLIER);

        InOrder order = inOrder(recorder, batches, python);
        order.verify(recorder).record(eq(F1), any(), any(), eq(ProcessSecretAction.AI_ACCESS), eq("ALLOW"), anyString(),
                eq("ai:platform-analysis"));
        order.verify(batches, atLeastOnce()).findByFactoryIdAndCreatedAtBetween(eq(F1), any(), any());
        order.verify(python).analyzeGeneralWithoutCapture(eq(F1), any(PythonGeneralAnalysisRequest.class));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void platformAnalysisControllerRateLimitUsesRealAspectPerAccountAndDoesNotConsumeFactoryQuota() throws Exception {
        grantRows.put("ROLE|operation_admin", PlatformAiAnalysisGrant.builder()
                .granteeType("ROLE").granteeRef("operation_admin").grantedBy(ADMIN)
                .grantedAt(LocalDateTime.now()).reason("限流隔离测试").build());

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(
                new PlatformFactoryAiAnalysisController(platformService));
        proxyFactory.addAspect(new RateLimitAspect());
        PlatformFactoryAiAnalysisController proxy = proxyFactory.getProxy();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", ADMIN);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, PLATFORM_ACTOR);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AIRequestDTO.TimeRangeAnalysisRequest body = new AIRequestDTO.TimeRangeAnalysisRequest();
        body.setStartDate(RANGE_START);
        body.setEndDate(RANGE_END);
        body.setDimension("overall");
        body.setQuestion(QUESTION);

        for (int i = 0; i < 6; i++) {
            answerWithAnalysis();
            assertThat(proxy.analyzeTimeRange(F1, body, request).getSuccess()).isTrue();
        }
        assertThatThrownBy(() -> proxy.analyzeTimeRange(F1, body, request))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(server.getRequestCount()).isEqualTo(6);

        request.setAttribute("userId", STAFF);
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, STAFF, "PLATFORM", "operation_admin"));
        answerWithAnalysis();
        assertThat(proxy.analyzeTimeRange(F1, body, request).getSuccess()).isTrue();
        verifyNoInteractions(quotaUsage, quotaConfig);
    }

    /** 裁定: 入口不读 a / b。四种组合都能用, a 开时保密产品都进提示词 (b 开关不改变结果)。 */
    @Test
    void everyCombinationOfProcessSecretAndTheBlockIsAllowed() throws Exception {
        addSecretProduct();
        for (Boolean processSecret : List.of(false, true)) {
            for (Boolean block : List.of(false, true)) {
                set(F1, SecrecyItem.PROCESS_SECRET, processSecret);
                set(F1, SecrecyItem.PLATFORM_RECIPE_BLOCK, block);
                answerWithAnalysis();
                analyse(F1, superAdminToken()).andExpect(status().isOk());
                assertThat(sentBody()).as("a=" + processSecret + " b=" + block).contains(SECRET_PRODUCT, PLAIN_PRODUCT);
            }
        }
    }

    /** 读不到配置时保密项都按开启: 入口照常可用, 只是发给模型的内容照常脱敏。 */
    @Test
    void aFailedConfigReadDoesNotCloseTheEntryAndStillRedacts() throws Exception {
        when(settings.findByFactoryId(anyString())).thenThrow(new QueryTimeoutException("slow"));
        config.invalidate(F1);
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        assertThat(sentBody()).contains(PLAIN_PRODUCT).doesNotContain(PHONE, SUPPLIER);
    }

    /** 真实入口上的既有平台访问留痕 (按路径工厂) 也要照常落一条, 且不含问题原文。 */
    @Test
    void theExistingPlatformAccessTrailRecordsThePathFactoryAndNoQuestionText() throws Exception {
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        ArgumentCaptor<PlatformDataAccessLog> row = ArgumentCaptor.forClass(PlatformDataAccessLog.class);
        verify(accessLog).submit(row.capture());
        assertThat(row.getValue().getTargetFactoryId()).isEqualTo(F1);
        assertThat(row.getValue().getActorUserId()).isEqualTo(ADMIN);
        assertThat(String.valueOf(row.getValue())).doesNotContain(PHONE);
    }

    // ------------------------------------------------------------------ 保密产品: 读得到, 且每个都留痕

    @Test
    void withProcessSecretAndTheBlockOnTheProtectedProductIsReadAndRecordedBeforeTheModel() throws Exception {
        protectEverything();
        addSecretProduct();
        answerWithAnalysis();

        analyse(F1, superAdminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysis").value(ANSWER))
                .andExpect(jsonPath("$.data.notice").doesNotExist())
                // 响应裁剪不许把这次的读数当成「平台读了受限产品」: 数字字段留着, 也没有 DENY
                .andExpect(jsonPath("$.data.quotaConsumed").value(0))
                .andExpect(jsonPath("$.data.processingTimeMs").isNumber());

        assertThat(sentBody()).contains(SECRET_PRODUCT, PLAIN_PRODUCT, SECRET_UNIT_COST).doesNotContain(SECRET_ROW_WITHHELD);
        assertThat(audit.get(0).productTypeId()).as("入口那一条排在最前").isNull();
        assertThat(audit).filteredOn(r -> "ai:platform-analysis".equals(r.resourceRef()))
                .extracting(AuditRow::productTypeId, AuditRow::action, AuditRow::decision)
                .containsExactly(Tuple.tuple(null, ProcessSecretAction.AI_ACCESS, "ALLOW"),
                        Tuple.tuple("P-SECRET", ProcessSecretAction.AI_ACCESS, "ALLOW"));
        // f 跟随 a: 成本分析汇合点自己那一条照写
        assertThat(audit).extracting(AuditRow::resourceRef).contains("ai:cost-analysis");
        assertThat(audit).filteredOn(r -> r.action() == ProcessSecretAction.VIEW_FULL).isEmpty();
        assertThat(audit).allSatisfy(r -> assertThat(r.reason() + r.resourceRef()).doesNotContain(PHONE, "来料"));

        // 保密产品的记录排在读批次之前: 名单来自保密策略, 不依赖批次
        InOrder order = inOrder(recorder, batches, python);
        order.verify(recorder).record(eq(F1), eq("P-SECRET"), any(), eq(ProcessSecretAction.AI_ACCESS), eq("ALLOW"),
                anyString(), eq("ai:platform-analysis"));
        order.verify(batches, atLeastOnce()).findByFactoryIdAndCreatedAtBetween(eq(F1), any(), any());
        order.verify(python).analyzeGeneralWithoutCapture(eq(F1), any(PythonGeneralAnalysisRequest.class));
    }

    @Test
    void aFailedRecordOfAProtectedProductStopsTheAnalysisBeforeTheModel() throws Exception {
        protectEverything();
        addSecretProduct();
        doThrow(new DataAccessResourceFailureException("down")).when(recorder).record(eq(F1), eq("P-SECRET"), any(), any(),
                any(), any(), any());

        analyse(F1, superAdminToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("保密访问记录暂时写不进去，已暂停平台 AI 分析"));
        assertNoDataAndNoModel();
    }

    /** B 档 (e 开) 云端没有完整工艺: 该行不进提示词, 其余照常分析, 响应里明说读不到; 请求里 Hibernate 读过它也不影响回答。 */
    @Test
    void customerHeldProductsCannotBeReadAndTheResponseSaysSo() throws Exception {
        protectEverything();
        set(F1, SecrecyItem.CUSTOMER_HELD, true);
        addPolicy(F1, "P-HELD", HELD_PRODUCT, ProcessSecretStorageMode.CUSTOMER_HELD);
        addBatch(F1, 13L, "P-HELD", HELD_PRODUCT).setTotalCost(new BigDecimal("5555"));
        addSecretProduct();
        answerWithAnalysis();

        analyse(F1, superAdminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysis").value(ANSWER))
                .andExpect(jsonPath("$.data.notice").value(HELD_RANGE_NOTICE + HELD_CONTEXT_NOTICE));

        // 名称和产量在 30 天统计里还会发出 (说明也这么写), 成本不发
        assertThat(sentBody()).contains(PLAIN_PRODUCT, SECRET_PRODUCT, SECRET_UNIT_COST, HELD_RANGE_NOTICE, HELD_ROW_WITHHELD)
                .doesNotContain(HELD_UNIT_COST);
        assertThat(audit).extracting(AuditRow::productTypeId).doesNotContain("P-HELD");
    }

    @Test
    void onlyCustomerHeldBatchesInTheRangeIsRefusedWithoutCallingTheModel() throws Exception {
        set(F1, SecrecyItem.CUSTOMER_HELD, true);
        batchRows.get(F1).clear();
        addPolicy(F1, "P-HELD", HELD_PRODUCT, ProcessSecretStorageMode.CUSTOMER_HELD);
        addBatch(F1, 13L, "P-HELD", HELD_PRODUCT);

        analyse(F1, superAdminToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("该时间段内的批次都是客户侧保管产品，核心工艺不在云端，无法读取，未发给 AI 分析"));
        assertNoModel();
        assertThat(audit).extracting(AuditRow::resourceRef).containsExactly("ai:platform-analysis");
    }

    /** 时间段里没有 B 档批次, 但最近 30 天有: 30 天统计扣掉了它的成本, 说明也要有, 且只说这一节。 */
    @Test
    void aCustomerHeldProductThatOnlyAppearsInTheLastThirtyDaysStillGetsANotice() throws Exception {
        set(F1, SecrecyItem.CUSTOMER_HELD, true);
        addPolicy(F1, "P-HELD", HELD_PRODUCT, ProcessSecretStorageMode.CUSTOMER_HELD);
        ProductionBatch recent = addBatch(F1, 13L, "P-HELD", HELD_PRODUCT);
        recent.setCreatedAt(RECENT_ONLY);
        recent.setTotalCost(new BigDecimal("5555"));
        answerWithAnalysis();

        analyse(F1, superAdminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notice").value(HELD_CONTEXT_NOTICE));

        assertThat(sentBody()).contains(PLAIN_PRODUCT, HELD_ROW_WITHHELD).doesNotContain(HELD_UNIT_COST);
    }

    /** 同计划的普通联产品成本也被隐藏, 但它不是保密产品: 说明的数字要把它算上, 不能只报 1。 */
    @Test
    void theThirtyDayNoticeCountsAPlainCoProductWithheldByItsPlan() throws Exception {
        set(F1, SecrecyItem.CUSTOMER_HELD, true);
        addPolicy(F1, "P-HELD", HELD_PRODUCT, ProcessSecretStorageMode.CUSTOMER_HELD);
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-HELD");
        plan.setProductTypeId("P-HELD");
        when(planRepository.findByFactoryId(F1)).thenReturn(List.of(plan));
        for (ProductionBatch batch : List.of(addBatch(F1, 13L, "P-HELD", HELD_PRODUCT), addBatch(F1, 14L, "P-CO", "同计划联产品"))) {
            batch.setCreatedAt(RECENT_ONLY);
            batch.setProductionPlanId("PLAN-HELD");
            batch.setTotalCost(new BigDecimal("5555"));
        }
        answerWithAnalysis();

        analyse(F1, superAdminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notice").value(contextNotice(2)));

        assertThat(sentBody()).contains(HELD_ROW_WITHHELD, "| 同计划联产品 | 98 kg | 保密 | 保密 |").doesNotContain(HELD_UNIT_COST);
    }

    /** 认不出产品的行有隐藏范围时成本照样不给, 背后却数不出保密产品: 按保密产品数报会是 0, 说明整句消失。 */
    @Test
    void theThirtyDayNoticeCountsARowWhoseProductIsUnknown() throws Exception {
        set(F1, SecrecyItem.CUSTOMER_HELD, true);
        addPolicy(F1, "P-HELD", HELD_PRODUCT, ProcessSecretStorageMode.CUSTOMER_HELD);
        ProductionBatch unknown = addBatch(F1, 14L, null, "认不出的产品");
        unknown.setCreatedAt(RECENT_ONLY);
        unknown.setTotalCost(new BigDecimal("5555"));
        answerWithAnalysis();

        analyse(F1, superAdminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notice").value(contextNotice(1)));

        assertThat(sentBody()).contains("| 认不出的产品 | 98 kg | 保密 | 保密 |").doesNotContain(HELD_UNIT_COST);
    }

    /** 30 天统计 (提示词里最大的数据来源) 在作用域里给实际成本, 在作用域外同一个调用照旧是保密。 */
    @Test
    void theThirtyDayContextShowsProtectedCostsOnlyInsideTheScope() throws Exception {
        protectEverything();
        addSecretProduct();
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        assertThat(sentBody()).as("入口").contains(SECRET_UNIT_COST).doesNotContain(SECRET_ROW_WITHHELD);

        String outside;
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(PLATFORM_ACTOR)) {
            outside = aiContext.formatProductionContextForPrompt(aiContext.buildProductionContextForAi(
                    F1, LocalDate.now().minusDays(30), LocalDate.now()));
        }
        assertThat(outside).as("作用域外").contains(SECRET_ROW_WITHHELD).doesNotContain(SECRET_UNIT_COST);
    }

    // ------------------------------------------------------------------ 数据不全要说

    @Test
    void moreThanFiftyBatchesIsToldToTheCallerAndTheModel() throws Exception {
        for (long id = 100; id < 151; id++) addBatch(F1, id, "P-PLAIN", PLAIN_PRODUCT);
        answerWithAnalysis();
        String notice = "说明：该时间段共 52 个批次，超过上限，只取了其中 50 个。";
        analyse(F1, superAdminToken()).andExpect(status().isOk()).andExpect(jsonPath("$.data.notice").value(notice));
        assertThat(sentBody()).contains(notice);
    }

    @Test
    void aBatchThatFailedToLoadIsToldToTheCallerAndTheModel() throws Exception {
        addBatch(F1, 12L, "P-PLAIN", PLAIN_PRODUCT);
        brokenBatches.add(12L);
        answerWithAnalysis();
        String notice = "说明：有 1 个批次取成本数据失败，未计入。";
        analyse(F1, superAdminToken()).andExpect(status().isOk()).andExpect(jsonPath("$.data.notice").value(notice));
        assertThat(sentBody()).contains(notice);
    }

    @Test
    void everyBatchFailingToLoadIsAClearFailureNotNoData() throws Exception {
        brokenBatches.add(11L);
        analyse(F1, superAdminToken()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("该时间段内的批次成本数据都没有取到，未发给 AI 分析"));
        assertNoModel();
    }

    /** 取数改成带计数之后, 老方法给别的调用方的结果不变: 仍是截断到 50、失败的丢掉。 */
    @Test
    void theSharedFetchKeepsItsBehaviourForOtherCallers() {
        for (long id = 100; id < 151; id++) addBatch(F1, id, "P-PLAIN", PLAIN_PRODUCT);
        brokenBatches.add(11L);
        LocalDateTime from = RANGE_START.atStartOfDay();
        LocalDateTime to = RANGE_END.atTime(23, 59, 59);
        assertThat(processing.getTimeRangeBatchesCostAnalysis(F1, from, to)).hasSize(49);
        var counted = processing.getTimeRangeBatchesWithCounts(F1, from, to);
        assertThat(counted.rows()).hasSize(49);
        assertThat(counted.found()).isEqualTo(52);
        assertThat(counted.overLimit()).isEqualTo(2);
        assertThat(counted.failed()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Python 侧不留存

    /** 只有这个入口带不留存信号: 老成本分析照旧不带, Python 的默认采集不变。 */
    @Test
    void onlyTheEntrySendsTheNoCaptureSignal() throws Exception {
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader("X-No-Training-Capture")).isEqualTo("1");

        answerWithAnalysis();
        existing.perform(post("/api/mobile/" + F1 + "/ai/analysis/cost/time-range")
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isOk());
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeader("X-No-Training-Capture")).isNull();
    }

    // ------------------------------------------------------------------ 放行只属于这个入口

    @Test
    void afterTheEntryTheSameAccountOnTheSameThreadStaysBlockedEverywhereElse() throws Exception {
        protectEverything();
        addSecretProduct();
        assertExistingEndpointsBlockThePlatform();

        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        assertThat(sentBody()).as("阳性对照: 入口确实读到了").contains(SECRET_PRODUCT);

        assertExistingEndpointsBlockThePlatform();
    }

    @Test
    void whenTheAnalysisFailsTheAllowanceIsGoneOnTheSameThread() throws Exception {
        protectEverything();
        addSecretProduct();
        server.enqueue(new MockResponse().setResponseCode(500));
        analyse(F1, superAdminToken()).andExpect(status().isServiceUnavailable());
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).as("确实走到了模型调用").isNotNull();
        assertExistingEndpointsBlockThePlatform();
    }

    @Test
    void whenTheAnalysisThrowsTheAllowanceIsGoneOnTheSameThread() throws Exception {
        protectEverything();
        addSecretProduct();
        ProcessSecretLlmCostScope spied = spy(costScope);
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new IllegalStateException("boom");
        }).when(spied).withoutProtectedBatches(anyString(), any());
        ReflectionTestUtils.setField(enterprise, "llmCostScope", spied);

        analyse(F1, superAdminToken()).andExpect(jsonPath("$.success").value(false));
        verify(spied).withoutProtectedBatches(eq(F1), any());

        ReflectionTestUtils.setField(enterprise, "llmCostScope", costScope);
        assertExistingEndpointsBlockThePlatform();
    }

    /** 分析进行中: 判定点不变, 放行只对这个线程、这个账号、这家工厂成立。 */
    @Test
    void whileTheAnalysisRunsNothingElseSeesTheAllowance() throws Exception {
        protectEverything();
        addSecretProduct();
        addPolicy(F2, "P-SECRET", SECRET_PRODUCT, ProcessSecretStorageMode.CLOUD_RESTRICTED);
        set(F2, SecrecyItem.PROCESS_SECRET, true);
        set(F2, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
        AuthenticatedActor otherAdmin = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, STAFF, "PLATFORM", "super_admin");
        AuthenticatedActor factoryUser = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, ADMIN, F1, "factory_super_admin");
        Map<String, Object> seen = new HashMap<>();
        doAnswer(inv -> {
            seen.put("self", access.permitsPlatformAiRead(PLATFORM_ACTOR, F1, "P-SECRET"));
            seen.put("decide", access.decide(PLATFORM_ACTOR, F1, "P-SECRET", ProcessSecretAction.VIEW_FULL).outcome());
            seen.put("viewFull", access.permitsViewFull(PLATFORM_ACTOR, F1, "P-SECRET", "probe"));
            seen.put("hidden", access.hiddenFromPlatform(F1));
            seen.put("otherAdmin", access.permitsPlatformAiRead(otherAdmin, F1, "P-SECRET"));
            seen.put("factoryUser", access.permitsPlatformAiRead(factoryUser, F1, "P-SECRET"));
            seen.put("otherFactory", access.permitsPlatformAiRead(PLATFORM_ACTOR, F2, "P-SECRET"));
            // 这个线程在作用域打开时才创建: 作用域若被子线程继承, 这里就会看到放行
            CompletableFuture<Boolean> other = new CompletableFuture<>();
            Thread thread = new Thread(() -> other.complete(access.permitsPlatformAiRead(PLATFORM_ACTOR, F1, "P-SECRET")));
            thread.start();
            seen.put("otherThread", other.get(5, TimeUnit.SECONDS));
            return inv.callRealMethod();
        }).when(python).analyzeGeneralWithoutCapture(anyString(), any(PythonGeneralAnalysisRequest.class));
        answerWithAnalysis();

        analyse(F1, superAdminToken()).andExpect(status().isOk());

        assertThat(seen).containsEntry("self", true)
                .containsEntry("decide", ProcessSecretAccessService.Outcome.DENY)
                .containsEntry("viewFull", false)
                .containsEntry("otherAdmin", false)
                .containsEntry("factoryUser", false)
                .containsEntry("otherFactory", false)
                .containsEntry("otherThread", false);
        assertThat(String.valueOf(seen.get("hidden"))).contains("P-SECRET");
    }

    /** 作用域只有这三个文件认识, 读取判定只有成本裁剪一处调用: 新增一个入口或调用点就红。 */
    @Test
    void onlyTheEntryOpensTheScopeAndOnlyTheCostScopeAsksForTheRead() throws IOException {
        assertThat(mainFilesMentioning("ProcessSecretPlatformAiScope")).containsExactly(
                "service/platform/PlatformFactoryAiAnalysisService.java",
                "service/processsecret/ProcessSecretAccessService.java",
                "service/processsecret/ProcessSecretPlatformAiScope.java");
        assertThat(mainFilesMentioning("permitsPlatformAiRead")).containsExactly(
                "service/processsecret/ProcessSecretAccessService.java",
                "service/processsecret/ProcessSecretLlmCostScope.java");
    }

    /**
     * 这几个方法绕开工厂配额和训练采集, 只该从入口走到: 文件清单挡新文件里的调用, 次数挡同一个文件里多出来的一处。
     * 第三个是上一层: 只钉下面两个的话, 从别处调它一样绕开入口的授权和留痕。
     */
    @Test
    void thePathsThatSkipQuotaAndCaptureEachHaveOneCaller() throws IOException {
        assertThat(mainFilesMentioning("analyzeCostForPlatformEntry")).containsExactly(
                "service/AIAnalysisService.java",
                "service/AIEnterpriseService.java");
        assertThat(mainFilesMentioning("analyzeGeneralWithoutCapture")).containsExactly(
                "client/PythonSmartBIClient.java",
                "service/AIAnalysisService.java");
        assertThat(mainFilesMentioning("analyzeTimeRangeForPlatform")).containsExactly(
                "service/AIEnterpriseService.java",
                "service/platform/PlatformFactoryAiAnalysisService.java");
        assertThat(mentionsIn("service/AIAnalysisService.java", "analyzeCostForPlatformEntry")).as("定义").isEqualTo(1);
        assertThat(mentionsIn("service/AIEnterpriseService.java", "analyzeCostForPlatformEntry")).as("调用").isEqualTo(1);
        assertThat(mentionsIn("client/PythonSmartBIClient.java", "analyzeGeneralWithoutCapture")).as("定义").isEqualTo(1);
        assertThat(mentionsIn("service/AIAnalysisService.java", "analyzeGeneralWithoutCapture")).as("调用").isEqualTo(1);
        assertThat(mentionsIn("service/AIEnterpriseService.java", "analyzeTimeRangeForPlatform")).as("定义").isEqualTo(1);
        assertThat(mentionsIn("service/platform/PlatformFactoryAiAnalysisService.java", "analyzeTimeRangeForPlatform"))
                .as("调用").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 授权

    @Test
    void aGrantedRoleCanUseTheEntryAndAnUngrantedRoleCannot() throws Exception {
        String auditor = jwt.generatePlatformToken(9L, "auditor-1", "auditor");
        adminRows.put(9L, PlatformAdmin.builder().id(9L).platformRole(PlatformRole.auditor).status(Status.active).build());
        analyse(F1, auditor).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE));
        assertNothingHappened();

        grant("ROLE", "auditor", "审计抽查").andExpect(status().isOk());
        answerWithAnalysis();
        analyse(F1, auditor).andExpect(status().isOk());
        assertThat(audit).extracting(r -> r.actor().id()).containsExactly(9L);
    }

    @Test
    void aGrantedAccountCanUseTheEntryAndAnotherAccountOfTheSameRoleCannot() throws Exception {
        adminRows.put(10L, PlatformAdmin.builder().id(10L).platformRole(PlatformRole.operation_admin).status(Status.active).build());
        grant("ACCOUNT", String.valueOf(STAFF), "运营专员").andExpect(status().isOk());
        answerWithAnalysis();
        analyse(F1, staffToken()).andExpect(status().isOk());
        analyse(F1, jwt.generatePlatformToken(10L, "other", "operation_admin")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE));
    }

    @Test
    void aRevokedGrantIsRefusedOnTheVeryNextCall() throws Exception {
        grant("ACCOUNT", String.valueOf(STAFF), "临时开通").andExpect(status().isOk());
        answerWithAnalysis();
        analyse(F1, staffToken()).andExpect(status().isOk());

        revoke("ACCOUNT", String.valueOf(STAFF), "用完收回").andExpect(status().isOk());
        audit.clear();
        analyse(F1, staffToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE));
        assertThat(audit).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /** 选定的行为: 账号授权在账号改角色后保留, 直到撤销。 */
    @Test
    void anAccountGrantStaysThroughARoleChangeUntilRevoked() throws Exception {
        grant("ACCOUNT", String.valueOf(STAFF), "专人").andExpect(status().isOk());
        adminRows.get(STAFF).setPlatformRole(PlatformRole.auditor);
        answerWithAnalysis();
        analyse(F1, jwt.generatePlatformToken(STAFF, "staff", "auditor")).andExpect(status().isOk());
        revoke("ACCOUNT", String.valueOf(STAFF), "收回").andExpect(status().isOk());
        analyse(F1, jwt.generatePlatformToken(STAFF, "staff", "auditor")).andExpect(status().isForbidden());
    }

    /** 账号升成超管后授权还在表里: 必须撤得掉, 否则他再被降级时授权就回来了。 */
    @Test
    void anAccountPromotedToSuperAdminCanBeRevokedAndNothingComesBackOnDemotion() throws Exception {
        grant("ACCOUNT", String.valueOf(STAFF), "专人").andExpect(status().isOk());
        adminRows.get(STAFF).setPlatformRole(PlatformRole.super_admin);

        revoke("ACCOUNT", String.valueOf(STAFF), "升了超管, 收回").andExpect(status().isOk());

        adminRows.get(STAFF).setPlatformRole(PlatformRole.auditor);
        analyse(F1, jwt.generatePlatformToken(STAFF, "staff", "auditor")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE));
        assertThat(grantRows).isEmpty();
        assertThat(grantAudit).extracting(PlatformAiAnalysisGrantAudit::getAction).containsExactly("GRANT", "REVOKE");
    }

    @Test
    void anAccountWhoseRowWasDeletedCanStillBeRevoked() throws Exception {
        grant("ACCOUNT", String.valueOf(STAFF), "专人").andExpect(status().isOk());
        adminRows.remove(STAFF);

        revoke("ACCOUNT", String.valueOf(STAFF), "人走了, 收回").andExpect(status().isOk());

        assertThat(grantRows).isEmpty();
        assertThat(grantAudit).extracting(PlatformAiAnalysisGrantAudit::getAction).containsExactly("GRANT", "REVOKE");
    }

    /** 撤销只看格式: 格式不对 400; 格式对而表里没有 404, 不留痕。 */
    @Test
    void aRevokeChecksTheFormatAndNothingElse() throws Exception {
        revoke("ACCOUNT", "abc", "不是编号").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("平台账号编号无效"));
        revoke("ROLE", "  ", "空角色").andExpect(status().isBadRequest());
        revoke("TEAM", "x", "不支持的类型").andExpect(status().isBadRequest());
        revoke("ACCOUNT", "999", "没开通过的账号").andExpect(status().isNotFound());
        revoke("ROLE", "nobody", "没开通过的角色").andExpect(status().isNotFound());
        assertThat(grantAudit).isEmpty();
    }

    /** 判定看库里的角色和状态: 旧令牌里写着超管、库里已降级的账号拿不到; 令牌里写的角色有授权、库里不是这个角色也拿不到。 */
    @Test
    void theGrantIsJudgedOnTheDatabaseRowNotTheToken() throws Exception {
        grant("ROLE", "auditor", "审计抽查").andExpect(status().isOk());
        analyse(F1, jwt.generatePlatformToken(STAFF, "staff", "auditor")).andExpect(status().isForbidden());
        analyse(F1, jwt.generatePlatformToken(STAFF, "staff", "super_admin")).andExpect(status().isForbidden());
        adminRows.get(STAFF).setPlatformRole(PlatformRole.auditor);
        adminRows.get(STAFF).setStatus(Status.inactive);
        analyse(F1, jwt.generatePlatformToken(STAFF, "staff", "auditor")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.INACTIVE_MESSAGE));
        assertNothingHappened();
    }

    @Test
    void anInactiveSuperAdminIsRefused() throws Exception {
        admin.setStatus(Status.inactive);
        analyse(F1, superAdminToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.INACTIVE_MESSAGE));
        assertNothingHappened();
    }

    @Test
    void onlyAnActiveSuperAdminInTheDatabaseMayManageGrants() throws Exception {
        String body = grantBody("ROLE", "auditor", "想开");
        // 库里不是超管, 令牌里写的是
        for (String token : List.of(jwt.generatePlatformToken(STAFF, "staff", "super_admin"),
                jwt.generatePlatformToken(STAFF, "staff", "platform_admin"))) {
            mvc.perform(post(GRANTS).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(PlatformAiAnalysisGrantService.NOT_MANAGER_MESSAGE));
            mvc.perform(post(GRANTS + "/revoke").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(get(GRANTS).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
        // 普通平台角色的令牌连授权接口都到不了
        mvc.perform(post(GRANTS).header("Authorization", "Bearer " + staffToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权访问平台管理API，需要平台管理员权限"));
        admin.setStatus(Status.inactive);
        grant("ROLE", "auditor", "停用的超管").andExpect(status().isForbidden());
        assertThat(grantRows).isEmpty();
        assertThat(grantAudit).isEmpty();
    }

    @Test
    void aGrantAndARevokeEachWriteExactlyOneAuditRowAndNoChangeWritesNone() throws Exception {
        grant("ROLE", "auditor", "审计抽查").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grantee").value("auditor"));
        grant("ROLE", "auditor", "再开一次").andExpect(status().isConflict())
                .andExpect(jsonPath("$.actionHint").value(PlatformAiAnalysisGrantService.ALREADY_GRANTED_HINT));
        revoke("ROLE", "auditor", "抽查结束").andExpect(status().isOk());
        revoke("ROLE", "auditor", "再撤一次").andExpect(status().isNotFound());

        assertThat(grantAudit).extracting(PlatformAiAnalysisGrantAudit::getAction, PlatformAiAnalysisGrantAudit::getGranteeType,
                        PlatformAiAnalysisGrantAudit::getGranteeRef, PlatformAiAnalysisGrantAudit::getActorId,
                        PlatformAiAnalysisGrantAudit::getActorRole, PlatformAiAnalysisGrantAudit::getReason)
                .containsExactly(Tuple.tuple("GRANT", "ROLE", "auditor", ADMIN, "super_admin", "审计抽查"),
                        Tuple.tuple("REVOKE", "ROLE", "auditor", ADMIN, "super_admin", "抽查结束"));
        mvc.perform(get(GRANTS).header("Authorization", "Bearer " + superAdminToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grants.length()").value(0))
                .andExpect(jsonPath("$.data.recentAudit.length()").value(2));
    }

    @Test
    void aGrantNeedsAReasonAndARealGrantee() throws Exception {
        grant("ROLE", "auditor", "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("必须填写理由"));
        revoke("ROLE", "auditor", "").andExpect(status().isBadRequest());
        grant("ROLE", "super_admin", "没必要").andExpect(status().isBadRequest());
        grant("ACCOUNT", String.valueOf(ADMIN), "超管给自己留一条").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("超级管理员不需要开通"));
        grant("ROLE", "nobody", "不存在的角色").andExpect(status().isBadRequest());
        grant("ACCOUNT", "999", "不存在的账号").andExpect(status().isNotFound());
        grant("ACCOUNT", "abc", "不是编号").andExpect(status().isBadRequest());
        grant("TEAM", "x", "不支持的类型").andExpect(status().isBadRequest());
        assertThat(grantRows).isEmpty();
        assertThat(grantAudit).isEmpty();
    }

    /** 留痕只追加: 服务层够不着改写和删除 (库里的触发器在 PG 闸里单独验)。 */
    @Test
    void theServiceHasNoWayToUpdateOrDeleteAuditRows() {
        List<String> methods = Stream.of(PlatformAiAnalysisGrantAuditRepository.class.getMethods()).map(Method::getName).toList();
        assertThat(methods).containsExactlyInAnyOrder("save", "findTop50ByOrderByChangedAtDescIdDesc");
        assertThat(PlatformAiAnalysisGrantAudit.class).hasAnnotation(Immutable.class);
        assertThat(Stream.of(PlatformAiAnalysisGrantAudit.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    // ------------------------------------------------------------------ 拦截器: 只放开这一条路由

    @Test
    void theInterceptorAdmitsOtherPlatformRolesOnThisRouteOnly() throws Exception {
        String auditor = jwt.generatePlatformToken(9L, "auditor-1", "auditor");
        assertThat(interceptorAdmits("POST", "/api/platform/factories/" + F1 + ROUTE, auditor)).isTrue();
        for (String[] denied : List.of(new String[]{"GET", "/api/platform/factories/" + F1 + ROUTE},
                new String[]{"POST", "/api/platform/factories/" + F1 + ROUTE + "/"},
                new String[]{"POST", "/api/platform/factories/" + F1 + ROUTE + ";x=1"},
                new String[]{"POST", "/api/platform/factories/" + F1 + "/ai-analysis/cost/other"},
                new String[]{"GET", "/api/platform/factories/" + F1 + "/secrecy"},
                new String[]{"POST", GRANTS})) {
            assertThat(interceptorAdmits(denied[0], denied[1], auditor)).as(denied[0] + " " + denied[1]).isFalse();
        }
    }

    // ------------------------------------------------------------------ 拒绝: 身份

    @Test
    void aFactoryAccountIsStoppedByTheRealInterceptorEvenOnItsOwnFactory() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setFactoryId(F1);
        user.setIsActive(true);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        String token = jwt.generateToken(5L, F1, "boss", "factory_super_admin");
        analyse(F1, token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权访问平台管理API，需要平台管理员权限"));
        assertNothingHappened();
    }

    /**
     * 这条路由对任何角色串都放行角色判断, 所以工厂令牌写着平台角色名时, 挡它的只有「是不是平台令牌」。
     * 校验消息: 要证明是拦截器这道闸挡的, 不是后面入口服务的身份闸兜住的。
     */
    @Test
    void aFactoryTokenNamingAPlatformRoleGetsNoPlatformAuthorityOnTheEntry() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setFactoryId(F1);
        user.setIsActive(true);
        when(users.findById(5L)).thenReturn(Optional.of(user));
        for (String platformRole : List.of("super_admin", "auditor", "operation_admin")) {
            analyse(F1, jwt.generateToken(5L, F1, "boss", platformRole)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("无权访问平台管理API，需要平台管理员权限"));
        }
        assertNothingHappened();
    }

    @Test
    void anAnonymousCallIsStoppedByTheRealInterceptor() throws Exception {
        mvc.perform(post("/api/platform/factories/" + F1 + ROUTE).contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isUnauthorized());
        assertNothingHappened();
    }

    @Test
    void aViewTokenIsStoppedByTheRealInterceptor() throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(ADMIN, "admin", "super_admin", F1);
        analyse(F1, token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("工厂查看令牌不能访问平台管理API"));
        assertNothingHappened();
    }

    // ------------------------------------------------------------------ 拒绝: 工厂

    @Test
    void anUnknownFactoryIsRefused() throws Exception {
        analyse("F-GHOST", superAdminToken()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("工厂不存在"));
        assertNothingHappened();
    }

    /** 工厂拦截器的关停闸对平台账号不生效, authority 只查存在, 这条只能靠入口自己那道检查。 */
    @Test
    void aClosedFactoryIsRefusedByTheEntryItself() throws Exception {
        factoryRows.get(F1).setIsActive(false);
        analyse(F1, superAdminToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(PlatformFactoryAiAnalysisService.FACTORY_CLOSED_MESSAGE));
        assertNothingHappened();
    }

    @Test
    void aUrlWithoutAFactoryNeverReachesTheEntry() throws Exception {
        String token = superAdminToken();
        for (String url : List.of("/api/platform/factories" + ROUTE, "/api/platform" + ROUTE,
                "/api/platform/factories//ai-analysis/cost/time-range")) {
            mvc.perform(post(url).header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body(""))).andExpect(status().isNotFound());
        }
        analyse("%20", token).andExpect(status().isBadRequest());
        assertNothingHappened();
    }

    @Test
    void aFailedAuditWriteStopsTheAnalysisBeforeAnyData() throws Exception {
        doAnswer(inv -> {
            throw new DataAccessResourceFailureException("down");
        }).when(recorder).record(any(), any(), any(), any(), any(), any(), any());
        analyse(F1, superAdminToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(PlatformFactoryAiAnalysisService.AUDIT_UNAVAILABLE_MESSAGE));
        assertNoDataAndNoModel();
    }

    @Test
    void aMissingDateIsRefusedBeforeTheService() throws Exception {
        mvc.perform(post("/api/platform/factories/" + F1 + ROUTE)
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endDate\":\"2026-10-31\"}"))
                .andExpect(status().isBadRequest());
        assertNothingHappened();
    }

    // ------------------------------------------------------------------ 工厂只取路径

    @Test
    void aFactoryInTheBodyOrQueryCannotRedirectTheCall() throws Exception {
        answerWithAnalysis();
        mvc.perform(post("/api/platform/factories/" + F1 + ROUTE).param("factoryId", F2)
                        .header("Authorization", "Bearer " + superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"factoryId\":\"" + F2 + "\"")))
                .andExpect(status().isOk());

        verify(batches, atLeastOnce()).findByFactoryIdAndCreatedAtBetween(eq(F1), any(), any());
        verify(batches, never()).findByFactoryIdAndCreatedAtBetween(eq(F2), any(), any());
        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent.getHeader("X-Factory-Id")).isEqualTo(F1);
        assertThat(sent.getBody().readString(StandardCharsets.UTF_8)).contains(PLAIN_PRODUCT).doesNotContain("别厂的产品");
        assertThat(audit).extracting(AuditRow::factoryId).containsExactly(F1);
    }

    // ------------------------------------------------------------------ 出外脱敏 c 按点名的工厂

    /** 入口不看 a / b, 但 c 管的是什么能发给外部模型, 不是谁能读: 照旧按点名的工厂判。 */
    @Test
    void redactionFollowsTheNamedFactoryAndTheTrustedHeadersCarryIt() throws Exception {
        set(F1, SecrecyItem.EGRESS_REDACTION, true);
        set(F2, SecrecyItem.EGRESS_REDACTION, false);
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent.getBody().readString(StandardCharsets.UTF_8)).doesNotContain(PHONE, SUPPLIER);
        assertThat(sent.getHeader("X-Factory-Id")).isEqualTo(F1);
        // 平台 id 不在 users 表里: 不当工厂用户传, 也不把平台令牌转给 Python
        assertThat(sent.getHeader("X-User-Id")).isNull();
        assertThat(sent.getHeader("Authorization")).isNull();

        // 阴性对照: 同一请求换成 c 关的工厂, 原话才原样发出 (上面的读数不是提示词里本来就没有)
        answerWithAnalysis();
        analyse(F2, superAdminToken()).andExpect(status().isOk());
        RecordedRequest raw = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(raw.getBody().readString(StandardCharsets.UTF_8)).contains(PHONE, SUPPLIER);
        assertThat(raw.getHeader("X-Factory-Id")).isEqualTo(F2);
    }

    /** c 开时保密产品名换代号发出, 回答里再换回真名。 */
    @Test
    void withRedactionOnTheProtectedNameLeavesAsATokenAndComesBackInTheAnswer() throws Exception {
        protectEverything();
        set(F1, SecrecyItem.EGRESS_REDACTION, true);
        addSecretProduct();
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        assertThat(sentBody()).contains(PLAIN_PRODUCT).doesNotContain(SECRET_PRODUCT);
        assertThat(audit).extracting(AuditRow::productTypeId).contains("P-SECRET");
    }

    /** DashScope 直连通道同样发脱敏后的正文 (开了 costAnalysis 迁移开关时走这条)。 */
    @Test
    void theDirectChannelSendsTheSameRedactedText() throws Exception {
        set(F1, SecrecyItem.EGRESS_REDACTION, true);
        useDirectChannel(ANSWER);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        analyse(F1, superAdminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysis").value(ANSWER));
        verify(pythonLlm).chatWithThinking(anyString(), sent.capture(), eq(50));
        assertThat(sent.getValue()).contains(PLAIN_PRODUCT).doesNotContain(PHONE, SUPPLIER);
        assertThat(server.getRequestCount()).isZero();
    }

    /** 深层出口不带工厂时靠 {@code analysing} 绑定解析; 平台账号没有绑定就会被拒。绑定只活在这次分析里。 */
    @Test
    void theNamedFactoryIsBoundForDeepExitsDuringTheAnalysisOnly() throws Exception {
        AtomicBoolean deepExitResolved = new AtomicBoolean();
        doAnswer(inv -> {
            deepExitResolved.set(!guard.structured("probe", null, Map.of("k", "v")).refused());
            return inv.callRealMethod();
        }).when(python).analyzeGeneralWithoutCapture(anyString(), any(PythonGeneralAnalysisRequest.class));
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        assertThat(deepExitResolved).as("分析期间深层出口认得点名的工厂").isTrue();
        assertThat(guard.structured("probe", null, Map.of("k", "v")).refused()).as("分析结束后绑定已还原").isTrue();
    }

    // ------------------------------------------------------------------ 不留共享结果 / 不耗配额

    @Test
    void theEntryUsesNoQuotaAndLeavesNoSharedResult() throws Exception {
        answerWithAnalysis();
        analyse(F1, superAdminToken()).andExpect(status().isOk());
        verifyNoInteractions(quotaUsage, quotaConfig, results, aiAuditLog);
    }

    // ------------------------------------------------------------------ 没有替代结果

    @Test
    void refusedRedactionIsAClearFailureWithoutCallingTheModel() throws Exception {
        protectEverything();
        set(F1, SecrecyItem.EGRESS_REDACTION, true);
        addSecretProduct();
        doThrow(new QueryTimeoutException("slow")).when(productTypeRepository).findAllById(any());

        analyse(F1, superAdminToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("数据里有无法脱敏的内容，未发给 AI 分析"));
        assertNoModel();
    }

    @Test
    void aModelFailureIsAClearFailureNotAnEmptySuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        analyse(F1, superAdminToken()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("AI服务暂时不可用，请稍后重试"));
        verifyNoInteractions(results);
    }

    @Test
    void aBlankAnswerFromTheModelIsAFailureNotAnEmptySuccess() throws Exception {
        useDirectChannel(" ");
        analyse(F1, superAdminToken()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void noBatchesInTheRangeIsAClearAnswerWithoutCallingTheModel() throws Exception {
        batchRows.get(F1).clear();
        analyse(F1, superAdminToken()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("该时间段内没有批次数据，未发给 AI 分析"));
        assertNoModel();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 平台账号在 a、b 都开时走既有接口的样子: 配方 403 / 摘要、老成本分析不含保密产品, 判定点都是拒绝。
     * 每次调用前后不留痕地比较, 所以这条既用作基线也用作入口之后的对照。
     */
    void assertExistingEndpointsBlockThePlatform() throws Exception {
        String bearer = "Bearer " + superAdminToken();
        existing.perform(get("/api/mobile/" + F1 + "/bom/recipes/r1/seasoning").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        verify(recipeService, never()).getSeasoning(anyString(), anyString());
        String detail = existing.perform(get("/api/mobile/" + F1 + "/bom/recipes/r1").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processSecret.visibility").value("RESTRICTED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(detail).doesNotContain(SECRET_RECIPE_LINE);

        answerWithAnalysis();
        existing.perform(post("/api/mobile/" + F1 + "/ai/analysis/cost/time-range").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isOk());
        // 老路由的提示词也走 30 天统计: 保密产品只剩名称和产量, 成本是「保密」
        assertThat(sentBody()).as("老成本分析").contains(PLAIN_PRODUCT, SECRET_ROW_WITHHELD).doesNotContain(SECRET_UNIT_COST);

        assertThat(access.decide(PLATFORM_ACTOR, F1, "P-SECRET", ProcessSecretAction.VIEW_FULL).outcome())
                .isEqualTo(ProcessSecretAccessService.Outcome.DENY);
        assertThat(access.permitsViewFull(PLATFORM_ACTOR, F1, "P-SECRET", "probe")).isFalse();
        assertThat(access.permitsPlatformAiRead(PLATFORM_ACTOR, F1, "P-SECRET")).isFalse();
        assertThat(access.filterPermitted(PLATFORM_ACTOR, F1, List.of("P-SECRET"), ProcessSecretAction.VIEW_FULL, false, "probe"))
                .isEmpty();
        assertThat(access.hiddenFromPlatform(F1)).contains("P-SECRET");
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(PLATFORM_ACTOR)) {
            assertThat(costScope.forCurrentCaller(F1).coversProduct("P-SECRET")).isTrue();
        }
    }

    void protectEverything() {
        set(F1, SecrecyItem.PROCESS_SECRET, true);
        set(F1, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
    }

    void addSecretProduct() {
        addPolicy(F1, "P-SECRET", SECRET_PRODUCT, ProcessSecretStorageMode.CLOUD_RESTRICTED);
        addBatch(F1, 12L, "P-SECRET", SECRET_PRODUCT).setTotalCost(new BigDecimal("7777"));
    }

    void assertNothingHappened() throws IOException {
        assertThat(audit).as("被拒的请求不留 ALLOW").isEmpty();
        assertNoDataAndNoModel();
    }

    void assertNoDataAndNoModel() throws IOException {
        verify(batches, never()).findByFactoryIdAndCreatedAtBetween(any(), any(), any());
        assertNoModel();
    }

    void assertNoModel() throws IOException {
        verify(python, never()).analyzeGeneralWithoutCapture(any(), any(PythonGeneralAnalysisRequest.class));
        verifyNoInteractions(pythonLlm);
        assertThat(server.getRequestCount()).isZero();
    }

    String sentBody() throws InterruptedException {
        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent).as("这次调用没有发给模型").isNotNull();
        return sent.getBody().readString(StandardCharsets.UTF_8);
    }

    ResultActions analyse(String factoryId, String token) throws Exception {
        return mvc.perform(post("/api/platform/factories/" + factoryId + ROUTE)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body("")));
    }

    ResultActions grant(String type, String grantee, String reason) throws Exception {
        return mvc.perform(post(GRANTS).header("Authorization", "Bearer " + superAdminToken())
                .contentType(MediaType.APPLICATION_JSON).content(grantBody(type, grantee, reason)));
    }

    ResultActions revoke(String type, String grantee, String reason) throws Exception {
        return mvc.perform(post(GRANTS + "/revoke").header("Authorization", "Bearer " + superAdminToken())
                .contentType(MediaType.APPLICATION_JSON).content(grantBody(type, grantee, reason)));
    }

    static String grantBody(String type, String grantee, String reason) {
        return "{\"granteeType\":\"" + type + "\",\"grantee\":\"" + grantee + "\",\"reason\":\"" + reason + "\"}";
    }

    boolean interceptorAdmits(String method, String uri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer " + token);
        return jwtInterceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    static String body(String extra) {
        return "{\"startDate\":\"" + RANGE_START + "\",\"endDate\":\"" + RANGE_END
                + "\",\"dimension\":\"overall\",\"question\":\"" + QUESTION + "\"" + extra + "}";
    }

    String superAdminToken() {
        return jwt.generatePlatformToken(ADMIN, "platform-test", "super_admin");
    }

    String staffToken() {
        return jwt.generatePlatformToken(STAFF, "staff", "operation_admin");
    }

    void answerWithAnalysis() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"answer\":\"" + ANSWER + "\"}"));
    }

    void useDirectChannel(String answer) {
        DashScopeConfig direct = mock(DashScopeConfig.class, RETURNS_DEEP_STUBS);
        when(direct.isAvailable()).thenReturn(true);
        when(direct.getMigration().isCostAnalysis()).thenReturn(true);
        when(direct.isThinkingEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(basicAi, "dashScopeConfig", direct);
        ChatCompletionResponse.Message message = new ChatCompletionResponse.Message();
        message.setRole("assistant");
        message.setContent(answer);
        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice();
        choice.setMessage(message);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        when(pythonLlm.chatWithThinking(anyString(), anyString(), eq(50))).thenReturn(response);
    }

    void set(String factory, SecrecyItem item, Boolean enabled) {
        settingRows.removeIf(r -> r.getFactoryId().equals(factory) && r.getItemCode().equals(item.name()));
        settingRows.add(FactorySecrecySetting.builder().factoryId(factory).itemCode(item.name()).enabled(enabled).updatedBy(1L).build());
        config.invalidate(factory);
        access.invalidate(factory);
    }

    void addPolicy(String factory, String productTypeId, String name, ProcessSecretStorageMode mode) {
        policies.add(ProcessSecretPolicy.builder().id((long) policies.size() + 1).factoryId(factory)
                .productTypeId(productTypeId).storageMode(mode).ownerUserId(21L).createdBy(21L).build());
        ProductType product = new ProductType();
        product.setId(productTypeId);
        product.setFactoryId(factory);
        product.setName(name);
        productTypes.put(productTypeId, product);
        access.invalidate(factory);
    }

    /** 批次实体 + 带供应商的消耗记录: 行由真实的 {@link ProcessingServiceImpl} 从这些实体拼出来。 */
    ProductionBatch addBatch(String factory, long id, String productTypeId, String productName) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(factory);
        batch.setBatchNumber("B-" + id);
        batch.setProductName(productName);
        batch.setProductTypeId(productTypeId);
        batch.setPlannedQuantity(new BigDecimal("100"));
        batch.setActualQuantity(new BigDecimal("98"));
        batch.setUnit("kg");
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        batch.setCreatedAt(IN_RANGE);
        batchRows.computeIfAbsent(factory, k -> new ArrayList<>()).add(batch);
        return batch;
    }

    MaterialConsumption consumption() {
        Supplier supplier = new Supplier();
        supplier.setId("S-1");
        supplier.setName(SUPPLIER);
        supplier.setContactPerson("王经理");
        MaterialBatch material = new MaterialBatch();
        material.setBatchNumber("MB-1");
        material.setSupplier(supplier);
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setBatch(material);
        consumption.setQuantity(new BigDecimal("10"));
        consumption.setUnitPrice(new BigDecimal("30"));
        consumption.setTotalCost(new BigDecimal("300"));
        return consumption;
    }

    static Factory factory(String id, boolean active) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setIsActive(active);
        return factory;
    }

    ProcessSecretAccessService accessService() {
        when(policyRepository.findByFactoryIdOrderByProductTypeIdAsc(anyString())).thenAnswer(inv -> policies.stream()
                .filter(p -> p.getFactoryId().equals(inv.getArgument(0))).toList());
        when(policyRepository.findByFactoryIdAndProductTypeId(anyString(), anyString())).thenAnswer(inv -> policies.stream()
                .filter(p -> p.getFactoryId().equals(inv.getArgument(0)) && p.getProductTypeId().equals(inv.getArgument(1)))
                .findFirst());
        when(policyRepository.count()).thenAnswer(inv -> (long) policies.size());
        doAnswer(inv -> {
            audit.add(new AuditRow(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), inv.getArgument(6)));
            return null;
        }).when(recorder).record(any(), any(), any(), any(), any(), any(), any());
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        ProcessSecretAccessService service = new ProcessSecretAccessService(policyRepository, grants, recorder);
        service.setFactorySecrecyConfig(config);
        return service;
    }

    void wireGrantRepositories() {
        when(grantRepository.existsByGranteeTypeAndGranteeRef(anyString(), anyString()))
                .thenAnswer(inv -> grantRows.containsKey(inv.getArgument(0) + "|" + inv.getArgument(1)));
        when(grantRepository.saveAndFlush(any())).thenAnswer(inv -> {
            PlatformAiAnalysisGrant row = inv.getArgument(0);
            grantRows.put(row.getGranteeType() + "|" + row.getGranteeRef(), row);
            return row;
        });
        when(grantRepository.revoke(anyString(), anyString()))
                .thenAnswer(inv -> grantRows.remove(inv.getArgument(0) + "|" + inv.getArgument(1)) == null ? 0 : 1);
        when(grantRepository.findAllByOrderByGrantedAtDescGranteeRefAsc()).thenAnswer(inv -> List.copyOf(grantRows.values()));
        when(grantAuditRepository.save(any())).thenAnswer(inv -> {
            grantAudit.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(grantAuditRepository.findTop50ByOrderByChangedAtDescIdDesc()).thenAnswer(inv -> List.copyOf(grantAudit));
    }

    /** 真实的取数实现: 构造器参数全是桩, 只把批次、消耗、质检三张表接到内存行上。 */
    ProcessingServiceImpl realProcessingService() throws Exception {
        Constructor<?> constructor = ProcessingServiceImpl.class.getConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
        ProcessingServiceImpl service = (ProcessingServiceImpl) constructor.newInstance(arguments);
        batches = (ProductionBatchRepository) ReflectionTestUtils.getField(service, "productionBatchRepository");
        when(batches.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any())).thenAnswer(inv -> {
            LocalDateTime from = inv.getArgument(1);
            LocalDateTime to = inv.getArgument(2);
            List<ProductionBatch> loaded = batchRows.getOrDefault(inv.<String>getArgument(0), List.of()).stream()
                    .filter(b -> !b.getCreatedAt().isBefore(from) && !b.getCreatedAt().isAfter(to)).toList();
            loaded.forEach(ProcessSecretTestHooks::record);   // 生产上由 Hibernate PostLoad 打这个标
            return loaded;
        });
        when(batches.findByIdAndFactoryId(any(), anyString())).thenAnswer(inv -> batchRows
                .getOrDefault(inv.<String>getArgument(1), List.of()).stream()
                .filter(b -> b.getId().equals(inv.getArgument(0)) && !brokenBatches.contains(b.getId())).findFirst());
        MaterialConsumptionRepository consumptions =
                (MaterialConsumptionRepository) ReflectionTestUtils.getField(service, "materialConsumptionRepository");
        when(consumptions.findByProductionBatchId(any())).thenAnswer(inv -> List.of(consumption()));
        QualityInspectionRepository inspections =
                (QualityInspectionRepository) ReflectionTestUtils.getField(service, "qualityInspectionRepository");
        when(inspections.findByFactoryIdAndProductionBatchId(anyString(), any(), any())).thenReturn(Page.empty());
        return service;
    }

    /**
     * 真实的按产品统计: 仓库桩只替 JPQL 做它做的事 (COMPLETED、时间窗、按产品聚合、Object[] 四列)。
     * ReportService 是一层委托, 这里接到真实的 {@link ProductionReportServiceImpl}。
     */
    ReportService realProductionReport() throws Exception {
        Constructor<?> constructor = ProductionReportServiceImpl.class.getConstructors()[0];
        Object[] arguments = Arrays.stream(constructor.getParameterTypes()).map(t -> (Object) mock(t)).toArray();
        ProductionReportServiceImpl report = (ProductionReportServiceImpl) constructor.newInstance(arguments);
        ProductionBatchRepository reportBatches =
                (ProductionBatchRepository) ReflectionTestUtils.getField(report, "productionBatchRepository");
        when(reportBatches.findProductionByProduct(anyString(), any(), any())).thenAnswer(inv -> {
            LocalDateTime from = inv.getArgument(1);
            LocalDateTime to = inv.getArgument(2);
            Map<String, Object[]> grouped = new LinkedHashMap<>();
            for (ProductionBatch b : batchRows.getOrDefault(inv.<String>getArgument(0), List.of())) {
                if (b.getStatus() != ProductionBatchStatus.COMPLETED || b.getCreatedAt().isBefore(from)
                        || !b.getCreatedAt().isBefore(to)) continue;
                Object[] row = grouped.computeIfAbsent(b.getProductTypeId(),
                        k -> new Object[]{b.getProductTypeId(), b.getProductName(), BigDecimal.ZERO, b.getUnit()});
                row[2] = ((BigDecimal) row[2]).add(b.getActualQuantity());
            }
            return new ArrayList<>(grouped.values());
        });
        ReportService reports = mock(ReportService.class);
        when(reports.getProductionByProduct(anyString(), any(), any())).thenAnswer(inv -> {
            List<ProductionByProductDTO> rows = report.getProductionByProduct(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return rows;
        });
        return reports;
    }

    PythonSmartBIClient pythonClient() throws Exception {
        PythonSmartBIConfig clientConfig = new PythonSmartBIConfig();
        clientConfig.setEnabled(true);
        clientConfig.setUrl(server.url("/").toString());
        clientConfig.setConnectTimeout(1000);
        clientConfig.setTimeout(3000);
        clientConfig.setMaxRetries(0);
        com.cretas.aims.client.PythonServiceCircuitBreaker breaker = new com.cretas.aims.client.PythonServiceCircuitBreaker();
        ReflectionTestUtils.setField(breaker, "failureThreshold", 100);
        ReflectionTestUtils.setField(breaker, "openDurationMs", 100L);
        return new PythonSmartBIClient(clientConfig, new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(), breaker, "platform-entry-test-secret");
    }

    /** 既有接口: 真实的配方 controller + 工艺保密拦截器, 真实的 AI controller; 平台账号走它们的样子不许变。 */
    MockMvc existingEndpoints(ProcessSecretResponseAdvice advice, ObjectMapper webMapper) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("r1");
        recipe.setFactoryId(F1);
        recipe.setProductTypeId("P-SECRET");
        recipe.setProductName(SECRET_PRODUCT);
        BomRecipeItem item = new BomRecipeItem();
        item.setId(7L);
        item.setRecipeId("r1");
        item.setFactoryId(F1);
        item.setMaterialName(SECRET_RECIPE_LINE);
        item.setStandardQuantity(new BigDecimal("3.25"));
        recipe.setItems(List.of(item));
        when(recipeRepository.findById("r1")).thenReturn(Optional.of(recipe));
        when(recipeService.getRecipe(F1, "r1")).thenReturn(recipe);
        when(recipeService.getSeasoning(F1, "r1")).thenReturn(new BomSeasoningResponse());

        BomRecipeController recipes = new BomRecipeController(recipeService, mock(BomCopyService.class),
                mock(BomSeasoningWorkspaceService.class), mock(BomItemSubstituteService.class),
                mock(ProductTypeRepository.class), access, recipeRepository);
        AIController ai = new AIController();
        ReflectionTestUtils.setField(ai, "aiEnterpriseService", enterprise);
        ReflectionTestUtils.setField(ai, "mobileService", mock(MobileService.class, RETURNS_DEEP_STUBS));
        when(quotaUsage.findByFactoryIdAndWeekStart(anyString(), any())).thenReturn(Optional.of(
                AIQuotaUsage.builder().factoryId(F1).usedCount(0).quotaLimit(100).build()));
        when(results.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProcessSecretGuardInterceptor secretGuard = new ProcessSecretGuardInterceptor(access, recipeRepository,
                mock(BomRecipeItemRepository.class), mock(ProductProcessWorkflowRepository.class),
                mock(com.cretas.aims.repository.bom.BomVersionRepository.class), mock(ProductionBatchRepository.class),
                mock(ProductionPlanRepository.class),
                mock(com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository.class));
        return MockMvcBuilders.standaloneSetup(recipes, ai)
                .addInterceptors(jwtInterceptor, secretGuard)
                .setControllerAdvice(new GlobalExceptionHandler(), advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(webMapper))
                .build();
    }

    static String contextNotice(int products) {
        return "说明：近 30 天生产统计里有 " + products + " 个产品的成本和工艺无法读取"
                + "（客户侧保管产品、与它们同计划的产品，或认不出的产品），未计入；这些产品的名称和产量仍在统计里。";
    }

    /** 这个词在这个主代码文件里出现几次 (去掉注释)。 */
    static int mentionsIn(String file, String word) throws IOException {
        String code = com.cretas.aims.service.secrecy.SecrecyGateTestHooks.stripJavaComments(
                Files.readString(Path.of("src/main/java/com/cretas/aims").resolve(file), StandardCharsets.UTF_8));
        return code.split(java.util.regex.Pattern.quote(word), -1).length - 1;
    }

    /** 主代码里出现这个词的文件 (去掉注释), 路径相对 com/cretas/aims。 */
    static TreeSet<String> mainFilesMentioning(String word) throws IOException {
        Path root = Path.of("src/main/java/com/cretas/aims");
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = com.cretas.aims.service.secrecy.SecrecyGateTestHooks.stripJavaComments(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains(word)) found.add(root.relativize(file).toString().replace('\\', '/'));
            }
        }
        return found;
    }

    /** 真实的 {@link WebMvcConfig#addInterceptors}: 只取它给这条路由挂上的拦截器, 顺序按它排的。 */
    List<HandlerInterceptor> registeredFor(ObjectMapper webMapper) {
        class Registry extends InterceptorRegistry {
            List<Object> all() {
                return getInterceptors();
            }
        }
        Registry registry = new Registry();
        new WebMvcConfig(jwtInterceptor, new DemoReadOnlyInterceptor(),
                new PermissionInterceptor(mock(PermissionService.class), users, webMapper),
                new RequireRoleInterceptor(webMapper), mock(ModuleEnabledInterceptor.class),
                mock(DataScopeRouteInterceptor.class), mock(ProcessSecretGuardInterceptor.class),
                accessLogInterceptor).addInterceptors(registry);
        MockHttpServletRequest route = new MockHttpServletRequest("POST", "/api/platform/factories/" + F1 + ROUTE);
        ServletRequestPathUtils.parseAndCache(route);
        List<HandlerInterceptor> chain = new ArrayList<>();
        for (Object registered : registry.all()) {
            if (registered instanceof MappedInterceptor mapped) {
                if (mapped.matches(route)) chain.add(mapped.getInterceptor());
            } else {
                chain.add((HandlerInterceptor) registered);
            }
        }
        assertThat(chain).as("JWT 拦截器必须挂在这条路由上").contains(jwtInterceptor);
        return chain;
    }
}
