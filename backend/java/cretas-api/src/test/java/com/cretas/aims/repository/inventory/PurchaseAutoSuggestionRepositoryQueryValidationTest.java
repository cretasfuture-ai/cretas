package com.cretas.aims.repository.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and invocation gate for the auto-suggestion count query. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.generate-ddl=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("自动采购建议编号查询：真实 JPA 启动闸")
class PurchaseAutoSuggestionRepositoryQueryValidationTest {

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Test
    void repositoryBootsAndCountQueryCanBeInvoked() {
        assertThat(purchaseOrderRepository.countAutoSuggestionsOn("JPA-PO-F1", "20261105"))
                .isZero();
    }
}
