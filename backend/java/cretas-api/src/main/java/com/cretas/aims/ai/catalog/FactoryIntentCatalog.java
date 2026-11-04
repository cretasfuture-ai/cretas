package com.cretas.aims.ai.catalog;

import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared platform catalog. Model head IDs and tenant authorization are separate contracts. */
public final class FactoryIntentCatalog {
    private static final JsonNode DOCUMENT = load();
    private static final Map<String, JsonNode> INTENTS = intents();
    private static final Map<String, String> ALIASES = aliases();
    private static final Set<String> RETIRED = strings("retired_intents");
    private static final Set<String> RETIRED_TOOLS = strings("retired_tools");
    private static final Set<String> RECLASSIFIED = strings("reclassified_intents");

    private FactoryIntentCatalog() {}

    private static JsonNode load() {
        try (InputStream input = FactoryIntentCatalog.class.getResourceAsStream("/ai/factory-intent-catalog.json")) {
            if (input == null) throw new IllegalStateException("Factory intent catalog is missing");
            JsonNode document = new ObjectMapper().readTree(input);
            if (document.path("schema_version").asInt() != 1) {
                throw new IllegalStateException("Unsupported factory intent catalog schema");
            }
            return document;
        } catch (IOException e) {
            throw new IllegalStateException("Invalid factory intent catalog", e);
        }
    }

