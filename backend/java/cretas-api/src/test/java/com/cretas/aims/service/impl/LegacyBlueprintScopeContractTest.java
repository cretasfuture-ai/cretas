package com.cretas.aims.service.impl;

import com.cretas.aims.dto.blueprint.*;
import com.cretas.aims.entity.config.FactoryTypeBlueprint;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyBlueprintScopeContractTest {
    @Test void canonicalIdsAreRejectedBeforeHistoricalTemplateReadsOrWrites() {
        var rows = mock(FactoryTypeBlueprintRepository.class);
        var applications = mock(BlueprintApplicationRepository.class);
        var history = mock(BlueprintVersionHistoryRepository.class);
        var bindings = mock(FactoryBlueprintBindingRepository.class);
        var legacy = new FactoryBlueprintServiceImpl(rows, applications, mock(FormTemplateRepository.class),
                mock(ProductTypeRepository.class), mock(DroolsRuleRepository.class), new ObjectMapper());
        var versions = new BlueprintVersionServiceImpl(history, bindings, rows, new ObjectMapper());
        when(rows.findByIdAndDeletedAtIsNull("canonical")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> legacy.getBlueprintById("canonical")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> legacy.updateBlueprint("canonical", new CreateBlueprintRequest())).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> legacy.deleteBlueprint("canonical")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> versions.getVersionHistory("canonical")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> versions.getPublishedVersions("canonical")).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> versions.compareVersions("canonical", 1, 2)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> versions.publishVersion("canonical", new PublishVersionRequest())).isInstanceOf(EntityNotFoundException.class);
        verify(rows, never()).save(any()); verifyNoInteractions(applications, history, bindings);
    }

    @Test void historicalTemplateStillUsesExistingUpdateBehavior() {
        var rows = mock(FactoryTypeBlueprintRepository.class);
        var legacy = new FactoryBlueprintServiceImpl(rows, mock(BlueprintApplicationRepository.class), mock(FormTemplateRepository.class),
                mock(ProductTypeRepository.class), mock(DroolsRuleRepository.class), new ObjectMapper());
        var template = new FactoryTypeBlueprint(); template.setId("template"); template.setName("old"); template.setVersion(2);
        when(rows.findByIdAndDeletedAtIsNull("template")).thenReturn(Optional.of(template));
        when(rows.save(template)).thenReturn(template);
        var request = new CreateBlueprintRequest(); request.setName("updated");
        assertThat(legacy.updateBlueprint("template", request).getName()).isEqualTo("updated");
        assertThat(template.getVersion()).isEqualTo(3);
        assertThat(template.getFactoryId()).isNull(); verify(rows).save(template);
    }
}
