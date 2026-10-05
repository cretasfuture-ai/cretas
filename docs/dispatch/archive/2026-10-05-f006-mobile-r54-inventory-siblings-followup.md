# F006 R54 inventory sibling investigation — 2026-10-05

Queued read-only investigation; Owner /root. Base 90e292646f4daee51012b258fb51c187da72573d. No source scope is claimed or authorized by this ticket. Root must register exact scope and commands before implementation. R53 is only stock summary wiring and does not close these items.

Same-cause source receipt: private `r51-r53-inventory-same-cause-sweep.json`, SHA256 994aabdb9521111a7f716f57086b1e2909288baaE48ebfc6aec05586121892cb (case-insensitive). No live leakage was tested.

- `MaterialBatchQueryTool.java` calls scoped `MaterialBatchServiceImpl.getMaterialBatchList` from the default legacy lane; its local material/date filters and ignored PageRequest status need separate paging semantics evidence. Test actual ToolDispatch/registry/service with rows A/B/F007, trusted SELF A/B and ALL boss, actual serialized prices, and filtered totals. First reproduce independently without changing module or row grants.
- `MaterialBatchWipQueryTool.java` calls unannotated `MaterialBatchServiceImpl.getWipBatches` and is not runtime-approved (LEGACY_INFERRED/REVIEW_REQUIRED). Compare the actual HTTP permission contract and real configured data scope before deciding an admission or row-filter fix. Test nonempty WIP A/B/F007 plus canonical deny, boss and warehouse controls; do not silently promote it into the approved policy.
- `MaterialExpiredQueryTool.java` calls unannotated `MaterialBatchServiceImpl.getExpiredBatches`; its simplified output does not copy price fields. Compare HTTP admission/data scope and verify nonempty expired A/B/F007 rows with ordinary dispatch and fixed workflow. Gateway identity binding alone does not add a missing service scope.

Evidence remains source risk until actual behavioral tests reproduce a violation of the agreed contract. No migration, Entity/Repository query edits, global switch activation, role grants, device/provider requests or business writes. Any future Repository/query edits require the real JPA context gate. Keep the campaign incomplete until these and pending device/Mini workflows are resolved.
