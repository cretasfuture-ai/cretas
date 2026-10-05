---
name: deploy-backend
description: >
  Use when the user asks to deploy, publish, release, restart, roll back, or
  verify Cretas Java, Python, or web-admin services in production.
---

# Cretas Deployment

Production is deployed only from a clean worktree whose `HEAD == origin/main`. The rules behind that (worktree isolation, merge channels, the offline protocol when GitHub is down) live in `.claude/rules/worktree-and-main-only-deploy.md`; server layout and blue-green commands live in `.claude/skills/server-operations/SKILL.md`. The Claude-side twin of this skill is `.claude/skills/deploy-backend/SKILL.md`; the history of why the flow looks like this (prewarm / CI artifact lane, lock incidents) is in `.claude/skills/deploy-backend/references/lock-and-history.md`. Read those on demand; the judgements needed to release are restated below because Codex does not auto-load `.claude/rules/`.

Routine releases are not gated on asking: once verification is green, deploy (`.claude/rules/standing-authorization-and-visual-acceptance.md`). Not asking does not mean not verifying: every criterion below still applies.

Where LIUSHANMEN/YOLO screening, training and target tests run (local Windows/RTX only, never Mac mini) is governed by `AGENTS.md` § "LIUSHANMEN VisionLab 本地主机计算规则"; this skill does not restate it.

## Environment

The test environment was decommissioned on 2026-07-13: `47` runs production only. The scripts still parse `--env test|all`, but there is nothing behind them (`10011` / `8084` are gone); do not use them, and "deploy to test first" is not an available workflow. Real E2E can only target production.

| Component | Production |
|---|---|
| Java | `47.100.235.168`, alternating `10010` / `10020`, runtime `/www/wwwroot/cretas/` |
| Python | `47.100.235.168:8083` |
| Web admin | `139.196.165.140:8086` (`/www/wwwroot/web-admin/`), `admin.cretaceousfuture.com` |

Java traffic is routed by `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`. Read it before any deploy or check; never assume which slot is active.

## Unified Release (Java / Web)

Build before merge (Steve 2026-09-15). The one real build runs before the merge, so the post-merge window is about one minute and another session advancing `origin/main` during it rarely voids the release.

```bash
# build phase: BEFORE merge, in the clean reviewed candidate worktree (HEAD = PR head).
# Builds, then uploads the Java artifact to the server-side SHA-256 cache. Does not install, restart or switch traffic.
./scripts/deploy/release-cretas.sh --phase build --base-sha '<Base SHA>' --tests '<MavenTestSelector>' --stage-backend YES-STAGE

# deploy phase: right AFTER merge, from a clean exact origin/main checkout. A manifest hit => build=reused, zero Maven, zero upload, ~1 min.
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<same selector>' --no-prefer-ci-artifact --confirm-prod YES-PROD
```

- `<Base SHA>` is the `origin/main` commit the change is based on (the one registered in `docs/dispatch/ACTIVE.md` when the task is registered there). The script requires it to be an ancestor of `HEAD` and diffs against it to decide which components changed.
- Do not run `prewarm-main-artifact.sh`, and always pass `--no-prefer-ci-artifact`: the script default is still ON, but the CI artifact lane cannot work in this org (private repo => no attestation; organisation artifact storage quota is full). Waiting for it only burns minutes before falling back to local Maven. Never trigger or wait for an Artifact during a release.
- Merging someone else's PR (this machine never built that tree): run the build phase on the merged `origin/main`, then the deploy phase.

Reuse needs all three; otherwise the deploy phase falls back once to local Maven (~4 min) and says `RELEASE_BUILD_MODE=java-fallback`:

1. `origin/main:backend/java/cretas-api` after the merge has the same tree hash as at build time. Before merging, `git fetch && git log --oneline <base>..origin/main -- backend/java/cretas-api` must be empty; if not, rerun the build phase on the merged main.
2. Both phases use exactly the same `--tests` selector.
3. `~/.cache/cretas/java-deploy/current/release-jar.manifest` was not overwritten by another tree's build. Parallel release lines each set their own `CRETAS_JAVA_RELEASE_MANIFEST`.

Success criterion: `DEPLOY_EXIT=0` and `RELEASE_FINAL_STATUS` appears exactly once in the log. Its absence is a failure (the closing printf is killed under `set -e`; the exit code in a background-task notification is not trustworthy).

- The exact-main gate rejects silently: BUILD SUCCESS, tests green, then it stops with `deploy_mode=none` in the receipt. Fix: `git fetch && git switch --detach origin/main`, rerun.
- `build="reused"` / `java_build=0` / `build_count=0` in the receipt is a normal success. Deploy-phase validation does not compare the test selector; read the manifest's `target_tests` before claiming the current selector was gated.
- `no-op` versus `deployed` comes from the Java/Web child receipts, never from the source diff.
- Once a production release starts, do not merge unrelated PRs until its receipt is complete.

After deploy: the entry already ran `verify-release.sh` (real upstream, systemd, direct slot health, Web four-way hash) and recorded it in the receipt. When the receipt is complete and successful, do not repeat those probes by hand; run `./scripts/deploy/verify-release.sh --target backend|web-admin|all --env prod` only when the receipt is missing or failed. Then find an ASCII marker of this change inside the running artifact (`strings` cannot read Chinese, so a Chinese marker always counts 0) and exercise that path once for real. `release-cretas.sh` ships only Java and web-admin; a Python-only change is not deployed by it.

## Release Preflight

Before a release from the exact merged `origin/main`, run the single fast gate instead of repeating shell, YAML, encoding, Flyway and diff checks by hand:

