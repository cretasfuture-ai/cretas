package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate startup gate for the document-trace source-order lookup.
 *
 * <p>Mockito service tests stub the repository, so a derived-query typo (e.g. a property
 * that does not exist on {@code ReturnOrder}) would survive compilation and only blow up
 * when Spring starts in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Return order repository query startup validation")
class ReturnOrderRepositoryQueryValidationTest {

    @Autowired private ReturnOrderRepository returnOrderRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ReturnOrderItemRepository returnOrderItemRepository;

    @Test
    @DisplayName("document-trace source-order lookup parses in a real JPA context")
    void sourceOrderLookupBoots() {
        assertNotNull(returnOrderRepository);
        assertNotNull(entityManager.getMetamodel()
                .entity(com.cretas.aims.entity.inventory.ReturnOrder.class)
                .getAttribute("sourceOrderId"));

        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        "F006", ReturnType.SALES_RETURN, "so-test"));
        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        "F006", ReturnType.PURCHASE_RETURN, "po-test"));
        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByIdAndFactoryId("return-test", "F006"));
    }

    @Test
    @DisplayName("completed physical sales-return date query executes and remains factory scoped")
    void completedSalesReturnDateLookupExecutes() {
        persistOrder("RO-IN", "F006", ReturnOrderStatus.COMPLETED, ReturnType.SALES_RETURN,
                LocalDate.of(2026, 1, 10));
        persistOrder("RO-OUT", "F006", ReturnOrderStatus.COMPLETED, ReturnType.SALES_RETURN,
                LocalDate.of(2026, 2, 1));
        persistOrder("RO-OTHER", "F007", ReturnOrderStatus.COMPLETED, ReturnType.SALES_RETURN,
                LocalDate.of(2026, 1, 10));
        persistOrder("RO-DRAFT", "F006", ReturnOrderStatus.DRAFT, ReturnType.SALES_RETURN,
                LocalDate.of(2026, 1, 10));
        entityManager.flush();

        var matched = returnOrderRepository
                .findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                        "F006", ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThat(matched).extracting("id").containsExactly("RO-IN");

        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                        "F006", ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertThatNoException().isThrownBy(() -> returnOrderRepository
                .findByFactoryIdAndReturnTypeAndStatusAndReturnDateBetween(
                        "OTHER_FACTORY", ReturnType.SALES_RETURN, ReturnOrderStatus.COMPLETED,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
    }

    @Test void completedReturnItemCostSynchronizationQueryIsFactoryAndSourceScoped() {
        persistOrder("SYNC-IN","F006",ReturnOrderStatus.COMPLETED,ReturnType.SALES_RETURN,LocalDate.of(2026,1,1));
        persistOrder("SYNC-OTHER","F007",ReturnOrderStatus.COMPLETED,ReturnType.SALES_RETURN,LocalDate.of(2026,1,1));
        persistOrder("SYNC-DRAFT","F006",ReturnOrderStatus.DRAFT,ReturnType.SALES_RETURN,LocalDate.of(2026,1,1));
        for(String id:java.util.List.of("SYNC-IN","SYNC-OTHER","SYNC-DRAFT")) {
            var item=new com.cretas.aims.entity.inventory.ReturnOrderItem();
            item.setReturnOrderId(id);item.setSourceFinishedGoodsBatchId("SOURCE");item.setBatchNumber("B1");
            item.setQuantity(java.math.BigDecimal.ONE);entityManager.persist(item);
        }
        entityManager.flush();entityManager.clear();
        assertThat(returnOrderItemRepository.findCompletedPhysicalReturnsForSource("F006","SOURCE","B1"))
                .extracting("returnOrderId").containsExactly("SYNC-IN");
        assertThat(returnOrderItemRepository.findCompletedPhysicalReturnsForSource("F006","WRONG","OTHER")).isEmpty();
    }

    private void persistOrder(String id, String factoryId, ReturnOrderStatus status,
                              ReturnType type, LocalDate date) {
        var order = new com.cretas.aims.entity.inventory.ReturnOrder();
        order.setId(id);
        order.setFactoryId(factoryId);
        order.setReturnNumber("R-" + id);
        order.setReturnType(type);
        order.setStatus(status);
        order.setCounterpartyId("CUSTOMER-1");
        order.setReturnDate(date);
        order.setCreatedBy(1L);
        order.setWithGoods(true);
        entityManager.persist(order);
    }
}
