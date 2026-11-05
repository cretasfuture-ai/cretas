# INTENT266-ACCURACY-20261105

Base `072bf5681969a55029d929d00a6101a28ec92a15`; Owner Codex/root `01a1a59f-7fcf-76f2-82a2-2cfd0d5fce98`. Source checkpoint `a9079dde3f` preceded real training. Scope: offline semantics/data/training/calibration/blind comparison, reviewed intake and default evaluation protection. No production deployment, model replacement, reload, migration or business write.

## Objective and acceptance boundary

User requested local266 accuracy>=80%, local+mini>=95%, without test contamination or overfitting. The two numerical targets were met on the frozen synthetic blind cohort and the old61 diagnostic cohort. **Full generalization acceptance did not pass**: validation-to-positive-blind accuracy gap10.0885199 percentage points exceeds the preregistered10-point limit. Do not round away the failure or claim absence of overfitting. This is an experimental candidate, not production acceptance.

Evidence outside Git: `C:/Users/Steve/Documents/Cretas意图准确率-20261105/`. Main report `评测报告.html`; exact receipts `data-manifest.json`, `selection.json`, `routing-policy.json`, `blind-opened.json`, `blind-summary.json`, `legacy-summary.json`, `usage.json`, `known-daily-budget.json`, `intake-receipt.json`. Source/CI/merge receipts are separate from model acceptance.

## Source-grounded semantic boundaries

Keep all266 physical output IDs and their exact mapping. Six true single-Tool equivalences are declared before generation, leaving260 distinct training goals: FINANCE_INVOICE_REQUEST→TAX_GROUP_INVOICE; WAREHOUSE_TODAY_RECEIVING_PENDING→MATERIAL_TODAY_RECEIVING_QUERY; SUPPLIER_QUALIFICATION_ALERT→SUPPLIER_QUALIFICATION_QUERY_EXPIRING; ADDITIVE_COMPLIANCE→ADDITIVE_CHECK; HACCP_CHECKPOINT→HACCP_REVIEW; PRODUCTION_DEMAND_ANALYSIS→PRODUCTION_DEMAND_QUERY. The final three are actual one-Tool Skill wrappers; do not invent extra alarm/nutrition/dispatch calls to distinguish them. Metadata validation requires the exact same single Tool. This is offline classification normalization, not a change to tenant activation, permissions or runtime bindings.

Other source boundaries distinguish basic trace/full production chain/recall customer impact/individual lineage; inspection standards/batch results/aggregate statistics/comprehensive report; today's production quantity/order progress; raw material stock/pending receipt/arrived-awaiting-QC; attendance events/counts/single department/all departments; query/evaluate/execute. Same-route normalized scoring is reported separately; blind raw-ID and equivalent-route accuracy are identical, so alias scoring did not increase the reported result.

## Data and isolation

13000 natural training candidates (40long+10short per effective class); separate mixed-candidate Mini audit receives no assigned labels. Long9975 and short2504 survived audit; source/privacy/protected exact/near/family checks leave12288 unique normalized training rows (9974long+2314short),26–50/class. No definition-name seed templates are the principal data in this round.

Model-selection validation1559 (1040long+519short), calibration1065 (520long+465short+80ambiguous), blind1637 (1040long+517short+80ambiguous). Train/development generated independently with Mini; blind generated independently with GPT5.4 using different scenario styles and no training examples. No gold passed to classifier retrieval or either evaluation-Mini pass. All data synthetic, not human business truth or customer records.

Conservative character-bag match-count upper bound accelerates the identical NFKC/SequenceMatcher>=.85 protection, with randomized equivalence tests; no approximate ANN screen can hide near duplicates. The pre-freeze guard caught a cross-label short-development collision; retain the selected test byte-for-byte and drop conflicting development items. SHA receipt proves test selection unchanged. No model inference had occurred. Every frozen split hash is checked before training/evaluation; once blind marker exists, training/calibration reject further fitting. A final source-bound routing hash and model hash are enforced. Old test/review files remain unchanged.

4261 new validation/calibration/blind rows appended to the default registry, preserving all324 original rows byte-for-byte.12288 independent TRAIN rows pass existing reviewed synthetic intake with audit/source evidence; no evaluation/disagreement admitted. Generated synthetic review approval is not human business approval. Original model's complete training provenance remains unknown.

## Actual training and calibration

Preregister at most three validation-only configurations; only first was needed. Start from previous266 snapshot SHA256 `e736f04de0b9a5a33716f54eacb461d02182210d173d17e10a7000dfbbb43183`, preserve full head IDs.6epochs,batch8,lr2e-5,freeze first6 encoder layers,max_length128,label smoothing.05,weight decay.01,epoch evaluation/save,patience2,select validation macroF1.793.53s on local RTX3060. Validation1498/1559=96.0872%, macroF1=.9597034. Training sample779/780 vs validation gap3.78456points. Candidate SHA256 `e1aca26a47ab36e2fe8fc483783323e726b7923b052adaff9939a4cdc7998ad0`.

