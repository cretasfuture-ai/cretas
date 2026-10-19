# Windows lightweight CI

One isolated Linux x64 runner in the **already-running Ubuntu-22.04** distribution
accepts the existing non-Java `self-hosted,cretas` queue. Aurora retains Java and
PostgreSQL concurrency jobs. No workflow, test selector, baseline or in-flight
job needs changing or rerunning to use this extra capacity.

The user authorized Aurora + Windows task separation on 2026-10-19. The retired
Windows heavy runner remains disabled. This is one new resource-limited slot;
it does not restart WSL/Docker or change their global configuration.

## Installation

From Windows PowerShell, download the official Linux x64 Actions runner package
and Node20 package, verifying each official SHA-256 before passing it to Linux.
Runner metadata comes from `GET /repos/cretasfuture-ai/cretas/actions/runners/downloads`;
Node checksums come from `https://nodejs.org/dist/latest-v20.x/SHASUMS256.txt`.
Never reuse another runner's credentials or copy application secrets.

```powershell
wsl.exe -d Ubuntu-22.04 -u root --exec /bin/bash <install.sh> <runner.tgz> <runner-sha256> <node.tar.xz> <node-sha256>
wsl.exe -d Ubuntu-22.04 -u root --exec /bin/bash <preflight.sh>
```

`install.sh` refuses existing target paths/users and an active retired runner;
it requires the existing native Python3.11 and PostgreSQL17 tools. Installation
does not register or start the listener. `preflight.sh` runs the actual installed
systemd unit as a oneshot under its real resource and filesystem restrictions,
then removes its own temporary override. Failure prevents registration.

Use a one-hour repository registration token obtained with the authenticated
admin CLI. Keep it in memory and pipe it to
`wsl.exe -d Ubuntu-22.04 -u root --exec /bin/bash /etc/cretas-ci-light/register.sh`.
Do not echo it, write it to evidence, or put it in a command string.
The registration script uses the fixed name `windows-light-cretas`, labels
`cretas,windows-light`, and a separate work/home/cache. Then enable and start
only `cretas-ci-light.service`.

## Boundaries and acceptance

- Exactly one listener/job; CPU3, hard RAM6GiB, soft RAM5GiB, no swap, Nice10.
- Fresh Linux user; protected Windows user disks, retired runner, Docker sockets
  and GPU. Application/work/cache are on the Linux filesystem.
- Job-start hook rejects known Java-heavy jobs and missing tools/isolation or
  insufficient Linux memory headroom; nonzero is a failed measurement, never a pass.
- No `java` label. Existing Maven-routing and self-hosted timeout gates must pass.
- Require real completed GitHub jobs with this exact runner ID/name, retained
  steps/results, and Aurora simultaneously continuing its existing Java job.
- Observe retired runner still inactive/disabled, existing containers still
  running, host/VM headroom and zero swap use. Do not infer a full reboot test.

## Maintenance and rollback

Wait for GitHub `busy=false` and no active owned worker before maintenance.
Stop/disable **only** `cretas-ci-light.service`; remove **only** its own GitHub
runner registration if retiring it. Preserve evidence and the old disabled
configuration. Installation deliberately refuses an overwrite; diagnose a
partial installation in place instead of deleting unrelated paths or retrying
blindly. No default worktree, container, VM or cache cleanup is authorized.
