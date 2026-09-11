# v3 — similarity matcher gets the original sentence when preprocessing collapsed the input

**Target**: `IntentRecognitionPipelineServiceImpl` (code). **Round metric**: `pass`.

## Evidence (train split only)

The verb+noun disambiguation step compresses whole sentences to 2–4 characters before the v14 similarity matcher runs, and a 4-character residue matches anything:

- `W015` 「帮我登记一条安全巡检记录：今天的巡检发现消防通道有物品堆放，已要求整改」→ processed `记录记录` → matched `发货记录查询` → `SHIPMENT_QUERY` — `v2/traces/W015_rep0.json`
- `W030` 「删除已作废的供应商记录」→ `删除记录` → `SHIPMENT_QUERY`（was `SUPPLIER_DELETE` before v2 skipped the phrase）— `v2/traces/W030_rep0.json`
- `M018` 「查一下张三的记录」→ `查记录` → `SHIPMENT_QUERY`

7 of the baseline's similarity hits were literally `input='记录记录', matchedPhrase='发或记录'`.

## Change

`pickSimilarityInput(processed, original)`: when the processed input is ≤4 characters and less than half the original（and the original is longer than 6）, the similarity matcher receives the original sentence. Long sentences don't clear the 0.75 threshold, so the decision falls through to the classifier / LLM instead of being made by a residue. Short questions and lightly-normalised inputs are unchanged. Unit test `SimilarityInputCollapseGuardTest`（2 tests executed）.

## Result

test 0.320 → **0.330**, train 0.300 → 0.300. Attributable: W020 recovered; W026「删除上个月的测试数据」lost（residue `删除数据` used to hit `清除数据 → DATA_BATCH_DELETE`; the full sentence now clarifies）. Everything else that flipped was LLM / clarification-path noise. Third consecutive round below the 0.03 delta → plateau per the Step 2 rule.
