package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the mandatory full suite portable across runners without widening the
 * set of opt-in PostgreSQL tests that the full workflow executes.
 */
@DisplayName("Portable Java full PostgreSQL wiring")
class JavaFullPortablePostgresContractTest {

    private static final Path REPO_ROOT = Path.of("..", "..", "..").normalize();
    private static final Path FULL_WORKFLOW = REPO_ROOT.resolve(".github/workflows/java-full-nightly.yml");
    private static final Path JAVA_POM = REPO_ROOT.resolve("backend/java/cretas-api/pom.xml");
    private static final Path TEST_ROOT = REPO_ROOT.resolve("backend/java/cretas-api/src/test/java");
    private static final Path R10_PROPERTIES = REPO_ROOT.resolve(
            "backend/java/cretas-api/src/test/resources/application-r10-it.properties");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String resolve(Properties properties, String key, StandardEnvironment environment) {
        return environment.resolvePlaceholders(properties.getProperty(key));
    }

    private static Properties r10Properties() throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(R10_PROPERTIES)) {
            properties.load(input);
        }
        return properties;
    }

    private static StandardEnvironment environment(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("workflow", values));
        return environment;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int start = 0;
        while ((start = text.indexOf(needle, start)) >= 0) {
            count++;
            start += needle.length();
        }
        return count;
    }

    private static String fullSuiteRunBlock(String workflow) {
        String startMarker = "      - name: Run the full Java suite\n";
        String endMarker = "      - name: Ratchet — 已知红只许变少\n";
        int start = workflow.indexOf(startMarker);
        int end = workflow.indexOf(endMarker, start);
        assertThat(start).as("full-suite step must be present").isGreaterThanOrEqualTo(0);
        assertThat(end).as("full-suite step must end before ratchet").isGreaterThan(start);
        String step = workflow.substring(start, end);
        StringBuilder run = new StringBuilder();
        boolean inRun = false;
        for (String line : step.split("\\R", -1)) {
            if (line.equals("        run: |")) {
                inRun = true;
                continue;
            }
            if (inRun) {
                if (line.startsWith("          ")) {
                    run.append(line.substring(10)).append('\n');
                } else if (!line.isBlank()) {
                    break;
                }
            }
        }
        return run.toString();
    }

    private static String bashCommand() {
        Path gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
        return Files.exists(gitBash) ? gitBash.toString() : "bash";
    }

    private static String shellPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void markExecutable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            assertThat(path.toFile().setExecutable(true, false)).isTrue();
        }
        assertThat(path.toFile().canExecute()).as("fake workflow tool must be executable: %s", path).isTrue();
    }

    private record WorkflowRun(int exitCode, String output, String log, boolean reportExists,
                               boolean r10ReportExists) {
    }

    private static WorkflowRun runFakeWorkflow(String runBlock, Path workspace, Path log, Path report,
                                                String mode) throws Exception {
        Path script = Files.createTempFile("cretas-full-suite-run-", ".sh");
        Path r10Report = workspace.resolve(
                "target/surefire-reports/TEST-com.cretas.aims.service.config.impl.FactoryConfigServiceImplReorderIT.xml");
        try {
            Files.writeString(script, runBlock, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            ProcessBuilder builder = new ProcessBuilder(bashCommand(), script.toString())
                    .redirectErrorStream(true);
            builder.directory(workspace.toFile());
            builder.environment().put("GITHUB_WORKSPACE", shellPath(workspace));
            builder.environment().put("FAKE_LOG", shellPath(log));
            builder.environment().put("FAKE_REPORT", shellPath(report));
            builder.environment().put("FAKE_R10_REPORT", shellPath(r10Report));
            builder.environment().put("FAKE_MODE", mode);
            builder.environment().put("SPRING_DATASOURCE_URL", "jdbc:postgresql://10.99.0.8:5432/inherited_unsafe");
            builder.environment().put("SPRING_DATASOURCE_USERNAME", "inherited_unsafe_user");
            builder.environment().put("SPRING_DATASOURCE_PASSWORD", "inherited_unsafe_password");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new WorkflowRun(exitCode, output, Files.exists(log) ? Files.readString(log) : "",
                    Files.isRegularFile(report), Files.isRegularFile(r10Report));
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private static void writeFakeWorkflowTools(Path workspace) throws IOException {
        Path ci = workspace.resolve("scripts/ci");
        Files.createDirectories(ci);
        Path resolver = ci.resolve("resolve-maven.sh");
        Files.writeString(resolver, "#!/usr/bin/env bash\nprintf '%s\\n' \"$GITHUB_WORKSPACE/fake-mvn.sh\"\n",
                StandardCharsets.UTF_8);
        markExecutable(resolver);
        Path wrapper = ci.resolve("run-with-test-postgres.sh");
        Files.writeString(wrapper, """
                #!/usr/bin/env bash
                set -euo pipefail
                export SMARTBI_PG_DSN='postgresql://cretas_ci@127.0.0.1:61234/cretas_workflow_verify_test'
                export CRETAS_WORKFLOW_PG_VERIFY=true
                export CRETAS_WORKFLOW_PG_URL='jdbc:postgresql://127.0.0.1:61234/cretas_workflow_verify_test'
                export CRETAS_WORKFLOW_PG_USER=cretas_ci
                export CRETAS_WORKFLOW_PG_PASSWORD=''
                case "${FAKE_MODE:-normal}" in
                  bad-url) CRETAS_WORKFLOW_PG_URL='jdbc:postgresql://10.99.0.8:61234/cretas_workflow_verify_test' ;;
                  bad-db) CRETAS_WORKFLOW_PG_URL='jdbc:postgresql://127.0.0.1:61234/shared_database' ;;
                  bad-verify) CRETAS_WORKFLOW_PG_VERIFY=false ;;
                  bad-user) CRETAS_WORKFLOW_PG_USER=wrong_user ;;
                  bad-password) CRETAS_WORKFLOW_PG_PASSWORD=secret ;;
                esac
                "$@"
                """, StandardCharsets.UTF_8);
        markExecutable(wrapper);
        Path fakeMaven = workspace.resolve("fake-mvn.sh");
        Files.writeString(fakeMaven, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf 'CALL\\n' >> "$FAKE_LOG"
                selector=''
                for arg in "$@"; do
                  case "$arg" in
                    -Dtest=*) selector="${arg#-Dtest=}" ;;
                  esac
                  printf 'arg=%s\\n' "$arg" >> "$FAKE_LOG"
                done
                printf 'selector=%s\\n' "$selector" >> "$FAKE_LOG"
                for name in CRETAS_WORKFLOW_PG_VERIFY SMARTBI_PG_DSN CRETAS_WORKFLOW_PG_URL CRETAS_WORKFLOW_PG_USER CRETAS_WORKFLOW_PG_PASSWORD; do
                  printf '%s=%s\\n' "$name" "${!name-<unset>}" >> "$FAKE_LOG"
                done
                for name in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD; do
                  printf '%s=%s\\n' "$name" "${!name-<unset>}" >> "$FAKE_LOG"
                done
                printf 'argline_count=%s\\n' "$(printf '%s\\n' "$@" | awk '/^-DargLine=/{count++} END{print count+0}')" >> "$FAKE_LOG"
                if [[ "$selector" == '!FactoryConfigServiceImplReorderIT' ]]; then
                  case "${FAKE_MODE:-normal}" in
                    compile-failure) exit 71 ;;
                    zero-tests) exit 72 ;;
                  esac
                  mkdir -p "$(dirname "$FAKE_REPORT")"
                  printf '<testsuite name="fake-full" failures="1"><testcase name="kept-failure"/></testsuite>\\n' > "$FAKE_REPORT"
                  exit 0
                fi
                if [[ "$selector" == 'FactoryConfigServiceImplReorderIT' ]]; then
                  test -s "$FAKE_REPORT"
                  case "${FAKE_MODE:-normal}" in
                    r10-failure-no-xml) exit 74 ;;
                    r10-success-no-xml) exit 0 ;;
                  esac
                  mkdir -p "$(dirname "$FAKE_R10_REPORT")"
                  case "${FAKE_MODE:-normal}" in
                    r10-zero-tests) printf '<testsuite name="fake-r10" tests="0" skipped="0"/>\\n' > "$FAKE_R10_REPORT" ;;
                    r10-all-skipped) printf '<testsuite name="fake-r10" tests="4" skipped="4"/>\\n' > "$FAKE_R10_REPORT" ;;
                    r10-malformed) printf '<testsuite name="fake-r10" tests="4"' > "$FAKE_R10_REPORT" ;;
                    r10-test-failure) printf '<testsuite name="fake-r10" tests="4" skipped="0" failures="1" errors="0"/>\\n' > "$FAKE_R10_REPORT" ;;
                    *) printf '<testsuite name="fake-r10" tests="4" skipped="0" failures="0" errors="0"/>\\n' > "$FAKE_R10_REPORT" ;;
                  esac
                  exit 0
                fi
                exit 73
                """, StandardCharsets.UTF_8);
        markExecutable(fakeMaven);
    }

    @Test
    @DisplayName("r10 properties resolve disposable URL, user, and an intentionally empty password")
    void resolvesDisposablePostgresPropertiesAndEmptyPassword() throws IOException {
        Properties properties = r10Properties();
        StandardEnvironment disposable = environment(Map.of(
                "CRETAS_WORKFLOW_PG_URL", "jdbc:postgresql://127.0.0.1:61234/cretas_workflow_verify_test",
                "CRETAS_WORKFLOW_PG_USER", "cretas_ci",
                "CRETAS_WORKFLOW_PG_PASSWORD", ""));

        assertThat(resolve(properties, "spring.datasource.url", disposable))
                .isEqualTo("jdbc:postgresql://127.0.0.1:61234/cretas_workflow_verify_test");
        assertThat(resolve(properties, "spring.datasource.username", disposable)).isEqualTo("cretas_ci");
        assertThat(resolve(properties, "spring.datasource.password", disposable)).isEmpty();
    }

    @Test
    @DisplayName("r10 properties retain the local legacy fallback when wrapper variables are absent")
    void retainsLegacyFallback() throws IOException {
        Properties properties = r10Properties();
        StandardEnvironment legacy = environment(Map.of("R10_IT_DB", "legacy_test_database"));

        assertThat(resolve(properties, "spring.datasource.url", legacy))
                .isEqualTo("jdbc:postgresql://localhost:5432/legacy_test_database");
        assertThat(resolve(properties, "spring.datasource.username", legacy)).isEqualTo("cretas_user");
        assertThat(resolve(properties, "spring.datasource.password", legacy)).isEqualTo("cretas_pass");
        assertThat(read(R10_PROPERTIES)).doesNotContain("SPRING_DATASOURCE_URL");
    }

    @Test
    @DisplayName("Spring Environment generic datasource inheritance is overridden only by the R10 child")
    void standardDatasourceEnvironmentCannotOverrideExplicitR10Child() throws IOException {
        Properties properties = r10Properties();
        String unsafe = "jdbc:postgresql://10.99.0.8:5432/inherited_unsafe";
        String safe = "jdbc:postgresql://127.0.0.1:61234/cretas_workflow_verify_test";

        StandardEnvironment inherited = new StandardEnvironment();
        inherited.getPropertySources().addFirst(new SystemEnvironmentPropertySource("inherited", Map.of(
                "SPRING_DATASOURCE_URL", unsafe,
                "SPRING_DATASOURCE_USERNAME", "inherited_unsafe_user",
                "SPRING_DATASOURCE_PASSWORD", "inherited_unsafe_password")));
        inherited.getPropertySources().addLast(new PropertiesPropertySource("r10", properties));
        assertThat(inherited.getProperty("spring.datasource.url")).isEqualTo(unsafe);

        StandardEnvironment child = new StandardEnvironment();
        child.getPropertySources().addFirst(new SystemEnvironmentPropertySource("inherited", Map.of(
                "SPRING_DATASOURCE_URL", unsafe,
                "SPRING_DATASOURCE_USERNAME", "inherited_unsafe_user",
                "SPRING_DATASOURCE_PASSWORD", "inherited_unsafe_password")));
        child.getPropertySources().addFirst(new MapPropertySource("workflow-child", Map.of(
                "spring.datasource.url", safe,
                "spring.datasource.username", "cretas_ci",
                "spring.datasource.password", "")));
        child.getPropertySources().addLast(new PropertiesPropertySource("r10", properties));

        assertThat(child.getProperty("spring.datasource.url")).isEqualTo(safe);
        assertThat(child.getProperty("spring.datasource.username")).isEqualTo("cretas_ci");
        assertThat(child.getProperty("spring.datasource.password")).isEmpty();
    }

    @Test
    @DisplayName("full workflow uses disposable PostgreSQL while preserving the prior opt-in test surface")
    void workflowUsesPortablePostgresWithScopedEnvironment() throws IOException {
        String workflow = read(FULL_WORKFLOW);

        assertThat(workflow)
                .contains("bash \"$GITHUB_WORKSPACE/scripts/ci/run-with-test-postgres.sh\" bash -c '")
                .contains("env -u CRETAS_WORKFLOW_PG_VERIFY")
                .contains("-u SMARTBI_PG_DSN")
                .contains("-u CRETAS_WORKFLOW_PG_URL")
                .contains("-u CRETAS_WORKFLOW_PG_USER")
                .contains("-u CRETAS_WORKFLOW_PG_PASSWORD")
                .contains("-Dtest=!FactoryConfigServiceImplReorderIT")
                .contains("-Dtest=FactoryConfigServiceImplReorderIT")
                .contains("-Dspring.test.context.cache.maxSize=2")
                .contains("-XX:+EnableDynamicAgentLoading -Xshare:off -Xmx3g -XX:+HeapDumpOnOutOfMemoryError")
                .contains("id: full-suite")
                .contains("Full-suite instrument gate")
                .contains("steps.full-suite.outcome")
                .contains("R10_REPORT=target/surefire-reports/TEST-com.cretas.aims.service.config.impl.FactoryConfigServiceImplReorderIT.xml")
                .contains("SPRING_DATASOURCE_URL=\"$CRETAS_WORKFLOW_PG_URL\"")
                .contains("SPRING_DATASOURCE_USERNAME=\"$CRETAS_WORKFLOW_PG_USER\"")
                .contains("SPRING_DATASOURCE_PASSWORD=\"$CRETAS_WORKFLOW_PG_PASSWORD\"")
                .contains("R10 instrument failure: wrapper URL is not an isolated loopback verify database")
                .contains("R10_PORT_NUMBER=$((10#$R10_PORT))")
                .contains("python3 - \"$R10_REPORT\" <<PY")
                .contains("ElementTree.parse(path)")
                .contains("tests - skipped <= 0")
                .doesNotContain("Bootstrap the r10-it PostgreSQL database")
                .doesNotContain("createdb -O cretas_user")
                .doesNotContain("R10_IT_DB=$DB");

        assertThat(workflow.indexOf("env -u CRETAS_WORKFLOW_PG_VERIFY"))
                .isLessThan(workflow.indexOf("\"$MVN\" -B test"));
        assertThat(workflow.indexOf("-Dtest=!FactoryConfigServiceImplReorderIT"))
                .isLessThan(workflow.indexOf("-Dtest=FactoryConfigServiceImplReorderIT"));
        assertThat(occurrences(workflow, "run-with-test-postgres.sh")).isEqualTo(1);
        assertThat(occurrences(workflow, "-Dspring.test.context.cache.maxSize=2")).isEqualTo(2);
        assertThat(workflow).contains("set -euo pipefail");
    }

    @Test
    @DisplayName("full-suite run block is valid Bash, including the wrapper child command")
    void fullSuiteRunBlockHasValidShellSyntax() throws Exception {
        String runBlock = fullSuiteRunBlock(read(FULL_WORKFLOW));
        Path script = Files.createTempFile("cretas-full-suite-", ".sh");
        try {
            Files.writeString(script, runBlock, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            String bash = Files.exists(Path.of("C:/Program Files/Git/bin/bash.exe"))
                    ? "C:/Program Files/Git/bin/bash.exe" : "bash";
            Process process = new ProcessBuilder(bash, "-n", script.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as("Bash syntax: %s", output).isZero();
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("workflow Bash execution scopes PG variables and propagates real phase failures")
    void executesWorkflowWithFakeWrapperAndMaven() throws Exception {
        String runBlock = fullSuiteRunBlock(read(FULL_WORKFLOW));
        Path workspace = Files.createTempDirectory("cretas-full-workflow-fixture-");
        Path log = workspace.resolve("calls.log");
        Path report = workspace.resolve("target/surefire-reports/failure.xml");
        Path r10Report = workspace.resolve(
                "target/surefire-reports/TEST-com.cretas.aims.service.config.impl.FactoryConfigServiceImplReorderIT.xml");
        try {
            writeFakeWorkflowTools(workspace);

            WorkflowRun normal = runFakeWorkflow(runBlock, workspace, log, report, "normal");
            assertThat(normal.exitCode()).as(normal.output()).isZero();
            assertThat(normal.reportExists()).isTrue();
            assertThat(normal.r10ReportExists()).isTrue();
            assertThat(Files.readString(report)).contains("kept-failure");
            assertThat(normal.log())
                    .containsSubsequence(
                            "CALL\n",
                            "selector=!FactoryConfigServiceImplReorderIT",
                            "CRETAS_WORKFLOW_PG_VERIFY=<unset>",
                            "SMARTBI_PG_DSN=<unset>",
                            "CRETAS_WORKFLOW_PG_URL=<unset>",
                            "CRETAS_WORKFLOW_PG_USER=<unset>",
                            "CRETAS_WORKFLOW_PG_PASSWORD=<unset>",
                            "SPRING_DATASOURCE_URL=<unset>",
                            "SPRING_DATASOURCE_USERNAME=<unset>",
                            "SPRING_DATASOURCE_PASSWORD=<unset>",
                            "CALL\n",
                            "selector=FactoryConfigServiceImplReorderIT",
                            "CRETAS_WORKFLOW_PG_VERIFY=true",
                            "SMARTBI_PG_DSN=postgresql://cretas_ci@127.0.0.1:61234/cretas_workflow_verify_test",
                            "CRETAS_WORKFLOW_PG_URL=jdbc:postgresql://127.0.0.1:61234/cretas_workflow_verify_test",
                            "CRETAS_WORKFLOW_PG_USER=cretas_ci",
                            "CRETAS_WORKFLOW_PG_PASSWORD=",
                            "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:61234/cretas_workflow_verify_test",
                            "SPRING_DATASOURCE_USERNAME=cretas_ci",
                            "SPRING_DATASOURCE_PASSWORD=")
                    .contains("arg=-Dmaven.test.failure.ignore=true")
                    .contains("arg=-Dspring.profiles.active=test")
                    .contains("arg=-DargLine=-XX:+EnableDynamicAgentLoading -Xshare:off -Xmx3g -XX:+HeapDumpOnOutOfMemoryError -Dspring.test.context.cache.maxSize=2")
                    .contains("argline_count=1");
            assertThat(occurrences(normal.log(), "CALL\n")).isEqualTo(2);
            assertThat(occurrences(normal.log(), "arg=-Dspring.profiles.active=test")).isEqualTo(2);
            assertThat(occurrences(normal.log(), "argline_count=1")).isEqualTo(2);

            Files.deleteIfExists(log);
            Files.deleteIfExists(report);
            Files.deleteIfExists(r10Report);
            WorkflowRun compileFailure = runFakeWorkflow(runBlock, workspace, log, report, "compile-failure");
            assertThat(compileFailure.exitCode()).isNotZero();
            assertThat(occurrences(compileFailure.log(), "CALL\n")).isEqualTo(1);
            assertThat(compileFailure.reportExists()).isFalse();
            assertThat(compileFailure.r10ReportExists()).isFalse();

            Files.deleteIfExists(log);
            Files.deleteIfExists(report);
            Files.deleteIfExists(r10Report);
            WorkflowRun zeroTests = runFakeWorkflow(runBlock, workspace, log, report, "zero-tests");
            assertThat(zeroTests.exitCode()).isNotZero();
            assertThat(occurrences(zeroTests.log(), "CALL\n")).isEqualTo(1);
            assertThat(zeroTests.reportExists()).isFalse();
            assertThat(zeroTests.r10ReportExists()).isFalse();

            Files.deleteIfExists(log);
            Files.deleteIfExists(report);
            Files.deleteIfExists(r10Report);
            WorkflowRun r10Failure = runFakeWorkflow(runBlock, workspace, log, report, "r10-failure-no-xml");
            assertThat(r10Failure.exitCode()).isNotZero();
            assertThat(occurrences(r10Failure.log(), "CALL\n")).isEqualTo(2);
            assertThat(r10Failure.reportExists()).isTrue();
            assertThat(r10Failure.r10ReportExists()).isFalse();

            Files.deleteIfExists(log);
            Files.deleteIfExists(report);
            Files.deleteIfExists(r10Report);
            WorkflowRun staleR10 = runFakeWorkflow(runBlock, workspace, log, report, "r10-success-no-xml");
            assertThat(staleR10.exitCode()).isNotZero();
            assertThat(occurrences(staleR10.log(), "CALL\n")).isEqualTo(2);
            assertThat(staleR10.reportExists()).isTrue();
            assertThat(staleR10.r10ReportExists()).isFalse();

            for (String invalidWrapper : List.of("bad-url", "bad-db", "bad-verify", "bad-user", "bad-password")) {
                Files.deleteIfExists(log);
                Files.deleteIfExists(report);
                Files.deleteIfExists(r10Report);
                WorkflowRun rejected = runFakeWorkflow(runBlock, workspace, log, report, invalidWrapper);
                assertThat(rejected.exitCode()).as(invalidWrapper).isNotZero();
                assertThat(occurrences(rejected.log(), "CALL\n")).as(invalidWrapper).isEqualTo(1);
                assertThat(rejected.r10ReportExists()).as(invalidWrapper).isFalse();
            }

            for (String invalidXml : List.of("r10-zero-tests", "r10-all-skipped", "r10-malformed")) {
                Files.deleteIfExists(log);
                Files.deleteIfExists(report);
                Files.deleteIfExists(r10Report);
                WorkflowRun rejected = runFakeWorkflow(runBlock, workspace, log, report, invalidXml);
                assertThat(rejected.exitCode()).as(invalidXml).isNotZero();
                assertThat(occurrences(rejected.log(), "CALL\n")).as(invalidXml).isEqualTo(2);
                assertThat(rejected.reportExists()).as(invalidXml).isTrue();
                assertThat(rejected.r10ReportExists()).as(invalidXml).isTrue();
            }

            Files.deleteIfExists(log);
            Files.deleteIfExists(report);
            Files.deleteIfExists(r10Report);
            WorkflowRun testFailure = runFakeWorkflow(runBlock, workspace, log, report, "r10-test-failure");
            assertThat(testFailure.exitCode()).isZero();
            assertThat(occurrences(testFailure.log(), "CALL\n")).isEqualTo(2);
            assertThat(testFailure.r10ReportExists()).isTrue();
        } finally {
            try (Stream<Path> paths = Files.walk(workspace)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("negative plus exact r10 selector covers the same Test/Tests/IT include surface")
    void complementarySelectorsEqualSurefireDefaultIncludes() throws IOException {
        String pom = read(JAVA_POM);
        assertThat(pom)
                .contains("<include>**/*Test.java</include>")
                .contains("<include>**/*Tests.java</include>")
                .contains("<include>**/*IT.java</include>");

        Set<String> defaultIncludes;
        try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
            defaultIncludes = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Test.java")
                            || name.endsWith("Tests.java")
                            || name.endsWith("IT.java"))
                    .collect(Collectors.toCollection(HashSet::new));
        }
        String r10Class = "FactoryConfigServiceImplReorderIT.java";
        assertThat(defaultIncludes).contains(r10Class);

        Set<String> firstRun = defaultIncludes.stream()
                .filter(name -> !name.equals(r10Class))
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> splitUnion = new HashSet<>(firstRun);
        splitUnion.add(r10Class);

        assertThat(splitUnion)
                .as("the negative default selector plus exact r10 selector must be a set-preserving split")
                .containsExactlyInAnyOrderElementsOf(defaultIncludes);
        assertThat(List.of("!FactoryConfigServiceImplReorderIT", "FactoryConfigServiceImplReorderIT"))
                .containsExactly("!FactoryConfigServiceImplReorderIT", "FactoryConfigServiceImplReorderIT");
    }
}
