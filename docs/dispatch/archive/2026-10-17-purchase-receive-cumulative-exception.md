# 分批补收的采购异常比较口径

- Task: `PURCHASE-RECEIVE-CUMULATIVE-EXCEPTION-20261017`; Owner: root.
- Base: `a2afbe1ddecc6bb14d2bae2112e35fd1d0d0d80e`.
- Scope: PurchaseServiceImpl exception generation arguments and its existing receive-variance test. Shared business fix is a separate PR from the exception-to-warehouse UI navigation increment.
- No production, migrations, Entity/Repository/security changes or new purchasing/receiving APIs.

## Reproduction and correction

Real UI accepted 8 of 10 boxes, recorded REQUEST_RESUPPLY, then received the remaining 2 boxes against the original PO. Two actual UI confirmations of the same supplemental receipt returned 200 / 409; inventory and payable posted only once. The order completed, but the old exception generator compared the supplemental 2 boxes with the full 10-box order and incorrectly created another UNDER_RECEIVE exception.

After updateOrderReceiveStatus has added the receipt to the order item, exception generation now compares ordered quantity with **cumulative received quantity, both in the PO item's unit**. It does not compare the individual receipt's quantity/unit with the entire order. Existing first-receipt and excess/shortage behavior remains meaningful; no historical exceptions are rewritten.

## Target evidence

The existing PurchaseServiceImplReceiveVarianceNotifyTest adds three calls through real confirmReceive and the real PurchaseExceptionServiceImpl generator: 8 + 2 = 10 produces no exception (unit 个), 7 + 1 = 8 leaves shortage 2 (unit 箱), and 8 + 3 = 11 produces excess 1 (unit kg).

- Six tests pass, zero failures/errors/skips, BUILD SUCCESS.
- Restoring the old receipt quantity/unit arguments produces three **assertion failures**, zero errors; compilation succeeds. The restored fix passes all six again.
- Private logs: resupply-cumulative-target.log, resupply-cumulative-mutant.log, resupply-cumulative-restored.log in the existing assembly-integration-assessment-20261015 evidence directory.
- The final exact-head Java checks and post-fix real UI results are recorded in the PR delivery comment. The previously accepted locking, rollback and payable gates are reused unless changed behavior requires a specific rerun.

The private pre-fix fixture and its spurious exception are retained as failure evidence. No linked history is deleted or rewritten. No production deployment or production acceptance is part of this task.
