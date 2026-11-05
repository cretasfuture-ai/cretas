package com.cretas.aims.service.material;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class MaterialMergeReferenceWriteGuardContractTest {

    private final RawMaterialTypeRepository repository = mock(RawMaterialTypeRepository.class);
    private final MaterialReferenceWriteGuard guard = new MaterialReferenceWriteGuard(repository);

    @Test
    void locksDistinctMaterialIdsInStableOrderAndReturnsActiveRows() {
        RawMaterialType a = material("A");
        RawMaterialType b = material("B");
        when(repository.lockByFactoryIdAndIdIn(eq("F"), any())).thenReturn(List.of(b, a));
        RawMaterialTypeRepository.WriteStateProjection bState = state("B", true, null);
        RawMaterialTypeRepository.WriteStateProjection aState = state("A", true, null);
        when(repository.findFreshWriteStateByFactoryIdAndIdIn(eq("F"), any()))
                .thenReturn(List.of(bState, aState));

        assertThat(guard.lockActive("F", List.of("B", "A", "B")))
                .extracting(RawMaterialType::getId).containsExactly("B", "A");
        ArgumentCaptor<java.util.Collection<String>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).lockByFactoryIdAndIdIn(eq("F"), ids.capture());
        assertThat(ids.getValue()).containsExactly("A", "B");
    }

    @Test
    void rejectsMissingOrForeignRows() {
        when(repository.lockByFactoryIdAndIdIn(eq("F"), any())).thenReturn(List.of(material("A")));

        assertThatThrownBy(() -> guard.lockActive("F", List.of("A", "B")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(409));
    }

    @Test
    void rejectsInactiveAndMergedRows() {
        RawMaterialType inactive = material("A");
        inactive.setIsActive(false);
        when(repository.lockByFactoryIdAndIdIn(eq("F"), any())).thenReturn(List.of(inactive));
        RawMaterialTypeRepository.WriteStateProjection inactiveState = state("A", false, null);
        when(repository.findFreshWriteStateByFactoryIdAndIdIn(eq("F"), any()))
                .thenReturn(List.of(inactiveState));
        assertThatThrownBy(() -> guard.lockActive("F", List.of("A")))
                .isInstanceOf(BusinessException.class);

        RawMaterialType merged = material("B");
        merged.setMergedIntoId("MASTER");
        when(repository.lockByFactoryIdAndIdIn(eq("F"), any())).thenReturn(List.of(merged));
        RawMaterialTypeRepository.WriteStateProjection mergedState = state("B", true, "MASTER");
        when(repository.findFreshWriteStateByFactoryIdAndIdIn(eq("F"), any()))
                .thenReturn(List.of(mergedState));
        assertThatThrownBy(() -> guard.lockActive("F", List.of("B")))
                .isInstanceOf(BusinessException.class);
    }

    private RawMaterialType material(String id) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId("F");
        material.setName(id);
        material.setIsActive(true);
        return material;
    }

    private RawMaterialTypeRepository.WriteStateProjection state(
            String id, boolean active, String mergedIntoId) {
        RawMaterialTypeRepository.WriteStateProjection state = mock(RawMaterialTypeRepository.WriteStateProjection.class);
        when(state.getId()).thenReturn(id);
        when(state.getIsActive()).thenReturn(active);
        when(state.getMergedIntoId()).thenReturn(mergedIntoId);
        return state;
    }
}
