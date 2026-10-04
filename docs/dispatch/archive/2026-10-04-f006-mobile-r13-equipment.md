# F006-MOBILE-R13-EQUIPMENT-20261004

- Owner: Codex/DIRECT; Base SHA: 1a851281b03f26a233fa853f3a0aac334ce7e643.
- Worktree: C:/Users/Steve/cretas-f006-mobile-r13-20261004; branch: codex/f006-mobile-r13-equipment-20261004.
- Scope: frontend/CretasFoodTrace/src/screens/reports/ProductionReportScreen.tsx, src/__tests__/integration/screens/ProductionReportScreenStatistics.test.tsx, src/i18n/locales/{zh-CN,en-US}/reports.json.
- Native evidence: candidate9 production realtime has idle=3, active=4, scrapped=1, each displayed 0%; denominator only running/stopped/maintenance. Report endpoint counts FactoryEquipment.status registration, not telemetry.
- Preserve factory/actor/module gates. Count all valid registered statuses in distribution, retain unknown status counts, label registration basis, do not treat active as measured live running. Missing operating samples must stay unknown. No backend, Entity, Repository, permissions, credentials or business writes.
- Acceptance: ProductionReportScreenStatistics target + TypeScript + relevant RN gates; full RN CI before merge; native acceptance after a later audit APK containing final source. Do not equate APK9 with this source.
