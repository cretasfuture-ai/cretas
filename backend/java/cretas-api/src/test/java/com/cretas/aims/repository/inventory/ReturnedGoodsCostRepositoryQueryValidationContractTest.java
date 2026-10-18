package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.ReturnOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate query gate for factory-scoped historical return lookup. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ReturnedGoodsCostRepositoryQueryValidationContractTest {

    @Autowired private ReturnOrderRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void factoryAndReturnNumberQueryParsesAndKeepsTenantScope() {
        String returnNumber = "RT-SAL-QUERY-SCOPE-20261016";
        repository.saveAllAndFlush(java.util.List.of(
                returnOrder("return-query-a", "FACTORY-A", returnNumber),
                returnOrder("return-query-b", "FACTORY-B", returnNumber)));
        entityManager.clear();

        assertThat(repository.findByFactoryIdAndReturnNumber("FACTORY-A", returnNumber))
                .get().extracting(ReturnOrder::getId).isEqualTo("return-query-a");
        assertThat(repository.findByFactoryIdAndReturnNumber("FACTORY-B", returnNumber))
                .get().extracting(ReturnOrder::getId).isEqualTo("return-query-b");
        assertThat(repository.findByFactoryIdAndReturnNumber("FACTORY-A", "RT-SAL-OTHER"))
                .isEmpty();
        assertThat(repository.findByFactoryIdAndReturnNumber("FACTORY-X", returnNumber))
                .isEmpty();
        assertThat(entityManager).isNotNull();
    }

    private ReturnOrder returnOrder(String id, String factoryId, String returnNumber) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setFactoryId(factoryId);
        order.setReturnNumber(returnNumber);
        order.setReturnType(ReturnType.SALES_RETURN);
        order.setStatus(ReturnOrderStatus.DRAFT);
        order.setCounterpartyId("customer-" + factoryId);
        order.setReturnDate(LocalDate.of(2026, 10, 16));
        order.setCreatedBy(1L);
        order.setWithGoods(true);
        return order;
    }
}
