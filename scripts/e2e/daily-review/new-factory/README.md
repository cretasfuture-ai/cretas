# P14 new-factory flow harness

`new_factory_flow.py` accepts the `factoryId` and account created by the real
platform API. It logs in through `/api/mobile/auth/unified-login`, proves the
returned `factoryId`, and runs resumable normal API business steps. All business
paths are factory-relative and the only accepted Java URL is
`http://127.0.0.1:10064`.

The intended sequence is:

```text
identity -> master -> workflows -> stock -> purchase -> receipt -> normal -> costs -> readback -> sales -> metrics-readback
```

`receipt` uploads a real attachment through the signed upload URL and requires
the object URL to be `127.0.0.1:18394` before confirming the receipt. `costs`
reuses the P0 `finish_costs` path so same-period indicators can report complete
cost. `metrics-readback` only reads the four platform-created definitions and
the current-period values. There is intentionally no `configure_metrics` command.
Credentials are supplied from a private ignored JSON file with a `password`
field; they are never written to state or evidence.

`bootstrap_platform.py` uses the existing platform credential file plus a
separate private factory credential file. It calls platform factory creation,
then the internal account provisioning endpoint with `companyId`, and finally
resets the generated `${factoryId}_admin` account. It writes the factory receipt
immediately after creation and updates it after account provisioning; reruns
must inspect that receipt instead of creating a second factory.

Before root starts the runtime, run `runtime.py --status-only` after preparing
the independent classes directory. The reserved ports are Java 10064, Python
18096, Web 31764 and object service 18394. The helper rejects P10 output and
does not start, stop, or alter any process.
