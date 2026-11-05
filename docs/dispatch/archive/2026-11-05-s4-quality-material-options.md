# TASKS-S4-QUALITY-READ-20261105

Owner: 01a1a954/root. Base: ecfb04cbac6283ac4e1b70592f4643be5feb44b8.
Code scope freezes into PR1469 and releases when that PR merges; the original acceptance task retains deployment/UI responsibility.

The quality disposal screen loaded its raw-material selector from the warehouse inventory endpoint. A real quality-manager request returned 403 while the quality release endpoint returned 200. Granting general inventory access would exceed this screen's need.

Added a quality-owned read-only selector, mirroring the existing finished-goods selector's module/permission boundary. It reuses the existing proxied MaterialBatchService list with DataScope and database-side AVAILABLE filtering, fixed creation-date sorting and bounded pagination. Its response projects only batch identity, material identity/name, quantity and unit; foreign-factory or non-AVAILABLE rows are rejected. The warehouse endpoint's permissions remain intact. No Repository, Entity, query or migration changed.

The screen now loads this endpoint. Submission and approval behavior remain unchanged.

Validation on the frozen change:

- DisposalMaterialBatchOptionsContractTest plus existing inventory permission/controller regressions: 11 tests, zero failures/errors/skips. The new five cases execute the real PermissionInterceptor and verify quality access, preserved warehouse-writer access, unrelated inventory-only denial, exact six-field projection, SQL-filter request parameters, foreign-factory rejection and non-AVAILABLE rejection.
- Four disposal Web suites: 18 tests passed, including quality endpoint wiring, raw quantity selection and no write during selection.
- Independent security review: no P0/P1/P2. Standalone MockMvc proves the permission/projection boundary; it is not represented as an end-to-end JWT/module/DataScope test.

The final main integration, artifact build and current-head CI remain required. After deployment, acceptance must find the actual own AVAILABLE receipt batch under the quality role and independently obtain a foreign-factory 403. An empty 200 does not pass. The QA receiving fixture and actual object/cleanup journals remain private; no signed business acceptance is asserted here.

CI on 0a2e4478 exposed two failures in GatedDataToolAiPathPermissionTest: the controller's direct call to the full MaterialBatchService list made the scanner treat the narrow quality projection as full inventory access, incorrectly requiring inventory AI tools to admit quality roles. The projection now belongs to a separate read-only DisposalMaterialBatchOptionsService capability, and the controller only returns that narrow service result. The underlying external Spring proxy still applies DataScope and factory/status filtering. No AI tool permission, scanner exclusion or baseline was changed. The HTTP test exercises the real projection service over a mocked data source. The existing failed classification test is included in the final 77-class selector; the prior 576-test artifact is historical until the new source is verified and rebuilt.