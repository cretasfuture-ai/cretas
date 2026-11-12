package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 写入确认的三个洞 (Fable 审阅 §8.2 / §8.3 / 建议 5), 走真实入口:
 * {@code ToolDispatchService.executeToolPreview} 铸凭据进库 → {@code IntentExecutionOrchestrator.confirm}
 * 原子认领 (真实 JPQL) → 确认前重跑预览比对改前值 → 执行 → 结果写回凭据。
 *
 * <p>设了 {@code CRETAS_WORKFLOW_PG_URL} 时跑在真实 PostgreSQL 上 (CI 由 run-with-test-postgres.sh 提供),
 * 否则跑 H2。唯一的替身是 {@code executeWithTool}: 它直接调工具, 工具抛异常时按真实实现的写路径口径回
 * OUTCOME_UNKNOWN (ToolDispatchService 执行循环里「Write tool outcome unknown; not retried」那一段)。
 * 每条「没执行」都配一个执行计数 (硬约束 9: 「没发生」要配一个「必发生」)。</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = IntentPreviewToken.class)
@EnableJpaRepositories(basePackageClasses = IntentPreviewTokenRepository.class)
@Import({PreviewTokenServiceImpl.class, WriteConfirmationStateBindingPostgresContractTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WriteConfirmationStateBindingPostgresContractTest {

    private static final String FACTORY = "F001";
    private static final long USER = 31L;
    private static final String ROLE = "FACTORY_ADMIN";
    private static final String INTENT = "TASK_FLIP";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:write_confirm_binding;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper testObjectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Autowired PreviewTokenService previewTokens;
    @Autowired IntentPreviewTokenRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private TaskTool tool;
    private ToolDispatchService dispatch;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        tool = new TaskTool(mapper);
        ToolDispatchService real = new ToolDispatchService(
                mock(ToolRegistry.class), mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        WriteGuardService guard = mock(WriteGuardService.class);
        when(guard.isWriteTool(tool)).thenReturn(true);
        ReflectionTestUtils.setField(real, "previewTokenService", previewTokens);
        ReflectionTestUtils.setField(real, "writeGuardService", guard);
        dispatch = spy(real);
        doAnswer(invocation -> {
            ToolExecutor target = invocation.getArgument(0);
            IntentExecuteRequest request = invocation.getArgument(2);
            try {
                target.execute(ToolCall.of("call", target.getToolName(), "{\"taskId\":12}"),
                        request.getContext());
                return IntentExecuteResponse.builder().status("SUCCESS").message("done").build();
            } catch (Exception thrown) {
                return IntentExecuteResponse.builder().status("OUTCOME_UNKNOWN").aiMode("WRITE")
                        .message("操作结果暂时无法确认").build();
            }
        }).when(dispatch).executeWithTool(any(), any(), any(), any(), any(), any(), any());

        AIIntentService intents = mock(AIIntentService.class);
        when(intents.getIntentByCode(FACTORY, INTENT)).thenReturn(Optional.of(intent()));
        when(intents.hasPermission(INTENT, ROLE)).thenReturn(true);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor(TaskTool.NAME)).thenReturn(Optional.of(tool));
        orchestrator = new IntentExecutionOrchestrator(
                intents, mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class),
                mock(ConversationMemoryService.class), mapper, mock(DashScopeClient.class),
                mock(DashScopeConfig.class), mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class), registry, mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                dispatch, mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", previewTokens);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
    }

    // ==================== 洞 1: 确认时比对状态 ====================

    @Test
    void someoneElseChangedTheObjectAfterPreviewSoConfirmRefusesAndNamesTheField() {
        ConfirmationProof proof = preview();
        tool.state.put("负责人", "李四"); // 另一个人在预览之后改了这条记录

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo(IntentExecutionOrchestrator.PREVIEW_STALE);
        assertThat(response.getMessage()).contains("「负责人」").contains("重新预览").contains("未执行");
        assertThat(((Map<?, ?>) response.getResultData()).get("changedFields")).isEqualTo(List.of("负责人"));
        assertThat(tool.executions.get()).as("按旧预览写入必须为 0 次").isZero();
        assertThat(status(proof)).as("没执行就是确定的失败, 不是结果不明").isEqualTo(TokenStatus.FAILED);
        assertThat(tool.previews.get()).as("确认时确实重跑了预览").isEqualTo(2);
    }

    @Test
    void unchangedObjectConfirmsAndExecutesExactlyOnce() {
        ConfirmationProof proof = preview();

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(tool.executions.get()).isEqualTo(1);
        assertThat(status(proof)).isEqualTo(TokenStatus.CONFIRMED);
    }

    @Test
    void mintedTokenCarriesObjectTypeAndObjectId() {
        ConfirmationProof proof = preview();

        IntentPreviewToken row = repository.findByToken(proof.proofToken()).orElseThrow();
        assertThat(row.getEntityType()).isEqualTo(TaskTool.NAME);
        assertThat(row.getEntityId()).isEqualTo("12");
        assertThat(row.getOperation()).isEqualTo("UPDATE");
        assertThat(row.getCurrentValues()).contains("张三");
    }

    // ==================== 洞 2: 执行异常记「结果不明」, 重放不再执行 ====================

    @Test
    void toolThrowsAfterTheWriteCommittedSoTokenIsOutcomeUnknownAndReplayDoesNotExecuteAgain() {
        ConfirmationProof proof = preview();
        tool.throwAfterWrite = true;

        IntentExecuteResponse first = orchestrator.confirm(FACTORY, proof, USER, ROLE);

        assertThat(first.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(first.getMessage()).contains("核对").contains("勿直接重复提交");
        assertThat(status(proof)).isEqualTo(TokenStatus.OUTCOME_UNKNOWN);
        assertThat(tool.state.get("状态")).as("写入其实已经落下").isEqualTo("进行中");

        IntentExecuteResponse replay = orchestrator.confirm(FACTORY, proof, USER, ROLE);

        assertThat(replay.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(replay.getMessage()).contains("没有重复执行");
        assertThat(tool.executions.get()).as("同一张凭据只执行一次").isEqualTo(1);
        assertThat(status(proof)).isEqualTo(TokenStatus.OUTCOME_UNKNOWN);
    }

    @Test
    void concurrentDoubleClickExecutesOnceAndTheLoserIsNeverReportedAsFailed() throws Exception {
        ConfirmationProof proof = preview();
        tool.holdExecution = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IntentExecuteResponse> a = pool.submit(() -> click(proof, ready, go));
            Future<IntentExecuteResponse> b = pool.submit(() -> click(proof, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            // 赢家停在工具里 (凭据处于 EXECUTING) 时, 输家必须已经被拒并返回
            Future<IntentExecuteResponse> first = awaitFirstDone(a, b);
            IntentExecuteResponse loser = first.get();
            tool.holdExecution.countDown();
            IntentExecuteResponse winner = (first == a ? b : a).get(10, TimeUnit.SECONDS);

            assertThat(winner.getStatus()).isEqualTo("SUCCESS");
            assertThat(loser.getStatus()).as("执行中被再点一次: 不能回「失败」诱导重做")
                    .isEqualTo("OUTCOME_UNKNOWN");
            assertThat(loser.getMessage()).contains("没有重复执行");
            assertThat(tool.executions.get()).isEqualTo(1);
            assertThat(status(proof)).isEqualTo(TokenStatus.CONFIRMED);

            IntentExecuteResponse late = orchestrator.confirm(FACTORY, proof, USER, ROLE);
            assertThat(late.getStatus()).isEqualTo(IntentExecutionOrchestrator.ALREADY_EXECUTED);
            assertThat(tool.executions.get()).isEqualTo(1);
        } finally {
            tool.holdExecution.countDown();
            go.countDown();
            pool.shutdownNow();
        }
    }

    // ==================== 洞 3: 对账停在「执行中」的凭据 ====================

    @Test
    void staleExecutingTokenIsReconciledToOutcomeUnknownAndCanNeverBeExecutedAgain() {
        ConfirmationProof stale = preview();
        ConfirmationProof fresh = previewForAnotherIntentInstance();
        // 进程在认领之后、回报结果之前死掉: 凭据停在 EXECUTING
        PreviewTokenService.ClaimResult staleClaim =
                previewTokens.claimToken(stale.proofToken(), FACTORY, USER, stale.commandDigest());
        PreviewTokenService.ClaimResult freshClaim =
                previewTokens.claimToken(fresh.proofToken(), FACTORY, USER, fresh.commandDigest());
        assertThat(staleClaim.isSuccess()).isTrue();
        assertThat(freshClaim.isSuccess()).isTrue();
        jdbc.update("UPDATE intent_preview_tokens SET claimed_at = ? WHERE token = ?",
                LocalDateTime.now().minusMinutes(30), stale.proofToken());

        int reconciled = previewTokens.reconcileStaleExecutingTokens();

        assertThat(reconciled).isEqualTo(1);
        assertThat(status(stale)).isEqualTo(TokenStatus.OUTCOME_UNKNOWN);
        assertThat(status(fresh)).as("阴性对照: 刚认领的不动").isEqualTo(TokenStatus.EXECUTING);

        IntentExecuteResponse replay = orchestrator.confirm(FACTORY, stale, USER, ROLE);
        assertThat(replay.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(tool.executions.get()).as("对账后的凭据不会被再次执行").isZero();
        assertThat(previewTokens.reconcileStaleExecutingTokens()).as("对账幂等").isZero();

        // 慢的执行者回来了: 它知道真实结果, 仍可把自己的租约落定
        assertThat(previewTokens.resolveClaim(stale.proofToken(), staleClaim.getClaimId(), true, "late done")).isTrue();
        assertThat(status(stale)).isEqualTo(TokenStatus.CONFIRMED);
    }

    // ==================== helpers ====================

    private IntentExecuteResponse click(ConfirmationProof proof, CountDownLatch ready, CountDownLatch go)
            throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        return orchestrator.confirm(FACTORY, proof, USER, ROLE);
    }

    private static Future<IntentExecuteResponse> awaitFirstDone(Future<IntentExecuteResponse> a,
                                                                Future<IntentExecuteResponse> b) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (a.isDone()) return a;
            if (b.isDone()) return b;
            Thread.sleep(10);
        }
        throw new AssertionError("neither confirmation returned while the winner was held");
    }

    private ConfirmationProof preview() {
        return previewWith(INTENT);
    }

    /** 同一用户同一意图的新预览会作废旧凭据, 第二张活凭据用另一个意图码。 */
    private ConfirmationProof previewForAnotherIntentInstance() {
        AIIntentService intents = (AIIntentService) ReflectionTestUtils.getField(orchestrator, "aiIntentService");
        AIIntentConfig other = intent();
        other.setIntentCode(INTENT + "_B");
        when(intents.getIntentByCode(FACTORY, INTENT + "_B")).thenReturn(Optional.of(other));
        when(intents.hasPermission(INTENT + "_B", ROLE)).thenReturn(true);
        return previewWith(INTENT + "_B");
    }

    private ConfirmationProof previewWith(String intentCode) {
        AIIntentConfig config = intent();
        config.setIntentCode(intentCode);
        IntentExecuteResponse preview = dispatch.executeToolPreview(tool, FACTORY,
                IntentExecuteRequest.builder().intentCode(intentCode).userInput("开始任务 12")
                        .context(new java.util.HashMap<>(Map.of("taskId", 12))).previewOnly(true).build(),
                config, USER, ROLE);
        IntentExecuteResponse.ConfirmableAction action = preview.getConfirmableAction();
        assertThat(action).as("写工具预览通过必须铸出凭据").isNotNull();
        return new ConfirmationProof(action.getConfirmToken(), action.getCommandDigest(), action.getExpiresAt());
    }

    private TokenStatus status(ConfirmationProof proof) {
        return repository.findByToken(proof.proofToken()).orElseThrow().getStatus();
    }

    private static AIIntentConfig intent() {
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode(INTENT);
        config.setIntentName("开始任务");
        config.setToolName(TaskTool.NAME);
        return config;
    }

    /** 一个改动已有记录的写工具: 预览报 currentValues, 执行改状态。对象状态在 {@link #state} 里, 测试可在中途改它。 */
    static final class TaskTool implements ToolExecutor {
        static final String NAME = "task_flip_update";
        final Map<String, Object> state = new ConcurrentHashMap<>(Map.of("状态", "待开始", "负责人", "张三"));
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger previews = new AtomicInteger();
        volatile boolean throwAfterWrite;
        volatile CountDownLatch holdExecution;
        private final ObjectMapper mapper;

        TaskTool(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override public String getToolName() { return NAME; }
        @Override public String getDescription() { return "flip a task"; }
        @Override public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of("taskId", Map.of("type", "integer")),
                    "required", List.of("taskId"));
        }
        @Override public boolean supportsPreview() { return true; }
        @Override public ActionType getActionType() { return ActionType.UPDATE; }
        @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }

        @Override
        public String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
            previews.incrementAndGet();
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("状态", state.get("状态"));
            current.put("负责人", state.get("负责人"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PREVIEW");
            data.put("canDo", "待开始".equals(state.get("状态")));
            data.put("message", "待开始".equals(state.get("状态")) ? "确认开始任务 12？" : "任务 12 已经开始。");
            data.put("currentValues", current);
            data.put("newValues", Map.of("状态", "进行中", "负责人", state.get("负责人")));
            return mapper.writeValueAsString(Map.of("success", true, "data", data));
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            executions.incrementAndGet();
            CountDownLatch hold = holdExecution;
            if (hold != null) {
                hold.await(10, TimeUnit.SECONDS);
            }
            state.put("状态", "进行中");
            if (throwAfterWrite) {
                throw new java.net.SocketTimeoutException("response timed out after commit");
            }
            return "{\"success\":true}";
        }
    }
}
