package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 重算入口守卫: 本事务没有持有该族 → 409「配方已被别人改动，请刷新后重试」(不是通用 500);
 * 登记过的族 → 放行 (阳性对照, 证明 409 是因为没登记, 不是守卫恒拒)。
 */
class BomRecipeHeadLocksGuardTest {

    @BeforeEach
    void openSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeSynchronization() {
        new java.util.ArrayList<>(TransactionSynchronizationManager.getResourceMap().keySet())
                .forEach(TransactionSynchronizationManager::unbindResourceIfPossible);
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void recomputingAFamilyThisTransactionDoesNotHoldIsA409Retry() {
        BusinessException refused = catchThrowableOfType(
                () -> BomRecipeHeadLocks.requireHeld(recipe("FAM-MOVED")), BusinessException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getCode()).isEqualTo(409);
        assertThat(refused.getErrorCode()).isEqualTo(BomRecipeHeadLocks.FAMILY_LOCK_STALE);
        assertThat(refused.getMessage()).isEqualTo("配方已被别人改动，请刷新后重试");
        assertThat(refused.getCause()).hasMessageContaining("FAM-MOVED");
    }

    @Test
    void aFamilyRegisteredInThisTransactionPasses() {
        BomRecipeHeadLocks.registerCreatedFamily("F006", "FAM-HELD");

        assertThatCode(() -> BomRecipeHeadLocks.requireHeld(recipe("FAM-HELD"))).doesNotThrowAnyException();
    }

    /**
     * 锁一组配方头: 一条 FOR UPDATE, 顺序交给 PG 的 ORDER BY (与族锁同一比较规则)。
     * 不能在 Java 里排序后逐个 lockById —— 非 UUID 形状的 id 两种比较规则可能相反, 会和族锁成环。
     */
    @Test
    void lockingASetOfHeadsUsesOnePgOrderedQueryNotAJavaSortedLoop() {
        com.cretas.aims.repository.bom.BomRecipeRepository recipes =
                org.mockito.Mockito.mock(com.cretas.aims.repository.bom.BomRecipeRepository.class);
        org.mockito.Mockito.when(recipes.lockByFactoryIdAndIdInOrderById("F006", java.util.List.of("R-b", "R-a")))
                .thenReturn(java.util.List.of(recipeWithId("R-a"), recipeWithId("R-b")));

        java.util.Map<String, BomRecipe> locked = BomRecipeHeadLocks.lockHeadsInIdOrder(
                null, recipes, "F006", java.util.List.of("R-b", "R-a", "R-b"));

        assertThat(locked.keySet()).as("按 PG 返回的顺序, 去重").containsExactly("R-a", "R-b");
        org.mockito.Mockito.verify(recipes)
                .lockByFactoryIdAndIdInOrderById("F006", java.util.List.of("R-b", "R-a"));
        org.mockito.Mockito.verify(recipes, org.mockito.Mockito.never())
                .lockByIdAndFactoryId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static BomRecipe recipeWithId(String id) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId("F006");
        return recipe;
    }

    private static BomRecipe recipe(String familyId) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("R-" + familyId);
        recipe.setFactoryId("F006");
        recipe.setBomFamilyId(familyId);
        return recipe;
    }
}
