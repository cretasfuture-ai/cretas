package com.cretas.aims.blueprint.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.cretas.aims.entity.conversation.FactoryConfirmedFact;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.FactoryConfirmedFactRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL proof for the carry-over of earlier interview facts when a concurrent restart already
 * inserted the same item. The table is built from the exact migrations (partial unique indexes included),
 * so the duplicate really violates {@code uq_fcf_one_pending_confirm_per_field}. If the per-item
 * {@code REQUIRES_NEW} were dropped, the failed insert would abort the surrounding transaction and the
 * read right after it would die with "current transaction is aborted".
 * Opt-in via CRETAS_WORKFLOW_PG_VERIFY=true (the Java PR gate runs it that way).
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@EnableJpaRepositories(basePackageClasses = FactoryConfirmedFactRepository.class)
class FactoryConfirmedFactCarryOverPostgresContractTest {

    private static final String FACTORY = "FACT-PG-F001";
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/flyway");

    @Autowired
    private FactoryConfirmedFactRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }

    @BeforeEach
    void createTableFromTheRealMigrations() throws IOException {
        jdbcTemplate.execute("DROP TABLE IF EXISTS factory_confirmed_fact CASCADE");
        for (String file : List.of("V20261103_115__factory_confirmed_fact_history.sql",
                "V20261103_128__factory_confirmed_fact_pending_confirm.sql")) {
            jdbcTemplate.execute(Files.readString(MIGRATIONS.resolve(file), StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void dropTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS factory_confirmed_fact CASCADE");
    }

    private static FactoryConfirmedFact pending(String field, String value) {
        FactoryConfirmedFact fact = new FactoryConfirmedFact();
        fact.setFactoryId(FACTORY);
        fact.setFieldKey(field);
        fact.setFieldLabel(field);
        fact.setValueText(value);
        fact.setStatus(FactoryConfirmedFact.PENDING_CONFIRM);
        fact.setVersionNo(1);
        fact.setSourceKind("USER_ANSWER");
        fact.setSourceRef("conversation:earlier");
        return fact;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDuplicateInsertOnlyLosesThatItemAndTheOuterTransactionStaysUsable() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        // The earlier (concurrent) restart already committed "raced".
        outer.executeWithoutResult(status -> repository.saveAndFlush(pending("raced", "1")));

        // This restart's existence check ran before that commit, so it still sees nothing for "raced".
        FactoryConfirmedFactRepository stale = mock(FactoryConfirmedFactRepository.class, delegatesTo(repository));
        doReturn(Optional.empty()).when(stale)
                .findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(FACTORY, "raced");
        FactoryConfirmedFactService service = new FactoryConfirmedFactService(stale, transactionManager);

        int[] result = outer.execute(status -> {
            int adopted = service.adoptPriorFacts(FACTORY, List.of(
                    new FactoryConfirmedFactService.PriorFact("raced", "x", "2", "s-new", false),
                    new FactoryConfirmedFactService.PriorFact("other", "y", "3", "s-new", false)));
            // Same outer transaction: dies with "current transaction is aborted" if the failed insert joined it.
            int visible = repository.findByFactoryIdAndStatus(FACTORY, FactoryConfirmedFact.PENDING_CONFIRM).size();
            return new int[] {adopted, visible};
        });

        assertThat(result[0]).as("撞唯一索引的那一项跳过, 其余照常带入").isEqualTo(1);
        assertThat(result[1]).as("外层事务没被毒化: 既有的 raced + 新带入的 other").isEqualTo(2);
        assertThat(repository.findByFactoryIdAndFieldKeyAndStatus(FACTORY, "raced",
                FactoryConfirmedFact.PENDING_CONFIRM)).get().extracting(FactoryConfirmedFact::getValueText)
                .as("先写入的那条不被覆盖").isEqualTo("1");
        assertThat(repository.findByFactoryIdAndFieldKeyAndStatus(FACTORY, "other",
                FactoryConfirmedFact.PENDING_CONFIRM)).isPresent();
    }
}
