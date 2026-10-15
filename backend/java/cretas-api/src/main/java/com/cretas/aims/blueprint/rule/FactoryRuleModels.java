package com.cretas.aims.blueprint.rule;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Wire and evaluation contracts for the controlled {@code factory-rule/v1} preview DSL. */
public final class FactoryRuleModels {

    public static final String SCHEMA_VERSION = "factory-rule/v1";
    public static final String COMPILER_VERSION = "factory-rule-compiler/v1";

    private FactoryRuleModels() {
    }

    public enum RuleKind {
        ROUTE_GUARD,
        QUALITY_DECISION,
        CRITICAL_LIMIT,
        CALCULATION,
        ALLOCATION,
        ALERT_TRIGGER
    }

    public enum ValueType {
        BOOLEAN,
        DECIMAL,
        QUANTITY,
        DURATION,
        STRING,
        ENUM,
        DATETIME,
        INTEGER
    }

    public enum Operator {
        VAR,
        CONST,
        ALL,
        ANY,
        NOT,
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        BETWEEN,
        IN,
        IS_KNOWN,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        MIN,
        MAX,
        ABS,
        ROUND,
        WITHIN,
        CONSECUTIVE_COUNT,
        WINDOW_AGGREGATE
    }

    public enum AggregateFunction {
        SUM,
        AVG,
        MIN,
        MAX,
        COUNT
    }

    public enum TriState {
        TRUE,
        FALSE,
        UNKNOWN
    }

    public enum UnknownPolicy {
        HOLD_FOR_REVIEW,
        BLOCK,
        DISPLAY_PARTIAL
    }

    public enum IssueSeverity {
        BLOCKING,
        WARNING
    }

    public record VariableDefinition(
            String id,
            ValueType type,
            String unit,
            boolean required,
            List<String> allowedValues,
            String sourceRef) {
        public VariableDefinition {
            id = clean(id);
            unit = clean(unit);
            allowedValues = immutable(allowedValues);
            sourceRef = clean(sourceRef);
        }
    }

    public record VariableCatalog(
            String factoryId,
            String version,
            String digest,
            List<VariableDefinition> definitions) {
        public VariableCatalog {
            factoryId = clean(factoryId);
            version = clean(version);
            digest = clean(digest);
            definitions = immutable(definitions);
        }
    }

    /**
     * Closed AST node. The op enum is the executable surface; unlisted operations cannot be
     * represented. Fields not used by the selected op must remain empty and are rejected by the
     * validator when they could make interpretation ambiguous.
     */
    public record Expression(
            Operator op,
            List<Expression> args,
            Expression left,
            Expression right,
            Expression value,
            Expression lower,
            Expression upper,
            List<Expression> values,
            String variableId,
            String constantValue,
            ValueType valueType,
            String unit,
            Integer scale,
            RoundingMode roundingMode,
            AggregateFunction aggregate,
            Duration window,
            Integer count,
            Boolean inclusiveLower,
            Boolean inclusiveUpper,
            String evidenceRef,
            String effectiveVersion) {
        public Expression {
            args = immutable(args);
            values = immutable(values);
            variableId = clean(variableId);
            constantValue = clean(constantValue);
            unit = clean(unit);
            evidenceRef = clean(evidenceRef);
            effectiveVersion = clean(effectiveVersion);
        }
    }

    public record Observation(Instant at, String value, String unit) {
        public Observation {
            value = clean(value);
            unit = clean(unit);
        }
    }

    public record InputValue(
            ValueType type,
            String value,
            String unit,
            List<Observation> observations) {
        public InputValue {
            value = clean(value);
            unit = clean(unit);
            observations = immutable(observations);
        }
    }

    public record TestVector(
            String id,
            Map<String, InputValue> inputs,
            String expected,
            Instant evaluationTime,
            List<String> evidenceRefs) {
        public TestVector {
            id = clean(id);
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            expected = clean(expected);
            evidenceRefs = immutable(evidenceRefs);
        }
    }

    public record RuleDefinition(
            String schemaVersion,
            String factoryId,
            String ruleId,
            RuleKind kind,
            ValueType resultType,
            Expression expression,
            UnknownPolicy unknownPolicy,
            String variableCatalogVersion,
            String variableCatalogDigest,
            String unitCatalogDigest,
            String compilerVersion,
            List<String> evidenceRefs,
            List<TestVector> testVectors) {
        public RuleDefinition {
            schemaVersion = clean(schemaVersion);
            factoryId = clean(factoryId);
            ruleId = clean(ruleId);
            variableCatalogVersion = clean(variableCatalogVersion);
            variableCatalogDigest = clean(variableCatalogDigest);
            unitCatalogDigest = clean(unitCatalogDigest);
            compilerVersion = clean(compilerVersion);
            evidenceRefs = immutable(evidenceRefs);
            testVectors = immutable(testVectors);
        }
    }

    public record ValidationIssue(
            String code,
            IssueSeverity severity,
            String path,
            String message,
            String recovery) {
        public ValidationIssue {
            code = clean(code);
            severity = severity == null ? IssueSeverity.BLOCKING : severity;
            path = clean(path);
            message = clean(message);
            recovery = clean(recovery);
        }
    }

    public record ValidationReport(
            boolean valid,
            ValueType inferredResultType,
            int nodeCount,
            int maxDepth,
            List<ValidationIssue> issues,
            int businessWriteCount) {
        public ValidationReport {
            issues = immutable(issues);
            businessWriteCount = 0;
        }
    }

    public record EvaluationValue(
            ValueType type,
            String value,
            String unit,
            TriState truth,
            List<String> unknownReasons) {
        public EvaluationValue {
            value = clean(value);
            unit = clean(unit);
            truth = truth == null ? TriState.UNKNOWN : truth;
            unknownReasons = immutable(unknownReasons);
        }

        public boolean unknown() {
            return truth == TriState.UNKNOWN && value.isBlank();
        }

        public String comparableResult() {
            return type == ValueType.BOOLEAN ? truth.name() : unknown() ? "UNKNOWN" : value;
        }
    }

    public record SimulationResult(
            String status,
            EvaluationValue result,
            UnknownPolicy appliedUnknownPolicy,
            String disposition,
            List<String> trace,
            int businessWriteCount,
            String runtimeBindingState) {
        public SimulationResult {
            status = clean(status);
            disposition = clean(disposition);
            trace = immutable(trace);
            businessWriteCount = 0;
            runtimeBindingState = "NOT_BOUND_TO_RUNTIME";
        }
    }

    public record TestVectorResult(
            String id,
            String expected,
            String actual,
            boolean passed,
            String message) {
        public TestVectorResult {
            id = clean(id);
            expected = clean(expected);
            actual = clean(actual);
            message = clean(message);
        }
    }

    public record RulePreview(
            String schemaVersion,
            String ruleId,
            String contentHash,
            String status,
            ValidationReport validation,
            List<TestVectorResult> testVectorResults,
            String publicationState,
            String activationState,
            String runtimeBindingState,
            int businessWriteCount) {
        public RulePreview {
            schemaVersion = SCHEMA_VERSION;
            ruleId = clean(ruleId);
            contentHash = clean(contentHash);
            status = clean(status);
            testVectorResults = immutable(testVectorResults);
            publicationState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            runtimeBindingState = "NOT_BOUND_TO_RUNTIME";
            businessWriteCount = 0;
        }
    }

    static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
