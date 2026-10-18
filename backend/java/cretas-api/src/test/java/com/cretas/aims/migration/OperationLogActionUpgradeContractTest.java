package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

class OperationLogActionUpgradeContractTest {
    @Test void exactUpgradePreservesExistingAuditRowsAndAcceptsFullBusinessAction() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:p14-audit-upgrade;MODE=PostgreSQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE operation_logs (id BIGINT PRIMARY KEY, action VARCHAR(20) NOT NULL)");
            statement.execute("CREATE INDEX p14_existing_action_index ON operation_logs(action)");
            statement.execute("INSERT INTO operation_logs VALUES (1,'UPDATE')");
            assertThatThrownBy(() -> statement.execute("INSERT INTO operation_logs VALUES (2,'RECALCULATE_SETTLED_COSTS')"))
                    .isInstanceOf(SQLException.class);
            var migration = new ClassPathResource("db/flyway/V20261103_68__widen_operation_log_action.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            statement.execute("INSERT INTO operation_logs VALUES (2,'RECALCULATE_SETTLED_COSTS')");
            ScriptUtils.executeSqlScript(connection, migration);
            try (var rows = statement.executeQuery("SELECT id,action FROM operation_logs ORDER BY id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("action")).isEqualTo("UPDATE");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("action")).isEqualTo("RECALCULATE_SETTLED_COSTS");
                assertThat(rows.next()).isFalse();
            }
            try (var indexes = connection.getMetaData().getIndexInfo(null, null, "OPERATION_LOGS", false, false)) {
                boolean preserved = false;
                while (indexes.next()) preserved |= "P14_EXISTING_ACTION_INDEX".equals(indexes.getString("INDEX_NAME"));
                assertThat(preserved).isTrue();
            }
        }
    }
}
