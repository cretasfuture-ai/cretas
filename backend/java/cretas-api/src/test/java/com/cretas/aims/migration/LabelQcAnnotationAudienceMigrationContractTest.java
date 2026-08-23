package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LabelQcAnnotationAudienceMigrationContractTest {

    @Test
    void migrationDefaultsHistoricalRowsToCustomerAndIndexesPlatformQueue() throws IOException {
        String resource = "/db/flyway/V20261030_19__label_qc_annotation_audience.sql";
        String sql;
        try (var input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("row_version BIGINT NOT NULL DEFAULT 0")
                .contains("audience VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER'")
                .contains("audience IN ('CUSTOMER', 'PLATFORM')")
                .contains("idx_label_qc_annotation_audience_queue")
                .contains("WHERE deleted_at IS NULL");
    }
}
