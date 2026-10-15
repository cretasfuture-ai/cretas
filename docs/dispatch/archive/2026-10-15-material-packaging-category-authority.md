# Shared packaging category authority

Base: `095ce82d2fc357fa415ea55f8612579cf011bbb1`. Owner: Codex root.

Use MaterialMainCategory.of to classify packaging in the existing material
creation service. All four accepted aliases (包材, 包辅材, PACKAGING, PACKAGE)
must require pricing and clear storage type. Ordinary raw material creation
retains storage and optional pricing. No assembly feature, migration, or
production operation is included.

Validation on the actual service:

- Baseline: 9 tests, 4 assertion failures, 0 errors.
- Fix plus existing storage/pricing guards: 34 tests passed.
- Mutation restored the original helper in the actual source: 9 tests,
  4 assertion failures, 0 errors; no compilation failure counted as red.
- Exact source bytes restored and all 9 contract tests passed again.
- Mutation source SHA256: bd707ea0b533daa359faf525c323d0578afdc4bc63bb281cf2e3550f2eb0ebe6.
- Mutant SHA256: b233d7d68f07cc8935b9639a6505021c717d9186d5a7d6a9503f3f97d1f7ace3.

Contract class ends in ContractTest and is selected by the automatic Java
gate selector. CI execution is recorded separately in the PR after push.
