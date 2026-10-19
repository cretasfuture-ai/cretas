"""Collect bounded local evidence; never logs in or modifies business data."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

assert sys.argv[1:] == ["NON_PRODUCTION_ONLY"]
root = Path(__file__).resolve().parents[4]
out = Path.home() / "Documents/cretas-connect-20261015/factory-data-p37"
read = lambda name: json.loads((out / name).read_text(encoding="utf-8"))
freeze = read("product-final-freeze.json")
for name, digest in freeze.items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
tests = read("java-selected-results.json")
assert tests and all(int(t["failures"]) == int(t["errors"]) == 0 for t in tests)
results = read("business-state.json")["generality"]["results"]
required = {"units", "units-negative", "multi", "multi-idempotence", "fresh-multi",
            "fresh-multi-idempotence", "upgrade", "output-visibility", "readback"}
assert required <= results.keys(), required - results.keys()
for key in ("multi-idempotence", "fresh-multi-idempotence"):
    assert results[key]["unchanged"] is True
assert results["upgrade"]["published"] is False and results["upgrade"]["activated"] is False
readiness = read("readiness-acceptance.json")
sql_controls = read("output-scope-sql-controls.json")
assert sql_controls["status"] == "PASS" and sql_controls["tableWrites"] == 0
assert sql_controls["sourceSha256"] == freeze["backend/java/cretas-api/src/main/java/com/cretas/aims/service/production/FormalFinishedOutputScope.java"]
assert readiness["businessHistoryUnchanged"] and not readiness["productionTouched"]
assert readiness["businessSignature"] == "UNVERIFIED"
ui = [read(mode + "-ui.json") for mode in ("platform", "daily")]
assert all(not item["errors"] and not item["failures"] and not item["blockedBusinessWrites"] for item in ui)
receipt = {
    "status": "LOCAL_BOUNDED_ACCEPTANCE_PASS", "productionTouched": False,
    "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "branch": subprocess.check_output(["git", "branch", "--show-current"], cwd=root, text=True).strip(),
    "runtime": read("runtime.json"), "frozenProductFiles": freeze,
    "java": {"selectedClasses": len(tests), "tests": sum(int(t["tests"]) for t in tests),
             "failures": 0, "errors": 0, "evidence": "java-selected-results.json",
             "mode": "targeted slices; unchanged passing evidence reused"},
    "businessStages": sorted(required), "readiness": readiness, "ui": ui, "sqlControls":sql_controls,
    "multiOutput": [{"planId": results[key]["plan"]["id"],
                     "totalCost": results[key]["allocatedCostSum"],
                     "sourceCostBySku": results[key]["sourceCostBySku"],
                     "outputs": results[key]["outputLines"]} for key in ("multi", "fresh-multi")],
    "limits": ["No push, merge, deployment or production business writes",
               "Configuration acceptance is simulated; business signoff remains UNVERIFIED",
               "Old-factory upgrade accepted as a draft only; published version and history preserved",
               "Four factory actual-flow metrics still require multi-output aggregation adaptation",
               "Full Web run retained three timeouts; isolated affected file passed",
               "Local optional-service health is incomplete; not production readiness"],
}
(out / "local-acceptance-receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"status": receipt["status"], "java": receipt["java"], "businessStages": receipt["businessStages"]}))
