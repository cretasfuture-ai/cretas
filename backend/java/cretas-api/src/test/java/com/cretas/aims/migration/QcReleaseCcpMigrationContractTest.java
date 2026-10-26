package com.cretas.aims.migration;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口 11/13 两个迁移的契约 —— H2 测试库按实体建表, 不跑 Flyway, 所以迁移文本本身要单独钉住:
 * <ol>
 *   <li>可重放 (全部 IF NOT EXISTS / 约束带存在性判断);</li>
 *   <li>零回归的那个默认值: {@code inspection_required NOT NULL DEFAULT FALSE};</li>
 *   <li>SQL 里的 CHECK 取值与 Java 常量<b>同一份</b> (两份必漂: 改了常量忘了约束 = 线上 23514)。</li>
 * </ol>
 */
@DisplayName("质检放行 / CCP 迁移契约")
class QcReleaseCcpMigrationContractTest {

    private static final Path DIR = Path.of("src/main/resources/db/flyway");

    private static String read(String name) throws IOException {
        String sql = Files.readString(DIR.resolve(name), StandardCharsets.UTF_8);
        assertThat(sql.length()).as(name + " 读成空 ⇒ 下面全是恒真式").isGreaterThan(500);
        return sql;
    }

    private static String checkList(String sql, String column) {
        Matcher m = Pattern.compile(column + "\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(m.find()).as("找不到 " + column + " 的 CHECK 取值").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("V20261103_88: 需检默认 FALSE、可重放、CHECK 与 Java 常量一致")
    void qcReleaseMigration() throws IOException {
        String sql = read("V20261103_88__qc_release_inspection_hold.sql");
        String upper = sql.toUpperCase(Locale.ROOT);
        assertThat(upper).contains("ADD COLUMN IF NOT EXISTS INSPECTION_REQUIRED BOOLEAN NOT NULL DEFAULT FALSE");
        assertThat(upper).contains("ADD COLUMN IF NOT EXISTS INSPECTION_STATUS VARCHAR(20)");
        assertThat(upper).contains("CREATE TABLE IF NOT EXISTS QC_RELEASE_RECORDS");
        assertThat(upper).contains("IF NOT EXISTS (SELECT 1 FROM PG_CONSTRAINT WHERE CONNAME = 'CK_SFI_INSPECTION_STATUS')");
        // BaseEntity 审计列齐全 (database-entity-sync)
        assertThat(upper).contains("CREATED_AT").contains("UPDATED_AT").contains("DELETED_AT");

        String sfiStatuses = checkList(sql, "inspection_status");
        assertThat(sfiStatuses).contains("'" + SemiFinishedInventory.InspectionStatus.PENDING + "'")
                .contains("'" + SemiFinishedInventory.InspectionStatus.RELEASED + "'")
                .contains("'" + SemiFinishedInventory.InspectionStatus.REJECTED + "'");
        String targets = checkList(sql, "target_type");
        assertThat(targets).contains("'" + QcReleaseRecord.TargetType.SEMI_FINISHED + "'")
                .contains("'" + QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH + "'")
                .contains("'" + QcReleaseRecord.TargetType.FINISHED_GOODS + "'");
        String decisions = checkList(sql, "decision");
        assertThat(decisions).contains("'" + QcReleaseRecord.Decision.RELEASED + "'")
                .contains("'" + QcReleaseRecord.Decision.REJECTED + "'");
    }

    @Test
    @DisplayName("V20261103_89: CCP 挂工序 + 记录补列全部可重放, 存量偏离不被放松 (不回填 handled_at)")
    void ccpMigration() throws IOException {
        String sql = read("V20261103_89__ccp_step_monitoring.sql");
        String upper = sql.toUpperCase(Locale.ROOT);
        assertThat(upper).contains("ALTER TABLE HACCP_CHECKPOINTS")
                .contains("ADD COLUMN IF NOT EXISTS WORK_PROCESS_ID VARCHAR(50)");
        for (String col : new String[]{"PRODUCTION_BATCH_ID", "PRODUCTION_PLAN_ID", "PROCESS_ORDER", "SOURCE",
                "CRITICAL_LIMIT_MIN_SNAPSHOT", "CRITICAL_LIMIT_MAX_SNAPSHOT", "UNIT_SNAPSHOT", "OPERATOR_NAME",
                "DEVIATION_HANDLED_AT", "DEVIATION_HANDLED_BY", "DEVIATION_HANDLED_BY_NAME"}) {
            assertThat(upper).as(col).contains("ADD COLUMN IF NOT EXISTS " + col);
        }
        // 存量偏离必须保持「未处理」: 迁移里不许出现给 deviation_handled_at 赋值的 UPDATE
        assertThat(upper).doesNotContain("UPDATE HACCP_MONITORING_RECORDS");
        assertThat(upper).contains("CREATE INDEX IF NOT EXISTS IDX_HACCP_MONITORING_OPEN_DEVIATION_PLAN");
    }
}
