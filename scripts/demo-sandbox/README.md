# F006 resettable demo sandbox

This directory implements one physically isolated, resettable F006 demo slot.
It is disabled in the Java gateway unless explicitly configured. A source build
or passing unit test does not mean the slot has been provisioned or deployed.

The browser fills normal forms and uses normal business endpoints. An active
session routes those requests through the gateway and loopback manager to an
independent Java service, PostgreSQL database and Redis. Production JWTs and
cookies are never passed to the isolated service. The node uses a separate
signing key and its network has no external access.

`baseline_export.py` exports only approved F006 demo master records and their
required parents, scrubs sensitive fields, and records schema/catalog/data
fingerprints. Its output is restricted private material: never commit it.
Business source rows are read in a repeatable-read, read-only transaction.
It does not copy the existing production demonstration transactions or photos.

`create_private_config.py` creates a new private directory, independent secrets,
an immutable JAR copy and fixed-topology configuration. It requires the SHA-256
fingerprint of the production signing key, never that key. On Windows it removes
inherited ACLs and grants only the current account and SYSTEM.

Use `hooks/sandbox_hook.py provision --config <runtime.private.json>` to create
the task's containers/network/volumes, then `bootstrap` once to establish a clean
baseline and an empty session manifest. Existing resources are never adopted.
Interrupted provisioning requires explicit identity review. PostgreSQL has no
host port; psql runs through Docker exec. The real cached pgvector dependency is
installed before the first restore without stopping PostgreSQL.

Java 10040 and Redis 6387 retain strictly loopback-only HostConfig bindings;
PostgreSQL has none. The internal-network transport does not depend on those
bindings being reachable (Docker Desktop may expose no effective host port).
It executes the image's real curl inside the registered Java container, using
`curl -q --config -`, HTTP only, with JWT/configuration and UTF-8 business bodies
on stdin. It never places node credentials in argv or follows redirects. Binary
demo attachments use the manager's local file endpoint. First preparation may
take several minutes on a local Docker host; enter before starting the demo.

Run the manager:

```text
python scripts/demo-sandbox/runtime.py --config C:\path\sandbox.private.json
```

The control key is sent as `X-Sandbox-Control-Key`; the actor is an integer in
`X-Sandbox-Actor-Id`. The fixed proxy route is
`/sessions/{uuid}/proxy/api/mobile/F006/...`.

The checked-in template intentionally contains no token or credential.

Lifecycle: `PREPARING` → `READY` → `ENDING` → `ENDED`. Any incomplete preparation,
identity check or reset becomes `FAILED`; business requests remain blocked and
the owner can retry ending the session. A missing/corrupt manifest cannot silently
unlock an existing slot. On restart, interrupted sessions require cleanup.

Ending a session drains active requests, stops only this slot's Java container,
resets all public tables in one transaction, restores approved masters, resets
sequences, clears this slot's Redis and deletes only the session's attachment
directory. It verifies registered container/network/volume/database identities,
JAR/configuration hashes, full table-content hashes and schema hash. The Java
root filesystem is read-only; writable runtime paths are tmpfs. PostgreSQL and
Redis volumes are retained. No Docker daemon/WSL shutdown, volume removal,
production transaction deletion or shared cache flush is performed.

Preparation creates one clearly named isolated finance-review fixture so the
normal approval chain has a valid independent role. Its password is disabled,
its platform access is empty and the manager never issues credentials for it.
The presenter continues using f006_admin. The fixture is removed by reset along
with the round's other added rows. Each preparation also renews the fixed demo
actor's 24-hour node token using the independent node key; an old token in the
private startup file does not prevent demonstrating again on a later day.

The finished flow remains available for cost and reverse-trace viewing until
the user clicks **结束演示并清空**. A new round is available only after `ENDED`.
Historical SmartBI and photo examples remain in production and are accessed
outside an active isolated round; the isolated node cannot call external AI.
The exact pure form-assistant parse endpoint can generate visible field prefills
at the gateway; form submission still writes through the isolated session.

`local_relay.py` is an optional same-origin Vite development bridge at
127.0.0.1:10050. It authenticates against production, limits the caller to F006
actor 1309, forbids production business mutations, and blocks ordinary production
business reads while a sandbox session exists. It is not a production gateway
deployment. Use a separate local browser profile and Vite port 5186. Never make
the manager, private configuration or relay publicly accessible.

Tests: `python -m unittest discover -s scripts/demo-sandbox/tests -v`.
Real acceptance additionally requires two complete business rounds, resetting
between them and verifying old session access is rejected and all isolated
content returns to the registered baseline. Unit tests alone do not prove this.

## Linux service template (review-only)

On Linux, private configuration is created only in a new direct child of
`/var/lib/cretas/f006-demo-sandbox`; the approved parent itself, existing
directories, repository paths, and symlink escapes are rejected. The slot
directory is mode 700 and generated private files are mode 600. Unsupported
platforms fail closed. The generator accepts only the production signing-key
SHA-256 fingerprint and creates independent sandbox keys; it never reads a
production secret.

`linux_service.py` renders, without running systemd or touching a remote
host, a review-only unit for the loopback manager at `127.0.0.1:10041` and
the fixed runtime config
`/var/lib/cretas/f006-demo-sandbox/runtime/runtime.private.json`. The unit
uses the dedicated
`/opt/cretas/f006-demo-sandbox/venv/bin/python` interpreter (Python 3.9+)
and read-only code directory. It also writes a clearly labelled blue/green
gateway environment review file using the real Spring relaxed-binding names
`CRETAS_DEMO_SANDBOX_ENABLED`,
`CRETAS_DEMO_SANDBOX_MANAGER_BASE_URL`, and
`CRETAS_DEMO_SANDBOX_CONTROL_KEY=` as a blank placeholder. It contains no
key, bearer token, or production endpoint. The service runs as root because
Docker socket access is root-equivalent; its command, loopback manager, and
private paths remain fixed. An operator must review and install these files
through the normal change process; this script never invokes daemon-reload,
restarts a service, removes volumes, or changes an active production gateway.
