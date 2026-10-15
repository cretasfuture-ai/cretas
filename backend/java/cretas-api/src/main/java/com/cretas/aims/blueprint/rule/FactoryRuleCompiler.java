package com.cretas.aims.blueprint.rule;

import com.cretas.aims.blueprint.rule.FactoryRuleModels.EvaluationValue;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleDefinition;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RulePreview;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.TestVectorResult;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.ValidationReport;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableCatalog;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Validation + test-vector compiler that emits a content-addressed preview, never a runtime rule. */
public final class FactoryRuleCompiler {

    private final FactoryRuleValidator validator;
    private final FactoryRuleSimulator simulator;
    private final ObjectMapper canonicalMapper;

    public FactoryRuleCompiler(FactoryRuleUnitResolver units) {
        this.validator = new FactoryRuleValidator(units);
        this.simulator = new FactoryRuleSimulator(units);
        this.canonicalMapper = new ObjectMapper().findAndRegisterModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public RulePreview compile(RuleDefinition rule, VariableCatalog catalog) {
        ValidationReport validation = validator.validate(rule, catalog);
        List<TestVectorResult> results = new ArrayList<>();
        if (validation.valid()) {
            for (var vector : rule.testVectors()) {
                var simulation = simulator.simulate(rule, catalog, vector.inputs(), vector.evaluationTime());
                String actual = simulation.result().comparableResult();
                boolean passed = vector.expected().equalsIgnoreCase(actual);
                results.add(new TestVectorResult(vector.id(), vector.expected(), actual, passed,
                        passed ? "PASS" : "期望与隔离模拟结果不一致"));
            }
        }
        results.sort(Comparator.comparing(TestVectorResult::id));
        boolean vectorsPass = !results.isEmpty() && results.stream().allMatch(TestVectorResult::passed);
        boolean blocked = !validation.valid() || !vectorsPass;
        String status = blocked ? "BLOCKED" : "PREVIEW_READY";
        return new RulePreview(FactoryRuleModels.SCHEMA_VERSION,
                rule == null ? "" : rule.ruleId(), contentHash(rule, catalog), status,
                validation, results, "NOT_PUBLISHED", "NOT_ACTIVATED", "NOT_BOUND_TO_RUNTIME", 0);
    }

    public FactoryRuleModels.SimulationResult simulate(
            RuleDefinition rule,
            VariableCatalog catalog,
            Map<String, FactoryRuleModels.InputValue> inputs,
            Instant evaluationTime) {
        ValidationReport validation = validator.validate(rule, catalog);
        if (!validation.valid() || evaluationTime == null) {
            List<String> codes = validation.issues().stream().map(FactoryRuleModels.ValidationIssue::code)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)).stream().toList();
            if (evaluationTime == null) {
                codes = new ArrayList<>(codes);
                codes.add("EVALUATION_TIME_REQUIRED");
                codes = codes.stream().distinct().sorted().toList();
            }
            return new FactoryRuleModels.SimulationResult("BLOCKED",
                    new EvaluationValue(rule == null ? null : rule.resultType(), "", "",
                            FactoryRuleModels.TriState.UNKNOWN, codes),
                    rule == null ? FactoryRuleModels.UnknownPolicy.BLOCK : rule.unknownPolicy(),
                    "VALIDATION_BLOCKED", codes, 0, "NOT_BOUND_TO_RUNTIME");
        }
        return simulator.simulate(rule, catalog, inputs, evaluationTime);
    }

    private String contentHash(RuleDefinition rule, VariableCatalog catalog) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            java.util.Map<String, Object> content = new java.util.TreeMap<>();
            content.put("compilerVersion", FactoryRuleModels.COMPILER_VERSION);
            content.put("rule", rule);
            content.put("schemaVersion", FactoryRuleModels.SCHEMA_VERSION);
            content.put("variableCatalog", catalog);
            byte[] serialized = canonicalMapper.writeValueAsBytes(content);
            return HexFormat.of().formatHex(sha256.digest(serialized));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (Exception invalidContract) {
            throw new IllegalStateException("Rule contract cannot be serialized", invalidContract);
        }
    }
}
