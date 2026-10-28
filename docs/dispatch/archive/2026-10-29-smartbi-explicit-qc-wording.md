# SC29-QC-WORDING

Root; Base `ce7841117f5df336d2cbe130d8721ad997872389`; review, scope released on merge. Scope is conversation.py, test_joint_planner.py and this receipt; no permissions, data, SQL, Java or native change.

Actual deployed F006 reproduction with the existing UI-created production/inspection/cost trio: `关联生产、质检和成本一起看` returned unavailable even though the same data already supported a verified inspection joint. The production-family classifier recognized 检验/inspection/cross-table but not the user's normal 质检 wording. It incorrectly requested the same-fact production family instead of the actual inspection relationship.

Add explicit 质检 and whole-word QC aliases to the inspection-family requirement and the QC axis. Keep ordinary 质量/良品 same-fact semantics unchanged. Two planner regressions prove the verified inspection family is exposed to the real provider selection while unrelated standalone/same-fact candidates are excluded.

Independent narrow source review PASS. Final factory suite: 454 passed, one retained independent-schema skip, sourceUnchanged=true. The original live unavailable response remains in private `sc29-qc-wording-before.json`; merge, official Python-only deployment and the exact same live wording must be recorded independently. No existing F006 test data is recreated.
