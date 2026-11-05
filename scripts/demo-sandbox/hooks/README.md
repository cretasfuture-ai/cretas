# Fixed local hooks

`sandbox_hook.py` is a local operator CLI for `provision`, `bootstrap`, `prepare`
and `reset`, using `--config <absolute-runtime.private.json>`.

The fixed PostgreSQL database is `f006_demo_sandbox`, reachable only through the
internal Docker network or Docker exec; Java uses 10040, manager 10041 and Redis
6387 on loopback. Preparation/reset use the orchestrator's registered physical
identities and full baseline proof. HTTP callers cannot supply executable paths,
shell commands or database connections. Bootstrap refuses to overwrite any
existing session manifest. See the parent README for lifecycle and verification.
