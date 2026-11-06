package com.cretas.aims.service.config;

import com.cretas.aims.entity.config.FactoryConfiguration;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.entity.config.FactoryTemplate;
import com.cretas.aims.entity.config.FactoryToolConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.entity.config.ModuleSchema;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.repository.config.FactoryConfigurationRepository;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.config.FactoryDefaultValueRepository;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryTemplateRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.repository.config.ModuleSchemaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Fresh, non-secret state reader used by the Canvas plan binding. */
@Service
public class CanvasConfigStateReader {
    private final FactoryConfigurationRepository configurations;
    private final FactoryModuleConfigRepository modules;
    private final FactoryToolConfigRepository tools;
    private final FactorySkillConfigRepository skills;
    private final FactoryTriggerChainRepository triggers;
    private final FactoryTemplateRepository templates;
    private final FactoryDefaultValueRepository defaults;
    private final CanvasDynamicFieldRepository dynamicFields;
    private final ModuleSchemaRepository moduleSchemas;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    @Autowired
    public CanvasConfigStateReader(
            FactoryConfigurationRepository configurations,
            FactoryModuleConfigRepository modules,
            FactoryToolConfigRepository tools,
            FactorySkillConfigRepository skills,
            FactoryTriggerChainRepository triggers,
            FactoryTemplateRepository templates,
            FactoryDefaultValueRepository defaults,
            CanvasDynamicFieldRepository dynamicFields,
            ModuleSchemaRepository moduleSchemas,
            @Lazy ToolRegistry toolRegistry,
            ObjectMapper mapper) {
        this.configurations = configurations;
        this.modules = modules;
        this.tools = tools;
        this.skills = skills;
        this.triggers = triggers;
        this.templates = templates;
        this.defaults = defaults;
        this.dynamicFields = dynamicFields;
        this.moduleSchemas = moduleSchemas;
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Snapshot read(String factoryId, String toolName, Map<String, Object> params) {
        Map<String, Object> state = new LinkedHashMap<>();
        String revision = "none";
        switch (toolName) {
            case "canvas_toggle_module", "canvas_update_field" -> {
                String moduleCode = text(params, "moduleCode");
                Optional<FactoryConfiguration> config = currentConfig(factoryId);
                if (config.isPresent()) {
                    revision = "config:" + config.get().getConfigVersion();
                    state.put("configVersion", config.get().getConfigVersion());
                    state.put("configStatus", config.get().getStatus());
                    modules.findByFactoryIdAndModuleCodeAndConfigVersion(
                                    factoryId, moduleCode, config.get().getConfigVersion())
                            .ifPresent(module -> state.put("module", moduleState(module, params)));
                }
                state.put("moduleCode", moduleCode);
            }
            case "canvas_apply_template" -> {
                Optional<FactoryConfiguration> config = currentConfig(factoryId);
                config.ifPresent(c -> {
                    state.put("configVersion", c.getConfigVersion());
                    state.put("configStatus", c.getStatus());
                });
                revision = config.map(c -> "config:" + c.getConfigVersion()).orElse("none");
                String code = text(params, "templateCode");
                templates.findByTemplateCodeIgnoreCase(code).ifPresent(template -> {
                    state.put("template", templateState(template));
                    state.put("templateImpact", templateImpact(factoryId, template));
                });
                config.ifPresent(c -> {
                    List<Map<String, Object>> moduleRows = new ArrayList<>();
                    modules.findByFactoryIdAndConfigVersion(factoryId, c.getConfigVersion())
                            .forEach(row -> moduleRows.add(moduleSnapshot(row)));
                    state.put("moduleConfigs", moduleRows.stream().sorted(mapComparator("moduleCode", "id")).toList());
                });
                state.put("defaultValues", defaults.findAllForFactory(factoryId).stream()
                        .map(this::defaultSnapshot)
                        .sorted(mapComparator("moduleCode", "fieldCode", "id"))
                        .toList());
                state.put("toolConfigs", tools.findByFactoryId(factoryId).stream().map(this::toolState)
                        .sorted(mapComparator("toolName", "id")).toList());
                state.put("templateCode", code.toLowerCase(Locale.ROOT));
            }
            case "canvas_toggle_tool" -> {
                String name = text(params, "toolName");
                state.put("toolName", name);
                tools.findByFactoryIdAndToolName(factoryId, name)
                        .ifPresent(row -> state.put("tool", toolState(row)));
            }
            case "canvas_toggle_skill" -> {
                String name = text(params, "skillName");
                state.put("skillName", name);
                skills.findByFactoryIdAndSkillName(factoryId, name)
                        .ifPresent(row -> state.put("skill", skillState(row)));
            }
            case "canvas_update_trigger_chain" -> {
                String code = text(params, "chainCode");
                state.put("chainCode", code);
                triggers.findByFactoryIdAndChainCode(factoryId, code)
                        .ifPresent(row -> state.put("trigger", triggerState(row)));
            }
            default -> throw new IllegalArgumentException("unsupported Canvas configuration tool");
        }
        return new Snapshot(revision, fingerprint(state), state);
    }

    private Optional<FactoryConfiguration> currentConfig(String factoryId) {
        return configurations.findDraft(factoryId).or(() -> configurations.findLatestPublished(factoryId));
    }

    private Map<String, Object> moduleState(FactoryModuleConfig row, Map<String, Object> params) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("configVersion", row.getConfigVersion());
        state.put("enabled", row.getEnabled());
        if ("canvas_update_field".equals(text(params, "_tool"))) {
            state.put("fieldConfig", row.getFieldConfig());
        } else if (params.containsKey("fieldCode")) {
            state.put("fieldConfig", row.getFieldConfig());
        }
        state.put("fieldCode", params.get("fieldCode"));
        return state;
    }

