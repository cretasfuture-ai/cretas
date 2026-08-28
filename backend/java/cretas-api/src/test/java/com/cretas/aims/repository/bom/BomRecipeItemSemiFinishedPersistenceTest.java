package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomRecipeItem;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回料(半成品)行在 ORM 这一层真的存得下、读得回。
 *
 * <p>为什么单独有这一条: 上层的 mockito 测试证明的是「服务把字段填对了」,
 * 而 {@code material_type_id} 从 {@code nullable = false} 变成可空是**映射层**的事 ——
 * 服务填对了但列还是 NOT NULL, 症状是 flush 时才炸, 而 mockito 永远不 flush。
 *
 * <p>⚠️ 这一层<b>不</b>验异或约束: H2 的 schema 由 Hibernate ddl-auto 生成, Flyway 在
 * test profile 是关的 (见 application-test.properties), 那条 CHECK 不在这里。
 * 它的行为由 {@code BomSemiFinishedRowMigrationContractTest} 在真库上跑。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("BomRecipeItem — 回料行的持久化往返")
class BomRecipeItemSemiFinishedPersistenceTest {

    private static final String FACTORY = "F-BRI-SEMI";
    private static final String RECIPE = "RECIPE-BRI-SEMI";

    @Autowired
    private BomRecipeItemRepository repository;

    @Autowired
    private BomRecipeRepository recipeRepository;

    @Autowired
    private EntityManager entityManager;

    /** bom_recipe_items.recipe_id 是硬外键, 没有父配方这张表一行都写不进去。 */
    @org.junit.jupiter.api.BeforeEach
    void ensureParentRecipe() {
        if (recipeRepository.findById(RECIPE).isPresent()) {
            return;
        }
        com.cretas.aims.entity.bom.BomRecipe recipe = new com.cretas.aims.entity.bom.BomRecipe();
        recipe.setId(RECIPE);
        recipe.setFactoryId(FACTORY);
        recipe.setRecipeCode("BOM-BRI-SEMI-001");
        recipe.setProductTypeId("PT-BRI-SEMI");
        recipe.setProductName("回料持久化测试产品");
        recipe.setVersion(1);
        recipe.setIsCurrent(false);
        recipe.setOutputQuantityPerUnit(BigDecimal.ONE);
        recipe.setOutputUnit("kg");
        recipe.setStatus(com.cretas.aims.entity.bom.BomRecipe.Status.DRAFT);
        recipe.setSourceType(com.cretas.aims.entity.bom.BomRecipe.SourceType.MANUAL);
        recipeRepository.saveAndFlush(recipe);
    }

    private BomRecipeItem row(String materialTypeId, String semiFinishedRefCode, int sortOrder) {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(RECIPE);
        item.setFactoryId(FACTORY);
        item.setMaterialTypeId(materialTypeId);
        item.setSemiFinishedRefCode(semiFinishedRefCode);
        item.setUnit("kg");
        item.setStandardQuantity(new BigDecimal("0.1951"));
        item.setYieldRate(new BigDecimal("100.00"));
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        item.setSortOrder(sortOrder);
        item.setMaterialCategory(semiFinishedRefCode == null
                ? "RAW" : BomRecipeItem.CATEGORY_SEMI_FINISHED);
        return item;
    }

    @Test
    @DisplayName("回料行: material_type_id 为 null 也能落库, semi_finished_ref_code 读得回来")
    void semiFinishedRowRoundTrips() {
        BomRecipeItem saved = repository.saveAndFlush(
                row(null, "WIP-F006-REWORK-20260829", 1));
        entityManager.clear();

        BomRecipeItem reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMaterialTypeId())
                .as("列还是 NOT NULL 的话上面那次 flush 就该炸了; 走到这里说明可空生效")
                .isNull();
        assertThat(reloaded.getSemiFinishedRefCode()).isEqualTo("WIP-F006-REWORK-20260829");
        assertThat(reloaded.getMaterialCategory())
                .isEqualTo(BomRecipeItem.CATEGORY_SEMI_FINISHED);
        assertThat(BomRecipeItem.isSemiFinishedRow(reloaded)).isTrue();
    }

    @Test
    @DisplayName("阳性对照: 普通原料行照旧落库、照旧被按物料反查到")
    void plainMaterialRowStillRoundTripsAndIsQueryable() {
        BomRecipeItem saved = repository.saveAndFlush(row("MT-BRI-SEMI-001", null, 2));
        entityManager.clear();

        BomRecipeItem reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getMaterialTypeId()).isEqualTo("MT-BRI-SEMI-001");
        assertThat(reloaded.getSemiFinishedRefCode()).isNull();
        assertThat(BomRecipeItem.isSemiFinishedRow(reloaded)).isFalse();

        assertThat(repository.findByFactoryIdAndMaterialTypeId(FACTORY, "MT-BRI-SEMI-001"))
                .extracting(BomRecipeItem::getId)
                .contains(saved.getId());
    }

    @Test
    @DisplayName("按物料反查不会把回料行捞出来 —— 它没有那个物料, 不该出现在「谁用了这个原料」里")
    void materialReverseLookupExcludesSemiFinishedRows() {
        repository.saveAndFlush(row(null, "WIP-F006-REWORK-EXCLUDE", 3));
        entityManager.clear();

        List<BomRecipeItem> byMaterial =
                repository.findByFactoryIdAndMaterialTypeId(FACTORY, "MT-BRI-SEMI-001");

        assertThat(byMaterial)
                .allSatisfy(item -> assertThat(item.getSemiFinishedRefCode()).isNull());
    }
}
