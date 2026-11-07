package com.cretas.aims.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates and validates the private server-side part of a Canvas plan token. */
@Service
public class CanvasConfigPlanBindingService {
    public static final String PUBLIC_PARAMETERS = "__canvas_public_parameters";
    public static final String BINDING = "__canvas_binding";
    private static final Set<String> TOOLS = Set.of(
            "canvas_toggle_module", "canvas_update_field", "canvas_apply_template",
            "canvas_toggle_tool", "canvas_toggle_skill", "canvas_update_trigger_chain");

    private final CanvasConfigStateReader stateReader;
    private final ObjectMapper mapper;

    public CanvasConfigPlanBindingService(CanvasConfigStateReader stateReader, ObjectMapper mapper) {
        this.stateReader = stateReader;
        this.mapper = mapper;
    }

    public boolean supports(String toolName) {
        return isConfigurationTool(toolName);
    }

    /** Bean-free allowlist used by controller fail-closed wiring checks. */
    public static boolean isConfigurationTool(String toolName) {
        return toolName != null && TOOLS.contains(toolName);
    }

    public Map<String, Object> issue(String factoryId, String toolName, Map<String, Object> publicParams) {
        requireTool(toolName);
        Map<String, Object> publicCopy = deepCopy(publicParams);
        CanvasConfigStateReader.Snapshot snapshot = stateReader.read(factoryId, toolName, publicCopy);
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("factoryId", factoryId);
        binding.put("tool", toolName);
        binding.put("revision", snapshot.revision());
        binding.put("targetStateDigest", snapshot.fingerprint());
        binding.put("targetState", deepCopy(snapshot.state()));
        Map<String, Object> internal = new LinkedHashMap<>();
        // Keep the validated public arguments at the top level because the normal
        // AbstractBusinessTool parameter validator runs before doExecute(). The
        // nested copy is retained as the explicit public/private boundary used by
        // the controller and by older token fixtures.
        internal.putAll(publicCopy);
        internal.put(PUBLIC_PARAMETERS, publicCopy);
        internal.put(BINDING, binding);
        return deepCopy(internal);
    }

    public Map<String, Object> publicParameters(Map<String, Object> internal) {
        Map<String, Object> publicParams = map(internal == null ? null : internal.get(PUBLIC_PARAMETERS));
        Map<String, Object> binding = map(internal == null ? null : internal.get(BINDING));
        if (publicParams == null || binding == null || !binding.containsKey("targetStateDigest")) {
            throw new IllegalArgumentException("Canvas server binding missing");
        }
        return deepCopy(publicParams);
    }

    public boolean publicParametersMatch(Map<String, Object> internal, Map<String, Object> submitted) {
        return Objects.equals(publicParameters(internal), deepCopy(submitted));
    }

    public Map<String, Object> binding(Map<String, Object> internal) {
        Map<String, Object> binding = map(internal == null ? null : internal.get(BINDING));
        if (binding == null) throw new IllegalArgumentException("Canvas server binding missing");
        return deepCopy(binding);
    }

    public void verifyBeforeWrite(String factoryId, String toolName, Map<String, Object> internal) {
        requireTool(toolName);
        Map<String, Object> binding = binding(internal);
        if (!Objects.equals(factoryId, binding.get("factoryId"))
                || !Objects.equals(toolName, binding.get("tool"))) {
            throw new IllegalArgumentException("Canvas binding tenant or tool mismatch");
        }
        CanvasConfigStateReader.Snapshot current = stateReader.read(factoryId, toolName, publicParameters(internal));
        if (!Objects.equals(binding.get("targetStateDigest"), current.fingerprint())) {
            throw new StalePlanException();
        }
    }

