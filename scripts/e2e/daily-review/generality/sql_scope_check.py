"""Read-only PostgreSQL controls for the production SQL predicate; CTE fixtures write no tables."""
from pathlib import Path
import hashlib
import json
import re
import sys
import psycopg

assert sys.argv[1:] == ["NON_PRODUCTION_ONLY"]
root = Path(__file__).resolve().parents[4]
source = root / "backend/java/cretas-api/src/main/java/com/cretas/aims/service/production/FormalFinishedOutputScope.java"
raw = source.read_text(encoding="utf-8")
predicate = re.search(r'SQL_PREDICATE = """(.*?)""";', raw, re.S).group(1)
cte = """
WITH production_batches(batch_number,factory_id,production_plan_id,product_type_id) AS
    (VALUES ('A','F','P','SKU'),('OWNER','F','P','RAW')),
production_settlements(id,factory_id,production_plan_id,deleted_at) AS
    (VALUES ('S','F','P',NULL::timestamp)),
production_settlement_output_lines(settlement_id,factory_id,production_plan_id,
    deleted_at,reported_batch_number,product_type_id) AS
    (VALUES ('S',%s,%s,NULL::timestamp,'A',%s))
SELECT b.batch_number FROM production_batches b WHERE b.factory_id='F'
"""
out = Path.home() / "Documents/cretas-connect-20261015/factory-data-p37"
with psycopg.connect(host="127.0.0.1", port=55447, dbname="p37_factory_generality_20261018",
                    user="assembly_executor", options="-c default_transaction_read_only=on") as db:
    assert db.execute("select current_database(),current_setting('transaction_read_only')").fetchone() == (
        "p37_factory_generality_20261018", "on")
    def query(sql, factory="F", plan="P", sku="SKU"):
        return sorted(row[0] for row in db.execute(cte + sql, (factory,plan,sku)))
    assert query(predicate) == ["A"]
    assert query(predicate, plan="OTHER") == ["A","OWNER"]
    assert query(predicate, factory="OTHER") == ["A","OWNER"]
    assert query(predicate, sku="OTHER") == []
    mutant = predicate.replace("AND fo.production_plan_id=fs.production_plan_id", "")
    assert mutant != predicate and query(mutant,plan="OTHER") == ["A"]
    assert query(predicate, plan="OTHER") == ["A","OWNER"]
receipt = {"status":"PASS", "sourceSha256":hashlib.sha256(source.read_bytes()).hexdigest(),
           "controls":["matching output", "wrong plan ignored", "foreign factory ignored",
                       "wrong SKU rejected", "missing plan guard mutant red", "original guard restored green"],
           "method":"read-only PostgreSQL CTE fixtures; production predicate unchanged", "tableWrites":0}
(out / "output-scope-sql-controls.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8")
print(json.dumps(receipt))
