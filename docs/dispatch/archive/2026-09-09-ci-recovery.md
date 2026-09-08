# CI recovery closeout

Task `FIX-CI-RECOVERY-20260909`, owner Codex, base `83c0e2a2023487c3033d757d7912f58d3ef01d21`, PR #758.

This closeout is included in the final candidate commit and takes effect when the PR merges. Before merge the PR remains the sole writer; final CI checks must be read back separately.

- Recovered the existing Windows WSL keepalive and verified real jobs on organization runner `windows-wsl-ci`, preserving filesystem, resource and production-network isolation.
- Fixed Windows comment matching and PID fixture semantics, monotonic timing, Node/npm preparation and relocated Python venv creation.
- Adopted the Ops and soft-delete scan checks through the shared runner registry. Hosted billing was not changed.
- Resolved `CI-PYTHON-PG-TESTS-WOKE-UP-AND-FAIL-20260907` through a fresh local PostgreSQL cluster per command, with cleanup and failure-code preservation. Local 39 PostgreSQL tests and real lifecycle controls passed. PR Python Gate run `34259128685` reported 9,650 passed and only the known registry audit-date failure; no database authentication errors remained.
- The AI quota review is still blocked on current console evidence. Its failing test is retained; this closeout does not claim all CI is green or update the registry date.
- Docker-dependent lanes, other migration drafts, production credentials and business data were not changed. Remaining migration tickets stay in ACTIVE.

Detailed evidence and validation boundaries: `docs/ops/2026-09-09-ci-recovery.md`; local evidence directory `D:/Temp/cretas-ci-recovery-20260909/`.