    public void verifyReadback(String factoryId, String toolName, Map<String, Object> internal,
                               CanvasConfigStateReader.Snapshot before,
                               CanvasConfigStateReader.Snapshot after) {
        Map<String, Object> params = publicParameters(internal);
        if (after == null || after.state().isEmpty()) {
            throw new IllegalStateException("Canvas configuration readback unavailable");
        }
        if ("canvas_apply_template".equals(toolName)) {
            Map<String, Object> template = map(after.state().get("template"));
            if (template == null || !Objects.equals(String.valueOf(params.get("templateCode")).toLowerCase(java.util.Locale.ROOT), template.get("templateCode"))
                    || !after.state().containsKey("moduleConfigs")
                    || !after.state().containsKey("defaultValues")
                    || !after.state().containsKey("toolConfigs")
                    || !before.state().containsKey("templateImpact")
                    || !after.state().containsKey("templateImpact")) {
                throw new IllegalStateException("Canvas template readback did not include its complete configuration impact");
            }
            verifyTemplateImpact(before, after);
            return;
        }
        if ("canvas_toggle_module".equals(toolName) || "canvas_toggle_tool".equals(toolName)
                || "canvas_toggle_skill".equals(toolName)) {
            Boolean expected = booleanValue(params.get("enabled"));
            String key = "canvas_toggle_module".equals(toolName) ? "module"
                    : "canvas_toggle_tool".equals(toolName) ? "tool" : "skill";
            Map<String, Object> target = map(after.state().get(key));
            if (target == null || !Objects.equals(expected, booleanValue(target.get("enabled")))) {
                throw new IllegalStateException("Canvas configuration readback did not match requested state");
            }
            return;
        }
        if ("canvas_update_trigger_chain".equals(toolName)) {
            Map<String, Object> target = map(after.state().get("trigger"));
            if (target == null) throw new IllegalStateException("Canvas trigger readback unavailable");
            if (params.get("stepOrder") != null) {
                if (!Objects.equals(params.get("stepEnabled"), findStep(target.get("steps"), params.get("stepOrder")))) {
                    throw new IllegalStateException("Canvas trigger step readback did not match");
                }
            } else if (!Objects.equals(params.get("enabled"), target.get("enabled"))) {
                throw new IllegalStateException("Canvas trigger readback did not match");
            }
            return;
        }
        Map<String, Object> target = map(after.state().get("module"));
        Map<String, Object> fields = target == null ? null : map(target.get("fieldConfig"));
        Map<String, Object> allFields = fields == null ? null : map(fields.get("fields"));
        Map<String, Object> field = allFields == null ? null : map(allFields.get(params.get("fieldCode")));
        if (field == null || !Objects.equals(params.get("value"), field.get(params.get("property")))) {
            throw new IllegalStateException("Canvas field readback did not match requested state");
        }
    }

