package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.InputType;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.RequiredInput;
import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Source;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.material.MaterialTypeDraft;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 供应商准入：物料引用 / 供应商做成下拉，不让人手敲标识。 */
class SupplierReceivingMaterialDropdownTest {

    private final MaterialTypeDraftRepository drafts = mock(MaterialTypeDraftRepository.class);
    private final RawMaterialTypeRepository live = mock(RawMaterialTypeRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final SupplierReceivingDraftProposer proposer = new SupplierReceivingDraftProposer(drafts, live, suppliers);

    private RequiredInput field(String path) {
        var blueprint = BlueprintResponse.builder().id("bp").factoryId("F1").version(3).rowVersion(1L).lifecycleState("VALIDATED").build();
        var proposed = proposer.propose(new Source("F1", blueprint, null, "d".repeat(64)));
        var policies = proposed.items().get(0).requiredInputs().get(0);
        return policies.itemFields().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void materialRefIsADropdownOfThisBlueprintsDraftsAndLiveMaterials() {
        var draft = new MaterialTypeDraft();
        draft.setId("draft-uuid-1"); draft.setDesiredName("冷冻牛肉"); draft.setNaturalKey("RM_FROZEN_BEEF");
        when(drafts.findByFactoryIdAndBlueprintIdAndBlueprintVersionAndStatusOrderByDesiredNameAsc("F1", "bp", 3L, "DRAFT"))
                .thenReturn(List.of(draft));
        var material = new RawMaterialType();
        material.setId("rmt-1"); material.setName("面粉"); material.setCode("RM001");
        when(live.findByFactoryIdAndIsActive("F1", true)).thenReturn(List.of(material));

        var input = field("materialRefId");
        assertThat(input.inputType()).isEqualTo(InputType.ENUM);
        assertThat(input.options()).extracting(o -> o.value()).containsExactly("draft-uuid-1", "rmt-1");
        assertThat(input.options().get(0).label()).contains("草稿").contains("冷冻牛肉").contains("RM_FROZEN_BEEF");
        assertThat(input.options().get(1).label()).contains("正式").contains("面粉");
        assertThat(input.constraint()).contains("MATERIAL_DRAFT").contains("供货关系");
    }

    @Test
    void supplierIsADropdownOfActiveSuppliers() {
        var supplier = new Supplier();
        supplier.setId("sup-1"); supplier.setName("华北牧业");
        when(suppliers.findByFactoryIdAndIsActive("F1", true)).thenReturn(List.of(supplier));
        var input = field("supplierId");
        assertThat(input.inputType()).isEqualTo(InputType.ENUM);
        assertThat(input.options()).extracting(o -> o.value()).containsExactly("sup-1");
        assertThat(input.options().get(0).label()).isEqualTo("华北牧业");
    }

    @Test
    void withNothingToPickTheFieldFallsBackToTextAndSaysWhy() {
        assertThat(field("materialRefId").inputType()).isEqualTo(InputType.TEXT);
        assertThat(field("materialRefId").constraint()).contains("还没有物料草稿或正式物料");
        assertThat(field("supplierId").inputType()).isEqualTo(InputType.TEXT);
        assertThat(field("supplierId").constraint()).contains("还没有启用的供应商");
    }
}
