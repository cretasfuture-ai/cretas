# Second-customer onboarding package

This is a customer-neutral, offline package for preparing a second factory's
assembly interview. It is an input package, not an applied configuration and
not a production readiness claim.

The payload follows the current `PlatformAssemblyInterviewController.ApplyAIInitRequest`
shape. The nested `suggestedData` fields are deliberately kept aligned with the
server DTO; work-process custom fields use the current
`WorkProcessCustomFieldSchema` contract (`key`, `label`, `type`, `enabled`,
`unit`, `standardValue`, `min`, `max`), and module IDs are checked against the
current `InterviewModuleConfigCatalog` vocabulary.

## Safe use

1. Copy `package.template.json` to a private working file.
2. Fill only confirmed customer facts and add a source reference for every
   confirmed fact under `evidenceByJsonPointer`.
3. Keep `targetFactoryId` null: the package is customer-neutral and is not
   bound to a tenant. A typed platform `super_admin` must separately confirm
   the target when reviewing
   `/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/preview`.
   `GET /api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/catalog`
   is the catalog reference, not proof that an offline package is current. The
   factory admin readiness endpoint is only for the same factory after it exists.
4. Run the validator locally. By default it refuses `UNCONFIRMED` packages.
   `--emit-preview` is an explicit export of confirmed facts only. It prints an
   envelope containing `status`, `canonicalDigest`, `payload`, evidence,
   unresolved items, and limitations, with status
   `OFFLINE_PREVIEW_UNVERIFIED`; it does not claim catalog or route semantics
   were verified. If a later authorized request needs the typed
   `ApplyAIInitRequest`, extract only the envelope's `payload`; this script does
   not send that request.

The typed `moduleConfigs` list is checked against the current interview catalog.
The legacy `suggestedData.modules` boolean map is preserved as a separate DTO
field and always remains an unresolved server-preview concern; its keys are not
treated as catalog module IDs.
5. Submit the resulting package to the authorized preview/review workflow.
   Do not call the apply endpoint from this script, provision users, publish a
   workflow, or place credentials/URLs/source business rows in the package.

```powershell
node scripts/onboarding/second-customer-package/validate-package.mjs `
  docs/onboarding/second-customer-package/package.template.json
```

The command should refuse the untouched template because its status is
`UNCONFIRMED`. See `acceptance-checklist.md` for the human and server-side
gates that remain after offline preparation.
