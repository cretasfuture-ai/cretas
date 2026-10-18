package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.enums.PaymentRequestStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Hibernate startup gate for the return-refund source queries. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PaymentRequestRepositoryQueryValidationContractTest {
    @Autowired private PaymentRequestRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void returnRefundQueriesBootInRealJpaContext() {
        assertNotNull(repository);
        assertNotNull(entityManager.getMetamodel().entity(
                com.cretas.aims.entity.inventory.PaymentRequest.class).getAttribute("returnOrderId"));
        List<PaymentRequestStatus> statuses = List.of(PaymentRequestStatus.PENDING, PaymentRequestStatus.PAID);
        assertThatNoException().isThrownBy(() -> repository.findSalesRefundsBySalesOrderId("F1", "SO-1", statuses));
        assertThatNoException().isThrownBy(() -> repository.findGenericSalesBySalesOrderId("F1", "SO-1", statuses));
    }
}
