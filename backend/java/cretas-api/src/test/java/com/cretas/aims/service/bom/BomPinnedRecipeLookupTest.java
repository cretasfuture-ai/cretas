package com.cretas.aims.service.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * D-S2-11: a historical Workflow version must show the recipe that was pinned to IT, never today's recipe.
 * v1 had no auxiliary material; v2 added one. Looking up v1 returns v1's recipe (without it), v2 returns v2's.
 */
class BomPinnedRecipeLookupTest {
    private static final String F = "F006";
    private static final String PRODUCT = "P-1";

    private BomRecipeRepository recipes;
    private ProductProcessWorkflowRepository workflows;
    private ProductProcessWorkflowRevisionRepository revisions;
    private BomWorkflowRevisionService service;
    private BomRecipe recipeV1;
    private BomRecipe recipeV2;

    private static ProductProcessWorkflow workflow(long id, String factory) {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setId(id);
        workflow.setFactoryId(factory);
        workflow.setProductTypeId(PRODUCT);
        return workflow;
    }

    private static ProductProcessWorkflowRevision revision(long id, long workflowId) {
        ProductProcessWorkflowRevision revision = new ProductProcessWorkflowRevision();
        revision.setId(id);
        revision.setFactoryId(F);
        revision.setWorkflowId(workflowId);
        return revision;
    }

    private static BomRecipe recipe(String id, int version, long revisionId) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(id);
        recipe.setFactoryId(F);
        recipe.setProductTypeId(PRODUCT);
        recipe.setVersion(version);
        recipe.setWorkflowRevisionId(revisionId);
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        return recipe;
    }

    @BeforeEach
    void setUp() {
        recipes = mock(BomRecipeRepository.class);
        workflows = mock(ProductProcessWorkflowRepository.class);
        revisions = mock(ProductProcessWorkflowRevisionRepository.class);
        service = new BomWorkflowRevisionService(recipes, workflows, revisions, null, null, null, null, null);

        when(workflows.findByIdAndFactoryId(1L, F)).thenReturn(Optional.of(workflow(1L, F)));
        when(workflows.findByIdAndFactoryId(2L, F)).thenReturn(Optional.of(workflow(2L, F)));
        when(workflows.findByIdAndFactoryId(3L, F)).thenReturn(Optional.of(workflow(3L, F)));
        when(workflows.findByIdAndFactoryId(9L, "F-OTHER")).thenReturn(Optional.of(workflow(9L, "F-OTHER")));
        when(revisions.findByFactoryIdAndWorkflowId(F, 1L)).thenReturn(List.of(revision(11L, 1L)));
        when(revisions.findByFactoryIdAndWorkflowId(F, 2L)).thenReturn(List.of(revision(22L, 2L)));
        when(revisions.findByFactoryIdAndWorkflowId(F, 3L)).thenReturn(List.of());

        recipeV1 = recipe("R-1", 1, 11L);
        recipeV2 = recipe("R-2", 2, 22L);
        BomRecipeItem aux = new BomRecipeItem();
        aux.setId(7L);
        aux.setMaterialName("QA-S2-06-辅料");
        recipeV2.getItems().add(aux);
        when(recipes.findByFactoryIdAndProductTypeIdAndWorkflowRevisionIdInOrderByVersionDesc(
                anyString(), anyString(), any())).thenAnswer(call -> {
                    Collection<Long> ids = call.getArgument(2);
                    return List.of(recipeV2, recipeV1).stream()
                            .filter(recipe -> ids.contains(recipe.getWorkflowRevisionId())).toList();
                });
    }

    @Test
    void aHistoricalVersionGetsTheRecipePinnedToItAndNeverTodaysRecipe() {
        BomRecipe v1 = service.findRecipePinnedToWorkflow(F, PRODUCT, 1L).orElseThrow();
        assertThat(v1.getId()).isEqualTo("R-1");
        assertThat(v1.getItems()).as("v1 当时没有辅料").isEmpty();

        BomRecipe v2 = service.findRecipePinnedToWorkflow(F, PRODUCT, 2L).orElseThrow();
        assertThat(v2.getItems()).extracting(BomRecipeItem::getMaterialName).containsExactly("QA-S2-06-辅料");
    }

    /** prod 实测: v1 的工艺行上挂着一份在 v2 发布前建的草稿 (带后来才加的辅料), 不能被当成 v1 当时的配方。 */
    @Test
    void aDraftRecipeCreatedOnTheOldRevisionBeforeThePublishIsNeverTheHistoricalRecipe() {
        BomRecipe staleDraft = recipe("R-D", 3, 11L);
        staleDraft.setStatus(BomRecipe.Status.DRAFT);
        BomRecipeItem later = new BomRecipeItem();
        later.setId(8L);
        later.setMaterialName("QA-S2-06-辅料");
        staleDraft.getItems().add(later);
        recipeV1.setStatus(BomRecipe.Status.ARCHIVED);
        // 重新打桩不能用 when(...): 它会拿 null 实参先调一次 setUp 里那个 answer
        doAnswer(call -> {
            Collection<Long> ids = call.getArgument(2);
            return List.of(staleDraft, recipeV2, recipeV1).stream()
                    .filter(recipe -> ids.contains(recipe.getWorkflowRevisionId())).toList();
        }).when(recipes).findByFactoryIdAndProductTypeIdAndWorkflowRevisionIdInOrderByVersionDesc(
                anyString(), anyString(), any());

        BomRecipe v1 = service.findRecipePinnedToWorkflow(F, PRODUCT, 1L).orElseThrow();
        assertThat(v1.getId()).as("v1 当时用过的是归档的 R-1, 不是后建的草稿").isEqualTo("R-1");
        assertThat(v1.getItems()).isEmpty();

        // 只有草稿 → 没有快照 (前端显示「历史版本不含配方快照」), 不是拿草稿顶替
        recipeV1.setStatus(BomRecipe.Status.DRAFT);
        assertThat(service.findRecipePinnedToWorkflow(F, PRODUCT, 1L)).isEmpty();
    }

    @Test
    void nothingPinnedToTheVersionMeansNoRecipeNotACurrentOneAndAnotherFactoryGetsNothing() {
        assertThat(service.findRecipePinnedToWorkflow(F, PRODUCT, 3L)).isEmpty();
        assertThat(service.findRecipePinnedToWorkflow(F, PRODUCT, 404L)).isEmpty();
        assertThat(service.findRecipePinnedToWorkflow(F, PRODUCT, 9L)).as("他厂的工艺行").isEmpty();
        assertThat(service.findRecipePinnedToWorkflow(F, PRODUCT, null)).isEmpty();
    }
}
