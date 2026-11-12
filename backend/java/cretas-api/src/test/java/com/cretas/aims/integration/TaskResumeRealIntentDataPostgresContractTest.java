package com.cretas.aims.integration;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import com.cretas.aims.service.execution.ConversationTaskStateService;
import com.cretas.aims.service.execution.TaskContinuationRule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTENT-DISTILL-20261111 任务状态跟进: 「继续报工」在生产上能恢复挂起的报工任务, 靠的是库里真实的意图配置,
 * 不是测试桩。一次性 schema 里按 prod 基线建 ai_intent_configs, 原样跑写入报工意图的两条迁移
 * (V20260516_07 建 WORK_PROCESS_TASK_COMPLETE「完成工序」, V20261101_48 给它加关键词「报工」),
 * 从库里读出名称 + 关键词, 经生产同一个 {@link ConversationTaskStateService#namesOf} 交给
 * {@link TaskContinuationRule#resumeTarget}。短语表故意给空: 恢复只能靠名称/关键词这一条路。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class TaskResumeRealIntentDataPostgresContractTest {

    private static final String REPORT = "WORK_PROCESS_TASK_COMPLETE";
    private static final String[] MIGRATIONS = {
            "V20260516_07__work_process_intents.sql",
            "V20261101_48__factory_intent_descriptions_in_frontline_words.sql",
    };

    /** ops/db-baseline/prod-schema-baseline.sql 的 ai_intent_configs, 去掉 public. 与 embedding vector 列, 补主键与 intent_code 唯一。 */
    private static final String BASELINE_TABLE = """
            CREATE TABLE ai_intent_configs (
                id character varying(36) NOT NULL PRIMARY KEY,
                created_at timestamp without time zone NOT NULL,
                deleted_at timestamp without time zone,
                updated_at timestamp without time zone NOT NULL,
                approval_chain_id character varying(36),
                cache_ttl_minutes integer,
                description character varying(500),
                handler_class character varying(200),
                intent_category character varying(50),
                intent_code character varying(50) NOT NULL UNIQUE,
                intent_name character varying(100) NOT NULL,
                is_active boolean,
                keywords jsonb,
                max_tokens integer,
                metadata jsonb,
                priority integer,
                quota_cost integer,
                regex_pattern character varying(500),
                required_roles jsonb,
                requires_approval boolean,
                response_template text,
                sensitivity_level character varying(20),
                factory_id character varying(50),
                config_version integer,
                previous_snapshot jsonb,
                semantic_action character varying(30),
                semantic_domain character varying(30),
                semantic_object character varying(50),
                semantic_path character varying(100),
                tool_name character varying(100),
                negative_keyword_penalty integer,
                negative_keywords jsonb,
                example_queries jsonb,
                negative_examples jsonb,
                analysis_service character varying(64),
                method_name character varying(64),
                confidence_threshold double precision DEFAULT '0.6'::double precision,
                patterns jsonb,
                examples jsonb,
                follow_up_questions jsonb,
                chart_type character varying(50),
                confidence_boost numeric(3,2) DEFAULT '0'::double precision,
                required_entities text,
                business_type character varying(30) DEFAULT 'COMMON'::character varying,
                required_permission character varying(64)
            )
            """;

    @Test
    void continueReportingResumesTheSuspendedReportTaskFromRealIntentConfig() throws Exception {
        String schema = "task_resume_real_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        String user = requiredEnv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        Path migrationDir = Files.createTempDirectory("task-resume-real-intents-");
        Path location = migrationDir.resolve("db/flyway");
        Files.createDirectories(location);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            Files.writeString(location.resolve("V20260101_01__ai_intent_configs_baseline.sql"), BASELINE_TABLE);
            for (String migration : MIGRATIONS) {
                byte[] sql = new ClassPathResource("db/flyway/" + migration).getInputStream().readAllBytes();
                Files.write(location.resolve(migration), sql);
            }
            Flyway flyway = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:" + location.toAbsolutePath()).outOfOrder(false).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);

            AIIntentConfig report = load(connection, schema, REPORT);
            AIIntentConfig spawn = load(connection, schema, "WORK_PROCESS_TASK_SPAWN");
            List<String> reportNames = ConversationTaskStateService.namesOf(report);
            System.out.println("[TaskResumeRealIntentData] " + REPORT + " namesOf=" + reportNames);
            assertThat(reportNames).as("名称在前").first().isEqualTo("完成工序");
            assertThat(reportNames).as("V20261101_48 加的关键词").contains("报工");

            Function<String, List<String>> intentNames = code -> switch (code) {
                case REPORT -> reportNames;
                case "WORK_PROCESS_TASK_SPAWN" -> ConversationTaskStateService.namesOf(spawn);
                default -> List.of();
            };
            Function<String, Optional<String>> noPhraseTable = s -> Optional.empty();

            ConversationSession reportTask = session(REPORT);
            ConversationSession spawnTask = session("WORK_PROCESS_TASK_SPAWN");
            List<ConversationSession> stack = List.of(spawnTask, reportTask); // 栈顶在前: 报工不在栈顶

            assertThat(TaskContinuationRule.resumeTarget("继续报工", stack, noPhraseTable, intentNames))
                    .as("「继续报工」→ 挂着的报工任务 (不是栈顶)").contains(reportTask);
            assertThat(TaskContinuationRule.resumeTarget("回到刚才的报工", stack, noPhraseTable, intentNames))
                    .contains(reportTask);
            assertThat(TaskContinuationRule.resumeTarget("继续完成工序", stack, noPhraseTable, intentNames))
                    .contains(reportTask);
            // 阴性对照: 名称/关键词都对不上的不恢复; 带数字的顺带值不恢复
            assertThat(TaskContinuationRule.resumeTarget("继续盘点", stack, noPhraseTable, intentNames)).isEmpty();
            assertThat(TaskContinuationRule.resumeTarget("继续报工1830", stack, noPhraseTable, intentNames)).isEmpty();

            // 报工正问着时说「继续报工」: 只重问, 不挂起、不另起 (REPEAT 分支)
            ConversationTaskStateService service = new ConversationTaskStateService(null); // classify 不碰仓库
            org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);
            assertThat(service.classify(reportTask, "继续报工", s -> true, noPhraseTable, intentNames))
                    .isEqualTo(TaskContinuationRule.Action.REPEAT);
            assertThat(service.classify(reportTask, "先看下库存", s -> true, noPhraseTable, intentNames))
                    .isEqualTo(TaskContinuationRule.Action.INTERRUPT);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            try (var paths = Files.walk(migrationDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException error) { throw new IllegalStateException(error); }
                });
            }
        }
    }

    private static AIIntentConfig load(Connection connection, String schema, String code) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("SELECT intent_name, keywords::text FROM " + schema
                + ".ai_intent_configs WHERE intent_code = ? AND deleted_at IS NULL")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(code + " 由迁移写入").isTrue();
                AIIntentConfig config = new AIIntentConfig();
                config.setIntentCode(code);
                config.setIntentName(rs.getString(1));
                config.setKeywords(rs.getString(2));
                return config;
            }
        }
    }

    private static ConversationSession session(String intent) {
        ConversationSession s = ConversationSession.createForParameterCollection("F006", 1686L, intent,
                new ArrayList<>(List.of(RequiredParameter.builder().name("taskId").label("任务号").type("INTEGER").build())),
                "请提供任务号");
        s.setSessionId(UUID.randomUUID().toString());
        s.setCollectedParameters(new LinkedHashMap<>());
        s.setLastActiveAt(LocalDateTime.now());
        return s;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
