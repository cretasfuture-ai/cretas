package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomRecipeItemCostCorrection;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomProcessInjectionConfigRepository;
import com.cretas.aims.repository.bom.BomRecipeItemCostCorrectionRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.bom.NestedBomCostService;
import com.cretas.aims.service.unit.TestUnitContractFactory;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.uom.MaterialUomConverter;
import com.cretas.aims.service.validation.ProductConfigurationReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BomRecipeActivePackagingCostCorrectionTest {
    @Mock BomRecipeRepository recipeRepo;
    @Mock BomRecipeItemRepository itemRepo;
    @Mock BomRecipeItemCostCorrectionRepository correctionRepo;
    @Mock ProductTypeRepository productTypeRepo;
    @Mock RawMaterialTypeRepository materialTypeRepo;
    @Mock MaterialUomConverter materialUomConverter;
    @Mock UnitContractService unitContractService;
    @Mock ProductPackagingSpecRepository packagingSpecRepository;
    @Mock ProductConfigurationReadinessService readinessService;
    @Mock BomWorkflowRevisionService bomWorkflowRevisionService;
    @Mock BomItemSubstituteService substituteService;
    @Mock NestedBomCostService nestedBomCostService;
    @Mock BomSeasoningItemRepository seasoningItemRepo;
    @Mock BomProcessInjectionConfigRepository processInjectionConfigRepo;
    @Mock ProductProcessWorkflowActivationRepository workflowActivationRepo;
    @InjectMocks BomRecipeServiceImpl service;

    private BomRecipe recipe;
    private BomRecipeItem item;

    @BeforeEach
    void setUp() {
        recipe = new BomRecipe();
        recipe.setId("R1");
        recipe.setFactoryId("LIUSHANMEN");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setItems(new ArrayList<>());

        item = new BomRecipeItem();
        item.setId(680L);
        item.setFactoryId("LIUSHANMEN");
        item.setRecipeId("R1");
        item.setMaterialTypeId("M1");
        item.setMaterialCategory("PACKAGING");
        item.setStandardQuantity(new BigDecimal("0.0694"));
        item.setYieldRate(new BigDecimal("100"));
        item.setUnit("pcs");
        item.setQuantityToPriceFactor(BigDecimal.ONE);
        recipe.getItems().add(item);

        RawMaterialType material = new RawMaterialType();
        material.setId("M1");
        material.setFactoryId("LIUSHANMEN");
        material.setUnit("pcs");
        material.setUnitPrice(new BigDecimal("2.65"));

        when(itemRepo.findByIdForUpdate(680L)).thenReturn(Optional.of(item));
        when(recipeRepo.lockByIdAndFactoryId("R1", "LIUSHANMEN")).thenReturn(Optional.of(recipe));
        when(materialTypeRepo.findByIdAndFactoryId("M1", "LIUSHANMEN")).thenReturn(Optional.of(material));
        when(itemRepo.save(any(BomRecipeItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recipeRepo.save(any(BomRecipe.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("R1")).thenReturn(List.of(item));
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(inv ->
                TestUnitContractFactory.contract().normalize(inv.getArgument(0), inv.getArgument(1)));
        when(materialUomConverter.toComparableQuantity(anyString(), any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(inv -> MaterialUomConverter.ConversionResult.converted(inv.getArgument(1), inv.getArgument(3)));
    }

    @Test
    void correctsOnlyDerivedCostAndWritesOldNewAudit() {
        BomRecipeItem result = service.correctActivePackagingItemCost(
                "LIUSHANMEN", 680L, "系统验收参考估值", 88L, "factory_super_admin");

        assertThat(result.getUnitPrice()).isEqualByComparingTo("2.65");
        assertThat(result.getItemCost()).isEqualByComparingTo("0.1839");
        ArgumentCaptor<BomRecipeItemCostCorrection> trace =
                ArgumentCaptor.forClass(BomRecipeItemCostCorrection.class);
        verify(correctionRepo).save(trace.capture());
        assertThat(trace.getValue().getOldUnitPrice()).isNull();
        assertThat(trace.getValue().getNewUnitPrice()).isEqualByComparingTo("2.65");
        assertThat(trace.getValue().getOldItemCost()).isNull();
        assertThat(trace.getValue().getNewItemCost()).isEqualByComparingTo("0.1839");
        assertThat(trace.getValue().getCorrectedBy()).isEqualTo(88L);
    }

    @Test
    void nonPackagingRowFailsClosedWithoutAudit() {
        item.setMaterialCategory("RAW");

        assertThatThrownBy(() -> service.correctActivePackagingItemCost(
                "LIUSHANMEN", 680L, "不应成功", 88L, "factory_super_admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("包材");
        verify(correctionRepo, never()).save(any());
    }

    @Test
    void unauthorizedRoleFailsBeforeMutation() {
        assertThatThrownBy(() -> service.correctActivePackagingItemCost(
                "LIUSHANMEN", 680L, "不应成功", 88L, "production_operator"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("财务经理或工厂管理员");
        verify(itemRepo, never()).findByIdForUpdate(any());
    }
}
