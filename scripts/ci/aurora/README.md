# Aurora local CI controls

The existing Colima `ci` Linux ARM64 VM is the primary for the main and Assembly repositories. These files version the deployed controls; they do not install credentials or perform application deployments.

## Installed paths

| Versioned file | Aurora installed path |
| --- | --- |
| `cretas_ci_primary.py` | `~/.local/lib/cretas-ci-primary/cretas_ci_primary.py` |
| `ci-primary.example.json` | `~/.config/cretas/ci-primary.json` (0600) |
| `com.cretas.ci-primary.plist` | `~/Library/LaunchAgents/com.cretas.ci-primary.plist` |
| `maven_output_guard.py` | CI guest `$HOME/.local/share/cretas-ci/bin/maven_output_guard.py` (0600) |
| `maven.sh` | CI guest `$HOME/.local/share/cretas-ci/bin/mvn` (0755), first in both runner `.path` files |
| `capacity.py`, `job-started.sh` | CI guest `$HOME/.local/share/cretas-ci/`, hook selected by both runner `.env` files |

The existing guest home is `/home/aurora.guest`; the hook intentionally points there. Always verify the guest's actual home before reinstalling. Registration tokens, runner credentials, GH login, and private signing material never belong in this directory.

Connect with `ssh morrowward-us-mini` and verify `Auroras-Mac-mini.local / aurora` before operations. Select the existing profile explicitly with `colima ssh -p ci`; do not activate it as the default Docker context. The default `colima` profile serves PomeloX. CI profile resources are 4 CPU / 8 GiB; do not resize/restart an active VM.

## Operating limits

The user LaunchAgent runs every 60 seconds, including at login. It checks only the local VM and two exact allowlisted units. Its only sudo command is `sudo -n systemctl start <allowlisted-unit>` inside that VM. It never stops or restarts workers, resizes a VM, queries Canada, or changes system networking. Existing services are enabled in the guest. GUI-user LaunchAgent availability after a reboot depends on the user session; host reboot was not tested.

Before starting a VM or service it requires 110 GiB free. Each new CI job has a read-only hook requiring 125 GiB on the mounted host filesystem, leaving headroom above the 100 GiB reserve. Low space rejects new work without killing an active job. Latest state is `~/.local/state/cretas-ci-primary/latest.json` (maximum 32 KiB), with at most 14 event files; stdout/stderr go to `/dev/null`. A single-instance lock prevents overlapping supervision.

Both repositories share a Maven lock. Wait is bounded to 300 seconds (exit 75). The entire Maven lifecycle is bounded to 90 minutes (exit 124), with a 30-second TERM-to-KILL grace period; this leaves report/ratchet time before the 120-minute nightly hard limit. Exit statuses propagate. Maven defaults to 1 GiB, javac is forked with a 2 GiB limit, and test classes get independent JVMs (default 3 GiB). Explicit workflow heap arguments remain effective. No runner-global Spring context cache override is injected. The existing nightly workflow's own cache argument and ratchet baseline remain unchanged. Per-class forks can take longer than one reused JVM; actual full-run timing must be recorded.

Changing `.path` or `.env` requires a runner service restart only after GitHub `busy=false` and local `Runner.Worker` absence. Replacing the wrapper atomically affects future Maven calls; it does not restart an active call.

## Validation and retirement

Run `python3 -m unittest discover -s scripts/ci/aurora -p 'test_*.py'` on Aurora and in the Linux CI guest. Linux runs all 43 tests; macOS runs 37 and skips the six Linux-only Maven subprocess tests. They cover service allowlists, no stop/failback, identity-safe context selection, space boundaries, bounded receipts, serialization, selector/heap/exit propagation, aggregate timeout, and lock conflict. They do not start real jobs or services.

Runtime acceptance is recorded under `~/.local/state/ci-build-migration-20261018/`. The full nightly [39148327770](https://github.com/cretasfuture-ai/cretas/actions/runs/39148327770) passed on `aurora-cretas` in 65m04s with `write_baseline=false`: 2,363 final XML suites / 15,880 test records / 117 skips / zero failures or errors, including four executed PostgreSQL R10 integration tests. The baseline hash was unchanged. Repeated Nested execution gives a larger console execution count; do not describe it as unique tests. Assembly's six actual gates and both real capacity-hook jobs also passed.

Surefire's negative-only selector with independent class JVMs can execute Nested tests repeatedly and overwrite an earlier failing XML report. An isolated real JUnit/Surefire fixture reproduced that false green: raw independent forks returned Maven 0 / ratchet 0, reused forks returned 0 / 1, and the guarded independent-fork wrapper returned 86 / 0. The observer streams original output unchanged and remembers any Maven failure/error summary. With the current empty accepted baseline, only a final failure testcase identifiable by the existing ratchet may proceed to that ratchet; missing, overwritten, malformed or unidentifiable reports fail closed with 86. A positive/absent baseline also fails closed in this situation. Original Maven failures take priority. No selector, assertion, baseline or real sample threshold changes. The fixture alone uses a one-test threshold. Replaying all 3,215 summary rows of the completed successful full run through the final observer returned 0; the observer did not reexecute tests.

Canada's four runner units and host CI LaunchAgent are disabled after draining; registration, data, labels-before receipts, VM disks and configuration are preserved for recovery. The old Aurora failover LaunchAgent is retired and its legacy shell entry forwards to the local supervisor. Never restore that Canada-dependent watchdog. Recovery requires checking current jobs and labels and draining the target before deliberately moving routing; do not run duplicate primaries.
