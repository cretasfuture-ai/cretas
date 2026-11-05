# INTENT-266-LOCAL-20261105

Base `5e59baa7e9f6a798cd66cd0032cc34aa2b27dd4f`; Owner Codex/root `01a1a59f-7fcf-76f2-82a2-2cfd0d5fce98`.
Scope: independent factory experiment/split guards, incremental trainer explicit validation arguments, default holdout append, targeted Python tests. No production writes, model replacement, reload, deployment, or customer-data cloud calls.

## Frozen data and actual run

Evidence: `C:/Users/Steve/Documents/Cretas意图微调-266-20261105/` (outside Git).
266 canonical labels share only93 identical IDs with the old259-label head; initialize173 new output rows and transfer93 rows by label identity, preserving the local baseline file. Historical training corpus contains81 current labels. Do not infer full model coverage from catalog count.

Training1949:72 newly authored synthetic expressions,519 definition-derived weak seeds,1238 historical replay (original weight training provenance unknown),120 previous authored training. Validation24; fresh synthetic test62; original35 records diagnostic-only. Frozen text/family checks before weights load reject exact/NFKC/near duplicates and group crossings. Uniform .85 audit against the full historical corpus found one additional test expression (`test:49`, “现在怎么样”) near legacy expressions; frozen62 unchanged, primary novel-subset table excludes this one (61). Unknown original model provenance remains a limitation.

The first attempt used legacy50-step evaluation and early-stopped during warmup at epoch0.26 (validation F1 no improvement). Before any local test predictions, preserve this attempt and complete the originally budgeted3 epochs from the same initialized weights with per-epoch evaluation. Completion took131.74s on local RTX3060, validation F1=.44722; no test-based threshold/model adjustment. Final candidate SHA256 `e736f04de0b9a5a33716f54eacb461d02182210d173d17e10a7000dfbbb43183`; baseline SHA256 remains `2ed60ec51712e9b786f383a54af4955129886d32a4e47f765d06f2f3e9ab9eaf`.

## Results and limits

On61 novel-subset strict-label cases: old16/61(26.2%), new26/61(42.6%). Shared-label subset old16/21, new20/21; new-head subset old0/33, new6/33. Seven remaining cases expect clarification; neither fixed-head raw argmax can emit that sentinel. The original35 are diagnostic old10/35,new11/35, not new generalization evidence. Do not replace production based on this pilot.

Fixed local acceptance requires active label, temperature1.5, confidence>=.8 and margin>=.2. New accepts6/61 but one is a false accept (underspecified inventory query), so confidence alone is insufficient. New+Luna guarded50/61, new+mini55/61, new+GPT5.4 55/61. Luna accepted precision44/45, mini49/53, GPT5.4 49/54. Three-model agreement cascade55/61; it adds calls and tail latency and has no accuracy gain over mini on this sample. Old+Luna44 accepted/44 correct,17 clarifications; old+mini and old+GPT5.4 both55/61. Keep strict ID scoring; source-defined trace labels overlap, so not every strict mismatch establishes a wrong business answer.

CPU batch1 inference(new model) P50/P95=40.02/47.94ms. New+Luna replay P50/P95=410.69/472.31ms; new+mini1465.17/2139.08ms; new+GPT5.4 1692.87/3501.19ms; cascade419.34/4574.18ms. Hybrid values are counterfactual sums of measured component latency, NOT a live integrated production endpoint measurement.

Cloud:97 cases x3 models=291 successful requests, no automatic retries. Decisions Luna126485 input tokens; mini152017 input+6738 output; GPT5.4 152017 input+7207 output. Total standard list-price equivalent USD0.64512975, not actual invoice cost; free incentive and remaining account allowance unverified. Same cloud results reused across local arms; no duplicate actual calls for hybrid variants. Candidate generation is offline char TF-IDF top24 over266 definitions, NOT production BM25/vector/reranker; no gold labels supplied to retrieval or teachers. Structured Responses uses low reasoning, strict JSON schema, store=false; Decisions uses choice/confidence. Synthetic only.

Candidate recall misses2/62 new tests and4/35 original reviews. New-test misses are SHIPMENT_STATS and SALES_DELIVERY_BATCH_ALLOCATE; original misses REPORT_TRENDS, QUALITY_STATS, ATTENDANCE_STATS, SHIPMENT_QUERY. Keep these misses in scores, never inject gold choices after seeing results. These are retrieval failures under this proxy, not proof the teacher cannot classify with adequate candidates.

## Flywheel and protection

35 reviewed rows stay protected diagnostics; ambiguous inputs require clarification, absent generic equipment-state/report routes map to explicit unsupported capability. Batch-specific existing quality results map to QUALITY_CHECK_SUMMARY; asking employee counts maps to ATTENDANCE_STATS. Frozen original review retains historical fields, and current266 delivery sidecar clearly labels current head compatibility. No test/teacher disagreement becomes training truth.

72 independent TRAIN expressions pass existing `load_reviewed` intake with explicit synthetic provenance, reviewer/basis and evidence refs.76 test/review disagreements are protected review candidates, training_eligible=false. Added86 current test+validation and119 previously unmerged experiment test+validation expressions to the default holdout registry by append, preserving all original bytes. External receipts bind source/data/model hashes. No original259 head mapping or baseline weights altered.

Remaining taxonomy/model gaps:173 new output rows mostly lack rich independent expressions; many labels have only weak definition seeds. Trace variants overlap; generic equipment real-time health/runtime reports have no precise route. Catalog engineering synchronization is complete, label boundary/business coverage and trained model quality are not complete. No label or route changed to improve scores.

## Validation and delivery

Targeted tests: factory experiment, training label contract, reviewed intake, consent gate. The earlier fixture compatibility failure (missing optional validation attributes on a SimpleNamespace) was fixed by optional attribute access without weakening guards. Final source/data suite64 passed (20.91s, one pre-existing Pydantic warning); log `final-resume-target-tests.log`. Final review also fixed partial-resume canary bookkeeping to prevent duplicate requests, proven by a fake-transport regression (5 remaining requests,6 unique total). The actual fresh291-call run has291 unique model/case pairs and was unaffected; no cloud/model rerun. CI/PR/merge tracked independently; source scope releases when merged. No online model replacement or deployment is part of this task.

First full CI on `de8fe1ed26` had10383 passed and2 new-test failures: the runner's existing transformers/tokenizers versions conflict, and sklearn is absent. Do not weaken production dependency pins or skip tests. Keep TF-IDF import inside the real `plan` path; the resume test mocks that already-tested local retrieval stage. Test classifier-row identity with real torch Linear tensors and a minimal head harness, avoiding an unrelated tokenizer import. Full BERT training/loading and same-head inference were actually exercised locally in the frozen experiment. Both tests remain required; re-run the final64-target slice and full Python Gate for the corrected commit.
