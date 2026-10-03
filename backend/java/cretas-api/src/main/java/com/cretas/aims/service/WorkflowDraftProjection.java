package com.cretas.aims.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkflowDraftProjection {

    private static final Set<String> SPEC_FIELDS = Set.of("rawMaterials", "steps");
    private static final Set<String> STEP_FIELDS = Set.of(
            "process", "inputs", "processCategory", "outputs", "seasonings", "injectionAmount");
    private static final Set<String> OUTPUT_FIELDS = Set.of("kind", "name", "unit", "byproduct");
    private static final Set<String> SEASONING_FIELDS = Set.of("name", "dosagePerKgG", "subsequentPotRatio");

    private final ObjectMapper strictMapper;

    public WorkflowDraftProjection(ObjectMapper objectMapper) {
        strictMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public Result project(String providerSemanticJson, String originalRequest) {
        String sourceSha256 = hash(originalRequest);
        JsonNode spec;
        try {
            spec = read(providerSemanticJson);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return new Result(Decision.REJECTED, Map.of(), sourceSha256, List.of("MALFORMED_JSON"));
        }
        Set<String> issues = validate(spec);
        if (!issues.isEmpty()) {
            return new Result(Decision.REJECTED, Map.of(), sourceSha256, List.copyOf(issues));
        }
        Map<String, Object> projectedSpec = strictMapper.convertValue(
                spec, new TypeReference<Map<String, Object>>() {});
        SourceBinding binding = bindOriginalRequest(originalRequest);
        if (binding == null) {
            return new Result(Decision.REVIEW_REQUIRED, projectedSpec, sourceSha256,
                    List.of("SOURCE_BINDING_REQUIRED"));
        }
        if (!matchesSource(spec.get("rawMaterials"), binding.semanticSpec().get("rawMaterials"))) {
            issues.add("RAW_MATERIAL_SOURCE_CHANGED");
        }
        if (!matchesSource(spec.get("steps"), binding.semanticSpec().get("steps"))) {
            issues.add("STEP_SOURCE_CHANGED");
        }
        if (!issues.isEmpty()) {
            return new Result(Decision.REJECTED, Map.of(), sourceSha256, List.copyOf(issues));
        }
        return new Result(Decision.REVIEWABLE, projectedSpec, sourceSha256, List.of());
    }

    private SourceBinding bindOriginalRequest(String originalRequest) {
        try {
            JsonNode callerSpec = read(originalRequest);
            return validate(callerSpec).isEmpty() ? new SourceBinding(callerSpec) : null;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return null;
        }
    }

    private boolean matchesSource(JsonNode proposed, JsonNode original) {
        if (proposed.isNumber() && original.isNumber()) {
            return proposed.decimalValue().compareTo(original.decimalValue()) == 0;
        }
        if (proposed.isObject() && original.isObject()) {
            if (proposed.size() != original.size()) {
                return false;
            }
            var fields = proposed.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode expected = original.get(field.getKey());
                if (expected == null || !matchesSource(field.getValue(), expected)) {
                    return false;
                }
            }
            return true;
        }
        if (proposed.isArray() && original.isArray()) {
            if (proposed.size() != original.size()) {
                return false;
            }
            for (int index = 0; index < proposed.size(); index++) {
                if (!matchesSource(proposed.get(index), original.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return proposed.equals(original);
    }

    private JsonNode read(String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("JSON is required");
        }
        return strictMapper.readTree(raw);
    }

    private Set<String> validate(JsonNode spec) {
        Set<String> issues = new LinkedHashSet<>();
        if (!object(spec, SPEC_FIELDS, "SPEC_OBJECT_OR_FIELDS_INVALID", issues)) {
            return issues;
        }
        if (!names(spec.get("rawMaterials"), false)) {
            issues.add("RAW_MATERIALS_INVALID");
        }
        JsonNode steps = spec.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            issues.add("STEPS_REQUIRED");
            return issues;
        }
        for (JsonNode step : steps) {
            if (!object(step, STEP_FIELDS, "STEP_OBJECT_OR_FIELDS_INVALID", issues)) {
                continue;
            }
            if (!text(step.get("process"))) {
                issues.add("PROCESS_REQUIRED");
            }
            if (step.has("inputs") && !names(step.get("inputs"), true)) {
                issues.add("INPUT_NAMES_INVALID");
            }
            if (step.has("processCategory") && !text(step.get("processCategory"))) {
                issues.add("PROCESS_CATEGORY_INVALID");
            }
            validateOutputs(step.get("outputs"), issues);
            validateSeasonings(step, issues);
            if (step.has("injectionAmount") && (!"注射".equals(step.path("processCategory").asText())
                    || !positive(step.get("injectionAmount")))) {
                issues.add("INJECTION_AMOUNT_INVALID");
            }
        }
        return issues;
    }

    private void validateOutputs(JsonNode outputs, Set<String> issues) {
        if (outputs == null || !outputs.isArray() || outputs.isEmpty()) {
            issues.add("OUTPUTS_REQUIRED");
            return;
        }
        for (JsonNode output : outputs) {
            if (!object(output, OUTPUT_FIELDS, "OUTPUT_OBJECT_OR_FIELDS_INVALID", issues)) {
                continue;
            }
            if (!Set.of("SEMI_FINISHED", "FINISHED_GOOD").contains(output.path("kind").asText())
                    || !text(output.get("name")) || !text(output.get("unit"))) {
                issues.add("OUTPUT_KIND_NAME_UNIT_INVALID");
            }
            if (output.has("byproduct") && !output.get("byproduct").isBoolean()) {
                issues.add("BYPRODUCT_BOOLEAN_REQUIRED");
            }
        }
    }

    private void validateSeasonings(JsonNode step, Set<String> issues) {
        if (!step.has("seasonings")) {
            return;
        }
        JsonNode seasonings = step.get("seasonings");
        if (!seasonings.isArray()) {
            issues.add("SEASONINGS_LIST_REQUIRED");
            return;
        }
        for (JsonNode seasoning : seasonings) {
            if (!object(seasoning, SEASONING_FIELDS, "SEASONING_OBJECT_OR_FIELDS_INVALID", issues)) {
                continue;
            }
            if (!text(seasoning.get("name")) || !positive(seasoning.get("dosagePerKgG"))) {
                issues.add("SEASONING_NAME_DOSAGE_INVALID");
            }
            if (seasoning.has("subsequentPotRatio")) {
                JsonNode ratio = seasoning.get("subsequentPotRatio");
                if (!"熟制".equals(step.path("processCategory").asText()) || !finiteNumber(ratio)
                        || ratio.decimalValue().compareTo(BigDecimal.ZERO) < 0
                        || ratio.decimalValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                    issues.add("SUBSEQUENT_POT_RATIO_INVALID");
                }
            }
        }
    }

    private boolean object(JsonNode node, Set<String> allowed, String issue, Set<String> issues) {
        if (node == null || !node.isObject()) {
            issues.add(issue);
            return false;
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                issues.add(issue);
            }
        });
        return true;
    }

    private boolean names(JsonNode node, boolean allowEmpty) {
        if (node == null || !node.isArray() || (!allowEmpty && node.isEmpty())) {
            return false;
        }
        for (JsonNode name : node) {
            if (!text(name)) {
                return false;
            }
        }
        return true;
    }

    private boolean text(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank();
    }

    private boolean finiteNumber(JsonNode node) {
        return node != null && node.isNumber();
    }

    private boolean positive(JsonNode node) {
        return finiteNumber(node) && node.decimalValue().compareTo(BigDecimal.ZERO) > 0;
    }

    private String hash(String source) {
        if (source == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record SourceBinding(JsonNode semanticSpec) {}

    public enum Decision {
        REJECTED, REVIEW_REQUIRED, REVIEWABLE
    }

    public record Result(Decision decision, Map<String, Object> spec,
                         String sourceSha256, List<String> issues) {}
}
