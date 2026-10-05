---
name: deploy-backend
description: >
  Use when the user asks to deploy, publish, release, restart, roll back, or
  verify Cretas Java, Python, or web-admin services in production.
---

# Cretas Deployment

The shared source for release commands, success criteria, single-component
entries and troubleshooting is `.claude/skills/deploy-backend/SKILL.md`; the
server map, blue-green slot lookup and logs are in
`.claude/skills/server-operations/SKILL.md`. Production deploys only from a
clean tree with `HEAD == origin/main` (`.claude/rules/worktree-and-main-only-deploy.md`).
This file keeps the Codex-side detail on artifact reuse, worktree reuse and
the guarantees each script already enforces.

There is no test environment: it was decommissioned on 2026-07-13 and the
scripts reject `--env test` / `--env all`. Production is
`47.100.235.168` (Java on alternating `10010`/`10020`, Python `8083`) and
web-admin on `139.196.165.140:8086`.

## Release Entry

Build before merge, deploy right after merge. The Base SHA is the one registered
in `docs/dispatch/ACTIVE.md`.

```bash
# Before merge, in the clean reviewed candidate worktree (HEAD = PR head):
# build once and pre-upload the JAR. No install, restart or traffic switch.
./scripts/deploy/release-cretas.sh --phase build --base-sha '<registered Base SHA>' \
  --tests '<MavenTestSelector>' --stage-backend YES-STAGE

# After merge, from clean exact origin/main. Reuses the build-phase manifest
# (build="reused", no Maven, no upload), about 1 minute.
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<registered Base SHA>' \
  --tests '<same MavenTestSelector>' --no-prefer-ci-artifact --confirm-prod YES-PROD
```

Do not run `prewarm-main-artifact.sh` and do not leave `--prefer-ci-artifact`
on (it defaults to on in the script). The CI artifact lane cannot work in this
org (private repo has no attestation, artifact storage quota is full), so
waiting for it only burns minutes before the local fallback.

Success means `DEPLOY_EXIT=0` and `RELEASE_FINAL_STATUS` printed exactly once.
Its absence is a failure, and an exact-main rejection is silent
(`deploy_mode=none` after a green build): `git fetch`, re-detach on
`origin/main`, rerun. When merging someone else's PR, run the build phase on the
merged main before the deploy phase.

The final `no-op` versus `deployed` state comes from the Java/Web child
receipts, not from the source diff. In the deploy phase,
`components.java.build: "reused"` with `java_build: 0` / `build_count: 0` is a
normal reuse. Deploy-phase validation does not compare the current `--tests`
with the recorded selector, so read the manifest's `target_tests` before
claiming this selector was gated.

## Build Once Or Reuse

For one `backend/java/cretas-api` Git tree and one selector, Maven runs zero or
one time. `release-jar-manifest.sh build --tests '<tests>'` reuses the cached
JAR only when the manifest is well formed with `success=true`, its
`target_tests` equals the request, its `backend_tree` equals the current tree,
and the JAR exists with a matching SHA-256. Otherwise it runs one
`mvn clean package -Dtest=<tests>`; `CRETAS_RELEASE_FORCE_JAVA_BUILD=1` forces
that. Do not run target tests separately and then package again.

A squash merge changes the commit but keeps the tree, so the candidate JAR is
still valid after merge. If reuse or any validation fails, the script falls
back exactly once to a local clean package; do not retry with a second package.
A matching production MD5 is a no-op only after the real upstream, systemd unit
and active-slot health are verified (`FORCE_REDEPLOY=1` bypasses it). GitHub
Artifact is a manual fallback only when it already exists and passes the same
trusted-manifest checks. Never trigger or wait for an Artifact during a release.

`./scripts/deploy/release-preflight.sh` is the single read-only gate before a
release from exact `origin/main` (shell, YAML, encoding, Flyway, diff); it never
runs Maven or contacts production. `--allow-non-main --allow-dirty --skip-fetch`
is for diagnosis only.

## Reuse A Merged Feature Worktree

Avoid creating a second release worktree when the reviewed feature worktree is clean and its PR is already merged:

1. Require an empty `git status --porcelain`, then fetch `origin`.
2. Record `feature-head=$(git rev-parse HEAD)`, then use merged PR metadata to obtain its head and merge commits. Require `feature-head == <pr-head>`, `git merge-base --is-ancestor <merge-commit> origin/main`, and `git diff --quiet "$feature-head" <merge-commit>` to pass. Do not infer a squash merge from feature-branch ancestry.
3. Run `git switch --detach origin/main`, then require both an empty status and `HEAD == origin/main` before any production build or deploy.

If any condition fails, create a fresh clean release worktree from `origin/main`. Never discard uncommitted work to enter this fast path.

## Web Admin

The deploy reuses one immutable `dist.tar.gz` when the build commit resolves,
the build and current `web-admin` trees match, and package-lock, index, archive
SHA-256, tar integrity and referenced chunks all pass; any miss does exactly one
local build. Success, including no-op, requires the four-way index hash gate
(trusted local index, server file, gateway localhost, public HTTPS).
Candidate archives are also keyed by the exact `web-admin` Git tree. If another
candidate replaced the `current` pointer, restore the matching tree cache before
using the one permitted fallback build.

Standalone: `./scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD`.
Non-interactive callers pass the flag or `CRETAS_WEB_PROD_CONFIRM=YES-PROD`;
never pipe a synthetic prompt answer.

## Single-Component, Parallel And Locks

- `deploy-backend.sh --env prod` (`--rollback`), `deploy-web-admin.sh`,
  `deploy-smartbi-python.sh --env prod`, `release-jar-manifest.sh`,
  `release-web-manifest.sh` and `release-cretas-artifacts.sh` are single-component
  or troubleshooting entries. They keep the blue-green, atomic-swap, health,
  rollback and hash gates; do not rebuild those mechanics by hand.
- Python reuses the remote virtualenv when its requirements/interpreter/package
  manifest is valid; do not force a repeated `pip install`. Never overwrite
  server `.env` files.
- Parallel release only with `--parallel-if-independent YES-INDEPENDENT-SERVICES`
  (or `deploy-cretas-parallel.sh --confirm-prod YES-PROD --confirm-independent-services YES-INDEPENDENT-SERVICES`)
  and only when either activation order is API compatible. Migrations, auth and
  cross-end contracts stay serial.
- Locks: `cretas-backend-deploy`, `cretas-web-admin-deploy`, `cretas-release`.
  Stale locks with a dead PID are removed automatically. Otherwise check the
  PID and any Maven/Vite/deploy descendants before clearing by hand; never
  `rm` a lock reflexively.

## Verification

The release entry already runs `verify-release.sh` (upstream, systemd, direct
health, Web hashes) and records it in the receipt; when the receipt is complete
and successful, add only task-specific checks: find this change's ASCII marker
in the running artifact and exercise that path once. If production assertions
fail, roll back first. Full online business acceptance or F006 UI E2E runs only
when the user asks; then use `e2e-web-admin`.
