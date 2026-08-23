package com.cretas.aims.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SalesInvoiceTodoOutboxMigrationContractTest {

    @Test
    void completionTriggerSeedsOnlyNewCompletionTransitions() throws IOException {
        String resource = "/db/flyway/V20261030_20__sales_invoice_todo_outbox.sql";
        String sql;
        try (var input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE OR REPLACE FUNCTION set_sales_order_invoice_todo_on_completion()")
                .contains("OLD.status IS DISTINCT FROM ''COMPLETED''")
                .contains("NEW.status = ''COMPLETED''")
                .contains("NEW.invoice_todo_status IS NULL")
                .contains("NEW.invoice_todo_status := ''PENDING''")
                .contains("NEW.invoice_todo_attempts := 0")
                .contains("DROP TRIGGER IF EXISTS trg_sales_order_invoice_todo_on_completion ON sales_orders")
                .contains("CREATE TRIGGER trg_sales_order_invoice_todo_on_completion")
                .contains("invoice_todo_status VARCHAR(20)");
    }
}
