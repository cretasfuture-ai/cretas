# Candidate read-only multi-turn probe

This is development regression, never independent acceptance or a production deployment.
The versioned Java probe executes the existing Controller, normal/SSE services, resolver
and eight real Tool implementations. Conversation, configuration and RBAC dependencies
are controlled; Sales/Material interfaces read authenticated F006 APIs. The QC count
adapter executes the existing Repository predicate with `BEGIN READ ONLY`.
Order pages apply the stable ordering exported by the candidate SalesService to a
complete authenticated GET collection (maximum1000; incomplete/duplicate collections
fail closed). This exercises candidate sorting over real authorized rows, not the
deployed Repository/JPA execution. Expiry-date scope is verified separately from
stored `EXPIRED` status; opened expectations are never retroactively relabelled.

The actual read actor comes from the authenticated private bridge; locally signed test
JWTs are refreshed per dialogue and checked before/after every request. They never reach
production. No production business mutation or feedback/training endpoint is called.

Prerequisites: Java21; the target contract tests compiled by Maven; a private directory
containing the established `real_business.py` and `readonly_remote.py` adapters and the
existing budgeted `verified_loopback.py` transport in its documented sibling directory.
Keep keys, account truth, catalogs, corpora, actual business rows and traces outside Git.
The transport uses real Luna/mini APIs through the unified budget ledger. Retrieval in
this probe uses the full-catalog LLM fallback, not the live local classifier/vector index.

Run `prepare_live_probe.py --output <private-directory>`, then
`run_complete_read.py --previous-evidence <private-adapters> --output <private-directory>
--corpus <locked-development-json>`. Use `--preflight` for eight-Tool registration and
actual expired/QC reads without model calls. Capture `read_oracle.py --phase before/after`
around the campaign, then run `score_complete_read.py` with the same paths/corpus.

Never overwrite completed runs, tune on protected35/796/held-out sets, call opened cases
independent gold, or count HTTP200/clarification as task success. All turns stay in the
denominator. Report missing carrier scope, expired test authentication, real permissions,
goal/parameter errors and SQL drift separately. Native model sampling can differ between
channels; disagreement alone does not prove an ordinary/SSE implementation difference.

Set `CRETAS_EVAL_STRICT_READ_TOOL_ARGS=1` only on the isolated OpenAI evaluation bridge
to compare strict function schemas. The adapter closes every object, makes original
optional fields nullable on the wire, and drops only optional nulls on return. It does
not fill defaults, invent values, repair undeclared fields, grant permissions or change
the production provider. Required values and explicit0/false remain unchanged. Each
strict schema hash is recorded; strict structure does not guarantee business correctness.

The existing read resolver binds ABSOLUTE page actions only from the model-selected
current-input evidence span using the shared numeric parser. Missing, ambiguous,
nonpositive or conflicting pages fail closed instead of silently reusing the old page.
The scorer retains wrong-page errors as parameters; overlapping IDs become an ordering
failure only when both observed page parameter sets match the expected consecutive pages.
