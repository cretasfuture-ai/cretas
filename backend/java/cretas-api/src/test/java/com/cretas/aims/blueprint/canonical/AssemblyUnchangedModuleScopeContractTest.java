package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.PlannedChange;
import com.cretas.aims.entity.config.FactoryCapabilityDefinition;
import com.cretas.aims.repository.config.FactoryCapabilityDefinitionRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssemblyUnchangedModuleScopeContractTest {
    private final FactoryBlueprintCapabilityCatalog catalog = new FactoryBlueprintCapabilityCatalog(
            mock(FactoryCapabilityDefinitionRepository.class), mock(ModuleSchemaRepository.class));

    private FactoryCapabilityDefinition definition(String id, String strategy, List<String> dependencies, String keyword) {
        return FactoryCapabilityDefinition.builder().capabilityId(id).label(id).description(id)
                .displayOrder(1).derivationStrategy(strategy).dependsOn(dependencies).matchKeywords(List.of(keyword)).build();
    }

    private PlannedChange change(String group, String key) {
        var change = new PlannedChange(); change.setGroup(group); change.setKey(key); return change;
    }

    @Test void unchangedModuleScopeDoesNotRequireSyntheticConfigurationWrites() {
        var snapshot = new FactoryBlueprintCapabilityCatalog.CatalogSnapshot("v2", "digest", List.of(
                definition("factory", "FACTORY", List.of(), "身份"),
                definition("modules", "PLANNED_CHANGE", List.of("factory"), "模块"),
                definition("forms", "PLANNED_CHANGE", List.of("modules"), "表单")), LocalDateTime.now());
        var payload = new ApplyAIInitRequest();
        var suggested = new ApplyAIInitRequest.SuggestedData();
        suggested.setModuleConfigs(List.of()); suggested.setModules(Map.of()); payload.setSuggestedData(suggested);
        var changes = List.of(change("表单模板", "MATERIAL_BATCH"),
                change("表单模板", "PROCESSING_BATCH"), change("表单模板", "SHIPMENT"));
        var views = catalog.derive(snapshot, changes, payload);
        assertThat(views.get(1).getStatus()).isEqualTo("NOT_STARTED");
        assertThat(views.get(1).getPlannedCount()).isZero();
        assertThat(views.get(2).getStatus()).isEqualTo("READY");
        assertThat(views.get(2).getPlannedCount()).isEqualTo(3);
        assertThat(views.get(2).getDependsOn()).containsExactly("modules");
        assertThat(suggested.getModuleConfigs()).isEmpty(); assertThat(suggested.getModules()).isEmpty();
    }

    @Test void actualModuleChangesStillRequireTheirDependencies() {
        var snapshot = new FactoryBlueprintCapabilityCatalog.CatalogSnapshot("v2", "digest", List.of(
                definition("modules", "PLANNED_CHANGE", List.of("factory"), "模块"),
                definition("forms", "PLANNED_CHANGE", List.of("modules"), "表单")), LocalDateTime.now());
        var views = catalog.derive(snapshot, List.of(
                change("模块开关", "legacy-module"), change("表单模板", "MATERIAL_BATCH")), new ApplyAIInitRequest());
        assertThat(views.get(0).getStatus()).isEqualTo("BLOCKED");
        assertThat(views.get(1).getStatus()).isEqualTo("BLOCKED");
        assertThat(views.get(1).getMissingDependencies()).containsExactly("modules");
    }
}
