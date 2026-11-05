package com.cretas.aims.service.skill;

import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retired restaurant skills remain absent and cannot be registered at runtime.
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
        assertRetiredSkillCannotRegister("restaurant-diagnostics");
    }

    @Test
    void retiredRestaurantChainAnalysisSkillIsRejected() {
        assertRetiredSkillCannotRegister("restaurant-chain-analysis");
    }

    @Test
    void retiredRestaurantOperationsAndWastageSkillsAreRejected() {
        assertRetiredSkillCannotRegister("restaurant-operations");
        assertRetiredSkillCannotRegister("restaurant-wastage");
    }

    private void assertRetiredSkillCannotRegister(String name) {
        assertThat(registry.getSkill(name)).isEmpty();
        SkillDefinition candidate = SkillDefinition.builder().name(name).enabled(true)
                .tools(List.of("batch_full_trace")).triggers(List.of("退役能力探针")).build();
        registry.register(candidate);
        assertThat(registry.getSkill(name)).as("retired names cannot re-enter through register")
                .isEmpty();

        SkillDefinition allowed = SkillDefinition.builder().name("factory-retirement-positive-control")
                .enabled(true).tools(List.of("batch_full_trace")).triggers(List.of("工厂追溯探针")).build();
        registry.register(allowed);
        assertThat(registry.getSkill(allowed.getName())).containsSame(allowed);
    }
}