```bash
./scripts/deploy/release-preflight.sh
```

It is read-only and never runs Maven or contacts production. `--allow-non-main --allow-dirty --skip-fetch` are diagnostics for feature work only.

## Reuse A Merged Feature Worktree

Avoid creating a second release worktree when the reviewed feature worktree is clean and its PR is already merged:

1. Require an empty `git status --porcelain`, then fetch `origin`.
2. Record `feature-head=$(git rev-parse HEAD)`, then use merged PR metadata to obtain its head and merge commits. Require `feature-head == <pr-head>`, `git merge-base --is-ancestor <merge-commit> origin/main`, and `git diff --quiet "$feature-head" <merge-commit>` to pass. Do not infer a squash merge from feature-branch ancestry.
3. Run `git switch --detach origin/main`, then require both an empty status and `HEAD == origin/main` before any production build or deploy.

If any condition fails, create a fresh clean release worktree from `origin/main`. Never discard uncommitted work to enter this fast path.

## Web Admin Specifics

The Web dist is reused by `web-admin` Git tree (package-lock, index, archive SHA-256, tar integrity and referenced chunks must all pass); any miss performs exactly one normal local build. Candidate archives are also keyed by the exact `web-admin` Git tree. Candidates finishing in a different order never justify a rebuild. Both no-op and deployed paths must pass the four-way index hash gate (trusted local index, server file, gateway localhost, public HTTPS).

Non-interactive callers must pass `--confirm-prod YES-PROD` (or set `CRETAS_WEB_PROD_CONFIRM=YES-PROD`) to `deploy-web-admin.sh`; never pipe a synthetic prompt response.

## Blue-Green, Locks, Parallel

- Each Java deploy goes to the inactive slot, is verified there, upstream is switched atomically, three post-switch health rounds run, then the old slot stops. Any failed round rolls back automatically.
- Three local mutexes: `cretas-release` (outer orchestrator), `cretas-backend-deploy`, `cretas-web-admin-deploy`; the orchestrator does not deadlock its children. A rejection message distinguishes a live concurrent deploy (wait for it) from orphan children (rsync / ssh / mvn) still holding fd 200 after the registered holder died; for the latter it prints `pid:cmdline`, a `kill` line and a recheck line. Confirm the orphans no longer write production artifacts and recheck with `ps` (PIDs get reused) before killing. Never `rm` a lock file: in flock mode that creates a second, mutually invisible lock. Details: `.claude/skills/deploy-backend/references/lock-and-history.md`.
- Default is safe-sequential. Parallel only with `--parallel-if-independent YES-INDEPENDENT-SERVICES` and when the migration / Entity / Repository / Security / Controller-DTO / config risk gates all pass; `--order backend-first|web-first` forces an explicit order. Cross-end API, migration and auth-contract changes are always serial.

## Single-Component And Troubleshooting Entries

These keep all backup, blue-green, atomic-swap, health, rollback, no-op and hash gates. Use them for one component or for troubleshooting, not to hand-assemble a normal full-stack release, and never replace them with hand-written rsync / ssh.

| Target | Command | Notes |
|---|---|---|
| Java | `./scripts/deploy/deploy-backend.sh --env prod` (`--rollback` restores the latest backup) | Writes a JSON receipt under `~/.cache/cretas/deploy-reports/`. Idle readiness is three-state: `UP:*` healthy, `TIMEOUT` / `RESTART_LIMIT:*` failed, empty string = ssh dropped, not measured |
| Java artifact only | `./scripts/deploy/release-jar-manifest.sh build --tests '<tests>'` | Zero or one Maven lifecycle per backend tree + selector; `CRETAS_RELEASE_FORCE_JAVA_BUILD=1` forces one |
| Java + Web artifacts | `./scripts/deploy/release-cretas-artifacts.sh --tests '<tests>'` | JDK 21 preflight first; a failing side cancels its sibling |
| Web | `./scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD` | web-admin is on 139, not 47; tar + atomic swap avoids mixed chunks |
| Java + Web parallel | `./scripts/deploy/deploy-cretas-parallel.sh --confirm-prod YES-PROD --confirm-independent-services YES-INDEPENDENT-SERVICES` | Only after merge, only when both activation orders are API-compatible |
| Python | `./scripts/deploy/deploy-smartbi-python.sh --env prod` | Reuses the remote venv when its manifest is valid; migration runner failure aborts before restart. Never overwrite server `.env` |
| mock-platform | `./scripts/deploy/deploy-mock-platform.sh` | 139 only |
| Restart only | `ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh prod"` | Incident handling only; read the active slot first |

## Merge Channel

PR vs fastlane (`publish-main-fastlane.sh`) is decided by the channel table in `.claude/rules/worktree-and-main-only-deploy.md` and the fastlane rule in `AGENTS.md` (开发流程去浪费 §6); run it with `--dry-run` first and read `FASTLANE_READY` versus `FASTLANE REJECTED: <reason>`. Merged and deployed are two separate states: verify each.

## Verification Scope

- If production assertions fail, roll back first; do not debug on the active bad release.
- Stop after service-level verification unless the user explicitly asks Codex for full online business acceptance or F006 UI E2E; then use `e2e-web-admin`, keep business mutations at zero, and do not count slot smoke checks as the full E2E.

| Symptom | Action |
|---|---|
| Health check timeout | Spring Boot cold start is slow; wait 60s and retry |
| JAR upload MD5 mismatch | Network instability; rerun `deploy-backend.sh` |
| Service will not start / 500 / blank page | Use the troubleshooting table in `server-operations` |
