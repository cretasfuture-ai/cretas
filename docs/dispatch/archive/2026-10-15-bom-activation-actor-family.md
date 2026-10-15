# BOM activation actor family audit

- Task: `BOM-ACTIVATION-ACTOR-FAMILY-20261015`; Owner root; Base `6fb3d66f322aa563de98c2a4d6fb496e98a5044e`.
- Scope: `BomRecipeServiceImpl.activateRecipeAsActor` and `BomActivationActorFamilyContractTest`; separate shared-business fix from platform UI continuation.
- Trigger: in the isolated, synthetic test factory, activating a promoted BOM family changed its members from DRAFT to ACTIVE, then the actor-audit adapter queried DRAFT members again. It raised `BOM_FAMILY_DRAFT_REQUIRED` and rolled the transaction back.
- Change: capture the owned DRAFT family after actor authorization and before invoking the unchanged activation path; stamp the captured members afterward in the same transaction. Existing family validation, product metadata, packaging, revision and activation rules remain in force. Platform IDs still never enter the legacy users FK.
- Regression: parameterized platform/factory actor test performs the actual status transition and makes the repository return only DRAFT members. Before fix: 2 assertion failures, 0 errors; after fix plus existing add-item/activation controls: 24 tests, 0 failures/errors (`BUILD SUCCESS`). Command: `mvn test -Dtest=BomActivationActorFamilyContractTest,BomRecipeServiceImplAddItemTest -Dcretas.testIncludes=**/service/bom/impl/*.java -Dmaven.compiler.useIncrementalCompilation=false`.
- Test inclusion: class ends in `ContractTest`, covered by the repository's contract-test selectors. No Entity/Repository/query/schema/migration changes.
- Evidence: private `Documents/assembly-integration-assessment-20261015/bom-activation-family-assertion-red.log` and `bom-activation-family-green.log`; raw credentials and runtime files are excluded from Git.
- Production: untouched. Actual platform publication and later production UI acceptance are tracked separately by `PLATFORM-BOM-WORKFLOW-UI-20261015`; unit tests alone do not prove that closure.
