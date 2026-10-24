# Windows quick-check CI slot

A second isolated Linux x64 runner in the **already-running Ubuntu-22.04**, next to
`windows-light-cretas`, that only takes the four sub-minute checks:
`tracked-secret-scan`, `no-literal-nul-bytes`, `scan-surface-in-sync`, `reap`.

Why: before this slot those checks shared the single light slot with vitest/pytest and
waited a median 12–17 minutes to run for under a minute (measured 2026-10-24 over 46 jobs).
The owner chose the no-cost option on 2026-10-24: one more slot on this PC, 1 CPU / 1 GiB.

## Routing

- The slot registers with the custom label **`cretas-quick` only** (plus the runner defaults
  `self-hosted, Linux, X64`). It has no `cretas` and no `java`, so no other route matches it.
- The four quick workflows use `runs-on: [self-hosted, cretas-quick]`, managed by
  `scripts/ci/switch_ci_runner.py` (`QUICK_WORKFLOWS`, `--adopt` for the one-time migration).
- `aurora-cretas` and `windows-light-cretas` also carry `cretas-quick` (added through the
  runners labels API), so quick checks still run when this slot is busy or the PC is off.
- `scripts/tests/test-quick-runner-routing.sh` pins: exact routes and job names, the hook
  allowlist equals `QUICK_JOBS`, the slot's labels and limits, no other route can land here,
  and the hosted round trip restores every file byte for byte.

## Installation

Download the official Linux x64 runner package; its SHA-256 comes from
`GET /repos/cretasfuture-ai/cretas/actions/runners/downloads`. No Node package is needed
(the jobs use git, python3 and bash; `actions/checkout` runs on the runner's bundled node).

```powershell
wsl.exe -d Ubuntu-22.04 -u root --exec /bin/bash <install.sh> <runner.tgz> <runner-sha256>
wsl.exe -d Ubuntu-22.04 -u root --exec /bin/bash <preflight.sh>
```

`install.sh` refuses existing target paths/users and an active retired runner. It does not
register or start the listener. `preflight.sh` runs the installed unit as a oneshot under its
real limits and sandbox, checks the old heavy runner is still inactive/disabled and the light
slot still active, then removes its own override. Failure prevents registration.

Pipe a one-hour repository registration token (authenticated admin CLI, kept in memory, never
echoed or written) to `/etc/cretas-ci-quick/register.sh`, then enable and start only
`cretas-ci-quick.service`.

## Boundaries and acceptance

- One listener/job; CPUQuota 100%, MemoryHigh 768M, MemoryMax 1G, no swap, TasksMax 128, Nice 10.
- Fresh Linux user `cretas-ci-quick`; Windows disks, the old runner, the light slot's paths,
  Docker sockets and GPU are inaccessible.
- The job-start hook refuses any job outside the allowlist (exit 75 = failed measurement,
  never a pass), missing tools, broken isolation, or under 2 GiB available memory.
- Acceptance needs real completed GitHub jobs with `runner_name=windows-quick-cretas`, plus
  the light slot and Aurora continuing their own jobs.

## Maintenance and rollback

Wait for GitHub `busy=false` before maintenance. Rollback: stop/disable **only**
`cretas-ci-quick.service` and remove **only** its own GitHub registration; quick checks keep
running on Aurora and the light slot through their `cretas-quick` label. To route the four
checks back to the generic queue, revert the routing commit (do not hand-edit `runs-on`).
