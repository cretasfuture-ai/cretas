# CI-LIGHT-WINDOWS-20261019

- Owner: side/01a1545a-f3dd-7433-96cd-4c943ab8109b; no subagents.
- Registered Base: `4276c25da8083f21705e0c849fdd7e830dacf327`.
- Worktree: `C:/Users/Steve/cretas-windows-light-ci-20261019`.
- Branch: `codex/windows-light-ci-20261019`.
- Scope: `tools/ci/windows-light/**`, paired CI routing rules, task dispatch/archive.
- Source status: review; source scope released on PR merge. Runtime is already enabled by the user's explicit request for efficient Aurora + Windows operation.

## Result

Added repository runner `windows-light-cretas`, ID25, in the already-running
Windows `Ubuntu-22.04`. It has default Linux/x64 labels plus `cretas,windows-light`,
and no Java label. It directly accepts existing non-Java queued jobs, without
workflow changes, cancellations, reruns or altered selectors/assertions.

The new `cretas-ci-light.service` uses a new restricted Linux user, one job,
CPU3, RAM soft5/hard6GiB, zero swap, Nice10 and isolated Linux application/work/cache.
The retired `cretas-ci.service` remains inactive/disabled. Aurora runner24,
its supervisor/VM, Docker/WSL global configuration, existing containers, main
Chat source, phone and business data were not changed by this task.

Official Actions runner2.337.0 and Node20.20.2 package SHA-256 checks passed.
The real installed unit passed an isolated oneshot preflight before registration;
Windows user disks, retired runner, Docker sockets and GPU are inaccessible.
Registration token was piped from memory, not saved in source/evidence.

## Actual validation

- Real source hygiene job118065205001 on runner25 passed,13:45:56–13:47:25UTC:
  https://github.com/cretasfuture-ai/cretas/actions/runs/39254183900/job/118065205001
- Real secret regression job118065205221 on runner25 passed,13:47:51–13:48:29UTC:
  https://github.com/cretasfuture-ai/cretas/actions/runs/39254183739/job/118065205221
- RN job118065205149 passed on runner25,13:48:34–13:54:46UTC: all311 suites and3302 tests passed, with TypeScript check passed.
  https://github.com/cretasfuture-ai/cretas/actions/runs/39254183733/job/118065205149
- Web job118065205544 passed on runner25,13:54:50–14:06:21UTC:774 files passed/1 skipped,6123 tests passed/5 skipped, with vue-tsc passed. Skips remain skips.
  https://github.com/cretasfuture-ai/cretas/actions/runs/39254183972/job/118065205544
- Aurora Java full job118073192810 continued concurrently, then failed at14:06:11UTC in its new-failure decision step (`rc=1`). This independent parent-PR1037 failure is not a runner-capacity pass or business acceptance; the main Chat retains its diagnosis/fix ownership. It was not cancelled or restarted.
- Existing Maven routing guard7 assertions and self-hosted timeout guard3 assertions passed, including their own negative controls.
- Job hook actual heavy-job negative control: `full-suite` refused with75.
- Re-running preflight after registration refuses with2 before unit changes.
- Re-running installation refuses an existing target with1; all installation paths and symlinks are guarded. Preflight/registration refuse an active listener before modifications.
- Outside-sandbox positive controls confirmed root can read Windows win.ini and old runner metadata, while the actual sandbox preflight refused both.
- Shared agent rule equality, dispatch limits, shell syntax and diff checks passed.
- Observed cgroup peak~5GiB, zero OOM/kill/max events and zero VM swap; runtime metrics continue separately. Enabled service is verified; full Windows reboot recovery is untested.

Private structured job, package, preflight, negative-control and runtime evidence:
`C:/Users/Steve/Documents/cretas-windows-light-ci-20261019/`.
Completed job logs, exact runner IDs and final RN/Web outcomes supersede online-only status.
This is CI capacity delivery, not application deployment or business acceptance.

## Follow-up boundaries

Only maintain/stop/remove this new runner after its own job reaches idle.
Preserve old environments and evidence. Do not restore retired runners, add Java
labels, change global WSL/Docker, bypass CI, or claim unexecuted checks passed.
Linux service startup is enabled; do not reboot the shared machine to test it.
