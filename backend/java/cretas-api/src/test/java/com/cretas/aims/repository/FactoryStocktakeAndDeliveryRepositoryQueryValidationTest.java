package com.cretas.aims.repository;

import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for the stocktake CAS and delivery line locks. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("Stocktake and delivery repository query startup validation")
class FactoryStocktakeAndDeliveryRepositoryQueryValidationTest {

    @Autowired private FactoryStocktakeRepository stocktakeRepository;
    @Autowired private SalesDeliveryItemRepository deliveryItemRepository;
    @Autowired private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("lock queries and new entity mappings parse in a real JPA context")
    void repositoriesAndMappingsBoot() {
        assertNotNull(stocktakeRepository);
        assertNotNull(deliveryItemRepository);
        assertNotNull(finishedGoodsBatchRepository);
        assertThat(entityManager.getMetamodel().entity(FactoryStocktake.class).getAttribute("version")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(FactoryStocktake.class).getAttribute("inventoryScope")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(FactoryStocktake.class).getAttribute("cancelledBy")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(FactoryStocktake.class).getAttribute("cancelledAt")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(FactoryStocktake.class).getAttribute("cancelReason")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(FactoryStocktakeItem.class).getAttribute("finishedGoodsBatchId")).isNotNull();
        assertThat(entityManager.getMetamodel().entity(SalesDeliveryItem.class).getAttribute("salesOrderItemId")).isNotNull();
    }

    @Test
    @DisplayName("active stocktake finder returns the blocking document and excludes ended or other-scope rows")
    void activeStocktakeFinderMatchesDuplicateGateContract() {
        LocalDateTime now = LocalDateTime.now();
        persistStocktake("ST-OLD", FactoryStocktake.Status.COUNTING,
                FactoryStocktake.InventoryScope.RAW_MATERIAL, now.minusMinutes(2));
        persistStocktake("ST-BLOCKING", FactoryStocktake.Status.APPROVED,
                FactoryStocktake.InventoryScope.RAW_MATERIAL, now.minusMinutes(1));
        persistStocktake("ST-ENDED", FactoryStocktake.Status.APPLIED,
                FactoryStocktake.InventoryScope.RAW_MATERIAL, now);
        persistStocktake("ST-RECOUNTED", FactoryStocktake.Status.RECOUNT_REQUIRED,
                FactoryStocktake.InventoryScope.RAW_MATERIAL, now.plusMinutes(2));
        persistStocktake("ST-CANCELLED", FactoryStocktake.Status.CANCELLED,
                FactoryStocktake.InventoryScope.RAW_MATERIAL, now.plusMinutes(3));
        persistStocktake("ST-OTHER-SCOPE", FactoryStocktake.Status.PENDING_APPROVAL,
                FactoryStocktake.InventoryScope.FINISHED_GOODS, now.plusMinutes(1));
        entityManager.flush();
        entityManager.clear();

        List<FactoryStocktake> active = stocktakeRepository.findActiveStocktakesForWarehouseAndMonth(
                "F006", "WH-RAW", "2026-08");

        assertThat(active).extracting(FactoryStocktake::getStocktakeNo)
                .containsExactly("ST-BLOCKING", "ST-OLD")
                .doesNotContain("ST-ENDED", "ST-RECOUNTED", "ST-CANCELLED", "ST-OTHER-SCOPE");
    }

    private void persistStocktake(String stocktakeNo, FactoryStocktake.Status status,
            FactoryStocktake.InventoryScope scope, LocalDateTime createdAt) {
        FactoryStocktake stocktake = new FactoryStocktake();
        stocktake.setFactoryId("F006");
        stocktake.setStocktakeNo(stocktakeNo);
        stocktake.setWarehouseId("WH-RAW");
        stocktake.setInventoryScope(scope);
        stocktake.setPeriodMonth("2026-08");
        stocktake.setStatus(status);
        stocktake.setInitiatedBy(1L);
        stocktake.setInitiatedAt(createdAt);
        stocktake.setCreatedAt(createdAt);
        stocktake.setUpdatedAt(createdAt);
        entityManager.persist(stocktake);
    }
}
