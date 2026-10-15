package com.cretas.aims.blueprint.rule;

import com.cretas.aims.blueprint.rule.FactoryRuleModels.AggregateFunction;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.EvaluationValue;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Expression;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.InputValue;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Observation;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleDefinition;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.SimulationResult;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.TriState;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.UnknownPolicy;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValueType;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableCatalog;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableDefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, isolated evaluator. It has no script engine, reflection, network, database or write API. */
public final class FactoryRuleSimulator {

    private final FactoryRuleUnitResolver units;

    public FactoryRuleSimulator(FactoryRuleUnitResolver units) {
        this.units = units;
    }

    public SimulationResult simulate(
            RuleDefinition rule,
            VariableCatalog catalog,
            Map<String, InputValue> inputs,
            Instant evaluationTime) {
        Map<String, VariableDefinition> variables = new LinkedHashMap<>();
        catalog.definitions().forEach(definition -> variables.put(definition.id(), definition));
        EvalContext context = new EvalContext(rule.factoryId(), variables,
                inputs == null ? Map.of() : Map.copyOf(inputs), evaluationTime, new ArrayList<>());
        Eval evaluated;
        try {
            evaluated = evaluate(rule.expression(), context);
        } catch (RuntimeException failure) {
            evaluated = Eval.unknown(rule.resultType(), "EVALUATION_ERROR:" + failure.getClass().getSimpleName());
        }
        EvaluationValue result = evaluated.toWire();
        boolean unknown = evaluated.unknown;
        String status = unknown ? "UNKNOWN" : "EVALUATED";
        String disposition = unknown ? unknownDisposition(rule.unknownPolicy()) : "NO_WRITE_PREVIEW";
        return new SimulationResult(status, result, rule.unknownPolicy(), disposition,
                context.trace, 0, "NOT_BOUND_TO_RUNTIME");
    }

    private Eval evaluate(Expression expression, EvalContext context) {
        context.trace.add(expression.op().name());
        return switch (expression.op()) {
            case VAR -> variable(expression, context);
            case CONST -> constant(expression);
            case ALL -> logicalAll(expression.args(), context);
            case ANY -> logicalAny(expression.args(), context);
            case NOT -> negate(evaluate(expression.value(), context));
            case EQ, NE, GT, GTE, LT, LTE -> compare(expression, context);
            case BETWEEN -> between(expression, context);
            case IN -> in(expression, context);
            case IS_KNOWN -> Eval.truth(!evaluate(expression.value(), context).unknown);
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MIN, MAX -> arithmetic(expression, context);
            case ABS -> numericUnary(expression, context, false);
            case ROUND -> numericUnary(expression, context, true);
            case WITHIN -> within(expression, context);
            case CONSECUTIVE_COUNT -> consecutive(expression, context);
            case WINDOW_AGGREGATE -> aggregate(expression, context);
        };
    }

    private Eval variable(Expression expression, EvalContext context) {
        VariableDefinition definition = context.variables.get(expression.variableId());
        InputValue input = context.inputs.get(expression.variableId());
        if (definition == null) return Eval.unknown(expression.valueType(), "UNREGISTERED_VARIABLE:" + expression.variableId());
        if (input == null || (input.value().isBlank() && input.observations().isEmpty())) {
            return Eval.unknown(definition.type(), "MISSING_INPUT:" + definition.id());
        }
        if (input.type() != definition.type()) return Eval.unknown(definition.type(), "INPUT_TYPE_MISMATCH:" + definition.id());
        if (definition.type() == ValueType.ENUM && !definition.allowedValues().contains(input.value())) {
            return Eval.unknown(definition.type(), "ENUM_VALUE_NOT_ALLOWED:" + definition.id());
        }
        if (input.value().isBlank()) return Eval.series(definition.type(), definition.unit(), input.observations());
        try {
            Eval parsed = parse(definition.type(), input.value(),
                    input.unit().isBlank() ? definition.unit() : input.unit(), input.observations());
            return parsed.number != null && !definition.unit().isBlank()
                    ? align(parsed, definition.unit(), context.factoryId, context.evaluationTime) : parsed;
        } catch (RuntimeException invalid) {
            return Eval.unknown(definition.type(), "INPUT_PARSE_FAILED:" + definition.id());
        }
    }

