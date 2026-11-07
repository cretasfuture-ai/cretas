package com.cretas.aims.service.factorydata;

import com.cretas.aims.service.factorydata.FactoryDataCatalog.Category;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表分类与「哪些 schema 算副本」的两处来源必须一致:
 * Java 删除时清掉该厂在副本里的行, scripts/cron/backup-copy-retention.sh 按 30 天整份清掉副本 ——
 * 两边认的不是同一批, 就会有副本既不被逐行清、也不会过期。
 */
class FactoryDataCatalogContractTest {

    @Test
    void copySchemaPatternIsIdenticalToTheRetentionScript() throws Exception {
        String script = Files.readString(Path.of("../../../scripts/cron/backup-copy-retention.sh"));
        Matcher m = Pattern.compile("(?m)^COPY_SCHEMA_RE='([^']+)'$").matcher(script);
        assertThat(m.find()).as("retention script defines COPY_SCHEMA_RE").isTrue();
        assertThat(m.group(1)).isEqualTo(FactoryDataCatalog.COPY_SCHEMA_REGEX);
    }

    @Test
    void copySchemaPatternMatchesTheCopiesFoundOnProdAndNothingElse() {
        for (String s : new String[]{"tenant_purge_68", "f006_clear_71", "f006_clean_20261024", "legacy_retired",
                "backup_f006_20261024", "bak_trf_20260809_1790", "mock_rest_bak_20260809"}) {
            assertThat(FactoryDataCatalog.isCopySchema(s)).as(s).isTrue();
        }
        for (String s : new String[]{"public", "pg_catalog", "analytics", "information_schema", "bak", "backup_"}) {
            assertThat(FactoryDataCatalog.isCopySchema(s)).as(s).isFalse();
        }
    }

    @Test
    void categoriesOfTheTablesNamedInTheRunbook() {
        assertThat(FactoryDataCatalog.categorize("factories")).isEqualTo(Category.FACTORY_ROOT);
        assertThat(FactoryDataCatalog.categorize("factory_data_requests")).isEqualTo(Category.RECEIPT);
        assertThat(FactoryDataCatalog.categorize("factory_erasure_audit_retention")).isEqualTo(Category.RECEIPT);
        assertThat(FactoryDataCatalog.categorize("platform_data_access_log")).isEqualTo(Category.AUDIT);
        assertThat(FactoryDataCatalog.categorize("smart_bi_llm_egress_audit")).isEqualTo(Category.AUDIT);
        assertThat(FactoryDataCatalog.categorize("smart_bi_distillation_samples")).isEqualTo(Category.TRAINING);
        assertThat(FactoryDataCatalog.categorize("training_consent")).isEqualTo(Category.BUSINESS);
        assertThat(FactoryDataCatalog.categorize("ai_training_samples")).isEqualTo(Category.BUSINESS);
        assertThat(FactoryDataCatalog.categorize("backup_sku_units_20260802")).isEqualTo(Category.COPY);
        assertThat(FactoryDataCatalog.categorize("flyway_schema_history")).isEqualTo(Category.LEDGER);
        assertThat(FactoryDataCatalog.categorize("sales_orders")).isEqualTo(Category.BUSINESS);
        // 每张审计表都必须有留存取数表达式, 否则删除时直接报错
        for (String t : FactoryDataCatalog.AUDIT_TABLES) {
            assertThat(FactoryAuditRetention.MAPPINGS).as(t).containsKey(t);
        }
    }

