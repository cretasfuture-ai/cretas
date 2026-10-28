package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and query execution gate for the disposal batch selector. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class FinishedGoodsDisposalRepositoryQueryValidationTest {

    @Autowired private FinishedGoodsBatchRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void eligibleQueryParsesAndExecutesInJpaContext() {
        assertThat(repository.findDisposalEligibleByFactoryId("NO-SUCH-FACTORY",
                List.of("AVAILABLE", "DEFECTIVE", "EXPIRED", "FROZEN"))).isEmpty();
    }

    @Test
    void eligibleQueryIncludesCompanyFreeBalanceAndOnlyUnsellableCustomerGoodsInSameFactory() {
        Factory factory = new Factory();
        factory.setId("DISPOSAL-FACTORY");
        factory.setName("Disposal fixture factory");
        entityManager.persist(factory);
        User creator = new User();
        creator.setFactoryId(factory.getId());
        creator.setUsername("disposal-fixture-user");
        creator.setPasswordHash("not-used");
        entityManager.persist(creator);
        ProductType product = new ProductType();
        product.setId("DISPOSAL-PRODUCT");
        product.setFactoryId(factory.getId());
        product.setCode("DISPOSAL-PRODUCT");
        product.setName("Test product");
        product.setUnit("kg");
        product.setCreatedBy(creator.getId());
        entityManager.persist(product);

        persistBatch("PARTIAL", "AVAILABLE", "100", "60", "10", InventoryOwnership.COMPANY_OWNED);
        persistBatch("FULL", "AVAILABLE", "100", "100", "0", InventoryOwnership.COMPANY_OWNED);
        persistBatch("RESERVED", "AVAILABLE", "10", "0", "10", InventoryOwnership.COMPANY_OWNED);
        persistBatch("CUSTOMER", "AVAILABLE", "10", "0", "0", InventoryOwnership.CUSTOMER_OWNED);
        persistBatch("DEFECTIVE", "DEFECTIVE", "5", "0", "0", InventoryOwnership.COMPANY_OWNED);
        // 退回/不良的客户货可以退还客户或经同意报废; 可售的客户货 (上面 CUSTOMER) 仍只能走代加工发货
        persistBatch("CUSTOMER-RETURNED", "DEFECTIVE", "16", "0", "0", InventoryOwnership.CUSTOMER_OWNED);
        persistBatch("REVERSED", "REVERSED", "5", "0", "0", InventoryOwnership.COMPANY_OWNED);
        entityManager.flush();
        entityManager.clear();

        List<FinishedGoodsBatch> result = repository.findDisposalEligibleByFactoryId("DISPOSAL-FACTORY",
                List.of("AVAILABLE", "DEFECTIVE", "EXPIRED", "FROZEN"));
        assertThat(result).extracting(FinishedGoodsBatch::getId)
                .containsExactlyInAnyOrder("DISPOSAL-PARTIAL", "DISPOSAL-DEFECTIVE", "DISPOSAL-CUSTOMER-RETURNED");
        assertThat(result.stream().filter(b -> b.getId().equals("DISPOSAL-PARTIAL")).findFirst().orElseThrow()
                .getAvailableQuantity()).isEqualByComparingTo("30");
        assertThat(repository.findDisposalEligibleByFactoryId("OTHER-FACTORY", List.of("AVAILABLE"))).isEmpty();
    }

    private void persistBatch(String suffix, String status, String produced, String shipped, String reserved,
            InventoryOwnership ownership) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("DISPOSAL-" + suffix);
        batch.setFactoryId("DISPOSAL-FACTORY");
        batch.setBatchNumber("DISPOSAL-" + suffix);
        batch.setProductTypeId("DISPOSAL-PRODUCT");
        batch.setProductName("Test product");
        batch.setUnit("kg");
        batch.setWarehouseId("WH-WKS");
        batch.setStatus(status);
        batch.setProducedQuantity(new BigDecimal(produced));
        batch.setShippedQuantity(new BigDecimal(shipped));
        batch.setReservedQuantity(new BigDecimal(reserved));
        batch.setOwnership(ownership);
        entityManager.persist(batch);
    }
}