    private Map<String, Object> moduleSnapshot(FactoryModuleConfig row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("factoryId", row.getFactoryId());
        state.put("moduleCode", row.getModuleCode());
        state.put("configVersion", row.getConfigVersion());
        state.put("enabled", row.getEnabled());
        state.put("fieldConfig", row.getFieldConfig());
        state.put("workflowConfig", row.getWorkflowConfig());
        state.put("validationConfig", row.getValidationConfig());
        state.put("permissionConfig", row.getPermissionConfig());
        state.put("roleModuleOverride", row.getRoleModuleOverride());
        state.put("layoutConfig", row.getLayoutConfig());
        state.put("customLabels", row.getCustomLabels());
        state.put("computedFields", row.getComputedFields());
        state.put("renderingMode", row.getRenderingMode());
        return state;
    }

    private Map<String, Object> defaultSnapshot(com.cretas.aims.entity.config.FactoryDefaultValue row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("factoryId", row.getFactoryId());
        state.put("moduleCode", row.getModuleCode());
        state.put("fieldCode", row.getFieldCode());
        state.put("defaultValue", row.getDefaultValue());
        state.put("condition", row.getCondition());
        state.put("description", row.getDescription());
        return state;
    }

    private Map<String, Object> dynamicSnapshot(CanvasDynamicField row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("factoryId", row.getFactoryId());
        state.put("moduleCode", row.getModuleCode());
        state.put("fieldCode", row.getFieldCode());
        state.put("fieldType", row.getFieldType());
        state.put("label", row.getLabel());
        state.put("config", row.getConfig());
        state.put("visibleWhen", row.getVisibleWhen());
        state.put("computedWhen", row.getComputedWhen());
        state.put("sortOrder", row.getSortOrder());
        state.put("status", row.getStatus());
        state.put("columnName", row.getColumnName());
        return state;
    }

    private Map<String, Object> schemaSnapshot(ModuleSchema row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("moduleCode", row.getModuleCode());
        state.put("moduleVersion", row.getModuleVersion());
        state.put("isActive", row.getIsActive());
        state.put("deletedAt", row.getDeletedAt());
        state.put("fieldSchema", row.getFieldSchema());
        return state;
    }

    private Map<String, Object> templateState(FactoryTemplate row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("templateCode", row.getTemplateCode() == null ? null : row.getTemplateCode().toLowerCase(Locale.ROOT));
        state.put("isActive", row.getIsActive());
        state.put("deletedAt", row.getDeletedAt());
        state.put("baseConfig", row.getBaseConfig());
        return state;
    }

    private Map<String, Object> toolState(FactoryToolConfig row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("factoryId", row.getFactoryId());
        state.put("toolName", row.getToolName());
        state.put("enabled", row.getEnabled());
        state.put("paramOverrides", row.getParamOverrides());
        state.put("riskOverride", row.getRiskOverride());
        return state;
    }

    private Map<String, Object> skillState(FactorySkillConfig row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("skillName", row.getSkillName());
        state.put("enabled", row.getEnabled());
        state.put("priority", row.getPriority());
        state.put("customDag", row.getCustomDag());
        state.put("customTriggers", row.getCustomTriggers());
        return state;
    }

