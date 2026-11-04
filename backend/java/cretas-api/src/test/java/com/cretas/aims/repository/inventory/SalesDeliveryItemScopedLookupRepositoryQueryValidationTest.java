package com.cretas.aims.repository.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import jakarta.persistence.EntityManager;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup/query gate for factory-scoped delivery-line lookup. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class SalesDeliveryItemScopedLookupRepositoryQueryValidationTest {

    @Autowired private SalesDeliveryItemRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void scopedDeliveryItemLookupBootsAndReturnsEmptyForUnknownRow() {
        assertThat(repository.findByIdAndDeliveryRecordFactoryId(999999L, "F006")).isEmpty();
    }

    @Test
    void lookupFindsOnlyOwnLiveDeliveryLine() {
        var owners = com.cretas.aims.testutil.FactoryCatalogJpaFixtures.seed(entityManager);
        var record = new SalesDeliveryRecord();
        record.setId("CATALOG-DLV"); record.setFactoryId("F006");
        record.setDeliveryNumber("CATALOG-DLV"); record.setCustomerId("CUSTOMER-F006");
        record.setDeliveryDate(LocalDate.now()); record.setShippedBy(owners.first());
        entityManager.persist(record);
        var item = new SalesDeliveryItem(); item.setDeliveryRecordId(record.getId());
        item.setProductTypeId("SKU-A"); item.setDeliveredQuantity(BigDecimal.ONE); item.setUnit("kg");
        entityManager.persist(item); entityManager.flush();
        assertThat(repository.findByIdAndDeliveryRecordFactoryId(item.getId(), "F006")).isPresent();
        assertThat(repository.findByIdAndDeliveryRecordFactoryId(item.getId(), "F007")).isEmpty();
        item.softDelete(); entityManager.flush(); entityManager.clear();
        assertThat(repository.findByIdAndDeliveryRecordFactoryId(item.getId(), "F006")).isEmpty();
    }
}
