# B08 shared H2 schema eviction

Task: DRIVE-20260910-B08-CI-DB-ISOLATION. Base: 4639d596ee619ae0e1567002c6d2b966e6d6130a.

CI job 102921619807 used cache.maxSize=2 (failed log lines 232/316), not 1. Its last three newly started Spring contexts were ProductProcessWorkflowRuntimeTransactionTest (782122), SalesOrderFinanceApprovedFanOutIntegrationTest (792167), FgReservationLedgerServiceTest (805212).

Fg created finished_goods_batches at line 795596. After Fg context startup, the table was dropped again at 805353; its first test insert then failed with an empty database at 805659. All ten errors arose during fixture creation, before the ledger business assertions.

ProductProcessWorkflowRuntimeTransactionTest:47-52 explicitly selects create-drop while using Replace.NONE and profile test. It inherits the shared jdbc:h2:mem:testdb URL. When inserting the third context into a cache of capacity two, eviction closes the oldest context and Hibernate drops that shared schema. Global application-test.properties:11 uses DB_CLOSE_DELAY=-1, which preserves database lifetime but does not prevent explicit DROP TABLE; its global ddl-auto=create is overridden by the producer test.

The producer, victim and global profile files have no changes between e03fc7cf and this task base. This is an existing test isolation fault exposed by context eviction/order, not evidence of a production ledger regression. Increasing cache size only delays exposure and is not the proposed repair.

The narrow fix gives the create-drop producer its own H2 database, preserving create-drop, PostgreSQL compatibility settings, transactions and all original assertions. Validation uses B08ContextSequence.java to execute the actual three JUnit classes in one JVM and fixed order; no replacement business assertions or skipped cases.
