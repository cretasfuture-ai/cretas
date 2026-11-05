# SMARTBI-FACTORY-GUIDANCE-20261105

- Owner root; base 65679cabd6389662764b8ed54879eb3755b05491; existing PR #1390. Scope: factory-facing cost interpretation, next-action priority, within-product work-order comparison and shared analysis reading order.
- Cost values and source data are unchanged. Product averages remain output-weighted. Missing data stays unknown; units, products and processing stages remain separate. The page identifies its cost-ready cohort rather than implying complete factory coverage.
- A product has one first action: data checks precede cost signals, then comparison readiness. Overview counts no longer double-count one product in both data and cost action categories. Header status filters use readable next-action labels.
- Selected products show the observed per-unit gap between two explicitly identified orders; material plus labor differences reconcile to that gap. Opposite movements are retained. Comparisons do not assert a time trend or a confirmed cause; conflicting identities, mixed units, invalid denominators, incomplete inputs and single samples are not compared. Zero baselines do not produce infinite percentages.
- Higher/lower order buttons open the corresponding order detail. Each order starts with its per-unit material/labor composition, then the next verification step and responsible role. Longer saved interpretations remain available in details and follow-up.
- Shared section analysis is ordered as conclusion, numbers to examine, next action. Remaining observations no longer repeat the first observation.

## Verification and delivery

- 35 frontend tests in 5 files passed, including new numerical and workflow cases, existing product grouping/weighting, scope reset and follow-up behavior. Full Vue typecheck and blacklake-public build passed.
- Actual saved 26-order audit: all 26 orders mapped once into 16 product/stage/unit groups; four eligible comparison groups reconciled material and labor differences to the total gap. These are full-cohort counts; the default recent-seven-day view has 25 orders and 15 groups.
- The existing HTTPS share URL is preserved. Its served HTML matches the new build. Public read-only gateway and account/credential boundaries are unchanged. Preview stop/reopen used the existing tested scripts; no production Cretas release, data migration, payroll write or Blacklake business write.
- Private evidence and build: D:/CretasSmartBILab/local-analyst-distillation/factory-guidance-20261105/. Final source hashes, artifact hashes, browser outcomes and commit are bound in the local receipt. No paid inference or new model calls.
- Validation addresses the current deterministic numbers and presentation. Underlying source batch lineage, currency and complete manufacturing overhead still require their existing business confirmations; UI polish does not establish those missing facts.
