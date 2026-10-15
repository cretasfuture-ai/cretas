# SHARED-PLATFORM-IDENTITY-20261015

Base: `e33d02b2cf6b4a2984eb072b55044250ef7fd053`. Owner: Codex root.

The flat login response previously caused Web to represent platform accounts
as factory users. Add an explicit account namespace to platform login/refresh
responses and signed access tokens. Keep numeric IDs, existing token claims,
factory token issuance, and ordinary factory JSON shape (null actorType omitted).

Web consumes the explicit marker, retains the platform role, and preserves
the existing `platform_admin` menu/permission alias for platform super_admin.
Role-only responses and equal numeric factory IDs stay in the factory namespace.
Malformed platform responses with factory scope are rejected before persistence.
Old platform access tokens remain usable for old APIs; the separate assembly
boundary requires a newly issued explicit platform token (re-login/refresh).

Validation:

- Java service regression: 8 tests passed, including factory response JSON,
  platform login/refresh issuer wiring, and ordinary module permissions.
- Real signed JWT contract: 2 passed; same numeric factory ID and a generic
  token containing only factoryId=PLATFORM cannot become explicit platform.
- Mutation: remove issuer actorType claim, compile actual mutated JwtUtil,
  run JWT contract: 2 tests, 1 assertion failure, 0 errors. Restore exact
  source and recompile: both tests pass. No compile error counted as red.
- Web: 17 relevant tests passed across auth namespace/fullName, assembly
  capability and route boundaries; vue-tsc and Vite build passed. The shared
  auth files were verified byte-identical to the tested integration worktree.
- Independent scoped security/compatibility review: PASS after NON_NULL
  serialization and negative JWT argument-order corrections.

No migrations, production access, deployment, or automatic factory changes.
Actual platform assembly UI acceptance is a separate integration task.
