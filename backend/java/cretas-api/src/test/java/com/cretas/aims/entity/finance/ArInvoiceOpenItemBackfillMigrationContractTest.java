package com.cretas.aims.entity.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AR invoice open-item backfill Flyway contract.
 *
 * <p>Mirrors {@link ApPayableSettlementMigrationContractTest} for the AR side (V20261101_27):
 * the migration must not blanket-set {@code outstanding_amount = amount} for historical
 * AR_INVOICE rows (that would silently reopen invoices customers already paid off — there is
 * no per-invoice AR settlement/allocation mechanism to know otherwise), must fall back to
 * NEEDS_RECONCILIATION when payment attribution isn't safe, and must widen the open-item CHECK
 * constraint to cover AR_INVOICE (not just AP_INVOICE).
 */
@DisplayName("AR invoice open-item backfill Flyway legacy-data contract")
class ArInvoiceOpenItemBackfillMigrationContractTest {

    private static final String MIGRATION =
            "db/flyway/V20261101_27__ar_invoice_open_item_backfill.sql";

    private String readMigration() throws Exception {
        ClassPathResource resource = new ClassPathResource(MIGRATION);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("only backfills AR_INVOICE rows that are still NULL — idempotent, does not touch AP")
    void onlyTargetsNullOutstandingArInvoiceRows() throws Exception {
        String sql = readMigration();

        assertThat(sql).contains("transaction_type = 'AR_INVOICE'");
        assertThat(sql).contains("t.outstanding_amount IS NULL");
        // Regression pin: a blanket "outstanding = amount" backfill would reopen invoices that
        // were already paid off via recordArPayment (no per-invoice AR allocation exists to
        // know otherwise) — same trap V20261028_94 called out for legacy AP rows.
        assertThat(sql).doesNotContain("SET outstanding_amount = ABS(amount)");
        assertThat(sql).doesNotContain("SET outstanding_amount = amount");
    }

    @Test
    @DisplayName("unattributable rows (no SO, or SO shared by 2+ invoices) fall back to NEEDS_RECONCILIATION")
    void unattributableRowsAreMarkedNeedsReconciliation() throws Exception {
        String sql = readMigration();

        assertThat(sql).contains("payment_status = 'NEEDS_RECONCILIATION'");
        assertThat(sql).contains("c.invoice_count = 1");
    }

    @Test
    @DisplayName("open-item CHECK constraint is widened to AR_INVOICE, not just AP_INVOICE")
    void checkConstraintCoversBothInvoiceTypes() throws Exception {
        String sql = readMigration();

        assertThat(sql).contains("ck_ar_ap_open_item");
        assertThat(sql).contains("transaction_type NOT IN ('AP_INVOICE', 'AR_INVOICE')");
        // The old AP-only constraint name must be dropped so it isn't left dangling.
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS ck_ar_ap_payable_open_item");
    }
}
