package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.bom.BomRecipeService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-19 F006「演示·酱卤牛肉250g 的成本是怎么构成的？」被判成 BOM_RECIPE_QUERY, 工具只收编号, 回
 * 「请提供 productTypeId 或 recipeId 至少一个」: 开发字段名露给用户, 也没从原话里按产品名找产品。
 * 钉住: 产品名 (含「演示·」前缀 / 规格 / 整句原话) 解析成产品; 多命中列候选; 查不到如实说; 没说产品时问人话,
 * 任何面向用户的文字不含字段名。
 */
@DisplayName("BOM 配方查询: 按产品名解析, 缺参提示用中文业务名")
class BomRecipeQueryProductNameContractTest {

    private static final String FACTORY = "F006";

    private final BomRecipeService recipeService = mock(BomRecipeService.class);
    private final ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
    private final ProcessSecretAccessService secrets = mock(ProcessSecretAccessService.class);
    private final BomRecipeQueryTool tool = new BomRecipeQueryTool();

    private static ProductType product(String id, String name, String category) {
        ProductType t = new ProductType();
        t.setId(id);
        t.setName(name);
        t.setProductCategory(category);
        return t;
    }

    private void catalog(ProductType... items) {
        List<ProductType> list = new ArrayList<>(List.of(items));
        when(productTypes.findByFactoryIdAndIsActiveTrue(FACTORY)).thenReturn(list);
        ReflectionTestUtils.setField(tool, "recipeService", recipeService);
        ReflectionTestUtils.setField(tool, "productTypeRepository", productTypes);
        ReflectionTestUtils.setField(tool, "processSecretAccessService", secrets);
        when(secrets.decide(any(), anyString(), any(), any()))
                .thenReturn(new ProcessSecretAccessService.Decision(
                        ProcessSecretAccessService.Outcome.NOT_PROTECTED, null, null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(Map<String, Object> params) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                tool, "doExecute", FACTORY, new HashMap<>(params), new HashMap<String, Object>());
    }

    private static void assertNoDevFieldNames(Object message) {
        assertThat(String.valueOf(message)).doesNotContain("productTypeId", "recipeId", "bomRecipeId");
    }

    @ParameterizedTest(name = "产品名「{0}」→ 取演示·酱卤牛肉250g 的当前配方")
    @ValueSource(strings = {"酱卤牛肉", "演示·酱卤牛肉250g", "演示·酱卤牛肉250g 的成本是怎么构成的？"})
    void productNameResolvesToTheProduct(String said) {
        catalog(product("PT-1", "演示·酱卤牛肉250g", "FINISHED_PRODUCT"),
                product("PT-2", "牛肉酱", "FINISHED_PRODUCT"));
        BomRecipe recipe = new BomRecipe();
        recipe.setId("R-1");
        recipe.setProductTypeId("PT-1");
        when(recipeService.getCurrentRecipe(FACTORY, "PT-1")).thenReturn(Optional.of(recipe));

        Map<String, Object> out = run(Map.of("productName", said));

        assertThat(out.get("message")).isEqualTo("查询成功");
        assertThat(out.get("data")).isSameAs(recipe);
        verify(recipeService).getCurrentRecipe(FACTORY, "PT-1");
    }

    @Test
    @DisplayName("抽参没给产品名时, 用原话找产品 (改前直接回「请提供 productTypeId…」)")
    void fallsBackToTheOriginalSentenceWhenNoParamWasExtracted() {
        catalog(product("PT-1", "演示·酱卤牛肉250g", "FINISHED_PRODUCT"));
        BomRecipe recipe = new BomRecipe();
        recipe.setId("R-1");
        recipe.setProductTypeId("PT-1");
        when(recipeService.getCurrentRecipe(FACTORY, "PT-1")).thenReturn(Optional.of(recipe));

        Map<String, Object> out = run(Map.of("originalUserInput", "演示·酱卤牛肉250g 的成本是怎么构成的？"));

        assertThat(out.get("message")).isEqualTo("查询成功");
    }

    @Test
    @DisplayName("名称命中多个产品 → 列候选让用户选, 不替用户挑")
    void manyMatchesListCandidates() {
        catalog(product("PT-1", "酱卤牛肉250g", "FINISHED_PRODUCT"),
                product("PT-2", "酱卤牛肉500g", "FINISHED_PRODUCT"));

        Map<String, Object> out = run(Map.of("productName", "酱卤牛肉250g和酱卤牛肉500g"));

        assertThat(String.valueOf(out.get("message"))).contains("酱卤牛肉250g", "酱卤牛肉500g", "请说清楚");
        assertNoDevFieldNames(out.get("message"));
        verify(recipeService, never()).getCurrentRecipe(anyString(), anyString());
    }

    @Test
    @DisplayName("查不到这个产品 → 如实说没找到, 不猜, 不查配方")
    void unknownProductIsReportedHonestly() {
        catalog(product("PT-1", "演示·酱卤牛肉250g", "FINISHED_PRODUCT"));

        Map<String, Object> out = run(Map.of("productName", "牛油果"));

        assertThat(String.valueOf(out.get("message"))).contains("没有").contains("牛油果");
        assertNoDevFieldNames(out.get("message"));
        verify(recipeService, never()).getCurrentRecipe(anyString(), anyString());
    }

    @Test
    @DisplayName("没说是哪个产品 → 用中文业务名问, 不露字段名")
    void nothingSaidAsksInPlainChinese() {
        catalog(product("PT-1", "演示·酱卤牛肉250g", "FINISHED_PRODUCT"));

        Map<String, Object> out = run(Map.of());

        assertThat(out.get("message")).isEqualTo("请告诉我是哪个产品或配方");
        assertNoDevFieldNames(out.get("message"));
    }

    @Test
    @DisplayName("产品存在但还没有生效配方 → 不露产品编号")
    void productWithoutActiveRecipeDoesNotLeakTheId() {
        catalog(product("PT-1", "演示·酱卤牛肉250g", "FINISHED_PRODUCT"));
        when(recipeService.getCurrentRecipe(FACTORY, "PT-1")).thenReturn(Optional.empty());

        Map<String, Object> out = run(Map.of("productName", "酱卤牛肉"));

        assertThat(String.valueOf(out.get("message"))).doesNotContain("PT-1");
        assertThat(String.valueOf(out.get("message"))).contains("没有当前生效的配方");
    }
}
