"""Transaction-scoped RLS and atomic records + cursor commits."""
import hashlib
import json
from contextlib import contextmanager

from psycopg2.extras import Json

from .contracts import BlacklakeError


class PostgresStore:
    def __init__(self, connection, settings):
        self.connection, self.settings = connection, settings
        destination = connection.get_dsn_parameters()
        if (destination.get("host") not in ("127.0.0.1", "localhost", "::1")
                or not (destination.get("dbname") == "blacklake_poc"
                        or destination.get("dbname", "").startswith("blacklake_connector_test_"))):
            raise BlacklakeError("TEST_DESTINATION_REQUIRED")
        with connection.cursor() as cur:
            cur.execute("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
            if any(cur.fetchone()):
                connection.rollback()
                raise BlacklakeError("DATABASE_ROLE_BYPASSES_RLS")
        connection.rollback()

    @contextmanager
    def transaction(self):
        with self.connection:
            with self.connection.cursor() as cur:
                cur.execute("SELECT set_config('blacklake.tenant_id', %s, true), "
                            "set_config('blacklake.source_factory_id', %s, true)",
                            (self.settings.tenant_id, self.settings.source_factory_id))
                yield cur

    @property
    def scope(self):
        return self.settings.tenant_id, self.settings.source_factory_id

    def cursor(self, resource):
        with self.transaction() as cur:
            cur.execute("SELECT watermark, contract_hash FROM blacklake.cursor "
                        "WHERE tenant_id=%s AND source_factory_id=%s AND resource=%s",
                        (*self.scope, resource))
            return cur.fetchone()

    def upsert(self, cur, resource, external_id, updated, raw, normalized):
        digest = hashlib.sha256(json.dumps(raw, sort_keys=True, ensure_ascii=False,
                                         separators=(',', ':')).encode()).hexdigest()
        cur.execute("INSERT INTO blacklake.raw_record "
                    "(tenant_id,source_factory_id,resource,external_id,content_hash,payload) "
                    "VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                    (*self.scope, resource, external_id, digest, Json(raw)))
        cur.execute("INSERT INTO blacklake.record "
                    "(tenant_id,source_factory_id,resource,external_id,source_updated_at,normalized,content_hash) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s) ON CONFLICT "
                    "(tenant_id,source_factory_id,resource,external_id) DO UPDATE SET "
                    "source_updated_at=EXCLUDED.source_updated_at, normalized=EXCLUDED.normalized, "
                    "content_hash=EXCLUDED.content_hash, observed_at=now() "
                    "WHERE EXCLUDED.source_updated_at >= blacklake.record.source_updated_at",
                    (*self.scope, resource, external_id, updated, Json(normalized), digest))

    def save_cursor(self, cur, resource, watermark, contract_hash):
        cur.execute("INSERT INTO blacklake.cursor VALUES (%s,%s,%s,%s,%s) "
                    "ON CONFLICT (tenant_id,source_factory_id,resource) DO UPDATE SET "
                    "watermark=EXCLUDED.watermark,contract_hash=EXCLUDED.contract_hash "
                    "WHERE blacklake.cursor.watermark <= EXCLUDED.watermark",
                    (*self.scope, resource, watermark, contract_hash))

    def run(self, cur, run_id, resource, start, end, count, status, error=None):
        cur.execute("INSERT INTO blacklake.sync_run "
                    "(tenant_id,source_factory_id,external_id,resource,window_start,window_end,"
                    "row_count,status,error_code) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (*self.scope, run_id, resource, start, end, count, status, error))

    def records(self, resource):
        with self.transaction() as cur:
            cur.execute("SELECT external_id, normalized FROM blacklake.record "
                        "WHERE tenant_id=%s AND source_factory_id=%s AND resource=%s ORDER BY external_id",
                        (*self.scope, resource))
            return cur.fetchall()

    def snapshot(self, resource, start, end):
        with self.transaction() as cur:
            cur.execute("SELECT external_id,content_hash FROM blacklake.record "
                        "WHERE tenant_id=%s AND source_factory_id=%s AND resource=%s "
                        "AND source_updated_at >= %s AND source_updated_at <= %s",
                        (*self.scope, resource, start, end))
            return dict(cur.fetchall())
