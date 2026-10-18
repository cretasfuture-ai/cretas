"""Consolidate completed P37 continuation evidence; no login or business I/O."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

assert sys.argv[1:] == ["NON_PRODUCTION_ONLY"]
ROOT = Path(__file__).resolve().parents[4]
RUNTIME = Path.home() / "Documents/cretas-connect-20261015/factory-data-p37"
OUT = RUNTIME / "joint-upgrade-20261019"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


freeze = read(RUNTIME / "product-final-freeze.json")
for name, digest in freeze.items():
    assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
tests = ["FactoryActualProductionMetricsContractTest", "MultiOutputActualMetricsServiceContractTest",
         "FactoryIndicatorApplicabilityContractTest", "WorkflowBomSynchronizationServiceTest",
         "ProductProcessWorkflowServiceImplTest", "BomWorkflowRevisionServiceTest", "ProductionSummaryServiceTest",
         "ProcessSheetYieldCardTest", "FormalFinishedOutputScopeContractTest", "FactoryReadinessContractTest",
         "BomRecipeDraftLifecycleJpaTest", "IndicatorCacheEvidenceContractTest", "BomRecipeFamilyCostAllocationTest",
         "ProductionPlanServiceImplActualCostsContractTest", "ProductionCostConsistencyContractTest", "ProductionPlanSettlementTest"]
reports = []
for name in tests:
    files = list((RUNTIME / "build/surefire-reports").glob("TEST-*." + name + ".xml"))
    assert len(files) == 1, name
    root = ET.parse(files[0]).getroot()
    counts = {key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    assert counts["tests"] > 0 and counts["failures"] == counts["errors"] == counts["skipped"] == 0, (name, counts)
    reports.append({"class": name, **counts, "path": str(files[0]), "sha256": hashlib.sha256(files[0].read_bytes()).hexdigest()})
web_tests = (OUT / "web-tests.log").read_text(encoding="utf-8", errors="replace")
assert re.search(r"Tests\s+64 passed", web_tests)
assert re.search(r"Tests\s+14 passed", (OUT / "web-cost-confirm-tests.log").read_text(encoding="utf-8", errors="replace"))
assert "built in" in (OUT / "web-build.log").read_text(encoding="utf-8", errors="replace")
assert "built in" in (OUT / "web-final-build.log").read_text(encoding="utf-8", errors="replace")
browser = read(OUT / "browser-acceptance.json")
assert browser["status"] == "PASS" and not any(browser[key] for key in ("errors", "failures", "blockedBusinessWrites"))
for screenshot in browser["screenshots"]:
    assert Path(screenshot).is_file() and Path(screenshot).stat().st_size > 1000
metrics = read(OUT / "metrics-acceptance.json")
assert set(metrics) == {"2026-10-18", "2026-10-19"}
history = read(OUT / "historical-proof.json")
assert history["historicalTransactionsAndStartedPlansUnchanged"]
business = read(OUT / "formal-upgrade-business.json")
assert business["old"]["pin"]["selected_workflow_version"] == 1
assert business["new"]["pin"]["selected_workflow_version"] == 2
assert read(OUT / "formal-content-proof.json")["oldWorkflowContentUnchanged"]
assert read(OUT / "tenant-negative.json")["status"] == 403
receipt = {"status": "PASS", "base": "929f314dc8b7cc311c2303429b23b2fe06192d16",
           "branch": subprocess.check_output(["git", "branch", "--show-current"], cwd=ROOT, text=True).strip(),
           "database": "p37_factory_generality_20261018", "factory": "FOOD_3101_001",
           "sourceVersion": "actual-flow-v3", "frozenFiles": freeze,
           "java": {"classes": len(reports), "tests": sum(r["tests"] for r in reports), "reports": reports},
           "web": {"tests": 78, "typeCheckAndBuild": True, "browser": browser},
           "history": history, "metricHistory": read(OUT / "metric-history-proof.json"),
           "oldPlan": business["old"]["planId"], "newPlan": business["new"]["planId"],
           "boundaries": {"localOnly": True, "businessSqlWrites": 0, "productionWrites": 0,
                          "pushed": False, "merged": False, "deployed": False, "userSignoff": "PENDING"}}
(OUT / "local-continuation-receipt.json").write_bytes(json.dumps(receipt, ensure_ascii=False, indent=2).encode("utf-8"))
print(json.dumps({"status": receipt["status"], "javaTests": receipt["java"]["tests"],
                  "webTests": 78, "evidence": str(OUT)}, ensure_ascii=False))
