package com.cretas.aims.integration;

import com.cretas.aims.entity.intent.IntentFlywheelTurn;
import com.cretas.aims.repository.intent.IntentFlywheelTurnRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 意图飞轮表 (Flyway V20261114_87) 在真 PostgreSQL 上:
 * <ul>
 *   <li>迁移可重放; CHECK 钉住「保密厂原文列必须为空」「丢文本的行导出列也为空」「反应与反应时间成对」;</li>
 *   <li>迁移建的列 = 实体映射的列 (名字与类型), 两份不漂;</li>
 *   <li>真 Hibernate + 真 Repository: 同会话最近几轮 / 按确认卡摘要找未判反应的那一轮。</li>
 * </ul>
 * 由 java-gate 的 postgres 分片执行 (清单 java-gate-pg-tests.txt)。
 */
@SpringBootTest(classes = IntentFlywheelTurnPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class IntentFlywheelTurnPostgresContractTest {

    private static final String MIGRATION = "db/flyway/V20261114_87__ai_intent_flywheel_turns.sql";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    static class Runtime {
    }

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

    @Autowired IntentFlywheelTurnRepository turns;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationReplaysAndItsChecksKeepRawTextOutOfSecretRows() throws Exception {
        inFreshSchema((st, schema) -> {
            String sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
            st.execute(sql);
            st.execute(sql);   // 重放不炸

            assertDoesNotThrow(() -> st.execute(insert("PLAIN", "'原文'", "'原文'", "NULL", "NULL")));
            assertDoesNotThrow(() -> st.execute(insert("REDACTED", "NULL", "'给客户A下五十斤卤鸭腿'", "NULL", "NULL")));
            assertDoesNotThrow(() -> st.execute(insert("TEXT_DROPPED", "NULL", "NULL", "NULL", "NULL")));
            assertThrows(SQLException.class, () -> st.execute(insert("REDACTED", "'给老张家下五十斤'", "'x'", "NULL", "NULL")),
                    "保密厂的行不能带原文");
            assertThrows(SQLException.class, () -> st.execute(insert("TEXT_DROPPED", "NULL", "'x'", "NULL", "NULL")),
                    "丢文本的行不能有导出文本");
            assertThrows(SQLException.class, () -> st.execute(insert("PLAIN", "'a'", "'a'", "'CONFIRMED'", "NULL")),
                    "反应和反应时间必须成对");
            assertThrows(SQLException.class, () -> st.execute(insert("PLAIN", "'a'", "'a'", "'LIKED'", "now()")),
                    "反应只收登记的值");
        });
    }

    @Test
    void migrationColumnsMatchTheEntityMapping() throws Exception {
        Map<String, String> hibernate = columns(null);
        Map<String, String>[] migrated = new Map[1];
        inFreshSchema((st, schema) -> {
            st.execute(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));
            migrated[0] = columns(schema);
        });
        assertThat(migrated[0]).isNotEmpty();
        assertThat(migrated[0]).isEqualTo(hibernate);
    }

    @Test
    void repositoryQueriesRunOnPostgres() {
        String factory = "QA-FW-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();
        IntentFlywheelTurn first = turn(factory, "sess", now.minusMinutes(2), "dg-1");
        IntentFlywheelTurn second = turn(factory, "sess", now.minusMinutes(1), null);
        IntentFlywheelTurn otherSession = turn(factory, "other", now, "dg-1");
        turns.saveAll(List.of(first, second, otherSession));

        List<IntentFlywheelTurn> recent = turns.findTop3ByFactoryIdAndUserIdAndSessionIdAndCreatedAtAfterOrderByIdDesc(
                factory, 7L, "sess", now.minusMinutes(30));
        assertThat(recent).extracting(IntentFlywheelTurn::getTurnId).containsExactly(second.getTurnId(), first.getTurnId());

        assertThat(turns.findFirstByFactoryIdAndConfirmDigestAndReactionIsNullOrderByIdDesc(factory, "dg-1"))
                .get().extracting(IntentFlywheelTurn::getTurnId).isEqualTo(otherSession.getTurnId());
        otherSession.setReaction(IntentFlywheelTurn.Reaction.CONFIRMED);
        otherSession.setReactedAt(now);
        turns.save(otherSession);
        assertThat(turns.findFirstByFactoryIdAndConfirmDigestAndReactionIsNullOrderByIdDesc(factory, "dg-1"))
                .get().extracting(IntentFlywheelTurn::getTurnId).isEqualTo(first.getTurnId());
        assertThat(turns.findById(first.getId()).orElseThrow().getConfidence()).isEqualByComparingTo("0.5500");
    }

    private static IntentFlywheelTurn turn(String factory, String session, LocalDateTime at, String digest) {
        IntentFlywheelTurn t = new IntentFlywheelTurn();
        t.setTurnId(UUID.randomUUID().toString());
        t.setFactoryId(factory);
        t.setUserId(7L);
        t.setSessionId(session);
        t.setEntry(IntentFlywheelTurn.Entry.NORMAL);
        t.setInputKind(IntentFlywheelTurn.InputKind.TEXT);
        t.setCatalogVersion("fic-sha256:test");
        t.setSecrecyMode(IntentFlywheelTurn.SecrecyMode.REDACTED);
        t.setUserInputExport("给客户A下五十斤卤鸭腿");
        t.setDecisionSource("LUNA");
        t.setDecisionAction(IntentFlywheelTurn.Action.SHOW);
        t.setConfidence(new BigDecimal("0.5500"));
        t.setFinalStatus("WRITE_CONFIRM_REQUIRED");
        t.setConfirmDigest(digest);
        t.setCreatedAt(at);
        return t;
    }

    private static String insert(String mode, String raw, String export, String reaction, String reactedAt) {
        return "INSERT INTO ai_intent_flywheel_turns (turn_id, factory_id, entry, input_kind, catalog_version,"
                + " secrecy_mode, user_input, user_input_export, decision_source, decision_action, final_status,"
                + " reaction, reacted_at) VALUES ('" + UUID.randomUUID() + "', 'F1', 'NORMAL', 'TEXT', 'fic', '" + mode
                + "', " + raw + ", " + export + ", 'LUNA', 'DIRECT', 'COMPLETED', " + reaction + ", " + reactedAt + ")";
    }

    /** 列名 → 类型 (udt_name, 字符型带长度); schema 为 null = Hibernate 建在 public 里的那张。 */
    private Map<String, String> columns(String schema) {
        Map<String, String> out = new TreeMap<>();
        jdbc.query("SELECT column_name, udt_name, character_maximum_length, is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'ai_intent_flywheel_turns' AND table_schema = ?",
                (ResultSet rs) -> {
                    Object len = rs.getObject(3);
                    out.put(rs.getString(1), rs.getString(2) + (len == null ? "" : "(" + len + ")") + " " + rs.getString(4));
                }, schema == null ? "public" : schema);
        return out;
    }

    interface SchemaWork {
        void run(Statement st, String schema) throws Exception;
    }

    private static void inFreshSchema(SchemaWork work) throws Exception {
        String schema = "fw_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);
            work.run(st, schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
