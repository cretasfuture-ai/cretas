package com.cretas.aims.integration;

import com.cretas.aims.dto.scheduling.WorkerAssignmentDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.service.FeatureEngineeringService;
import com.cretas.aims.service.LinUCBService;
import com.cretas.aims.service.scheduling.impl.WorkerAssignmentServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [PROTO-STALE-TRIGGERS] 10 张没有 updated_at 列却挂着 update_timestamp() 触发器的表, 真 PostgreSQL + 真 Hibernate。
 *
 * <p>按 prod 实况 (2026-11-19 只读核对) 在这 10 张表上挂旧触发器 (表里没有 updated_at):
 * <ol>
 *   <li>阳性对照: 迁移前每张表 UPDATE 都失败 (10/10), 签到也失败且状态没落库;</li>
 *   <li>执行迁移文件本身 (V20261120_99, 读 classpath 原文, 跑两遍证明幂等) 后每张表 UPDATE 成功 (10/10),
 *       有 updated_at 列的表 (line_schedules) 上的同名触发器与共用函数原样保留;</li>
 *   <li>业务路径: {@link WorkerAssignmentServiceImpl#workerCheckIn} / {@link WorkerAssignmentServiceImpl#workerCheckOut}
 *       迁移后成功落库。</li>
 * </ol>
 *
 * <p>与其它 *PostgresContractTest 一致, 由 CRETAS_WORKFLOW_PG_VERIFY=true 打开,
 * 跑在 postgres-reversal-concurrency 车道 (scripts/ci/java-gate-pg-tests.txt)。
 */
@SpringBootTest(classes = StaleUpdateTimestampTriggerMigrationPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class StaleUpdateTimestampTriggerMigrationPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261120_99__drop_stale_update_timestamp_triggers.sql";

    /** prod 2026-11-19 只读核对出的 10 张表 (scheduling_alerts 由 V20261120_97 处理, 不在此列)。 */
    private static final List<String> TABLES = List.of(
            "ai_intent_config_history", "ai_usage_log", "form_template_versions", "iot_device_data",
            "isapi_event_logs", "model_performance_log", "scheduling_predictions", "system_logs",
            "tool_call_cache", "worker_assignments");

    /** 阴性对照表: 有 updated_at 列, 触发器是好的, 迁移不能动它。 */
    private static final String CONTROL_TABLE = "line_schedules";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import(WorkerAssignmentServiceImpl.class)
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @MockBean FeatureEngineeringService featureEngineeringService;
    @MockBean LinUCBService linUCBService;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager em;
    @Autowired WorkerAssignmentServiceImpl workerAssignmentService;

    private TransactionTemplate tx;
    private String factory;
    private String deviceId;
    private String scheduleId;
    private String assignmentId;

    @BeforeEach
    void committedFixture() {
        tx = new TransactionTemplate(transactionManager);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        factory = "ST-" + tag;
        tx.executeWithoutResult(s -> {
            Factory f = new Factory();
            f.setId(factory);
            f.setName(factory);
            em.persist(f);
            User u = new User();
            u.setFactoryId(factory);
            u.setUsername("st-" + UUID.randomUUID());
            u.setPasswordHash("unused-test-fixture");
            u.setRoleCode("operator");
            u.setIsActive(true);
            em.persist(u);
            ProductionLine line = new ProductionLine();
            line.setFactoryId(factory);
            line.setName("一号线");
            line.setStatus(ProductionLine.LineStatus.active);
            line.setHourlyCapacity(new BigDecimal("100"));
            em.persist(line);
            em.flush();
            SchedulingPlan plan = new SchedulingPlan();
            plan.setFactoryId(factory);
            plan.setPlanDate(LocalDate.of(2026, 12, 22));
            plan.setPlanName(factory);
            plan.setStatus(SchedulingPlan.PlanStatus.confirmed);
            em.persist(plan);
            LineSchedule row = new LineSchedule();
            row.setPlan(plan);
            row.setProductionLineId(line.getId());
            row.setStatus(LineSchedule.ScheduleStatus.pending);
            row.setPlannedStartTime(plan.getPlanDate().atTime(8, 0));
            row.setPlannedEndTime(plan.getPlanDate().atTime(12, 0));
            row.setPlannedQuantity(10);
            em.persist(row);
            em.flush();
            WorkerAssignment a = new WorkerAssignment();
            a.setSchedule(row);
            a.setUserId(u.getId());
            a.setStatus(WorkerAssignment.AssignmentStatus.assigned);
            em.persist(a);
            em.flush();
            scheduleId = row.getId();
            assignmentId = a.getId();
        });
        // Hibernate create-drop 建不出 tool_call_cache: 实体的唯一约束名 uk_cache_key 与另一实体撞名
        // (日志: relation "uk_cache_key" already exists), 只是测试库的 DDL 生成问题, prod 表由 SQL 迁移建、已存在。
        // 这里按 prod 列 (2026-11-19 只读核对) 补建, 约束不命名; 表已在则什么也不做。
        jdbc.execute("CREATE TABLE IF NOT EXISTS tool_call_cache (id bigserial primary key, cache_key varchar(256) not null "
                + "unique, session_id varchar(128) not null, tool_name varchar(128) not null, parameters_hash varchar(64) "
                + "not null, cached_result jsonb, original_call_id bigint not null, expires_at timestamp(6) not null, "
                + "hit_count integer, created_at timestamp(6))");
        deviceId = "ST-DEV-" + tag;
        jdbc.update("insert into isapi_devices (id, factory_id, device_name, device_type, ip_address, port, protocol, "
                + "username, password_encrypted, channel_count, status, created_at, updated_at) "
                + "values (?, ?, 'cam', 'IPC', '127.0.0.1', 80, 'HTTP', 'u', 'p', 1, 'UNKNOWN', now(), now())",
                deviceId, factory);
    }

    @AfterEach
    void dropReproducedTriggers() {
        for (String t : TABLES) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + t + "_update_timestamp ON " + t);
        }
        jdbc.execute("DROP TRIGGER IF EXISTS " + CONTROL_TABLE + "_update_timestamp ON " + CONTROL_TABLE);
    }

    // ==================== 迁移前: 阳性对照 ====================

    @Test
    void everyListedTableRejectsUpdateBeforeTheMigration() {
        installProdTriggers();
        Map<String, String> failures = new LinkedHashMap<>();
        for (String t : TABLES) {
            String id = insertRow(t);
            try {
                touch(t, id);
            } catch (RuntimeException e) {
                failures.put(t, rootMessage(e));
            }
        }
        System.out.println("STALE_TRIGGER_BEFORE failed=" + failures.size() + "/" + TABLES.size() + " " + failures.keySet());
        assertThat(failures.keySet()).containsExactlyElementsOf(TABLES);
        assertThat(failures.values()).allSatisfy(m -> assertThat(m).contains("updated_at"));
    }

    @Test
    void workerCheckInFailsOnTheProdTriggerShapeBeforeTheMigration() {
        installProdTriggers();

        assertThatThrownBy(() -> workerAssignmentService.workerCheckIn(factory, assignmentId))
                .satisfies(e -> assertThat(rootMessage(e)).contains("updated_at"));
        assertThat(jdbc.queryForObject("select status from worker_assignments where id = ?", String.class, assignmentId))
                .isEqualTo("assigned");
    }

    // ==================== 迁移后 ====================

    @Test
    void afterRunningTheMigrationFileTwiceEveryListedTableAcceptsUpdates() throws IOException {
        installProdTriggers();
        int controlBefore = triggerCount(CONTROL_TABLE);
        assertThat(staleTriggerCount()).as("迁移前 10 张表都挂着触发器").isEqualTo(TABLES.size());

        runMigrationTwice();

        assertThat(staleTriggerCount()).as("迁移后 10 张表上的 update_timestamp 触发器全部摘除").isZero();
        assertThat(triggerCount(CONTROL_TABLE)).as("有 updated_at 的表上的触发器原样保留").isEqualTo(controlBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_proc where proname = 'update_timestamp'", Integer.class))
                .as("共用函数不动").isEqualTo(1);

        List<String> updated = new ArrayList<>();
        for (String t : TABLES) {
            String id = insertRow(t);
            assertThat(touch(t, id)).as(t + " UPDATE 命中 1 行").isEqualTo(1);
            updated.add(t);
        }
        System.out.println("STALE_TRIGGER_AFTER updated=" + updated.size() + "/" + TABLES.size() + " " + updated);
        assertThat(updated).containsExactlyElementsOf(TABLES);

        // 阴性对照的触发器仍然生效: UPDATE line_schedules 会把 updated_at 刷新
        jdbc.update("update line_schedules set updated_at = timestamp '2000-01-01' where id = ?", scheduleId);
        assertThat(jdbc.queryForObject("select updated_at > timestamp '2000-01-02' from line_schedules where id = ?",
                Boolean.class, scheduleId)).as("line_schedules 的触发器仍在改写 updated_at").isTrue();
    }

    @Test
    void workerCheckInAndCheckOutPersistAfterTheMigration() throws IOException {
        installProdTriggers();
        runMigrationTwice();

        WorkerAssignmentDTO in = workerAssignmentService.workerCheckIn(factory, assignmentId);
        assertThat(in.getStatus()).isEqualTo("checked_in");
        Map<String, Object> afterIn = jdbc.queryForMap(
                "select status, actual_start_time from worker_assignments where id = ?", assignmentId);
        System.out.println("STALE_TRIGGER_CHECKIN " + afterIn);
        assertThat(afterIn.get("status")).isEqualTo("checked_in");
        assertThat(afterIn.get("actual_start_time")).isNotNull();

        WorkerAssignmentDTO out = workerAssignmentService.workerCheckOut(factory, assignmentId, 90);
        assertThat(out.getStatus()).isEqualTo("checked_out");
        Map<String, Object> afterOut = jdbc.queryForMap(
                "select status, actual_end_time, performance_score from worker_assignments where id = ?", assignmentId);
        System.out.println("STALE_TRIGGER_CHECKOUT " + afterOut);
        assertThat(afterOut.get("status")).isEqualTo("checked_out");
        assertThat(afterOut.get("actual_end_time")).isNotNull();
        assertThat(((Number) afterOut.get("performance_score")).intValue()).isEqualTo(90);
    }

    // ==================== fixtures ====================

    /** prod 实况: 共用函数写 NEW.updated_at, 10 张表上挂着它 (而这些表没有 updated_at); 另在有该列的对照表上也挂一条。 */
    private void installProdTriggers() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION update_timestamp() RETURNS TRIGGER AS $$
                BEGIN NEW.updated_at = CURRENT_TIMESTAMP; RETURN NEW; END; $$ LANGUAGE plpgsql""");
        for (String t : TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = 'public' "
                    + "and table_name = ? and column_name = 'updated_at'", Integer.class, t))
                    .as("与 prod 一致: " + t + " 没有 updated_at 列").isZero();
            jdbc.execute("DROP TRIGGER IF EXISTS " + t + "_update_timestamp ON " + t);
            jdbc.execute("CREATE TRIGGER " + t + "_update_timestamp BEFORE UPDATE ON " + t
                    + " FOR EACH ROW EXECUTE FUNCTION update_timestamp()");
        }
        jdbc.execute("DROP TRIGGER IF EXISTS " + CONTROL_TABLE + "_update_timestamp ON " + CONTROL_TABLE);
        jdbc.execute("CREATE TRIGGER " + CONTROL_TABLE + "_update_timestamp BEFORE UPDATE ON " + CONTROL_TABLE
                + " FOR EACH ROW EXECUTE FUNCTION update_timestamp()");
    }

    /** 执行迁移文件本身 (classpath 原文), 两遍: 第二遍必须什么也不做且不报错。 */
    private void runMigrationTwice() throws IOException {
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.out.println("STALE_TRIGGER_MIGRATION bytes=" + sql.length());
        jdbc.execute(sql);
        jdbc.execute(sql);
    }

    private int staleTriggerCount() {
        return jdbc.queryForObject("select count(*) from pg_trigger t join pg_class c on c.oid = t.tgrelid "
                + "join pg_proc p on p.oid = t.tgfoid where not t.tgisinternal and p.proname = 'update_timestamp' "
                + "and c.relname = any(?::text[])", Integer.class, "{" + String.join(",", TABLES) + "}");
    }

    private int triggerCount(String table) {
        return jdbc.queryForObject("select count(*) from pg_trigger where tgrelid = ?::regclass and not tgisinternal",
                Integer.class, table);
    }

    private int touch(String table, String id) {
        return jdbc.update("UPDATE " + table + " SET id = id WHERE id::text = ?", id);
    }

    /** 每张表插一行最小合法数据, 返回 id 文本。worker_assignments 用 fixture 里经 JPA 建好的那行。 */
    private String insertRow(String table) {
        String u = UUID.randomUUID().toString();
        return switch (table) {
            case "ai_intent_config_history" -> jdbc.queryForObject(
                    "insert into ai_intent_config_history (intent_config_id, factory_id, intent_code, version_number, "
                            + "snapshot, changed_at, change_type) values (?, ?, 'ST_INTENT', 1, '{}'::jsonb, now(), 'UPDATE') "
                            + "returning id::text", String.class, u, factory);
            case "ai_usage_log" -> jdbc.queryForObject(
                    "insert into ai_usage_log (factory_id, created_at) values (?, now()) returning id::text",
                    String.class, factory);
            case "form_template_versions" -> jdbc.queryForObject(
                    "insert into form_template_versions (id, template_id, factory_id, entity_type, name, version) "
                            + "values (?, ?, ?, 'BATCH', 'v', 1) returning id::text", String.class, u, u, factory);
            case "iot_device_data" -> jdbc.queryForObject(
                    "insert into iot_device_data (device_id, device_code, factory_id, data_type, data_value, collected_at, "
                            + "received_at, processed) values (?, 'D1', ?, 'WEIGHT', '{}', now(), now(), false) returning id::text",
                    String.class, u, factory);
            case "isapi_event_logs" -> jdbc.queryForObject(
                    "insert into isapi_event_logs (factory_id, device_id, event_type, event_state, event_time, received_time) "
                            + "values (?, ?, 'VMD', 'ACTIVE', now(), now()) returning id::text", String.class, factory, deviceId);
            case "model_performance_log" -> jdbc.queryForObject(
                    "insert into model_performance_log (factory_id, period_type, period_start, period_end) "
                            + "values (?, 'DAILY', now(), now()) returning id::text", String.class, factory);
            case "scheduling_predictions" -> jdbc.queryForObject(
                    "insert into scheduling_predictions (id, schedule_id, prediction_type) values (?, ?, 'efficiency') "
                            + "returning id::text", String.class, u, scheduleId);
            case "system_logs" -> jdbc.queryForObject(
                    "insert into system_logs (factory_id, log_type, log_level, created_at) values (?, 'OP', 'INFO', now()) "
                            + "returning id::text", String.class, factory);
            case "tool_call_cache" -> jdbc.queryForObject(
                    "insert into tool_call_cache (cache_key, session_id, tool_name, parameters_hash, original_call_id, "
                            + "expires_at) values (?, 's', 't', 'h', 1, now()) returning id::text", String.class, u);
            case "worker_assignments" -> assignmentId;
            default -> throw new IllegalArgumentException(table);
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        StringBuilder all = new StringBuilder();
        while (t != null) {
            all.append(t.getMessage()).append(" | ");
            t = t.getCause() == t ? null : t.getCause();
        }
        return all.toString();
    }
}
