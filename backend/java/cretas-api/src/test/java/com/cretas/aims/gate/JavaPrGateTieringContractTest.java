package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the Java PR gate fast during iteration without weakening the final-candidate proof.
 */
@DisplayName("Java PR gate tiering")
class JavaPrGateTieringContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..", "..").normalize();
    private static final Path PR_GATE = REPO_ROOT.resolve(".github/workflows/java-gate-pr.yml");
    private static final Path FULL_GATE = REPO_ROOT.resolve(".github/workflows/java-full-nightly.yml");

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

        assertThat(workflow)
                .contains("types: [opened, synchronize, reopened, labeled]")
                .containsOnlyOnce("group: java-pr-gate-${{ github.event.pull_request.number }}")
                .containsSubsequence("fast-contracts:",
                        "if: ${{ !contains(github.event.pull_request.labels.*.name, 'java-full-ready') }}",
                        "ref: ${{ github.event.pull_request.head.sha }}")
                .contains("fast-contracts:")
                .contains("*ContractTest")
                .contains("*RepositoryQueryValidationTest")
                .contains("FlywayVersionUniquenessTest")
                .contains("*StartupGuardTest")
                .contains("*RoleGateTest")
                .contains("*SecurityTest")
                .containsSubsequence("postgres-reversal-concurrency:",
                        "ref: ${{ github.event.pull_request.head.sha }}")
                .contains("postgres-reversal-concurrency:")
                .contains("bash \"$GITHUB_WORKSPACE/scripts/ci/run-with-test-postgres.sh\" \"$MVN\" -B test")
                .contains("-Dtest=ProductionScopePostgresContractTest,FinanceScopePostgresContractTest,"
                        + "VoucherLedgerScopePostgresContractTest,ReportReversalLogRepositoryPostgresConcurrencyIT,"
                        + "ProductionBatchRepositoryPostgresConcurrencyIT,ProductionReportCorrectionPartialUniquePostgresIT")
                .doesNotContain("localhost:5432/$DB");

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
    @DisplayName("the full suite is final-candidate only and remains bound to every new labeled PR head")
    void fullSuiteRequiresFinalCandidateLabelAndSynchronizeTrigger() throws IOException {
        String workflow = read(PR_GATE);
        Pattern labeledFullJob = Pattern.compile(
                "final-full-suite:\\s*\\n"
                        + "\\s*if: \\$\\{\\{ contains\\(github\\.event\\.pull_request\\.labels\\.\\*\\.name, "
                        + "'java-full-ready'\\) \\}\\}\\s*\\n"
                        + "\\s*uses: \\.\\/.github/workflows/java-full-nightly\\.yml",
                Pattern.MULTILINE);

        assertThat(labeledFullJob.matcher(workflow).find())
                .as("the full suite must be guarded by the final-candidate label on the same PR run")
                .isTrue();
        assertThat(workflow)
                .as("the final candidate must not waste a third runner slot on tests already inside the full suite")
                .contains("if: ${{ !contains(github.event.pull_request.labels.*.name, 'java-full-ready') }}");
        assertThat(workflow)
                .contains("synchronize")
                .contains("cancel-in-progress: true");
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
