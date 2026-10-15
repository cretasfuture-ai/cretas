package com.cretas.aims.blueprint.rule;

import com.cretas.aims.blueprint.rule.FactoryRuleModels.Expression;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.IssueSeverity;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.Operator;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleDefinition;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleKind;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.UnknownPolicy;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValidationIssue;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValidationReport;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValueType;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableCatalog;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableDefinition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Static schema, type, unit, safety and complexity gate for {@code factory-rule/v1}. */
public final class FactoryRuleValidator {

    public static final int MAX_NODES = 128;
    public static final int MAX_DEPTH = 16;
    public static final int MAX_ARGS = 32;
    public static final int MAX_TEST_VECTORS = 64;
    public static final int MAX_CONSTANT_LENGTH = 256;
    public static final Duration MAX_WINDOW = Duration.ofDays(31);

    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,95}");

    private final FactoryRuleUnitResolver units;

    public FactoryRuleValidator(FactoryRuleUnitResolver units) {
        this.units = units;
    }

    public ValidationReport validate(RuleDefinition rule, VariableCatalog catalog) {
        List<ValidationIssue> issues = new ArrayList<>();
        validateEnvelope(rule, catalog, issues);
        Map<String, VariableDefinition> variables = indexVariables(catalog, issues);
        NodeInfo root = infer(rule == null ? null : rule.expression(), "$.expression", 1,
                rule, variables, issues, new HashSet<>());
        if (rule != null && rule.resultType() != null && root.type() != null
                && rule.resultType() != root.type()) {
            issues.add(issue("RESULT_TYPE_MISMATCH", "$.resultType",
                    "声明结果类型 " + rule.resultType() + " 与表达式类型 " + root.type() + " 不一致",
                    "修正规则 resultType 或 AST"));
        }
        if (root.nodeCount() > MAX_NODES) issues.add(issue("RULE_TOO_COMPLEX", "$.expression",
                "规则节点数 " + root.nodeCount() + " 超过上限 " + MAX_NODES, "拆分为多个可复核规则"));
        if (root.depth() > MAX_DEPTH) issues.add(issue("RULE_TOO_DEEP", "$.expression",
                "规则深度 " + root.depth() + " 超过上限 " + MAX_DEPTH, "降低嵌套层级"));
        validateTestVectors(rule, issues);
        issues.sort(java.util.Comparator.comparing(ValidationIssue::path)
                .thenComparing(ValidationIssue::code));
        boolean valid = issues.stream().noneMatch(item -> item.severity() == IssueSeverity.BLOCKING);
        return new ValidationReport(valid, root.type(), root.nodeCount(), root.depth(), issues, 0);
    }

    private void validateEnvelope(
            RuleDefinition rule, VariableCatalog catalog, List<ValidationIssue> issues) {
        if (rule == null) {
            issues.add(issue("RULE_REQUIRED", "$", "规则不能为空", "提供 factory-rule/v1 结构化规则"));
            return;
        }
        if (!FactoryRuleModels.SCHEMA_VERSION.equals(rule.schemaVersion())) issues.add(issue(
                "SCHEMA_VERSION_UNSUPPORTED", "$.schemaVersion", "只接受 factory-rule/v1",
                "使用当前受控 schema，禁止传入 raw text/SpEL"));
        if (rule.factoryId().isBlank()) issues.add(issue("FACTORY_ID_REQUIRED", "$.factoryId",
                "规则缺少工厂范围", "绑定当前已授权 factoryId"));
        if (!RULE_ID.matcher(rule.ruleId()).matches()) issues.add(issue("RULE_ID_INVALID", "$.ruleId",
                "ruleId 必须是稳定的小写标识", "使用 3-96 位小写字母、数字、点、横线或下划线"));
        if (rule.kind() == null || rule.resultType() == null || rule.expression() == null) issues.add(issue(
                "RULE_SHAPE_INCOMPLETE", "$", "kind、resultType 和 expression 均为必填",
                "补齐结构化规则字段"));
        if (!FactoryRuleModels.COMPILER_VERSION.equals(rule.compilerVersion())) issues.add(issue(
                "COMPILER_VERSION_UNSUPPORTED", "$.compilerVersion", "编译器版本未锁定到 v1",
                "使用 factory-rule-compiler/v1"));
        if (rule.variableCatalogVersion().isBlank() || rule.variableCatalogDigest().isBlank()
                || rule.unitCatalogDigest().isBlank()) issues.add(issue(
                "CATALOG_LOCK_REQUIRED", "$", "规则必须锁定变量目录和单位目录摘要",
                "回读版本化目录并保存 exact version/digest"));
        if (!rule.unitCatalogDigest().isBlank()
                && !com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(rule.unitCatalogDigest(), units.catalogDigest(rule.factoryId()))) issues.add(issue(
                "UNIT_CATALOG_DRIFT", "$.unitCatalogDigest", "规则锁定摘要与当前单位目录不一致",
                "重新编译并复核规则，禁止跟随单位目录漂移"));
        if (catalog == null) {
            issues.add(issue("VARIABLE_CATALOG_REQUIRED", "$.variableCatalog", "变量目录不能为空",
                    "提供当前工厂的版本化变量目录"));
        } else {
            if (!rule.factoryId().equals(catalog.factoryId())) issues.add(issue(
                    "CROSS_FACTORY_VARIABLE_CATALOG", "$.variableCatalog", "变量目录不属于当前工厂",
                    "从当前 factoryId 重新加载变量目录"));
            if (!rule.variableCatalogVersion().equals(catalog.version())
                    || !rule.variableCatalogDigest().equals(catalog.digest())) issues.add(issue(
                    "VARIABLE_CATALOG_DRIFT", "$.variableCatalog", "规则锁定摘要与变量目录不一致",
                    "重新编译并复核规则，禁止跟随目录漂移"));
        }
        if (rule.kind() != null && rule.resultType() != null
                && !allowedResultTypes(rule.kind()).contains(rule.resultType())) issues.add(issue(
                "KIND_RESULT_TYPE_INVALID", "$.resultType", "规则种类与结果类型不兼容",
                "按 kind 使用受支持的结果类型"));
        if (rule.unknownPolicy() == null) issues.add(issue("UNKNOWN_POLICY_REQUIRED", "$.unknownPolicy",
                "必须显式声明 UNKNOWN 处置", "安全/质量/路线规则使用 HOLD_FOR_REVIEW 或 BLOCK"));
        if (safetyCritical(rule.kind()) && rule.unknownPolicy() == UnknownPolicy.DISPLAY_PARTIAL) issues.add(issue(
                "UNSAFE_UNKNOWN_POLICY", "$.unknownPolicy", "食品安全、质量、路线和告警规则不能 partial 放行",
                "改为 HOLD_FOR_REVIEW 或 BLOCK"));
        if (rule.kind() == RuleKind.CRITICAL_LIMIT && rule.evidenceRefs().isEmpty()) issues.add(issue(
                "SAFETY_EVIDENCE_REQUIRED", "$.evidenceRefs", "关键限值规则必须绑定确认依据",
                "由工厂食品安全负责人确认限值和生效版本"));
    }

    private Map<String, VariableDefinition> indexVariables(
            VariableCatalog catalog, List<ValidationIssue> issues) {
        Map<String, VariableDefinition> result = new LinkedHashMap<>();
        if (catalog == null) return result;
        for (int index = 0; index < catalog.definitions().size(); index++) {
            VariableDefinition definition = catalog.definitions().get(index);
            String path = "$.variableCatalog.definitions[" + index + "]";
            if (definition.id().isBlank() || definition.type() == null || definition.sourceRef().isBlank()) {
                issues.add(issue("VARIABLE_DEFINITION_INVALID", path,
                        "变量必须有稳定 ID、类型和来源", "补齐版本化变量定义"));
                continue;
            }
            if (result.putIfAbsent(definition.id(), definition) != null) issues.add(issue(
                    "DUPLICATE_VARIABLE", path, "变量 ID 重复: " + definition.id(), "只保留一个权威定义"));
            if (requiresUnit(definition.type()) && definition.unit().isBlank()) issues.add(issue(
                    "VARIABLE_UNIT_REQUIRED", path + ".unit", "数量/时长变量必须声明单位", "选择单位目录中的单位"));
            if (!definition.unit().isBlank() && units.describe(catalog.factoryId(), definition.unit()).isEmpty()) {
                issues.add(issue("UNKNOWN_VARIABLE_UNIT", path + ".unit", "变量单位未在权威单位目录中注册",
                        "使用 UnitContractService 可识别的活动单位"));
            }
            if (definition.type() == ValueType.ENUM && definition.allowedValues().isEmpty()) issues.add(issue(
                    "ENUM_VALUES_REQUIRED", path + ".allowedValues", "枚举变量必须锁定允许值",
                    "补齐可审阅的枚举值"));
        }
        return result;
    }

    private NodeInfo infer(
            Expression expression,
            String path,
            int depth,
            RuleDefinition rule,
            Map<String, VariableDefinition> variables,
            List<ValidationIssue> issues,
            Set<Expression> active) {
        if (expression == null) {
            issues.add(issue("EXPRESSION_REQUIRED", path, "缺少表达式节点", "补齐受控 AST 节点"));
            return new NodeInfo(null, "", 0, depth);
        }
        if (!active.add(expression)) {
            issues.add(issue("CYCLIC_AST", path, "AST 不能循环引用", "重建无环表达式树"));
            return new NodeInfo(null, "", 1, depth);
        }
        if (expression.op() == null) {
            issues.add(issue("OP_REQUIRED", path + ".op", "节点缺少受控 op", "选择 factory-rule/v1 op"));
            active.remove(expression);
            return new NodeInfo(null, "", 1, depth);
        }
        validateClosedShape(expression, path, issues);
        NodeInfo result = switch (expression.op()) {
            case VAR -> inferVariable(expression, path, variables, issues);
            case CONST -> inferConstant(expression, path, rule, issues);
            case ALL, ANY -> inferLogical(expression, path, depth, rule, variables, issues, active);
            case NOT -> inferUnaryBoolean(expression, path, depth, rule, variables, issues, active);
            case EQ, NE, GT, GTE, LT, LTE -> inferComparison(
                    expression, path, depth, rule, variables, issues, active);
            case BETWEEN -> inferBetween(expression, path, depth, rule, variables, issues, active);
            case IN -> inferIn(expression, path, depth, rule, variables, issues, active);
            case IS_KNOWN -> inferIsKnown(expression, path, depth, rule, variables, issues, active);
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MIN, MAX -> inferArithmetic(
                    expression, path, depth, rule, variables, issues, active);
            case ABS, ROUND -> inferNumericUnary(expression, path, depth, rule, variables, issues, active);
            case WITHIN -> inferWithin(expression, path, depth, rule, variables, issues, active);
            case CONSECUTIVE_COUNT -> inferConsecutive(
                    expression, path, depth, rule, variables, issues, active);
            case WINDOW_AGGREGATE -> inferAggregate(
                    expression, path, depth, rule, variables, issues, active);
        };
        active.remove(expression);
        return result;
    }

    private void validateClosedShape(
            Expression expression, String path, List<ValidationIssue> issues) {
        Set<String> allowed = switch (expression.op()) {
            case VAR -> Set.of("variableId", "valueType", "unit");
            case CONST -> Set.of("constantValue", "valueType", "unit", "evidenceRef", "effectiveVersion");
            case ALL, ANY -> Set.of("args");
            case NOT, IS_KNOWN, ABS -> Set.of("value");
            case ROUND -> Set.of("value", "scale", "roundingMode");
            case EQ, NE, GT, GTE, LT, LTE, ADD, SUBTRACT, MULTIPLY, MIN, MAX, WITHIN ->
                    Set.of("left", "right");
            case DIVIDE -> Set.of("left", "right", "scale", "roundingMode");
            case BETWEEN -> Set.of("value", "lower", "upper", "inclusiveLower", "inclusiveUpper");
            case IN -> Set.of("value", "values");
            case CONSECUTIVE_COUNT -> Set.of("left", "window", "count");
            case WINDOW_AGGREGATE -> Set.of("left", "aggregate", "window", "count");
        };
        List<String> present = new ArrayList<>();
        if (!expression.args().isEmpty()) present.add("args");
        if (expression.left() != null) present.add("left");
        if (expression.right() != null) present.add("right");
        if (expression.value() != null) present.add("value");
        if (expression.lower() != null) present.add("lower");
        if (expression.upper() != null) present.add("upper");
        if (!expression.values().isEmpty()) present.add("values");
        if (!expression.variableId().isBlank()) present.add("variableId");
        if (!expression.constantValue().isBlank()) present.add("constantValue");
        if (expression.valueType() != null) present.add("valueType");
        if (!expression.unit().isBlank()) present.add("unit");
        if (expression.scale() != null) present.add("scale");
        if (expression.roundingMode() != null) present.add("roundingMode");
        if (expression.aggregate() != null) present.add("aggregate");
        if (expression.window() != null) present.add("window");
        if (expression.count() != null) present.add("count");
        if (expression.inclusiveLower() != null) present.add("inclusiveLower");
        if (expression.inclusiveUpper() != null) present.add("inclusiveUpper");
        if (!expression.evidenceRef().isBlank()) present.add("evidenceRef");
        if (!expression.effectiveVersion().isBlank()) present.add("effectiveVersion");
        List<String> unexpected = present.stream().filter(field -> !allowed.contains(field)).sorted().toList();
        if (!unexpected.isEmpty()) issues.add(issue("AMBIGUOUS_NODE_FIELDS", path,
                "节点包含当前 op 不允许的字段: " + String.join(", ", unexpected),
                "仅保留 " + expression.op() + " 的受控字段"));
    }

    private NodeInfo inferVariable(
            Expression expression, String path, Map<String, VariableDefinition> variables,
            List<ValidationIssue> issues) {
        VariableDefinition definition = variables.get(expression.variableId());
        if (definition == null) {
            issues.add(issue("UNREGISTERED_VARIABLE", path + ".variableId",
                    "变量未在版本化目录中注册: " + expression.variableId(), "先注册并锁定变量目录"));
            return new NodeInfo(expression.valueType(), expression.unit(), 1, 1);
        }
        if (expression.valueType() != definition.type() || !com.cretas.aims.blueprint.FactoryBlueprintSnapshotEquality.sameConfirmedValue(expression.unit(), definition.unit())) {
            issues.add(issue("VARIABLE_CONTRACT_MISMATCH", path,
                    "AST 变量类型/单位与目录定义不一致", "从变量目录生成引用，不允许自由改写"));
        }
        return new NodeInfo(definition.type(), definition.unit(), 1, 1);
    }

    private NodeInfo inferConstant(
            Expression expression, String path, RuleDefinition rule, List<ValidationIssue> issues) {
        if (expression.valueType() == null || expression.constantValue().isBlank()) issues.add(issue(
                "TYPED_CONSTANT_REQUIRED", path, "常量必须有显式类型和值", "补齐 type 与 const"));
        if (expression.constantValue().length() > MAX_CONSTANT_LENGTH) issues.add(issue(
                "CONSTANT_TOO_LONG", path + ".constantValue", "常量长度超过上限", "缩短为结构化常量"));
        validateScalar(expression.constantValue(), expression.valueType(), path, issues);
        validateUnit(expression.valueType(), expression.unit(), rule, path, issues);
        if (rule != null && rule.kind() == RuleKind.CRITICAL_LIMIT
                && (expression.evidenceRef().isBlank() || expression.effectiveVersion().isBlank())) issues.add(issue(
                "LIMIT_CONSTANT_PROVENANCE_REQUIRED", path,
                "关键限值常量必须带 evidenceRef 和 effectiveVersion",
                "绑定食品安全负责人确认的依据和生效版本"));
        return new NodeInfo(expression.valueType(), expression.unit(), 1, 1);
    }

    private NodeInfo inferLogical(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        if (expression.args().size() < 2 || expression.args().size() > MAX_ARGS) issues.add(issue(
                "LOGICAL_ARITY_INVALID", path + ".args", "all/any 需要 2-" + MAX_ARGS + " 个参数",
                "调整受控参数数量"));
        List<NodeInfo> children = new ArrayList<>();
        for (int index = 0; index < expression.args().size(); index++) children.add(infer(
                expression.args().get(index), path + ".args[" + index + "]", depth + 1,
                rule, variables, issues, active));
        requireTypes(children, ValueType.BOOLEAN, path, issues);
        return combined(ValueType.BOOLEAN, "", children, depth);
    }

    private NodeInfo inferUnaryBoolean(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo child = infer(expression.value(), path + ".value", depth + 1, rule, variables, issues, active);
        requireType(child, ValueType.BOOLEAN, path, issues);
        return combined(ValueType.BOOLEAN, "", List.of(child), depth);
    }

    private NodeInfo inferComparison(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo left = infer(expression.left(), path + ".left", depth + 1, rule, variables, issues, active);
        NodeInfo right = infer(expression.right(), path + ".right", depth + 1, rule, variables, issues, active);
        if (left.type() != null && right.type() != null && left.type() != right.type()) issues.add(issue(
                "COMPARISON_TYPE_MISMATCH", path, "比较两侧类型不一致", "使用同类型变量/常量"));
        if (Set.of(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE).contains(expression.op())
                && !ordered(left.type())) issues.add(issue("ORDERED_TYPE_REQUIRED", path,
                "大小比较只支持数值、数量、时长、整数或时间", "改用可排序类型"));
        validateCompatibleUnits(left, right, rule, path, issues);
        return combined(ValueType.BOOLEAN, "", List.of(left, right), depth);
    }

    private NodeInfo inferBetween(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo value = infer(expression.value(), path + ".value", depth + 1, rule, variables, issues, active);
        NodeInfo lower = infer(expression.lower(), path + ".lower", depth + 1, rule, variables, issues, active);
        NodeInfo upper = infer(expression.upper(), path + ".upper", depth + 1, rule, variables, issues, active);
        if (!ordered(value.type()) || value.type() != lower.type() || value.type() != upper.type()) issues.add(issue(
                "BETWEEN_TYPE_INVALID", path, "between 的值和上下界必须是相同可排序类型",
                "统一类型并显式声明边界包含性"));
        if (expression.inclusiveLower() == null || expression.inclusiveUpper() == null) issues.add(issue(
                "BOUNDARY_INCLUSIVITY_REQUIRED", path, "between 必须显式声明上下界是否包含",
                "设置 inclusiveLower 与 inclusiveUpper"));
        validateCompatibleUnits(value, lower, rule, path, issues);
        validateCompatibleUnits(value, upper, rule, path, issues);
        return combined(ValueType.BOOLEAN, "", List.of(value, lower, upper), depth);
    }

    private NodeInfo inferIn(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo value = infer(expression.value(), path + ".value", depth + 1, rule, variables, issues, active);
        if (expression.values().isEmpty() || expression.values().size() > MAX_ARGS) issues.add(issue(
                "IN_OPTIONS_INVALID", path + ".values", "in 需要 1-" + MAX_ARGS + " 个候选值",
                "提供有界常量候选"));
        List<NodeInfo> children = new ArrayList<>(List.of(value));
        for (int index = 0; index < expression.values().size(); index++) {
            NodeInfo option = infer(expression.values().get(index), path + ".values[" + index + "]",
                    depth + 1, rule, variables, issues, active);
            children.add(option);
            if (value.type() != null && option.type() != value.type()) issues.add(issue(
                    "IN_OPTION_TYPE_MISMATCH", path + ".values[" + index + "]",
                    "候选值类型与被比较值不一致", "统一候选类型"));
        }
        return combined(ValueType.BOOLEAN, "", children, depth);
    }

    private NodeInfo inferIsKnown(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo child = infer(expression.value(), path + ".value", depth + 1, rule, variables, issues, active);
        return combined(ValueType.BOOLEAN, "", List.of(child), depth);
    }

    private NodeInfo inferArithmetic(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo left = infer(expression.left(), path + ".left", depth + 1, rule, variables, issues, active);
        NodeInfo right = infer(expression.right(), path + ".right", depth + 1, rule, variables, issues, active);
        if (!arithmeticNumeric(left.type()) || !arithmeticNumeric(right.type())) issues.add(issue(
                "NUMERIC_OPERANDS_REQUIRED", path, "算术节点只接受数值类型", "改用 decimal/integer/quantity/duration"));
        ValueType resultType = left.type();
        String resultUnit = left.unit();
        if (Set.of(Operator.ADD, Operator.SUBTRACT, Operator.MIN, Operator.MAX).contains(expression.op())) {
            if (left.type() != right.type()) issues.add(issue("ARITHMETIC_TYPE_MISMATCH", path,
                    "加减/min/max 两侧必须同类型", "统一类型"));
            validateCompatibleUnits(left, right, rule, path, issues);
        } else if (expression.op() == Operator.MULTIPLY) {
            if (left.type() == ValueType.QUANTITY && right.type() == ValueType.QUANTITY) issues.add(issue(
                    "DERIVED_UNIT_UNSUPPORTED", path, "v1 不支持数量乘数量产生派生单位", "仅允许数量乘无量纲 decimal"));
            if (left.type() == ValueType.DECIMAL) {
                resultType = right.type();
                resultUnit = right.unit();
            } else if (right.type() != ValueType.DECIMAL) issues.add(issue(
                    "MULTIPLY_DIMENSION_INVALID", path, "乘法至少一侧必须是无量纲 decimal",
                    "显式拆分单位换算和比例"));
        } else if (expression.op() == Operator.DIVIDE) {
            if (expression.scale() == null || expression.scale() < 0 || expression.scale() > 12
                    || expression.roundingMode() == null) issues.add(issue(
                    "DIVISION_ROUNDING_REQUIRED", path,
                    "divide 必须显式声明 0-12 scale 与 roundingMode", "补齐除法舍入契约"));
            if (expression.right() != null && expression.right().op() == Operator.CONST
                    && parsesZero(expression.right().constantValue())) issues.add(issue(
                    "STATIC_DIVIDE_BY_ZERO", path + ".right", "除数常量不能为 0", "修正确认值"));
            if (left.type() == ValueType.QUANTITY && right.type() == ValueType.QUANTITY) {
                validateCompatibleUnits(left, right, rule, path, issues);
                resultType = ValueType.DECIMAL;
                resultUnit = "";
            } else if (right.type() != ValueType.DECIMAL) issues.add(issue(
                    "DIVIDE_DIMENSION_INVALID", path, "v1 只支持数值/decimal 或同量纲数量相除",
                    "显式声明比例或先统一单位"));
        }
        return combined(resultType, resultUnit, List.of(left, right), depth);
    }

    private NodeInfo inferNumericUnary(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo child = infer(expression.value(), path + ".value", depth + 1, rule, variables, issues, active);
        if (!arithmeticNumeric(child.type())) issues.add(issue("NUMERIC_VALUE_REQUIRED", path,
                "abs/round 只接受数值", "改用数值表达式"));
        if (expression.op() == Operator.ROUND
                && (expression.scale() == null || expression.scale() < 0 || expression.scale() > 12
                || expression.roundingMode() == null)) issues.add(issue(
                "ROUNDING_CONTRACT_REQUIRED", path, "round 必须显式声明 0-12 scale 与 roundingMode",
                "补齐舍入契约"));
        return combined(child.type(), child.unit(), List.of(child), depth);
    }

    private NodeInfo inferWithin(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo date = infer(expression.left(), path + ".left", depth + 1, rule, variables, issues, active);
        NodeInfo duration = infer(expression.right(), path + ".right", depth + 1, rule, variables, issues, active);
        requireType(date, ValueType.DATETIME, path + ".left", issues);
        requireType(duration, ValueType.DURATION, path + ".right", issues);
        return combined(ValueType.BOOLEAN, "", List.of(date, duration), depth);
    }

    private NodeInfo inferConsecutive(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo series = infer(expression.left(), path + ".left", depth + 1, rule, variables, issues, active);
        requireType(series, ValueType.BOOLEAN, path + ".left", issues);
        validateWindowAndCount(expression.window(), expression.count(), path, issues);
        if (expression.left() == null || expression.left().op() != Operator.VAR) issues.add(issue(
                "SERIES_VARIABLE_REQUIRED", path + ".left", "consecutiveCount 必须引用注册的布尔序列变量",
                "使用 VAR 节点并在模拟输入提供 observations"));
        return combined(ValueType.BOOLEAN, "", List.of(series), depth);
    }

    private NodeInfo inferAggregate(
            Expression expression, String path, int depth, RuleDefinition rule,
            Map<String, VariableDefinition> variables, List<ValidationIssue> issues, Set<Expression> active) {
        NodeInfo series = infer(expression.left(), path + ".left", depth + 1, rule, variables, issues, active);
        if (!arithmeticNumeric(series.type())) issues.add(issue("AGGREGATE_NUMERIC_SERIES_REQUIRED", path,
                "windowAggregate 只接受数值序列", "使用注册的数值 VAR 节点"));
        if (expression.left() == null || expression.left().op() != Operator.VAR) issues.add(issue(
                "SERIES_VARIABLE_REQUIRED", path + ".left", "windowAggregate 必须引用注册序列变量",
                "使用 VAR 节点并提供 observations"));
        if (expression.aggregate() == null) issues.add(issue("AGGREGATE_FUNCTION_REQUIRED", path + ".aggregate",
                "必须显式声明聚合函数", "选择 SUM/AVG/MIN/MAX/COUNT"));
        validateWindowAndCount(expression.window(), expression.count(), path, issues);
        ValueType type = expression.aggregate() == FactoryRuleModels.AggregateFunction.COUNT
                ? ValueType.INTEGER : series.type();
        String unit = type == ValueType.INTEGER ? "" : series.unit();
        return combined(type, unit, List.of(series), depth);
    }

    private void validateWindowAndCount(
            Duration window, Integer count, String path, List<ValidationIssue> issues) {
        if (window == null || window.isZero() || window.isNegative() || window.compareTo(MAX_WINDOW) > 0) issues.add(issue(
                "TIME_WINDOW_INVALID", path + ".window", "时间窗必须大于 0 且不超过 31 天",
                "提供显式、有限的 Duration"));
        if (count == null || count < 1 || count > 10_000) issues.add(issue(
                "SAMPLE_COUNT_INVALID", path + ".count", "样本计数必须在 1-10000",
                "提供有限的连续样本数"));
    }

    private void validateTestVectors(RuleDefinition rule, List<ValidationIssue> issues) {
        if (rule == null) return;
        if (rule.testVectors().isEmpty() || rule.testVectors().size() > MAX_TEST_VECTORS) issues.add(issue(
                "TEST_VECTOR_COUNT_INVALID", "$.testVectors", "必须提供 1-" + MAX_TEST_VECTORS + " 个测试向量",
                "至少覆盖典型、边界和缺值"));
        if (safetyCritical(rule.kind()) && rule.testVectors().size() < 3) issues.add(issue(
                "TEST_VECTOR_COVERAGE_REQUIRED", "$.testVectors", "安全/质量/路线规则至少需要 3 个测试向量",
                "分别提供典型、边界和缺值场景"));
        Set<String> ids = new HashSet<>();
        boolean hasUnknown = false;
        for (int index = 0; index < rule.testVectors().size(); index++) {
            var vector = rule.testVectors().get(index);
            String path = "$.testVectors[" + index + "]";
            if (vector.id().isBlank() || !ids.add(vector.id())) issues.add(issue(
                    "TEST_VECTOR_ID_INVALID", path + ".id", "测试向量 ID 为空或重复", "提供稳定唯一 ID"));
            if (vector.expected().isBlank() || vector.evaluationTime() == null) issues.add(issue(
                    "TEST_VECTOR_EXPECTATION_REQUIRED", path, "测试向量必须有 expected 和 evaluationTime",
                    "固定期望和模拟时点"));
            if (vector.evidenceRefs().isEmpty()) issues.add(issue(
                    "TEST_VECTOR_EVIDENCE_REQUIRED", path + ".evidenceRefs", "测试向量必须绑定可复核依据",
                    "记录访谈答案、法规、样本或负责人确认引用"));
            if ("UNKNOWN".equalsIgnoreCase(vector.expected())) hasUnknown = true;
        }
        if (safetyCritical(rule.kind()) && !hasUnknown) issues.add(issue(
                "UNKNOWN_TEST_VECTOR_REQUIRED", "$.testVectors", "安全/质量/路线规则必须覆盖缺值 UNKNOWN",
                "增加缺少关键输入的测试向量"));
    }

    private void validateScalar(
            String value, ValueType type, String path, List<ValidationIssue> issues) {
        if (type == null || value == null || value.isBlank()) return;
        try {
            switch (type) {
                case DECIMAL, QUANTITY, DURATION -> new BigDecimal(value);
                case INTEGER -> Integer.parseInt(value);
                case BOOLEAN -> FactoryRuleModels.TriState.valueOf(value.toUpperCase());
                case DATETIME -> Instant.parse(value);
                case STRING, ENUM -> {
                }
            }
        } catch (RuntimeException invalid) {
            issues.add(issue("CONSTANT_PARSE_FAILED", path + ".constantValue",
                    "常量不能按声明类型解析", "修正值格式"));
        }
    }

    private void validateUnit(
            ValueType type, String unit, RuleDefinition rule, String path, List<ValidationIssue> issues) {
        if (type == null) return;
        if (requiresUnit(type) && unit.isBlank()) {
            issues.add(issue("UNIT_REQUIRED", path + ".unit", "数量/时长常量必须声明单位", "选择权威单位"));
            return;
        }
        if (!unit.isBlank() && units.describe(rule == null ? "" : rule.factoryId(), unit).isEmpty()) issues.add(issue(
                "UNKNOWN_UNIT", path + ".unit", "单位未在 UnitContractService 目录中注册",
                "选择同工厂活动单位"));
    }

    private void validateCompatibleUnits(
            NodeInfo left, NodeInfo right, RuleDefinition rule, String path, List<ValidationIssue> issues) {
        if (!requiresUnit(left.type()) && !requiresUnit(right.type())) return;
        var leftUnit = units.describe(rule.factoryId(), left.unit());
        var rightUnit = units.describe(rule.factoryId(), right.unit());
        if (leftUnit.isEmpty() || rightUnit.isEmpty()
                || leftUnit.get().dimension() != rightUnit.get().dimension()) issues.add(issue(
                "INCOMPATIBLE_UNITS", path, "两侧单位未知或量纲不兼容", "使用同量纲权威单位"));
    }

    private void requireTypes(
            List<NodeInfo> nodes, ValueType type, String path, List<ValidationIssue> issues) {
        for (NodeInfo node : nodes) requireType(node, type, path, issues);
    }

    private void requireType(
            NodeInfo node, ValueType type, String path, List<ValidationIssue> issues) {
        if (node.type() != null && node.type() != type) issues.add(issue(
                "TYPE_MISMATCH", path, "表达式必须返回 " + type + "，实际为 " + node.type(), "修正 AST 类型"));
    }

    private NodeInfo combined(ValueType type, String unit, List<NodeInfo> children, int currentDepth) {
        int nodes = 1 + children.stream().mapToInt(NodeInfo::nodeCount).sum();
        int depth = Math.max(currentDepth, children.stream().mapToInt(NodeInfo::depth).max().orElse(currentDepth));
        return new NodeInfo(type, unit, nodes, depth);
    }

    private boolean requiresUnit(ValueType type) {
        return type == ValueType.QUANTITY || type == ValueType.DURATION;
    }

    private boolean arithmeticNumeric(ValueType type) {
        return type == ValueType.DECIMAL || type == ValueType.INTEGER || type == ValueType.QUANTITY
                || type == ValueType.DURATION;
    }

    private boolean ordered(ValueType type) {
        return arithmeticNumeric(type) || type == ValueType.DATETIME;
    }

    private boolean parsesZero(String value) {
        try {
            return new BigDecimal(value).compareTo(BigDecimal.ZERO) == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean safetyCritical(RuleKind kind) {
        return kind == RuleKind.ROUTE_GUARD || kind == RuleKind.QUALITY_DECISION
                || kind == RuleKind.CRITICAL_LIMIT || kind == RuleKind.ALERT_TRIGGER;
    }

    private Set<ValueType> allowedResultTypes(RuleKind kind) {
        return switch (kind) {
            case ROUTE_GUARD, CRITICAL_LIMIT, ALERT_TRIGGER -> Set.of(ValueType.BOOLEAN);
            case QUALITY_DECISION -> Set.of(ValueType.ENUM);
            case CALCULATION -> Set.of(ValueType.DECIMAL, ValueType.QUANTITY, ValueType.INTEGER);
            case ALLOCATION -> Set.of(ValueType.DECIMAL);
        };
    }

    private ValidationIssue issue(String code, String path, String message, String recovery) {
        return new ValidationIssue(code, IssueSeverity.BLOCKING, path, message, recovery);
    }

    private record NodeInfo(ValueType type, String unit, int nodeCount, int depth) {
    }
}