    private static Map<String, JsonNode> intents() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode row : DOCUMENT.required("canonical_intents")) {
            String code = row.required("intent_code").asText();
            if (code.isBlank() || result.put(code, row) != null) {
                throw new IllegalStateException("Duplicate or empty canonical intent code");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> aliases() {
        Map<String, String> result = new LinkedHashMap<>();
        DOCUMENT.required("aliases").fields().forEachRemaining(entry -> {
            String target = entry.getValue().asText();
            if (!INTENTS.containsKey(target)) throw new IllegalStateException("Alias target is not canonical");
            result.put(entry.getKey(), target);
        });
        return Map.copyOf(result);
    }

    private static Set<String> strings(String key) {
        Set<String> result = new LinkedHashSet<>();
        JsonNode values = DOCUMENT.required(key);
        if (!values.isArray()) throw new IllegalStateException("Catalog field must be an array: " + key);
        values.forEach(value -> result.add(value.asText()));
        return Set.copyOf(result);
    }

    public static String canonicalCode(String code) {
        return code == null ? null : ALIASES.getOrDefault(code, code);
    }

    public static boolean isRetiredIntent(String code) {
        return code != null && (RETIRED.contains(code) || code.startsWith("RESTAURANT_"));
    }

    public static boolean isRetiredTool(String name) {
        return name != null && (RETIRED_TOOLS.contains(name) || name.startsWith("restaurant_"));
    }

    public static boolean isRetiredSkill(String name, List<String> tools) {
        return (name != null && name.startsWith("restaurant-"))
                || (tools != null && tools.stream().anyMatch(FactoryIntentCatalog::isRetiredTool));
    }

    public static List<String> requiredSkillTools(String skillName) {
        for (JsonNode row : INTENTS.values()) {
            if (skillName != null && skillName.equals(row.path("skill_name").asText()) && row.has("skill_tools")) {
                java.util.ArrayList<String> tools = new java.util.ArrayList<>();
                row.path("skill_tools").forEach(value -> tools.add(value.asText()));
                return List.copyOf(tools);
            }
        }
        return null;
    }

    public static boolean isAllowedSkill(String name, List<String> tools) {
        if (isRetiredSkill(name, tools)) return false;
        List<String> required = requiredSkillTools(name);
        // A persisted legacy override cannot add writes to a catalog-owned read-only Skill.
        return required == null || required.equals(tools);
    }

    public static boolean isAllowedSkill(com.cretas.aims.dto.skill.SkillDefinition skill) {
        if (skill == null || !isAllowedSkill(skill.getName(), skill.getTools())) return false;
        List<String> allowed = requiredSkillTools(skill.getName());
        if (skill.getExecutionGraph() != null) {
            for (var node : skill.getExecutionGraph()) {
                if (!allowedNodeTool(node.getToolName(), allowed) || !allowedNodeTool(node.getFallbackTool(), allowed)) return false;
            }
        }
        return allowedConfig(skill.getConfig(), allowed);
    }

    private static boolean allowedNodeTool(String name, List<String> allowed) {
        return name == null || !isRetiredTool(name) && (allowed == null || allowed.contains(name));
    }

    private static boolean allowedConfig(Object value, List<String> allowed) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (Set.of("tool", "toolName", "fallbackTool", "fallback_tool", "targetTool").contains(String.valueOf(entry.getKey()))
                        && entry.getValue() instanceof String name && !allowedNodeTool(name, allowed)) return false;
                if (!allowedConfig(entry.getValue(), allowed)) return false;
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object item : values) if (!allowedConfig(item, allowed)) return false;
        }
        return true;
    }

    public static Set<String> canonicalCodes() { return INTENTS.keySet(); }
    public static Set<String> retiredCodes() { return RETIRED; }

    public static String skillName(String code) {
        JsonNode row = INTENTS.get(canonicalCode(code));
        return row != null && row.hasNonNull("skill_name") ? row.path("skill_name").asText() : null;
    }

    public static String intentCodeForSkill(String skillName) {
        return INTENTS.values().stream().filter(row -> skillName != null
                && skillName.equals(row.path("skill_name").asText()))
                .map(row -> row.path("intent_code").asText()).findFirst().orElse(null);
    }

    public static boolean isPlatformOnlyIntent(String code) {
        return "GOVERNANCE_SKILL_COMPOSE".equals(canonicalCode(code))
                || "GOVERNANCE_SKILL_MANAGE".equals(canonicalCode(code))
                || "GOVERNANCE_PATTERN_DISCOVERY".equals(canonicalCode(code));
    }

    /** Never mutate managed entities; preserve tenant/confirmation and tighten global governance roles only. */
    public static AIIntentConfig project(AIIntentConfig source) {
        if (source == null || isRetiredIntent(source.getIntentCode()) || ALIASES.containsKey(source.getIntentCode())) {
            return null;
        }
        JsonNode row = INTENTS.get(source.getIntentCode());
        if (row == null || !RECLASSIFIED.contains(source.getIntentCode()) && !row.path("binding_override").asBoolean()) return source;
        AIIntentConfig copy = new AIIntentConfig();
        BeanUtils.copyProperties(source, copy);
        if (RECLASSIFIED.contains(source.getIntentCode())) copy.setBusinessType(row.path("business_type").asText("FACTORY"));
        // Null is an explicit Skill binding, not permission to keep a stale legacy Tool.
        copy.setToolName(row.hasNonNull("tool_name") ? row.path("tool_name").asText() : null);
        // This route can only explain a policy refusal (410), never issue an invoice.
        // A legacy write classification must not ask users to confirm a nonexistent write.
        if ("AUTOMATIC_INVOICING_ONLY".equals(row.path("execution_policy").asText())
                || "NAVIGATION_ONLY".equals(row.path("execution_policy").asText())) {
            copy.setSensitivityLevel("LOW");
            copy.setRequiresApproval(false);
        }
        if (isPlatformOnlyIntent(source.getIntentCode())) {
            copy.setRequiredRoles("[\"super_admin\",\"platform_admin\",\"developer\"]");
        }
        return copy;
    }

    public static List<AIIntentConfig> project(List<AIIntentConfig> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(FactoryIntentCatalog::project).filter(java.util.Objects::nonNull).toList();
    }

    /** Copy cached DTOs; a retired best match cannot be replaced by guessing the second candidate. */
    public static IntentMatchResult filterResult(IntentMatchResult result) {
        if (result == null) return null;
        if (result.getBestMatch() != null && (project(result.getBestMatch()) == null
                || isPlatformOnlyIntent(result.getBestMatch().getIntentCode()) && !PlatformGovernanceAccess.allowed())) {
            return IntentMatchResult.empty(result.getUserInput());
        }
        List<IntentMatchResult.CandidateIntent> candidates = result.getTopCandidates() == null ? List.of()
                : result.getTopCandidates().stream()
                .filter(candidate -> !isRetiredIntent(candidate.getIntentCode())
                        && !ALIASES.containsKey(candidate.getIntentCode())
                        && (!isPlatformOnlyIntent(candidate.getIntentCode()) || PlatformGovernanceAccess.allowed())).toList();
        return result.toBuilder().bestMatch(project(result.getBestMatch())).topCandidates(candidates).build();
    }
}
