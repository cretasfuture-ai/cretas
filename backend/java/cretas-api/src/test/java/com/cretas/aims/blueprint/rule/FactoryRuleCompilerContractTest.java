package com.cretas.aims.blueprint.rule;

import com.cretas.aims.blueprint.rule.FactoryRuleModels.AggregateFunction;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.InputValue;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Observation;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Operator;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleDefinition;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleKind;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.TestVector;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.TriState;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.UnknownPolicy;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValueType;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableCatalog;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableDefinition;
import com.cretas.aims.service.unit.UnitDimension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.cretas.aims.blueprint.rule.FactoryRuleExpressions.binary;
import static com.cretas.aims.blueprint.rule.FactoryRuleExpressions.constant;
import static com.cretas.aims.blueprint.rule.FactoryRuleExpressions.consecutiveCount;
import static com.cretas.aims.blueprint.rule.FactoryRuleExpressions.logical;
import static com.cretas.aims.blueprint.rule.FactoryRuleExpressions.variable;
import static com.cretas.aims.blueprint.rule.FactoryRuleExpressions.windowAggregate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactoryRuleCompilerContractTest {

    private static final Instant NOW = Instant.parse("2026-08-31T04:00:00Z");
    private final TestUnits units = new TestUnits();
    private final FactoryRuleCompiler compiler = new FactoryRuleCompiler(units);

    @Test void inputAliasReturnsTheDeclaredCanonicalOutputUnit() {
        var rule=calculationRule(variable("material.mass",ValueType.QUANTITY,"kg"));
        var result=compiler.simulate(rule,catalog(),Map.of(
                "material.mass",input(ValueType.QUANTITY,"2","公斤")),NOW);
        assertThat(result.status()).isEqualTo("EVALUATED");
        assertThat(result.result().unit()).isEqualTo("kg");
        assertThat(result.result().value()).isEqualTo("2");
    }

    @Test
    void criticalLimitCompilesWithTypicalBoundaryAndMissingVectors() {
        RuleDefinition rule = criticalLimitRule();

        var preview = compiler.compile(rule, catalog());

        assertThat(preview.status()).isEqualTo("PREVIEW_READY");
        assertThat(preview.validation().valid()).isTrue();
        assertThat(preview.testVectorResults()).hasSize(3).allMatch(result -> result.passed());
        assertThat(preview.contentHash()).hasSize(64)
                .isEqualTo(compiler.compile(rule, catalog()).contentHash());
        assertThat(preview.publicationState()).isEqualTo("NOT_PUBLISHED");
        assertThat(preview.activationState()).isEqualTo("NOT_ACTIVATED");
        assertThat(preview.runtimeBindingState()).isEqualTo("NOT_BOUND_TO_RUNTIME");
        assertThat(preview.businessWriteCount()).isZero();
    }

    @Test
    void missingInputIsUnknownAndRoutesToHumanHoldInsteadOfFalse() {
        RuleDefinition rule = criticalLimitRule();

        var result = compiler.simulate(rule, catalog(), Map.of(
                "process.holdDuration", input(ValueType.DURATION, "60", "s")), NOW);

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.result().truth()).isEqualTo(TriState.UNKNOWN);
        assertThat(result.result().unknownReasons()).contains("MISSING_INPUT:process.coreTemperature");
        assertThat(result.disposition()).isEqualTo("HOLD_FOR_REVIEW");
        assertThat(result.businessWriteCount()).isZero();
    }

    @Test
    void compatibleUnitsAreConvertedButIncompatibleDimensionsFailValidation() {
        RuleDefinition converted = calculationRule(binary(Operator.ADD,
                variable("material.mass", ValueType.QUANTITY, "kg"),
                constant("500", ValueType.QUANTITY, "g")));
        var convertedPreview = compiler.compile(converted, catalog());
        var convertedResult = compiler.simulate(converted, catalog(), Map.of(
                "material.mass", input(ValueType.QUANTITY, "1", "kg")), NOW);

        assertThat(convertedPreview.validation().valid()).isTrue();
        assertThat(convertedResult.result().value()).isEqualTo("1.5");
        assertThat(convertedResult.result().unit()).isEqualTo("kg");

        RuleDefinition incompatible = calculationRule(binary(Operator.ADD,
                variable("material.mass", ValueType.QUANTITY, "kg"),
                constant("1", ValueType.QUANTITY, "L")));
        assertThat(compiler.compile(incompatible, catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("INCOMPATIBLE_UNITS");
    }

    @Test
    void divideByZeroUnsafeUnknownPolicyAndMissingLimitEvidenceAreBlocked() {
        RuleDefinition divideByZero = calculationRule(FactoryRuleExpressions.divide(
                variable("material.mass", ValueType.QUANTITY, "kg"),
                constant("0", ValueType.DECIMAL, ""), 4, RoundingMode.HALF_UP));
        assertThat(compiler.compile(divideByZero, catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("STATIC_DIVIDE_BY_ZERO");

        RuleDefinition unsafe = copy(criticalLimitRule(), UnknownPolicy.DISPLAY_PARTIAL,
                List.of(), criticalLimitRule().expression());
        assertThat(compiler.compile(unsafe, catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("UNSAFE_UNKNOWN_POLICY", "SAFETY_EVIDENCE_REQUIRED");
    }

    @Test
    void catalogDriftAndUnreviewableSafetyVectorsAreBlocked() {
        RuleDefinition source = criticalLimitRule();
        RuleDefinition drifted = new RuleDefinition(source.schemaVersion(), source.factoryId(), source.ruleId(),
                source.kind(), source.resultType(), source.expression(), source.unknownPolicy(),
                source.variableCatalogVersion(), source.variableCatalogDigest(), "stale-unit-digest",
                source.compilerVersion(), source.evidenceRefs(), source.testVectors());
        var noEvidence = new TestVector("no-evidence", source.testVectors().getFirst().inputs(),
                "TRUE", NOW, List.of());
        RuleDefinition underSpecified = new RuleDefinition(source.schemaVersion(), source.factoryId(), source.ruleId(),
                source.kind(), source.resultType(), source.expression(), source.unknownPolicy(),
                source.variableCatalogVersion(), source.variableCatalogDigest(), source.unitCatalogDigest(),
                source.compilerVersion(), source.evidenceRefs(), List.of(noEvidence));

        assertThat(compiler.compile(drifted, catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("UNIT_CATALOG_DRIFT");
        assertThat(compiler.compile(underSpecified, catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("TEST_VECTOR_COVERAGE_REQUIRED", "TEST_VECTOR_EVIDENCE_REQUIRED",
                        "UNKNOWN_TEST_VECTOR_REQUIRED");
    }

    @Test
    void timeWindowAggregateAndConsecutiveCountAreDeterministic() {
        var average = windowAggregate(variable("storage.temperature", ValueType.QUANTITY, "Cel"),
                AggregateFunction.AVG, Duration.ofMinutes(10), 100);
        RuleDefinition averageRule = calculationRule(average);
        var averageResult = compiler.simulate(averageRule, catalog(), Map.of(
                "storage.temperature", new InputValue(ValueType.QUANTITY, "", "Cel", List.of(
                        observation(-8, "6", "Cel"), observation(-4, "8", "Cel"), observation(-20, "99", "Cel")))), NOW);
        assertThat(averageResult.result().value()).isEqualTo("7");

        var consecutive = consecutiveCount(variable("storage.overLimit", ValueType.BOOLEAN, ""),
                3, Duration.ofMinutes(10));
        RuleDefinition alert = alertRule(consecutive);
        var alertResult = compiler.simulate(alert, catalog(), Map.of(
                "storage.overLimit", new InputValue(ValueType.BOOLEAN, "", "", List.of(
                        observation(-3, "TRUE", ""), observation(-2, "TRUE", ""),
                        observation(-1, "TRUE", "")))), NOW);
        assertThat(alertResult.result().truth()).isEqualTo(TriState.TRUE);
    }

    @Test
    void complexityAndUnregisteredVariablesFailClosed() {
        var expression = variable("unknown.variable", ValueType.DECIMAL, "");
        for (int index = 0; index < FactoryRuleValidator.MAX_DEPTH + 2; index++) {
            expression = FactoryRuleExpressions.unary(Operator.ABS, expression);
        }
        RuleDefinition rule = calculationRule(expression);

        assertThat(compiler.compile(rule, catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("UNREGISTERED_VARIABLE", "RULE_TOO_DEEP");
    }

    @Test
    void simulatorRefusesInvalidAstBeforeEvaluation() {
        RuleDefinition invalid = calculationRule(
                variable("unknown.variable", ValueType.QUANTITY, "kg"));

        var result = compiler.simulate(invalid, catalog(), Map.of(
                "unknown.variable", input(ValueType.QUANTITY, "3", "kg")), NOW);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.disposition()).isEqualTo("VALIDATION_BLOCKED");
        assertThat(result.trace()).contains("UNREGISTERED_VARIABLE");
        assertThat(result.businessWriteCount()).isZero();
        assertThat(result.runtimeBindingState()).isEqualTo("NOT_BOUND_TO_RUNTIME");
    }

    @Test
    void wireContractRejectsRawSpelAndArbitraryOperations() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String rawSpel = """
                {"schemaVersion":"factory-rule/v1","factoryId":"F999","ruleId":"bad-rule",
                 "kind":"ROUTE_GUARD","resultType":"BOOLEAN","rawSpel":"T(java.lang.Runtime).exec('x')"}
                """;
        String arbitraryOp = """
                {"op":"invoke","variableId":"process.temperature"}
                """;

        assertThatThrownBy(() -> mapper.readValue(rawSpel, RuleDefinition.class))
                .hasMessageContaining("rawSpel");
        assertThatThrownBy(() -> mapper.readValue(arbitraryOp, FactoryRuleModels.Expression.class))
                .hasMessageContaining("invoke");
    }

    @Test
    void validatorRejectsFieldsThatDoNotBelongToTheSelectedOperation() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var ambiguous = mapper.readValue("""
                {"op":"VAR","variableId":"material.mass","valueType":"QUANTITY","unit":"kg",
                 "constantValue":"unexpected"}
                """, FactoryRuleModels.Expression.class);

        assertThat(compiler.compile(calculationRule(ambiguous), catalog()).validation().issues())
                .extracting(FactoryRuleModels.ValidationIssue::code)
                .contains("AMBIGUOUS_NODE_FIELDS");
    }

    private RuleDefinition criticalLimitRule() {
        var expression = logical(Operator.ALL,
                binary(Operator.GTE,
                        variable("process.coreTemperature", ValueType.QUANTITY, "Cel"),
                        constant("75", ValueType.QUANTITY, "Cel", "fact://limit/temp", "2026-08-31")),
                binary(Operator.GTE,
                        variable("process.holdDuration", ValueType.DURATION, "s"),
                        constant("60", ValueType.DURATION, "s", "fact://limit/time", "2026-08-31")));
        return new RuleDefinition("factory-rule/v1", "F999", "lsm-cook-release",
                RuleKind.CRITICAL_LIMIT, ValueType.BOOLEAN, expression, UnknownPolicy.HOLD_FOR_REVIEW,
                "variables/v1", "variables-digest", "units-digest", "factory-rule-compiler/v1",
                List.of("fact://critical-limit/confirmed"), List.of(
                vector("typical-pass", Map.of(
                        "process.coreTemperature", input(ValueType.QUANTITY, "75", "Cel"),
                        "process.holdDuration", input(ValueType.DURATION, "60", "s")), "TRUE"),
                vector("boundary-fail", Map.of(
                        "process.coreTemperature", input(ValueType.QUANTITY, "74.9", "Cel"),
                        "process.holdDuration", input(ValueType.DURATION, "60", "s")), "FALSE"),
                vector("missing-input", Map.of(
                        "process.holdDuration", input(ValueType.DURATION, "60", "s")), "UNKNOWN")));
    }

    private RuleDefinition calculationRule(FactoryRuleModels.Expression expression) {
        ValueType resultType = expression.op() == Operator.WINDOW_AGGREGATE
                ? expression.left().valueType() : ValueType.QUANTITY;
        return new RuleDefinition("factory-rule/v1", "F999", "material-calculation",
                RuleKind.CALCULATION, resultType, expression, UnknownPolicy.DISPLAY_PARTIAL,
                "variables/v1", "variables-digest", "units-digest", "factory-rule-compiler/v1",
                List.of("fact://calculation"), List.of(vector("preview", Map.of(
                "material.mass", input(ValueType.QUANTITY, "1", "kg"),
                "storage.temperature", input(ValueType.QUANTITY, "7", "Cel")),
                expression.op() == Operator.WINDOW_AGGREGATE ? "UNKNOWN" : "1.5")));
    }

    private RuleDefinition alertRule(FactoryRuleModels.Expression expression) {
        return new RuleDefinition("factory-rule/v1", "F999", "cold-storage-alert",
                RuleKind.ALERT_TRIGGER, ValueType.BOOLEAN, expression, UnknownPolicy.HOLD_FOR_REVIEW,
                "variables/v1", "variables-digest", "units-digest", "factory-rule-compiler/v1",
                List.of("fact://alert"), List.of(
                vector("three", Map.of("storage.overLimit", new InputValue(ValueType.BOOLEAN, "", "", List.of(
                        observation(-3, "TRUE", ""), observation(-2, "TRUE", ""), observation(-1, "TRUE", "")))), "TRUE"),
                vector("two", Map.of("storage.overLimit", new InputValue(ValueType.BOOLEAN, "", "", List.of(
                        observation(-2, "TRUE", ""), observation(-1, "TRUE", "")))), "FALSE"),
                vector("missing", Map.of(), "UNKNOWN")));
    }

    private RuleDefinition copy(
            RuleDefinition source, UnknownPolicy policy, List<String> evidence,
            FactoryRuleModels.Expression expression) {
        return new RuleDefinition(source.schemaVersion(), source.factoryId(), source.ruleId(), source.kind(),
                source.resultType(), expression, policy, source.variableCatalogVersion(),
                source.variableCatalogDigest(), source.unitCatalogDigest(), source.compilerVersion(),
                evidence, source.testVectors());
    }

    private VariableCatalog catalog() {
        return new VariableCatalog("F999", "variables/v1", "variables-digest", List.of(
                variableDefinition("process.coreTemperature", ValueType.QUANTITY, "Cel"),
                variableDefinition("process.holdDuration", ValueType.DURATION, "s"),
                variableDefinition("material.mass", ValueType.QUANTITY, "kg"),
                variableDefinition("storage.temperature", ValueType.QUANTITY, "Cel"),
                variableDefinition("storage.overLimit", ValueType.BOOLEAN, "")));
    }

    private VariableDefinition variableDefinition(String id, ValueType type, String unit) {
        return new VariableDefinition(id, type, unit, true, List.of(), "schema://" + id);
    }

    private TestVector vector(String id, Map<String, InputValue> inputs, String expected) {
        return new TestVector(id, inputs, expected, NOW, List.of("fact://vector/" + id));
    }

    private InputValue input(ValueType type, String value, String unit) {
        return new InputValue(type, value, unit, List.of());
    }

    private Observation observation(long minutes, String value, String unit) {
        return new Observation(NOW.plus(Duration.ofMinutes(minutes)), value, unit);
    }

    private static final class TestUnits implements FactoryRuleUnitResolver {
        private final Map<String, UnitDescriptor> catalog = Map.of(
                "kg", new UnitDescriptor("kg", UnitDimension.MASS),
                "g", new UnitDescriptor("g", UnitDimension.MASS),
                "L", new UnitDescriptor("L", UnitDimension.VOLUME),
                "s", new UnitDescriptor("s", UnitDimension.TIME),
                "min", new UnitDescriptor("min", UnitDimension.TIME),
                "Cel", new UnitDescriptor("Cel", UnitDimension.TEMPERATURE));
        private final Map<String, BigDecimal> factors = Map.of(
                "kg", BigDecimal.valueOf(1000), "g", BigDecimal.ONE,
                "L", BigDecimal.ONE, "s", BigDecimal.ONE,
                "min", BigDecimal.valueOf(60), "Cel", BigDecimal.ONE);

        @Override
        public String catalogDigest(String factoryId) {
            return "units-digest";
        }

        @Override
        public Optional<UnitDescriptor> describe(String factoryId, String unit) {
            if ("公斤".equals(unit)) return Optional.of(catalog.get("kg"));
            return Optional.ofNullable(catalog.get(unit));
        }

        @Override
        public Conversion convert(
                String factoryId, BigDecimal value, String fromUnit, String toUnit, Instant at) {
            UnitDescriptor from = catalog.get(fromUnit);
            UnitDescriptor to = catalog.get(toUnit);
            if (from == null || to == null) return Conversion.failure("UNKNOWN_UNIT", "unknown unit");
            if (from.dimension() != to.dimension()) return Conversion.failure("INCOMPATIBLE_DIMENSION", "dimension mismatch");
            return Conversion.success(value.multiply(factors.get(fromUnit))
                    .divide(factors.get(toUnit), 12, RoundingMode.HALF_UP).stripTrailingZeros(), toUnit);
        }
    }
}