    private void verifyTemplateImpact(CanvasConfigStateReader.Snapshot before,
                                      CanvasConfigStateReader.Snapshot after) {
        Map<String, Object> beforeTemplate = mapOrNull(before.state().get("template"));
        Map<String, Object> afterTemplate = mapOrNull(after.state().get("template"));
        if (beforeTemplate == null || afterTemplate == null
                || !sameWithout(beforeTemplate, afterTemplate, "usageCount")) {
            throw new IllegalStateException("Canvas template definition changed after the plan was issued");
        }
        Map<String, Object> beforeImpact = map(before.state().get("templateImpact"));
        Map<String, Object> afterImpact = map(after.state().get("templateImpact"));
        if (!Objects.equals(beforeImpact.get("canonicalTemplateCode"), afterImpact.get("canonicalTemplateCode"))) {
            throw new IllegalStateException("Canvas template canonical identity changed");
        }
        assertTemplatePlanStable(beforeImpact, afterImpact);

        List<Map<String, Object>> beforeModules = maps(beforeImpact.get("moduleRows"));
        List<Map<String, Object>> afterModules = maps(afterImpact.get("moduleRows"));
        assertModuleStates(afterImpact, afterModules);
        assertModuleConfigurationCloned(beforeImpact, afterImpact, beforeModules, afterModules);

        List<Map<String, Object>> beforeDefaults = maps(beforeImpact.get("factoryDefaults"));
        List<Map<String, Object>> afterDefaults = maps(afterImpact.get("factoryDefaults"));
        assertPreserved(beforeDefaults, afterDefaults, "factory default");
        List<Map<String, Object>> expectedDefaults = maps(beforeImpact.get("expectedDefaultRows"));
        List<Map<String, Object>> addedDefaults = new java.util.ArrayList<>(afterDefaults);
        beforeDefaults.forEach(addedDefaults::remove);
        if (addedDefaults.size() != expectedDefaults.size()) {
            throw new IllegalStateException("Canvas template default impact count did not increase exactly");
        }
        for (Map<String, Object> expected : expectedDefaults) {
            Map<String, Object> row = addedDefaults.stream()
                    .filter(candidate -> Objects.equals(expected.get("moduleCode"), candidate.get("moduleCode"))
                            && Objects.equals(expected.get("fieldCode"), candidate.get("fieldCode"))
                            && Objects.equals(expected.get("storedValue"), candidate.get("defaultValue")))
                    .findFirst().orElse(null);
            if (row == null || !Objects.equals(row.get("factoryId"), afterFactoryId(afterDefaults))) {
                throw new IllegalStateException("Canvas template default value was not read back exactly");
            }
            addedDefaults.remove(row);
        }
        if (!addedDefaults.isEmpty()) {
            throw new IllegalStateException("Canvas template created an unexpected default value");
        }

        List<Map<String, Object>> beforeTools = maps(beforeImpact.get("toolRows"));
        List<Map<String, Object>> afterTools = maps(afterImpact.get("toolRows"));
        for (Map<String, Object> previous : beforeTools) {
            Map<String, Object> current = by(afterTools, "toolName", previous.get("toolName"));
            if (current == null || !Boolean.FALSE.equals(current.get("enabled"))
                    || !sameWithout(previous, current, "enabled")) {
                throw new IllegalStateException("Existing matched tool configuration was not safely disabled");
            }
        }
        for (String tool : strings(afterImpact.get("disabledTools"))) {
            Map<String, Object> row = by(afterTools, "toolName", tool);
            if (row == null || !Boolean.FALSE.equals(row.get("enabled"))) {
                throw new IllegalStateException("Canvas template disabled tool was not read back: " + tool);
            }
        }

        List<Map<String, Object>> beforeSeeds = maps(beforeImpact.get("seedDynamicFields"));
        List<Map<String, Object>> afterSeeds = maps(afterImpact.get("seedDynamicFields"));
        for (Map<String, Object> seed : afterSeeds) {
            Map<String, Object> beforeSeed = beforeSeeds.stream()
                    .filter(candidate -> Objects.equals(candidate.get("moduleCode"), seed.get("moduleCode"))
                            && Objects.equals(candidate.get("fieldCode"), seed.get("fieldCode")))
                    .findFirst().orElse(null);
            Map<String, Object> current = mapOrNull(seed.get("current"));
            if (current == null) throw new IllegalStateException("Canvas seed dynamic field was not read back");
            Map<String, Object> previous = beforeSeed == null ? null : mapOrNull(beforeSeed.get("current"));
            if (previous != null) {
                if (!Objects.equals(previous, current)) throw new IllegalStateException("Existing dynamic field was overwritten");
            } else if (!sameWithoutId(map(seed.get("expected")), current)) {
                throw new IllegalStateException("New dynamic field did not match the exact seed contract");
            }
        }
    }