    private Eval constant(Expression expression) {
        try {
            return parse(expression.valueType(), expression.constantValue(), expression.unit(), List.of());
        } catch (RuntimeException invalid) {
            return Eval.unknown(expression.valueType(), "CONSTANT_PARSE_FAILED");
        }
    }

    private Eval parse(ValueType type, String value, String unit, List<Observation> observations) {
        return switch (type) {
            case BOOLEAN -> {
                TriState state = TriState.valueOf(value.toUpperCase());
                yield state == TriState.UNKNOWN ? Eval.unknown(ValueType.BOOLEAN, "EXPLICIT_UNKNOWN")
                        : Eval.truth(state == TriState.TRUE);
            }
            case DECIMAL, QUANTITY, DURATION, INTEGER -> Eval.number(type, new BigDecimal(value), unit, observations);
            case DATETIME -> Eval.dateTime(Instant.parse(value));
            case STRING, ENUM -> Eval.text(type, value);
        };
    }

    private Eval logicalAll(List<Expression> args, EvalContext context) {
        boolean unknown = false;
        List<String> reasons = new ArrayList<>();
        for (Expression arg : args) {
            Eval value = evaluate(arg, context);
            if (!value.unknown && value.truth == TriState.FALSE) return Eval.truth(false);
            if (value.unknown) {
                unknown = true;
                reasons.addAll(value.reasons);
            }
        }
        return unknown ? Eval.unknown(ValueType.BOOLEAN, reasons) : Eval.truth(true);
    }

    private Eval logicalAny(List<Expression> args, EvalContext context) {
        boolean unknown = false;
        List<String> reasons = new ArrayList<>();
        for (Expression arg : args) {
            Eval value = evaluate(arg, context);
            if (!value.unknown && value.truth == TriState.TRUE) return Eval.truth(true);
            if (value.unknown) {
                unknown = true;
                reasons.addAll(value.reasons);
            }
        }
        return unknown ? Eval.unknown(ValueType.BOOLEAN, reasons) : Eval.truth(false);
    }

    private Eval negate(Eval value) {
        if (value.unknown) return Eval.unknown(ValueType.BOOLEAN, value.reasons);
        return Eval.truth(value.truth != TriState.TRUE);
    }

    private Eval compare(Expression expression, EvalContext context) {
        Eval left = evaluate(expression.left(), context);
        Eval right = evaluate(expression.right(), context);
        if (left.unknown || right.unknown) return unknownBoolean(left, right);
        int comparison = compareValues(left, right, context.factoryId, context.evaluationTime);
        boolean result = switch (expression.op()) {
            case EQ -> comparison == 0;
            case NE -> comparison != 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            default -> throw new IllegalStateException("not a comparison");
        };
        return Eval.truth(result);
    }

    private Eval between(Expression expression, EvalContext context) {
        Eval value = evaluate(expression.value(), context);
        Eval lower = evaluate(expression.lower(), context);
        Eval upper = evaluate(expression.upper(), context);
        if (value.unknown || lower.unknown || upper.unknown) return unknownBoolean(value, lower, upper);
        int lowerComparison = compareValues(value, lower, context.factoryId, context.evaluationTime);
        int upperComparison = compareValues(value, upper, context.factoryId, context.evaluationTime);
        boolean lowerOk = Boolean.TRUE.equals(expression.inclusiveLower()) ? lowerComparison >= 0 : lowerComparison > 0;
        boolean upperOk = Boolean.TRUE.equals(expression.inclusiveUpper()) ? upperComparison <= 0 : upperComparison < 0;
        return Eval.truth(lowerOk && upperOk);
    }

