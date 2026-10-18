package com.cretas.aims.service.yield.bom;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次的成本按<b>它创建时绑定的那一版 BOM</b> 算。
 *
 * <h2>业务口径</h2>
 *
 * <p>8 月做的货，成本就该是 8 月那版配方算出来的；9 月改了配方，不该让 8 月的账跟着变。
 * 否则毛利报表纵向不可比、对账拿不出当时依据、报价没有锚。</p>
 *
 * <h2>为什么优先批次而不是计划（2026-08-27 生产实测）</h2>
 *
 * <ul>
 *   <li>批次侧绑定 <b>61/61 全部填了</b></li>
 *   <li>计划侧 43 个计划里<b>只有 36 个</b>有绑定</li>
 * </ul>
 *
 * <p>而成本归集此前读的是<b>计划</b>那份 —— 覆盖率更低，语义也不对
 * （一个计划可对多个批次，批次才是「这批货」）。</p>
 */
@DisplayName("批次 BOM 绑定")
class BatchBomBindingContractTest {

    private static final String PRODUCT = "pt-butter-chicken";

    private ProductionBatch batch(String recipeId, Integer version) {
        ProductionBatch b = new ProductionBatch();
        b.setProductTypeId(PRODUCT);
        if (recipeId != null) {
            b.setSelectedBomRecipeIdsByProduct(Map.of(PRODUCT, recipeId));
        }
        if (version != null) {
            b.setSelectedBomVersionsByProduct(Map.of(PRODUCT, version));
        }
        return b;
    }

    private ProductionPlan plan(String recipeId, Integer version) {
        ProductionPlan p = new ProductionPlan();
        p.setSelectedBomRecipeId(recipeId);
        p.setSelectedBomVersion(version);
        return p;
    }

    @Test
    @DisplayName("批次钉了就用批次的 —— 即使计划指向另一版")
    void batchBindingWinsOverPlan() {
        var r = BatchBomBinding.resolve(batch("recipe-A", 3), plan("recipe-B", 9));
        assertThat(r.recipeId()).isEqualTo("recipe-A");
        assertThat(r.version()).isEqualTo(3);
        assertThat(r.source()).isEqualTo(BatchBomBinding.Source.BATCH);
    }

    @Test
    @DisplayName("批次没钉才退回计划, 且来源要如实标 PLAN")
    void fallsBackToPlanAndSaysSo() {
        var r = BatchBomBinding.resolve(batch(null, null), plan("recipe-B", 9));
        assertThat(r.recipeId()).isEqualTo("recipe-B");
        assertThat(r.source())
                .as("⛔ 来源必须可观测 —— 不带来源就分不清「批次钉的」和「退回计划的」")
                .isEqualTo(BatchBomBinding.Source.PLAN);
    }

    @Test
    @DisplayName("两边都没有 → NONE, 且 pinned()=false (不许当成钉住了)")
    void neitherMeansNotPinned() {
        var r = BatchBomBinding.resolve(batch(null, null), plan(null, null));
        assertThat(r.pinned()).isFalse();
        assertThat(r.source()).isEqualTo(BatchBomBinding.Source.NONE);
    }

    @Test
    @DisplayName("批次只有一半绑定时失败关闭, 不许把未知批次伪装成计划绑定")
    void halfBindingFailsClosed() {
        assertThatThrownBy(() -> BatchBomBinding.resolve(batch("recipe-A", null), plan("recipe-B", 9)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getErrorCode()).isEqualTo("BATCH_BOM_BINDING_INCOMPLETE");
                });
    }

    @Test
    @DisplayName("权威多产出 map 没有运行时 owner → NONE, 不误借计划标量的产出 A")
    void authoritativeMapWithoutRuntimeOwnerMeansNone() {
        ProductionBatch b = new ProductionBatch();
        b.setProductTypeId(PRODUCT);
        b.setSelectedBomRecipeIdsByProduct(Map.of("other-product", "recipe-X"));
        b.setSelectedBomVersionsByProduct(Map.of("other-product", 5));
        var r = BatchBomBinding.resolve(b, plan("recipe-B", 9));
        assertThat(r.source()).isEqualTo(BatchBomBinding.Source.NONE);
        assertThat(r.pinned()).isFalse();
    }

    @Test
    @DisplayName("权威 map 的 key 集不一致时失败关闭")
    void mismatchedAuthoritativeMapsFailClosed() {
        ProductionBatch b = new ProductionBatch();
        b.setProductTypeId(PRODUCT);
        b.setSelectedBomRecipeIdsByProduct(Map.of(PRODUCT, "recipe-A", "product-B", "recipe-B"));
        b.setSelectedBomVersionsByProduct(Map.of(PRODUCT, 3));

        assertThatThrownBy(() -> BatchBomBinding.resolve(b, plan("recipe-plan", 9)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("映射不完整");
    }

    @Test
    @DisplayName("权威 map 存在但批次产品身份缺失时失败关闭")
    void authoritativeMapWithoutProductIdentityFailsClosed() {
        ProductionBatch b = new ProductionBatch();
        b.setSelectedBomRecipeIdsByProduct(Map.of(PRODUCT, "recipe-A"));
        b.setSelectedBomVersionsByProduct(Map.of(PRODUCT, 3));

        assertThatThrownBy(() -> BatchBomBinding.resolve(b, plan("recipe-plan", 9)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("映射不完整");
    }

    // ==================== 同产品跨批次版本不一致 ====================

    @Test
    @DisplayName("🔴 同产品的几批绑了不同版本 → 必须报出来 (这几批毛利不可横向比)")
    void mixedVersionsAreReported() {
        assertThat(BatchBomBinding.mixedVersions(List.of(3, 4, 3)))
                .as("期间改过配方 ⇒ 「这批毛利低」会被读成生产问题, 而实际是换了配方")
                .containsExactly(3, 4);
    }

    @Test
    @DisplayName("阴性对照: 版本一致时不许报 —— 一道天天误报的提示最终会被无视")
    void consistentVersionsAreSilent() {
        assertThat(BatchBomBinding.mixedVersions(List.of(3, 3, 3))).isEmpty();
        assertThat(BatchBomBinding.mixedVersions(List.of(3))).isEmpty();
        assertThat(BatchBomBinding.mixedVersions(List.of())).isEmpty();
        assertThat(BatchBomBinding.mixedVersions(null)).isEmpty();
    }

    @Test
    @DisplayName("null 版本不参与判定 (没钉的批次不该把一致的说成不一致)")
    void nullsDoNotCreateFalseMixed() {
        assertThat(BatchBomBinding.mixedVersions(java.util.Arrays.asList(3, null, 3))).isEmpty();
    }
}
