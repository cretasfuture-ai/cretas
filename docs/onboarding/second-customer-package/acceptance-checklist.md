# Acceptance checklist

Offline preparation is complete only when all of the following are recorded.

- [ ] The package contains no credentials, tokens, URLs, contacts, customer
      IDs, source factory IDs, attachments, or copied business rows.
- [ ] Every non-empty customer fact is confirmed by the customer and has a
      source reference in `evidenceByJsonPointer`.
- [ ] `overwrite` is explicitly `false` and
      `suggestedData.provisionDefaultUsers` is explicitly `false`.
- [ ] Product/material codes, conversion references, work-process names,
      product routes, route node IDs, and module IDs pass offline structural
      checks. Unknown unit/catalog/route meaning remains unresolved.
- [ ] The offline validator was run with no network access and its canonical
      digest was recorded.
- [ ] A typed platform `super_admin` reviewed the authoritative
      `/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/preview`
      response. The preview must be business-zero and must identify the exact
      target factory, current catalog version/digest, unresolved items, and
      proposed writes.
- [ ] The platform catalog was read through
      `GET /api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/catalog`; an offline
      catalog pin is never reported as verified by this package.
- [ ] The preview was compared with the package and customer evidence. No
      apply, account provisioning, or workflow publication occurs as part of
      this package.
- [ ] After the factory exists, its same-factory `factory_super_admin` checked
      `/api/mobile/{id}/factory-readiness/current`. Platform preview authority
      and factory readiness are separate checks.
- [ ] Any later apply/approval, account setup, workflow publication, and real
      business acceptance are recorded separately; this package does not claim
      any of them.
