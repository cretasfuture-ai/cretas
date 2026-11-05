package com.cretas.aims.service.skill;

import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for 2 new restaurant skills added in P2.7.
 *
 * Constructs {@link SkillRegistryImpl} directly with mocked dependencies
 * and invokes the private {@code initializeDefaultSkills()} via reflection.
 * Avoids {@code @SpringBootTest} to keep the test fast and independent of
 * Spring context, database, or classpath scanning.
 */
class RestaurantSkillsRegistrationTest {

    private SkillRegistryImpl registry;

    @BeforeEach
    void setUp() throws Exception {
        SmartBiSkillRepository mockRepo = mock(SmartBiSkillRepository.class);
        when(mockRepo.findAll()).thenReturn(Collections.emptyList());

        ObjectMapper mapper = new ObjectMapper();
        registry = new SkillRegistryImpl(mockRepo, mapper);

        // loadDefaults is @Value-injected and defaults to false for tests.
        // Set it true via reflection so initializeDefaultSkills() is reachable
        // via the public loadSkills() → initializeDefaultSkills() path.
        Field loadDefaultsField = SkillRegistryImpl.class.getDeclaredField("loadDefaults");
        loadDefaultsField.setAccessible(true);
        loadDefaultsField.setBoolean(registry, true);

        // Invoke initializeDefaultSkills() directly (private method) to isolate
        // the test from classpath and database loading steps.
        Method initMethod = SkillRegistryImpl.class.getDeclaredMethod("initializeDefaultSkills");
        initMethod.setAccessible(true);
        initMethod.invoke(registry);
    }

    @Test
    void retiredRestaurantDiagnosticsSkillIsRejected() {
        assertThat(registry.getSkill("restaurant-diagnostics")).isEmpty();
    }

    @Test
    void retiredRestaurantChainAnalysisSkillIsRejected() {
        assertThat(registry.getSkill("restaurant-chain-analysis")).isEmpty();
    }

    @Test
    void retiredRestaurantOperationsAndWastageSkillsAreRejected() {
        assertThat(registry.getSkill("restaurant-operations")).isEmpty();
        assertThat(registry.getSkill("restaurant-wastage")).isEmpty();
    }
}
