package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.ai.ToolExecutionAuditEvent;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.repository.intent.IntentPreviewTokenRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.execution.ToolDispatchService;
import com.cretas.aims.service.impl.PreviewTokenServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新执行网关的「执行前业务状态核对」与「认领绑业务对象」, 走真实路径:
 * {@code ToolDispatchService.executeToolPreview} 铸凭据进库 (记下改前值与对象) →
 * {@link DefaultToolExecutionGateway#execute} → 真实 {@link ToolConfirmationLease} 原子认领 (真实 JPQL) →
 * 执行前重跑预览比对改前值 ({@link PreviewStateRecheck}, 与老入口同一份) → 执行 → 真实账本与凭据落定。
 *
 * <p>设了 {@code CRETAS_WORKFLOW_PG_URL} 时跑在真实 PostgreSQL 上, 否则 H2。替身只有主体重建与工具解析
 * (两者不在本次被测行为里)。每条「没执行」都配一个执行计数与一次「确实重跑了预览」的读数
 * (硬约束 9: 「没发生」要配一个「必发生」)。</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {
        IntentPreviewToken.class, ToolExecutionIdempotencyRecord.class, ToolExecutionAuditEvent.class})
@EnableJpaRepositories(basePackageClasses = {
        IntentPreviewTokenRepository.class, ToolExecutionIdempotencyRepository.class,
        ToolExecutionAuditEventRepository.class})