    private Map<String, Object> triggerState(FactoryTriggerChain row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("factoryId", row.getFactoryId());
        state.put("chainCode", row.getChainCode());
        state.put("eventType", row.getEventType());
        state.put("description", row.getDescription());
        state.put("enabled", row.getEnabled());
        state.put("steps", row.getSteps());
        state.put("errorStrategy", row.getErrorStrategy());
        state.put("runAsUserId", row.getRunAsUserId());
        return state;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> templateImpact(String factoryId, FactoryTemplate template) {
        Map<String, Object> base = template.getBaseConfig() == null ? Map.of() : template.getBaseConfig();
        Map<String, Object> moduleConfig = map(base.get("moduleConfigs"));
        List<String> enabled = sortedStrings(moduleConfig.get("enabledModules"));
        List<String> disabled = sortedStrings(moduleConfig.get("disabledModules"));
        Map<String, Object> overrides = map(base.get("defaultOverrides"));
        Map<String, Object> defaultValues = map(overrides.get("defaultValues"));
        Set<String> moduleCodes = new LinkedHashSet<>();
        moduleCodes.addAll(enabled);
        moduleCodes.addAll(disabled);
        moduleCodes.addAll(defaultValues.keySet().stream().map(String::valueOf).toList());

        List<Map<String, Object>> schemas = moduleCodes.stream().sorted().map(code -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("moduleCode", code);
            Optional<ModuleSchema> schema = moduleSchemas.findByModuleCode(code);
            state.put("exists", schema.isPresent());
            schema.ifPresent(value -> state.putAll(schemaSnapshot(value)));
            return state;
        }).toList();
        List<Map<String, Object>> moduleRows = currentConfig(factoryId)
                .map(config -> modules.findByFactoryIdAndConfigVersion(factoryId, config.getConfigVersion()).stream()
                        .map(this::moduleSnapshot).sorted(mapComparator("moduleCode", "id")).toList())
                .orElseGet(List::of);
        Optional<FactoryConfiguration> impactConfig = currentConfig(factoryId);
        int latestPhysicalVersion = configurations.findByFactoryIdOrderByConfigVersionDesc(factoryId).stream()
                .mapToInt(FactoryConfiguration::getConfigVersion).max().orElse(0);
        boolean legacyVersionOneExists = latestPhysicalVersion == 0
                && !modules.findByFactoryIdAndConfigVersion(factoryId, 1).isEmpty();
        int expectedDraftVersion = impactConfig.filter(value -> "DRAFT".equals(value.getStatus()))
                .map(FactoryConfiguration::getConfigVersion)
                .orElseGet(() -> CanvasDraftVersionPolicy.next(latestPhysicalVersion, legacyVersionOneExists));
        List<Map<String, Object>> factoryDefaults = defaults.findAllForFactory(factoryId).stream()
                .filter(row -> factoryId.equals(row.getFactoryId()))
                .map(this::defaultSnapshot).sorted(mapComparator("moduleCode", "fieldCode", "id")).toList();
        List<Map<String, Object>> expectedDefaults = new ArrayList<>();
        defaultValues.keySet().stream().map(String::valueOf).sorted().forEach(module -> {
            if (com.cretas.aims.util.RetiredTemplatePolicy.isRetiredModule(module)) return;
            if (!(defaultValues.get(module) instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Canvas template default fields must be an object");
            }
            Map<String, Object> fields = map(defaultValues.get(module));
            fields.keySet().stream().map(String::valueOf).sorted().forEach(field -> {
                Object raw = fields.get(field);
                if (raw == null) throw new IllegalArgumentException("Canvas template default value cannot be null");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("moduleCode", module);
                row.put("fieldCode", field);
                row.put("rawValue", raw);
                row.put("storedValue", Map.of("value", raw));
                expectedDefaults.add(row);
            });
        });

        List<String> disabledTools = toolRegistry.getAllToolNames().stream()
                .filter(name -> matchesAny(name, sortedStrings(overrides.get("disabledTools"))))
                .sorted().toList();
        List<Map<String, Object>> toolRows = tools.findByFactoryId(factoryId).stream()
                .filter(row -> disabledTools.contains(row.getToolName()))
                .map(this::toolState).sorted(mapComparator("toolName", "id")).toList();

        List<Map<String, Object>> seedFields = new ArrayList<>();
        for (Map<String, Object> seed : mapsStrict(base.get("seedDynamicFields"))) {
            String module = text(seed, "moduleCode");
            String field = text(seed, "fieldCode");
            if (module.isBlank() || field.isBlank()) {
                throw new IllegalArgumentException("Canvas seed dynamic field identity missing");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("moduleCode", module);
            item.put("fieldCode", field);
            item.put("expected", expectedSeed(seed, factoryId));
            item.put("current", dynamicFields.findByFactoryIdAndModuleCodeAndFieldCode(factoryId, module, field)
                    .map(this::dynamicSnapshot).orElse(null));
            seedFields.add(item);
        }
        seedFields.sort(mapComparator("moduleCode", "fieldCode"));

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("canonicalTemplateCode", template.getTemplateCode().toLowerCase(Locale.ROOT));
        impact.put("enabledModules", enabled);
        impact.put("disabledModules", disabled);
        Map<String, Boolean> desiredStates = new LinkedHashMap<>();
        enabled.forEach(code -> desiredStates.put(code, true));
        disabled.forEach(code -> desiredStates.put(code, false));
        impact.put("moduleDesiredStates", desiredStates);
        impact.put("moduleSchemas", schemas);
        impact.put("moduleRows", moduleRows);
        impact.put("configVersion", impactConfig.map(FactoryConfiguration::getConfigVersion).orElse(null));
        impact.put("configStatus", impactConfig.map(FactoryConfiguration::getStatus).orElse(null));
        impact.put("latestPhysicalVersion", latestPhysicalVersion);
        impact.put("legacyVersionOneExists", legacyVersionOneExists);
        impact.put("expectedDraftVersion", expectedDraftVersion);
        impact.put("factoryDefaults", factoryDefaults);
        impact.put("expectedDefaultRows", expectedDefaults);
        impact.put("disabledTools", disabledTools);
        impact.put("toolRows", toolRows);
        impact.put("seedDynamicFields", seedFields);
        return impact;
    }

    private Map<String, Object> expectedSeed(Map<String, Object> seed, String factoryId) {
        String field = text(seed, "fieldCode");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("factoryId", factoryId);
        expected.put("moduleCode", text(seed, "moduleCode"));
        expected.put("fieldCode", field);
        expected.put("fieldType", seed.get("fieldType"));
        expected.put("label", seed.get("label"));
        expected.put("config", map(seed.get("config")));
        expected.put("visibleWhen", seed.get("visibleWhen"));
        expected.put("computedWhen", seed.get("computedWhen"));
        expected.put("sortOrder", seed.get("sortOrder") == null ? 0 : ((Number) seed.get("sortOrder")).intValue());
        expected.put("status", "PENDING_DDL");
        expected.put("columnName", "cf_" + field);
        return expected;
    }

    private boolean matchesAny(String toolName, List<String> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.endsWith("*")
                ? toolName.startsWith(pattern.substring(0, pattern.length() - 1))
                : toolName.equals(pattern));
    }

    private List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance).map(value -> map(value)).toList();
    }

    private List<Map<String, Object>> mapsStrict(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Canvas seed dynamic fields must be objects");
            result.add(map(value));
        }
        return result;
    }

    private List<String> sortedStrings(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf).distinct().sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
    }

    private Comparator<Map<String, Object>> mapComparator(String... keys) {
        return (left, right) -> {
            for (String key : keys) {
                int result = String.valueOf(left.getOrDefault(key, ""))
                        .compareTo(String.valueOf(right.getOrDefault(key, "")));
                if (result != 0) return result;
            }
            return 0;
        };
    }

    private String text(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public String fingerprint(Map<String, Object> state) {
        try {
            JsonNode node = canonicalNode(state);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(node.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Canvas state fingerprint unavailable", e);
        }
    }

    private JsonNode canonicalNode(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            raw.keySet().stream().map(String::valueOf).sorted().forEach(key -> {
                Object original = raw.get(key);
                sorted.put(key, canonicalNode(original));
            });
            return mapper.valueToTree(sorted);
        }
        if (value instanceof List<?> list) {
            List<JsonNode> nodes = new ArrayList<>();
            list.forEach(item -> nodes.add(canonicalNode(item)));
            return mapper.valueToTree(nodes);
        }
        return mapper.valueToTree(value);
    }

    public record Snapshot(String revision, String fingerprint, Map<String, Object> state) { }
}
