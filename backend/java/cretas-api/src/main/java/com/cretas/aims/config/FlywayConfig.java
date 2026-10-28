package com.cretas.aims.config;

import com.cretas.aims.dev.lineage.LineageBackfillMode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.pattern.ValidatePattern;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reject migration drift before startup without rewriting schema history.
 *
 * <p>Validation must not count a <em>pending</em> migration as drift: a migration that exists
 * in the jar but not yet in the history table is exactly what {@code migrate()} is about to
 * apply. Flyway's {@code validate()} reports pending migrations as invalid unless
 * {@code *:pending} is in {@code ignoreMigrationPatterns}, so a bare {@code validate()} here
 * made every release that carries a new migration fail to start (2026-10-03: V20261101_48 →
 * "Detected resolved migration not applied to database"; blue/green kept the old slot live).
 * Checksum mismatches, missing and failed migrations still fail startup, and nothing is ever
 * repaired automatically.
 */
@org.springframework.context.annotation.Configuration
public class FlywayConfig {

    public static final String STARTUP_SAFETY_MARKER = "FLYWAY_VALIDATE_WITHOUT_REPAIR_V2_PENDING_OK";

    @Bean
    @Profile(LineageBackfillMode.NOT_ACTIVE)
    public FlywayMigrationStrategy migrationStrategy() {
        return (Flyway flyway) -> {
            // Explicit validation also protects profiles configured with validateOnMigrate=false.
            // A mismatch must fail startup; reconciling deployed history requires a separate review.
            pendingTolerantValidator(flyway).validate();
            flyway.migrate();
        };
    }

    /**
     * 一次性回填进程（{@link LineageBackfillMode#PROFILE}）的 Flyway 策略：<b>只校验，永不迁移</b>。
     *
     * <p>回填是用 jar 在生产机上另起的旁路进程。它若带着比线上更新的迁移启动，默认策略会替线上
     * 把 schema 迁了 —— 迁移只该由正式发布做。所以这里：漂移照样拒绝（同 {@link #migrationStrategy()}），
     * 另外<b>有任何 pending 迁移就拒绝启动</b>（说明这个 jar 和库不是同一版，回填读数不可信）。
     */
    @Bean
    @Profile(LineageBackfillMode.PROFILE)
    public FlywayMigrationStrategy lineageBackfillValidateOnlyStrategy() {
        return (Flyway flyway) -> {
            pendingTolerantValidator(flyway).validate();
            MigrationInfo[] pending = flyway.info().pending();
            if (pending.length > 0) {
                StringBuilder versions = new StringBuilder();
                for (MigrationInfo info : pending) {
                    if (versions.length() > 0) {
                        versions.append(", ");
                    }
                    versions.append(info.getVersion());
                }
                throw new IllegalStateException("[lineage-backfill] 库里还有 " + pending.length
                        + " 个未执行的迁移 (" + versions + "): 这个 jar 比线上库新。"
                        + "回填进程不迁移 schema —— 先走正式发布, 再用线上同一个 jar 跑回填。");
            }
            // ⛔ 刻意不调 flyway.migrate()。
        };
    }

    /** Same configuration (data source, locations, placeholders, ...) plus {@code *:pending}. */
    static Flyway pendingTolerantValidator(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        List<ValidatePattern> patterns = new ArrayList<>();
        if (configuration.getIgnoreMigrationPatterns() != null) {
            patterns.addAll(Arrays.asList(configuration.getIgnoreMigrationPatterns()));
        }
        patterns.add(ValidatePattern.fromPattern("*:pending"));
        return Flyway.configure(configuration.getClassLoader())
                .configuration(configuration)
                .ignoreMigrationPatterns(patterns.toArray(new ValidatePattern[0]))
                .load();
    }
}
