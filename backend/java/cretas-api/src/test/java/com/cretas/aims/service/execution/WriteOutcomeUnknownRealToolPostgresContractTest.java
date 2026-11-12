package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.ToolWriteCommitObserver;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.exception.BusinessException;
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
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写入确认三洞 #2 用<b>真实的 {@link AbstractBusinessTool} 子类</b>验: 工具里第一步已经提交 (真实事务管理器、
 * 真实提交), 第二步抛异常 —— AbstractBusinessTool 会把异常吞成 {@code {success:false}}。以前确认流程据此记
 * 「失败」, 用户重做就写两遍。现在全链真实 (预览铸凭据 → confirm → 真实 executeWithTool → 工具 execute):
 * 凭据必须记 OUTCOME_UNKNOWN, 重放不再写; 阴性对照: 业务规则在写之前就拒绝 → 记 FAILED, 一行没写。
 *
 * <p>设了 {@code CRETAS_WORKFLOW_PG_URL} 时跑真实 PostgreSQL, 否则 H2。</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = IntentPreviewToken.class)
@EnableJpaRepositories(basePackageClasses = IntentPreviewTokenRepository.class)
@Import({PreviewTokenServiceImpl.class, WriteOutcomeUnknownRealToolPostgresContractTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WriteOutcomeUnknownRealToolPostgresContractTest {

    private static final String FACTORY = "F001";
    private static final long USER = 41L;
    private static final String ROLE = "FACTORY_ADMIN";
    private static final String INTENT = "STOCK_OUT";

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
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:write_outcome_unknown;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
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
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper mapper;

    private StockOutTool tool;
    private ToolDispatchService dispatch;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // 生产上 PrimaryDataSourceConfig 给事务管理器挂上提交观察者; 测试上下文的事务管理器由 Boot 自动配置, 这里同样挂上
        JpaTransactionManager jpa = (JpaTransactionManager) transactionManager;
        if (!jpa.getTransactionExecutionListeners().contains(ToolWriteCommitObserver.INSTANCE)) {
            jpa.addListener(ToolWriteCommitObserver.INSTANCE);
        }
        repository.deleteAll();
        jdbc.execute("CREATE TABLE IF NOT EXISTS confirm_test_ledger (note VARCHAR(64))");
        jdbc.execute("DELETE FROM confirm_test_ledger");

        tool = new StockOutTool(new TransactionTemplate(transactionManager), jdbc);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor(StockOutTool.NAME)).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        dispatch = new ToolDispatchService(registry, mapper, null, redundancy,
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokens);
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);

        AIIntentService intents = mock(AIIntentService.class);
        when(intents.getIntentByCode(FACTORY, INTENT)).thenReturn(Optional.of(intent()));
        when(intents.hasPermission(INTENT, ROLE)).thenReturn(true);
        orchestrator = new IntentExecutionOrchestrator(
                intents, mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class),
                mock(ConversationMemoryService.class), mapper, null,
                mock(DashScopeConfig.class), mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class), registry, mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                dispatch, mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", previewTokens);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
    }

    // ==================== 工具层: 真实子类 + 真实提交 ====================

    @Test
    void firstStepCommittedThenSecondStepThrewIsFlaggedPossiblyWritten() throws Exception {
        tool.mode = Mode.COMMIT_THEN_FAIL;
        Map<String, Object> result = execute();
        assertThat(result).containsEntry("success", false).containsEntry(AbstractBusinessTool.OUTCOME_UNKNOWN_FLAG, true);
        assertThat(ledgerRows()).as("第一步真的提交了").isEqualTo(1);

        tool.mode = Mode.COMMIT_THEN_BUSINESS_REJECT;
        assertThat(execute()).as("提交之后的业务异常也不是「确定没写」")
                .containsEntry(AbstractBusinessTool.OUTCOME_UNKNOWN_FLAG, true);
        assertThat(ledgerRows()).isEqualTo(2);
    }

    @Test
    void businessRejectionBeforeAnyWriteStaysADefiniteFailure() throws Exception {
        tool.mode = Mode.REJECT_BEFORE_WRITE;
        Map<String, Object> result = execute();
        assertThat(result).containsEntry("success", false).doesNotContainKey(AbstractBusinessTool.OUTCOME_UNKNOWN_FLAG);
        assertThat(ledgerRows()).isZero();

        // 缺参数在业务逻辑开始前就返回: 也是确定没写
        Map<String, Object> missing = mapper.readValue(tool.execute(ToolCall.of("c", StockOutTool.NAME, "{}"),
                principal()), Map.class);
        assertThat(missing).doesNotContainKey(AbstractBusinessTool.OUTCOME_UNKNOWN_FLAG);
    }

    @Test
    void unexpectedExceptionAfterBusinessStartedIsTreatedAsPossiblyWrittenEvenWithoutACommit() throws Exception {
        // 外部副作用 / 不经事务管理器的写入数不到: 意外异常一律按可能已写 (宁可让用户先核对)
        tool.mode = Mode.UNEXPECTED_BEFORE_WRITE;
        assertThat(execute()).containsEntry(AbstractBusinessTool.OUTCOME_UNKNOWN_FLAG, true);
        assertThat(ledgerRows()).isZero();
    }

    // ==================== 全链: 预览 → confirm → 真实 executeWithTool → 真实工具 ====================

    @Test
    void confirmOfAToolThatCommittedThenFailedRecordsOutcomeUnknownAndNeverWritesTwice() {
        ConfirmationProof proof = preview();
        tool.mode = Mode.COMMIT_THEN_FAIL;

        IntentExecuteResponse first = orchestrator.confirm(FACTORY, proof, USER, ROLE);

        assertThat(first.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(status(proof)).isEqualTo(TokenStatus.OUTCOME_UNKNOWN);
        assertThat(ledgerRows()).isEqualTo(1);

        IntentExecuteResponse replay = orchestrator.confirm(FACTORY, proof, USER, ROLE);
        assertThat(replay.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(ledgerRows()).as("重放不再写").isEqualTo(1);
        assertThat(tool.executions).isEqualTo(1);
    }

    @Test
    void confirmOfAToolRejectedBeforeWritingRecordsFailedAndWritesNothing() {
        ConfirmationProof proof = preview();
        tool.mode = Mode.REJECT_BEFORE_WRITE;

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof, USER, ROLE);

        assertThat(response.getStatus()).isNotEqualTo("OUTCOME_UNKNOWN").isNotEqualTo("SUCCESS");
        assertThat(status(proof)).isEqualTo(TokenStatus.FAILED);
        assertThat(ledgerRows()).isZero();
        assertThat(tool.executions).isEqualTo(1);
    }

    // ==================== helpers ====================

    private Map<String, Object> execute() throws Exception {
        return mapper.readValue(tool.execute(ToolCall.of("c", StockOutTool.NAME, "{\"batchId\":\"MB-1\"}"),
                principal()), Map.class);
    }

    private static Map<String, Object> principal() {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", FACTORY);
        context.put("userId", USER);
        return context;
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM confirm_test_ledger", Integer.class);
    }

    private ConfirmationProof preview() {
        IntentExecuteResponse preview = dispatch.executeToolPreview(tool, FACTORY,
                IntentExecuteRequest.builder().intentCode(INTENT).userInput("批次 MB-1 出库")
                        .context(new HashMap<>(Map.of("batchId", "MB-1"))).previewOnly(true).build(),
                intent(), USER, ROLE);
        IntentExecuteResponse.ConfirmableAction action = preview.getConfirmableAction();
        assertThat(action).as("真实写工具预览通过必须铸出凭据").isNotNull();
        return new ConfirmationProof(action.getConfirmToken(), action.getCommandDigest(), action.getExpiresAt());
    }

    private TokenStatus status(ConfirmationProof proof) {
        return repository.findByToken(proof.proofToken()).orElseThrow().getStatus();
    }

    private static AIIntentConfig intent() {
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode(INTENT);
        config.setIntentName("批次出库");
        config.setToolName(StockOutTool.NAME);
        return config;
    }

    enum Mode { OK, COMMIT_THEN_FAIL, COMMIT_THEN_BUSINESS_REJECT, REJECT_BEFORE_WRITE, UNEXPECTED_BEFORE_WRITE }

    /** 真实的 AbstractBusinessTool 子类: 第一步走真实事务提交一行台账, 第二步按 mode 出错。 */
    static final class StockOutTool extends AbstractBusinessTool {
        static final String NAME = "stock_out_update";
        private final TransactionTemplate tx;
        private final JdbcTemplate jdbc;
        volatile Mode mode = Mode.OK;
        volatile int executions;

        StockOutTool(TransactionTemplate tx, JdbcTemplate jdbc) {
            this.tx = tx;
            this.jdbc = jdbc;
        }

        @Override public String getToolName() { return NAME; }
        @Override public String getDescription() { return "批次出库"; }
        @Override public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of("batchId", Map.of("type", "string")),
                    "required", List.of("batchId"));
        }
        @Override protected List<String> getRequiredParameters() { return List.of("batchId"); }
        @Override public boolean supportsPreview() { return true; }
        @Override public ActionType getActionType() { return ActionType.UPDATE; }
        @Override public AccessMode getAccessMode() { return AccessMode.WRITE; }

        @Override
        protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                                Map<String, Object> context) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("批次", params.get("batchId"));
            current.put("已出库笔数", jdbc.queryForObject("SELECT count(*) FROM confirm_test_ledger", Integer.class));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "PREVIEW");
            result.put("canDo", true);
            result.put("currentValues", current);
            return result;
        }

        @Override
        protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                                Map<String, Object> context) {
            executions++;
            if (mode == Mode.REJECT_BEFORE_WRITE) {
                throw new BusinessException(409, "库存不足，未出库");
            }
            if (mode == Mode.UNEXPECTED_BEFORE_WRITE) {
                throw new IllegalStateException("downstream connection reset");
            }
            tx.executeWithoutResult(status -> jdbc.update("INSERT INTO confirm_test_ledger (note) VALUES ('out')"));
            if (mode == Mode.COMMIT_THEN_FAIL) {
                throw new IllegalStateException("second step: ledger posting service timed out");
            }
            if (mode == Mode.COMMIT_THEN_BUSINESS_REJECT) {
                throw new BusinessException(500, "第二步记账失败");
            }
            return Map.of("出库", "完成");
        }
    }
}