@Import({PreviewTokenServiceImpl.class, ToolExecutionLedgerService.class, ToolConfirmationLease.class,
        DefaultToolExecutionGateway.class, GatewayPreExecutionStateCheckContractTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GatewayPreExecutionStateCheckContractTest {

    private static final String FACTORY = "F001";
    private static final long USER = 31L;
    private static final String ROLE = "factory_admin";
    private static final String INTENT = "TASK_FLIP";
    private static final String VERSION = "1.0.0";

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
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:gateway_state_check;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
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
    @Autowired IntentPreviewTokenRepository tokenRepository;
    @Autowired ToolExecutionIdempotencyRepository idempotencyRepository;
    @Autowired ToolExecutionAuditEventRepository auditRepository;
    @Autowired DefaultToolExecutionGateway gateway;
    @Autowired ObjectMapper mapper;

    @MockBean ToolPrincipalPolicy principalPolicy;
    @MockBean ToolRuntimeRegistry runtimeRegistry;
    @MockBean LegacyToolMigrationRegistry legacyToolMigrationRegistry;

    private TaskTool tool;
    private ToolDispatchService dispatch;
    private ExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        idempotencyRepository.deleteAll();
        auditRepository.deleteAll();
        tool = new TaskTool(mapper);

        dispatch = new ToolDispatchService(
                com.cretas.aims.testsupport.ToolRegistryStubs.allToolsEnabled(), mapper, mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        WriteGuardService guard = mock(WriteGuardService.class);
        when(guard.isWriteTool(tool)).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokens);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", guard);

        principal = new ExecutionPrincipal(FACTORY, "FACTORY", Long.toString(USER), PrincipalType.USER,
                Set.of(ROLE), Set.of("production:read_write"), Set.of());
        when(principalPolicy.rehydrate(any(ExecutionPrincipal.class))).thenReturn(Optional.of(
                new ToolPrincipalPolicy.RehydratedPrincipal(principal, Map.of(
                        "factoryId", FACTORY, "tenantId", FACTORY, "businessType", "FACTORY",
                        "userId", USER, "userRole", ROLE, "canViewPrices", true,
                        "canViewPurchasePrices", true, "canViewPurchaseOrFinancePrices", true))));
        ToolDescriptor descriptor = new ToolDescriptor(TaskTool.NAME, ToolExecutor.ActionType.UPDATE,
                ToolExecutor.RiskLevel.MEDIUM, Set.of("production:read_write"), Set.of(),
                Set.of(FactoryType.FACTORY), Set.of("production"), VERSION, true,
                ConfirmationPolicy.REQUIRED_FOR_EXECUTION, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.REQUIRED_FOR_EXECUTION, DataClassification.INTERNAL,
                Set.of(ToolExecutionSource.AI_CHAT), ToolEgressPolicy.denyAll(),
                DescriptorProvenance.EXPLICIT);
        when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(new ResolvedTool(descriptor, tool)));
        lenient().when(legacyToolMigrationRegistry.resolve(any(), any())).thenReturn(Optional.empty());
    }

    // ==================== 执行前业务状态核对 ====================

    @Test
    void unchangedObjectExecutesExactlyOnceAfterTheRecheck() {
        Minted minted = preview();

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(tool.executions.get()).isEqualTo(1);
        assertThat(tool.previews.get()).as("铸凭据 1 次 + 执行前核对 1 次").isEqualTo(2);
        assertThat(status(minted)).isEqualTo(TokenStatus.CONFIRMED);
        assertThat(tool.state.get("状态")).isEqualTo("进行中");
    }

    @Test
    void someoneElseChangedTheObjectAfterPreviewSoGatewayRefusesWithPreviewStale() {
        Minted minted = preview();
        tool.state.put("负责人", "李四"); // 另一个人在预览之后改了这条记录

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        JsonNode payload = result.payload();
        assertThat(payload.path("success").asBoolean(true)).isFalse();
        assertThat(payload.path("status").asText()).isEqualTo("PREVIEW_STALE");
        assertThat(payload.path("reason").asText()).isEqualTo("STATE_CHANGED");
        assertThat(payload.path("needsRepreview").asBoolean()).isTrue();
        assertThat(mapper.convertValue(payload.path("changedFields"), List.class)).isEqualTo(List.of("负责人"));
        assertThat(result.message()).contains("「负责人」").contains("重新预览").contains("未执行");
        assertThat(tool.executions.get()).as("按旧预览写入必须为 0 次").isZero();
        assertThat(tool.previews.get()).as("执行前确实重跑了预览").isEqualTo(2);
        assertThat(status(minted)).as("没执行就是确定的失败, 不是结果不明").isEqualTo(TokenStatus.FAILED);
        assertThat(audit(result).getResultCode()).isEqualTo(GatewayResultCode.PREVIEW_STALE.name());
        assertThat(onlyLedgerRow().getState()).isEqualTo(ToolExecutionIdempotencyRecord.State.FAILED);
    }

    @Test
    void gatewayAndLegacyConfirmEntryGiveTheSameStaleVerdictAndWording() throws Exception {
        Minted minted = preview();
        tool.state.put("负责人", "李四");
        IntentPreviewToken row = tokenRepository.findByToken(minted.proof().proofToken()).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> bound = mapper.readValue(row.getPreviewData(), Map.class);
        PreviewStateRecheck.ConfirmStateCheck legacy = (PreviewStateRecheck.ConfirmStateCheck)
                ReflectionTestUtils.invokeMethod(dispatch, "recheckPreviewState", tool, intent(INTENT),
                        row.getCurrentValues(), bound, FACTORY, USER, ROLE);

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(legacy).isNotNull();
        assertThat(legacy.proceed()).isFalse();
        assertThat(result.payload().path("status").asText()).isEqualTo(legacy.responseStatus());
        assertThat(result.payload().path("reason").asText()).isEqualTo(legacy.code());
        assertThat(result.message()).isEqualTo(legacy.message());
        assertThat(tool.executions.get()).isZero();
    }

    @Test
    void preconditionNoLongerHoldsSoGatewayRefusesWithThePreviewReason() {
        Minted minted = preview();
        tool.state.put("状态", "进行中"); // 预览之后别人已经开始了这个任务

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.payload().path("status").asText()).isEqualTo("PREVIEW_STALE");
        assertThat(result.payload().path("reason").asText()).isEqualTo("PRECONDITION_CHANGED");
        assertThat(result.message()).contains("预览之后情况有变化").contains("任务 12 已经开始").contains("未执行");
        assertThat(tool.executions.get()).isZero();
        assertThat(tool.previews.get()).isEqualTo(2);
        assertThat(status(minted)).isEqualTo(TokenStatus.FAILED);
    }

    @Test
    void rereadFailureRefusesWithoutExecutingAndIsNotOutcomeUnknown() {
        Minted minted = preview();
        tool.failPreview = true;

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.payload().path("reason").asText()).isEqualTo("REREAD_FAILED");
        assertThat(result.payload().path("status").asText()).isEqualTo("PREVIEW_STALE");
        assertThat(tool.executions.get()).isZero();
        assertThat(status(minted)).isEqualTo(TokenStatus.FAILED);
    }

    // ==================== 空快照: 白名单豁免 / 其余照常核对 ====================

    @Test
    void whitelistedEmptySnapshotTokenSkipsTheRecheckAndIsCounted() {
        // 画布式签发: 签发方自己跑过一次预览 (网关 PREVIEW 模式), 凭据不记改前值, 类型 CANVAS_DIFF
        ToolExecutionResult issuerPreview = gateway.execute(previewCommand());
        assertThat(issuerPreview.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        Minted minted = mintBound("CANVAS_DIFF", TaskTool.NAME, Map.of());
        long exemptionsBefore = gateway.recheckExemptionCount();

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(tool.previews.get()).as("只有签发方那 1 次预览, 执行前没有再核对").isEqualTo(1);
        assertThat(tool.executions.get()).isEqualTo(1);
        assertThat(gateway.recheckExemptionCount()).as("豁免留了计数").isEqualTo(exemptionsBefore + 1);
        assertThat(status(minted)).isEqualTo(TokenStatus.CONFIRMED);
    }

    @Test
    void createOnlyToolWithEmptySnapshotSkipsTheRecheck() {
        tool.createsNewRecordOnly = true;
        Minted minted = mintBound(TaskTool.NAME, "NEW", Map.of());

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(tool.previews.get()).as("新建型不核对: 执行前 0 次预览").isZero();
        assertThat(tool.executions.get()).isEqualTo(1);
    }

    @Test
    void nonWhitelistedEmptySnapshotIsRefusedAsNotComparableLikeTheLegacyEntry() {
        tool.reportsPriorState = false; // 工具给不出改前值, 凭据也没记 → 无法核对
        Minted minted = mintBound(TaskTool.NAME, "12", Map.of());

        ToolExecutionResult result = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(result.payload().path("status").asText()).isEqualTo("CONFIRM_NOT_SUPPORTED");
        assertThat(result.payload().path("reason").asText()).isEqualTo("NOT_COMPARABLE");
        assertThat(result.payload().path("needsRepreview").asBoolean(true)).isFalse();
        assertThat(result.message()).isEqualTo(PreviewStateRecheck.NOT_COMPARABLE_MESSAGE);
        assertThat(tool.executions.get()).as("无法核对就不执行").isZero();
        assertThat(tool.previews.get()).as("确实跑了执行前核对").isEqualTo(1);
        assertThat(status(minted)).isEqualTo(TokenStatus.FAILED);
        assertThat(audit(result).getResultCode())
                .as("账本结果码与回执口径一致").isEqualTo(GatewayResultCode.CONFIRM_NOT_SUPPORTED.name());
    }

    // ==================== 认领绑业务对象 ====================

    @Test
    void tokenIssuedForAnotherObjectIsNeverClaimedAndStaysPendingForItsOwnObject() {
        Minted minted = preview();
        IntentPreviewToken row = tokenRepository.findByToken(minted.proof().proofToken()).orElseThrow();
        assertThat(row.getEntityType()).isEqualTo(TaskTool.NAME);
        assertThat(row.getEntityId()).isEqualTo("12");

        ToolExecutionResult otherObject = gateway.execute(command(
                withSubject(minted.proof(), TaskTool.NAME, "13"), "idem-" + UUID.randomUUID()));
        ToolExecutionResult otherType = gateway.execute(command(
                withSubject(minted.proof(), "PURCHASE_ORDER", "12"), "idem-" + UUID.randomUUID()));

        assertThat(otherObject.status()).isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        assertThat(otherObject.message()).isEqualTo("Confirmation rejected");
        assertThat(otherType.status()).isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        assertThat(tool.executions.get()).as("换对象 0 次执行").isZero();
        assertThat(status(minted)).as("换对象的尝试不消耗凭据").isEqualTo(TokenStatus.PENDING);

        // 阳性对照: 同一张凭据按它签发时的对象认领, 照常执行
        ToolExecutionResult rightObject = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));
        assertThat(rightObject.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(tool.executions.get()).isEqualTo(1);
    }

    @Test
    void proofThatDoesNotNameItsObjectIsRejectedWithoutConsumingTheToken() {
        Minted minted = preview();
        ConfirmationProof anonymous = new ConfirmationProof(minted.proof().proofToken(),
                minted.proof().commandDigest(), minted.proof().expiresAt());

        ToolExecutionResult result = gateway.execute(command(anonymous, "idem-" + UUID.randomUUID()));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        assertThat(tool.executions.get()).isZero();
        assertThat(status(minted)).isEqualTo(TokenStatus.PENDING);
    }

    // ==================== 同令牌重放不重复写 ====================

    @Test
    void replayingTheSameTokenNeverWritesTwice() {
        Minted minted = preview();
        String idem = "idem-" + UUID.randomUUID();
        ToolExecutionCommand command = command(minted.proof(), idem);

        ToolExecutionResult first = gateway.execute(command);
        ToolExecutionResult sameKey = gateway.execute(command);
        ToolExecutionResult newKey = gateway.execute(command(minted.proof(), "idem-" + UUID.randomUUID()));

        assertThat(first.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(sameKey.status()).isEqualTo(ToolExecutionStatus.IDEMPOTENT_REPLAY);
        assertThat(sameKey.payload().path("originalStatus").asText()).isEqualTo("SUCCEEDED");
        assertThat(newKey.status()).as("换幂等键重放同一张凭据: 认领被拒")
                .isEqualTo(ToolExecutionStatus.CONFIRMATION_REQUIRED);
        assertThat(tool.executions.get()).as("同一张凭据只写一次").isEqualTo(1);
        assertThat(status(minted)).isEqualTo(TokenStatus.CONFIRMED);
    }

    // ==================== helpers ====================

    private record Minted(ConfirmationProof proof) { }

    private Minted preview() {
        AIIntentConfig config = intent(INTENT);
        IntentExecuteResponse preview = dispatch.executeToolPreview(tool, FACTORY,
                IntentExecuteRequest.builder().intentCode(INTENT).userInput("开始任务 12")
                        .context(new java.util.HashMap<>(Map.of("taskId", 12))).previewOnly(true).build(),
                config, USER, ROLE);
        IntentExecuteResponse.ConfirmableAction action = preview.getConfirmableAction();
        assertThat(action).as("写工具预览通过必须铸出凭据").isNotNull();
        IntentPreviewToken row = tokenRepository.findByToken(action.getConfirmToken()).orElseThrow();
        assertThat(row.getCurrentValues()).as("凭据记下了改前值").contains("张三");
        return new Minted(new ConfirmationProof(action.getConfirmToken(), action.getCommandDigest(),
                action.getExpiresAt(), row.getEntityType(), row.getEntityId()));
    }

    /** 不经预览直接签发 (画布 / 自动任务 / AI 建产品那样), 改前值由调用方给。 */
    private Minted mintBound(String entityType, String entityId, Map<String, Object> currentValues) {
        IntentPreviewToken token = previewTokens.createBoundToken(new PreviewTokenService.BoundTokenRequest(
                FACTORY, USER, null, "GW_DIRECT_" + UUID.randomUUID(), "direct issue", TaskTool.NAME, VERSION,
                ToolExecutionMode.EXECUTE, entityType, entityId, "UPDATE", Map.of("taskId", 12),
                currentValues, Map.of(), 300));
        return new Minted(new ConfirmationProof(token.getToken(), token.getCommandDigest(),
                token.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant(), entityType, entityId));
    }

    private ToolExecutionCommand previewCommand() {
        String id = "gw-preview-" + UUID.randomUUID();
        return new ToolExecutionCommand(id, id, id + "-trace", TaskTool.NAME, VERSION,
                mapper.createObjectNode().put("taskId", 12), principal, ToolExecutionSource.AI_CHAT,
                ToolExecutionMode.PREVIEW, Optional.empty(), Optional.empty(), Optional.empty(),
                Instant.now().plusSeconds(30));
    }

    private static ConfirmationProof withSubject(ConfirmationProof proof, String entityType, String entityId) {
        return new ConfirmationProof(proof.proofToken(), proof.commandDigest(), proof.expiresAt(),
                entityType, entityId);
    }

    private ToolExecutionCommand command(ConfirmationProof proof, String idempotencyKey) {
        IntentPreviewToken row = tokenRepository.findByToken(proof.proofToken()).orElseThrow();
        JsonNode parameters;
        try {
            parameters = mapper.readTree(row.getPreviewData());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String id = "gw-" + UUID.randomUUID();
        return new ToolExecutionCommand(id, id, id + "-trace", TaskTool.NAME, VERSION, parameters, principal,
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE, Optional.of(idempotencyKey),
                Optional.of(proof), Optional.empty(), Instant.now().plusSeconds(30));
    }

    private TokenStatus status(Minted minted) {
        return tokenRepository.findByToken(minted.proof().proofToken()).orElseThrow().getStatus();
    }

    private ToolExecutionAuditEvent audit(ToolExecutionResult result) {
        return auditRepository.findById(result.auditEventId()).orElseThrow();
    }

    private ToolExecutionIdempotencyRecord onlyLedgerRow() {
        List<ToolExecutionIdempotencyRecord> rows = idempotencyRepository.findAll();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static AIIntentConfig intent(String code) {
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode(code);
        config.setIntentName("开始任务");
        config.setToolName(TaskTool.NAME);
        return config;
    }

    /** 改动已有记录的写工具: 预览报 currentValues, 执行改状态。对象状态在 {@link #state} 里, 测试可在中途改它。 */
    static final class TaskTool implements ToolExecutor {
        static final String NAME = "task_flip_update";
        final Map<String, Object> state = new ConcurrentHashMap<>(Map.of("状态", "待开始", "负责人", "张三"));
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger previews = new AtomicInteger();
        volatile boolean failPreview;
        volatile boolean createsNewRecordOnly;
        volatile boolean reportsPriorState = true;
        private final ObjectMapper mapper;

        TaskTool(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override public String getToolName() { return NAME; }
        @Override public String getDescription() { return "flip a task"; }
        @Override public String getVersion() { return VERSION; }
        @Override public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of("taskId", Map.of("type", "integer")),
                    "required", List.of("taskId"));
        }
        @Override public boolean supportsPreview() { return true; }
        @Override public ActionType getActionType() { return ActionType.UPDATE; }
        @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }
        @Override public boolean previewCreatesNewRecordOnly() { return createsNewRecordOnly; }

        @Override
        public String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
            previews.incrementAndGet();
            if (failPreview) {
                throw new IllegalStateException("state store unavailable");
            }
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("状态", state.get("状态"));
            current.put("负责人", state.get("负责人"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "PREVIEW");
            data.put("canDo", "待开始".equals(state.get("状态")));
            data.put("message", "待开始".equals(state.get("状态")) ? "确认开始任务 12？" : "任务 12 已经开始。");
            if (reportsPriorState) {
                data.put("currentValues", current);
            }
            data.put("newValues", Map.of("状态", "进行中", "负责人", state.get("负责人")));
            return mapper.writeValueAsString(Map.of("success", true, "data", data));
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            executions.incrementAndGet();
            state.put("状态", "进行中");
            return "{\"success\":true,\"data\":{\"taskId\":12}}";
        }
    }
}
