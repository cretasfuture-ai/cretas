package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应收发票唯一性: 从「每销售订单一条」改成「每子发运单一条」。
 *
 * <p><b>为什么必须改</b>: 要让应收反映<b>实发</b>, 就得按子发运单分笔挂账
 * (一次发货挂一次它自己的实发额, 累计 = 实发总额)。「一个 SO 多条 AR_INVOICE」
 * 正是旧约束 {@code uk_aat_ar_invoice_per_so} 禁止的。
 *
 * <p><b>这一步在采购侧已经踩过一次</b>: 2026-07-02 把应付幂等键改成「每张入库单」时
 * <b>只改了代码没改约束</b>, 那次修复从未真正生效 —— 症状只是从「抛异常」变成
 * 「insert 时炸」, 直到 2026-08-03 分批到货的第二批永远入不了库才被发现
 * ({@code V20261029_49} 补的约束, 见 {@link ApInvoiceUniquePerReceiptMigrationContractTest})。
 * 本测试存在的意义就是<b>钉住代码与约束同步</b>, 不重演那次。
 *
 * <p><b>存量影响</b> (prod 实查 2026-08-28): 全库 AR_INVOICE 12 条, 其中 source_id 非空 0 条、
 * 同一 SO 多条 0 条 ⇒ 新约束不撞任何历史数据; 12 条 legacy 行全部因 source_id IS NULL 不纳入。
 */
@DisplayName("迁移契约 — 应收发票按子发运单唯一")
class ArInvoiceUniquePerShipmentMigrationContractTest {

    private static final Path SQL = Path.of("src", "main", "resources", "db", "flyway",
            "V20261031_06__ar_invoice_unique_per_shipment.sql");

    private String sql() throws Exception {
        assertThat(SQL).as("迁移文件必须存在").exists();
        return Files.readString(SQL);
    }

    @Test
    @DisplayName("回归: 必须建立按子发运单的新唯一约束")
    void createsPerShipmentUniqueIndex() throws Exception {
        String sql = sql();
        assertThat(sql).as("新约束名").contains("uk_aat_ar_invoice_per_shipment");
        assertThat(sql.replaceAll("\\s+", " "))
                .as("唯一键必须是 (factory_id, source_type, source_id) —— 与代码幂等键 (SALES_DELIVERY, deliveryId) 一致")
                .contains("(factory_id, source_type, source_id)");
    }

    @Test
    @DisplayName("回归: 必须删除旧的「每 SO 一条」约束, 否则第二次发货照样撞唯一键")
    void dropsLegacyPerSalesOrderIndex() throws Exception {
        assertThat(sql())
                .as("旧约束不删则分批发货第二批撞唯一键 → 整事务回滚 → 货发不出去 (采购侧 8/03 的原样重演)")
                .contains("DROP INDEX")
                .contains("uk_aat_ar_invoice_per_so");
    }

    @Test
    @DisplayName("幂等不能丢: 新约束仍须限定 AR_INVOICE + 未删除 + source_id 非空")
    void keepsPartialPredicateSoIdempotencyHolds() throws Exception {
        String flat = sql().replaceAll("\\s+", " ");
        assertThat(flat).as("只约束应收发票, 不误伤其它交易类型 (同一订单可有多笔收款/调整)")
                .contains("transaction_type = 'AR_INVOICE'");
        assertThat(flat).as("软删除的行不参与唯一性, 否则冲销后无法重开")
                .contains("deleted_at IS NULL");
        assertThat(flat).as("source_id 为空的 legacy 行不纳入, 否则 12 条历史行会互相冲突")
                .contains("source_id IS NOT NULL");
    }

    @Test
    @DisplayName("顺序: 先建新约束再删旧的 —— 不留「旧的删了新的没建上」的中间态")
    void createsBeforeDropSoNoUnprotectedWindow() throws Exception {
        String sql = sql();
        int create = sql.indexOf("CREATE UNIQUE INDEX");
        int drop = sql.indexOf("DROP INDEX");
        assertThat(create).as("必须有 CREATE").isGreaterThanOrEqualTo(0);
        assertThat(drop).as("必须有 DROP").isGreaterThanOrEqualTo(0);
        assertThat(create)
                .as("若先 DROP 再 CREATE, 历史数据违反新约束时会留下完全无保护的表")
                .isLessThan(drop);
    }
}
