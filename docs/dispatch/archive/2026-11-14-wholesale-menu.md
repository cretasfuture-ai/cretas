# WHOLESALE-MENU-20261114

Owner: Codex/root. Base: `f3eee9125b280d1221d71c1c3f2895feafaae628`.
User approved one temporary additional worktree over the existing count limit. Implementation and release used `C:/Users/Steve/cretas-wholesaler-menu-20261114`; no other worktree was removed. Code scope is released.

Implemented and merged [PR #1727](https://github.com/cretasfuture-ai/cretas/pull/1727), reviewed head `cff8032779ae5097903e47a917782926c1e1b195`, merge/release source `ee6e7dd7803175857750603adba0fba37732fa5a`.

- Platform menu catalog now covers the union of legacy and grouped real leaves, including weighing, WIP and top-level entries; exact module/path guards remain.
- Explicit per-factory `dashboard.config.businessNavigationProfile=wholesale` moves existing supplier/customer directory routes into procurement/sales and labels remaining master data 基础资料. Existing guards, routes and default layouts are preserved. Sidebar and Header share the current tenant snapshot profile.
- Only `WHOLESALE_0000_001` was configured: weighing/WIP hidden, SKU retained, analytics visible, wholesale profile enabled. Other eight factories' full feature-config hashes matched before/after each final application; none had the opt-in profile.

Verification: 49 menu/catalog/mounted tests, 8 navigation/breadcrumb tests, 25 isolation/mounted Header tests (overlap between selections); `vue-tsc -b` exit 0; `PlatformFactoryMenuContractTest` 9/9; PR checks all passed. No Entity/Repository/JPQL/migration change. Independent risk review passed with operational backup/rollback conditions fulfilled.

Candidate and clean exact-main builds passed. A concurrent canonical main release published the same exact source; its complete successful receipts were reused rather than triggering another rollout: `cretas-1794670790-22707.json`, status=deployed, exit_code=0; Java/Web outcomes=deployed, both child exit codes=0; backend green/10020 healthy; Web local/server/gateway/public index hashes all `26ef6618af9b2d02207d17486ecb47a08ab92ff40d58faa79ca214c3488ea977`. Receipt copy and task evidence are in the coordinator's private `wholesaler-20261114` task folder. Earlier build/deploy attempts rejected by a genuinely active release lock did not change the service; no lock was deleted or another task killed.

Exact trial tenant pre-write backup is immutable. Verified old-catalog-compatible restore on N-1, then actual application → rollback/fresh readback → reapplication/fresh readback. Receipts include UTC time, backup/script/runtime hashes and HTTP status/path records; mismatching fresh configuration stops the operation before overwriting it.

Real UI: procurement → supplier directory and sales → customer directory opened with matching breadcrumbs; 基础资料 → SKU opened; production/weighing absent; 数据与分析 → 进销存总览 opened without page errors, with the expected empty trial data. Existing F006 QA login kept the default 主数据 layout and original production menu; no business submit controls were used. An older F001 credential was rejected, was not reset, and was replaced by the existing valid QA account for the read-only comparison. Returned the browser to the trial account.

This closes menu configuration/layout only. Wholesale-specific tenant type/homepage terminology, digital employee execution, multimodal intake, configurable automatic allocation, trade-SKU stock linkage and direct-delivery fulfilment remain separate development work. No actual customer orders, stock, payment or invoicing transaction was submitted for this task.
