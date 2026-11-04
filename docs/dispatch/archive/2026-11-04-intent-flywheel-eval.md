# INTENT-FLYWHEEL-EVAL-20261104

- Base: `29f88332b053865f7d0f8a5b0b1607e347557590`; Owner: Codex/root.
- Scope: `scripts/finetune/{flywheel_reviewed,training_label_contract,export_training_data,incremental_finetune}.py`, `pipeline.sh`, README, `data/evaluation_holdout.jsonl`, two targeted Python test modules. Final commit releases this batch's scope; GitHub PR/merge is a separate fact.
- User requested same-sample inference on the current self-hosted intent classifier, per-case comparison with prior three OpenAI runs, and reviewed intake into the existing training flow.

## Measured evidence

Current internal classifier `/api/classifier/info` reports available, CPU, 259 labels. Its info is identical before/after 120 sequential inference requests; Python service PID/start time also unchanged. No external LLM calls, business writes, model reload, training, or deployment were performed in this batch.

120 case results include 85 unique inputs, one empty-text slot fixture. Protect 119 nonempty rows / 84 unique texts; keep the empty case in evaluation evidence, never train it. Intent review queue has 35 records: 26 catalog mismatches, 5 local/teacher disagreements needing domain review, 3 agreement anchors, 1 teacher disagreement. Directly trainable records: 0; the queue stays pending/holdout.

Eight of eleven legacy intent IDs are absent from the live catalog. Only nine positive-choice cases use identical existing label IDs: local agrees with old expected label 4/9; each prior OpenAI model agrees 8/9. This is descriptive, not a fair 259-class accuracy comparison: cloud candidates omitted local alternatives such as TRACE_BATCH/EQUIPMENT_STATS; some alternatives may be legitimate. Non-intent tasks are retained per case but not scored as classifier accuracy. P50/P95 inference roundtrip through the isolated SSH tunnel: 108.45/161.90 ms, including transport.

The checked-in full/merged/incremental training corpora already contain 9/8/1 exact evaluation expressions respectively. This is not proof the current weights trained on those particular files, and no uncontaminated generalization claim is made. Any future improvement acceptance needs fresh group-isolated examples and model/training-data provenance.

Host evidence folder (outside Git): `C:/Users/Steve/Documents/Cretas模型评测-20261104/local-flywheel/`. It includes model info before/after, raw predictions, full paired-comparison, pending review queue, protected holdout, corpus-overlap check, HTML report and test logs. The model file on disk was fingerprinted without reloading it; its hash is not misrepresented as proof of in-memory training provenance.

## Integration and checks

- Reviewed intake requires current labels, explicit reviewer/basis/provenance, and a non-evaluation training split. Any factory-scoped row passes existing consent/withdrawal rules, including rows marked synthetic. Shared protected-name filtering remains on all export sources.
- Mandatory default evaluation registry applies to all export sources and direct trainer new/replay records. Optional task holdouts add protection. Conflicting normalized labels fail; outputs cannot alias inputs/registries and replace atomically. No-source export fails rather than reusing stale output.
- Trainer now accepts pipeline's `--label-mapping`, preserves the existing full head/IDs, validates non-generic config mappings, refuses mismatched-size head replacement, and supports the actual Bert/RoBERTa backbone freezer. New/replay paths/content must differ; pipeline no longer replays the merged corpus into itself. Normalized duplicates are removed before splitting, preserving reviewed evidence.
- Final targeted command: `python -m pytest backend/python/tests/test_flywheel_reviewed_intake.py backend/python/tests/test_training_label_contract.py backend/python/tests/test_finetune_training_consent_gate.py -q`.
- 57 tests passed; one existing Pydantic deprecation warning. This includes real exporter wiring with controlled fixture sources, direct trainer pre-load guards, exact pipeline Step-5 argv, and negative controls. No real training was executed.
- `bash -n scripts/finetune/pipeline.sh`, `git diff --check`, dispatch gate passed. Pipeline argument forwarding was inspected via `--dry-run --skip-train`; the legacy missing E2E script warning is not counted as product acceptance.
- Independent read-only risk review found legacy CLI/head-ID/replay and stale-export issues; addressed with specific regression tests. No model improvement, production deployment or business acceptance is claimed.
