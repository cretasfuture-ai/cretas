# FACTORY-DEMO-DISPLAY-20261021

Base SHA: 1efd457fa89391096dafd76bec4e5cd3809aa769. Owner: /root. Scope released after implementation: factory-onboarding index, interview archive component and component tests only.

Production incident: the running Python process started on October 18 before the existing `CRETAS_PLATFORM_FACTORY_ASSEMBLY_ENABLED=true` configuration became effective. The live OpenAPI lacked the generation route and Java returned 502. No source or configuration rewrite was needed. Verified key Python source hashes against main, no in-flight 8083 connections, then restarted only cretas-python. New process read true; health 200, generation route present, unauthenticated generation rejected with 401. A real platform request using the saved demonstration interview returned three schemas, one finished product, five materials and three processes in 14.25 seconds. This request generated suggestions only and did not replace the applied demonstration blueprint.

The Web now provides a read-only, collapsible full-interview archive on both the conversation and blueprint screens. It renders every stored answer, every pending question with server-provided hints/options, skipped/unresolved items and every history message. Removed the six-item pending-list truncation. No synthetic answers or new conversation are created by inspection.

Validation: 37 onboarding Vitest files / 278 tests passed; vue-tsc --noEmit exit 0. Real UI using the demo tenant: 46 answers and 15 history messages; final answer present. Mobile 390px archive overflow checked. Full build, PR/CI, merge and production UI readback are separate subsequent release facts. Existing demo data and Workflow publication state are unchanged.

Private incident/generation/data evidence: C:/Users/Steve/AppData/Local/Temp/cretas-factory-demo-20261021. Browser evidence: .playwright-mcp/factory-interview-all-items-mobile-20261021.png. Credentials and tokens are excluded from this archive.