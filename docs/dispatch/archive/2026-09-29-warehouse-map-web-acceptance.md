# WEB-WH-MAP-POPPER-20260929

- Base SHA: `2b8dd678a907da1eb1cd3da3e3964a4d6cc7d28b`; Owner: Codex.
- Scope: `web-admin/src/views/warehouse/storage-locations/index.vue`; task dispatch files.
- Production F006 acceptance found the full-screen map canvas intercepted clicks on the zone selector's teleported options. The same popup behavior also affects the rack location binding menu. Both popups now stay inside the full-screen panel.
- Before the fix, production UI confirmed zone/location/floor/rack creation, location binding, rectangle drawing, rack dragging, refresh readback, unbinding, and rack/floor deletion. All writes were scoped to `PUT /api/mobile/F006/warehouse/layout`. Temporary objects were removed; the original layout hash before and after was identical (`7e9746b377c9072d54b94ef4a0d9249405ae521b6a1f343a225da1563b232722`).
- Target checks: storage-location Vitest 78/78; `vue-tsc -b` passed. Release build, PR/CI, production Web release, and final UI readback are recorded in their own receipts.
- Separate production dependency issue: AI floor-plan recognition returned 503. Python's vision route received provider `Arrearage` from the first account and exhausted free-tier quota responses from the other two accounts. No recognized layout was applied.