    private void assertTemplatePlanStable(Map<String, Object> before, Map<String, Object> after) {
        for (String key : new String[]{"canonicalTemplateCode", "enabledModules", "disabledModules",
                "moduleDesiredStates", "moduleSchemas", "expectedDefaultRows", "disabledTools", "expectedDraftVersion"}) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                throw new IllegalStateException("Canvas template impact changed after the plan was issued: " + key);
            }
        }
        List<Map<String, Object>> beforeSeeds = maps(before.get("seedDynamicFields"));
        List<Map<String, Object>> afterSeeds = maps(after.get("seedDynamicFields"));
        if (beforeSeeds.size() != afterSeeds.size()) {
            throw new IllegalStateException("Canvas template seed impact changed after the plan was issued");
        }
        for (Map<String, Object> expected : beforeSeeds) {
            Map<String, Object> current = afterSeeds.stream()
                    .filter(candidate -> Objects.equals(expected.get("moduleCode"), candidate.get("moduleCode"))
                            && Objects.equals(expected.get("fieldCode"), candidate.get("fieldCode")))
                    .findFirst().orElse(null);
            if (current == null || !sameWithout(expected, current, "current")) {
                throw new IllegalStateException("Canvas template seed impact changed after the plan was issued");
            }
        }
    }

    private void assertModuleStates(Map<String, Object> impact, List<Map<String, Object>> rows) {
        Map<String, Object> desired = map(impact.get("moduleDesiredStates"));
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            Map<String, Object> row = by(rows, "moduleCode", entry.getKey());
            if (row == null || !Objects.equals(entry.getValue(), row.get("enabled"))) {
                throw new IllegalStateException("Template module desired state was not read back: " + entry.getKey());
            }
        }
        for (String module : strings(impact.get("enabledModules"))) {
            Map<String, Object> row = by(rows, "moduleCode", module);
            if (row == null || !Boolean.TRUE.equals(row.get("enabled"))) {
                throw new IllegalStateException("Enabled template module was not read back: " + module);
            }
        }
        for (String module : strings(impact.get("disabledModules"))) {
            Map<String, Object> row = by(rows, "moduleCode", module);
            if (row == null || !Boolean.FALSE.equals(row.get("enabled"))) {
                throw new IllegalStateException("Disabled template module was not read back: " + module);
            }
        }
        for (Map<String, Object> schema : maps(impact.get("moduleSchemas"))) {
            if (strings(impact.get("enabledModules")).contains(String.valueOf(schema.get("moduleCode")))
                    && !Boolean.TRUE.equals(schema.get("exists"))) {
                throw new IllegalStateException("Enabled template module has no schema: " + schema.get("moduleCode"));
            }
        }
    }

    private void assertModuleConfigurationCloned(Map<String, Object> beforeImpact,
                                                 Map<String, Object> afterImpact,
                                                 List<Map<String, Object>> before,
                                                 List<Map<String, Object>> after) {
        String beforeStatus = String.valueOf(beforeImpact.get("configStatus"));
        String afterStatus = String.valueOf(afterImpact.get("configStatus"));
        Integer beforeVersion = integer(beforeImpact.get("configVersion"));
        Integer afterVersion = integer(afterImpact.get("configVersion"));
        boolean publishedClone = "PUBLISHED".equalsIgnoreCase(beforeStatus);
        boolean initialDraft = beforeImpact.get("configStatus") == null && beforeVersion == null;
        Integer expectedDraftVersion = integer(beforeImpact.get("expectedDraftVersion"));
        if (publishedClone || initialDraft) {
            if (!"DRAFT".equalsIgnoreCase(afterStatus) || afterVersion == null
                    || expectedDraftVersion == null || expectedDraftVersion < 1
                    || !Objects.equals(afterVersion, expectedDraftVersion)
                    || (initialDraft && !before.isEmpty())) {
                throw new IllegalStateException("Template impact did not read back as the bound next draft");
            }
        } else if (!Objects.equals(beforeStatus, afterStatus)
                || beforeVersion == null || afterVersion == null || !Objects.equals(beforeVersion, afterVersion)) {
            throw new IllegalStateException("Canvas template changed configuration version without a published clone");
        }
        Map<String, Object> desired = map(afterImpact.get("moduleDesiredStates"));
        for (Map<String, Object> previous : before) {
            Map<String, Object> current = by(after, "moduleCode", previous.get("moduleCode"));
            if (current == null) throw new IllegalStateException("Existing module configuration was not cloned");
            if (!Objects.equals(integer(previous.get("configVersion")), beforeVersion)
                    || !Objects.equals(integer(current.get("configVersion")), afterVersion)) {
                throw new IllegalStateException("Module configuration version did not match its configuration snapshot");
            }
            if (!sameWithout(previous, current, publishedClone
                    ? new String[]{"id", "configVersion", "enabled"}
                    : new String[]{"enabled"})) {
                throw new IllegalStateException("Existing module configuration was changed outside the template toggle");
            }
            String module = String.valueOf(previous.get("moduleCode"));
            if (!desired.containsKey(module) && !Objects.equals(previous.get("enabled"), current.get("enabled"))) {
                throw new IllegalStateException("Unchanged module enabled state was modified: " + module);
            }
            if (desired.containsKey(module) && !Objects.equals(desired.get(module), current.get("enabled"))) {
                throw new IllegalStateException("Template module desired state was not applied: " + module);
            }
        }
    }

    private void assertPreserved(List<Map<String, Object>> before, List<Map<String, Object>> after, String label) {
        for (Map<String, Object> row : before) {
            if (!after.contains(row) && !after.stream().anyMatch(candidate -> sameWithoutEnabled(row, candidate))) {
                throw new IllegalStateException("Existing " + label + " was not preserved");
            }
        }
    }

    private boolean sameWithoutEnabled(Map<String, Object> left, Map<String, Object> right) {
        return sameWithout(left, right, "enabled");
    }

    private boolean sameWithoutId(Map<String, Object> left, Map<String, Object> right) {
        return sameWithout(left, right, "id");
    }

    private boolean sameWithout(Map<String, Object> left, Map<String, Object> right, String... ignored) {
        Map<String, Object> a = new LinkedHashMap<>(left);
        Map<String, Object> b = new LinkedHashMap<>(right);
        for (String key : ignored) {
            a.remove(key);
            b.remove(key);
        }
        return Objects.equals(a, b);
    }

    private Integer integer(Object raw) {
        return raw instanceof Number number ? number.intValue() : null;
    }

    private Map<String, Object> by(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(row -> Objects.equals(String.valueOf(row.get(key)), String.valueOf(value)))
                .findFirst().orElse(null);
    }

    private String afterFactoryId(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("factoryId"))).findFirst().orElse(null);
    }

    private List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        values.forEach(value -> {
            Map<String, Object> row = mapOrNull(value);
            if (row != null) rows.add(row);
        });
        return rows;
    }

    private List<String> strings(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return List.of();
        List<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(String.valueOf(value)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOrNull(Object raw) {
        return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : null;
    }

    private Object findStep(Object rawSteps, Object rawOrder) {
        if (!(rawSteps instanceof Iterable<?> steps) || rawOrder == null) return null;
        int order = Integer.parseInt(String.valueOf(rawOrder));
        int index = 0;
        Object indexed = null;
        for (Object raw : steps) {
            Map<String, Object> step = map(raw);
            if (step != null && Integer.parseInt(String.valueOf(step.getOrDefault("order", -1))) == order) {
                return step.get("enabled");
            }
            if (index == order - 1) indexed = step == null ? null : step.get("enabled");
            index++;
        }
        return indexed;
    }

    private Boolean booleanValue(Object value) {
        return value == null ? null : value instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Object raw) {
        return mapper.convertValue(raw == null ? Map.of() : raw, Map.class);
    }

    private void requireTool(String toolName) {
        if (!supports(toolName)) throw new IllegalArgumentException("Canvas configuration tool is not bindable");
    }

    /** Constructible only here, after a fresh digest mismatch and before the writer is called. */
    public static final class StalePlanException extends IllegalStateException {
        private StalePlanException() {
            super("Canvas configuration changed after preview; confirmation is stale");
        }
    }
}
