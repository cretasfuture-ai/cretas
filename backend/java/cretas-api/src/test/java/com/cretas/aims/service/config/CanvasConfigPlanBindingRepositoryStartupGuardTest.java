package com.cretas.aims.service.config;

import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.config.FactoryDefaultValueRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryTemplateRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.entity.config.FactoryConfiguration;
import com.cretas.aims.entity.config.FactoryDefaultValue;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.entity.config.FactoryTemplate;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup/query smoke gate for every repository read used by the binding reader. */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:canvas_config_binding_query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.hikari.connection-init-sql=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR(1000000)",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {
        FactoryConfiguration.class, FactoryDefaultValue.class, FactoryModuleConfig.class,
        FactorySkillConfig.class, FactoryTemplate.class, FactoryToolConfig.class, FactoryTriggerChain.class
})
@EnableJpaRepositories(basePackageClasses = {
        FactoryConfigurationRepository.class, FactoryDefaultValueRepository.class,
        FactoryModuleConfigRepository.class, FactorySkillConfigRepository.class,
        FactoryTemplateRepository.class, FactoryToolConfigRepository.class,
        FactoryTriggerChainRepository.class
})
class CanvasConfigPlanBindingRepositoryStartupGuardTest {
    @Autowired TestEntityManager entityManager;
    @Autowired FactoryConfigurationRepository configurations;
    @Autowired FactoryModuleConfigRepository modules;
    @Autowired FactoryToolConfigRepository tools;
    @Autowired FactorySkillConfigRepository skills;
    @Autowired FactoryTriggerChainRepository triggers;
    @Autowired FactoryTemplateRepository templates;
    @Autowired FactoryDefaultValueRepository defaults;

    @Test
    void allBindingReaderQueriesStartAndReturnTenantScopedEmptyResults() {
        String factory = "BINDING-QUERY-NOT-THERE";
        assertThat(configurations.findDraft(factory)).isEmpty();
        assertThat(configurations.findLatestPublished(factory)).isEmpty();
        assertThat(configurations.findByFactoryIdOrderByConfigVersionDesc(factory)).isEmpty();
        assertThat(modules.findByFactoryIdAndConfigVersion(factory, 99)).isEmpty();
        assertThat(tools.findByFactoryIdAndToolName(factory, "canvas_toggle_module")).isEmpty();
        assertThat(skills.findByFactoryIdAndSkillName(factory, "missing")).isEmpty();
        assertThat(triggers.findByFactoryIdAndChainCode(factory, "missing")).isEmpty();
        assertThat(templates.findByTemplateCodeIgnoreCase("missing-template")).isEmpty();
        assertThat(defaults.findAllForFactory(factory)).isEmpty();
        entityManager.flush();
    }
}