First calibration found a real routing bug: lexical ambiguity signals directly vetoed50 definite requests. Preserve v1 result89.3897%; change the signal to semantic verification by Mini, without changing weights/gold/tests or repeating first-pass API calls. Calibration v2 reached93.5211%. Also test selective same-Mini medium-reasoning review of161 model/Mini disagreements; no net calibration gain, so final policy disables it. V1/v2/v3 receipts retained. No test-based pipeline revision.

Final policy selected only on calibration: temperature1.0,local confidence.95,margin.05,Mini confidence.8,critic=false. Candidate pool:12 classifier plus18 lexical TF-IDF proposals, fused with neighbors, up to32 intents plus clarification. This is an offline proxy, NOT production BM25/vector/reranker verification.

## Frozen blind results and limits

Exactly one primary blind opening, no subsequent fit, relabel, deletion or threshold adjustment:

| Arm | Correct/n | Accuracy | Macro F1 |
|---|---:|---:|---:|
| Original259 snapshot |146/1637|8.9188%|.07331|
| Previous266 snapshot |267/1637|16.3103%|.11452|
| New266 raw IDs |1339/1637|81.7960%|.83867|
| New local+Mini |1579/1637|96.4569%|.97105|

Original259 cannot express most new classes; this is a matched-case classifier comparison, not a statement about production's complete rule/recall/LLM pipeline. On the1557 positive cases, new raw1339/1557=85.9987%; all80 expected clarifications count as raw-head errors. Short487/517=94.1973%, long852/1040=81.9231%. No input exceeded128 model tokens (maximum69), so the long-query deficit is not truncation. All1637 normalized test strings unique; no exact cross-label conflicts in posthoc audit. Primary scores unchanged.

Validation96.0872%→positive blind85.9987% drops10.08852points: **generalization gate FAIL**, no threshold relaxation. Cross-expression-source/implicit-goal long queries remain weaker. This is not proof of zero overfitting even though training/validation loss curves are stable. Numeric targets true, full targets/generalization false in the receipt.

Only611/1637(37.32%) accepted locally,602 correct(98.53% accepted precision). Mini handles1026/1637(62.68%),977 correct. Do not equate81.8% raw classifier accuracy with80% local autonomous completion. Of80 ambiguous inputs, hybrid65 correct,15 wrong;11 turned-to-Mini cases lacked their gold candidate. Existing execution permissions, schema/parameter checks and critical-operation confirmation remain required and unchanged.

After primary was frozen, old61 regression (not tuning): previous26626/61=42.62%→new50/61=81.97%, hybrid58/61=95.08%. Original35 hard boundary records: new raw16/35,hybrid26/35=74.29%; this unresolved subgroup must not be hidden by the overall score. All remain protected, not memorized into training.

## Cost and daily evidence

Actual4207 requests: Mini3686(input7039127+output954735),GPT5.4 521(input428658+output111556), allHTTP200. Includes generation/audit/calibration/unused critic experiment/blind/legacy. Standard cached-discount list-price equivalent USD12.23251, not verified invoice/free redemption. Blind runtime1026 Mini calls equivalentUSD2.16276, aboutUSD1.32117 per1000 full pipeline requests. Not all expense is runtime inference.

Including known prior traffic today: Mini8152617<=10000000;GPT5.4 699438<=1000000. These are this task family's known counts, not organization-wide remaining allowance. No organization usage/actual free incentive access was inferred.

## Verification and follow-up boundary

Related Python checks cover semantic binding equivalence, conservative overlap bound, real trainer explicit validation wiring, row identity transfer, no-gold Mini/critic payloads, optional critic, completed-request de-duplication, unknown-timeout HOLD and daily request reservation before network, sealed-test fitting rejection and routing hash lock. Final related suite: `python -m pytest backend/python/tests/test_factory_accuracy_protocol.py backend/python/tests/test_factory_experiment_contract.py backend/python/tests/test_training_label_contract.py backend/python/tests/test_flywheel_reviewed_intake.py backend/python/tests/test_finetune_training_consent_gate.py -q`: 85 passed, one existing Pydantic deprecation warning, exit 0. Final source/data checks and CI receipts recorded separately.

Remaining model-quality work is long implicit semantic queries, the35 boundary cases, ambiguous-input false accepts and real recall integration. Any further fitting must use independent source-grounded samples and a newly preregistered confirmation cohort; the opened1637 can only be diagnostic. Do not retroactively make this run pass its generalization gate. No online model replacement is authorized by this archive. Source scope releases only when its PR is merged; model-quality failure remains recorded.
