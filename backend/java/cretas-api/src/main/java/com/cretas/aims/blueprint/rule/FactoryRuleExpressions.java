package com.cretas.aims.blueprint.rule;

import com.cretas.aims.blueprint.rule.FactoryRuleModels.AggregateFunction;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Expression;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Operator;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValueType;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/** Small factories that keep authored ASTs readable while retaining one wire shape. */
public final class FactoryRuleExpressions {

    private FactoryRuleExpressions() {
    }

    public static Expression variable(String id, ValueType type, String unit) {
        return expression(Operator.VAR, List.of(), null, null, null, null, null, List.of(),
                id, "", type, unit, null, null, null, null, null, null, null, "", "");
    }

    public static Expression constant(String value, ValueType type, String unit) {
        return constant(value, type, unit, "", "");
    }

    public static Expression constant(
            String value, ValueType type, String unit, String evidenceRef, String effectiveVersion) {
        return expression(Operator.CONST, List.of(), null, null, null, null, null, List.of(),
                "", value, type, unit, null, null, null, null, null, null, null,
                evidenceRef, effectiveVersion);
    }

    public static Expression logical(Operator op, Expression... args) {
        return expression(op, List.of(args), null, null, null, null, null, List.of(),
                "", "", null, "", null, null, null, null, null, null, null, "", "");
    }

    public static Expression unary(Operator op, Expression value) {
        return expression(op, List.of(), null, null, value, null, null, List.of(),
                "", "", null, "", null, null, null, null, null, null, null, "", "");
    }

    public static Expression binary(Operator op, Expression left, Expression right) {
        return expression(op, List.of(), left, right, null, null, null, List.of(),
                "", "", null, "", null, null, null, null, null, null, null, "", "");
    }

    public static Expression divide(
            Expression left, Expression right, int scale, RoundingMode roundingMode) {
        return expression(Operator.DIVIDE, List.of(), left, right, null, null, null, List.of(),
                "", "", null, "", scale, roundingMode, null, null, null, null, null, "", "");
    }

    public static Expression between(
            Expression value, Expression lower, Expression upper, boolean inclusiveLower, boolean inclusiveUpper) {
        return expression(Operator.BETWEEN, List.of(), null, null, value, lower, upper, List.of(),
                "", "", null, "", null, null, null, null, null, inclusiveLower, inclusiveUpper, "", "");
    }

    public static Expression in(Expression value, Expression... options) {
        return expression(Operator.IN, List.of(), null, null, value, null, null, List.of(options),
                "", "", null, "", null, null, null, null, null, null, null, "", "");
    }

    public static Expression round(Expression value, int scale, RoundingMode mode) {
        return expression(Operator.ROUND, List.of(), null, null, value, null, null, List.of(),
                "", "", null, "", scale, mode, null, null, null, null, null, "", "");
    }

    public static Expression within(Expression dateTime, Expression duration) {
        return binary(Operator.WITHIN, dateTime, duration);
    }

    public static Expression consecutiveCount(Expression booleanSeries, int count, Duration window) {
        return expression(Operator.CONSECUTIVE_COUNT, List.of(), booleanSeries, null, null, null, null, List.of(),
                "", "", null, "", null, null, null, window, count, null, null, "", "");
    }

    public static Expression windowAggregate(
            Expression series, AggregateFunction aggregate, Duration window, int maxSamples) {
        return expression(Operator.WINDOW_AGGREGATE, List.of(), series, null, null, null, null, List.of(),
                "", "", null, "", null, null, aggregate, window, maxSamples, null, null, "", "");
    }

    private static Expression expression(
            Operator op, List<Expression> args, Expression left, Expression right, Expression value,
            Expression lower, Expression upper, List<Expression> values, String variableId,
            String constantValue, ValueType valueType, String unit, Integer scale,
            RoundingMode roundingMode, AggregateFunction aggregate, Duration window, Integer count,
            Boolean inclusiveLower, Boolean inclusiveUpper, String evidenceRef, String effectiveVersion) {
        return new Expression(op, args, left, right, value, lower, upper, values, variableId,
                constantValue, valueType, unit, scale, roundingMode, aggregate, window, count,
                inclusiveLower, inclusiveUpper, evidenceRef, effectiveVersion);
    }
}
