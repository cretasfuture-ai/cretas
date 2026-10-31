# YDWM-IMAGE-CONTEXT-20261031

Owner root; Base 7769ceb34549f3f9c69f33ddf206a1ae70bfd402; branch codex/ydwm-image-context-20261031; worktree C:/Users/Steve/cretas-ydwm-image-auth-20261031.

Actual UI exposed a pre-existing downstream failure after authorization was corrected: Python /api/llm/vision rejected absent trusted factory context with NoEgressFactoryError, yielding warehouse HTTP503. Preserve the redaction guard; propagate the JWT-validated route factory through the warehouse-only internal transport using the existing server-side internal secret. Do not change Python, provider configuration, global vision behavior, account roles, disabled modules, or Canvas AI authorization.

Scope lock: WarehouseLayoutController.java; PythonLLMClient.java warehouse method/transport only; WarehouseLayoutControllerTest.java; WarehouseLayoutRecognitionPermissionContractTest.java; PythonLLMClientUsageLogTest.java; new PythonLLMClientWarehouseScopeContractTest.java; this task dispatch files.

Acceptance selector: WarehouseLayoutControllerTest,WarehouseLayoutRecognitionPermissionContractTest,PythonLLMClientUsageLogTest,PythonLLMClientWarehouseScopeContractTest. One trusted candidate build/stage; independent security review; PR checks; main-only release; source-backed recognition preview/cancel/local apply/reload, before/after role/module/production-layout equality; no privacy bypass or production business saves.