    private Eval in(Expression expression, EvalContext context) {
        Eval value = evaluate(expression.value(), context);
        if (value.unknown) return Eval.unknown(ValueType.BOOLEAN, value.reasons);
        boolean sawUnknown = false;
        List<String> reasons = new ArrayList<>();
        for (Expression option : expression.values()) {
            Eval candidate = evaluate(option, context);
            if (candidate.unknown) {
                sawUnknown = true;
                reasons.addAll(candidate.reasons);
            } else if (compareValues(value, candidate, context.factoryId, context.evaluationTime) == 0) return Eval.truth(true);
        }
        return sawUnknown ? Eval.unknown(ValueType.BOOLEAN, reasons) : Eval.truth(false);
    }

    private Eval arithmetic(Expression expression, EvalContext context) {
        Eval left = evaluate(expression.left(), context);
        Eval right = evaluate(expression.right(), context);
        if (left.unknown || right.unknown) return unknownNumeric(left, right);
        Eval alignedRight = align(right, left.unit, context.factoryId, context.evaluationTime);
        if (alignedRight.unknown && SetLike.sameUnitRequired(expression.op())) return alignedRight;
        BigDecimal l = left.number;
        BigDecimal r = SetLike.sameUnitRequired(expression.op()) ? alignedRight.number : right.number;
        ValueType resultType = left.type;
        String resultUnit = left.unit;
        BigDecimal result;
        switch (expression.op()) {
            case ADD -> result = l.add(r);
            case SUBTRACT -> result = l.subtract(r);
            case MIN -> result = l.min(r);
            case MAX -> result = l.max(r);
            case MULTIPLY -> {
                result = l.multiply(r);
                if (left.type == ValueType.DECIMAL) {
                    resultType = right.type;
                    resultUnit = right.unit;
                }
            }
            case DIVIDE -> {
                if (r.compareTo(BigDecimal.ZERO) == 0) return Eval.unknown(left.type, "DIVIDE_BY_ZERO");
                if (left.type == ValueType.QUANTITY && right.type == ValueType.QUANTITY) {
                    Eval denominator = align(right, left.unit, context.factoryId, context.evaluationTime);
                    if (denominator.unknown) return denominator;
                    r = denominator.number;
                    resultType = ValueType.DECIMAL;
                    resultUnit = "";
                }
                result = l.divide(r, expression.scale(), expression.roundingMode()).stripTrailingZeros();
            }
            default -> throw new IllegalStateException("not arithmetic");
        }
        return Eval.number(resultType, result, resultUnit, List.of());
    }

    private Eval numericUnary(Expression expression, EvalContext context, boolean round) {
        Eval value = evaluate(expression.value(), context);
        if (value.unknown) return value;
        BigDecimal result = round
                ? value.number.setScale(expression.scale(), expression.roundingMode())
                : value.number.abs();
        return Eval.number(value.type, result, value.unit, List.of());
    }

    private Eval within(Expression expression, EvalContext context) {
        Eval dateTime = evaluate(expression.left(), context);
        Eval duration = evaluate(expression.right(), context);
        if (dateTime.unknown || duration.unknown) return unknownBoolean(dateTime, duration);
        Eval seconds = align(duration, "s", context.factoryId, context.evaluationTime);
        if (seconds.unknown) return Eval.unknown(ValueType.BOOLEAN, seconds.reasons);
        Instant lower = context.evaluationTime.minusMillis(seconds.number.multiply(BigDecimal.valueOf(1000)).longValue());
        return Eval.truth(!dateTime.instant.isBefore(lower) && !dateTime.instant.isAfter(context.evaluationTime));
    }

