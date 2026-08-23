package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「家族成员按设计有没有自己的投入行」这条判据 —— <b>只许有一份</b>。
 *
 * <h2>它修的是什么(2026-08-19 prod 回归)</h2>
 *
 * <p>联合生产里投入只消耗一次: {@code ensureDraft} 只把画布的 RAW 行播到家族<b>锚点</b>上,
 * 其余终端产出按设计没有自己的明细。所以逐成员要求「至少一条原辅料」的两道闸
 * ({@code validateActivatableItems} / {@code requireBomCompleteForActivation})
 * 必须放过非锚点成员。
 *
 * <p>这个豁免原来写成 {@code role == BY_PRODUCT} —— 那是**代理**:
 * 在坎 3 之前, 没人声明时第二个终端会被按<b>节点 id 字典序</b>自动编号成 {@code BY_PRODUCT},
 * 于是「非锚点」恰好等价于「BY_PRODUCT」。
 *
 * <p>坎 3 删掉那条字典序规则后, 两个都不是副产的终端拿到 {@code MAIN} + {@code CO_PRODUCT}
 * ⇒ 代理失效 ⇒ {@code CO_PRODUCT} 不再被豁免 ⇒ 多产出工作流<b>发布不了</b>。
 *
 * <pre>
 * prod 实测 2026-08-19 01:0x —— 建两个都不是副产的终端, publish-and-activate:
 *   409 BOM_ACTIVATION_ITEMS_REQUIRED 「BOM 还没有任何原辅料或包材，不能激活」
 * 而同一套脚本在坎 3 之前跑得通(CLAUDEPF-成品乙 v5 以 0 行明细到达 ACTIVE)。
 * </pre>
 *
 * <h2>⚠️ 覆盖面如实说</h2>
 *
 * <p>下面「行为」那一组跑在 {@link BomRecipe#bearsOwnInputRows} 上, 是真行为断言;
 * 「唯一判据」那一组读的是**源码文本**, 只挡得住「有人又在别处自己判一遍」,
 * 挡不住运行期。运行期由 prod 上真发布一次多产出工作流来证明(见本轮交接件 §10)。
 */
@DisplayName("契约 — 非锚点成员的投入行豁免只许有一份判据")
class FamilyInputRowExemptionContractTest {

    private static final Path ACTIVATION = Path.of("src", "main", "java", "com", "cretas", "aims",
            "service", "bom", "impl", "BomRecipeServiceImpl.java");
    private static final Path CATALOG = Path.of("src", "main", "java", "com", "cretas", "aims",
            "service", "validation", "ProductProcessWorkflowCatalogValidator.java");

    // ---------- 行为 ----------

    @Test
    @DisplayName("锚点(MAIN)照常校验 —— 空 BOM 依然激活不了")
    void anchorStillCarriesTheGate() {
        assertThat(BomRecipe.bearsOwnInputRows(BomRecipe.OutputRole.MAIN))
                .as("MAIN 是投入行的落点, 豁免它等于任何空 BOM 都能激活")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 CO_PRODUCT 必须被豁免 —— 漏掉它就是 2026-08-19 那次 prod 回归")
    void coProductIsExempt() {
        assertThat(BomRecipe.bearsOwnInputRows(BomRecipe.OutputRole.CO_PRODUCT))
                .as("联产品同样没有自己的投入行(投入挂在家族锚点上)")
                .isFalse();
    }

    @Test
    @DisplayName("BY_PRODUCT 仍然豁免 —— 原有行为一个字不变")
    void byProductStaysExempt() {
        assertThat(BomRecipe.bearsOwnInputRows(BomRecipe.OutputRole.BY_PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("⛔ role 为 null 时照常校验 —— 宁可严不可松, 不许把历史行悄悄放行")
    void nullRoleIsStillValidated() {
        assertThat(BomRecipe.bearsOwnInputRows(null))
                .as("写成 `role != MAIN` 会让历史 null 行从「校验」变成「跳过」, 那是削弱这道闸")
                .isTrue();
    }

    @Test
    @DisplayName("每个角色都有明确归属 —— 将来新增角色会落在「照常校验」这一侧")
    void everyRoleIsClassified() {
        long exempt = java.util.Arrays.stream(BomRecipe.OutputRole.values())
                .filter(role -> !BomRecipe.bearsOwnInputRows(role))
                .count();
        assertThat(exempt)
                .as("当前恰好两个非锚点角色被豁免; 数变了说明枚举动过, 请同时复核这条判据")
                .isEqualTo(2);
    }

    // ---------- 唯一判据(源码级, 局限见类注释) ----------

    @Test
    @DisplayName("两处消费方都走同一个 helper, ⛔ 不许各自再判一遍 BY_PRODUCT")
    void bothConsumersShareTheSingleCriterion() throws Exception {
        String activation = Files.readString(ACTIVATION);
        String catalog = Files.readString(CATALOG);

        assertThat(activation)
                .as("激活循环必须问 helper")
                .contains("BomRecipe.bearsOwnInputRows(member.getOutputRole())");
        assertThat(catalog)
                .as("目录校验器必须问同一个 helper —— 两处各写一遍就会漂(形态 D)")
                .contains("BomRecipe.bearsOwnInputRows(recipe.getOutputRole())");

        // ⛔ 阴性对照: 这两个文件里不许再出现「拿 BY_PRODUCT 当『没有投入行』的代理」那种写法。
        //    ⚠️ 只禁这一种具体形态 —— 别的 BY_PRODUCT 判据(NRV 抵扣、人工制费)是对的, 不许误伤。
        assertThat(activation)
                .doesNotContain("member.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT");
        assertThat(catalog)
                .doesNotContain("recipe.getOutputRole() == BomRecipe.OutputRole.BY_PRODUCT");
    }

    @Test
    @DisplayName("阳性对照: 锚点定位串确实能在源码里找到 —— 否则上面那组「不包含」是恒真的")
    void theAnchorsActuallyExist() throws Exception {
        assertThat(Files.readString(ACTIVATION))
                .as("定位不到激活循环, 上面的 doesNotContain 就没有意义")
                .contains("validateActivatableItems(member);")
                .contains("readinessService.requireBomCompleteForActivation(factoryId, member);");
        assertThat(Files.readString(CATALOG))
                .contains("findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(");
    }
}
