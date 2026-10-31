# YDWM-IMAGE-AUTH-20261031

Owner: root
Base: f703c96d18a5d18ac482712adde99d94656f8988
Worktree: C:/Users/Steve/cretas-ydwm-image-auth-20261031
Branch: codex/ydwm-image-auth-20261031

Scope lock: backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WarehouseLayoutController.java; existing WarehouseLayoutControllerTest.java; new WarehouseLayoutRecognitionPermissionContractTest.java; this task dispatch records.

User explicitly selected: only repair image recognition authorization according to existing warehouse write permission. No role changes, no module switches, no Canvas AI authorization changes, no business data saves or layout writes in production. Local prototype may save recognition results in an isolated practice session.

Acceptance selector: WarehouseLayoutControllerTest,WarehouseLayoutRecognitionPermissionContractTest. Use one trusted candidate package via release-cretas.sh --phase build --base-sha <Base> --tests <selector> --stage-backend YES-STAGE; independent risk review; PR/required checks; main-only release; real ydwm_admin recognition UI preview/cancel/local apply/reload and source permission/module/layout readback.

## Candidate verification and scope release

Implementation/test HEAD: 7c8cdc35d2521b08ba94a01cc79fe5bf6ef0c8c7. Source: only recognition annotation changed to warehouse:write; existing save/move roles and Canvas AI unchanged.

Trusted candidate build exit 0, RELEASE_FINAL_STATUS=built exactly once; report C:/Users/Steve/.cache/cretas/release-reports/cretas-1793417036-27679.json. Selector WarehouseLayoutControllerTest,WarehouseLayoutRecognitionPermissionContractTest executed 23 tests (17+6), failures/errors/skips all 0. JAR SHA256 c3568d5486cc8c1f6b2a2870fd94bb45374bdb5b9f3ff859a315cc49d2fccb2e staged in immutable server cache, not installed and no upstream switch.

Independent read-only risk review at same HEAD: source/target tests approved; no P0/P1/P2 blocker. Real JWT/permission/role interceptors exercised with mocked JwtUtil; no claim of a real-signed platform JWT test. Effective warehouse permission rw/w allowed and r/- denied, not a claim that this test exercises Canvas enabled=false. No Entity/Repository/migration changes.

Final commit only archives this task and releases code scope atomically on merge. PR checks, merge, main-only release, actual-account UI recognition and unchanged-role/module/production-layout readback remain separate pending facts, with execution receipts retained in local prototype evidence/image-auth/.
