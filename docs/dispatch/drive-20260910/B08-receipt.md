# B08 CI shared-H2 isolation receipt

- Task: DRIVE-20260910-B08-CI-DB-ISOLATION
- Owner: task 01a08b80-648a-73d1-88ab-f4030b763787
- Base: 4639d596ee619ae0e1567002c6d2b966e6d6130a
- Worktree: C:/Users/Steve/cretas-drive-20260910-b08
- Branch: codex/drive-20260910-b08-ci-isolation

## Change and review

ProductProcessWorkflowRuntimeTransactionTest now uses its own H2 database instead of shared testdb. Its create-drop mode, PostgreSQL compatibility options, transactional test behavior and all assertions are unchanged. No production Java, Entity/Repository, global test configuration, CI workflow, dates, known-failure lists or ACTIVE/Drive edits.

The only test-source diff is the dedicated URL and explanatory comment. The remaining files are this receipt, B08-root-cause.md and the dedicated B08ContextSequence.java reproducer. Self-review checked the inherited H2 options against application-test.properties, database-name uniqueness, scope and whitespace. No new business behavior or schema migration.

## Verification method

Compiled the clean base in this worktree with `mvn -q -DskipTests test-compile` (exit 0). This creates no release manifest and is not a release JAR build. The B08ContextSequence JUnit Launcher then ran the real classes, all methods and lifecycle callbacks, in this fixed order within one JVM:

1. ProductProcessWorkflowRuntimeTransactionTest — 2 tests, real DataJpaTest/Hibernate context.
2. SalesOrderFinanceApprovedFanOutIntegrationTest — 1 test, real SpringBootTest context.
3. FgReservationLedgerServiceTest — 10 tests, real DataJpaTest/Hibernate context.

After reproducing the base failure, only the changed test class was compiled with javac using that same test dependency classpath and existing B08 classes (`-proc:none -encoding UTF-8`); exit 0. Both fixed runs use this same compiled class. No other worktree/runner target was read for mutable output or overwritten; the classpath dependency list was reused, replacing its target entries with this worktree's freshly compiled target.

| Source | Cache capacity | Workflow | Sales | FG | Process result |
|---|---:|---:|---:|---:|---|
| Base | 2 | 2/2 | 1/1 | 0/10 | exit 1; ten missing-table errors reproduced |
| Fixed | 2 | 2/2 | 1/1 | 10/10 | exit 0 |
| Fixed | 1 | 2/2 | 1/1 | 10/10 | exit 0 |

The base failure is evidence of reproduction, not a passing product test. Both fixed sequences passed 13/13 with zero failures, aborted or skipped cases; the compiled class hash was unchanged after both runs. These are real JPA contexts with in-memory H2, not PostgreSQL/production acceptance.

## Reuse

Target selector: `ProductProcessWorkflowRuntimeTransactionTest,SalesOrderFinanceApprovedFanOutIntegrationTest,FgReservationLedgerServiceTest`. A Maven selector alone does not guarantee class order: use B08ContextSequence.java when verifying eviction order.

Runtime classpath must contain target/test-classes, target/classes, project test dependencies, plus junit-platform-launcher 1.10.5 (matching project JUnit Platform). Invoke Java with `-Dspring.profiles.active=test -Dspring.test.context.cache.maxSize=2` (then 1) and this classpath, passing the reproducer source path. Local exact argfiles are b08-sequence-cache2.args and b08-sequence-cache1.args; they contain local dependency paths and are retained untracked.

Evidence retained at worktree root: b08-baseline-compile.log, b08-baseline-cache2.log, b08-fixed-test-compile.log, b08-fixed-cache2.log, b08-fixed-cache1.log. Full-suite CI was not rerun by this task; coordinator must retain its required exact-head gate. No PR, merge, deployment or production writes by B08.

Final test-source SHA256: 941CC1F8229664E66B56A44CF840F5352ECD54731B258DF6A943AD1F231F5F03.
Compiled outer-class SHA256: A164D841A1B27A043C42EB04B78A89900459F99AA53814301A5AD2D5B4B3CAA1.
