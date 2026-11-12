package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup gate for the warehouse terminal-history query. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SalesDeliveryRecordRepositoryQueryValidationTest {

    @Autowired private SalesDeliveryRecordRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void tenantAndStatusQueryBootsInRealJpaContext() {
        assertThat(repository).isNotNull();
        assertThat(entityManager.getMetamodel()
                .entity(com.cretas.aims.entity.inventory.SalesDeliveryRecord.class))
                .isNotNull();

        assertThat(repository.findByFactoryIdAndStatusIn(
                "F006",
                List.of(SalesDeliveryStatus.SHIPPED, SalesDeliveryStatus.DELIVERED),
                PageRequest.of(0, 20))).isEmpty();
    }

    /**
     * Narrower-than-factory scope: the warehouse queue follows source-document ownership.
     * Positive: own order's delivery and own customer's standalone delivery are listed.
     * Negative: another creator's order, another factory, and a non-queue status are not.
     */
    @Test
    void scopedQueueListsOnlyDeliveriesWhoseSourceDocumentIsOwnedInsideTheScope() {
        // Fixture rows only: factories/users parents are irrelevant to the ownership predicate.
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            assertScopedQueue();
        } finally {
            // H2 SET commits the open transaction, so the fixture is removed explicitly.
            for (String table : List.of("sales_delivery_records", "sales_orders", "customers")) {
                entityManager.createNativeQuery("DELETE FROM " + table + " WHERE factory_id IN ('FQ-SCOPE','FQ-OTHER')")
                        .executeUpdate();
            }
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    private void assertScopedQueue() {
        insertCustomer("C-MINE", "FQ-SCOPE", 56L);
        insertCustomer("C-OTHER", "FQ-SCOPE", 77L);
        insertCustomer("C-F2", "FQ-OTHER", 56L);
        insertOrder("SO-MINE", "FQ-SCOPE", "C-OTHER", 56L);
        insertOrder("SO-OTHER", "FQ-SCOPE", "C-MINE", 77L);
        insertOrder("SO-F2", "FQ-OTHER", "C-F2", 56L);
        insertDelivery("D-MINE-ORDER", "FQ-SCOPE", "SO-MINE", "C-OTHER", "PENDING_WAREHOUSE_CONFIRM");
        insertDelivery("D-MINE-SAMPLE", "FQ-SCOPE", null, "C-MINE", "DRAFT");
        insertDelivery("D-OTHER-ORDER", "FQ-SCOPE", "SO-OTHER", "C-MINE", "PENDING_WAREHOUSE_CONFIRM");
        insertDelivery("D-OTHER-SAMPLE", "FQ-SCOPE", "", "C-OTHER", "DRAFT");
        insertDelivery("D-F2", "FQ-OTHER", "SO-F2", "C-F2", "PENDING_WAREHOUSE_CONFIRM");
        insertDelivery("D-MINE-SHIPPED", "FQ-SCOPE", "SO-MINE", "C-OTHER", "SHIPPED");
        entityManager.flush();
        entityManager.clear();

        var pending = List.of(SalesDeliveryStatus.DRAFT, SalesDeliveryStatus.PENDING_WAREHOUSE_CONFIRM,
                SalesDeliveryStatus.PICKED);
        var scoped = repository.findByFactoryIdAndStatusInAndOwnerIn("FQ-SCOPE", pending, List.of(56L),
                PageRequest.of(0, 20));
        assertThat(scoped.getContent()).extracting(com.cretas.aims.entity.inventory.SalesDeliveryRecord::getId)
                .containsExactlyInAnyOrder("D-MINE-ORDER", "D-MINE-SAMPLE");
        assertThat(scoped.getTotalElements()).isEqualTo(2);

        // Whole-factory query is unchanged and sees both creators' rows (but never the other factory).
        assertThat(repository.findByFactoryIdAndStatusIn("FQ-SCOPE", pending, PageRequest.of(0, 20)).getContent())
                .extracting(com.cretas.aims.entity.inventory.SalesDeliveryRecord::getId)
                .containsExactlyInAnyOrder("D-MINE-ORDER", "D-MINE-SAMPLE", "D-OTHER-ORDER", "D-OTHER-SAMPLE");

        assertThat(repository.findByFactoryIdAndStatusInAndOwnerIn("FQ-SCOPE", List.of(SalesDeliveryStatus.SHIPPED),
                List.of(56L), PageRequest.of(0, 20)).getContent())
                .extracting(com.cretas.aims.entity.inventory.SalesDeliveryRecord::getId)
                .containsExactly("D-MINE-SHIPPED");
    }

    private void insertCustomer(String id, String factory, long createdBy) {
        entityManager.createNativeQuery("INSERT INTO customers (id, factory_id, name, code, customer_code, created_by, "
                        + "is_active, version, created_at, updated_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?4, ?5, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .setParameter(1, id).setParameter(2, factory).setParameter(3, "客户" + id)
                .setParameter(4, id).setParameter(5, createdBy).executeUpdate();
    }

    private void insertOrder(String id, String factory, String customer, long createdBy) {
        entityManager.createNativeQuery("INSERT INTO sales_orders (id, factory_id, order_number, customer_id, "
                        + "order_date, status, vflag, customer_stock_fulfillment_mode, version, created_by, "
                        + "created_at, updated_at) VALUES (?1, ?2, ?3, ?4, CURRENT_DATE, 'CONFIRMED', 'UNCREATED', "
                        + "'ORDER_DRIVEN', 0, ?5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .setParameter(1, id).setParameter(2, factory).setParameter(3, id)
                .setParameter(4, customer).setParameter(5, createdBy).executeUpdate();
    }

    private void insertDelivery(String id, String factory, String order, String customer, String status) {
        entityManager.createNativeQuery("INSERT INTO sales_delivery_records (id, factory_id, delivery_number, "
                        + "sales_order_id, customer_id, delivery_date, status, shipped_by, record_role, "
                        + "created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, CURRENT_DATE, ?6, 1, 'LEGACY', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                .setParameter(1, id).setParameter(2, factory).setParameter(3, id)
                .setParameter(4, order).setParameter(5, customer).setParameter(6, status).executeUpdate();
    }
}
