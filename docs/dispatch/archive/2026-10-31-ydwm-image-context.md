# YDWM-IMAGE-CONTEXT-20261031

Owner root; Base 7769ceb34549f3f9c69f33ddf206a1ae70bfd402; branch codex/ydwm-image-context-20261031; worktree C:/Users/Steve/cretas-ydwm-image-auth-20261031.

Actual UI exposed a pre-existing downstream failure after authorization was corrected: Python /api/llm/vision rejected absent trusted factory context with NoEgressFactoryError, yielding warehouse HTTP503. Preserve the redaction guard; propagate the JWT-validated route factory through the warehouse-only internal transport using the existing server-side internal secret. Do not change Python, provider configuration, global vision behavior, account roles, disabled modules, or Canvas AI authorization.

Scope lock: WarehouseLayoutController.java; PythonLLMClient.java warehouse method/transport only; WarehouseLayoutControllerTest.java; WarehouseLayoutRecognitionPermissionContractTest.java; PythonLLMClientUsageLogTest.java; new PythonLLMClientWarehouseScopeContractTest.java; this task dispatch files.

Acceptance selector: WarehouseLayoutControllerTest,WarehouseLayoutRecognitionPermissionContractTest,PythonLLMClientUsageLogTest,PythonLLMClientWarehouseScopeContractTest. One trusted candidate build/stage; independent security review; PR checks; main-only release; source-backed recognition preview/cancel/local apply/reload, before/after role/module/production-layout equality; no privacy bypass or production business saves.

Pre-merge result: source/test HEAD 6500fc948d2964149bf74ee8e50e5dbe76a945c4; 35 tests PASS (17 controller, 7 real MVC authorization, 6 usage, 5 real HTTP scope), failures/errors/skips 0. Independent risk review found no must-fix security issue. Runtime Java/Python internal-secret presence and equality confirmed without exposing values. Unified candidate build/stage exit0, report cretas-1793419377-32263.json; JAR SHA256 05bc6e9986fc62b55cfd5903d51d9345561b6aa24d5732046e59f2e4e18d6982. Candidate build only; deployment not yet performed. Scope released in final documentation commit; PR checks, main merge, main-only release and actual UI acceptance remain independently required.
