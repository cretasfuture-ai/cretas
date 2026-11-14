package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.CreateBomRecipeRequest.BomRecipeItemDTO;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 红线 3 锁序回归: 草稿改行 / 删行必须「整族配方头 → 明细行」, 不能回到 #1586 的「行 → 头」。
 *
 * <p>整表更新、激活、升级会在持有整族头之后改写明细行; 改行 / 删行若先锁行再锁头, 两个方向并存就会互等成环。
 * 这里用 InOrder 钉住加锁调用的先后 (真 PG 的并发行为由 BomFamilyCostLockOrderPostgresContractTest 守)。
 * 用例让配方处于 ACTIVE, 两把锁都拿到之后在 DRAFT 闸上停下, 不必搭整套写路径。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipeItemLockOrderContractTest {

    private static final String F = "F006";
    private static final String FAMILY = "FAM-LOCK-ORDER";

    @Mock BomRecipeRepository recipeRepo;
    @Mock BomRecipeItemRepository itemRepo;

    @InjectMocks BomRecipeServiceImpl service;

    private BomRecipe recipe;
    private BomRecipeItem item;

    @BeforeEach
    void setUp() {
        recipe = new BomRecipe();
        recipe.setId("R-LOCK");
        recipe.setFactoryId(F);
        recipe.setBomFamilyId(FAMILY);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        item = new BomRecipeItem();
        item.setId(5L);
        item.setFactoryId(F);
        item.setRecipeId("R-LOCK");
        item.setMaterialCategory("RAW");
        when(itemRepo.findRecipeIdById(5L)).thenReturn(Optional.of("R-LOCK"));
        when(recipeRepo.findBomFamilyIdByIdAndFactoryId("R-LOCK", F)).thenReturn(Optional.of(FAMILY));
        when(recipeRepo.lockFamilyByFactoryIdAndBomFamilyIdOrderById(F, FAMILY)).thenReturn(List.of(recipe));
        when(itemRepo.findByIdForUpdate(5L)).thenReturn(Optional.of(item));
        when(recipeRepo.findById("R-LOCK")).thenReturn(Optional.of(recipe));
    }

    @Test
    void updateItemLocksTheWholeFamilyHeadBeforeTheItemRow() {
        assertThat(catchThrowableOfType(() -> service.updateItem(F, 5L, new BomRecipeItemDTO()),
                IllegalStateException.class)).as("两把锁之后停在 DRAFT 闸").isNotNull();

        assertHeadThenRow();
    }

    @Test
    void deleteItemLocksTheWholeFamilyHeadBeforeTheItemRow() {
        assertThat(catchThrowableOfType(() -> service.deleteItem(F, 5L),
                IllegalStateException.class)).as("两把锁之后停在 DRAFT 闸").isNotNull();

        assertHeadThenRow();
    }

    /** 阴性对照: 两步之间行被挪到别的配方 (锁住的族不是它现在所在的) → 409, 不在没锁住的族上继续写。 */
    @Test
    void rowMovedToAnotherRecipeBetweenTheTwoLocksIsRefusedWith409() {
        item.setRecipeId("R-OTHER");

        BusinessException refused = catchThrowableOfType(
                () -> service.updateItem(F, 5L, new BomRecipeItemDTO()), BusinessException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getCode()).isEqualTo(409);
        assertThat(refused.getErrorCode()).isEqualTo("BOM_ITEM_MOVED_CONCURRENTLY");
        verify(recipeRepo, never()).findById(any());
    }

    private void assertHeadThenRow() {
        InOrder order = inOrder(itemRepo, recipeRepo);
        order.verify(itemRepo).findRecipeIdById(5L);
        order.verify(recipeRepo).lockFamilyByFactoryIdAndBomFamilyIdOrderById(F, FAMILY);
        order.verify(itemRepo).findByIdForUpdate(5L);
    }
}
