# YDWM-IMAGE-AUTH-20261031

Owner: root
Base: f703c96d18a5d18ac482712adde99d94656f8988
Worktree: C:/Users/Steve/cretas-ydwm-image-auth-20261031
Branch: codex/ydwm-image-auth-20261031

Scope lock: backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WarehouseLayoutController.java; existing WarehouseLayoutControllerTest.java; new WarehouseLayoutRecognitionPermissionContractTest.java; this task dispatch records.

User explicitly selected: only repair image recognition authorization according to existing warehouse write permission. No role changes, no module switches, no Canvas AI authorization changes, no business data saves or layout writes in production. Local prototype may save recognition results in an isolated practice session.

Acceptance selector: WarehouseLayoutControllerTest,WarehouseLayoutRecognitionPermissionContractTest. Use one trusted candidate package via release-cretas.sh --phase build --base-sha <Base> --tests <selector> --stage-backend YES-STAGE; independent risk review; PR/required checks; main-only release; real ydwm_admin recognition UI preview/cancel/local apply/reload and source permission/module/layout readback.
