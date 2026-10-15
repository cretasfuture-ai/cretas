package com.cretas.aims.service.materialdraft;

import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.repository.material.MaterialTypeDraftRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.service.unit.UnitUsageScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Locks the assembly adapter to the ID/parentId taxonomy contract. */
class MaterialDraftTaxonomyAdapterContractTest {
    private final MaterialTypeDraftRepository drafts = mock(MaterialTypeDraftRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final MaterialCodeSegmentRepository segments = mock(MaterialCodeSegmentRepository.class);
    private final UnitContractService units = mock(UnitContractService.class);
    private MaterialTypeDraftCommand command;

    @BeforeEach
    void setUp() {
        command = new MaterialTypeDraftCommand(drafts, materials, segments, units);
        when(materials.existsByFactoryIdAndNormalizedName(any(), any())).thenReturn(false);
        when(units.normalize(any(), eq("kg")))
                .thenReturn(new UnitNormalizationResult("kg", "kg", mock(CanonicalUnit.class)));
        when(units.supportsUsage(any(), eq("kg"), eq(UnitUsageScope.INVENTORY_QUANTITY))).thenReturn(true);
        when(units.storageUnit(any(), eq("kg"))).thenReturn("kg");
    }

    @Test
    void resolvesNonDefaultNodeIdsAndValidatesParentChain() {
        MaterialCodeSegment l1 = node(205L, 1, null, "原料");
        MaterialCodeSegment l2 = node(9007L, 2, 205L, "肉类");
        MaterialCodeSegment l3 = node(41003L, 3, 9007L, "牛腱");
        when(segments.findByIdAndFactoryId(41003L, "F1")).thenReturn(Optional.of(l3));
        when(segments.findByIdAndFactoryId(9007L, "F1")).thenReturn(Optional.of(l2));
        when(segments.findByIdAndFactoryId(205L, "F1")).thenReturn(Optional.of(l1));
        var preview = command.preview(request("F1", "41003"));
        assertEquals("READY", preview.status());
        assertEquals("205", preview.desired().primaryCategoryCode());
        assertEquals("41003", preview.desired().classificationSegmentCode());
    }

    @Test
    void explicitBasicCategoryDoesNotInventAnOptionalTaxonomyId() {
        var preview = command.preview(request("F1", "category:原料"));
        assertEquals("READY", preview.status());
        assertEquals("", preview.desired().classificationSegmentCode());
        assertEquals("", preview.desired().primaryCategoryCode());
        assertEquals("原料", preview.desired().category());
        org.mockito.Mockito.verifyNoInteractions(segments);
    }

    @Test
    void rejectsCrossFactoryOrBrokenParentChain() {
        MaterialCodeSegment l3 = node(41003L, 3, 9007L, "牛腱");
        when(segments.findByIdAndFactoryId(41003L, "F1")).thenReturn(Optional.of(l3));
        when(segments.findByIdAndFactoryId(9007L, "F1")).thenReturn(Optional.empty());
        var preview = command.preview(request("F1", "41003"));
        assertTrue(preview.issues().stream().anyMatch(i -> i.code().equals("MATERIAL_DRAFT_CLASSIFICATION_CHAIN_INVALID")));
    }

    @Test
    void rejectsLegacyPlannedCodesExplicitly() {
        var preview = command.preview(request("F1", "123"));
        assertTrue(preview.issues().stream().anyMatch(i -> i.code().equals("MATERIAL_DRAFT_CLASSIFICATION_NOT_AVAILABLE")));
    }

    @Test
    void rejectsL1BecauseFormalMaterialClassificationRequiresL3() {
        when(segments.findByIdAndFactoryId(205L, "F1"))
                .thenReturn(Optional.of(node(205L, 1, null, "原料")));
        var preview = command.preview(request("F1", "205"));
        assertTrue(preview.issues().stream().anyMatch(i -> i.code().equals("MATERIAL_DRAFT_CLASSIFICATION_L1_UNSUPPORTED")));
    }

    private static MaterialCodeSegment node(long id, int level, Long parentId, String label) {
        return MaterialCodeSegment.builder().id(id).factoryId("F1").level((short) level)
                .parentId(parentId).segmentLabel(label).normalizedLabel(label).isActive(true).sortOrder(0).build();
    }

    private static MaterialTypeDraftModels.DraftRequest request(String factory, String classificationId) {
        return new MaterialTypeDraftModels.DraftRequest(factory, "bp", 1L, "a".repeat(64), "",
                new MaterialTypeDraftModels.RequestedMaterial("牛腱", classificationId, "kg",
                        MaterialTypeDraftModels.StorageMode.FROZEN, 30, false), "", "idem", 7L);
    }
}
