package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties={"spring.datasource.url=jdbc:h2:mem:receiving_scope_jpa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages="com.cretas.aims.entity")
@EnableJpaRepositories(basePackages="com.cretas.aims.repository")
class PurchaseReceivingScopeRepositoryQueryValidationTest {
    @Autowired PurchaseOrderRepository repository;
    @Autowired EntityManager entityManager;

    /**
     * Receiving list under a narrower-than-factory scope (warehouse worker, 2026-11-13): the status
     * query lists only orders created inside the scope. Negative rows: another creator, another
     * factory, another status, and a soft-deleted own order.
     */
    @Test void scopedStatusQueryListsOnlyOwnedOrdersOfThatStatusAndFactory() {
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            insertOrder("PO-MINE", "PQ-SCOPE", "FINANCE_APPROVED", 56L, false);
            insertOrder("PO-PEER", "PQ-SCOPE", "FINANCE_APPROVED", 57L, false);
            insertOrder("PO-OTHER", "PQ-SCOPE", "FINANCE_APPROVED", 77L, false);
            insertOrder("PO-F2", "PQ-OTHER", "FINANCE_APPROVED", 56L, false);
            insertOrder("PO-MINE-DONE", "PQ-SCOPE", "COMPLETED", 56L, false);
            insertOrder("PO-MINE-DELETED", "PQ-SCOPE", "FINANCE_APPROVED", 56L, true);
            entityManager.flush();
            entityManager.clear();

            var scoped = repository.findByFactoryIdAndStatusAndCreatedByInOrderByCreatedAtDesc(
                    "PQ-SCOPE", PurchaseOrderStatus.FINANCE_APPROVED, List.of(56L, 57L), PageRequest.of(0, 20));
            assertThat(scoped.getContent()).extracting(com.cretas.aims.entity.inventory.PurchaseOrder::getId)
                    .containsExactlyInAnyOrder("PO-MINE", "PO-PEER");
            assertThat(scoped.getTotalElements()).isEqualTo(2);

            // Whole-factory query (unchanged ALL path) still sees every creator, in this factory only.
            assertThat(repository.findByFactoryIdAndStatusOrderByCreatedAtDesc("PQ-SCOPE",
                    PurchaseOrderStatus.FINANCE_APPROVED, PageRequest.of(0, 20)).getContent())
                    .extracting(com.cretas.aims.entity.inventory.PurchaseOrder::getId)
                    .containsExactlyInAnyOrder("PO-MINE", "PO-PEER", "PO-OTHER");
        } finally {
            entityManager.createNativeQuery("DELETE FROM purchase_orders WHERE factory_id IN ('PQ-SCOPE','PQ-OTHER')")
                    .executeUpdate();
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    private void insertOrder(String id, String factory, String status, long createdBy, boolean deleted) {
        entityManager.createNativeQuery("INSERT INTO purchase_orders (id, vflag, factory_id, order_number, purchase_type, "
                        + "order_date, status, created_by, version, purchase_invoice_status, created_at, updated_at, deleted_at) "
                        + "VALUES (?1, 'UNCREATED', ?2, ?1, 'DIRECT', CURRENT_DATE, ?3, ?4, 0, 'NOT_RECEIVED', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, " + (deleted ? "CURRENT_TIMESTAMP" : "NULL") + ")")
                .setParameter(1, id).setParameter(2, factory).setParameter(3, status).setParameter(4, createdBy)
                .executeUpdate();
    }

    @Test void scopedStatusQueryParsesAndExecutesWithPageAndCount() {
        var page=repository.findByFactoryIdAndStatusAndCreatedByInOrderByCreatedAtDesc(
                "RECEIVING-SCOPE-EMPTY",PurchaseOrderStatus.FINANCE_APPROVED,List.of(11L,22L),PageRequest.of(0,1));
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(repository.findByIdAndFactoryIdAndCreatedByIn("missing","RECEIVING-SCOPE-EMPTY",List.of(11L))).isEmpty();
        assertThat(repository.findByFactoryIdAndOrderNumberAndCreatedByIn("RECEIVING-SCOPE-EMPTY","missing",List.of(11L))).isEmpty();
    }
}
