package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.config.FactoryCapabilityDefinition;
import com.cretas.aims.repository.config.FactoryCapabilityDefinitionRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssemblyRawRecipeIntentContractTest {
    @Test
    void explicitRawMaterialRecipeOpensBomWithoutInventingAuxiliaryIngredients() {
        var repository = mock(FactoryCapabilityDefinitionRepository.class);
        var modules = mock(ModuleSchemaRepository.class);
        var catalog = new FactoryBlueprintCapabilityCatalog(repository, modules);
        var definition = FactoryCapabilityDefinition.builder().capabilityId("bom").label("配方").displayOrder(1)
                .derivationStrategy("BOM").dependsOn(List.of()).build();
        var snapshot = new FactoryBlueprintCapabilityCatalog.CatalogSnapshot("test", "digest", List.of(definition), LocalDateTime.now());
        var payload = new ApplyAIInitRequest();
        var data = new ApplyAIInitRequest.SuggestedData();
        payload.setSuggestedData(data);
        assertFalse(catalog.derive(snapshot, List.of(), payload).getFirst().isIncluded());

        var conversion = new ApplyAIInitRequest.ConversionItem();
        conversion.setFromCode("FLOUR"); conversion.setToCode("BREAD"); conversion.setRate(BigDecimal.TEN);
        data.setConversionRates(List.of(conversion));
        var bom = catalog.derive(snapshot, List.of(), payload).getFirst();
        assertTrue(bom.isIncluded()); assertEquals("READY", bom.getStatus());
        assertEquals(1, bom.getPlannedCount());
        assertNull(data.getWorkProcesses()); // no fabricated auxiliary or packaging recipe
        assertEquals(BigDecimal.TEN, conversion.getRate()); // intent does not rewrite quantities

        conversion.setToCode(" ");
        assertFalse(catalog.derive(snapshot, List.of(), payload).getFirst().isIncluded());
        verifyNoInteractions(repository, modules);
    }
}
