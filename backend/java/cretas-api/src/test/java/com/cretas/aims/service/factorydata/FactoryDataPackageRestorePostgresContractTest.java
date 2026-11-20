package com.cretas.aims.service.factorydata;

import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies v2 export metadata; complete restore requires the protected schema baseline CLI. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactoryDataPackageRestorePostgresContractTest {
    private record CliRun(int exitCode, JsonNode receipt, String output) {}
    private enum CliScenario { COPY_REFERENCE, FORGED_PROOF, LEGACY_INTERNAL_FK, LEGACY_NO_FK,
        TAMPERED_LEGACY_DESCRIPTOR, DISGUISED_CURRENT_DESCRIPTOR }
    // Reviewed origin/main before this PR introduced per-FK referencedSchema.
    private static final String LEGACY_MAIN = "a585126e8de2404e307c3d68582b631b854a1daf";
    private record DatabaseIdentity(String name, long oid) {}
    private static final String FACTORY = "FA_RESTORE";
    private static final AuthenticatedActor OPERATOR =
            new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");
    private final ObjectMapper json = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private PGSimpleDataSource source;
    private PGSimpleDataSource smartbiSource;
    private String sourceSchema;
    private String smartbiDb;

    void setUpSources() throws Exception {
        source = dataSource(url());
        boolean vectorProof = "true".equalsIgnoreCase(System.getenv("CRETAS_WORKFLOW_CLI_VERIFY"));
        if (vectorProof) exec(source, "CREATE EXTENSION IF NOT EXISTS vector");
        sourceSchema = "restore_src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        exec(source, "CREATE SCHEMA " + q(sourceSchema));
        exec(source, FactoryDataScopePostgresContractTest.mainSchema(sourceSchema));
        exec(source, "CREATE TABLE IF NOT EXISTS factory_data_requests (id BIGSERIAL PRIMARY KEY, "
                + "request_type varchar(24) NOT NULL, factory_id varchar(64) NOT NULL, factory_name varchar(255), "
                + "actor_type varchar(32) NOT NULL, actor_id bigint NOT NULL, actor_role varchar(64), "
                + "status varchar(16) NOT NULL, related_request_id bigint, not_before timestamp, summary jsonb, "
                + "package_sha256 varchar(64), package_bytes bigint, error_message text, created_at timestamp NOT NULL, "
                + "completed_at timestamp, trigger_source varchar(32) NOT NULL DEFAULT 'MANUAL')");
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "INSERT INTO factories (id, name, type) VALUES ('" + FACTORY + "', 'Restore A', 'FACTORY'), ('FB_RESTORE', 'Restore B', 'FACTORY');"
                + "ALTER TABLE users ADD COLUMN int_values bigint[];"
                + "ALTER TABLE users ADD COLUMN text_values text[];"
                + "ALTER TABLE users ADD COLUMN payload bytea;"
                + "ALTER TABLE users ADD COLUMN event_date date;"
                + "ALTER TABLE users ADD COLUMN event_time time(6);"
                + "ALTER TABLE users ADD COLUMN event_tz timestamptz;"
                + "ALTER TABLE users ADD COLUMN details jsonb;"
                + "ALTER TABLE users ADD COLUMN exact_value numeric(40,12);"
                + "ALTER TABLE users ADD COLUMN float_value double precision;"
                + "INSERT INTO users (id, factory_id, username, password_hash, token_usage, token_id, refresh_token) VALUES "
                + "(1, '" + FACTORY + "', 'a1', 'secret-a', 21.25, 9101, 'credential-a'),"
                + "(2, '" + FACTORY + "', 'a2', 'secret-b', 22.50, 9102, 'credential-b'),"
                + "(3, 'FB_RESTORE', 'b1', 'secret-other', 99.99, 9999, 'credential-other');"
                + "UPDATE users SET int_values = ARRAY[9007199254740993::bigint, 9007199254740994::bigint],"
                + "text_values = ARRAY['alpha','beta'], payload = decode('00ff10','hex'),"
                + "event_date = DATE '2026-11-07', event_time = TIME '12:34:56.123456',"
                + "event_tz = TIMESTAMPTZ '2026-11-07 12:34:56.123456+08',"
                + "details = '{\"exact\":12345678901234567890.123456789012}'::jsonb,"
                + "exact_value = 12345678901234567890.123456789012, float_value = '-0.0'::double precision"
                + " WHERE id = 1;"
                + "INSERT INTO sales_orders (id, factory_id, created_by, note) VALUES (10, '" + FACTORY + "', 1, 'A-RESTORE'), (20, 'FB_RESTORE', 3, 'B-SECRET');"
                + "CREATE TABLE share_links(token varchar(64) PRIMARY KEY, factory_id varchar(64) NOT NULL, note text);"
                + "INSERT INTO share_links VALUES ('link-a','" + FACTORY + "','A-link'),('link-b','" + FACTORY + "','B-link'),('link-other','FB_RESTORE','other-link');"
                + "CREATE TABLE factory_templates(id bigint PRIMARY KEY, template_name varchar(100), base_config jsonb);"
                + "INSERT INTO factory_templates VALUES(1,'global-template-secret','{\"global\":true}'),(2,'other-global-secret','{}');"
                + "CREATE TABLE factory_configurations(id bigint PRIMARY KEY,factory_id varchar(64),template_id bigint REFERENCES factory_templates(id));"
                + "INSERT INTO factory_configurations VALUES(101,'" + FACTORY + "',NULL),(102,'" + FACTORY + "',NULL),(103,'FB_RESTORE',2);"
                + "CREATE TABLE IF NOT EXISTS flyway_schema_history (installed_rank int, version varchar(50), description varchar(200),"
                + "type varchar(20), script varchar(200), checksum int, installed_by varchar(100), installed_on timestamp, execution_time int, success boolean);"
                + "INSERT INTO flyway_schema_history VALUES (1, 'source-1', 'restore source', 'SQL', 'Vsource.sql', 7, 'ci', now(), 4, true);");
        if (vectorProof) exec(source, "SET search_path = " + q(sourceSchema) + ", public;"
                + "ALTER TABLE users ADD COLUMN embedding public.vector(3);"
                + "UPDATE users SET embedding='[0.1,-2,3.25]'::public.vector WHERE id=1");
        smartbiDb = "restore_smartbi_src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        exec(source, "CREATE DATABASE " + q(smartbiDb));
        smartbiSource = dataSource(url().replaceAll("/[^/?]+(\\?|$)", "/" + smartbiDb + "$1"));
        if (vectorProof) exec(smartbiSource, "CREATE EXTENSION IF NOT EXISTS vector");
        exec(smartbiSource, "CREATE SCHEMA " + q(sourceSchema));
        exec(smartbiSource, FactoryDataScopePostgresContractTest.smartbiSchema(sourceSchema));
        exec(smartbiSource, "SET search_path = " + q(sourceSchema) + ";"
                + "INSERT INTO smart_bi_uploads (id, factory_id, file_name) VALUES (1, '" + FACTORY + "', 'restore.csv');"
                + "INSERT INTO smart_bi_field_defs (id, upload_id, name) VALUES (1, 1, 'amount');"
                + "CREATE TABLE IF NOT EXISTS smartbi_migrations (filename varchar(100), version varchar(50), checksum integer, applied_at timestamptz, applied_by varchar(100), duration_ms integer);"
                + "INSERT INTO smartbi_migrations VALUES ('Vsource.sql','source-1',7,now(),'ci',4);");
        if (vectorProof) exec(smartbiSource, "SET search_path = " + q(sourceSchema) + ", public;"
                + "ALTER TABLE smart_bi_field_defs ADD COLUMN embedding public.vector(3);"
                + "UPDATE smart_bi_field_defs SET embedding='[0.1,-2,3.25]'::public.vector WHERE id=1");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (source != null) exec(source, "DROP SCHEMA IF EXISTS " + q(sourceSchema) + " CASCADE");
        if (source != null && smartbiDb != null) exec(source, "DROP DATABASE IF EXISTS " + q(smartbiDb));
    }

    @Test
    void exportBindsProtectedBaselineAndDoesNotPublishRawDdl() throws Exception {
        setUpSources();
        FactoryDataService service = FactoryDataServiceFixture.create(source, smartbiSource, Set.of(1L), sourceSchema);
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                Set.of(FactoryDataService.Format.JSON), output);
        Map<String, byte[]> files = unzip(output.toByteArray());
        JsonNode manifest = json.readTree(files.get("manifest.json"));
        assertThat(manifest.get("format").asText()).isEqualTo("cretas-factory-export/2");
        for (JsonNode database : manifest.get("databases")) {
            String dir = database.get("database").asText().toLowerCase();
            JsonNode metadata = json.readTree(files.get(dir + "/metadata/schema.json"));
            assertThat(metadata.get("restoreContract").asText()).isEqualTo("COMPATIBLE_SCHEMA_REQUIRED");
            assertThat(metadata.get("restoreStatus").asText()).isEqualTo("REQUIRES_PROTECTED_SCHEMA_BASELINE");
            assertThat(metadata.get("rawDdlIncluded").asBoolean()).isFalse();
            assertThat(metadata.get("fingerprint").asText()).hasSize(64);
            assertThat(metadata.get("snapshot").get("crossDatabaseAtomic").asBoolean()).isFalse();
            assertThat(metadata.toString()).doesNotContain("functionDefinition", "indexdef", "column_default");
            assertThat(database.get("tables").size()).isGreaterThan(0);
            assertThat(files).containsKey(dir + "/metadata/migrations.json");
            JsonNode migrations = json.readTree(files.get(dir + "/metadata/migrations.json"));
            assertThat(migrations.get("fingerprint").asText()).hasSize(64);
        }
        assertThat(new String(files.get("cretas/json/users.json"), StandardCharsets.UTF_8))
                .doesNotContain("secret-a", "credential-a", "secret-other");
        JsonNode typed = json.readTree(files.get("cretas/json/users.json")).get(0);
        JsonNode links = json.readTree(files.get("cretas/json/share_links.json"));
        assertThat(links.size()).isEqualTo(2);
        assertThat(links.toString()).doesNotContain("link-a", "link-b", "link-other", "other-link");
        assertThat(links.get(0).get("token").asText()).matches("r[0-9a-f]{32}")
                .isNotEqualTo(links.get(1).get("token").asText());
        assertThat(links.get(1).get("token").asText()).matches("r[0-9a-f]{32}");
        assertThat(files).doesNotContainKey("cretas/json/factory_templates.json");
        assertThat(files.values().stream().map(value -> new String(value, StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining()))
                .doesNotContain("global-template-secret", "other-global-secret");
        JsonNode configurations = json.readTree(files.get("cretas/json/factory_configurations.json"));
        assertThat(configurations.size()).isEqualTo(2);
        configurations.forEach(configuration -> assertThat(configuration.get("template_id").isNull()).isTrue());
        JsonNode external = json.readTree(files.get("cretas/metadata/schema.json")).get("externalReferences");
        assertThat(external.size()).isEqualTo(1);
        assertThat(external.get(0).get("action").asText()).isEqualTo("SCHEMA_ONLY_ZERO_REFERENCES");
        assertThat(external.get(0).get("factoryId").asText()).isEqualTo(FACTORY);
        assertThat(external.get(0).get("referencingRows").asLong()).isZero();
        assertThat(typed.get("int_values").get(0).longValue()).isEqualTo(9007199254740993L);
        assertThat(typed.get("text_values").get(1).asText()).isEqualTo("beta");
        assertThat(typed.get("payload").asText()).isEqualTo("\\x00ff10");
        assertThat(typed.get("event_date").asText()).isEqualTo("2026-11-07");
        assertThat(typed.get("event_time").asText()).contains("12:34:56.123456");
        java.time.OffsetDateTime expectedInstant = java.time.OffsetDateTime.parse("2026-11-07T12:34:56.123456+08:00");
        java.time.OffsetDateTime actualInstant = java.time.OffsetDateTime.parse(typed.get("event_tz").asText());
        assertThat(actualInstant.toInstant()).isEqualTo(expectedInstant.toInstant());
        assertThat(actualInstant.getNano()).isEqualTo(123456000);
        ObjectMapper numericJson = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode details = numericJson.readTree(typed.get("details").asText());
        assertThat(details.get("exact").decimalValue())
                .isEqualByComparingTo("12345678901234567890.123456789012");
        assertThat(typed.get("exact_value").decimalValue())
                .isEqualByComparingTo("12345678901234567890.123456789012");
        assertThat(typed.get("float_value").asText()).isEqualTo("-0.0");
        if ("true".equalsIgnoreCase(System.getenv("CRETAS_WORKFLOW_CLI_VERIFY"))) {
            assertThat(typed.get("embedding").asText()).isEqualTo("[0.1,-2,3.25]");
        }
    }

    @Test
    void compositeForeignKeyMetadataPairsParentColumnsByOrdinal() throws Exception {
        setUpSources();
        exec(source, "SET search_path=" + q(sourceSchema) + ";"
                + "CREATE TABLE ordinal_parent(left_id bigint,right_id bigint,factory_id varchar(64),PRIMARY KEY(left_id,right_id));"
                + "CREATE TABLE ordinal_child(id bigint PRIMARY KEY,factory_id varchar(64),first_ref bigint,second_ref bigint,"
                + "FOREIGN KEY(first_ref,second_ref) REFERENCES ordinal_parent(right_id,left_id));"
                + "INSERT INTO ordinal_parent VALUES(10,20,'" + FACTORY + "');"
                + "INSERT INTO ordinal_child VALUES(1,'" + FACTORY + "',20,10)");
        var parent = new FactoryDataScope.TableScope("ordinal_parent", FactoryDataCatalog.Category.BUSINESS,
                List.of("factory_id"), null, 0);
        parent.setPredicateRows(1);
        var child = new FactoryDataScope.TableScope("ordinal_child", FactoryDataCatalog.Category.BUSINESS,
                List.of("factory_id"), "id", 0);
        child.addKey("1");
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            var snapshot = FactoryDataSchemaMetadata.capture(connection, FactoryDataCatalog.Database.CRETAS,
                    sourceSchema, List.of(parent, child), FACTORY);
            @SuppressWarnings("unchecked")
            var constraints = (List<Map<String, Object>>) snapshot.schema().get("constraints");
            var foreign = constraints.stream().filter(row -> "FOREIGN KEY".equals(row.get("type"))).toList();
            assertThat(foreign).hasSize(2);
            assertThat(foreign).anySatisfy(row -> {
                assertThat(row.get("column")).isEqualTo("first_ref");
                assertThat(row.get("referencedColumn")).isEqualTo("right_id");
                assertThat(row.get("referencedSchema")).isEqualTo(sourceSchema);
            }).anySatisfy(row -> {
                assertThat(row.get("column")).isEqualTo("second_ref");
                assertThat(row.get("referencedColumn")).isEqualTo("left_id");
            });
            connection.rollback();
        }
    }

    @Test
    void crossSchemaSameNamedParentWithNullReferencesRefusesBeforeManifest() throws Exception {
        setUpSources();
        String externalSchema = sourceSchema + "_external";
        exec(source, "CREATE SCHEMA " + q(externalSchema));
        try {
            exec(source, "SET search_path=" + q(sourceSchema) + ";"
                    + "UPDATE factory_configurations SET template_id=NULL;"
                    + "ALTER TABLE factory_configurations DROP CONSTRAINT factory_configurations_template_id_fkey;"
                    + "CREATE TABLE " + q(externalSchema) + ".users(id bigint PRIMARY KEY);"
                    + "ALTER TABLE factory_configurations ADD FOREIGN KEY(template_id) REFERENCES "
                    + q(externalSchema) + ".users(id)");
            assertExportRefusedBeforeManifest("跨 schema");
        } finally {
            exec(source, "DROP SCHEMA " + q(externalSchema) + " CASCADE");
        }
    }

    @Test
    void actualExternalReferenceStillRefusesBeforeManifest() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema)
                + ";UPDATE factory_configurations SET template_id=1 WHERE id=101 AND factory_id='" + FACTORY + "'");
        assertExportRefusedBeforeManifest("外部外键");
    }

    @Test
    void selectOnlyRoleCannotHideANonNullExternalForeignKey() throws Exception {
        assertReadOnlyExternalReferenceRefused(true, true);
    }

    @Test
    void hiddenExternalParentColumnsRefuseZeroReferenceExport() throws Exception {
        assertReadOnlyExternalReferenceRefused(false, false);
    }

    @Test
    void hiddenExternalParentColumnsRefuseNonNullReferenceExport() throws Exception {
        assertReadOnlyExternalReferenceRefused(true, false);
    }

    private void assertReadOnlyExternalReferenceRefused(boolean nonNullReference, boolean parentVisible) throws Exception {
        setUpSources();
        String role = sourceSchema + "_read";
        exec(smartbiSource, "SET search_path = " + q(sourceSchema) + ";"
                + "CREATE TABLE global_templates(id bigint PRIMARY KEY); INSERT INTO global_templates VALUES (77);"
                + "ALTER TABLE smart_bi_uploads ADD COLUMN template_id bigint CONSTRAINT select_only_template_fk "
                + "REFERENCES global_templates(id);"
                + "CREATE ROLE " + q(role) + " LOGIN PASSWORD 'fixture-only' NOSUPERUSER NOBYPASSRLS;");
        try {
            if (nonNullReference) exec(smartbiSource, "UPDATE " + q(sourceSchema) + ".smart_bi_uploads SET template_id=77");
            exec(smartbiSource, "GRANT USAGE ON SCHEMA " + q(sourceSchema) + " TO " + q(role)
                    + "; GRANT SELECT ON ALL TABLES IN SCHEMA " + q(sourceSchema) + " TO " + q(role));
            if (!parentVisible) exec(smartbiSource, "REVOKE SELECT ON " + q(sourceSchema)
                    + ".global_templates FROM " + q(role));
            PGSimpleDataSource reader = dataSource(smartbiSource.getUrl());
            reader.setUser(role);
            reader.setPassword("fixture-only");
            try (Connection conn = reader.getConnection(); Statement statement = conn.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT NOT has_table_privilege('" + sourceSchema
                         + ".smart_bi_uploads', 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'),"
                         + " NOT EXISTS (SELECT FROM information_schema.table_constraints WHERE table_schema='"
                         + sourceSchema + "' AND constraint_name='select_only_template_fk'),"
                         + " has_any_column_privilege('" + sourceSchema + ".global_templates', 'SELECT,INSERT,UPDATE,REFERENCES'),"
                         + " EXISTS (SELECT FROM information_schema.columns WHERE table_schema='" + sourceSchema
                         + "' AND table_name='global_templates')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.getBoolean(2)).isTrue();
                assertThat(rows.getBoolean(3)).isEqualTo(parentVisible);
                assertThat(rows.getBoolean(4)).isEqualTo(parentVisible);
            }
            FactoryDataService service = FactoryDataServiceFixture.create(source, reader, Set.of(1L), sourceSchema);
            assertExportRefusedBeforeManifest(service, parentVisible ? "select_only_template_fk"
                    : "表列描述不完整，拒绝生成可恢复包: global_templates");
        } finally {
            exec(smartbiSource, "DROP OWNED BY " + q(role) + "; DROP ROLE " + q(role));
        }
    }

    @Test
    void directlySelectedOrderReferencingAnotherFactoryUserRefusesBeforeZip() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "INSERT INTO users(id, factory_id, username) VALUES (42424242, 'FB_RESTORE', 'other-creator');"
                + "UPDATE sales_orders SET created_by=42424242 WHERE factory_id='" + FACTORY + "'");
        FactoryDataService service = FactoryDataServiceFixture.create(source, smartbiSource, Set.of(1L), sourceSchema);
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                Set.of(FactoryDataService.Format.JSON), output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("包内外键父行未选中")
                .hasMessageContaining("child=sales_orders, parent=users, name=sales_orders_created_by_fkey]=1")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("FB_RESTORE", "42424242", "other-creator"));
        assertThat(output.size()).isZero();
    }

    @Test
    void slowCretasZipWriteKeepsTheOriginalSmartbiSnapshotAlive() throws Exception {
        setUpSources();
        AtomicBoolean shortenedTimeout = new AtomicBoolean();
        DataSource waiting = shortIdleTimeout(smartbiSource, shortenedTimeout);
        FactoryDataService service = FactoryDataServiceFixture.create(source, waiting, Set.of(1L), sourceSchema);
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean delayed = new AtomicBoolean();
        OutputStream slow = new FilterOutputStream(bytes) {
            private void delayFirstZipWrite() throws IOException {
                if (!delayed.compareAndSet(false, true)) return;
                try {
                    exec(smartbiSource, "UPDATE " + q(sourceSchema) + ".smart_bi_uploads "
                            + "SET file_name='after-preflight.csv' WHERE id=1");
                    // The real PostgreSQL session timeout is two seconds.
                    Thread.sleep(4_500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("slow ZIP fixture interrupted", e);
                } catch (Exception e) {
                    throw new IOException("slow ZIP fixture failed", e);
                }
            }
            @Override public void write(int value) throws IOException {
                delayFirstZipWrite();
                out.write(value);
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                delayFirstZipWrite();
                out.write(data, offset, length);
            }
        };
        assertThatCode(() -> service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                Set.of(FactoryDataService.Format.JSON), slow)).doesNotThrowAnyException();
        assertThat(shortenedTimeout).isTrue();
        assertThat(delayed).isTrue();
        Map<String, byte[]> files = unzip(bytes.toByteArray());
        JsonNode manifest = json.readTree(files.get("manifest.json"));
        assertThat(manifest.get("factoryId").asText()).isEqualTo(FACTORY);
        assertThat(manifest.get("databases")).extracting(db -> db.get("database").asText())
                .containsExactly("CRETAS", "SMARTBI");
        String uploads = "smartbi/json/smart_bi_uploads.json";
        byte[] body = files.get(uploads);
        assertThat(json.readTree(body).get(0).get("file_name").asText()).isEqualTo("restore.csv");
        assertThat(manifest.get("files")).anySatisfy(file -> {
            assertThat(file.get("path").asText()).isEqualTo(uploads);
            assertThat(file.get("rows").asInt()).isEqualTo(1);
            assertThat(file.get("sha256").asText())
                    .isEqualTo(java.util.HexFormat.of().formatHex(FactoryDataPackageWriter.sha256().digest(body)));
        });
        try (Connection live = smartbiSource.getConnection(); Statement statement = live.createStatement();
             ResultSet rows = statement.executeQuery("SELECT file_name FROM " + q(sourceSchema) + ".smart_bi_uploads WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("after-preflight.csv");
        }
    }

    @Test
    void waitingHeartbeatHasANetworkReadDeadlineWhenCretasZipWriteFails() throws Exception {
        setUpSources();
        CountDownLatch heartbeatStarted = new CountDownLatch(1);
        DataSource waiting = interceptHeartbeat(smartbiSource, sql -> {
            heartbeatStarted.countDown();
            // A real PostgreSQL response delayed beyond the socket read limit, within the query timeout.
            return "SELECT pg_sleep(4)";
        }, null);
        FactoryDataService service = FactoryDataServiceFixture.create(source, waiting, Set.of(1L), sourceSchema, 1_500);
        long request = service.startExport(OPERATOR, FACTORY);
        IOException writeFailure = new IOException("CRETAS ZIP write failed");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream output = afterHeartbeat(bytes, heartbeatStarted, writeFailure);

        assertThatThrownBy(() -> service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                Set.of(FactoryDataService.Format.JSON), output))
                .hasRootCause(writeFailure)
                .satisfies(error -> assertThat(error.getSuppressed())
                        .as("SmartBI 心跳须在 JDBC 套接字读超时后结束并回传异常")
                        .anySatisfy(heartbeat -> assertThat(heartbeat)
                                .hasMessageContaining("快照心跳失败")
                                .hasRootCauseInstanceOf(SocketTimeoutException.class)));
        assertFailedExport(service, request, bytes);
    }

    @Test
    void runtimeHeartbeatFailureRemainsTheCauseWhenSnapshotRollbackFails() throws Exception {
        setUpSources();
        CountDownLatch heartbeatStarted = new CountDownLatch(1);
        AtomicInteger heartbeats = new AtomicInteger();
        RuntimeException heartbeatFailure = new IllegalStateException("SmartBI heartbeat wrapper failed");
        SQLException rollbackFailure = new SQLException("SmartBI snapshot rollback failed");
        DataSource waiting = interceptHeartbeat(smartbiSource, sql -> {
            heartbeats.incrementAndGet();
            heartbeatStarted.countDown();
            throw heartbeatFailure;
        }, rollbackFailure);
        FactoryDataService service = FactoryDataServiceFixture.create(source, waiting, Set.of(1L), sourceSchema);
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream output = afterHeartbeat(bytes, heartbeatStarted, null);

        assertThatThrownBy(() -> service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                Set.of(FactoryDataService.Format.JSON), output))
                .hasMessageContaining("快照心跳失败")
                .hasRootCause(heartbeatFailure)
                .satisfies(error -> assertThat(error.getCause().getSuppressed()).containsExactly(rollbackFailure));
        assertThat(heartbeats).hasValue(1);
        assertThat(bytes.size()).isPositive();
        assertThat(bytes.toString(StandardCharsets.ISO_8859_1)).doesNotContain("smartbi/json/");
        assertFailedExport(service, request, bytes);
    }

    @Test
    void cretasZipFailureRemainsTheCauseWhenHeartbeatFailsDuringRollback() throws Exception {
        setUpSources();
        CountDownLatch heartbeatEntered = new CountDownLatch(1);
        CountDownLatch rollbackEntered = new CountDownLatch(1);
        CountDownLatch heartbeatAboutToThrow = new CountDownLatch(1);
        var heartbeatThread = new AtomicReference<Thread>();
        var writeFailure = new IOException("CRETAS ZIP write failed before rollback");
        var heartbeatFailure = new IllegalStateException("SmartBI heartbeat failed during CRETAS rollback");
        var rollbackFailure = new SQLException("CRETAS snapshot rollback failed");
        DataSource waiting = interceptHeartbeat(smartbiSource, sql -> {
            heartbeatThread.set(Thread.currentThread());
            heartbeatEntered.countDown();
            awaitBarrier(rollbackEntered);
            heartbeatAboutToThrow.countDown();
            throw heartbeatFailure;
        }, null);
        DataSource cretas = interceptConnection(source, (method, args) -> {
            if ("rollback".equals(method)) {
                rollbackEntered.countDown();
                awaitBarrier(heartbeatAboutToThrow);
                // The failed scheduled task has returned to the idle executor before rollback throws.
                Thread thread = heartbeatThread.get();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(5);
                }
                assertThat(thread.getState()).as("SmartBI heartbeat finished during CRETAS rollback")
                        .isEqualTo(Thread.State.WAITING);
                throw rollbackFailure;
            }
        });
        FactoryDataService service = FactoryDataServiceFixture.create(cretas, waiting, Set.of(1L), sourceSchema);
        long request = service.startExport(OPERATOR, FACTORY);
        var bytes = new ByteArrayOutputStream();
        OutputStream output = afterHeartbeat(bytes, heartbeatEntered, writeFailure);

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> service.writeExport(request, OPERATOR,
                FACTORY, FactoryDataService.AttachmentMode.SKIP, Set.of(FactoryDataService.Format.JSON), output));
        assertFailedExport(service, request, bytes);
        assertThat(failure).hasRootCause(writeFailure);
        assertThat(failure.getSuppressed()).hasSize(2).contains(rollbackFailure)
                .anySatisfy(heartbeat -> assertThat(heartbeat).hasMessageContaining("快照心跳失败")
                        .hasRootCause(heartbeatFailure));
        assertThat(service.ledger().find(request)).hasValueSatisfying(row ->
                assertThat(row.errorMessage()).contains(writeFailure.getMessage()));
    }

    private static void awaitBarrier(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("snapshot failure ordering barrier").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertFailedExport(FactoryDataService service, long request, ByteArrayOutputStream bytes) {
        assertThat(bytes.toString(StandardCharsets.ISO_8859_1)).doesNotContain("manifest.json");
        assertThat(service.ledger().find(request)).hasValueSatisfying(row -> {
            assertThat(row.status()).isEqualTo(FactoryDataRequestLedger.Status.FAILED);
            assertThat(row.packageSha256()).isNull();
        });
    }

    private static OutputStream afterHeartbeat(ByteArrayOutputStream bytes, CountDownLatch heartbeatStarted,
                                               IOException writeFailure) {
        return new FilterOutputStream(bytes) {
            private boolean firstWrite = true;

            private void beforeWrite() throws IOException {
                if (!firstWrite) return;
                firstWrite = false;
                try {
                    if (!heartbeatStarted.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("未进入 SmartBI 心跳");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待 SmartBI 心跳时被中断", e);
                }
                if (writeFailure != null) throw writeFailure;
            }

            @Override public void write(int value) throws IOException {
                beforeWrite();
                out.write(value);
            }

            @Override public void write(byte[] data, int offset, int length) throws IOException {
                beforeWrite();
                out.write(data, offset, length);
            }
        };
    }

    private static DataSource shortIdleTimeout(DataSource original, AtomicBoolean shortenedTimeout) {
        return interceptHeartbeat(original, UnaryOperator.identity(), null, shortenedTimeout);
    }

    private static DataSource interceptHeartbeat(DataSource original, UnaryOperator<String> heartbeat,
                                                 SQLException rollbackFailure) {
        return interceptHeartbeat(original, heartbeat, rollbackFailure, new AtomicBoolean());
    }

    private static DataSource interceptHeartbeat(DataSource original, UnaryOperator<String> heartbeat,
                                                 SQLException rollbackFailure, AtomicBoolean shortenedTimeout) {
        return interceptConnection(original, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                if ("SET LOCAL idle_in_transaction_session_timeout = '15min'".equals(args[0])) {
                    args[0] = "SET LOCAL idle_in_transaction_session_timeout = '2s'";
                    shortenedTimeout.set(true);
                } else if ("SELECT 1".equals(args[0])) {
                    args[0] = heartbeat.apply((String) args[0]);
                }
            } else if ("rollback".equals(method) && rollbackFailure != null) {
                throw rollbackFailure;
            }
        });
    }

    @FunctionalInterface
    private interface SnapshotCall {
        void beforeCall(String method, Object[] args) throws Exception;
    }

    private static DataSource interceptConnection(DataSource original, SnapshotCall call) {
        return new DelegatingDataSource(original) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection real = super.getConnection();
                // Forward every call to a real JDBC session, shortening only
                // the production SET LOCAL so PostgreSQL enforces the deadline.
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            call.beforeCall(method.getName(), args);
                            try {
                                return method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
    }

    @Test
    void matchFullAllowsAnEntirelyNullCompositeKey() throws Exception {
        assertCompositeNullExport(true, false);
    }

    @Test
    void matchSimpleAllowsAPartiallyNullCompositeKey() throws Exception {
        assertCompositeNullExport(false, true);
    }

    @Test
    void matchFullRefusesPartiallyNullHistoricalCompositeKey() throws Exception {
        setUpSources();
        seedCompositeMatchForeignKey(true, true);
        assertExportRefusedBeforeManifest("name=composite_match_fk]=1");
    }

    private void assertCompositeNullExport(boolean matchFull, boolean partial) throws Exception {
        setUpSources();
        seedCompositeMatchForeignKey(matchFull, partial);
        Map<String, byte[]> files = unzip(exportPackage(source, smartbiSource, sourceSchema));
        assertThat(files).containsKey("manifest.json");
        JsonNode row = json.readTree(files.get("cretas/json/match_child.json")).get(0);
        assertThat(row.get("part_b").isNull()).isTrue();
        assertThat(row.get("part_a").isNull()).isEqualTo(!partial);
    }

    private void seedCompositeMatchForeignKey(boolean matchFull, boolean partial) throws Exception {
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "CREATE TABLE match_parent(id bigint PRIMARY KEY, factory_id varchar(64), part_a int, part_b int,"
                + " UNIQUE(part_a, part_b));"
                + "CREATE TABLE match_child(id bigint PRIMARY KEY, factory_id varchar(64), part_a int, part_b int);"
                + "INSERT INTO match_parent VALUES (1, '" + FACTORY + "', 10, 20);"
                + "INSERT INTO match_child VALUES (2, '" + FACTORY + "', " + (partial ? "10" : "NULL") + ", NULL);"
                + "ALTER TABLE match_child ADD CONSTRAINT composite_match_fk FOREIGN KEY(part_a, part_b) "
                + "REFERENCES match_parent(part_a, part_b) MATCH " + (matchFull ? "FULL" : "SIMPLE") + " NOT VALID");
    }

    @Test
    void nonNullExternalReferenceToCopyTableRefusesBeforeManifest() throws Exception {
        setUpSources();
        addCopyForeignKey();
        exec(source, "UPDATE " + q(sourceSchema) + ".factory_configurations SET copy_template_id=1 WHERE id=101");
        assertExportRefusedBeforeManifest("copy_template_fk");
    }

    private void addCopyForeignKey() throws Exception {
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "CREATE TABLE backup_factory_templates(id bigint PRIMARY KEY, factory_id varchar(64), note text);"
                + "INSERT INTO backup_factory_templates VALUES (1, '" + FACTORY + "', 'backup-only-note');"
                + "ALTER TABLE factory_configurations ADD COLUMN copy_template_id bigint CONSTRAINT copy_template_fk "
                + "REFERENCES backup_factory_templates(id)");
    }

    @Test
    void generatedCredentialPrimaryKeyRefusesBeforeManifest() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "ALTER TABLE share_links DROP CONSTRAINT share_links_pkey;"
                + "ALTER TABLE share_links DROP COLUMN token;"
                + "ALTER TABLE share_links ADD COLUMN token varchar(64) GENERATED ALWAYS AS (note || '-credential') STORED PRIMARY KEY");
        assertExportRefusedBeforeManifest("生成列");
    }

    @Test
    void compositeCredentialPrimaryKeyRefusesBeforeManifest() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "ALTER TABLE share_links DROP CONSTRAINT share_links_pkey;"
                + "ALTER TABLE share_links ADD PRIMARY KEY(token,factory_id)");
        assertExportRefusedBeforeManifest("复合主键");
    }

    @Test
    void credentialForeignKeyAliasStillRefusesBeforeManifest() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "CREATE TABLE share_alias_refs(id bigint PRIMARY KEY,factory_id varchar(64),link_ref varchar(64) REFERENCES share_links(token));"
                + "INSERT INTO share_alias_refs VALUES(1,'" + FACTORY + "','link-a')");
        assertExportRefusedBeforeManifest("外键");
    }

    @Test
    void shortCredentialPrimaryKeyStillRefusesBeforeManifest() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";ALTER TABLE share_links ALTER COLUMN token TYPE varchar(16)");
        assertExportRefusedBeforeManifest("源列长度");
    }

    @Test
    void nonDefaultArrayBoundsRefuseBeforeSuccessfulManifest() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "UPDATE users SET int_values=array_fill(1::bigint,ARRAY[2],ARRAY[0]) WHERE id=1");
        FactoryDataService service = FactoryDataServiceFixture.create(source, smartbiSource, Set.of(1L), sourceSchema);
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> service.writeExport(request, OPERATOR, FACTORY,
                FactoryDataService.AttachmentMode.SKIP, Set.of(FactoryDataService.Format.JSON), output))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("下界");
        assertThat(new String(output.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("manifest.json");
    }

    @Test
    void shadowVectorTypeRefusesEvenWithRealExtensionInstalled() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("CRETAS_WORKFLOW_CLI_VERIFY")));
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "CREATE TYPE " + q(sourceSchema) + ".vector AS ENUM ('[0.1,-2,3.25]');"
                + "ALTER TABLE users ADD COLUMN fake_embedding " + q(sourceSchema) + ".vector");
        assertExportRefusedBeforeManifest("pgvector");
    }

    @Test
    void customUuidNamedTypeIsNotMistakenForBuiltinUuid() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "CREATE TYPE " + q(sourceSchema) + ".uuid AS ENUM ('not-a-builtin-uuid');"
                + "ALTER TABLE users ADD COLUMN fake_identity " + q(sourceSchema) + ".uuid");
        assertExportRefusedBeforeManifest("自定义类型");
    }

    @Test
    void fixedWidthCredentialCannotCreateUnrestorablePaddedMarker() throws Exception {
        setUpSources();
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "ALTER TABLE users ADD COLUMN auth_token character(64)");
        assertExportRefusedBeforeManifest("敏感列");
    }

    @Test
    void missingMigrationLedgerRefusesBeforeSuccessfulManifest() throws Exception {
        setUpSources();
        exec(source, "DROP TABLE " + q(sourceSchema) + ".flyway_schema_history");
        assertExportRefusedBeforeManifest("migration");
    }

    private void assertExportRefusedBeforeManifest(String message) throws Exception {
        FactoryDataService service = FactoryDataServiceFixture.create(source, smartbiSource, Set.of(1L), sourceSchema);
        assertExportRefusedBeforeManifest(service, message);
    }

    private void assertExportRefusedBeforeManifest(FactoryDataService service, String message) throws Exception {
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> service.writeExport(request, OPERATOR, FACTORY,
                FactoryDataService.AttachmentMode.SKIP, Set.of(FactoryDataService.Format.CSV), output))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
        assertThat(new String(output.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("manifest.json");
    }

    @Test
    void javaExportRoundTripsThroughProtectedCliWhenExplicitlyEnabled() throws Exception {
        verifyProtectedCli(CliScenario.COPY_REFERENCE);
    }

    @Test
    void forgedZeroReferenceProofRefusesBeforeAnyTargetConnection() throws Exception {
        verifyProtectedCli(CliScenario.FORGED_PROOF);
    }

    @Test
    void legacyMainExportWithInternalForeignKeysRestoresThroughProtectedCli() throws Exception {
        verifyProtectedCli(CliScenario.LEGACY_INTERNAL_FK);
    }

    @Test
    void legacyMainExportWithOnlyPrimaryUniqueAndCheckConstraintsRestores() throws Exception {
        verifyProtectedCli(CliScenario.LEGACY_NO_FK);
    }

    @Test
    void alteredLegacyExportFingerprintRefusesOfflinePreflight() throws Exception {
        verifyProtectedCli(CliScenario.TAMPERED_LEGACY_DESCRIPTOR);
    }

    @Test
    void currentExportDisguisedAsLegacyRefusesOfflinePreflight() throws Exception {
        verifyProtectedCli(CliScenario.DISGUISED_CURRENT_DESCRIPTOR);
    }

    private void verifyProtectedCli(CliScenario scenario) throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("CRETAS_WORKFLOW_CLI_VERIFY")));
        String pgBin = System.getenv("CRETAS_WORKFLOW_PG_BIN");
        String python = System.getenv().getOrDefault("CRETAS_WORKFLOW_PYTHON", "python");
        String pgUser = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "postgres");
        String ownershipReceipt = System.getenv("CRETAS_WORKFLOW_PG_OWNER_RECEIPT");
        assumeTrue(pgBin != null && !pgBin.isBlank());
        assumeTrue(ownershipReceipt != null && !ownershipReceipt.isBlank());
        Path repo = Path.of("..", "..", "..").toAbsolutePath().normalize();
        Path script = Path.of(System.getenv().getOrDefault("CRETAS_WORKFLOW_RESTORE_SCRIPT",
                repo.resolve("scripts/backup/restore-factory-export-v2.py").toString()));
        assumeTrue(Files.isRegularFile(script));
        String jdbc = url();
        java.net.URI uri = java.net.URI.create(jdbc.substring("jdbc:".length()));
        String host = uri.getHost();
        String port = Integer.toString(uri.getPort());
        String systemId = systemIdentifier(dataSource(url()));
        verifyOwnedConnection(python, script, host, port, pgUser, systemId, pgBin, ownershipReceipt);
        setUpSources();
        boolean legacy = scenario == CliScenario.LEGACY_INTERNAL_FK || scenario == CliScenario.LEGACY_NO_FK
                || scenario == CliScenario.TAMPERED_LEGACY_DESCRIPTOR;
        boolean descriptorTampering = scenario == CliScenario.TAMPERED_LEGACY_DESCRIPTOR
                || scenario == CliScenario.DISGUISED_CURRENT_DESCRIPTOR;
        if (scenario == CliScenario.COPY_REFERENCE) addCopyForeignKey();
        if (legacy || descriptorTampering) prepareLegacySources(scenario == CliScenario.LEGACY_NO_FK);
        int postgresMajor = serverMajor(source);
        Path work = Files.createTempDirectory("factory-export-cli-");
        Path packagePath = work.resolve("java-export.zip");
        Path cretasDump = work.resolve("cretas.dump");
        Path smartbiDump = work.resolve("smartbi.dump");
        Path smartbiMismatchDump = work.resolve("smartbi-mismatch.dump");
        Path happyReceipt = work.resolve("happy-receipt.json");
        Path mismatchReceipt = work.resolve("mismatch-receipt.json");
        String cretasTarget = "restore_java_cretas_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String smartbiTarget = "restore_java_smartbi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String mismatchCretasTarget = "restore_java_cretas_bad_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String mismatchSmartbiTarget = "restore_java_smartbi_bad_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try {
            if (legacy) exportUsingLegacyMain(repo, work, packagePath);
            else Files.write(packagePath, exportPackage(source, smartbiSource, sourceSchema));
            dump(pgBin, host, port, pgUser, "postgres", cretasDump);
            dump(pgBin, host, port, pgUser, smartbiDb, smartbiDump);
            Map<String, byte[]> packageFiles = unzip(Files.readAllBytes(packagePath));
            if (scenario == CliScenario.COPY_REFERENCE) assertCopyReferenceProof(packageFiles);
            if (legacy) assertLegacyFixture(packageFiles, scenario == CliScenario.LEGACY_NO_FK);
            JsonNode manifest = json.readTree(packageFiles.get("manifest.json"));
            Map<String, Object> baseline = new HashMap<>();
            for (JsonNode database : manifest.get("databases")) {
                String key = database.get("database").asText();
                String dir = key.toLowerCase();
                JsonNode schema = json.readTree(packageFiles.get(dir + "/metadata/schema.json"));
                JsonNode migrations = json.readTree(packageFiles.get(dir + "/metadata/migrations.json"));
                baseline.put(key, Map.of("schemaFingerprint", schema.get("fingerprint").asText(),
                        "migrationFingerprint", migrations.get("fingerprint").asText()));
            }
            Path baselineManifest = work.resolve("baseline-manifest.json");
            Files.writeString(baselineManifest, json.writeValueAsString(baseline), StandardCharsets.UTF_8);
            writeSidecar(cretasDump, json.readTree(packageFiles.get("cretas/metadata/schema.json")),
                    json.readTree(packageFiles.get("cretas/metadata/migrations.json")), postgresMajor);
            writeSidecar(smartbiDump, json.readTree(packageFiles.get("smartbi/metadata/schema.json")),
                    json.readTree(packageFiles.get("smartbi/metadata/migrations.json")), postgresMajor);
            if (descriptorTampering) {
                assertDescriptorTamperingRefused(python, script, packagePath, packageFiles, cretasDump, smartbiDump,
                        baselineManifest, host, port, pgUser, systemId, cretasTarget, smartbiTarget, pgBin,
                        ownershipReceipt, scenario);
                return;
            }
            exec(source, "CREATE DATABASE " + q(cretasTarget) + "; CREATE DATABASE " + q(smartbiTarget));
            Map<String, DatabaseIdentity> happyDatabaseIds = databaseOids(source, Map.of("CRETAS", cretasTarget, "SMARTBI", smartbiTarget));
            if (scenario == CliScenario.FORGED_PROOF) {
                long before = targetSessions(cretasTarget, smartbiTarget);
                Path forgedPackage = work.resolve("forged-export.zip");
                forgeExternalReference(forgedPackage, packageFiles);
                Path forgedReceipt = work.resolve("forged-receipt.json");
                CliRun refused = runCli(python, script, forgedPackage, cretasDump, smartbiDump,
                        baselineManifest, host, port, pgUser, systemId, cretasTarget, smartbiTarget, pgBin,
                        ownershipReceipt, forgedReceipt);
                assertThat(refused.exitCode()).isNotZero();
                assertThat(refused.output()).contains("外部 FK 证明与导出行的非 NULL 值不一致");
                assertThat(forgedReceipt).doesNotExist();
                assertThat(targetSessions(cretasTarget, smartbiTarget)).isEqualTo(before);
                assertThat(databaseOids(source, Map.of("CRETAS", cretasTarget, "SMARTBI", smartbiTarget)))
                        .isEqualTo(happyDatabaseIds);
                return;
            }
            CliRun happy = runCli(python, script, packagePath, cretasDump, smartbiDump, baselineManifest,
                    host, port, pgUser, systemId, cretasTarget, smartbiTarget, pgBin, ownershipReceipt,
                    happyReceipt);
            assertThat(happy.exitCode()).as(happy.output()).isZero();
            assertThat(happy.receipt().get("status").asText()).isEqualTo("RESTORED_AND_VERIFIED");
            assertReceiptDatabaseIds(happy.receipt(), happyDatabaseIds);
            if (legacy) return;
            try (Connection restored = dataSource(jdbc.replaceAll("/[^/?]+(\\?|$)", "/" + cretasTarget + "$1")).getConnection();
                 Statement statement = restored.createStatement()) {
                statement.execute("SET search_path=" + q(sourceSchema));
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM factory_templates")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM backup_factory_templates")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isZero();
                }
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM factory_configurations WHERE factory_id='"
                        + FACTORY + "' AND template_id IS NULL AND copy_template_id IS NULL")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(2);
                }
            }

            // Keep the original package and Cretas baseline, but bind a fresh
            // SmartBI archive whose catalog has one extra CHECK constraint.
            // The mismatch must reach target structural verification rather than
            // being rejected by package/baseline hashes.
            exec(smartbiSource, "SET search_path = " + q(sourceSchema)
                    + "; ALTER TABLE smart_bi_uploads ADD CONSTRAINT restore_mismatch_check CHECK (id > 0)");
            dump(pgBin, host, port, pgUser, smartbiDb, smartbiMismatchDump);
            JsonNode oldSmartbiSchema = json.readTree(packageFiles.get("smartbi/metadata/schema.json"));
            JsonNode oldSmartbiMigrations = json.readTree(packageFiles.get("smartbi/metadata/migrations.json"));
            writeSidecar(smartbiMismatchDump, oldSmartbiSchema, oldSmartbiMigrations, postgresMajor);
            exec(source, "DROP DATABASE IF EXISTS " + q(cretasTarget) + "; DROP DATABASE IF EXISTS " + q(smartbiTarget));
            exec(source, "CREATE DATABASE " + q(mismatchCretasTarget) + "; CREATE DATABASE " + q(mismatchSmartbiTarget));
            Map<String, DatabaseIdentity> mismatchDatabaseIds = databaseOids(source,
                    Map.of("CRETAS", mismatchCretasTarget, "SMARTBI", mismatchSmartbiTarget));
            CliRun mismatch = runCli(python, script, packagePath, cretasDump, smartbiMismatchDump, baselineManifest,
                    host, port, pgUser, systemId, mismatchCretasTarget, mismatchSmartbiTarget, pgBin,
                    ownershipReceipt, mismatchReceipt);
            assertThat(mismatch.exitCode()).isNotZero();
            assertThat(mismatch.receipt().get("status").asText()).isEqualTo("RESTORE_FAILED");
            assertReceiptDatabaseIds(mismatch.receipt(), mismatchDatabaseIds);
            for (JsonNode state : mismatch.receipt().get("databases")) {
                assertThat(state.get("cleanup").asText()).isEqualTo("DESTROYED_AND_VERIFIED");
            }
            assertThat(databaseExists(source, mismatchCretasTarget)).isFalse();
            assertThat(databaseExists(source, mismatchSmartbiTarget)).isFalse();
        } finally {
            exec(source, "DROP DATABASE IF EXISTS " + q(cretasTarget) + "; DROP DATABASE IF EXISTS " + q(smartbiTarget)
                    + "; DROP DATABASE IF EXISTS " + q(mismatchCretasTarget) + "; DROP DATABASE IF EXISTS " + q(mismatchSmartbiTarget));
            deleteTree(work);
        }
    }

    private void assertDescriptorTamperingRefused(String python, Path script, Path original, Map<String, byte[]> files,
            Path cretasDump, Path smartbiDump, Path baseline, String host, String port, String pgUser, String systemId,
            String cretasTarget, String smartbiTarget, String pgBin, String ownershipReceipt, CliScenario scenario) throws Exception {
        CliRun valid = runCli(python, script, original, cretasDump, smartbiDump, baseline, host, port, pgUser, systemId,
                cretasTarget, smartbiTarget, pgBin, ownershipReceipt, null, false);
        assertThat(valid.exitCode()).as(valid.output()).isZero();
        assertThat(valid.output()).contains("VALIDATED_ONLY");
        String entry = "cretas/metadata/schema.json";
        JsonNode schema = json.readTree(files.get(entry));
        if (scenario == CliScenario.DISGUISED_CURRENT_DESCRIPTOR) {
            for (JsonNode constraint : schema.get("constraints")) {
                assertThat(((com.fasterxml.jackson.databind.node.ObjectNode) constraint).remove("referencedSchema")).isNotNull();
            }
        } else {
            var constraint = (com.fasterxml.jackson.databind.node.ObjectNode) schema.get("constraints").get(0);
            constraint.put("definitionFingerprint", "00000000000000000000000000000000");
        }
        Path tampered = original.resolveSibling("tampered-descriptor.zip");
        rewritePackageEntry(tampered, files, entry, json.writeValueAsBytes(schema));
        CliRun refused = runCli(python, script, tampered, cretasDump, smartbiDump, baseline, host, port, pgUser, systemId,
                cretasTarget, smartbiTarget, pgBin, ownershipReceipt, null, false);
        assertThat(refused.exitCode()).as(refused.output()).isNotZero();
        assertThat(refused.output()).contains("源结构描述重算指纹与声明或受保护 baseline 不匹配");
        assertThat(databaseExists(source, cretasTarget)).isFalse();
        assertThat(databaseExists(source, smartbiTarget)).isFalse();
    }

    private void assertCopyReferenceProof(Map<String, byte[]> packageFiles) throws Exception {
        assertThat(packageFiles).doesNotContainKey("cretas/json/backup_factory_templates.json");
        JsonNode metadata = json.readTree(packageFiles.get("cretas/metadata/schema.json"));
        assertThat(metadata.get("externalReferences")).anySatisfy(proof -> {
            assertThat(proof.get("constraint").asText()).isEqualTo("copy_template_fk");
            assertThat(proof.get("referencedTable").asText()).isEqualTo("backup_factory_templates");
            assertThat(proof.get("referencingRows").asLong()).isZero();
        });
    }

    private void prepareLegacySources(boolean noForeignKeys) throws Exception {
        exec(source, "SET search_path = " + q(sourceSchema) + ";"
                + "ALTER TABLE factory_configurations DROP CONSTRAINT factory_configurations_template_id_fkey;"
                + "ALTER TABLE users ADD CONSTRAINT legacy_user_name_unique UNIQUE (username),"
                + " ADD CONSTRAINT legacy_user_id_check CHECK (id > 0)");
        if (!noForeignKeys) return;
        for (DataSource ds : List.of(source, smartbiSource)) {
            try (Connection conn = ds.getConnection();
                 var query = conn.prepareStatement("SELECT c.relname, pc.conname FROM pg_constraint pc "
                         + "JOIN pg_class c ON c.oid=pc.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
                         + "WHERE pc.contype='f' AND n.nspname=?");
                 Statement ddl = conn.createStatement()) {
                query.setString(1, sourceSchema);
                try (ResultSet keys = query.executeQuery()) {
                    while (keys.next()) {
                        ddl.addBatch("ALTER TABLE " + q(sourceSchema) + "." + q(keys.getString(1))
                                + " DROP CONSTRAINT " + q(keys.getString(2)));
                    }
                }
                ddl.executeBatch();
            }
        }
    }

    private void assertLegacyFixture(Map<String, byte[]> files, boolean noForeignKeys) throws Exception {
        Set<String> types = new java.util.HashSet<>();
        for (String db : List.of("cretas", "smartbi")) {
            JsonNode metadata = json.readTree(files.get(db + "/metadata/schema.json"));
            assertThat(metadata.get("externalReferences")).isEmpty();
            for (JsonNode constraint : metadata.get("constraints")) {
                assertThat(constraint.has("referencedSchema")).isFalse();
                types.add(constraint.get("type").asText());
            }
        }
        assertThat(types).contains("PRIMARY KEY", "UNIQUE", "CHECK");
        if (noForeignKeys) assertThat(types).doesNotContain("FOREIGN KEY");
        else assertThat(types).contains("FOREIGN KEY");
    }

    private void exportUsingLegacyMain(Path repo, Path work, Path output) throws Exception {
        Path classes = Files.createDirectory(work.resolve("legacy-classes"));
        List<String> compilerArgs = new java.util.ArrayList<>(List.of("-encoding", "UTF-8", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        for (String name : List.of("FactoryDataService.java", "FactoryDataSchemaMetadata.java")) {
            Path java = work.resolve(name);
            Process git = new ProcessBuilder("git", "-C", repo.toString(), "show",
                    LEGACY_MAIN + ":backend/java/cretas-api/src/main/java/com/cretas/aims/service/factorydata/" + name)
                    .redirectOutput(java.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            assertThat(git.waitFor()).as("read exporter source from origin/main %s", LEGACY_MAIN).isZero();
            compilerArgs.add(java.toString());
        }
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err,
                compilerArgs.toArray(String[]::new));
        assertThat(compiled).as("compile unmodified origin/main exporter").isZero();
        Path args = work.resolve("legacy-java-args.txt");
        String classpath = classes + java.io.File.pathSeparator + System.getProperty("java.class.path");
        Files.writeString(args, "-cp\n" + javaArgument(classpath) + "\n" + getClass().getName() + "\n"
                + javaArgument(source.getUrl()) + "\n" + javaArgument(smartbiSource.getUrl()) + "\n"
                + javaArgument(sourceSchema) + "\n" + javaArgument(output.toString()) + "\n");
        Process exporter = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + args).inheritIO().start();
        assertThat(exporter.waitFor()).as("export v2 using origin/main %s", LEGACY_MAIN).isZero();
    }

    private static String javaArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void main(String[] args) throws Exception {
        Files.write(Path.of(args[3]), exportPackage(dataSource(args[0]), dataSource(args[1]), args[2]));
    }

    private static byte[] exportPackage(DataSource main, DataSource smartbi, String schema) throws Exception {
        FactoryDataService service = FactoryDataServiceFixture.create(main, smartbi, Set.of(1L), schema);
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                Set.of(FactoryDataService.Format.JSON), output);
        return output.toByteArray();
    }

    private void forgeExternalReference(Path output, Map<String, byte[]> files) throws Exception {
        String path = "cretas/json/factory_configurations.json";
        JsonNode table = json.readTree(files.get(path));
        ((com.fasterxml.jackson.databind.node.ObjectNode) table.get(0)).put("template_id", 1);
        rewritePackageEntry(output, files, path, json.writeValueAsBytes(table));
    }

    private void rewritePackageEntry(Path output, Map<String, byte[]> files, String path, byte[] body) throws Exception {
        files.put(path, body);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        JsonNode manifest = json.readTree(files.get("manifest.json"));
        for (JsonNode file : manifest.get("files")) {
            if (path.equals(file.get("path").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) file).put("sha256", sha).put("bytes", body.length);
            }
        }
        for (JsonNode db : manifest.get("databases")) {
            JsonNode sourceSchema = db.get("sourceSchema");
            if (path.equals(sourceSchema.get("path").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) sourceSchema).put("sha256", sha);
            }
            for (JsonNode tableEntry : db.get("tables")) {
                for (JsonNode file : tableEntry.get("files")) {
                    if (path.equals(file.get("path").asText())) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) file).put("sha256", sha);
                    }
                }
            }
        }
        files.put("manifest.json", json.writeValueAsBytes(manifest));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output), StandardCharsets.UTF_8)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private long targetSessions(String cretas, String smartbi) throws Exception {
        try (Connection conn = source.getConnection();
             var query = conn.prepareStatement("SELECT sum(sessions) FROM pg_stat_database WHERE datname IN (?, ?)")) {
            query.setString(1, cretas);
            query.setString(2, smartbi);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static void verifyOwnedConnection(String python, Path script, String host, String port,
                                               String pgUser, String systemId, String pgBin,
                                               String ownershipReceipt) throws Exception {
        Process p = new ProcessBuilder(python, script.toString(), "--verify-owner-only",
                "--target-host", host, "--target-port", port, "--target-user", pgUser,
                "--target-system-id", systemId, "--pg-bin", pgBin,
                "--ownership-receipt", ownershipReceipt).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        assertThat(p.waitFor()).as("owned PG connection verification failed").isZero();
    }

    private static String systemIdentifier(DataSource dataSource) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT system_identifier::text FROM pg_control_system()")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static int serverMajor(DataSource dataSource) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('server_version_num')")) {
            assertThat(rs.next()).isTrue();
            return Integer.parseInt(rs.getString(1)) / 10000;
        }
    }

    private static void dump(String pgBin, String host, String port, String pgUser, String database, Path output) throws Exception {
        String executable = java.io.File.separatorChar == '\\' ? "pg_dump.exe" : "pg_dump";
        Process p = new ProcessBuilder(Path.of(pgBin, executable).toString(), "-h", host, "-p", port, "-U", pgUser,
                "-d", database, "--format=custom", "--schema-only", "--file", output.toString()).inheritIO().start();
        assertThat(p.waitFor()).isZero();
    }

    private static void writeSidecar(Path dump, JsonNode schema, JsonNode migrations, int postgresMajor) throws Exception {
        Map<String, Object> sidecar = Map.of("format", "cretas-factory-schema-baseline/1", "archiveSha256", sha256(dump),
                "schemaFingerprint", schema.get("fingerprint").asText(), "migrationFingerprint", migrations.get("fingerprint").asText(),
                "postgresMajor", postgresMajor);
        Files.writeString(Path.of(dump + ".json"), new ObjectMapper().writeValueAsString(sidecar), StandardCharsets.UTF_8);
    }

    private static CliRun runCli(String python, Path script, Path packageFile, Path cretasDump, Path smartbiDump,
                               Path baseline, String host, String port, String pgUser, String systemId,
                               String cretasTarget, String smartbiTarget,
                               String pgBin, String ownershipReceipt, Path receipt) throws Exception {
        return runCli(python, script, packageFile, cretasDump, smartbiDump, baseline, host, port, pgUser, systemId,
                cretasTarget, smartbiTarget, pgBin, ownershipReceipt, receipt, true);
    }

    private static CliRun runCli(String python, Path script, Path packageFile, Path cretasDump, Path smartbiDump,
                               Path baseline, String host, String port, String pgUser, String systemId,
                               String cretasTarget, String smartbiTarget, String pgBin, String ownershipReceipt,
                               Path receipt, boolean apply) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of(python, script.toString(), "--package", packageFile.toString(),
                "--cretas-schema-baseline", cretasDump.toString(), "--smartbi-schema-baseline", smartbiDump.toString(),
                "--baseline-manifest", baseline.toString(), "--target-host", host, "--target-port", port,
                "--target-user", pgUser, "--target-system-id", systemId, "--cretas-target-db", cretasTarget,
                "--smartbi-target-db", smartbiTarget, "--pg-bin", pgBin,
                "--ownership-receipt", ownershipReceipt));
        if (apply) command.add("--apply");
        if (receipt != null) {
            command.add("--receipt");
            command.add(receipt.toString());
        }
        ProcessBuilder process = new ProcessBuilder(command).redirectErrorStream(true);
        process.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = process.start();
        String output;
        try (InputStream in = p.getInputStream()) { output = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        System.out.print(output);
        int exit = p.waitFor();
        JsonNode body = receipt == null || !Files.isRegularFile(receipt) ? new ObjectMapper().createObjectNode()
                : new ObjectMapper().readTree(Files.readString(receipt));
        return new CliRun(exit, body, output);
    }

    private static Map<String, DatabaseIdentity> databaseOids(DataSource dataSource, Map<String, String> databases) throws Exception {
        Map<String, DatabaseIdentity> result = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT oid FROM pg_database WHERE datname = ?")) {
            for (Map.Entry<String, String> entry : databases.entrySet()) {
                ps.setString(1, entry.getValue());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("database exists before CLI: " + entry.getValue()).isTrue();
                    result.put(entry.getKey(), new DatabaseIdentity(entry.getValue(), rs.getLong(1)));
                }
            }
        }
        return result;
    }

    private static boolean databaseExists(DataSource dataSource, String database) throws Exception {
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_database WHERE datname = ?")) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1) != 0;
            }
        }
    }

    private static void assertReceiptDatabaseIds(JsonNode receipt, Map<String, DatabaseIdentity> expected) {
        assertThat(receipt).isNotNull();
        java.util.Iterator<Map.Entry<String, JsonNode>> states = receipt.path("databases").fields();
        while (states.hasNext()) {
            Map.Entry<String, JsonNode> entry = states.next();
            String key = entry.getKey();
            JsonNode state = entry.getValue();
            String database = state.path("database").asText();
            assertThat(database).as("receipt database for " + key).isEqualTo(expected.get(key).name());
            JsonNode actual = state.get("actualDbId");
            if (actual == null || actual.isNull()) actual = state.get("actualdbid");
            assertThat(actual).as("actualDbId for " + key).isNotNull();
            assertThat(actual.asLong()).isEqualTo(expected.get(key).oid());
        }
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) files.put(entry.getName(), zin.readAllBytes());
        }
        return files;
    }

    private static PGSimpleDataSource dataSource(String jdbcUrl) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        return ds;
    }

    private static String url() { return System.getenv("CRETAS_WORKFLOW_PG_URL"); }

    private static void exec(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) { st.execute(sql); }
    }

    private static String q(String identifier) { return "\"" + identifier + "\""; }
}
