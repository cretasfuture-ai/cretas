# SC68 PPT final-character orphan

- Base 8fc2c2623630fd3cefbe3ee5716b9010741918e1. Coordinator Root.
- Writer sc68_ppt_orphan owns ONLY backend/python/smartbi/services/ppt_generator.py
  and backend/python/smartbi/services/factory_charts/test_factory_exports.py.
- Actual Xiaomi-generated PPT smartbi-F006-1793286590191.pptx retains a single 售
  line after 不代表全部销 on slides3/6. Office PNG proves a visible orphan; content,
  source and 30yuan/3shipments/7stock checks PASS. Preserve failed actual file.
- Minimal paragraph-tail balancing must preserve English identifiers, whitespace,
  facts, max width and the existing pagination contract. No other renderer or
  export data changes. Tests run factory_charts/test_factory_exports.py and the
  related export regression targets; no global service changes or business writes.
- After merge Root deploys Python only from clean exact main and regenerates only
  actual native PPT. Excel/PDF same-source passed evidence is reused. Agent cannot
  edit ACTIVE, merge/deploy, access physical phone, or start child agents.

- Frozen implementation validated: export/guard25 passed, exact real fact preserved,
  generated PPT6slides and no lone售; explicit paragraphboundary retained. Root
  reviewed code; PythonLF normalization has identical AST, final25 tests passed
  and standard git diff --check passed. Prior failed nativePPT retained.
- Source scope released to review; Python deployment and native actual PPT Office
  readback remain required, not implied by unit results.
