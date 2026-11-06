package com.cretas.aims.service.decision;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.service.QualityDispositionRuleService.DispositionAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The versioned, side-effect-free evaluator used by quality disposition.
 *
 * <p>Keep this class deliberately independent from Spring, persistence, the
 * current clock and current factory configuration.  The thresholds and the
 * branch order are the historical live semantics at the time of introducing
 * replay.  A future rule change must use a new evaluator version.</p>
 */
public final class QualityRecommendationEvaluatorV1 {
    public static final String EVALUATOR_ID = "QUALITY_RECOMMENDATION";
    public static final String EVALUATOR_VERSION = "1.0.0";
    public static final String SNAPSHOT_SCHEMA_VERSION = "quality-recommendation.v1";
    public static final String SNAPSHOT_CONTEXT_KEY = "__cretas_quality_recommendation_v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RULE_DESCRIPTOR =
            "quality-disposition:v1|release>=95&PASS|conditional>=85|rework>=70|"
                    + "scrap:pass<70&defect>=50|special:pass<70&defect<50|"
                    + "null-pass=HOLD|confidence:live-v1";
    public static final String RULE_BUNDLE_HASH = sha256(RULE_DESCRIPTOR);

    private static final BigDecimal RELEASE = new BigDecimal("95.00");
    private static final BigDecimal CONDITIONAL = new BigDecimal("85.00");
    private static final BigDecimal REWORK = new BigDecimal("70.00");
    private static final BigDecimal SCRAP_DEFECT = new BigDecimal("50.00");

    public Evaluation evaluate(QualityInspection inspection) {
        if (inspection == null) {
            throw new IllegalArgumentException("inspection is required");
        }
        return evaluate(new Input(inspection.getPassRate(), inspection.getDefectRate(), inspection.getResult()));
    }

    public Evaluation evaluate(Input input) {
        if (input == null) {
            throw new IllegalArgumentException("evaluation input is required");
        }
        BigDecimal passRate = input.passRate();
        String result = input.result();
        DispositionAction action;
        if (passRate == null) {
            action = DispositionAction.HOLD;
        } else if (passRate.compareTo(RELEASE) >= 0 && "PASS".equalsIgnoreCase(result)) {
            action = DispositionAction.RELEASE;
        } else if (passRate.compareTo(CONDITIONAL) >= 0) {
            action = DispositionAction.CONDITIONAL_RELEASE;
        } else if (passRate.compareTo(REWORK) >= 0) {
            action = DispositionAction.REWORK;
        } else if (input.defectRate() != null && input.defectRate().compareTo(SCRAP_DEFECT) >= 0) {
            action = DispositionAction.SCRAP;
        } else if (passRate.compareTo(REWORK) < 0) {
            action = DispositionAction.SPECIAL_APPROVAL;
        } else {
            action = DispositionAction.HOLD;
        }
        return new Evaluation(action, confidence(passRate, action));
    }

    private static BigDecimal confidence(BigDecimal passRate, DispositionAction action) {
        if (passRate == null) {
            return BigDecimal.ZERO;
        }
        return switch (action) {
            case RELEASE -> passRate.min(new BigDecimal("100"));
            case CONDITIONAL_RELEASE -> new BigDecimal("75").add(
                    passRate.subtract(CONDITIONAL)
                            .divide(new BigDecimal("10"), 2, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("20")));
            case REWORK -> new BigDecimal("70");
            case SCRAP -> new BigDecimal("85");
            default -> new BigDecimal("50");
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /** Canonical JSON hash used for the persisted snapshot (object keys sorted recursively). */
    public static String canonicalHash(Map<String, Object> fields) {
        JsonNode node = MAPPER.valueToTree(new TreeMap<>(fields));
        if (node.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("snapshotHash");
        return canonicalHash(node);
    }

    public static String canonicalHash(JsonNode node) {
        return sha256(canonical(node));
    }

    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append('"').append(escape(entry.getKey())).append('"').append(':')
                        .append(canonical(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(node.get(i)));
            }
            return out.append(']').toString();
        }
        return node.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\b", "\\b").replace("\f", "\\f")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public record Input(BigDecimal passRate, BigDecimal defectRate, String result) { }

    public record Evaluation(DispositionAction recommendedAction, BigDecimal confidence) { }
}
