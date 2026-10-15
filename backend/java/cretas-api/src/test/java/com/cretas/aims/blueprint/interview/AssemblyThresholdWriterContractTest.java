package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssemblyThresholdWriterContractTest {
    private final FactoryThresholdRepository rows = mock(FactoryThresholdRepository.class);
    private final ThresholdResolverService resolver = mock(ThresholdResolverService.class);
    private final AssemblyThresholdWriter writer = new AssemblyThresholdWriter(rows, resolver);
    private final String key = "inventory.turnover.red";

    @Test void inheritsAuthorityTypeAndNeverChangesGlobalDefault() {
        FactoryThreshold global = FactoryThreshold.builder().factoryId("*").thresholdKey(key)
                .category(ThresholdCategory.INVENTORY).valueType(ThresholdValueType.INTEGER)
                .thresholdValue("6").defaultValue("6").build();
        when(rows.findByFactoryIdAndThresholdKey("*", key)).thenReturn(Optional.of(global));
        writer.upsert("NEW-FACTORY", key, "62");
        var saved = ArgumentCaptor.forClass(FactoryThreshold.class);
        verify(rows).saveAndFlush(saved.capture());
        assertEquals("NEW-FACTORY", saved.getValue().getFactoryId());
        assertEquals("62", saved.getValue().getThresholdValue());
        assertEquals("6", saved.getValue().getDefaultValue());
        assertEquals(ThresholdValueType.INTEGER, saved.getValue().getValueType());
        assertEquals("6", global.getThresholdValue());
        verify(resolver).invalidate("NEW-FACTORY", key);
    }

    @Test void invalidNumericValueFailsDuringReadonlyPreviewBeforeAnySave() {
        when(rows.findByFactoryIdAndThresholdKey("*", key)).thenReturn(Optional.of(FactoryThreshold.builder()
                .valueType(ThresholdValueType.INTEGER).build()));
        assertThrows(BusinessException.class, () -> writer.validate("NEW-FACTORY", key, "6.2"));
        assertThrows(BusinessException.class, () -> writer.upsert("NEW-FACTORY", key, "6.2"));
        verify(rows, never()).saveAndFlush(any());
        verifyNoInteractions(resolver);
    }

    @Test void globalTargetAndUnknownKeyAreRefusedBeforeRepositoryAccess() {
        assertThrows(BusinessException.class, () -> writer.upsert("*", key, "62"));
        assertThrows(BusinessException.class, () -> writer.upsert("NEW-FACTORY", "invented.unknown", "62"));
        verifyNoInteractions(rows, resolver);
    }

    @Test void knownNumericKeyWithoutAuthorityCannotBeReinterpretedAsString() {
        assertThrows(BusinessException.class, () -> writer.validate("NEW-FACTORY", "production.oee.red", "abc"));
        assertThrows(BusinessException.class, () -> writer.upsert("NEW-FACTORY", "production.oee.red", "62"));
        verify(rows, never()).saveAndFlush(any());
        verifyNoInteractions(resolver);
    }
}
