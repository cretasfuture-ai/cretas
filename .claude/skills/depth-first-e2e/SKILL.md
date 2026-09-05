---
name: depth-first-e2e
description: Use when designing, running, or auditing multi-round E2E campaigns; enforcing numeric coverage thresholds; writing L3/L4 deep tests; a deep E2E test finds a real bug; reviewing E2E depth/coverage; or deciding whether an E2E round is test-complete versus delivery-complete. Triggers on "5 轮测试", "多轮 E2E", "E2E 综合测试", "spec §8.2 阈值", "测试覆盖率", "same-cause sweep", "深度测试", "depth-first".
---

# Depth-First E2E

Prevents shallow page checks from being reported as deep business coverage. Origin: a 5-round campaign met every §8.2 threshold and found 1 real bug in 1181 test points, because every "L4 flow" was page-accessibility in disguise; a later 12-round depth-only campaign caught 0 of 16 customer-reported bugs because 13 of them lived in modules at zero coverage.

For concrete Playwright patterns use `project-playwright-e2e` (Codex) / `e2e-web-admin`; for one-off UI verification use `e2e-web-admin`.

## Core rule: every record carries `depth`

| depth | Criteria |
|---|---|
| `smoke` | Page renders, keyword exists, table visible, loose row count. No real submit / persistence / detail verification. |
| `medium` | Form filled and submitted, API/toast captured, but no full detail/readback verification. |
| `deep` | Fill + submit + API/toast + fresh list/persistence (exact `+1` or business-key lookup) + detail or downstream readback. |

A test that silently skips verification because prerequisite data is missing downgrades to `medium`/`smoke`; PASS+WARN with no real data is not deep. Tests without `depth` are invalid in the audit.

## Hard rules

1. Every multi-round campaign (3+ rounds) keeps a coverage matrix by module × depth, produced in Round 0 and updated every round. No round goes deep on module X while another module sits at `none` without a documented exemption; three consecutive rounds leaving a module at `none` force a breadth round. "Scheduled in triage doc" is not coverage.
2. Each round adds at least one new `depth: "deep"` L4 (or equivalent business-flow) test.
3. Do not satisfy numeric targets with smoke tests that look like L4; spec §1.3 hard rules (filled + toast + list-after) beat §8.2 numbers. If §8.2 can only be hit by violating §1.3, report BLOCKED.
4. Audit reports the depth breakdown (smoke / medium / deep) and answers, per L4 test: would it fail on backend 500, on broken submit, on silent persistence failure, on wrong downstream state; did it create prerequisite data; has it ever caught a real bug.
5. A deep test that finds a real bug requires a **same-cause sweep** before the fix is called complete (below). The sweep scope is the *pattern*, not the module.
6. The Critic phase of an audit is a separate, zero-context agent (a recon/Explore subagent given the diff, the test that caught the bug, and "what does this fix NOT cover?"); its output is pasted verbatim. Self-impersonated critics ask softball questions. Self-critique is fine for plan self-review and progress notes.
7. Non-trivial fix diffs (>50 LOC, or touching async / interceptor / transaction boundaries, or new UI flows) get an independent review before commit; self-review found 0/3 of the bugs a clean reviewer found 3/3.
8. A committed branch is not delivered. Before calling a round delivery-complete: branch pushed, PR opened, prod deploy plan with owner and date (or an explicit reason the round is test-env-only), deferred items registered in `docs/dispatch/ACTIVE.md`, CI integration status stated.
9. "Next round will handle it" needs a concrete tracked item with owner and test design; otherwise it is Rule 8's failure mode ("later" = never).
10. Repeating the same full suite does not increase depth; count new assertions and states, not executions. After a combined production release, run one final read-only acceptance; further full reruns need a new deployment or a changed shared prerequisite.

When the user chooses to skip any of these, state what evidence is lost, mark the round accordingly, and proceed as decided.

## Risk-first order and rerun budget

Within a round: (1) the highest-risk persisted legacy state related to the change (old `g/box/case` snapshots, historical rows); (2) the focused regression for the changed workflow; (3) broader page and cross-module coverage only after (2) passes.

Classify before rerunning: harness failure → fix locator/fixture/expectation and rerun that scenario; product failure → focused failing assertion, root-cause fix, same-cause sweep, rerun affected scenarios; shared prerequisite changed (login, seed data, bootstrap, deployment version) → rerun the dependent suite; new deployment → one final production read-only acceptance. Do not rerun a full browser suite for a cleaner screenshot.

## Deep test checklist

Prerequisite data created or fail-fast checked · real navigation to the entry point · dialog opened and required fields filled · submit with precise API or UI success evidence · exact toast/response captured · fresh navigation or reload · strict persistence check (`+1` exactly or stable business key) · detail or downstream readback · screenshot or structured evidence path. Any core step missing → label `medium` or `smoke`. Concrete Element Plus / Playwright patterns: `references/deep-test-patterns.md`, checklist detail: `references/depth-checklist.md`.

## Same-cause sweep

1. State the root cause as a searchable pattern (e.g. `JdbcTemplate.update` without `@Transactional`; `@RequireRole` missing on a POST; `text.includes(keyword)` as evidence).
2. Search the relevant area **and unrelated modules** with the same idiom. Subagent sweeps have 50–80% false positives on cross-file patterns (URL-template construction, dictionary dispatch, annotations): hand-verify every match with a direct grep before counting it.
3. Report patterns searched, files/lines matched, verdict per match: `vulnerable` / `safe` / `needs verification`.
4. Fix vulnerable siblings in the same round when feasible; otherwise register a concrete tracked item with file references and a test design.

Why: R3 fixed `setCustomFields` missing `@Transactional` and left three sibling endpoints with the identical bug; all metrics were green. See `references/case-r3-incomplete-fix.md`, `references/case-r7-rating-bug-sweep.md`.

## Round summary schema

```json
{ "round": 1, "specTotal": 30, "effectiveTotal": 28, "actualExecuted": 24, "actualPass": 23,
  "depthBreakdown": { "smoke": 14, "medium": 7, "deep": 3 },
  "actualBugsFound": 1, "sameCauseSweep": "done | not_applicable | blocked",
  "deliveryStatus": "test_complete | delivery_complete | blocked" }
```

Do not report only `{ pass, total }`; it hides shallow coverage. Remediating an existing shallow suite: keep the tests, relabel them `smoke`, add deep tests alongside, rewrite the report honestly.

## References

- `references/anti-patterns.md`: shallow-test anti-patterns and the 5-grep detector for a compromised suite.
- `references/depth-checklist.md`, `references/audit-rules.md`, `references/deep-test-patterns.md`.
- `references/case-r3-incomplete-fix.md`, `references/case-r5-delivery-gap.md`, `references/case-r7-rating-bug-sweep.md`.
