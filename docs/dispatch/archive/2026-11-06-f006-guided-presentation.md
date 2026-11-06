# F006-GUIDED-PRESENTATION-20261106

- Owner root; Base SHA 7872d98a83c370a4d284a071e9c65601cf4f5cbf.
- Scope released: demo-flow/index.vue, AppLayout.vue, f006Presentation.ts and their three target test files.
- Previous release stayed on the console and waited only 1.4 seconds. The new presentation keeps one actor-scoped controller across real business routes, opens this round's actual order/batch or supported warehouse/production list, and refreshes business-tab caches through the existing workspace mechanism.
- Default is manual. Continuous presentation holds the visible form and the returned business result separately for 10, 20 or 30 seconds (default10). Loading does not consume the hold. Pause/resume restarts a full hold, background visibility pauses, unexpected navigation pauses even during an internal page load, and failures stop playback. No playback state is persisted or auto-resumed after reload or actor change.
- Raw Axios adapter tracking preserves existing envelope handling and response bytes. Start/finish balance includes pre-adapter guard rejection and retry/disposal. Generic cross-page AI automation remains outside this fixed workflow.
- Validation: 28 target files/183 tests passed; git diff --check passed. Source review found navigation-race and pre-adapter accounting issues; both corrected with behavioral regressions. Final trusted Web build, exact-head CI, production release and paced formal UI/full-flow/reset acceptance are delivery gates recorded separately after this scope checkpoint.
- No backend, migration, ordinary production ERP data or sandbox snapshot change in this slice.
