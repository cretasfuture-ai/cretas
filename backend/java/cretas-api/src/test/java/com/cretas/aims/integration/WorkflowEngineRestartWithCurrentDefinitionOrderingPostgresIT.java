package com.cretas.aims.integration;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance.InstanceStatus;
import com.cretas.aims.repository.workflow.ApprovalHistoryRepository;
import com.cretas.aims.repository.workflow.ApprovalWorkflowInstanceRepository;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.impl.ApprovalWorkflowServiceImpl;
import com.cretas.aims.service.workflow.impl.WorkflowEngineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in PostgreSQL proof of the 缺陷 27 recovery ordering bug (2026-09-01,
 * LIUSHANMEN, {@code PO-20260901-0001}).
 *
 * <p><b>What this reproduces</b>: {@code restartWithCurrentDefinition} calls
 * {@code cancel(instanceId, ...)} (queues an UPDATE on the stranded RUNNING row
 * via {@code instanceRepository.save()}, no flush) and then
 * {@code startWorkflow(...)} (queues an INSERT for the new RUNNING row). Inside
 * one Hibernate flush, {@code ActionQueue} always runs insertions before
 * updates — so the new row is inserted while the old row is <i>still RUNNING in
 * the database</i>, which collides with the partial unique index
 * {@code uq_aw_instances_active} from
 * {@code V20260608_02__unique_active_workflow_instance.sql}
 * ({@code (factory_id, module_code, business_entity_id) WHERE status='RUNNING'}).
 *
 * <p><b>Why a mock-based test cannot catch this</b>: a mocked repository has no
 * partial unique index, so "cancel then insert" can never collide — every
 * existing unit test for this method mocks {@code ApprovalWorkflowInstanceRepository}
 * and was green both before and after the defect existed. This test runs the
 * real {@link WorkflowEngineServiceImpl} against a real Hibernate
 * {@link ApprovalWorkflowInstanceRepository} + {@link ApprovalHistoryRepository}
 * bound to a disposable PostgreSQL database carrying the <b>exact</b> migration
 * file (via {@link ScriptUtils#executeSqlScript}, not a hand-copied second
 * definition — see 硬约束 measurement-and-wiring.md 形态 D).
 *
 * <p>{@link ApprovalWorkflowService} is the only faked collaborator: only its
 * two DB-touching lookups ({@code getByIdForUpdate} /
 * {@code getActiveByDecisionType}) are overridden to return a fixed in-memory
 * workflow definition. JSON (de)serialization (the part that matters for the
 * graph-walk under test) still runs through the real
 * {@link ApprovalWorkflowServiceImpl} implementation via {@code super}.
 *
 * <p>The test method suspends {@code @DataJpaTest}'s implicit per-test rollback
 * transaction ({@code @Transactional(propagation = Propagation.NOT_SUPPORTED)},
 * same pattern as {@code ReportReversalLogRepositoryPostgresConcurrencyIT}) and
 * drives its own {@link TransactionTemplate} — otherwise the pending writes
 * would never actually flush to PostgreSQL and the constraint could never fire.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EnableJpaRepositories(basePackageClasses = {
        ApprovalWorkflowInstanceRepository.class, ApprovalHistoryRepository.class
})
class WorkflowEngineRestartWithCurrentDefinitionOrderingPostgresIT {

    private static final String FACTORY = "WF-PG-F001";
    private static final String MODULE_CODE = "PURCHASE_ORDER";
    private static final String BUSINESS_ENTITY = "PO-PG-VERIFY-0001";
    private static final String START_NODE = "start";
    private static final String APPROVAL_NODE = "approval1";
    private static final Long OPERATOR_USER_ID = 999L;
    private static final Long ORIGINAL_INITIATOR = 5L;
    /** 64 hex chars — deliberately not a real digest, only needs to fail the equality check. */
    private static final String STALE_DIGEST = "0".repeat(64);

    @Autowired private ApprovalWorkflowInstanceRepository instanceRepository;
    @Autowired private ApprovalHistoryRepository historyRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private WorkflowEngineServiceImpl service;
    private ApprovalWorkflow boundWorkflow;
    private String stableDigestContextKey;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    @BeforeEach
    void createExactTargetSchemaAndService() throws Exception {
        jdbcTemplate.execute("DROP TABLE IF EXISTS approval_workflow_instances CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS approval_history CASCADE");
        // Column list mirrors ApprovalWorkflowInstance + BaseEntity exactly (id/factory_id/
        // workflow_id/module_code/business_entity_id/status/current_node_ids/context_json/
        // initiated_by/initiated_at/completed_at/version + created_at/updated_at/deleted_at).
        jdbcTemplate.execute("""
                CREATE TABLE approval_workflow_instances (
                    id VARCHAR(36) PRIMARY KEY,
                    factory_id VARCHAR(50) NOT NULL,
                    workflow_id VARCHAR(36) NOT NULL,
                    module_code VARCHAR(50) NOT NULL,
                    business_entity_id VARCHAR(191) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    current_node_ids JSONB NOT NULL,
                    context_json JSONB NOT NULL,
                    initiated_by BIGINT,
                    initiated_at TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    deleted_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE approval_history (
                    id BIGSERIAL PRIMARY KEY,
                    factory_id VARCHAR(50) NOT NULL,
                    instance_id VARCHAR(36) NOT NULL,
                    node_id VARCHAR(50) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    actor_id BIGINT,
                    actor_role VARCHAR(50),
                    notes TEXT,
                    duration_seconds INTEGER,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        // 🔴 与 prod 同一份 migration 文件建 partial unique index —— 不手写第二份定义
        // (两份定义一定会漂, 见 measurement-and-wiring.md 形态 D)。这正是缺陷 27 恢复
        // 撞上的那个索引: uq_aw_instances_active。
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/flyway/V20260608_02__unique_active_workflow_instance.sql"));
        }

        ObjectMapper objectMapper = new ObjectMapper();
        List<ApprovalWorkflowNode> nodes = List.of(
                ApprovalWorkflowNode.builder().id(START_NODE).type("start").build(),
                ApprovalWorkflowNode.builder().id(APPROVAL_NODE).type("approval").build());
        List<ApprovalWorkflowEdge> edges = List.of(
                ApprovalWorkflowEdge.builder().id("e1").source(START_NODE).target(APPROVAL_NODE).build());

        boundWorkflow = ApprovalWorkflow.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(FACTORY)
                .decisionType(DecisionType.PURCHASE_ORDER_APPROVAL)
                .name("PG IT ordering-bug workflow")
                .nodesJson(objectMapper.writeValueAsString(nodes))
                .edgesJson(objectMapper.writeValueAsString(edges))
                .startNodeId(START_NODE)
                .version(1)
                .publishStatus("published")
                .enabled(true)
                .build();

        // Only the two DB-touching lookups are faked. deserializeNodes/deserializeEdges
        // (the part that actually matters for the graph walk under test) run through the
        // real implementation via super — this is not a mock of the behavior under test,
        // it stands in for an unrelated collaborator (workflow *definition* storage), same
        // pattern as FactoryConfigServiceImplReorderIT's "optional deps stay null".
        ApprovalWorkflowService workflowServiceStub = new ApprovalWorkflowServiceImpl(null, objectMapper) {
            @Override
            public Optional<ApprovalWorkflow> getByIdForUpdate(String factoryId, String id) {
                return Optional.of(boundWorkflow);
            }

            @Override
            public Optional<ApprovalWorkflow> getActiveByDecisionType(String factoryId, DecisionType decisionType) {
                return Optional.of(boundWorkflow);
            }
        };

        service = new WorkflowEngineServiceImpl(
                instanceRepository, historyRepository, workflowServiceStub, null, null);

        // Read the private context key via reflection rather than hand-copying the literal —
        // if the constant is ever renamed, this test fails loudly with WORKFLOW_REBIND_NOT_STALE
        // instead of silently drifting (measurement-and-wiring.md 硬约束 8).
        stableDigestContextKey = readPrivateStaticString(
                WorkflowEngineServiceImpl.class, "STABLE_DEFINITION_DIGEST_CONTEXT_KEY");
    }

    private static String readPrivateStaticString(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /** Seeds the stranded RUNNING instance exactly like the production incident. */
    private String seedStrandedRunningInstance() {
        Map<String, Object> context = new HashMap<>();
        // Deliberately NOT equal to the real digest of boundWorkflow — this is what makes
        // restartWithCurrentDefinition's staleness gate agree the definition "changed" and
        // proceed past WORKFLOW_REBIND_NOT_STALE into the cancel-then-restart path.
        context.put(stableDigestContextKey, STALE_DIGEST);

        ApprovalWorkflowInstance stranded = ApprovalWorkflowInstance.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(FACTORY)
                .workflowId(boundWorkflow.getId())
                .moduleCode(MODULE_CODE)
                .businessEntityId(BUSINESS_ENTITY)
                .status(InstanceStatus.RUNNING)
                .currentNodeIds(new java.util.ArrayList<>(List.of(APPROVAL_NODE)))
                .contextJson(context)
                .initiatedBy(ORIGINAL_INITIATOR)
                .initiatedAt(LocalDateTime.now().minusDays(1))
                .build();
        return instanceRepository.saveAndFlush(stranded).getId();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void restartWithCurrentDefinition_doesNotCollideWithThePartialUniqueIndex() {
        String strandedId = seedStrandedRunningInstance();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        ApprovalWorkflowInstance restarted = txTemplate.execute(status ->
                service.restartWithCurrentDefinition(
                        FACTORY, strandedId, OPERATOR_USER_ID,
                        "PG IT 复现缺陷 27 恢复顺序 bug"));

        assertThat(restarted).isNotNull();
        assertThat(restarted.getId()).isNotEqualTo(strandedId);
        assertThat(restarted.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(restarted.getContextJson()).containsKey("approvalRebind");
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) restarted.getContextJson().get("approvalRebind");
        assertThat(audit.get("previousInstanceId")).isEqualTo(strandedId);

        // Exactly one RUNNING instance for the document throughout (requirement #3) —
        // the old row is CANCELLED, not deleted; history is preserved.
        List<ApprovalWorkflowInstance> allForDocument = instanceRepository.findAll().stream()
                .filter(i -> FACTORY.equals(i.getFactoryId())
                        && MODULE_CODE.equals(i.getModuleCode())
                        && BUSINESS_ENTITY.equals(i.getBusinessEntityId()))
                .toList();
        assertThat(allForDocument).hasSize(2);
        assertThat(allForDocument).filteredOn(i -> i.getStatus() == InstanceStatus.RUNNING).hasSize(1);
        assertThat(allForDocument).filteredOn(i -> i.getStatus() == InstanceStatus.CANCELLED).hasSize(1);

        ApprovalWorkflowInstance oldRow = instanceRepository.findById(strandedId).orElseThrow();
        assertThat(oldRow.getStatus()).isEqualTo(InstanceStatus.CANCELLED);
        assertThat(oldRow.getCompletedAt()).isNotNull();
    }

    /**
     * Independent DB-level proof the partial unique index is really the mechanism (and that
     * the test schema faithfully reproduces the production migration): a raw two-row insert
     * attempt through the same repository, same ordering, same transaction, with NO
     * intervening flush, must collide exactly like the production incident did.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancelThenInsertWithoutAnInterveningFlush_collidesOnTheIndex_evenOutsideTheService() {
        String strandedId = seedStrandedRunningInstance();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            ApprovalWorkflowInstance old = instanceRepository.findById(strandedId).orElseThrow();
            old.setStatus(InstanceStatus.CANCELLED);
            old.setCompletedAt(LocalDateTime.now());
            instanceRepository.save(old); // queued UPDATE, not flushed

            ApprovalWorkflowInstance fresh = ApprovalWorkflowInstance.builder()
                    .id(UUID.randomUUID().toString())
                    .factoryId(FACTORY)
                    .workflowId(boundWorkflow.getId())
                    .moduleCode(MODULE_CODE)
                    .businessEntityId(BUSINESS_ENTITY)
                    .status(InstanceStatus.RUNNING)
                    .currentNodeIds(List.of(APPROVAL_NODE))
                    .contextJson(Map.of())
                    .initiatedBy(ORIGINAL_INITIATOR)
                    .initiatedAt(LocalDateTime.now())
                    .build();
            return instanceRepository.save(fresh); // queued INSERT — flushes before the UPDATE
        })).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_aw_instances_active");
    }
}
