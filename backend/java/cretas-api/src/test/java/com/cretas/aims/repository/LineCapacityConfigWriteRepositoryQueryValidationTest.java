package com.cretas.aims.repository;

import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.ProductEquipmentRequirement;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionLineCapacityConfig;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.entity.ProductionLineCapacityConfig.Source;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.scheduling.LineCapacityConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * S4 切片 2 写入路径走真实 Hibernate: 替换 = 软删旧行 + 插新行, 旧行留作审计, 在用行只剩一条。
 * PostgreSQL 上额外装上迁移里的部分唯一索引, 证明替换顺序过得了它 (先插后删会撞索引),
 * 并且不经替换直接再插一条确实被索引挡住 (索引在, 不是恒绿)。
 * H2 默认; 设 CRETAS_WORKFLOW_PG_URL 在 PostgreSQL 上跑。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {ProductionLineCapacityConfig.class, ProductEquipmentRequirement.class})
@EnableJpaRepositories(basePackageClasses = ProductionLineCapacityConfigRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {ProductionLineCapacityConfigRepository.class, ProductEquipmentRequirementRepository.class}))
@Import(LineCapacityConfigService.class)
class LineCapacityConfigWriteRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (onPostgres()) {
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
    }

    private static boolean onPostgres() {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        return url != null && !url.isBlank();
    }

    private static final String F = "F-S4W";
    private static final ActorRef USER7 = new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L);
    private static final ActorRef USER8 = new ActorRef(AuthenticatedActorType.FACTORY_USER, 8L);
    /** 平台管理员走对话建厂: 同样的数字 id, 另一个命名空间 —— 不得进 configured_by。 */
    private static final ActorRef PLATFORM7 = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7L);

    @Autowired TestEntityManager entityManager;
    @Autowired LineCapacityConfigService service;
    @Autowired ProductionLineCapacityConfigRepository capacity;
    @Autowired ProductEquipmentRequirementRepository requirements;
    @MockBean ProductionLineRepository lines;
    @MockBean ProductTypeRepository products;
    @MockBean EquipmentRepository equipment;

    @BeforeEach
    void setUp() throws Exception {
        if (onPostgres()) {
            // the migration's partial unique indexes (ddl-auto=create does not know them)
            String sql = new ClassPathResource("db/flyway/V20261103_92__line_capacity_and_equipment_requirements.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            // comments first: they contain ';' too
            List<String> uniques = Arrays.stream(sql.replaceAll("(?m)--.*$", "").split(";"))
                    .map(String::trim)
                    .filter(s -> s.startsWith("CREATE UNIQUE INDEX")).toList();
            assertThat(uniques).hasSize(2);
            var em = entityManager.getEntityManager();
            for (String u : uniques) {
                em.createNativeQuery(u.replace("CREATE UNIQUE INDEX", "CREATE UNIQUE INDEX IF NOT EXISTS")).executeUpdate();
            }
        }
        ProductionLine line = new ProductionLine();
        line.setId("L1");
        line.setFactoryId(F);
        line.setName("包装线");
        // 其余 (他厂产线 / 产品) 走 Mockito 默认: Optional.empty()
        when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("L1", F)).thenReturn(Optional.of(line));
        ProductType pt = new ProductType();
        pt.setId("PT-1");
        pt.setFactoryId(F);
        when(products.findByIdAndFactoryId("PT-1", F)).thenReturn(Optional.of(pt));
        when(equipment.findByIdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().filter(id -> id < 100).map(id -> {
                FactoryEquipment e = new FactoryEquipment();
                e.setId(id);
                e.setFactoryId(F);
                return e;
            }).toList();
        });
    }

    private long activeRows() {
        return ((Number) entityManager.getEntityManager().createNativeQuery(
                "select count(*) from production_line_capacity_configs where factory_id = 'F-S4W' and deleted_at is null")
                .getSingleResult()).longValue();
    }

    private long allRows() {
        return ((Number) entityManager.getEntityManager().createNativeQuery(
                "select count(*) from production_line_capacity_configs where factory_id = 'F-S4W'")
                .getSingleResult()).longValue();
    }

    @Test
    void replacingACapacityRetiresTheOldRowAndKeepsItForAudit() {
        ProductionLineCapacityConfig first = service.setCapacity(F, "L1", "PT-1", new BigDecimal("100"), "kg",
                Source.FACTORY_CONFIG, USER7);
        ProductionLineCapacityConfig second = service.setCapacity(F, "L1", "PT-1", new BigDecimal("80"), "PIECE",
                Source.FACTORY_CONFIG, USER8);
        service.setCapacity(F, "L1", null, new BigDecimal("500"), "KG", Source.CONVERSATIONAL_BUILD, PLATFORM7);
        entityManager.clear();

        System.out.println("CAPACITY_WRITE active=" + activeRows() + " all=" + allRows() + " pg=" + onPostgres());
        assertThat(activeRows()).isEqualTo(2);   // PT-1 专用行 + 通用行
        assertThat(allRows()).isEqualTo(3);      // 被替换的那条留作审计
        ProductionLineCapacityConfig current = capacity.findByFactoryIdAndProductionLineIdAndProductTypeId(F, "L1", "PT-1")
                .orElseThrow();
        assertThat(current.getId()).isEqualTo(second.getId()).isNotEqualTo(first.getId());
        assertThat(current.getCapacityUnit()).isEqualTo(CapacityUnit.PIECE);
        assertThat(current.getConfiguredBy()).isEqualTo(8L);
        ProductionLineCapacityConfig generic = capacity.findByFactoryIdAndProductionLineIdAndProductTypeId(F, "L1", null)
                .orElseThrow();
        assertThat(generic.getSource()).isEqualTo(Source.CONVERSATIONAL_BUILD);
        assertThat(generic.getActorType()).isEqualTo(AuthenticatedActorType.PLATFORM_ADMIN);
        assertThat(generic.getActorId()).isEqualTo(7L);
        assertThat(generic.getConfiguredBy()).isNull();
        assertThat(current.getActorType()).isEqualTo(AuthenticatedActorType.FACTORY_USER);
    }

    @Test
    void theActiveIndexIsRealOnPostgres() {
        org.junit.jupiter.api.Assumptions.assumeTrue(onPostgres(), "partial unique index exists only on PostgreSQL");
        service.setCapacity(F, "L1", "PT-1", new BigDecimal("100"), "KG", Source.FACTORY_CONFIG, USER7);
        ProductionLineCapacityConfig dup = new ProductionLineCapacityConfig();
        dup.setFactoryId(F);
        dup.setProductionLineId("L1");
        dup.setProductTypeId("PT-1");
        dup.setCapacityPerHour(BigDecimal.ONE);
        dup.setCapacityUnit(CapacityUnit.KG);
        dup.setSource(Source.FACTORY_CONFIG);
        dup.setActor(USER7);
        dup.setEffectiveFrom(java.time.LocalDateTime.now());
        assertThatThrownBy(() -> capacity.saveAndFlush(dup)).isInstanceOf(Exception.class);
    }

    @Test
    void foreignOrBadInputWritesNothing() {
        assertThatThrownBy(() -> service.setCapacity(F, "L-OTHER", null, BigDecimal.TEN, "KG", Source.FACTORY_CONFIG, USER7))
                .isInstanceOf(BusinessException.class).hasMessageContaining("产线不存在");
        assertThatThrownBy(() -> service.setCapacity(F, "L1", "PT-OTHER", BigDecimal.TEN, "KG", Source.FACTORY_CONFIG, USER7))
                .isInstanceOf(BusinessException.class).hasMessageContaining("产品不存在");
        assertThatThrownBy(() -> service.setCapacity(F, "L1", null, BigDecimal.TEN, "TON", Source.FACTORY_CONFIG, USER7))
                .isInstanceOf(BusinessException.class).hasMessageContaining("产能单位");
        assertThatThrownBy(() -> service.setCapacity(F, "L1", null, BigDecimal.ZERO, "KG", Source.FACTORY_CONFIG, USER7))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setCapacity(F, "L1", null, BigDecimal.TEN, "KG", Source.FACTORY_CONFIG, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("操作人");
        assertThatThrownBy(() -> service.replaceEquipment(F, "PT-1", List.of(1L, 500L), Source.FACTORY_CONFIG, USER7))
                .isInstanceOf(BusinessException.class).hasMessageContaining("500");
        assertThatThrownBy(() -> service.retireCapacity("F-OTHER", 1L)).isInstanceOf(BusinessException.class);
        entityManager.clear();
        assertThat(allRows()).isZero();
        assertThat(requirements.findByFactoryId(F)).isEmpty();
    }

    @Test
    void equipmentReplaceKeepsUnchangedRowsAndRetiresRemovedOnes() {
        List<ProductEquipmentRequirement> first = service.replaceEquipment(F, "PT-1", List.of(1L, 2L), Source.FACTORY_CONFIG, USER7);
        Long keptId = first.stream().filter(r -> r.getEquipmentId() == 1L).findFirst().orElseThrow().getId();
        service.replaceEquipment(F, "PT-1", List.of(1L, 3L), Source.FACTORY_CONFIG, USER7);
        entityManager.clear();

        assertThat(requirements.findByFactoryIdAndProductTypeId(F, "PT-1"))
                .extracting(ProductEquipmentRequirement::getEquipmentId).containsExactlyInAnyOrder(1L, 3L);
        assertThat(requirements.findByFactoryIdAndProductTypeId(F, "PT-1"))
                .filteredOn(r -> r.getEquipmentId() == 1L).singleElement()
                .extracting(ProductEquipmentRequirement::getId).isEqualTo(keptId);
        assertThat(((Number) entityManager.getEntityManager().createNativeQuery(
                "select count(*) from product_equipment_requirements where equipment_id = 2 and deleted_at is not null")
                .getSingleResult()).longValue()).isEqualTo(1);

        service.replaceEquipment(F, "PT-1", List.of(), Source.FACTORY_CONFIG, USER7);
        entityManager.clear();
        assertThat(requirements.findByFactoryIdAndProductTypeId(F, "PT-1")).isEmpty();
    }
}