    @Test
    void credentialColumnsAreRecognisedWithoutCatchingOrdinaryColumns() {
        for (String c : new String[]{"password_hash", "api_key", "access_key_id", "app_key", "refresh_token",
                "client_secret", "otp_code", "salt"}) {
            assertThat(FactoryDataCatalog.isCredentialColumn(c)).as(c).isTrue();
        }
        for (String c : new String[]{"keyword", "key_points", "remarks", "hotpot_flag", "factory_id"}) {
            assertThat(FactoryDataCatalog.isCredentialColumn(c)).as(c).isFalse();
        }
        for (String c : new String[]{"token_id", "token_usage", "token_count", "usage_token"}) {
            assertThat(FactoryDataCatalog.isCredentialColumn(c)).as(c).isFalse();
        }
        for (String c : new String[]{"token", "access_token", "refresh_token", "token_value", "idempotency_token"}) {
            assertThat(FactoryDataCatalog.isCredentialColumn(c)).as(c).isTrue();
        }
        assertThat(FactoryDataCatalog.shouldRedactColumn("token_label", java.sql.Types.VARCHAR)).isTrue();
        for (String unknownToken : new String[]{"tokenid", "input_tokens", "tokens", "auth_token", "reset_token"}) {
            assertThat(FactoryDataCatalog.shouldRedactColumn(unknownToken, java.sql.Types.VARCHAR)).as(unknownToken).isTrue();
        }
        assertThat(FactoryDataCatalog.shouldRedactColumn("token_id", java.sql.Types.BIGINT)).isTrue();
        assertThat(FactoryDataCatalog.shouldRedactColumn("users", "token_id", java.sql.Types.BIGINT, 0)).isFalse();
        assertThat(FactoryDataCatalog.shouldRedactColumn("users", "token_id", java.sql.Types.VARCHAR, 0)).isTrue();
        assertThat(FactoryDataCatalog.shouldRedactColumn("users", "token_usage", java.sql.Types.NUMERIC, 2)).isFalse();
        assertThat(FactoryDataCatalog.shouldRedactColumn("ai_intent_configs", "max_tokens",
                java.sql.Types.BIGINT, 0)).isFalse();
        assertThat(FactoryDataCatalog.shouldRedactColumn("ai_intent_configs", "max_tokens",
                java.sql.Types.NUMERIC, 2)).isTrue();
        assertThat(FactoryDataCatalog.shouldRedactColumn("smart_bi_llm_usage", "input_tokens",
                java.sql.Types.INTEGER, 0)).isFalse();
        assertThat(FactoryDataCatalog.shouldRedactColumn("unknown_usage", "input_tokens",
                java.sql.Types.BIGINT, 0)).isTrue();
        assertThat(FactoryDataCatalog.shouldRedactColumn("scheduler_run_results", "token_usage_state",
                java.sql.Types.VARCHAR, null)).isFalse();
        assertThat(FactoryDataCatalog.shouldRedactColumn("pos_connections", "token_expires_at",
                java.sql.Types.TIMESTAMP, null)).isFalse();
    }

    @Test
    void redactionPreservesUniqueColumnShapeWithoutRetainingSecrets() {
        byte[] key = FactoryDataRedaction.newKey();
        assertThat(FactoryDataRedaction.value("users", "refresh_token", "source-secret-a", key, 64))
                .startsWith("r");
        assertThat(FactoryDataRedaction.value("users", "refresh_token", "source-secret-b", key, 64))
                .startsWith("r")
                .doesNotContain("source-secret");
        assertThat(FactoryDataRedaction.value("users", "refresh_token", "source-secret-a", key, 64))
                .isEqualTo(FactoryDataRedaction.value("users", "refresh_token", "source-secret-a", key, 64));
        assertThat(FactoryDataRedaction.value("users", "refresh_token", "source-secret-a", key, 64))
                .isNotEqualTo(FactoryDataRedaction.value("users", "refresh_token", "source-secret-b", key, 64));
    }

    @Test
    void zipEntryPathsCannotClimbOutOfTheArchive() {
        assertThat(FactoryDataPackageWriter.safeEntryPath("attachments/b/F1/../../etc/x"))
                .isEqualTo("attachments/b/F1/%2E%2E/%2E%2E/etc/x");
        assertThat(FactoryDataPackageWriter.safeEntryPath("/abs//a\\..\\b")).isEqualTo("abs/a%5C..%5Cb");
    }

    @Test
    void csvGuardsAgainstFormulaInjection() {
        assertThat(FactoryDataPackageWriter.csv("=1+1")).isEqualTo("\"'=1+1\"");
        assertThat(FactoryDataPackageWriter.csv("\t=cmd")).startsWith("\"'");
        assertThat(FactoryDataPackageWriter.csv("-5")).isEqualTo("-5");
        assertThat(FactoryDataPackageWriter.csv("plain")).isEqualTo("plain");
    }

    @Test
    void jsonWireTypeContractIsExplicitForLosslessAndUnknownPostgresTypes() {
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.ARRAY, "_int4")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.ARRAY, "_int8")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.ARRAY, "_text")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.ARRAY, "_numeric")).isFalse();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.TIMESTAMP_WITH_TIMEZONE,
                "timestamptz")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.BINARY, "bytea")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.OTHER, "jsonb")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.OTHER, "uuid")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.OTHER, "vector")).isTrue();
        assertThat(FactoryDataPackageWriter.supportedJsonType(java.sql.Types.OTHER, "mystery_type")).isFalse();
    }
}
