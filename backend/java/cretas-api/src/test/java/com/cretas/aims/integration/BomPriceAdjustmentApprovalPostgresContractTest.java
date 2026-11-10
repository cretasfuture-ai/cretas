package com.cretas.aims.integration;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomPriceAdjustmentProposalRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import com.cretas.aims.service.bom.impl.BomPriceAdjustmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 真 PostgreSQL: BOM 调价建议批准 (D-S5-10 归档版本不回写 / D-S5-11 批准即所见)。
 *
 * <p>为什么要真库: 拒绝路径的承诺是「一行不改」—— 这取决于 {@code @Transactional} 代理在 409 时真的回滚、
 * 以及版本状态是经 {@code SELECT ... FOR UPDATE} 读到的; Mockito 只能证明内存对象没被改,
 * 证明不了库里的 {@code bom_recipe_items.unit_price / item_cost} 与建议行在提交后真的没动。
 * 读回一律走 JdbcTemplate (绕过持久化上下文)。</p>
 *
 * <p>schema 由实体 create-drop 建, 外键全部摘掉 (一次性库), 不代表生产迁移。
 * 开关: {@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL} (见 {@link DisposablePostgresTargetGuard})。</p>
 */
@SpringBootTest(classes = BomPriceAdjustmentApprovalPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class BomPriceAdjustmentApprovalPostgresContractTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import(BomPriceAdjustmentServiceImpl.class)
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired BomPriceAdjustmentService service;
    @Autowired BomRecipeRepository recipes;
    @Autowired BomRecipeItemRepository items;
    @Autowired BomPriceAdjustmentProposalRepository proposals;
    @Autowired RawMaterialTypeRepository materials;

    private TransactionTemplate tx;
    private String factory;
    private String material;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        factory = "BPA-" + UUID.randomUUID().toString().substring(0, 8);
        material = "RMT-PKG-" + factory;
        // 线上形状: 收货 100 个 @0.8 之后包材移动均价 0.7501
        tx.executeWithoutResult(status -> {
            RawMaterialType type = new RawMaterialType();
            type.setId(material);
            type.setFactoryId(factory);
            type.setCode("PKG-" + factory);
            type.setName("QA-S3-1104-包材");
            type.setCategory("包材");
            type.setUnit("个");
            type.setIsByproduct(false);
            type.setSelfSupplied(false);
            type.setIsActive(true);
            type.setCreatedBy(1L);
            type.setUnitPrice(new BigDecimal("0.5"));
            type.setMovingAvgPrice(new BigDecimal("0.7501"));
            materials.saveAndFlush(type);
        });
    }

    @Test
    void archivedVersionProposalIsRejectedAndNothingIsWrittenInTheDatabase() {
        long line = line("RW-V1", 1, BomRecipe.Status.ARCHIVED, "2");
        long proposal = proposal("RW-V1", line, "0.7501");

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(factory, proposal, 9L, "ok"), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_RECIPE_NOT_EDITABLE);
        Map<String, Object> row = itemRow(line);
        assertThat((BigDecimal) row.get("unit_price")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) row.get("item_cost")).as("2 个 × 0.5, 未被改成 2 × 0.7501").isEqualByComparingTo("1.0");
        Map<String, Object> p = proposalRow(proposal);
        assertThat(p.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) p.get("proposed_unit_price")).isEqualByComparingTo("0.7501");
        assertThat(p.get("approved_by")).isNull();
        assertThat(auditCount()).isZero();
    }

    @Test
    void staleProposalIsRejectedAndNeitherTheLineNorTheProposalIsRewritten() {
        long line = line("SRC-V3", 3, BomRecipe.Status.ACTIVE, "1");
        long proposal = proposal("SRC-V3", line, "0.8");

        BusinessException rejected = catchThrowableOfType(
                () -> service.approve(factory, proposal, 9L, "ok"), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getErrorCode()).isEqualTo(BomPriceAdjustmentServiceImpl.ERROR_STALE);
        assertThat((BigDecimal) itemRow(line).get("unit_price")).isEqualByComparingTo("0.5");
        Map<String, Object> p = proposalRow(proposal);
        assertThat((BigDecimal) p.get("proposed_unit_price")).as("⛔ 不能被悄悄改成 0.7501").isEqualByComparingTo("0.8");
        assertThat(p.get("status")).isEqualTo("PENDING");
        assertThat(auditCount()).isZero();
    }

    @Test
    void activeVersionApprovalWritesExactlyTheProposalPriceAndLeavesTheProposalPriceAsSeen() {
        long line = line("RW-V4", 4, BomRecipe.Status.ACTIVE, "3");
        long proposal = proposal("RW-V4", line, "0.7501");

        service.approve(factory, proposal, 9L, "ok");

        Map<String, Object> row = itemRow(line);
        assertThat((BigDecimal) row.get("unit_price")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) row.get("item_cost")).as("3 个 × 0.7501").isEqualByComparingTo("2.2503");
        Map<String, Object> p = proposalRow(proposal);
        assertThat(p.get("status")).isEqualTo("APPROVED");
        assertThat((BigDecimal) p.get("proposed_unit_price")).isEqualByComparingTo("0.7501");
        assertThat((BigDecimal) p.get("current_unit_price")).isEqualByComparingTo("0.5");
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void generationWritesProposalsOnlyForEditableVersions() {
        long active = line("SRC-V3", 3, BomRecipe.Status.ACTIVE, "1");
        long draft = line("NEW-V1", 1, BomRecipe.Status.DRAFT, "1");
        long archived = line("SRC-V1", 1, BomRecipe.Status.ARCHIVED, "1");

        service.generateForMaterial(factory, material, null, new BigDecimal("0.8"),
                BomPriceAdjustmentProposal.SourceType.PURCHASE_RECEIVE, "RCV-1", 1L);

        assertThat(jdbc.queryForList("select recipe_item_id from bom_price_adjustment_proposals "
                + "where factory_id = ? order by recipe_item_id", Long.class, factory))
                .containsExactlyInAnyOrder(active, draft)
                .doesNotContain(archived);
        assertThat(jdbc.queryForList("select proposed_unit_price from bom_price_adjustment_proposals "
                + "where factory_id = ?", BigDecimal.class, factory))
                .allSatisfy(price -> assertThat(price).isEqualByComparingTo("0.7501"));
    }

    // ---- fixtures ----

    private long line(String tag, int version, BomRecipe.Status status, String quantity) {
        String recipeId = "BOM-" + tag + "-" + factory;
        return tx.execute(s -> {
            BomRecipe bom = new BomRecipe();
            bom.setId(recipeId);
            bom.setFactoryId(factory);
            bom.setRecipeCode("CODE-" + tag + "-" + factory);
            bom.setProductTypeId("SKU-" + tag + "-" + factory);
            bom.setProductName("product " + tag);
            bom.setVersion(version);
            bom.setIsCurrent(status == BomRecipe.Status.ACTIVE);
            bom.setOutputQuantityPerUnit(BigDecimal.ONE);
            bom.setOutputUnit("个");
            bom.setStatus(status);
            recipes.saveAndFlush(bom);
            BomRecipeItem item = new BomRecipeItem();
            item.setRecipeId(recipeId);
            item.setFactoryId(factory);
            item.setMaterialTypeId(material);
            item.setMaterialName("QA-S3-1104-包材");
            item.setStandardQuantity(new BigDecimal(quantity));
            item.setYieldRate(new BigDecimal("100.00"));
            item.setUnit("个");
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setMaterialCategory("PACKAGING");
            item.setSortOrder(0);
            item.setIsOptional(false);
            item.setPerPortion(false);
            item.setUnitPrice(new BigDecimal("0.5"));
            item.setItemCost(item.computeItemCost());
            return items.saveAndFlush(item).getId();
        });
    }

    private long proposal(String tag, long lineId, String proposed) {
        return tx.execute(s -> {
            BomPriceAdjustmentProposal p = new BomPriceAdjustmentProposal();
            p.setFactoryId(factory);
            p.setRecipeId("BOM-" + tag + "-" + factory);
            p.setRecipeItemId(lineId);
            p.setMaterialTypeId(material);
            p.setCurrentUnitPrice(new BigDecimal("0.5"));
            p.setProposedUnitPrice(new BigDecimal(proposed));
            p.setDeltaAmount(new BigDecimal(proposed).subtract(new BigDecimal("0.5")));
            p.setAffectedProductCount(1);
            p.setStatus(BomPriceAdjustmentProposal.Status.PENDING);
            p.setSourceType(BomPriceAdjustmentProposal.SourceType.PURCHASE_RECEIVE);
            return proposals.saveAndFlush(p).getId();
        });
    }

    private Map<String, Object> itemRow(long id) {
        return jdbc.queryForMap("select unit_price, item_cost from bom_recipe_items where id = ?", id);
    }

    private Map<String, Object> proposalRow(long id) {
        return jdbc.queryForMap("select status, proposed_unit_price, current_unit_price, approved_by "
                + "from bom_price_adjustment_proposals where id = ?", id);
    }

    private int auditCount() {
        return jdbc.queryForObject("select count(*) from bom_price_adjustment_audits where factory_id = ?",
                Integer.class, factory);
    }
}
