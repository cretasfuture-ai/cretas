package com.cretas.aims.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs the actual public-schema migration in a new database on the disposable PG server. */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class AttachmentSopMigrationPostgresContractTest {
    @Test
    void freshAndUpgradeKeepLegacyAttachmentsAndEnforceExpandedEnumIdempotently() throws Exception {
        String parentUrl = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String database = "cretas_workflow_verify_sop_" + UUID.randomUUID().toString().replace("-", "");
        String childUrl = DisposablePostgresTargetGuard.requireSafeUrl(
                parentUrl.substring(0, parentUrl.lastIndexOf('/') + 1) + database);
        boolean created = false;
        try (Connection parent = DriverManager.getConnection(parentUrl, user, password);
             Statement admin = parent.createStatement()) {
            try {
                admin.execute("CREATE DATABASE " + database);
                created = true;
                try (Connection child = DriverManager.getConnection(childUrl, user, password);
                     Statement sql = child.createStatement()) {
                    assertThat(child.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
                    String migration = new ClassPathResource(
                            "db/flyway/V20261105_94__attachment_entity_type_add_work_process_equipment.sql")
                            .getContentAsString(StandardCharsets.UTF_8);
                    // Fresh database: real migration must honor its no-table guard.
                    sql.execute(migration);
                    // Old deployed shape: uploaded_by exists, namespace columns do not.
                    sql.execute("CREATE TABLE public.attachments (id bigint primary key, factory_id varchar(50) not null, "
                            + "entity_type varchar(50) not null, uploaded_by bigint not null, "
                            + "CONSTRAINT chk_att_entity_type CHECK (entity_type IN ('CUSTOMER','GENERIC')))");
                    sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_by) VALUES "
                            + "(1,'F-SOP','CUSTOMER',41),(2,'F-SOP','GENERIC',42)");
                    sql.execute(migration);
                    try (var rows = sql.executeQuery("SELECT id, uploaded_by, uploaded_actor_type, uploaded_actor_id "
                            + "FROM public.attachments WHERE id IN (1,2) ORDER BY id")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1L);
                        assertThat(rows.getLong(2)).isEqualTo(41L);
                        assertThat(rows.getString(3)).isEqualTo("FACTORY_USER");
                        assertThat(rows.getLong(4)).isEqualTo(41L);
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(2L);
                        assertThat(rows.getLong(2)).isEqualTo(42L);
                        assertThat(rows.getString(3)).isEqualTo("FACTORY_USER");
                        assertThat(rows.getLong(4)).isEqualTo(42L);
                    }
                    sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type) VALUES "
                            + "(3,'F-SOP','WORK_PROCESS'),(4,'F-SOP','EQUIPMENT')");
                    sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_by) VALUES (5,'F-SOP','CUSTOMER',77)");
                    try (var rows = sql.executeQuery("SELECT uploaded_actor_type, uploaded_actor_id "
                            + "FROM public.attachments WHERE id=5")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("FACTORY_USER");
                        assertThat(rows.getLong(2)).isEqualTo(77L);
                    }
                    // Real old-JAR update shape: it knows only uploaded_by. Existing explicit
                    // namespace is never silently rewritten, so changing the uploader is rejected.
                    assertThrows(SQLException.class,
                            () -> sql.execute("UPDATE public.attachments SET uploaded_by=88 WHERE id=5"));
                    // An old-JAR metadata update that leaves uploaded_by untouched remains valid.
                    sql.execute("UPDATE public.attachments SET entity_type='CUSTOMER' WHERE id=5");
                    try (var rows = sql.executeQuery("SELECT uploaded_actor_type, uploaded_actor_id "
                            + "FROM public.attachments WHERE id=5")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isEqualTo("FACTORY_USER");
                        assertThat(rows.getLong(2)).isEqualTo(77L);
                    }
                    sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_actor_type,uploaded_actor_id) "
                            + "VALUES (6,'F-SOP','CUSTOMER','PLATFORM_ADMIN',41)");
                    try (var rows = sql.executeQuery("SELECT uploaded_by, uploaded_actor_type, uploaded_actor_id "
                            + "FROM public.attachments WHERE id=6")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getObject(1)).isNull();
                        assertThat(rows.getString(2)).isEqualTo("PLATFORM_ADMIN");
                        assertThat(rows.getLong(3)).isEqualTo(41L);
                    }
                    assertThrows(SQLException.class,
                            () -> sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_by,uploaded_actor_type,uploaded_actor_id) "
                                    + "VALUES (7,'F-SOP','CUSTOMER',99,'PLATFORM_ADMIN',99)"));
                    assertThrows(SQLException.class,
                            () -> sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_actor_type) "
                                    + "VALUES (8,'F-SOP','CUSTOMER','FACTORY_USER')"));
                    assertThrows(SQLException.class,
                            () -> sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_actor_id) "
                                    + "VALUES (9,'F-SOP','CUSTOMER',123)"));
                    assertThrows(SQLException.class,
                            () -> sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_by,uploaded_actor_type,uploaded_actor_id) "
                                    + "VALUES (10,'F-SOP','CUSTOMER',99,'FACTORY_USER',100)"));
                    assertThrows(SQLException.class,
                            () -> sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type,uploaded_actor_type,uploaded_actor_id) "
                                    + "VALUES (11,'F-SOP','CUSTOMER','UNKNOWN',42)"));
                    assertThrows(SQLException.class,
                            () -> sql.execute("INSERT INTO public.attachments (id,factory_id,entity_type) "
                                    + "VALUES (12,'F-SOP','UNRECOGNIZED_SOP_TYPE')"));
                    sql.execute(migration);
                    try (var rows = sql.executeQuery("SELECT entity_type FROM public.attachments ORDER BY id")) {
                        java.util.List<String> types = new java.util.ArrayList<>();
                        while (rows.next()) types.add(rows.getString(1));
                        assertThat(types).containsExactly("CUSTOMER", "GENERIC", "WORK_PROCESS", "EQUIPMENT", "CUSTOMER", "CUSTOMER");
                    }
                }
            } finally {
                if (created) admin.execute("DROP DATABASE " + database);
            }
        }
    }
}
