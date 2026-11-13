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

    private static BomRecipe recipe(String familyId) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("R-" + familyId);
        recipe.setFactoryId("F006");
        recipe.setBomFamilyId(familyId);
        return recipe;
    }
}
