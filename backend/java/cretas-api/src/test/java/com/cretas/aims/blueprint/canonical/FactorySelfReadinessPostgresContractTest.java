package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.activation.FactoryFormalRuntimeReadback;
import com.cretas.aims.blueprint.interview.FactoryBuildApplyService;
import com.cretas.aims.blueprint.interview.FactoryBuildPlanService;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.indicator.FactoryIndicatorApplicabilityService;
import com.cretas.aims.service.workflow.ProductWorkflowResolutionService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The factory's own readiness entry on real PostgreSQL: the real controller, the real authority over
 * the real user table (soft-deleted rows hidden by the entity), the real current-blueprint query and
 * the real readiness read. The test does not wrap the call in its own transaction, so the controller's
 * read-only transaction is the one PostgreSQL sees; the write-side build services are mocks that must
 * never be touched, and the rows the read could write are counted before and after.
 */
@DataJpaTest(showSql = false, properties = "cretas.platform.factory-assembly.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({FactorySelfReadinessController.class, CanonicalBlueprintActorAuthority.class,
        CanonicalFactoryBlueprintService.class, FactoryReadinessService.class, FactoryFormalRuntimeReadback.class,
        FactoryBusinessReadinessReader.class, WarehouseResolver.class, JacksonAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactorySelfReadinessPostgresContractTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
        registry.add("spring.datasource.url", () -> bound);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @MockBean FactoryBuildPlanService planService;
    @MockBean FactoryBuildApplyService applyService;
    @MockBean FactoryBlueprintCapabilityCatalog capabilityCatalog;
    @MockBean FactoryIndicatorApplicabilityService indicators;
    @MockBean ProductWorkflowResolutionService resolution;

    @Autowired FactorySelfReadinessController controller;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String factoryId = "FSR-" + suffix;
    private final String otherFactoryId = "FSO-" + suffix;
    private Long superAdminId;
    private Long otherFactoryAdminId;
    private Long deletedAdminId;
    private Long operatorId;

    @BeforeEach
    void seed() {
        transaction.executeWithoutResult(status -> {
            factory(factoryId);
            factory(otherFactoryId);
            superAdminId = user(factoryId, "factory_super_admin", "sa");
            otherFactoryAdminId = user(otherFactoryId, "factory_super_admin", "other");
            deletedAdminId = user(factoryId, "factory_super_admin", "deleted");
            operatorId = user(factoryId, "operator", "op");
        });
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", deletedAdminId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM factory_type_blueprints WHERE factory_id IN (?, ?)", factoryId, otherFactoryId);
        jdbc.update("DELETE FROM users WHERE factory_id IN (?, ?)", factoryId, otherFactoryId);
        jdbc.update("DELETE FROM factories WHERE id IN (?, ?)", factoryId, otherFactoryId);
    }

    @Test
    void withoutACanonicalBlueprintTheRealReadSaysSoAndWritesNothing() {
        List<Long> before = rowCounts();

        var answer = controller.current(factoryId, factoryUser(superAdminId, factoryId)).getData();

        assertThat(answer.state()).isEqualTo(FactorySelfReadinessController.NO_CANONICAL_BLUEPRINT);
        assertThat(answer.blueprint()).isNull();
        assertThat(answer.readiness()).isNull();
        assertThat(rowCounts()).isEqualTo(before);
        verifyNoInteractions(planService, applyService);
    }

    @Test
    void aCurrentCanonicalBlueprintIsReadThroughTheRealReadinessReportWithoutWrites() {
        String blueprintId = UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> blueprint(blueprintId, factoryId));
        Long rowVersion = jdbc.queryForObject("SELECT row_version FROM factory_type_blueprints WHERE id = ?", Long.class, blueprintId);
        List<Long> before = rowCounts();

        var answer = controller.current(factoryId, factoryUser(superAdminId, factoryId)).getData();

        assertThat(answer.state()).isEqualTo(FactorySelfReadinessController.READY_TO_READ);
        assertThat(answer.blueprint()).isEqualTo(
                new FactorySelfReadinessController.BlueprintRef(blueprintId, 1, rowVersion, "DRAFT"));
        assertThat(answer.readiness().factoryId()).isEqualTo(factoryId);
        assertThat(answer.readiness().blueprintId()).isEqualTo(blueprintId);
        assertThat(answer.readiness().businessAcceptanceState()).isEqualTo("UNVERIFIED");
        assertThat(answer.readiness().businessWriteCount()).isZero();
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT row_version FROM factory_type_blueprints WHERE id = ?", Long.class, blueprintId))
                .isEqualTo(rowVersion);
        verifyNoInteractions(planService, applyService);
    }

    @Test
    void anotherFactorysDeletedOrWrongRoleAccountIsRefusedByTheRealUserTable() {
        transaction.executeWithoutResult(status -> blueprint(UUID.randomUUID().toString(), factoryId));

        assertDenied(otherFactoryAdminId, "CANONICAL_FACTORY_MEMBER_REQUIRED");
        assertDenied(deletedAdminId, "CANONICAL_FACTORY_MEMBER_REQUIRED");
        assertDenied(operatorId, "CANONICAL_FACTORY_ROLE_REQUIRED");
    }

    private void assertDenied(Long userId, String code) {
        BusinessException denied = catchThrowableOfType(
                () -> controller.current(factoryId, factoryUser(userId, factoryId)), BusinessException.class);
        assertThat(denied.getCode()).isEqualTo(403);
        assertThat(denied.getErrorCode()).isEqualTo(code);
    }

    private List<Long> rowCounts() {
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM factory_type_blueprints", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM blueprint_applications", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM factory_blueprint_bindings", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM users", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM factories", Long.class));
    }

    private static MockHttpServletRequest factoryUser(Long userId, String tokenFactoryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, tokenFactoryId, "factory_super_admin"));
        return request;
    }

    private void factory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("self readiness " + id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private Long user(String factory, String role, String name) {
        User user = new User();
        user.setFactoryId(factory);
        user.setUsername("fsr-" + name + "-" + suffix);
        user.setPasswordHash("test-only");
        user.setRoleCode(role);
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private void blueprint(String id, String factory) {
        FactoryTypeBlueprint blueprint = new FactoryTypeBlueprint();
        blueprint.setId(id);
        blueprint.setFactoryId(factory);
        blueprint.setFactoryName("self readiness " + factory);
        blueprint.setScopeType("FACTORY");
        blueprint.setSchemaVersion("canonical-factory-blueprint/v1");
        blueprint.setLifecycleState("DRAFT");
        blueprint.setIsCurrent(true);
        blueprint.setName("self readiness blueprint");
        blueprint.setCapabilitySnapshot("[]");
        // Every canonical blueprint carries the reviewed onboarding request it came from.
        blueprint.setSourceSnapshot("{\"payload\":{\"industryCode\":\"FOOD\",\"industryName\":\"食品加工\",\"schemas\":[],\"overwrite\":false}}");
        entityManager.persist(blueprint);
    }
}
