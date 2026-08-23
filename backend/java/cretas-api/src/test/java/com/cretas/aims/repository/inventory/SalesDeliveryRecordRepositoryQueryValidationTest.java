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
}
