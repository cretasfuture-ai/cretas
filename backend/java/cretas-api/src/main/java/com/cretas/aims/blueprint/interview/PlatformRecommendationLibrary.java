package com.cretas.aims.blueprint.interview;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-owned onboarding recommendations selected by the current factory's industry answer.
 *
 * <p>The public recommendation contract deliberately has no source factory, customer, tenant,
 * similarity score or cross-factory provenance. A customer-owned Blueprint may inform a separately
 * governed internal review, but it can never be served from this library. Files loaded here must be
 * reviewed platform assets containing only generalized structure and prompts.</p>
 *
 * <p>Every value remains a suggestion. It becomes a fact only after the current factory explicitly
 * confirms it through the normal interview answer path.</p>
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class PlatformRecommendationLibrary {

    private static final String BASE = "ai/factory-interview/platform-recommendations/";

    @Data
    public static class PlatformRecommendation {
        private String industryHint;
        private String intro;
        private List<Map<String, Object>> steps = new ArrayList<>();
        private List<String> variants = new ArrayList<>();
        private List<Map<String, Object>> mustAsk = new ArrayList<>();
        /** Suggested process count; the current factory must still confirm it. */
        private int stepCount;
        /** Suggested values keyed to expanded interview fields. */
        private Map<String, String> suggestions = new LinkedHashMap<>();
    }

    /** Returns a platform recommendation, or {@code null} when this industry has no reviewed pack. */
    public PlatformRecommendation forIndustry(String industryHint) {
        if (industryHint == null || industryHint.isBlank()) {
            return null;
        }
        String name = industryHint.trim();
        if (!name.matches("[\\p{L}\\p{N}_-]{1,64}")) {
            log.warn("Ignoring unsafe platform recommendation name");
            return null;
        }
        ClassPathResource resource = new ClassPathResource(BASE + name + ".yaml");
        if (!resource.exists()) {
            log.info("No reviewed platform onboarding recommendation for industry {}", name);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> raw = new Yaml().load(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            rejectPrivateProvenance(raw, name);
            return toRecommendation(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Platform recommendation file is invalid: " + name + ".yaml", e);
        }
    }

    void rejectPrivateProvenance(Map<String, Object> raw, String name) {
        if (raw == null) {
            throw new IllegalArgumentException("Empty platform recommendation: " + name);
        }
        List<String> forbidden = List.of(
                "derivedFrom", "factoryId", "factoryName", "sourceFactoryId", "sourceFactoryName",
                "customerId", "customerName", "tenantId", "tenantName", "similarity");
        List<String> present = new ArrayList<>();
        findForbiddenKeys(raw, "$", forbidden, present);
        if (!present.isEmpty()) {
            throw new IllegalArgumentException(
                    "Platform recommendation contains private provenance fields: " + present);
        }
    }

    private void findForbiddenKeys(Object value, String path, List<String> forbidden, List<String> present) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path + "." + key;
                if (forbidden.contains(key)) {
                    present.add(childPath);
                }
                findForbiddenKeys(entry.getValue(), childPath, forbidden, present);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                findForbiddenKeys(list.get(i), path + "[" + i + "]", forbidden, present);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private PlatformRecommendation toRecommendation(Map<String, Object> raw) {
        PlatformRecommendation recommendation = new PlatformRecommendation();
        recommendation.setIndustryHint(string(raw.get("industryHint")));
        recommendation.setIntro(string(raw.get("intro")));
        Object variants = raw.get("variants");
        if (variants instanceof List) {
            for (Object variant : (List<Object>) variants) {
                recommendation.getVariants().add(String.valueOf(variant));
            }
        }
        Object mustAsk = raw.get("mustAsk");
        if (mustAsk instanceof List) {
            recommendation.getMustAsk().addAll((List<Map<String, Object>>) mustAsk);
        }

        Object steps = raw.get("steps");
        if (!(steps instanceof List)) {
            return recommendation;
        }
        int index = 0;
        for (Object value : (List<Object>) steps) {
            if (!(value instanceof Map)) {
                continue;
            }
            Map<String, Object> step = (Map<String, Object>) value;
            index++;
            recommendation.getSteps().add(step);
            String prefix = "step" + index + ".";
            put(recommendation, prefix + "name", string(step.get("name")));
            put(recommendation, prefix + "inputMaterial", string(step.get("input")));
            put(recommendation, prefix + "auxMaterials", joinOrNone(step.get("aux")));
            put(recommendation, prefix + "packMaterials", joinOrNone(step.get("pack")));
            put(recommendation, prefix + "output", outputWithUnit(step));
            put(recommendation, prefix + "params", joinOrNone(step.get("params")));
            put(recommendation, prefix + "yieldRate", yieldText(step));
            put(recommendation, prefix + "byproducts", joinOrNone(step.get("byproducts")));
        }
        recommendation.setStepCount(index);
        return recommendation;
    }

    private void put(PlatformRecommendation recommendation, String key, String value) {
        if (value != null && !value.isBlank()) {
            recommendation.getSuggestions().put(key, value);
        }
    }

    private String outputWithUnit(Map<String, Object> step) {
        String output = string(step.get("output"));
        String unit = string(step.get("outputUnit"));
        if (output == null) {
            return null;
        }
        return unit == null ? output : output + "，按" + unit + "计";
    }

    /**
     * Numeric yield suggestions are allowed only when the platform pack deliberately supplies a
     * reviewed range. Missing ranges do not become a fake default. A non-numeric explanatory note
     * is returned only for steps where yield is explicitly not applicable, such as packaging.
     */
    private String yieldText(Map<String, Object> step) {
        Object range = step.get("yieldRange");
        String note = string(step.get("yieldNote"));
        if (!(range instanceof List) || ((List<?>) range).size() != 2) {
            return note;
        }
        List<?> values = (List<?>) range;
        String base = values.get(0) + "–" + values.get(1);
        double upper;
        try {
            upper = Double.parseDouble(String.valueOf(values.get(1)));
        } catch (NumberFormatException e) {
            return base;
        }
        if (upper > 1.0) {
            return base + "（大于 1 可能来自注水或加汁，请按当前工厂实际情况确认）";
        }
        return note != null ? base + "（" + note + "）" : base;
    }

    @SuppressWarnings("unchecked")
    private String joinOrNone(Object value) {
        if (!(value instanceof List) || ((List<Object>) value).isEmpty()) {
            return "没有";
        }
        StringBuilder result = new StringBuilder();
        for (Object item : (List<Object>) value) {
            if (result.length() > 0) {
                result.append("、");
            }
            result.append(item);
        }
        return result.toString();
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }
}
