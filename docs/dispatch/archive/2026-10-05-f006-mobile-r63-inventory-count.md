# F006 mobile inventory count correction

Task F006-MOBILE-R63-20261005; owner root/mobile-r63; base f7892e2a1cd028ce3a2a840c4ea5483040a858a2. Scope: WHInventoryListScreen and its price-visibility/pagination screen tests. Source scope is released in this candidate commit; PR merge, CI and native APK acceptance are separate subsequent facts.

Actual Xiaomi F006 warehouse-worker inventory showed one visible batch but a header of 220 batches. The shared statistics endpoint intentionally has a factory-wide contract. The screen now derives batch and warning counts from the current actor's complete paginated list, while preserving the statistics request, authorized value card, quantity units, identity guards and pagination failure handling. No backend, shared API, permissions or navigation change.

Worker RED failed four assertions in 35 tests against the previous rendered count. Final coordinator verification: two screen suites, 35 tests passed, with explicit one-visible-batch versus factory-wide 220/99 controls, 51-row pagination, warning counts, quantity retention, denied/authorized/null/zero price and owner-switch controls. Existing ambiguous quantity/count assertions were replaced with precise checks. No assertions were deleted to pass tests.

Command: `npx jest src/__tests__/integration/screens/WHInventoryPriceVisibility.test.tsx src/__tests__/integration/screens/WHInventoryPaginationSearch.test.tsx --runInBand --no-cache`. Private immutable logs/screenshots remain outside Git in the dated F006 phone-test evidence directory. Dedicated test APK build/install and original one-batch phone readback remain pending at this source checkpoint; no public APK/OTA or production business mutation has occurred.
