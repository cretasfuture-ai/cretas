# ERP-S309-OPENING-PACKAGING-20261024

Base SHA: `8b1dbc6a4e04936dc2b07c592ffc1610ae869aae`.
Worktree: `C:/Users/Steve/cretas-s309-opening-packaging-20261024`.
Branch: `codex/s309-opening-packaging-20261024`.
Source write Owner: `/root/s305_correction_boundary`; Root alone owns dispatch, integration and release. Original QA alone owns browser/device/canonical CSV.

## Evidence and boundary

Original QA used the normal published inventory hub and opening form. Base-unit inventory plus an explicitly selected packaging specification was offered by the UI but rejected with `PACKAGING_SPEC_UNIT_MISMATCH`. Independent database readback confirmed zero added inventory and no changes in nineteen protected business groups. Preserve that failure. Existing UI requires an explicit specification for ambiguous base-unit inventory; do not delete its assertions or clear the selection to evade this case.

## Exclusive source scope

1. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/product/ProductPackagingSpecService.java`
2. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/product/impl/ProductPackagingSpecServiceImpl.java`
3. `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/inventory/SalesController.java`
4. `backend/java/cretas-api/src/test/java/com/cretas/aims/service/product/impl/ProductPackagingSpecServiceImplTest.java`
5. `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/inventory/SalesFinishedGoodsPriceContractTest.java`
6. `backend/java/cretas-api/src/test/java/com/cretas/aims/service/inventory/FgQuantityUnitConverterTest.java`

Add an opening-specific selection method, using existing tenant/product/active repository lookups. General transaction selection retains its package-unit-only contract. Opening selection may bind either the selected package unit or its base unit; preserve the original quantity and complete packaging snapshot. Multiple matching specifications without an explicit choice must reject. Invalid, inactive, other-tenant/product, unknown-unit selections remain rejected. No Web assertion, Entity, Repository/query, migration, permission, workflow, procurement, supplier or converter-source changes.

## Acceptance

Development: `mvn test -Dtest=ProductPackagingSpecServiceImplTest,SalesFinishedGoodsPriceContractTest,FgQuantityUnitConverterTest` with the same three classes in `cretas.testIncludes`, isolated worktree/target and measured local resource limits. Preserve all original assertions and failures. Cover 16 base units with factor 8, alternate explicit factors, omitted ambiguous selection, tenant/product/inactive/unknown selection, unchanged general resolver behavior, and subsequent 16 base units to 2 outer units without double conversion. No JPA/query changes are authorized; any such need requires a new scope review and real JPA gate.

Root reviews exact diff and necessary final checks, then manual PR merge and official clean exact-main publication. Original QA repeats the same normal opening flow and Root supplies fresh independent database readback. Implementation, checks, merge, publication and complete S3-09 acceptance remain separate. Archive/remove the ACTIVE row in the final delivery commit; no business acceptance is implied by that release.

## Integration handoff

Source Owner released the six-file scope to Root. The original insufficient Controller mock test was retained as an old review failure; a new Controller test uses the real packaging resolver and captures the 16-base-unit save with all five packaging snapshot fields. The service test also checks explicit choice among 8 and 12 factors. Controller 5 and service 10 tests passed; unchanged converter 22-test evidence is reused. Independent review v2 closes the earlier verification gap. Private evidence: `execution/coordination/s309-opening-packaging-20261024/implementation-receipt-v2.json` and `risk-review-v2.md`.

This change is integrated with ERP-S301-BINDING-INHERIT-20261024 in one final candidate. Root owns final checks, manual merge, official release, and original-QA scheduling. This archive is the scope handoff, not a claim that pending CI, deployment, or S3-09 business acceptance has passed.
