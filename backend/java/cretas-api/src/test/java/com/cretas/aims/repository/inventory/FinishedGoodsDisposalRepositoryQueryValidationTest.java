package com.cretas.aims.repository.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and query execution gate for the disposal batch selector. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class FinishedGoodsDisposalRepositoryQueryValidationTest {

    @Autowired private FinishedGoodsBatchRepository repository;

    @Test
    void eligibleQueryParsesAndExecutesInJpaContext() {
        assertThat(repository.findDisposalEligibleByFactoryId("NO-SUCH-FACTORY",
                List.of("AVAILABLE", "DEFECTIVE", "EXPIRED", "FROZEN"))).isEmpty();
    }
}
