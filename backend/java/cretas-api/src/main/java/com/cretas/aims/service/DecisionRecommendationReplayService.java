package com.cretas.aims.service;

import com.cretas.aims.dto.decision.DecisionRecommendationReplay;
import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.DecisionAuditLogRepository;
import com.cretas.aims.service.decision.QualityRecommendationEvaluatorV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Performs a bounded, factory-scoped, read-only replay of future snapshots. */
@Service
@RequiredArgsConstructor
public class DecisionRecommendationReplayService {
    private static final int MAX_CONTEXT_CHARS = 262_144;
    private static final int MAX_DECIMAL_CHARS = 32;
    private static final int MAX_TEXT_CHARS = 191;
    private static final String QUALITY_INSPECTION = "QualityInspection";
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?");
    private static final Pattern CANONICAL_NON_NEGATIVE_INTEGER = Pattern.compile("(?:0|[1-9]\\d*)");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "schemaVersion", "evaluatorId", "evaluatorVersion", "ruleBundleHash", "factoryId",
            "inspectionId", "productionBatchId", "inspectionDate", "passRate", "defectRate", "result",
            "qualityGrade", "sampleSize", "passCount", "failCount", "recordedRecommendation",
            "recordedConfidence", "rules", "snapshotHash");
    private static final Set<String> RULE_FIELDS = Set.of(
            "releasePassRateMin", "conditionalPassRateMin", "reworkPassRateMin",
            "scrapDefectRateMin", "nullPassRateAction");

    private final DecisionAuditLogRepository repository;
    private final ObjectMapper mapper;
    private final QualityRecommendationEvaluatorV1 evaluator = new QualityRecommendationEvaluatorV1();

    @Transactional(readOnly = true)
    public DecisionRecommendationReplay replay(String factoryId, String decisionId) {
        if (blank(factoryId) || blank(decisionId)) {
            throw new BusinessException(400, "工厂和决策ID不能为空");
        }
        DecisionAuditLog row = repository.findByIdAndFactoryId(decisionId, factoryId)
                .orElseThrow(() -> new EntityNotFoundException("决策记录不存在或不可访问"));
        return replay(row, factoryId);
    }

    private DecisionRecommendationReplay replay(DecisionAuditLog row, String factoryId) {
        if (row == null || !factoryId.equals(row.getFactoryId())
                || !QUALITY_INSPECTION.equals(row.getEntityType())
                || row.getDecisionType() == null
                || (row.getDecisionType() != DecisionAuditLog.DecisionType.RULE_EXECUTION
                && row.getDecisionType() != DecisionAuditLog.DecisionType.APPROVAL)) {
            return unavailable(row == null ? null : row.getId(), "UNSUPPORTED_DECISION_SOURCE");
        }
        if (!Boolean.TRUE.equals(row.getIsReplayable())) {
            return unavailable(row.getId(), "SNAPSHOT_NOT_REPLAYABLE");
        }
        if (blank(row.getInputContext()) || row.getInputContext().length() > MAX_CONTEXT_CHARS) {
            return unavailable(row.getId(), "SNAPSHOT_MISSING_OR_INVALID");
        }
        try {
            JsonNode context = mapper.readTree(row.getInputContext());
            JsonNode snapshot = context == null ? null : context.get(QualityRecommendationEvaluatorV1.SNAPSHOT_CONTEXT_KEY);
            Validation valid = validateSnapshot(row, snapshot, factoryId);
            if (!valid.valid()) {
                return unavailable(row.getId(), valid.reason());
            }
            String recorded = snapshot.get("recordedRecommendation").textValue();
            QualityRecommendationEvaluatorV1.Evaluation replayed = evaluator.evaluate(new QualityRecommendationEvaluatorV1.Input(
                    decimal(snapshot, "passRate"), decimal(snapshot, "defectRate"), text(snapshot, "result")));
            String replayedAction = replayed.recommendedAction().name();
            String recordedConfidence = text(snapshot, "recordedConfidence");
            String normalizedRecordedConfidence = decimalText(new BigDecimal(recordedConfidence));
            String replayedConfidence = decimalText(replayed.confidence());
            boolean same = recorded.equals(replayedAction)
                    && new BigDecimal(recordedConfidence).compareTo(replayed.confidence()) == 0;
            String reason = recorded.equals(replayedAction)
                    ? (same ? "RECORDED_RECOMMENDATION_MATCHES_REPLAY"
                    : "RECORDED_CONFIDENCE_DIFFERS_FROM_REPLAY")
                    : "RECORDED_RECOMMENDATION_DIFFERS_FROM_REPLAY";
            return new DecisionRecommendationReplay(row.getId(),
                    same ? DecisionRecommendationReplay.Status.REPLAY_MATCH
                            : DecisionRecommendationReplay.Status.REPLAY_DIFFERENT,
                    reason,
                    "QUALITY_RECOMMENDATION_ONLY", text(snapshot, "evaluatorId"),
                    text(snapshot, "evaluatorVersion"), text(snapshot, "ruleBundleHash"),
                    text(snapshot, "snapshotHash"), recorded, replayedAction,
                    normalizedRecordedConfidence, replayedConfidence,
                    true, true, false, false);
        } catch (JsonProcessingException | RuntimeException invalid) {
            return unavailable(row.getId(), "SNAPSHOT_MISSING_OR_INVALID");
        }
    }

    private Validation validateSnapshot(DecisionAuditLog row, JsonNode snapshot, String factoryId) {
        if (snapshot == null || !snapshot.isObject() || !exactKeys(snapshot, SNAPSHOT_FIELDS)) {
            return invalid("SNAPSHOT_MISSING_OR_INVALID");
        }
        if (!Objects.equals(QualityRecommendationEvaluatorV1.SNAPSHOT_SCHEMA_VERSION, text(snapshot, "schemaVersion"))
                || !Objects.equals(QualityRecommendationEvaluatorV1.EVALUATOR_ID, text(snapshot, "evaluatorId"))
                || !Objects.equals(QualityRecommendationEvaluatorV1.EVALUATOR_VERSION, text(snapshot, "evaluatorVersion"))
                || !Objects.equals(QualityRecommendationEvaluatorV1.RULE_BUNDLE_HASH, text(snapshot, "ruleBundleHash"))
                || !Objects.equals(factoryId, text(snapshot, "factoryId"))
                || !validProductionBatchId(text(snapshot, "productionBatchId"))
                || !validInspectionDate(text(snapshot, "inspectionDate"))
                || !hasDecimalFields(snapshot)
                || !validTextField(snapshot, "factoryId", false, 64)
                || !validTextField(snapshot, "inspectionId", false, MAX_TEXT_CHARS)
                || !validTextField(snapshot, "result", true, 20)
                || !validTextField(snapshot, "qualityGrade", true, 20)
                || !validRules(snapshot.get("rules"))
                || !validRecommendation(text(snapshot, "recordedRecommendation"))) {
            return invalid("SNAPSHOT_MISSING_OR_INVALID");
        }
        if (!Objects.equals(row.getEntityId(), text(snapshot, "inspectionId"))) {
            return invalid("BUSINESS_SCOPE_MISMATCH");
        }
        String storedHash = text(snapshot, "snapshotHash");
        if (blank(storedHash) || !storedHash.matches("(?i)[0-9a-f]{64}")) {
            return invalid("SNAPSHOT_HASH_INVALID");
        }
        ObjectNode body = (ObjectNode) snapshot.deepCopy();
        body.remove("snapshotHash");
        if (!storedHash.equalsIgnoreCase(QualityRecommendationEvaluatorV1.canonicalHash(body))) {
            return invalid("SNAPSHOT_HASH_MISMATCH");
        }
        return new Validation(true, null);
    }

    private boolean hasDecimalFields(JsonNode snapshot) {
        DecimalValue passRate = decimalField(snapshot, "passRate", true);
        DecimalValue defectRate = decimalField(snapshot, "defectRate", true);
        DecimalValue sampleSize = decimalField(snapshot, "sampleSize", false);
        DecimalValue passCount = decimalField(snapshot, "passCount", false);
        DecimalValue failCount = decimalField(snapshot, "failCount", false);
        DecimalValue confidence = decimalField(snapshot, "recordedConfidence", false);
        return passRate.valid() && defectRate.valid() && sampleSize.valid() && passCount.valid()
                && failCount.valid() && confidence.valid()
                && inRange(passRate.value(), BigDecimal.ZERO, new BigDecimal("100"))
                && inRange(defectRate.value(), BigDecimal.ZERO, new BigDecimal("100"))
                && validCount(sampleSize.value()) && validCount(passCount.value())
                && validCount(failCount.value())
                && inRange(confidence.value(), BigDecimal.ZERO, new BigDecimal("105"));
    }

    private boolean validRules(JsonNode rules) {
        return rules != null && rules.isObject()
                && exactKeys(rules, RULE_FIELDS)
                && "95.00".equals(text(rules, "releasePassRateMin"))
                && "85.00".equals(text(rules, "conditionalPassRateMin"))
                && "70.00".equals(text(rules, "reworkPassRateMin"))
                && "50.00".equals(text(rules, "scrapDefectRateMin"))
                && "HOLD".equals(text(rules, "nullPassRateAction"));
    }

    private static boolean validProductionBatchId(String value) {
        if (blank(value) || value.length() > 19 || !CANONICAL_NON_NEGATIVE_INTEGER.matcher(value).matches()) return false;
        try { return Long.parseLong(value) >= 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static boolean validInspectionDate(String value) {
        if (blank(value) || value.length() != 10) return false;
        try { LocalDate.parse(value); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private static BigDecimal decimal(JsonNode snapshot, String field) {
        String value = text(snapshot, field);
        return value == null ? null : new BigDecimal(value);
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static DecimalValue decimalField(JsonNode snapshot, String field, boolean nullable) {
        JsonNode value = snapshot.get(field);
        if (value == null || value.isNull()) return new DecimalValue(nullable, null);
        if (!value.isTextual()) return new DecimalValue(false, null);
        String raw = value.textValue();
        if (raw == null || raw.isBlank() || raw.length() > MAX_DECIMAL_CHARS
                || !PLAIN_DECIMAL.matcher(raw).matches()) {
            return new DecimalValue(false, null);
        }
        try {
            return new DecimalValue(true, new BigDecimal(raw));
        } catch (NumberFormatException invalid) {
            return new DecimalValue(false, null);
        }
    }

    private static boolean validCount(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.scale() <= 2 && value.precision() <= 10;
    }

    private static boolean inRange(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value == null || (value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0);
    }

    private static boolean validTextField(JsonNode object, String field, boolean nullable, int maxLength) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return nullable;
        return value.isTextual() && value.textValue() != null
                && value.textValue().length() <= maxLength && !value.textValue().isBlank();
    }

    private static boolean exactKeys(JsonNode object, Set<String> expected) {
        if (object == null || !object.isObject()) return false;
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static boolean validRecommendation(String value) {
        if (value == null) return false;
        try { com.cretas.aims.service.QualityDispositionRuleService.DispositionAction.valueOf(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.isTextual() ? value.textValue() : null;
    }

    private static DecisionRecommendationReplay unavailable(String id, String reason) {
        return DecisionRecommendationReplay.unavailable(id, reason);
    }

    private static Validation invalid(String reason) { return new Validation(false, reason); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record Validation(boolean valid, String reason) { }

    private record DecimalValue(boolean valid, BigDecimal value) { }
}
