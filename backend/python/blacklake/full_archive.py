"""Per-run archive with parent-scoped identity, receipts and resumable partitions."""
import hashlib
import json
import uuid
from datetime import datetime, timezone

from psycopg2.extras import Json

from .contracts import BlacklakeError, at, put
from .sync import timestamp

ARCHIVE_VALIDATION_VERSION = 2

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode()).hexdigest()


class Archive:
    def __init__(self, client, store, contract, run_id):
        self.client, self.store, self.contract, self.run_id = client, store, contract, run_id
        self.started = datetime.now(timezone.utc)

    def partition_id(self, resource, body, context):
        return str(uuid.uuid5(uuid.UUID(self.run_id), resource + digest([ARCHIVE_VALIDATION_VERSION, body, context, self.contract[resource]])))

    def done(self, key):
        with self.store.transaction() as cur:
            cur.execute("SELECT row_count FROM blacklake.sync_run WHERE tenant_id=%s "
                        "AND source_factory_id=%s AND external_id=%s AND status='success'",
                        (*self.store.scope, key))
            result = cur.fetchone()
            return result[0] if result else None

    def fetch(self, resource, body):
        spec = self.contract[resource]
        if not spec.get("paged"):
            response = self.client.query(resource, body)
            rows = at(response, spec["items"])
            if rows is None and spec.get("empty_null"):
                rows = []
            if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
                raise BlacklakeError("FULL_ARRAY_SHAPE_INVALID")
            if spec.get("wrap_parent_response"):
                parent = body["projectIds"][0]
                rows = [{"requested_project_id": parent, "response": rows,
                         "identity_resolved": bool(rows) and all(r.get("projectId") == parent for r in rows)}]
            return rows, len(rows)
        all_rows, expected, fingerprints = [], None, set()
        for page in range(1, 10001):
            request = json.loads(json.dumps(body))
            put(request, "page.pageNum", page)
            put(request, "page.pageSize", 100)
            response = self.client.query(resource, request)
            rows, total = at(response, spec["items"]), at(response, spec["total"])
            if rows is None and total == 0 and spec.get("empty_null"):
                rows = []
            if (type(total) is not int or total < 0 or not isinstance(rows, list)
                    or len(rows) > 100 or any(not isinstance(row, dict) for row in rows)):
                raise BlacklakeError("FULL_PAGE_SHAPE_INVALID")
            if expected is not None and total != expected:
                raise BlacklakeError("FULL_SOURCE_TOTAL_CHANGED")
            expected = total
            fingerprint = digest(rows)
            if rows and fingerprint in fingerprints:
                raise BlacklakeError("FULL_REPEATED_PAGE")
            fingerprints.add(fingerprint)
            all_rows.extend(rows)
            if len(all_rows) > total or (not rows and len(all_rows) < total):
                raise BlacklakeError("FULL_PAGE_INCOMPLETE")
            if len(all_rows) == total:
                return all_rows, total
        raise BlacklakeError("FULL_PAGE_LIMIT_EXCEEDED")

    def identity(self, resource, row, context):
        spec = self.contract[resource]
        values = [at(row, key) for key in spec["keys"]]
        if any(isinstance(v, (bool, dict, list)) or v is None or str(v) == "" for v in values):
            raise BlacklakeError("FULL_STABLE_ID_REQUIRED")
        return json.dumps([*context, *values], ensure_ascii=False, separators=(',', ':'))

    def normalized(self, resource, row, context):
        spec = self.contract[resource]
        factory_path = spec.get("factory")
        if factory_path and str(at(row, factory_path)) != self.client.settings.source_factory_id:
            raise BlacklakeError("SOURCE_FACTORY_MISMATCH")
        if not factory_path and not self.client.factory_verified:
            raise BlacklakeError("SOURCE_FACTORY_NOT_PROVEN")
        values = {}
        for target, source in spec.get("fields", {}).items():
            try:
                values[target] = at(row, source)
            except BlacklakeError:
                values[target] = None
        values["request_context"] = context
        source_updated = None
        if spec.get("updated"):
            value = at(row, spec["updated"])
            if value not in (None, ""):
                source_updated = timestamp(value, "Asia/Shanghai")
        values["source_timestamp_available"] = source_updated is not None
        return values, source_updated

    def ingest(self, resource, body, *, context=(), membership=None):
        key = self.partition_id(resource, body, context)
        previous = self.done(key)
        if previous is not None:
            return {"resource": resource, "count": previous, "reused": True}
        rows, total = self.fetch(resource, body)
        seen = set()
        with self.store.transaction() as cur:
            for row in rows:
                external_id = self.identity(resource, row, context)
                if external_id in seen:
                    raise BlacklakeError("FULL_DUPLICATE_ID")
                seen.add(external_id)
                if membership and not membership(row):
                    raise BlacklakeError("FULL_PARENT_SCOPE_MISMATCH")
                normalized, updated = self.normalized(resource, row, context)
                if resource == "materials":
                    if row.get("projectId") is not None and row["projectId"] != body["projectId"]:
                        raise BlacklakeError("FULL_PARENT_SCOPE_MISMATCH")
                    normalized["parent_scope_verified"] = row.get("projectId") == body["projectId"]
                    cur.execute("SELECT 1 FROM blacklake.full_snapshot WHERE tenant_id=%s "
                                "AND source_factory_id=%s AND run_id=%s AND resource='materials' "
                                "AND normalized->>'id'=%s LIMIT 1",
                                (*self.store.scope, self.run_id, str(row["id"])))
                    if cur.fetchone():
                        raise BlacklakeError("FULL_MATERIAL_ID_REPEATED_ACROSS_PARENTS")
                content_hash = digest(row)
                cur.execute("SELECT content_hash FROM blacklake.full_snapshot WHERE tenant_id=%s "
                            "AND source_factory_id=%s AND run_id=%s AND resource=%s AND external_id=%s",
                            (*self.store.scope, self.run_id, resource, external_id))
                existing = cur.fetchone()
                if existing and existing[0] != content_hash:
                    raise BlacklakeError("FULL_SOURCE_CHANGED_DURING_SCAN")
                if existing:
                    raise BlacklakeError("FULL_DUPLICATE_ACROSS_PARTITIONS")
                cur.execute("INSERT INTO blacklake.raw_record "
                            "(tenant_id,source_factory_id,resource,external_id,content_hash,payload) "
                            "VALUES (%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                            (*self.store.scope, "archive:" + resource, external_id, content_hash, Json(row)))
                cur.execute("INSERT INTO blacklake.full_snapshot "
                            "(tenant_id,source_factory_id,run_id,resource,external_id,content_hash,source_updated_at,normalized) "
                            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                            (*self.store.scope, self.run_id, resource, external_id, content_hash, updated, Json(normalized)))
            self.client.coordination.check_lease()
            self.store.run(cur, key, "full:" + resource, self.started,
                           datetime.now(timezone.utc), total, "success")
        return {"resource": resource, "count": total, "reused": False}

    def raw_rows(self, resource):
        with self.store.transaction() as cur:
            cur.execute("SELECT raw.payload FROM blacklake.full_snapshot s JOIN blacklake.raw_record raw "
                        "ON raw.tenant_id=s.tenant_id AND raw.source_factory_id=s.source_factory_id "
                        "AND raw.resource='archive:'||s.resource AND raw.external_id=s.external_id "
                        "AND raw.content_hash=s.content_hash WHERE s.tenant_id=%s AND s.source_factory_id=%s "
                        "AND s.run_id=%s AND s.resource=%s ORDER BY s.external_id", (*self.store.scope, self.run_id, resource))
            return [row[0] for row in cur.fetchall()]

    def audit(self):
        with self.store.transaction() as cur:
            cur.execute("SELECT s.resource,s.content_hash,raw.payload FROM blacklake.full_snapshot s "
                        "LEFT JOIN blacklake.raw_record raw ON raw.tenant_id=s.tenant_id "
                        "AND raw.source_factory_id=s.source_factory_id AND raw.resource='archive:'||s.resource "
                        "AND raw.external_id=s.external_id AND raw.content_hash=s.content_hash "
                        "WHERE s.tenant_id=%s AND s.source_factory_id=%s AND s.run_id=%s", (*self.store.scope, self.run_id))
            rows = cur.fetchall()
        counts, mismatches = {}, 0
        for resource, expected, raw in rows:
            counts[resource] = counts.get(resource, 0) + 1
            mismatches += raw is None or digest(raw) != expected
        return {"counts": counts, "records": len(rows), "raw_hash_mismatches": mismatches}
