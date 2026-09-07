# ML-YOLO-LOCAL-ONLY-20260907

- Status: review; scope released with merge. Owner: current Codex task.
- Base SHA: `fb94ba5abcef5580b933cbba3a7ed7f69f0ad6cf`.
- Scope: AGENTS VisionLab compute rules; VisionLab CLI; local Windows workflow/test entrypoints; local-policy tests under scripts/tests; deployment/server-operation skill guidance and worktree rule; local-compute operations note and this archive. No protected ROI/mine_tray_queue/tray_workflow files or tests were changed.
- Implemented local Windows checks before CLI state creation and external execution; reject remote configuration, MPS and Mac endpoints. Preserve production collection/upload channels and all model gates.
- Validation: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/windows/test-yolo-local.ps1` passed 85 tests locally, including CLI rejection before State/subprocess and production-host positive controls. No Mac computation or connection was used for this change.
- Local configuration was backed up and bound to the current Windows computer; all other configuration values were read back unchanged. The existing YOLO automation's prompt was updated using the automation tool, preserving schedule, model, project and PAUSED state. Three Windows YOLO scheduled tasks remain disabled.
- Shared CI routing belongs to the separate PR #745; its workflow changes were not overwritten. Mac history, source receipts and other machine workloads were not modified. A stale disabled VisionLab scheduled-task checkout must be replaced with clean merged code before enabling it.
- Commit/PR merge and runtime rollout are independent facts. This archive does not attest merge or enable any training, production deployment or model upload.

- Additional CLI regression: `python -m pytest tools/vision-lab/tests/test_vision_lab.py -q` passed 41 tests and 13 subtests locally. Shared route PR #745 remains open, so this branch is pushed without opening a PR that could dispatch CI to the existing Mac runner. Resume PR creation only after verifying the active workflow no longer selects Mac.
