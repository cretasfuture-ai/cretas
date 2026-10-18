# Aurora local CI controls

The existing Colima `ci` Linux ARM64 VM is the primary for the main and Assembly repositories. These files version the deployed controls; they do not install credentials or perform application deployments.

## Installed paths

| Versioned file | Aurora installed path |
| --- | --- |
| `cretas_ci_primary.py` | `~/.local/lib/cretas-ci-primary/cretas_ci_primary.py` |
| `ci-primary.example.json` | `~/.config/cretas/ci-primary.json` (0600) |
| `com.cretas.ci-primary.plist` | `~/Library/LaunchAgents/com.cretas.ci-primary.plist` |
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

Run `python3 -m unittest discover -s scripts/ci/aurora -p 'test_*.py'` on Aurora and in the Linux CI guest. Linux runs all 29 tests; macOS runs 23 and skips the six Linux-only Maven subprocess tests. They cover service allowlists, no stop/failback, identity-safe context selection, space boundaries, bounded receipts, serialization, selector/heap/exit propagation, aggregate timeout, and lock conflict. They do not start real jobs or services.

Runtime acceptance also requires actual job runner names and the full nightly ratchet result, not merely an online runner or scheduler canary. Migration receipts live under `~/.local/state/ci-build-migration-20261018/`; the full diagnostic run is https://github.com/cretasfuture-ai/cretas/actions/runs/39148327770 (`write_baseline=false`). Record its completed result before declaring final acceptance.

Canada's four runner units and host CI LaunchAgent are disabled after draining; registration, data, labels-before receipts, VM disks and configuration are preserved for recovery. The old Aurora failover LaunchAgent is retired and its legacy shell entry forwards to the local supervisor. Never restore that Canada-dependent watchdog. Recovery requires checking current jobs and labels and draining the target before deliberately moving routing; do not run duplicate primaries.
