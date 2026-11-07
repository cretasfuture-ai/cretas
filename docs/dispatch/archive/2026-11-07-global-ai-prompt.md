# GLOBAL-AI-PROMPT-20261107

Base: 49009fd44ad1f348c5599a460744dd7ca177aebe. Owner: /root, executor /root/backend_demo.

Adds the server-owned UI_PAGE_ACTION prompt to the existing authenticated, quota-metered form-assistant parser. It returns exactly one visible-page action suggestion in field_values; no business Tool, SQL, or write service is dispatched. Original-page controls remain responsible for business submissions and permission checks. This is the planning half of GLOBAL-AI-PAGE-20261107, delivered separately so backend CI can run while the browser driver is integrated; it does not expose a global UI by itself.

Scope: UI_PAGE_ACTION.md and UiPageActionPromptContractTest.java. Validation: FormPromptRegistryTest, UiPageActionPromptContractTest, FormAssistantEgressRedactionTest; 18 passed, no failures/errors/skips. Existing seven prompts remain covered. No Entity, Repository, migration, security or business-service changes. Frontend scope remains with the coordinator; deployment is bundled with the completed browser driver after applicable CI/build/acceptance gates.
