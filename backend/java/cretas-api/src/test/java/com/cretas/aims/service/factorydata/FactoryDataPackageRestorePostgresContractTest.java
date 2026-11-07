package com.cretas.aims.service.factorydata;

import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies v2 export metadata; complete restore requires the protected schema baseline CLI. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactoryDataPackageRestorePostgresContractTest {
    private record CliRun(int exitCode, JsonNode receipt) {}
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
        long request = service.startExport(OPERATOR, FACTORY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> service.writeExport(request, OPERATOR, FACTORY,
                FactoryDataService.AttachmentMode.SKIP, Set.of(FactoryDataService.Format.CSV), output))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
        assertThat(new String(output.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("manifest.json");
    }

    @Test
    void javaExportRoundTripsThroughProtectedCliWhenExplicitlyEnabled() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("CRETAS_WORKFLOW_CLI_VERIFY")));
        String pgBin = System.getenv("CRETAS_WORKFLOW_PG_BIN");
        String python = System.getenv().getOrDefault("CRETAS_WORKFLOW_PYTHON", "python");
        String pgUser = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "postgres");
        String ownershipReceipt = System.getenv("CRETAS_WORKFLOW_PG_OWNER_RECEIPT");
        assumeTrue(pgBin != null && !pgBin.isBlank());
        assumeTrue(ownershipReceipt != null && !ownershipReceipt.isBlank());
        Path repo = Path.of("..", "..", "..").toAbsolutePath().normalize();
        Path script = repo.resolve("scripts/backup/restore-factory-export-v2.py");
        assumeTrue(Files.isRegularFile(script));
        String jdbc = url();
        java.net.URI uri = java.net.URI.create(jdbc.substring("jdbc:".length()));
        String host = uri.getHost();
        String port = Integer.toString(uri.getPort());
        String systemId = systemIdentifier(dataSource(url()));
        verifyOwnedConnection(python, script, host, port, pgUser, systemId, pgBin, ownershipReceipt);
        setUpSources();
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
            FactoryDataService service = FactoryDataServiceFixture.create(source, smartbiSource, Set.of(1L), sourceSchema);
            long request = service.startExport(OPERATOR, FACTORY);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            service.writeExport(request, OPERATOR, FACTORY, FactoryDataService.AttachmentMode.SKIP,
                    Set.of(FactoryDataService.Format.JSON), output);
            Files.write(packagePath, output.toByteArray());
            dump(pgBin, host, port, pgUser, "postgres", cretasDump);
            dump(pgBin, host, port, pgUser, smartbiDb, smartbiDump);
            exec(source, "CREATE DATABASE " + q(cretasTarget) + "; CREATE DATABASE " + q(smartbiTarget));
            Map<String, DatabaseIdentity> happyDatabaseIds = databaseOids(source, Map.of("CRETAS", cretasTarget, "SMARTBI", smartbiTarget));
            Map<String, byte[]> packageFiles = unzip(output.toByteArray());
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
            CliRun happy = runCli(python, script, packagePath, cretasDump, smartbiDump, baselineManifest,
                    host, port, pgUser, systemId, cretasTarget, smartbiTarget, pgBin, ownershipReceipt,
                    happyReceipt);
            assertThat(happy.exitCode()).isZero();
            assertThat(happy.receipt().get("status").asText()).isEqualTo("RESTORED_AND_VERIFIED");
            assertReceiptDatabaseIds(happy.receipt(), happyDatabaseIds);

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
        Process p = new ProcessBuilder(Path.of(pgBin, "pg_dump.exe").toString(), "-h", host, "-p", port, "-U", pgUser,
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
        List<String> command = new java.util.ArrayList<>(List.of(python, script.toString(), "--package", packageFile.toString(),
                "--cretas-schema-baseline", cretasDump.toString(), "--smartbi-schema-baseline", smartbiDump.toString(),
                "--baseline-manifest", baseline.toString(), "--target-host", host, "--target-port", port,
                "--target-user", pgUser, "--target-system-id", systemId, "--cretas-target-db", cretasTarget,
                "--smartbi-target-db", smartbiTarget, "--pg-bin", pgBin,
                "--ownership-receipt", ownershipReceipt, "--apply"));
        if (receipt != null) {
            command.add("--receipt");
            command.add(receipt.toString());
        }
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) { in.transferTo(System.out); }
        int exit = p.waitFor();
        JsonNode body = receipt == null || !Files.isRegularFile(receipt) ? new ObjectMapper().createObjectNode()
                : new ObjectMapper().readTree(Files.readString(receipt));
        return new CliRun(exit, body);
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
