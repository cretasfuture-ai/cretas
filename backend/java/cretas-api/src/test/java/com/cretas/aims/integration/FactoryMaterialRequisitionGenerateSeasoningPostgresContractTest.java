package com.cretas.aims.integration;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 真 PostgreSQL + 真 Hibernate + 真 Spring Data 仓库: 「按计划生成领料单」时调料表查询失败,
 * 单据 (主料行) 照样提交落库, 只是没有调料行并标 {@code seasoningItemsStatus=UNAVAILABLE}。
 *
 * <p>为什么非要真 PG: PostgreSQL 上一条语句报错, 同一个事务 (同一条连接) 后面的语句全部
 * 「current transaction is aborted」; 加上仓库事务拦截器把参与的外层事务标 rollback-only ——
 * 两层都只有真库真代理才看得见。失败注入用真的: 把 {@code bom_seasoning_items} 表改名, 仓库查询拿到真实的
 * SQL 错误 (relation does not exist)。
 *
 * <p>计划与 BOM 行是桩 (只取值, 不是本测试的对象); 被测的调料仓库、领料单仓库、物料档案 / 批次仓库、
 * 事务管理器全部是真 bean。
 */
@SpringBootTest(classes = FactoryMaterialRequisitionGenerateSeasoningPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@DisplayName("PG: generateFromPlan 调料读库失败只降级调料行, 单据照常提交")
class FactoryMaterialRequisitionGenerateSeasoningPostgresContractTest {

    private static final String FACTORY_ID = "REQ-SEASON-PG";
    private static final String PLAN_ID = "PP-REQ-SEASON-PG";
    private static final String PRODUCT_TYPE_ID = "PT-REQ-SEASON-PG";
    private static final String RECIPE_ID = "RECIPE-REQ-SEASON-PG";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import(FactoryMaterialRequisitionServiceImpl.class)
    static class Runtime {
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /** 计划只用来取计划量 / 产品; 真表的 NOT NULL 夹具与本测试无关。 */
    @MockBean
    ProductionPlanRepository productionPlans;

    /** BOM 行只用来取物料 / 配方 id (PlanBomItems 的解析在别处测)。 */
    @MockBean
    BomRecipeItemRepository bomRecipeItems;

    @Autowired FactoryMaterialRequisitionService service;
    @Autowired BomSeasoningItemRepository seasonings;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // 仪器自检: generateFromPlan 上的 @Transactional 真的生效 (被测 bean 是代理)
        assertThat(AopUtils.isAopProxy(service)).as("被测 service 没有事务代理").isTrue();
        // This disposable target intentionally uses entity-generated DDL without production FK fixtures.
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        // requisition_no 全局唯一, 单号按厂按天递增 —— 每条测试从干净的本厂开始
        jdbc.update("DELETE FROM factory_material_requisition_items WHERE requisition_id IN "
                + "(SELECT id FROM factory_material_requisitions WHERE factory_id = ?)", FACTORY_ID);
        jdbc.update("DELETE FROM factory_material_requisitions WHERE factory_id = ?", FACTORY_ID);
        jdbc.update("DELETE FROM bom_seasoning_items WHERE recipe_id = ?", RECIPE_ID);

        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setProductTypeId(PRODUCT_TYPE_ID);
        plan.setPlanNumber("PLAN-REQ-SEASON-PG");
        plan.setPlannedQuantity(new BigDecimal("100"));
        when(productionPlans.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));

        BomRecipeItem raw = new BomRecipeItem();
        raw.setId(1L);
        raw.setRecipeId(RECIPE_ID);
        raw.setFactoryId(FACTORY_ID);
        raw.setMaterialTypeId("MAT-PORK-PG");
        raw.setMaterialName("猪肉");
        raw.setStandardQuantity(new BigDecimal("2"));
        raw.setUnit("kg");
        raw.setMaterialCategory("RAW");
        when(bomRecipeItems.findCurrentByProduct(FACTORY_ID, PRODUCT_TYPE_ID)).thenReturn(List.of(raw));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            seasonings.save(seasoning("SEASON-A-PG", "香辛料", "10", 1));
            seasonings.save(seasoning("SEASON-B-PG", "黄油调味料", "25", 2));
        });
    }

    private static BomSeasoningItem seasoning(String materialTypeId, String name, String dosage, int seq) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setRecipeId(RECIPE_ID);
        s.setFactoryId(FACTORY_ID);
        s.setMaterialTypeId(materialTypeId);
        s.setName(name);
        s.setDosagePerKgG(new BigDecimal(dosage));
        s.setSection(BomSeasoningItem.SECTION_INJECTION);
        s.setSeq(seq);
        s.setCountInSeasoning(true);
        return s;
    }

    /** 已提交的明细 (JdbcTemplate 不在事务里 ⇒ 另一条连接, 只看得见提交了的行)。 */
    private List<Map<String, Object>> committedItems(String requisitionId) {
        return jdbc.queryForList("SELECT material_type_id, material_category, required_qty "
                + "FROM factory_material_requisition_items WHERE requisition_id = ? ORDER BY material_type_id",
                requisitionId);
    }

    private int committedRequisitions(String requisitionId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM factory_material_requisitions WHERE id = ?",
                Integer.class, requisitionId);
        return n == null ? -1 : n;
    }

    @Test
    @DisplayName("阳性对照: 调料表正常 ⇒ 落库 1 主料 + 2 调料 (80kg: 0.800 / 2.000), 状态 OK")
    void seasoningsCommittedWhenLookupSucceeds() {
        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L, new BigDecimal("80"));

        assertThat(mr.getSeasoningItemsStatus()).isEqualTo(FactoryMaterialRequisitionService.SEASONING_ITEMS_OK);
        assertThat(committedRequisitions(mr.getId())).isEqualTo(1);
        List<Map<String, Object>> items = committedItems(mr.getId());
        assertThat(items).extracting(r -> r.get("material_type_id"))
                .containsExactly("MAT-PORK-PG", "SEASON-A-PG", "SEASON-B-PG");
        assertThat((BigDecimal) items.get(1).get("required_qty")).isEqualByComparingTo("0.800");
        assertThat((BigDecimal) items.get(2).get("required_qty")).isEqualByComparingTo("2.000");
        assertThat(items.get(1).get("material_category")).isEqualTo("AUXILIARY");
    }

    @Test
    @DisplayName("🔴 调料表查询报真 SQL 错 ⇒ 不抛、单据与主料行真的提交落库、无调料行、状态 UNAVAILABLE")
    void seasoningLookupFailureStillCommitsTheRequisition() {
        jdbc.execute("ALTER TABLE bom_seasoning_items RENAME TO bom_seasoning_items_hidden_by_test");
        FactoryMaterialRequisition mr;
        try {
            // 阳性锚点: 注入真的生效 —— 同一个仓库查询此刻确实报错 (否则下面在守空气)
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> seasonings.findByRecipeIdOrderBySeqAsc(RECIPE_ID))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);

            mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L, new BigDecimal("80"));
        } finally {
            jdbc.execute("ALTER TABLE bom_seasoning_items_hidden_by_test RENAME TO bom_seasoning_items");
        }

        assertThat(mr.getSeasoningItemsStatus()).as("读失败必须标出来, 不能看起来像「配方没有调料」")
                .isEqualTo(FactoryMaterialRequisitionService.SEASONING_ITEMS_UNAVAILABLE);
        assertThat(committedRequisitions(mr.getId())).as("单据没落库 —— 外层写事务被拖成回滚").isEqualTo(1);
        List<Map<String, Object>> items = committedItems(mr.getId());
        assertThat(items).extracting(r -> r.get("material_type_id")).containsExactly("MAT-PORK-PG");
        assertThat(items.get(0).get("material_category")).isEqualTo("RAW");
        assertThat((BigDecimal) items.get(0).get("required_qty")).isEqualByComparingTo("200");
    }
}
