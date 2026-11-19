package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keeps the Java PR gate fast during iteration without weakening the final-candidate proof.
 */
@DisplayName("Java PR gate tiering")
class JavaPrGateTieringContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..", "..").normalize();
    private static final Path PR_GATE = REPO_ROOT.resolve(".github/workflows/java-gate-pr.yml");
    private static final Path FULL_GATE = REPO_ROOT.resolve(".github/workflows/java-full-nightly.yml");
    private static final String FULL_READY = "java-full-ready";
    private static final String LABEL_NAMES = "github.event.pull_request.labelNames";
    private static final SpelExpressionParser EXPRESSIONS = new SpelExpressionParser();

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("positive control: both workflow sources are present and readable")
    void workflowSourcesArePresent() throws IOException {
        assertThat(PR_GATE).exists().isRegularFile();
        assertThat(FULL_GATE).exists().isRegularFile();
        assertThat(read(PR_GATE)).contains("name: Java Gate", "jobs:");
        assertThat(read(FULL_GATE)).contains("name: Java Full Test (nightly ratchet)", "Ratchet");
    }

    @Test
    @DisplayName("every PR head runs fast gates and a fail-closed real PostgreSQL concurrency proof")
    void everyPrHeadRunsFastAndPostgresGates() throws IOException {
        String workflow = read(PR_GATE);
        Node config = new Yaml().compose(new StringReader(workflow));
        Node jobs = field(config, "jobs");

        assertThat(values(field(field(field(config, "on"), "pull_request"), "types")))
                .contains("opened", "synchronize", "reopened", "labeled");
        assertThat(values(field(field(field(config, "on"), "merge_group"), "types")))
                .contains("checks_requested");
        for (String gate : List.of("fast-contracts", "postgres-reversal-concurrency")) {
            assertThat(values(field(field(jobs, gate + "-shard"), "needs"))).containsExactly("changes");
            assertThat(values(field(field(jobs, gate), "needs")))
                    .containsExactlyInAnyOrder("changes", gate + "-shard");
        }
        assertThat(workflow)
                .containsSubsequence("fast-contracts-shard:",
                        "ref: ${{ github.event.pull_request.head.sha }}")
                .contains("java-gate-fast-patterns.txt")
                .containsSubsequence("postgres-reversal-concurrency-shard:",
                        "ref: ${{ github.event.pull_request.head.sha }}")
                .contains("java-gate-pg-tests.txt")
                .contains("bash \"$GITHUB_WORKSPACE/scripts/ci/run-with-test-postgres.sh\" \"$MVN\" -B test")
                .doesNotContain("localhost:5432/$DB");

        for (String job : List.of("fast-contracts-shard", "fast-contracts")) {
            String condition = scalar(field(field(jobs, job), "if"));
            assertThat(evaluate(condition, pr("synchronize", "", 21, "101"))).as(job).isEqualTo(true);
            assertThat(evaluate(condition, pr("synchronize", FULL_READY, 21, "102")))
                    .as("%s is covered by the final-candidate full suite", job).isEqualTo(false);
        }

        // class lists live in files (sharded since 2026-11-10); the gate classes must stay on them
        assertThat(read(REPO_ROOT.resolve("scripts/ci/java-gate-fast-patterns.txt")))
                .contains("*ContractTest", "*RepositoryQueryValidationTest", "FlywayVersionUniquenessTest",
                        "*StartupGuardTest", "*RoleGateTest", "*SecurityTest");
        assertThat(read(REPO_ROOT.resolve("scripts/ci/java-gate-pg-tests.txt")))
                .contains("ProductionScopePostgresContractTest", "FinanceScopePostgresContractTest",
                        "VoucherLedgerScopePostgresContractTest", "ReportReversalLogRepositoryPostgresConcurrencyIT",
                        "ProductionBatchRepositoryPostgresConcurrencyIT",
                        "ProductionReportCorrectionPartialUniquePostgresIT");

        assertThat(read(REPO_ROOT.resolve("scripts/ci/run-with-test-postgres.sh")))
                .contains("initdb -D \"$cluster_root/data\"")
                .contains("CRETAS_WORKFLOW_PG_VERIFY=true")
                .contains("CRETAS_WORKFLOW_PG_URL=\"jdbc:postgresql://127.0.0.1:$port/$database\"")
                .contains("database=cretas_workflow_verify_test")
                .contains("Missing CI database tool:")
                .contains("trap cleanup EXIT")
                .contains("pg_ctl -D \"$cluster_root/data\" -m fast -w stop")
                .contains("exit \"$result\"");

        assertThat(workflow)
                .as("fast gates fail directly; the full-suite failure baseline must not mask them")
                .doesNotContain("-Dmaven.test.failure.ignore=true");
    }

    @Test
    @DisplayName("PR concurrency replaces stale gate runs and isolates unrelated label events")
    void concurrencyReplacesStaleRunsWithoutCancellingOnUnrelatedLabels() throws IOException {
        Node workflow = new Yaml().compose(new StringReader(read(PR_GATE)));
        Node concurrency = field(workflow, "concurrency");
        assertConcurrencyPolicy(scalar(field(concurrency, "group")), scalar(field(concurrency, "cancel-in-progress")),
                scalar(field(field(field(workflow, "jobs"), "changes"), "if")));
    }

    @Test
    @DisplayName("negative controls: disabling cancellation or sharing the PR key with unrelated labels breaks concurrency")
    void concurrencyRejectsMissingCancellationAndSharedUnrelatedLabelGroup() throws IOException {
        Node workflow = new Yaml().compose(new StringReader(read(PR_GATE)));
        Node concurrency = field(workflow, "concurrency");
        String group = scalar(field(concurrency, "group"));
        String changes = scalar(field(field(field(workflow, "jobs"), "changes"), "if"));

        assertThatThrownBy(() -> assertConcurrencyPolicy(group, "false", changes))
                .isInstanceOf(AssertionError.class).hasMessageContaining("must cancel superseded");
        String sharedGroup = "java-pr-gate-${{ github.event.pull_request.number || github.ref }}";
        assertThatThrownBy(() -> assertConcurrencyPolicy(sharedGroup, "true", changes))
                .isInstanceOf(AssertionError.class).hasMessageContaining("unrelated labels must not share");
    }

    private static void assertConcurrencyPolicy(String group, String cancel, String changes) {
        var opened = pr("opened", "", 21, "101");
        String prGroup = groupKey(group, opened);
        for (var event : List.of(opened, pr("synchronize", "", 21, "102"), pr("reopened", "", 21, "103"),
                pr("labeled", "ci-hosted", 21, "104"), pr("labeled", FULL_READY, 21, "105"))) {
            assertThat(groupKey(group, event)).as("same PR gate concurrency key for %s", event).isEqualTo(prGroup);
            assertThat(evaluate(cancel, event)).as("new PR runs must cancel superseded runs").isEqualTo(true);
            assertThat(evaluate(changes, event)).as("eligible PR events must run changes").isEqualTo(true);
        }
        assertThat(groupKey(group, pr("opened", "", 22, "106"))).isNotEqualTo(prGroup);
        var unrelated = pr("labeled", "documentation", 21, "107");
        assertThat(groupKey(group, unrelated)).as("unrelated labels must not share the PR concurrency key")
                .isNotEqualTo(prGroup)
                .isNotEqualTo(groupKey(group, pr("labeled", "triaged", 21, "108")));
        assertThat(evaluate(changes, unrelated)).as("unrelated labels must skip changes").isEqualTo(false);
        var unrelatedOnCandidate = with(unrelated, LABEL_NAMES, List.of(FULL_READY, "documentation"));
        assertThat(groupKey(group, unrelatedOnCandidate)).isNotEqualTo(prGroup);
        assertThat(evaluate(changes, unrelatedOnCandidate)).isEqualTo(false);

        String firstRef = "refs/heads/gh-readonly-queue/main/pr-21-a";
        var firstGroup = mergeGroup(firstRef, "109");
        var secondGroup = mergeGroup("refs/heads/gh-readonly-queue/main/pr-22-b", "110");
        assertThat(groupKey(group, firstGroup)).as("different merge queue refs must not cancel one another")
                .isNotEqualTo(groupKey(group, secondGroup))
                .isEqualTo(groupKey(group, mergeGroup(firstRef, "111")));
        assertThat(evaluate(changes, firstGroup)).isEqualTo(true);
    }

    @Test
    @DisplayName("the full suite is final-candidate only and remains bound to every new labeled PR head")
    void fullSuiteRequiresFinalCandidateLabelAndSynchronizeTrigger() throws IOException {
        Node workflow = new Yaml().compose(new StringReader(read(PR_GATE)));
        Node full = field(field(workflow, "jobs"), "final-full-suite");
        assertThat(values(field(field(field(workflow, "on"), "pull_request"), "types")))
                .contains("labeled", "synchronize");
        assertThat(values(field(full, "needs"))).containsExactly("changes");
        assertThat(scalar(field(full, "uses"))).isEqualTo("./.github/workflows/java-full-nightly.yml");
        assertFullSuitePolicy(scalar(field(full, "if")));
    }

    @Test
    @DisplayName("negative control: dropping the final-candidate label admits an unlabeled PR")
    void fullSuiteRejectsMissingFinalCandidateLabelGuard() {
        String missingLabel = "${{ !cancelled() && needs.changes.result != 'skipped' && needs.changes.outputs.run != 'false' }}";
        assertThatThrownBy(() -> assertFullSuitePolicy(missingLabel))
                .isInstanceOf(AssertionError.class).hasMessageContaining("an unlabeled PR must not run");
    }

    private static void assertFullSuitePolicy(String condition) {
        var candidate = pr("synchronize", FULL_READY, 21, "112");
        assertThat(evaluate(condition, pr("synchronize", "", 21, "111")))
                .as("an unlabeled PR must not run the full suite").isEqualTo(false);
        assertThat(evaluate(condition, pr("labeled", "ci-hosted", 21, "114")))
                .as("the recovery label alone must not start the full suite").isEqualTo(false);
        assertThat(evaluate(condition, pr("labeled", FULL_READY, 21, "110")))
                .as("adding the final-candidate label must run the full suite").isEqualTo(true);
        assertThat(evaluate(condition, candidate))
                .as("a new head retaining the final-candidate label must run the full suite").isEqualTo(true);
        assertThat(evaluate(condition, mergeGroup("refs/heads/gh-readonly-queue/main/pr-21-a", "113")))
                .as("merge queue checks must not run the final-candidate suite").isEqualTo(false);
        assertThat(evaluate(condition, with(candidate, "needs.changes.result", "skipped")))
                .as("skipped changes must not start the full suite").isEqualTo(false);
        assertThat(evaluate(condition, with(candidate, "needs.changes.outputs.run", "false")))
                .as("changes outside the Java gate scope must not start the full suite").isEqualTo(false);
        assertThat(evaluate(condition, with(candidate, "cancelled", true)))
                .as("a cancelled run must not start the full suite").isEqualTo(false);
    }

    private static Node field(Node node, String key) {
        var matches = ((MappingNode) node).getValue().stream()
                .filter(entry -> scalar(entry.getKeyNode()).equals(key))
                .map(entry -> entry.getValueNode())
                .toList();
        assertThat(matches).as("workflow field %s", key).hasSize(1);
        return matches.getFirst();
    }

    private static String scalar(Node node) {
        return ((ScalarNode) node).getValue();
    }

    private static List<String> values(Node node) {
        return node instanceof SequenceNode sequence
                ? sequence.getValue().stream().map(JavaPrGateTieringContractTest::scalar).toList()
                : List.of(scalar(node));
    }

    private static Map<String, Object> pr(String action, String label, int number, String runId) {
        return Map.of(
                "github.event_name", "pull_request",
                "github.event.action", action,
                "github.event.label.name", action.equals("labeled") ? label : "",
                "github.event.pull_request.number", number,
                "github.ref", "refs/pull/" + number + "/merge",
                "github.run_id", runId,
                LABEL_NAMES, label.isEmpty() ? List.of() : List.of(label),
                "needs.changes.result", "success",
                "needs.changes.outputs.run", "true",
                "cancelled", false);
    }

    private static Map<String, Object> mergeGroup(String ref, String runId) {
        var context = with(pr("checks_requested", "", 0, runId), "github.ref", ref);
        context.put("github.event_name", "merge_group");
        context.remove("github.event.pull_request.number");
        return context;
    }

    private static Map<String, Object> with(Map<String, Object> context, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(context);
        copy.put(key, value);
        return copy;
    }

    private static String groupKey(String template, Map<String, Object> context) {
        int start = template.indexOf("${{");
        assertThat(start).as("concurrency group must depend on the workflow context").isNotNegative();
        return template.substring(0, start) + evaluate(template.substring(start), context);
    }

    private static Object evaluate(String text, Map<String, Object> context) {
        String expression = text.strip();
        if (expression.startsWith("${{")) {
            assertThat(expression).endsWith("}}");
            expression = expression.substring(3, expression.length() - 2).strip();
        }
        // Use the SpEL parser for the AST and adapt the GitHub label projection and value semantics below.
        var ast = EXPRESSIONS.parseRaw(expression.replace("github.event.pull_request.labels.*.name", LABEL_NAMES)).getAST();
        return evaluate(ast, context);
    }

    private static Object evaluate(SpelNode node, Map<String, Object> context) {
        return switch (node) {
            case Literal literal -> literal.getLiteralValue().getValue();
            case CompoundExpression path -> lookup(path.toStringAST(), context);
            case OpAnd and -> {
                Object left = evaluate(and.getChild(0), context);
                yield truthy(left) ? evaluate(and.getChild(1), context) : left;
            }
            case OpOr or -> {
                Object left = evaluate(or.getChild(0), context);
                yield truthy(left) ? left : evaluate(or.getChild(1), context);
            }
            case OperatorNot not -> !truthy(evaluate(not.getChild(0), context));
            case OpEQ equal -> equalValues(evaluate(equal.getChild(0), context), evaluate(equal.getChild(1), context));
            case OpNE unequal -> !equalValues(evaluate(unequal.getChild(0), context), evaluate(unequal.getChild(1), context));
            case MethodReference method -> evaluateFunction(method, context);
            default -> throw new AssertionError("Unsupported workflow expression: " + node.toStringAST());
        };
    }

    private static Object lookup(String path, Map<String, Object> context) {
        if (path.equals("github.event.pull_request.number")) {
            return context.getOrDefault(path, "");
        }
        assertThat(context).as("workflow context for %s", path).containsKey(path);
        return context.get(path);
    }

    private static Object evaluateFunction(MethodReference method, Map<String, Object> context) {
        return switch (method.getName()) {
            case "always" -> true;
            case "cancelled" -> context.get("cancelled");
            case "contains" -> {
                List<?> labels = (List<?>) evaluate(method.getChild(0), context);
                Object wanted = evaluate(method.getChild(1), context);
                yield labels.stream().anyMatch(label -> equalValues(label, wanted));
            }
            case "format" -> evaluate(method.getChild(0), context).toString()
                    .replace("{0}", evaluate(method.getChild(1), context).toString());
            default -> throw new AssertionError("Unsupported workflow function: " + method.getName());
        };
    }

    private static boolean equalValues(Object left, Object right) {
        return left instanceof String a && right instanceof String b ? a.equalsIgnoreCase(b) : Objects.equals(left, right);
    }

    private static boolean truthy(Object value) {
        return value != null && !Boolean.FALSE.equals(value) && !"".equals(value)
                && (!(value instanceof Number number) || number.doubleValue() != 0);
    }

    @Test
    @DisplayName("nightly full coverage and the single ratchet implementation remain intact")
    void nightlyAndReusableFullGateRemainIntact() throws IOException {
        String workflow = read(FULL_GATE);

        assertThat(workflow)
                .contains("schedule:")
                .contains("cron: '37 18 * * *'")
                .contains("workflow_call:")
                .contains("ref: ${{ github.event_name == 'pull_request'"
                        + " && github.event.pull_request.head.sha || github.sha }}")
                .contains("java-test-ratchet.py")
                .contains("maven.test.failure.ignore=true")
                .contains("steps.ratchet.outputs.rc != '0'")
                .contains("steps.ratchet.outputs.rc == '1'");
    }
}
