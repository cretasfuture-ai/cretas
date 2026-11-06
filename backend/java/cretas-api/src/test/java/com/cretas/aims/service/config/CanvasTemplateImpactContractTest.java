package com.cretas.aims.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class CanvasTemplateImpactContractTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 4})
    void firstDraftUsesTheNextPhysicalVersionWithNoCurrentConfiguration(int latestArchivedVersion) {
        CanvasConfigPlanBindingService service = new CanvasConfigPlanBindingService(
                mock(CanvasConfigStateReader.class), new ObjectMapper());
        Map<String, Object> before = impact(false, false);
        before.put("configVersion", null);
        before.put("configStatus", null);
        before.put("moduleRows", List.of());
        before.put("latestPhysicalVersion", latestArchivedVersion);
        before.put("expectedDraftVersion", latestArchivedVersion + 1);
        Map<String, Object> after = impact(true, true);
        after.put("configVersion", latestArchivedVersion + 1);
        after.put("expectedDraftVersion", latestArchivedVersion + 1);
        maps(after.get("moduleRows")).forEach(row -> row.put("configVersion", latestArchivedVersion + 1));
        assertThatCode(() -> service.verifyReadback("F006", "canvas_apply_template", templateInternal(),
                new CanvasConfigStateReader.Snapshot("none", "before", state(before)),
                new CanvasConfigStateReader.Snapshot("draft", "after", state(after))))
                .doesNotThrowAnyException();
        after.put("configVersion", latestArchivedVersion + 2);
        assertThatThrownBy(() -> service.verifyReadback("F006", "canvas_apply_template", templateInternal(),
                new CanvasConfigStateReader.Snapshot("none", "before", state(before)),
                new CanvasConfigStateReader.Snapshot("wrong", "after", state(after))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound next draft");
    }

    @Test
    void publishedConfigurationCanHaveLaterArchivedVersionsWithoutLosingItsModuleSnapshot() {
        CanvasConfigPlanBindingService service = new CanvasConfigPlanBindingService(
                mock(CanvasConfigStateReader.class), new ObjectMapper());
        Map<String, Object> before = impact(false, false);
        before.put("latestPhysicalVersion", 5);
        before.put("expectedDraftVersion", 6);
        Map<String, Object> after = impact(true, true);
        after.put("configVersion", 6);
        after.put("expectedDraftVersion", 6);
        maps(after.get("moduleRows")).forEach(row -> row.put("configVersion", 6));
        assertThatCode(() -> service.verifyReadback("F006", "canvas_apply_template", templateInternal(),
                new CanvasConfigStateReader.Snapshot("config:1", "before", state(before)),
                new CanvasConfigStateReader.Snapshot("config:6", "after", state(after))))
                .doesNotThrowAnyException();
    }

    private Map<String, Object> templateInternal() {
        return Map.of(CanvasConfigPlanBindingService.PUBLIC_PARAMETERS, Map.of("templateCode", "PRODUCTION_BASE"),
                CanvasConfigPlanBindingService.BINDING, Map.of("targetStateDigest", "digest"));
    }

    @Test
    void templateReadbackRequiresEachModuleDefaultToolAndSeedEffect() {
        CanvasConfigPlanBindingService service = new CanvasConfigPlanBindingService(
                mock(CanvasConfigStateReader.class), new ObjectMapper());
        Map<String, Object> publicParams = Map.of("templateCode", "PRODUCTION_BASE");
        Map<String, Object> internal = Map.of(CanvasConfigPlanBindingService.PUBLIC_PARAMETERS, publicParams,
                CanvasConfigPlanBindingService.BINDING, Map.of("targetStateDigest", "digest"));

        CanvasConfigStateReader.Snapshot before = new CanvasConfigStateReader.Snapshot("config:1", "before",
                state(impact(false, false)));
        CanvasConfigStateReader.Snapshot after = new CanvasConfigStateReader.Snapshot("config:2", "after",
                state(impact(true, true)));

        assertThatCode(() -> service.verifyReadback("F006", "canvas_apply_template", internal, before, after))
                .doesNotThrowAnyException();

        Map<String, Object> badImpact = impact(true, true);
        List<Map<String, Object>> defaults = new ArrayList<>(maps(badImpact.get("factoryDefaults")));
        defaults.get(1).put("defaultValue", Map.of("value", "WRONG"));
        badImpact.put("factoryDefaults", defaults);
        Map<String, Object> badState = state(badImpact);
        CanvasConfigStateReader.Snapshot badAfter = new CanvasConfigStateReader.Snapshot("config:2", "bad", badState);
        assertThatThrownBy(() -> service.verifyReadback("F006", "canvas_apply_template", internal, before, badAfter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default");

        Map<String, Object> badImpactPlan = impact(true, true);
        maps(badImpactPlan.get("expectedDefaultRows")).get(0).put("rawValue", "81");
        assertThatThrownBy(() -> service.verifyReadback("F006", "canvas_apply_template", internal, before,
                new CanvasConfigStateReader.Snapshot("config:2", "bad-plan", state(badImpactPlan))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("impact changed");

        Map<String, Object> badTemplateState = state(impact(true, true));
        @SuppressWarnings("unchecked")
        Map<String, Object> changedTemplate = (Map<String, Object>) badTemplateState.get("template");
        changedTemplate.put("baseConfig", Map.of("changed", true));
        assertThatThrownBy(() -> service.verifyReadback("F006", "canvas_apply_template", internal, before,
                new CanvasConfigStateReader.Snapshot("config:2", "bad-template", badTemplateState)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template definition");

        Map<String, Object> badUnchangedModuleImpact = impact(true, true);
        List<Map<String, Object>> badModules = maps(badUnchangedModuleImpact.get("moduleRows"));
        badModules.stream().filter(row -> "quality".equals(row.get("moduleCode"))).findFirst()
                .orElseThrow().put("enabled", false);
        assertThatThrownBy(() -> service.verifyReadback("F006", "canvas_apply_template", internal, before,
                new CanvasConfigStateReader.Snapshot("config:2", "bad-module", state(badUnchangedModuleImpact))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unchanged module enabled state");

        Map<String, Object> badUnchangedModuleConfigImpact = impact(true, true);
        List<Map<String, Object>> badConfigModules = maps(badUnchangedModuleConfigImpact.get("moduleRows"));
        badConfigModules.stream().filter(row -> "quality".equals(row.get("moduleCode"))).findFirst()
                .orElseThrow().put("fieldConfig", Map.of("qc", false));
        assertThatThrownBy(() -> service.verifyReadback("F006", "canvas_apply_template", internal, before,
                new CanvasConfigStateReader.Snapshot("config:2", "bad-module-config",
                        state(badUnchangedModuleConfigImpact))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed outside the template toggle");
    }

    private Map<String, Object> state(Map<String, Object> impact) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("template", new LinkedHashMap<>(Map.of("templateCode", "production_base")));
        state.put("moduleConfigs", List.of());
        state.put("defaultValues", List.of());
        state.put("toolConfigs", List.of());
        state.put("templateImpact", impact);
        return state;
    }

    private Map<String, Object> impact(boolean after, boolean seedCreated) {
        Map<String, Object> moduleBefore = row("id", 1L, "factoryId", "F006", "moduleCode", "production", "enabled", false,
                "configVersion", 1, "fieldConfig", Map.of("old", true), "workflowConfig", Map.of(),
                "validationConfig", Map.of(), "permissionConfig", Map.of(),
                "roleModuleOverride", Map.of("factory_super_admin", Map.of("production", "rw")),
                "layoutConfig", Map.of("density", "comfortable"), "customLabels", Map.of(),
                "computedFields", Map.of("displayName", "Production"), "renderingMode", "CANVAS");
        Map<String, Object> moduleAfter = new LinkedHashMap<>(moduleBefore);
        moduleAfter.put("enabled", true);
        if (after) {
            moduleAfter.put("id", 9L);
            moduleAfter.put("configVersion", 2);
        }
        Map<String, Object> unchangedBefore = row("id", 7L, "factoryId", "F006", "moduleCode", "quality", "enabled", true,
                "configVersion", 1, "fieldConfig", Map.of("qc", true), "workflowConfig", Map.of(),
                "validationConfig", Map.of(), "permissionConfig", Map.of(),
                "roleModuleOverride", Map.of("factory_super_admin", Map.of("quality", "r")),
                "layoutConfig", Map.of("density", "compact"), "customLabels", Map.of(),
                "computedFields", Map.of("displayName", "Quality"), "renderingMode", "CANVAS");
        Map<String, Object> unchangedAfter = new LinkedHashMap<>(unchangedBefore);
        if (after) {
            unchangedAfter.put("id", 10L);
            unchangedAfter.put("configVersion", 2);
        }
        Map<String, Object> disabled = row("id", 2L, "factoryId", "F006", "moduleCode", "sales", "enabled", false,
                "configVersion", 1, "fieldConfig", Map.of(), "workflowConfig", Map.of(), "validationConfig", Map.of(),
                "permissionConfig", Map.of(), "roleModuleOverride", Map.of("factory_super_admin", Map.of("sales", "-")),
                "layoutConfig", Map.of(), "customLabels", Map.of(), "computedFields", Map.of(), "renderingMode", "LEGACY");
        Map<String, Object> existingDefault = row("id", 3L, "factoryId", "F006", "moduleCode", "production",
                "fieldCode", "old", "defaultValue", Map.of("value", "OLD"), "condition", null);
        Map<String, Object> newDefault = row("id", 4L, "factoryId", "F006", "moduleCode", "production",
                "fieldCode", "temperature", "defaultValue", Map.of("value", "80"), "condition", null);
        Map<String, Object> toolBefore = row("id", 5L, "factoryId", "F006", "toolName", "canvas_update_field",
                "enabled", true, "paramOverrides", Map.of(), "riskOverride", null);
        Map<String, Object> toolAfter = new LinkedHashMap<>(toolBefore);
        toolAfter.put("enabled", false);
        Map<String, Object> expectedSeed = row("id", null, "factoryId", "F006", "moduleCode", "production",
                "fieldCode", "temperature", "fieldType", "NUMBER", "label", "温度", "config", Map.of("unit", "℃"),
                "visibleWhen", null, "computedWhen", null, "sortOrder", 1, "status", "PENDING_DDL", "columnName", "cf_temperature");
        Map<String, Object> seed = row("moduleCode", "production", "fieldCode", "temperature", "expected", expectedSeed,
                "current", seedCreated ? row("id", "seed-1", "factoryId", "F006", "moduleCode", "production",
                        "fieldCode", "temperature", "fieldType", "NUMBER", "label", "温度", "config", Map.of("unit", "℃"),
                        "visibleWhen", null, "computedWhen", null, "sortOrder", 1, "status", "PENDING_DDL", "columnName", "cf_temperature") : null);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("canonicalTemplateCode", "production_base");
        impact.put("enabledModules", List.of("production"));
        impact.put("disabledModules", List.of("sales"));
        impact.put("moduleDesiredStates", Map.of("production", true, "sales", false));
        impact.put("moduleSchemas", List.of(row("moduleCode", "production", "exists", true), row("moduleCode", "sales", "exists", true)));
        impact.put("configVersion", after ? 2 : 1);
        impact.put("configStatus", after ? "DRAFT" : "PUBLISHED");
        impact.put("expectedDraftVersion", 2);
        impact.put("moduleRows", after ? List.of(moduleAfter, unchangedAfter, disabled)
                : List.of(moduleBefore, unchangedBefore));
        impact.put("factoryDefaults", after ? List.of(existingDefault, newDefault) : List.of(existingDefault));
        impact.put("expectedDefaultRows", List.of(row("moduleCode", "production", "fieldCode", "temperature",
                "rawValue", "80", "storedValue", Map.of("value", "80"))));
        impact.put("disabledTools", List.of("canvas_update_field"));
        impact.put("toolRows", after ? List.of(toolAfter) : List.of(toolBefore));
        impact.put("seedDynamicFields", List.of(seed));
        return impact;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