    private Eval consecutive(Expression expression, EvalContext context) {
        InputValue input = seriesInput(expression.left(), context);
        if (input == null || input.observations().isEmpty()) return Eval.unknown(ValueType.BOOLEAN, "MISSING_SERIES");
        Instant lower = context.evaluationTime.minus(expression.window());
        List<Observation> observations = input.observations().stream()
                .filter(item -> item.at() != null && !item.at().isBefore(lower) && !item.at().isAfter(context.evaluationTime))
                .sorted(Comparator.comparing(Observation::at).reversed()).toList();
        int consecutive = 0;
        for (Observation observation : observations) {
            if (!"TRUE".equalsIgnoreCase(observation.value())) break;
            consecutive++;
        }
        return Eval.truth(consecutive >= expression.count());
    }

    private Eval aggregate(Expression expression, EvalContext context) {
        InputValue input = seriesInput(expression.left(), context);
        if (input == null || input.observations().isEmpty()) return Eval.unknown(
                expression.aggregate() == AggregateFunction.COUNT ? ValueType.INTEGER : expression.left().valueType(),
                "MISSING_SERIES");
        Instant lower = context.evaluationTime.minus(expression.window());
        List<Observation> observations = input.observations().stream()
                .filter(item -> item.at() != null && !item.at().isBefore(lower) && !item.at().isAfter(context.evaluationTime))
                .sorted(Comparator.comparing(Observation::at)).toList();
        if (observations.isEmpty()) return Eval.unknown(expression.left().valueType(), "EMPTY_WINDOW");
        if (observations.size() > expression.count()) return Eval.unknown(expression.left().valueType(),
                "SAMPLE_LIMIT_EXCEEDED:" + expression.count());
        if (expression.aggregate() == AggregateFunction.COUNT) {
            return Eval.number(ValueType.INTEGER, BigDecimal.valueOf(observations.size()), "", List.of());
        }
        List<BigDecimal> values = new ArrayList<>();
        String targetUnit = expression.left().unit();
        for (Observation observation : observations) {
            try {
                BigDecimal raw = new BigDecimal(observation.value());
                String sourceUnit = observation.unit().isBlank() ? targetUnit : observation.unit();
                var converted = units.convert(context.factoryId, raw, sourceUnit, targetUnit, context.evaluationTime);
                if (!converted.succeeded()) return Eval.unknown(expression.left().valueType(),
                        "UNIT_CONVERSION_FAILED:" + converted.errorCode());
                values.add(converted.value());
            } catch (RuntimeException invalid) {
                return Eval.unknown(expression.left().valueType(), "SERIES_PARSE_FAILED");
            }
        }
        BigDecimal result = switch (expression.aggregate()) {
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 12, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
            case MIN -> values.stream().min(BigDecimal::compareTo).orElseThrow();
            case MAX -> values.stream().max(BigDecimal::compareTo).orElseThrow();
            case COUNT -> throw new IllegalStateException("handled above");
        };
        return Eval.number(expression.left().valueType(), result, targetUnit, List.of());
    }

    private InputValue seriesInput(Expression expression, EvalContext context) {
        return expression != null && expression.op() == FactoryRuleModels.Operator.VAR
                ? context.inputs.get(expression.variableId()) : null;
    }

    private int compareValues(Eval left, Eval right, String factoryId, Instant evaluationTime) {
        if (left.type == ValueType.BOOLEAN) return left.truth.compareTo(right.truth);
        if (left.type == ValueType.DATETIME) return left.instant.compareTo(right.instant);
        if (left.number != null) {
            Eval aligned = align(right, left.unit, factoryId, evaluationTime);
            if (aligned.unknown) throw new IllegalArgumentException(String.join(",", aligned.reasons));
            return left.number.compareTo(aligned.number);
        }
        return left.text.compareTo(right.text);
    }

    private Eval align(Eval value, String targetUnit, String factoryId, Instant evaluationTime) {
        if (value.unit.isBlank() && targetUnit.isBlank()) return value;
        if (units.sameQuantityUnit(factoryId, value.unit, targetUnit))
            return Eval.number(value.type, value.number, targetUnit, value.observations);
        var conversion = units.convert(factoryId, value.number, value.unit, targetUnit, evaluationTime);
        if (!conversion.succeeded()) return Eval.unknown(value.type,
                "UNIT_CONVERSION_FAILED:" + conversion.errorCode());
        return Eval.number(value.type, conversion.value(), targetUnit, value.observations);
    }

