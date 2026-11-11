package com.cretas.aims.repository;

import com.cretas.aims.repository.finance.ArApTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 销售单财务批准后取消前的两道闸 (2026-11-11) 走真实 Hibernate 启动与真实查询:
 * 有收款 / 应收就只能走退款。阴性对照: 驳回的收款、软删的流水、别的订单、别的工厂都不算。
 */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("财务批准后取消: 收款 / 应收计数查询")
class ApprovedSalesOrderCancelGuardsRepositoryQueryValidationTest {

    private static final String F = "IT-SO-CANCEL";

    @Autowired private PaymentRecordRepository payments;
    @Autowired private ArApTransactionRepository arAp;
    @Autowired private MaterialBatchRepository materialBatches;
    @Autowired private JdbcTemplate jdbc;

    private void payment(String factoryId, String soId, String status, boolean deleted) {
        jdbc.update("insert into payment_records (id, factory_id, payment_number, sales_order_id, amount, status, "
                        + "created_at, updated_at, deleted_at) values (?, ?, ?, ?, 10.00, ?, now(), now(), "
                        + (deleted ? "now()" : "null") + ")",
                UUID.randomUUID().toString(), factoryId, "PAY-" + UUID.randomUUID().toString().substring(0, 8),
                soId, status);
    }

    @Test
    void paymentCountIgnoresRejectedDeletedAndOtherOrders() {
        payment(F, "SO-P", "REJECTED", false);
        payment(F, "SO-P", "VERIFIED", true);
        payment(F, "SO-OTHER", "VERIFIED", false);
        payment("IT-OTHER-F", "SO-P", "VERIFIED", false);
        assertThat(payments.countLiveBySalesOrder(F, "SO-P")).as("阴性对照全不算").isZero();

        payment(F, "SO-P", "PENDING", false);
        assertThat(payments.countLiveBySalesOrder(F, "SO-P")).as("待确认的收款也算 (钱可能已到)").isEqualTo(1);
    }

    @Test
    void arApCountQueryRunsAndIsZeroForAnOrderWithoutTransactions() {
        // 查询能在真实 Hibernate 上编译并执行; 有流水时计数见 ApprovedSalesOrderCancellationContractTest 的闸
        assertThat(arAp.countLiveBySalesOrder(F, "SO-NONE")).isZero();
    }

    @Test
    void customerSuppliedBatchCountQueryRunsOnRealHibernate() {
        assertThat(materialBatches.countCustomerOwnedWithStockForSalesOrder(F, "SO-NONE")).isZero();
    }
}
