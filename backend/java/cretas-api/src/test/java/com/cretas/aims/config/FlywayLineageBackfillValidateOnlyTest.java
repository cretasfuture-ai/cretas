package com.cretas.aims.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 回填进程的 Flyway 策略：只校验，有 pending 迁移就拒绝启动，永不 migrate。
 * 对照：线上策略（{@link FlywayConfig#migrationStrategy()}）对同一个 pending 会执行它 ——
 * 见 {@link FlywayNoAutomaticRepairContractTest#pendingMigrationWithoutDriftIsAppliedAtStartup}。
 */
@DisplayName("FlywayConfig: lineage-backfill 只校验不迁移")
class FlywayLineageBackfillValidateOnlyTest {

    @TempDir
    Path migrations;

    @Test
    @DisplayName("库与 jar 同版本 → 通过, 且不调用 migrate()")
    void upToDateSchemaPassesWithoutMigrating() throws Exception {
        Files.writeString(migrations.resolve("V1__synthetic.sql"), "CREATE TABLE synthetic_bf (value INT);");
        String url = "jdbc:h2:mem:backfill_flyway_uptodate;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + migrations).load().migrate();
        Flyway flyway = spy(Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations).load());

        new FlywayConfig().lineageBackfillValidateOnlyStrategy().migrate(flyway);

        verify(flyway, never()).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    @DisplayName("jar 比库新 (有 pending) → 拒绝启动, pending 迁移没有被执行")
    void pendingMigrationRefusesToStartAndIsNotApplied() throws Exception {
        Files.writeString(migrations.resolve("V1__synthetic.sql"),
                "CREATE TABLE synthetic_bf2 (value INT); INSERT INTO synthetic_bf2 VALUES (1);");
        String url = "jdbc:h2:mem:backfill_flyway_pending;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + migrations).load().migrate();
        Files.writeString(migrations.resolve("V2__synthetic_pending.sql"), "INSERT INTO synthetic_bf2 VALUES (2);");
        Flyway flyway = spy(Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations).load());

        assertThatThrownBy(() -> new FlywayConfig().lineageBackfillValidateOnlyStrategy().migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 个未执行的迁移")
                .hasMessageContaining("2");

        verify(flyway, never()).migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             ResultSet rows = c.createStatement().executeQuery("SELECT COUNT(*) FROM synthetic_bf2")) {
            rows.next();
            assertThat(rows.getInt(1)).as("pending V2 不能被执行").isEqualTo(1);
        }
    }
}