    private Eval unknownBoolean(Eval... values) {
        List<String> reasons = new ArrayList<>();
        for (Eval value : values) if (value.unknown) reasons.addAll(value.reasons);
        return Eval.unknown(ValueType.BOOLEAN, reasons);
    }

    private Eval unknownNumeric(Eval... values) {
        List<String> reasons = new ArrayList<>();
        ValueType type = ValueType.DECIMAL;
        for (Eval value : values) {
            if (value.type != null) type = value.type;
            if (value.unknown) reasons.addAll(value.reasons);
        }
        return Eval.unknown(type, reasons);
    }

    private String unknownDisposition(UnknownPolicy policy) {
        return switch (policy) {
            case HOLD_FOR_REVIEW -> "HOLD_FOR_REVIEW";
            case BLOCK -> "BLOCKED";
            case DISPLAY_PARTIAL -> "DISPLAY_PARTIAL_WITH_MISSING_FIELDS";
        };
    }

    private record EvalContext(
            String factoryId,
            Map<String, VariableDefinition> variables,
            Map<String, InputValue> inputs,
            Instant evaluationTime,
            List<String> trace) {
    }

    private static final class Eval {
        private final ValueType type;
        private final BigDecimal number;
        private final String text;
        private final String unit;
        private final TriState truth;
        private final Instant instant;
        private final List<Observation> observations;
        private final boolean unknown;
        private final List<String> reasons;

        private Eval(ValueType type, BigDecimal number, String text, String unit, TriState truth,
                     Instant instant, List<Observation> observations, boolean unknown, List<String> reasons) {
            this.type = type;
            this.number = number;
            this.text = text == null ? "" : text;
            this.unit = unit == null ? "" : unit;
            this.truth = truth == null ? TriState.UNKNOWN : truth;
            this.instant = instant;
            this.observations = observations == null ? List.of() : List.copyOf(observations);
            this.unknown = unknown;
            this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        static Eval truth(boolean value) {
            return new Eval(ValueType.BOOLEAN, null, "", "", value ? TriState.TRUE : TriState.FALSE,
                    null, List.of(), false, List.of());
        }

        static Eval number(ValueType type, BigDecimal value, String unit, List<Observation> observations) {
            return new Eval(type, value, "", unit, TriState.UNKNOWN, null, observations, false, List.of());
        }

        static Eval text(ValueType type, String value) {
            return new Eval(type, null, value, "", TriState.UNKNOWN, null, List.of(), false, List.of());
        }

        static Eval dateTime(Instant value) {
            return new Eval(ValueType.DATETIME, null, "", "", TriState.UNKNOWN, value, List.of(), false, List.of());
        }

        static Eval series(ValueType type, String unit, List<Observation> observations) {
            return new Eval(type, null, "", unit, TriState.UNKNOWN, null, observations, false, List.of());
        }

        static Eval unknown(ValueType type, String reason) {
            return unknown(type, List.of(reason));
        }

        static Eval unknown(ValueType type, List<String> reasons) {
            return new Eval(type, null, "", "", TriState.UNKNOWN, null, List.of(), true,
                    reasons.stream().distinct().sorted().toList());
        }

        EvaluationValue toWire() {
            String value = number != null ? number.stripTrailingZeros().toPlainString()
                    : instant != null ? instant.toString() : text;
            return new EvaluationValue(type, value, unit, truth, reasons);
        }
    }

    private static final class SetLike {
        private SetLike() {
        }

        static boolean sameUnitRequired(FactoryRuleModels.Operator operator) {
            return operator == FactoryRuleModels.Operator.ADD || operator == FactoryRuleModels.Operator.SUBTRACT
                    || operator == FactoryRuleModels.Operator.MIN || operator == FactoryRuleModels.Operator.MAX;
        }
    }
}
