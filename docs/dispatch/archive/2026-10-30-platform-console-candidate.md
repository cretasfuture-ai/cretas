# PLATFORM-CONSOLE-20261030 candidate freeze

Release Base SHA: c51415ce6ec4de1eb1ac38620ee0b6574b90cc73
Original Base SHA: cf6d18e1bf8656caafc9041aaabc1c9f264c335f
Owner: root. PR: https://github.com/cretasfuture-ai/cretas/pull/1338

Status: review; implementation frozen, scope locks released with candidate commit. Merge, production activation and actual UI acceptance remain pending; this archive does not assert their completion. All subagents returned ownership. Java/Web only; Python has no task diff and is not deployed. Detailed scope: 2026-10-30-platform-console-scope.md.

Evidence (private): C:/Users/Steve/.codex/tmp/platform-console-20261030/. Local Web full: 6724 passed / 5 skipped, followed by 34 passed changed-boundary tests; final vue-tsc exit 0. Java security/unit targets 101 passed, actual PostgreSQL concurrency 3 passed / 0 skipped, formal-activation PostgreSQL 1 passed / 0 skipped. Final unified candidate build will run all 105 targets together and publish its manifest; required exact-head CI and independent security review remain release gates.

Archived rows:
- `PLATFORM-CONSOLE-20261030` — `in-progress` — Owner root — Base cf6d18e1bf8656caafc9041aaabc1c9f264c335f — 平台日常菜单与工厂业务分离；选厂配置菜单、保留实名平台主体的只读协助查看。UI/API子scope与契约见archive/2026-10-30-platform-console-scope.md；目标测试、独立终审、CI、main发布及UI验收。
- `PLATFORM-CONSOLE-UI-20261030` — `review` — Owner root — Base cf6d18e1bf8656caafc9041aaabc1c9f264c335f — 仅前端平台导航/选厂菜单配置/只读查看上下文及目标测试；精确scope见同批scope归档；不改后端/ACTIVE。
- `PLATFORM-CONSOLE-API-20261030` — `review` — Owner root — Base cf6d18e1bf8656caafc9041aaabc1c9f264c335f — 仅平台菜单配置API、工厂旧入口敏感字段保护、带平台主体的短期只读查看令牌与Java正向护栏及目标测试；Python不改、不开放查看凭证；不改前端/ACTIVE/Entity/Repository。
- `PLATFORM-CONSOLE-NETWORK-20261030` — `review` — Owner root — Base cf6d18e1bf8656caafc9041aaabc1c9f264c335f — 仅utils/platformFactoryViewNetwork.ts、对应测试、main.ts接线；原生fetch/XHR短期凭证与只读护栏，验收命令和scope见同批归档；不改Root/API源码或ACTIVE。
- `PLATFORM-CONSOLE-20261030`：仅root写ACTIVE/本批归档、审查集成及发布；前后端互斥scope与API契约以archive/2026-10-30-platform-console-scope.md为准；不修改其他工厂业务数据、不打开factory-assembly全局灰度。
