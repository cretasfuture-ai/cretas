package com.cretas.aims.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanvasConfigPlanBindingContractTest {
    @Mock private CanvasConfigStateReader stateReader;

    @Test
    void issuedBindingKeepsPrivateStateOutOfPublicParametersAndPinsTenantToolAndDigest() {
        CanvasConfigStateReader.Snapshot state = new CanvasConfigStateReader.Snapshot(
                "config:7", "state-7", Map.of("module", Map.of("enabled", false)));
        when(stateReader.read("F006", "canvas_toggle_module", Map.of("moduleCode", "purchase", "enabled", true)))
                .thenReturn(state);
        CanvasConfigPlanBindingService service = new CanvasConfigPlanBindingService(stateReader, new ObjectMapper());

        Map<String, Object> issued = service.issue("F006", "canvas_toggle_module",
                Map.of("moduleCode", "purchase", "enabled", true));

        assertThat(issued).containsKeys(CanvasConfigPlanBindingService.PUBLIC_PARAMETERS,
                CanvasConfigPlanBindingService.BINDING);
        assertThat(issued).containsEntry("moduleCode", "purchase")
                .containsEntry("enabled", true);
        assertThat(service.publicParameters(issued))
                .containsEntry("moduleCode", "purchase")
                .containsEntry("enabled", true);
        assertThat(service.binding(issued))
                .containsEntry("factoryId", "F006")
                .containsEntry("tool", "canvas_toggle_module")
                .containsEntry("revision", "config:7")
                .containsEntry("targetStateDigest", "state-7");
    }

    @Test
    void forgedPublicParametersAndStaleStateAreRejectedBeforeWriter() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("moduleCode", "purchase");
        original.put("enabled", true);
        when(stateReader.read("F006", "canvas_toggle_module", original))
                .thenReturn(new CanvasConfigStateReader.Snapshot("config:7", "state-7", Map.of()),
                        new CanvasConfigStateReader.Snapshot("config:7", "state-8", Map.of()));
        CanvasConfigPlanBindingService service = new CanvasConfigPlanBindingService(stateReader, new ObjectMapper());
        Map<String, Object> issued = service.issue("F006", "canvas_toggle_module", original);

        assertThat(service.publicParametersMatch(issued, original)).isTrue();
        assertThat(service.publicParametersMatch(issued, Map.of("moduleCode", "purchase", "enabled", false)))
                .isFalse();
        assertThatThrownBy(() -> service.verifyBeforeWrite("F006", "canvas_toggle_module", issued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void onlySixConfigurationWritersAreBindable() {
        CanvasConfigPlanBindingService service = new CanvasConfigPlanBindingService(stateReader, new ObjectMapper());
        assertThat(service.supports("canvas_toggle_module")).isTrue();
        assertThat(service.supports("canvas_update_field")).isTrue();
        assertThat(service.supports("canvas_apply_template")).isTrue();
        assertThat(service.supports("canvas_toggle_tool")).isTrue();
        assertThat(service.supports("canvas_toggle_skill")).isTrue();
        assertThat(service.supports("canvas_update_trigger_chain")).isTrue();
        assertThat(service.supports("canvas_work_process_catalog")).isFalse();
        assertThat(service.supports("restaurant_delete_everything")).isFalse();
    }
}
