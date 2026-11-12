# One-minute isolated read-only observation

This adapter runs only for the fixed `BLACKLAKE_POC_TEST` tenant and its encrypted factory binding. It never invokes source business mutations. Source credentials remain in Windows CurrentUser DPAPI files; browser requests contain no source credentials.

`python -m blacklake.live_monitor --config <absolute-private-config.json>` collects one cycle every 60 seconds for a seven-day observation window. Cycles are serialized with a renewable local lease and the connector's Redis connection lock/quotas. Slow cycles finish before the next starts; no overlapping scans or fabricated minute records. Windows must remain logged in, awake and online. At-logon tasks resume the same database; interruptions and failures remain visible in the page.

Private configuration (no credentials):

```json
{
  "schema": "blacklake-live/1",
  "tenant_id": "BLACKLAKE_POC_TEST",
  "source_factory_id": "<verified-factory-id>",
  "baselineRunId": "<immutable-baseline-run>",
  "intervalSeconds": 60,
  "days": 7,
  "db": "D:/private-blacklake/monitor.sqlite3",
  "observation": "D:/private-blacklake/observations-private.json",
  "bom": "D:/private-blacklake/bom-supplement-private.json",
  "supplement": "D:/private-blacklake/supplement-private.json"
}
```

Set `BLACKLAKE_LIVE_CONFIG` to that file in the owned isolated adapter launcher. Keep `BLACKLAKE_PERIOD_SCHEDULER_ENABLED=0`: the live lane performs the existing durable nightly tick at Beijing 23:55, without a duplicate scheduler task. The period endpoint reports this actual execution lane and persists nightly failures separately.

Use `scripts/windows/blacklake-live-monitor.ps1 -Install -Config <path> -Python <python.exe> -LocalData <LocalAppData>` for the collector. Add `-AdapterLauncher <owned-profile.py>` to install its adapter supervisor. The installer resolves the actual DPAPI/launcher paths before registering the task, including packaged-app redirection; source secrets are neither copied nor printed. Only the explicitly owned adapter signature can occupy port8095. An already running owned adapter is supervised until it exits, preserving in-flight reports.

Each cycle refreshes visible projects in all documented statuses, their tasks/reports, both stock-order directions, current definitions and product/warehouse stock pairs. Changed/new project material-parent filters are checked by ID and code; all are revalidated hourly. BOM references are checked daily and on product-set changes. Actual cost uses refreshed movements/task wages, not cached standard material quantities. All reference and fact clocks are retained separately. Disappeared parents/incomplete scans fail visibly while retaining the last successful snapshot, rather than silently deleting data.

SQLite stores every cycle receipt and content-addressed source payload. It validates history hashes and keeps Beijing date boundaries; source snapshots are never summed. Each originally negative pair remains traceable even when its current balance is not returned. Missing balance is unknown, not zero/resolved. Reconciliation uses detail warehouse IDs before header warehouse IDs, audited statuses, signed stock changes and identical units. Without a verified opening/closing boundary it does not claim a full stocktake reconciliation or physical shortage.

Deterministic statistics/reconciliation update each cycle. Changed facts enter one coalescing local27B report lane; unchanged facts reuse saved analysis. Native model retries and bounded failure recovery do not impose a normal analysis-count quota. The final unseen snapshot can still be analyzed after collection ends. Rules, queued/partial AI results, usage and history are separately labeled. Current product costs use the same pinned live cycle; archived original44 explanations keep explicit historical engine/run/time provenance.

`GET /live-monitor` exposes only the authorized fixed scope, recent cycle counts, Beijing daily totals, visible gaps and saved report links. `history_offset` pages all retained receipts in batches of100; the gateway accepts only this bounded integer for this route. Browser refresh is30seconds; source collection is60seconds. Full saved reports remain individually retrievable. Local JSON/SQLite backups and the Git bundle are kept outside tracked files. Seven-day completion requires real elapsed time; successful startup is not week-long acceptance.
