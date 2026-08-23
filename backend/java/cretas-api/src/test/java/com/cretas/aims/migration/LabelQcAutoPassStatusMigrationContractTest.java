package com.cretas.aims.migration;

import com.cretas.aims.entity.enums.LabelQcPhotoStatus;
import com.cretas.aims.entity.enums.LabelQcTaskStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LabelQcAutoPassStatusMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src", "main", "resources", "db", "flyway",
            "V20261030_18__label_qc_auto_pass_status.sql");

    @Test
    void migrationRebuildsBothStatusConstraintsWithEveryEnumValue() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("DROP CONSTRAINT IF EXISTS chk_label_qc_task_status")
                .contains("ADD CONSTRAINT chk_label_qc_task_status CHECK")
                .contains("DROP CONSTRAINT IF EXISTS chk_label_qc_photo_status")
                .contains("ADD CONSTRAINT chk_label_qc_photo_status CHECK");

        String taskConstraint = sql.substring(
                sql.indexOf("ADD CONSTRAINT chk_label_qc_task_status CHECK"),
                sql.indexOf("ALTER TABLE label_qc_photos"));
        String photoConstraint = sql.substring(
                sql.indexOf("ADD CONSTRAINT chk_label_qc_photo_status CHECK"));

        for (LabelQcTaskStatus status : LabelQcTaskStatus.values()) {
            assertThat(taskConstraint).contains("'" + status.name() + "'");
        }
        for (LabelQcPhotoStatus status : LabelQcPhotoStatus.values()) {
            assertThat(photoConstraint).contains("'" + status.name() + "'");
        }
    }
}
