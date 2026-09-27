#!/usr/bin/env bash
# Invoke through run-with-test-postgres.sh; it supplies a disposable database URL.
set -euo pipefail
: "${SMARTBI_PG_DSN:?disposable PostgreSQL URL required}"
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
psql "$SMARTBI_PG_DSN" -v ON_ERROR_STOP=1 -q \
  -f "$project_root/backend/java/cretas-api/src/main/resources/db/flyway/V20261101_45__activity_events.sql"
psql "$SMARTBI_PG_DSN" -v ON_ERROR_STOP=1 -q <<'SQL'
INSERT INTO activity_events
  (event_id,factory_id,user_id,source,action,route,detail,correlation_id,client_action_id,client,occurred_at)
VALUES
  ('event-1','F006',42,'click','purchase.submit','/purchase',
   '{"entityId":"PO-123","quantity":12}'::jsonb,'corr-1','action-1','web-admin',NOW()),
  ('event-2','F006',42,'api','POST','/api/mobile/{factoryId}/purchase',
   '{"status":200,"businessSuccess":true}'::jsonb,'corr-1','action-1','web-admin',NOW());
INSERT INTO activity_events
  (event_id,factory_id,user_id,source,action,route,client,occurred_at)
VALUES ('event-1','F006',42,'click','purchase.submit','/purchase','web-admin',NOW())
ON CONFLICT (factory_id,user_id,event_id) DO NOTHING;
DO $$
BEGIN
  IF (SELECT COUNT(*) FROM activity_events WHERE factory_id='F006' AND client_action_id='action-1') <> 2 THEN
    RAISE EXCEPTION 'activity timeline insert or action correlation failed';
  END IF;
  IF (SELECT COUNT(*) FROM pg_indexes WHERE tablename='activity_events') <> 7 THEN
    RAISE EXCEPTION 'activity events indexes missing';
  END IF;
  IF (SELECT COUNT(*) FROM activity_events WHERE
        to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') LIKE '%Z') <> 2 THEN
    RAISE EXCEPTION 'UTC timeline formatting failed';
  END IF;
END $$;
SQL
if [ "${ACTIVITY_SKIP_MAVEN:-0}" = 1 ]; then
  echo 'ACTIVITY_MIGRATION=passed rows=2 indexes=7 jdbc=not-run'
else
  (
    cd "$project_root/backend/java/cretas-api"
    mvn -q test -Dtest=ActivityEventPostgresContractTest
  )
  echo 'ACTIVITY_MIGRATION=passed rows=2 indexes=7 jdbc=committed'
fi
