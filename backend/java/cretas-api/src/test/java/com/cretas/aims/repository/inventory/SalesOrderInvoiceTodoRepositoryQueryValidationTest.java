package com.cretas.aims.repository.inventory;

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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.inventory")
@DisplayName("Sales order invoice todo repository real JPA startup gate")
class SalesOrderInvoiceTodoRepositoryQueryValidationTest {

    @Autowired SalesOrderRepository salesOrderRepository;
    @Autowired EntityManager entityManager;

    @Test
    void todoQueryAndClaimUpdatesBootWithoutScanningHistoricalNulls() {
        assertThat(salesOrderRepository.findInvoiceTodoCandidates(
                LocalDateTime.now(), 5, org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(salesOrderRepository.claimInvoiceTodo(
                "missing", "F006", LocalDateTime.now(), LocalDateTime.now().plusMinutes(10), 5))
                .isZero();
        assertThat(salesOrderRepository.markInvoiceTodoDone("missing", "F006")).isZero();
        assertThat(salesOrderRepository.markInvoiceTodoFailure(
                "missing", "F006", LocalDateTime.now().plusMinutes(1), "test", 5)).isZero();
        assertThat(entityManager.getMetamodel().entity(
                com.cretas.aims.entity.inventory.SalesOrder.class)
                .getAttribute("invoiceTodoStatus")).isNotNull();
    }
}
