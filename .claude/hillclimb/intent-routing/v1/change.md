# v1 — dialect guard: single-character keys skip business compounds

**Target**: `DialectNormalizationServiceImpl` (code). **Round metric**: `pass` (all kinds must hold).

## Evidence (train split only)

`货 → 物料` is applied with `String.replace`, so it rewrites the character inside compounds. Train traces:

- `L004` 「这个月的出货量难道还不如上个月…」→ `出物料量…出物料数据` → phrase `物料数据` → `MATERIAL_BATCH_QUERY`（expected `SHIPMENT_STATS`）— `baseline/traces/L004_rep0.json`
- `M003` 「我要创建一张发货单」→ `创建物料` → phrase `物料` → `MATERIAL_BATCH_QUERY`（expected `SHIPMENT_CREATE`）— `baseline/traces/M003_rep0.json`
- `M013` 「这批原料要退货」→ `退物料` → similarity `退回原料` → `MATERIAL_BATCH_RELEASE`（expected `RETURN_ORDER_CREATE`）— `baseline/traces/M013_rep0.json`
- `S005` 「上礼拜的出货单拉出来看看」→ `出物料单` → `MATERIAL_BATCH_QUERY`（expected `SHIPMENT_BY_DATE`）— `baseline/traces/S005_rep0.json`

Same defect class for `活`（干活/活动）and `中`（进行中/其中）— `中 → 行` turns 「进行中的生产订单」into 「进行行的…」.

## Change

`replaceOutsideCompounds()`：single-character dialect keys are not replaced when the occurrence sits inside a listed business compound（出货/发货/退货/到货/收货/进货/… for 货; 干活/活动/… for 活; 进行中/其中/… for 中）. Multi-character keys and standalone occurrences behave exactly as before（「这批货能不能用」still → 「这批物料能不能用」）. Unit test `DialectNormalizationCompoundGuardTest` (3 tests, executed: surefire shows Tests run: 3) pins both the protected and the still-replaced forms.

## Expected

Recover the 出货/发货/退货 sentences（train: L004, M003, M013, S005; the test split has the same shapes）. Cases that pass *because* 货 was rewritten to 物料 would regress — none visible in train.
