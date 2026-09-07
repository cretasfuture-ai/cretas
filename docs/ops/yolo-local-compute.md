# YOLO local compute policy

From 2026-09-07, LIUSHANMEN image deduplication, screening, mining, training, export, replay, protected evaluation and performance tests run on the local Windows/RTX 3060 host. Mac mini is reserved for other work. No automatic remote fallback is allowed.

Use `scripts/windows/liushanmen-vision-lab.ps1` for the existing workflow and `scripts/windows/test-yolo-local.ps1` for local target tests. Python defaults to `B:\anaconda3\python.exe`; originals, annotations, models and receipts remain under `D:\CretasVisionLab`. The Python CLI enforces local Windows execution before state creation or external commands, rejects MPS and remote execution settings, and can bind a config to `execution.computer_name`.

The local runtime config uses `execution.mode=local` and `execution.remote_enabled=false`. Missing local resources are an explicit blocker: reduce batch size or execute sequentially, never dispatch to `canada-mini` or `codex-mac`. Historical Mac scripts and hash-bound receipts are retained only as historical evidence; do not resume them.

The existing YOLO automation keeps its original schedule and paused state. Its prompt now explicitly prohibits Mac dispatch. The three existing Windows YOLO scheduled tasks were already disabled; this change does not enable training. A disabled task referencing a missing checkout must be repointed to a clean merged checkout before it is enabled.

Production read-only collection and gated model upload remain supported. Local execution does not waive human review, split isolation, protected/normal-set gates, parity, latency, Git or deployment checks. Do not modify originals, human annotations, remote historical files, SSH configuration or unrelated Mac services.

Shared GitHub CI routing is separate from local YOLO validation. PR #745 is owned by the CI routing task and switches shared jobs to hosted runners; do not overwrite that task's workflows. Local test success does not prove mandatory CI success, and no Mac runner may be used as a fallback for this work.
