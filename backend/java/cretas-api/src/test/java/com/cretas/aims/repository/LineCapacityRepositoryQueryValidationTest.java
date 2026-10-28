package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.entity.ProductionLineCapacityConfig.Source;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4 产能 / 设备配置的真实 Hibernate 映射与查询: 插单判定读到的就是配置写下的那几行
 * (本厂、本产品 + 通用、已生效、未软删)。H2 默认; 设 CRETAS_WORKFLOW_PG_URL 在 PostgreSQL 上跑同一组断言。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {ProductionLineCapacityConfig.class, ProductEquipmentRequirement.class})
@EnableJpaRepositories(basePackageClasses = ProductionLineCapacityConfigRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ProductionLineCapacityConfigRepository.class, ProductEquipmentRequirementRepository.class}))
class LineCapacityRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 8, 0);

    @Autowired TestEntityManager entityManager;
    @Autowired ProductionLineCapacityConfigRepository capacity;
    @Autowired ProductEquipmentRequirementRepository requirements;

    private ProductionLineCapacityConfig config(String factoryId, String lineId, String productTypeId,
                                                CapacityUnit unit, LocalDateTime effectiveFrom) {
        ProductionLineCapacityConfig c = new ProductionLineCapacityConfig();
        c.setFactoryId(factoryId);
        c.setProductionLineId(lineId);
        c.setProductTypeId(productTypeId);
        c.setCapacityPerHour(new BigDecimal("12.5"));
        c.setCapacityUnit(unit);
        c.setSource(Source.FACTORY_CONFIG);
        c.setActor(new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L));
        c.setEffectiveFrom(effectiveFrom);
        return capacity.saveAndFlush(c);
    }

    @Test
    void effectiveRowsAreThisProductAndGenericInThisFactoryOnly() {
        config("F-CAP", "L1", "PT-1", CapacityUnit.PIECE, NOW.minusDays(1));
        config("F-CAP", "L1", null, CapacityUnit.KG, NOW.minusDays(1));
        config("F-CAP", "L2", "PT-OTHER", CapacityUnit.KG, NOW.minusDays(1));     // 别的产品
        config("F-OTHER", "L9", "PT-1", CapacityUnit.KG, NOW.minusDays(1));       // 别的厂
        config("F-CAP", "L3", "PT-1", CapacityUnit.BATCH, NOW.plusDays(1));      // 还没生效
        ProductionLineCapacityConfig retired = config("F-CAP", "L4", "PT-1", CapacityUnit.KG, NOW.minusDays(1));
        retired.setDeletedAt(NOW.minusHours(1));                                  // 已被新配置替换
        capacity.saveAndFlush(retired);
        entityManager.clear();

        var rows = capacity.findEffectiveForProduct("F-CAP", "PT-1", NOW);

        assertThat(rows).extracting(ProductionLineCapacityConfig::getProductionLineId,
                        ProductionLineCapacityConfig::getProductTypeId, ProductionLineCapacityConfig::getCapacityUnit)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("L1", "PT-1", CapacityUnit.PIECE),
                        org.assertj.core.groups.Tuple.tuple("L1", null, CapacityUnit.KG));
        assertThat(rows).allSatisfy(r -> assertThat(r.getCapacityPerHour()).isEqualByComparingTo("12.5"));
        // 枚举按名字落库, 与迁移里的 CHECK 取值一致
        assertThat(entityManager.getEntityManager()
                .createNativeQuery("select capacity_unit from production_line_capacity_configs"
                        + " where production_line_id = 'L1' and product_type_id = 'PT-1'").getSingleResult())
                .isEqualTo("PIECE");
    }

    @Test
    void aPlatformAdminWriteKeepsItsActorAndLeavesConfiguredByEmpty() {
        ProductionLineCapacityConfig c = new ProductionLineCapacityConfig();
        c.setFactoryId("F-CAP");
        c.setProductionLineId("L-PLAT");
        c.setCapacityPerHour(new BigDecimal("5"));
        c.setCapacityUnit(CapacityUnit.BATCH);
        c.setSource(Source.CONVERSATIONAL_BUILD);
        c.setActor(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 3L));
        c.setEffectiveFrom(NOW.minusDays(1));
        capacity.saveAndFlush(c);
        entityManager.clear();

        Object[] row = (Object[]) entityManager.getEntityManager().createNativeQuery(
                "select actor_type, actor_id, configured_by from production_line_capacity_configs"
                        + " where production_line_id = 'L-PLAT'").getSingleResult();
        System.out.println("PLATFORM_ACTOR_ROW actor_type=" + row[0] + " actor_id=" + row[1] + " configured_by=" + row[2]);
        assertThat(row[0]).isEqualTo("PLATFORM_ADMIN");
        assertThat(((Number) row[1]).longValue()).isEqualTo(3L);
        assertThat(row[2]).isNull();   // 平台 id 不进 users 命名空间的列
    }

    @Test
    void equipmentRequirementsAreReadPerFactoryAndProduct() {
        for (Object[] row : new Object[][]{{"F-CAP", "PT-1", 1L}, {"F-CAP", "PT-1", 2L}, {"F-CAP", "PT-2", 3L}, {"F-OTHER", "PT-1", 4L}}) {
            ProductEquipmentRequirement r = new ProductEquipmentRequirement();
            r.setFactoryId((String) row[0]);
            r.setProductTypeId((String) row[1]);
            r.setEquipmentId((Long) row[2]);
            r.setSource(Source.CONVERSATIONAL_BUILD);
            r.setActor(new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L));
            requirements.saveAndFlush(r);
        }
        entityManager.clear();

        assertThat(requirements.findByFactoryIdAndProductTypeId("F-CAP", "PT-1"))
                .extracting(ProductEquipmentRequirement::getEquipmentId).containsExactlyInAnyOrder(1L, 2L);
    }
}
