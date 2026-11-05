# BLACKLAKE-PRODUCT-UX-20261105

Owner: root. Base: `b302ccf7be1b741a5e94d03a90ee746f45ec8ee5`. Draft PR: #1390.
Scope: existing isolated SmartBI preview, product-first cost navigation and frontend grouping. No backend/DB changes or source-data mutation.

## Product flow

1. Default landing: **产品成本分析**. Show one row per product identity, name/stage and output unit; source names for finished/semi-finished products remain separate. Do not infer a new product-type classification from names or code prefixes.
2. Product overview includes output, material cost, labor cost, weighted unit cost, order count and linked issues. Lists and member orders are paginated independently.
3. Open a product to see its cost composition, minimum/maximum member-order unit cost, aggregated material usage and process costs, linked action items and its own orders.
4. Open an order to see that order's existing validated interpretation, materials, labor, missing-data actions and named follow-up. Return to product overview without mixing other products' orders.

Date/product criteria scope the data. Issue and order-number searches locate products; they do not silently remove the product's other orders from its totals. Product numbers reflect the selected date window. Unit cost is `(sum materials + sum labor) / sum finished quantity`, never an unweighted average. Upstream and downstream product costs are not added a second time.

Unknown material or process amounts remain null in component totals. Missing product codes do not merge rows on name alone. Conflicting names/stages under one product code remain separate and carry a warning. The source 26-order cohort, 145 stored explanations, tenant authorization and follow-up evidence binding are unchanged. Product aggregate explanation is deterministic; no new AI conclusion is attributed to the model.

## Validation and local evidence

Evidence directory: `D:/CretasSmartBILab/local-analyst-distillation/product-ux-20261105/`.

- 25 tests across grouping, existing cost model, drill-down UI, 1000-order pagination and preview guards passed (`tests-final.log`). TypeScript check passed (`typecheck-final.log`). Blacklake preview build recorded in `build.log`.
- Tests distinguish weighted costs from simple averages, retain exact member IDs, keep separate stages/codes/units, preserve missing component values, reject stale tenant responses and keep all product orders when searching one order code.
- Native in-app browser: product list → real four-order product statistics → its four orders → correct order drawer; issue action opens the correct order; individual follow-up submitted successfully. DOM evidence retained locally.
- Desktop interactions verified. Phone/pixel-level visual acceptance remains unverified. No production deploy, paid inference or Blacklake business write.
