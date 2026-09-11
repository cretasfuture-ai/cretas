# v2 — phrase shortcut: skip read-only intents when the input carries a write verb

**Target**: `IntentKnowledgeBase.doMatchPhrase` (code). **Round metric**: `pass`.

## Evidence (train split only)

Noun phrases in the table resolve to *query* intents regardless of the verb in front of them:

- `P001` 「帮我删除这条质检记录」→ phrase `质检记录` → `QUALITY_CHECK_QUERY` — `baseline/traces/P001_rep0.json`
- `F019` 「创建入库记录，数量-100公斤」→ `入库记录` → `MATERIAL_BATCH_QUERY` — `baseline/traces/F019_rep0.json`
- `M007` 「帮我录入供应商评价」→ `供应商评价` → `SUPPLIER_RANKING` — `baseline/traces/M007_rep0.json`
- `P017` 「删除三个月前的所有考勤异常记录」→ `考勤异常` → `ATTENDANCE_STATS_BY_DEPT`
- `P028` 「批量创建200个新的原料批次」→ `原料批次` → `MATERIAL_BATCH_QUERY`
- `F018` 「强制修改已入账财务数据」→ `财务数据` → `REPORT_FINANCE`

15 train cases share the shape: write verb（创建/删除/录入/修改/批量/取消…）+ noun phrase whose table entry is a `_QUERY` / `_STATS` / `REPORT_*` intent.

## Change

In `doMatchPhrase`, a phrase whose intent is read-only（`isReadOnlyIntent`: `_QUERY/_LIST/_STATS/_SUMMARY/_HISTORY/_TREND/_RANKING/_DASHBOARD/…` suffixes, `REPORT_/QUERY_/TRACE_/LINEAGE_` prefixes）is not a hit when the input contains a write verb（`WRITE_VERBS`）; the scan continues to the next phrase, so later stages decide. Pure queries and write-verb + write-intent phrases are untouched. Unit test `IntentKnowledgeBaseWriteVerbGuardTest`（2 tests executed）.

## Result

test 0.300 → **0.320**, train 0.270 → 0.300（vs `v1_control`）. Attributable wins: M007, W003, W016, W026, P024, P025, M012. Attributable losses: W030「删除已作废的供应商记录」, W020, P014 — the skipped phrase fell through to the v14 similarity matcher, which matched the stripped input（「删除记录」/「查记录」）against `发货记录查询` → `SHIPMENT_QUERY`. That matcher is the next lever.
