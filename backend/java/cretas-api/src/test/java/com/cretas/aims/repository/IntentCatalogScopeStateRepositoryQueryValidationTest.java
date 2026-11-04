package com.cretas.aims.repository;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class IntentCatalogScopeStateRepositoryQueryValidationTest {
    @Autowired AIIntentConfigRepository repository;
    @Autowired EntityManager em;
    @Test
    void inactiveAndDeletedRowsRemainAuthoritativeOnlyInTheirOwnFactory() {
        var inactive = AIIntentConfig.builder().factoryId("A").intentCode("TEST-DISABLED")
                .intentName("Disabled fixture").isActive(false).build();
        var deleted = AIIntentConfig.builder().factoryId("A").intentCode("TEST-DELETED")
                .intentName("Deleted fixture").isActive(true).build();
        deleted.setDeletedAt(LocalDateTime.now());
        em.persist(inactive); em.persist(deleted); em.flush(); em.clear();
        assertThat(repository.findBlockedIntentCodesInFactoryScope("A"))
                .containsExactlyInAnyOrder("TEST-DISABLED", "TEST-DELETED");
        assertThat(repository.findBlockedIntentCodesInFactoryScope("B")).isEmpty();
        assertThat(repository.findByIntentCodeAndFactoryIdOrPlatform("TEST-DISABLED", "A")).isEmpty();
    }
}
