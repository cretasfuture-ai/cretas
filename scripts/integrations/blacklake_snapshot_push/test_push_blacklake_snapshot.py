"""push_blacklake_snapshot 的投影与采集库读取（合成数据，不碰真实采集库）。

python -I -m unittest discover -s scripts/integrations/blacklake_snapshot_push -p "test_*.py"
"""
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import push_blacklake_snapshot as p  # noqa: E402

PAYLOAD = {
    "data": {
        "process": [{"id": 1, "name": "腌制", "outputUsers": ["张三"]}, {"id": 2, "name": "烟熏"}],
        "output": [
            {"endtimeofReport": "2026-11-14 10:00:00", "productCode": "CP1", "productName": "牛肉", "processId": 1,
             "stockUnit": "KG", "numberofgoodProducts": "10.5", "numberofDefectives": "0.5",
             "productionWorkerKey": "W-1", "estimatedSalary": "88", "effectiveTime": 3600},
            {"endtimeofReport": "2026-11-14 15:00:00", "productCode": "CP1", "productName": "牛肉", "processId": 1,
             "stockUnit": "KG", "numberofgoodProducts": "4.5", "numberofDefectives": "0",
             "productionWorkerKey": "W-2", "estimatedSalary": "12"},
            {"endtimeofReport": "2026-11-14 16:00:00", "productCode": "CP1", "productName": "牛肉", "processId": 2,
             "stockUnit": "KG", "numberofgoodProducts": "7", "numberofDefectives": "1"},
            {"endtimeofReport": "", "productCode": "CP9", "productName": "坏行", "processId": 2,
             "numberofgoodProducts": "1"},
        ],
    }
}


class ProjectionTest(unittest.TestCase):
    def test_aggregates_by_date_product_process_unit_and_drops_people_and_pay(self):
        rows = p.project_report_output(PAYLOAD)
        self.assertEqual(len(rows), 2)
        first = next(r for r in rows if r["processName"] == "腌制")
        self.assertEqual(first, {"reportDate": "2026-11-14", "productCode": "CP1", "productName": "牛肉",
                                 "processName": "腌制", "unit": "KG", "goodQty": "15.000", "defectQty": "0.500",
                                 "reportCount": 2})
        dumped = json.dumps(rows, ensure_ascii=False)
        for leaked in ("W-1", "W-2", "张三", "88", "Salary", "Worker", "effectiveTime"):
            self.assertNotIn(leaked, dumped)
        for row in rows:
            self.assertTrue(set(row) <= set(p.ALLOWED_FIELDS))

    def test_reads_latest_success_cycle_read_only_and_builds_utc_request(self):
        with tempfile.TemporaryDirectory() as d:
            db = Path(d) / "monitor.sqlite3"
            conn = sqlite3.connect(db)
            conn.execute("CREATE TABLE content (hash TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            conn.execute("CREATE TABLE cycle (id TEXT PRIMARY KEY, started TEXT, finished TEXT, status TEXT, hash TEXT)")
            conn.execute("INSERT INTO content VALUES ('h1', ?)", (json.dumps(PAYLOAD),))
            conn.execute("INSERT INTO cycle VALUES ('old', 'x', '2026-11-13T00:01:00+00:00', 'success', 'h1')")
            conn.execute("INSERT INTO cycle VALUES ('new', 'x', '2026-11-15T00:01:11+00:00', 'success', 'h1')")
            conn.execute("INSERT INTO cycle VALUES ('bad', 'x', '2026-11-16T00:01:00+00:00', 'failed', NULL)")
            conn.commit()
            conn.close()
            cycle, payload = p.latest_success_cycle(db)
            self.assertEqual(cycle["id"], "new")
            body = p.build_request({"source_factory_id": 1662664}, cycle, payload)
            self.assertEqual(body["collectedAt"], "2026-11-15T00:01:11+00:00")
            self.assertEqual(body["sourceOrgId"], "1662664")
            self.assertEqual(body["system"], "blacklake")
            self.assertEqual(list(body["datasets"]), ["report_output"])

    def test_unreadable_quantity_is_not_counted_as_zero(self):
        for bad in ("12kg", "", None, "NaN", True):
            payload = json.loads(json.dumps(PAYLOAD))
            payload["data"]["output"][1]["numberofgoodProducts"] = bad
            with self.assertRaises(p.UnreadableQuantity) as ctx:
                p.project_report_output(payload)
            self.assertIn("numberofgoodProducts 1 行", str(ctx.exception), repr(bad))
        payload = json.loads(json.dumps(PAYLOAD))
        del payload["data"]["output"][0]["numberofDefectives"]
        with self.assertRaises(p.UnreadableQuantity) as ctx:
            p.project_report_output(payload)
        self.assertIn("numberofDefectives 1 行", str(ctx.exception))

    def test_unreadable_quantity_exits_unmeasured_rc2(self):
        with tempfile.TemporaryDirectory() as d:
            payload = json.loads(json.dumps(PAYLOAD))
            payload["data"]["output"][0]["numberofgoodProducts"] = "坏数"
            db = Path(d) / "monitor.sqlite3"
            conn = sqlite3.connect(db)
            conn.execute("CREATE TABLE content (hash TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            conn.execute("CREATE TABLE cycle (id TEXT PRIMARY KEY, started TEXT, finished TEXT, status TEXT, hash TEXT)")
            conn.execute("INSERT INTO content VALUES ('h1', ?)", (json.dumps(payload),))
            conn.execute("INSERT INTO cycle VALUES ('c1', 'x', '2026-11-15T00:01:11+00:00', 'success', 'h1')")
            conn.commit()
            conn.close()
            cfg = Path(d) / "config.json"
            cfg.write_text(json.dumps({"db": str(db), "source_factory_id": 1}), encoding="utf-8")
            self.assertEqual(p.main(["push", "--config", str(cfg), "--dry-run"]), 2)

    def test_body_over_the_server_byte_limit_is_refused_locally(self):
        output = [{"endtimeofReport": f"2026-11-14 10:00:{i % 60:02d}", "productCode": f"CP{i}",
                   "productName": "很长的产品名" * 8, "processId": 1, "stockUnit": "KG",
                   "numberofgoodProducts": "1", "numberofDefectives": "0"} for i in range(4000)]
        payload = {"data": {"process": [{"id": 1, "name": "腌制"}], "output": output}}
        rows = p.project_report_output(payload)
        self.assertLessEqual(len(rows), p.MAX_ROWS)
        with self.assertRaises(SystemExit) as ctx:
            p.build_request({"source_factory_id": 1}, {"id": "c", "finished": "2026-11-15T00:01:11+00:00"}, payload)
        self.assertIn("字节", str(ctx.exception.code))

    def test_refuses_plain_http_to_remote_hosts(self):
        with self.assertRaises(SystemExit):
            p.post("http://example.invalid", "cxs_x", {})


if __name__ == "__main__":
    unittest.main()
