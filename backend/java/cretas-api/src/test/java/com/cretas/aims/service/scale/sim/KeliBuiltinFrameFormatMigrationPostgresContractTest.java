package com.cretas.aims.service.scale.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.ScaleProtocolConfigRepository;
import com.cretas.aims.service.impl.ScaleProtocolAdapterServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实 PostgreSQL 上跑迁移 V20261114_51 (修正 prod 内置柯力协议 frame_format):
 * 旧行 -> 14/11 并能读样例帧; 再跑一次幂等; 人工改过的行不覆盖; 行不存在 / 表不存在不报错。
 * 旧行 JSON 取种子 SQL 里的新配置反向改回 (frameLength 14->12, stable.start 11->10), 即 prod 实际状态。
 * 解析走线上入口 ScaleProtocolAdapterServiceImpl, 读的是迁移后从库里读回的 frame_format。
 * Opt-in via CRETAS_WORKFLOW_PG_VERIFY=true (Java PR gate 这样跑)。
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EnableJpaRepositories(basePackageClasses = ScaleProtocolConfigRepository.class)
class KeliBuiltinFrameFormatMigrationPostgresContractTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/flyway",
            "V20261114_51__fix_keli_d2008_builtin_frame_format.sql");
    private static final String ID = "proto-keli-d2008-ascii";
    private static final String CODE = "KELI_D2008_ASCII";
    private static final String SAMPLE_HEX = "2B303031323430206B6720530D0A";

    @Autowired
    private JdbcTemplate jdbc;

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
    void createTable() {
        jdbc.execute("DROP TABLE IF EXISTS scale_protocol_configs CASCADE");
        // flyway 链里没有这张表的建表语句 (历史上由 Hibernate 建), 这里只建迁移用到的列, frame_format 与 prod 一样是 jsonb
        jdbc.execute("CREATE TABLE scale_protocol_configs (id varchar(36) PRIMARY KEY, factory_id varchar(50), "
                + "protocol_code varchar(50) NOT NULL UNIQUE, protocol_name varchar(100) NOT NULL, frame_format jsonb)");
    }

    @AfterEach
    void dropTable() {
        jdbc.execute("DROP TABLE IF EXISTS scale_protocol_configs CASCADE");
    }

    private static ObjectNode seedNewFormat() throws IOException {
        return (ObjectNode) ScaleSimSupport.MAPPER.readTree(ScaleSimSupport.seedFrameFormat(ID));
    }

    private static ObjectNode oldFormat() throws IOException {
        ObjectNode n = seedNewFormat();
        n.put("frameLength", 12);
        stable(n).put("start", 10);
        return n;
    }

    private static ObjectNode stable(ObjectNode format) {
        for (JsonNode f : (ArrayNode) format.get("fields")) {
            if ("stable".equals(f.get("name").asText())) return (ObjectNode) f;
        }
        throw new IllegalStateException("no stable field");
    }

    private void insert(ObjectNode format) {
        jdbc.update("INSERT INTO scale_protocol_configs (id, factory_id, protocol_code, protocol_name, frame_format) "
                + "VALUES (?, NULL, ?, '柯力', ?::jsonb)", ID, CODE, format.toString());
    }

    private void runMigration() throws IOException {
        jdbc.execute(Files.readString(MIGRATION, StandardCharsets.UTF_8));
    }

    private ObjectNode readBack() throws IOException {
        String json = jdbc.queryForObject("SELECT frame_format::text FROM scale_protocol_configs WHERE id = ?",
                String.class, ID);
        return (ObjectNode) ScaleSimSupport.MAPPER.readTree(json);
    }

    private com.cretas.aims.dto.scale.ScaleDataParseResult parseFromDb() throws IOException {
        ScaleProtocolAdapterServiceImpl a = ScaleSimSupport.adapter(
                ScaleSimSupport.config(CODE, readBack().toString()));
        return a.parseScaleDataByCode(CODE, HexFormat.of().parseHex(SAMPLE_HEX));
    }

    @Test
    void oldRowIsFixedAndReadsItsSampleFrame() throws IOException {
        insert(oldFormat());
        assertThat(parseFromDb().getSuccess()).as("迁移前旧配置读不了样例帧 (阳性前提)").isFalse();

        runMigration();

        ObjectNode after = readBack();
        assertThat(after.get("frameLength").asInt()).isEqualTo(14);
        assertThat(stable(after).get("start").asInt()).isEqualTo(11);
        // 其它字段原样不动
        ObjectNode expected = seedNewFormat();
        assertThat(after).isEqualTo(expected);

        var r = parseFromDb();
        assertThat(r.getSuccess()).as(r.getErrorMessage()).isTrue();
        assertThat(r.getWeight()).isEqualByComparingTo(new BigDecimal("124.0"));
        assertThat(r.getUnit()).isEqualTo("kg");
        assertThat(r.getStable()).isTrue();
    }

    @Test
    void secondRunChangesNothing() throws IOException {
        insert(oldFormat());
        runMigration();
        String once = readBack().toString();
        runMigration();
        assertThat(readBack().toString()).isEqualTo(once);
        assertThat(readBack().get("frameLength").asInt()).isEqualTo(14);
    }

    @Test
    void manuallyEditedRowIsNotOverwritten() throws IOException {
        ObjectNode custom = oldFormat();
        custom.put("frameLength", 16); // 人工改成别的值: 旧值条件不成立
        insert(custom);
        String before = readBack().toString();

        runMigration();

        assertThat(readBack().toString()).isEqualTo(before);
        assertThat(readBack().get("frameLength").asInt()).isEqualTo(16);
        assertThat(stable(readBack()).get("start").asInt()).isEqualTo(10);
    }

    @Test
    void otherProtocolRowsAreUntouched() throws IOException {
        insert(oldFormat());
        jdbc.update("INSERT INTO scale_protocol_configs (id, factory_id, protocol_code, protocol_name, frame_format) "
                + "VALUES ('other', NULL, 'OTHER', 'x', ?::jsonb)", oldFormat().toString());
        runMigration();
        String other = jdbc.queryForObject("SELECT frame_format::text FROM scale_protocol_configs WHERE id='other'",
                String.class);
        assertThat(ScaleSimSupport.MAPPER.readTree(other)).isEqualTo(oldFormat());
    }

    /** 2026-11-14 只读核对 prod scale_protocol_test_cases 里引用柯力的 5 条 (id, 样例帧, 期望重量, 稳定) 全部与迁移后配置一致。 */
    @Test
    void prodTestCasesAllMatchMigratedConfig() throws IOException {
        insert(oldFormat());
        runMigration();
        ScaleProtocolAdapterServiceImpl a = ScaleSimSupport.adapter(ScaleSimSupport.config(CODE, readBack().toString()));
        Object[][] rows = {
                {"test-keli-001", "2B303031323430206B6720530D0A", "124.0", true},
                {"test-keli-002", "2D303030353230206B6720530D0A", "-52.0", true},
                {"test-keli-003", "2B303031323435206B6720550D0A", "124.5", false},
                {"test-keli-004", "2B303030303030206B6720530D0A", "0", true},
                {"test-keli-005", "2B393939393939206B6720530D0A", "99999.9", true},
        };
        for (Object[] row : rows) {
            var r = a.parseScaleDataByCode(CODE, HexFormat.of().parseHex((String) row[1]));
            assertThat(r.getSuccess()).as("%s: %s", row[0], r.getErrorMessage()).isTrue();
            assertThat(r.getWeight()).as("%s weight", row[0]).isEqualByComparingTo(new BigDecimal((String) row[2]));
            assertThat(r.getUnit()).as("%s unit", row[0]).isEqualTo("kg");
            assertThat(r.getStable()).as("%s stable", row[0]).isEqualTo(row[3]);
        }
    }

    @Test
    void missingRowOrTableDoesNotFailTheMigration() throws IOException {
        runMigration(); // 表在、行不在
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scale_protocol_configs", Integer.class)).isZero();
        jdbc.execute("DROP TABLE scale_protocol_configs");
        runMigration(); // 表不在
    }
}
