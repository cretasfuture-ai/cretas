"""Factory-domain AgentOps: gate exit codes, domain separation, traces, fixture wiring."""

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from smartbi.agent.eval import (
    AgentOpsContext,
    AgentOpsService,
    InMemoryAgentOpsStore,
)
from smartbi.agent.eval.validation import AgentOpsValidationError
from smartbi.agent.eval.store import AgentOpsAccessError
from smartbi.api.agent_ops import get_agent_ops_service, router

from .helpers import case, config_snapshot, request_id

REPO = Path(__file__).resolve().parents[4]
GATE = REPO / "scripts" / "ci" / "factory_agentops_gate.py"
FIXTURES = REPO / "backend" / "python" / "tests" / "fixtures" / "factory_agentops"
JAVA_SRC = REPO / "backend" / "java" / "cretas-api" / "src" / "main"


def factory_ctx(factory="F006", role="factory_super_admin"):
    return AgentOpsContext(factory, "42", role, "corr-f", "FACTORY")


def factory_case(case_id="fc-1", route="MATERIAL_BATCH_QUERY"):
    return {
        "caseId": case_id, "expectedRoute": route,
        "requiredTools": ["material_batch_query"], "numericTruthRefs": {},
        "maxRounds": 2, "maxToolCalls": 3,
    }


def factory_actual(route="MATERIAL_BATCH_QUERY", tools=("material_batch_query",)):
    return {
        "routeCode": route, "tools": list(tools), "numericTruthRefs": {},
        "roundsUsed": 1, "toolCallsUsed": len(tools),
    }


# ---------------------------------------------------------------- the CI gate

def run_gate(tmp_path, mutate=None, drop=None, extra=()):
    work = tmp_path / "fx"
    shutil.copytree(FIXTURES, work)
    if mutate:
        mutate(work)
    if drop:
        (work / drop).unlink()
    proc = subprocess.run(
        [sys.executable, "-I", str(GATE),
         "--eval-set", str(work / "eval_set.json"),
         "--actuals", str(work / "recorded_actuals.json"),
         "--baseline", str(work / "baseline.json"), *extra],
        capture_output=True, text=True, timeout=120,
    )
    return proc, work


def test_gate_is_green_on_the_committed_fixtures(tmp_path):
    proc, _ = run_gate(tmp_path)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert proc.stdout.count("FACTORY_AGENTOPS_GATE rc=0") == 1


def test_gate_fails_rc1_on_injected_route_regression(tmp_path):
    def mutate(work):
        path = work / "recorded_actuals.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["fx-003"]["routeCode"] = "GENERAL_CHAT"  # a previously passing case misroutes
        path.write_text(json.dumps(data), encoding="utf-8")

    proc, _ = run_gate(tmp_path, mutate)
    assert proc.returncode == 1, proc.stdout + proc.stderr
    assert "fx-003" in proc.stdout and "REGRESSION" in proc.stdout


def test_gate_fails_rc1_on_dropped_tool_and_wrong_number(tmp_path):
    def mutate(work):
        path = work / "recorded_actuals.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["fx-004"]["tools"] = ["material_batch_query"]  # fifo step vanished
        data["fx-005"]["numericTruthRefs"]["wip:qty"] = "1"
        path.write_text(json.dumps(data), encoding="utf-8")

    proc, _ = run_gate(tmp_path, mutate)
    assert proc.returncode == 1
    assert "fx-004" in proc.stdout and "fx-005" in proc.stdout


@pytest.mark.parametrize("drop", ["eval_set.json", "recorded_actuals.json", "baseline.json"])
def test_gate_is_rc2_when_a_fixture_is_missing(tmp_path, drop):
    proc, _ = run_gate(tmp_path, drop=drop)
    assert proc.returncode == 2, proc.stdout + proc.stderr
    assert "UNMEASURED" in proc.stdout


def test_gate_is_rc2_on_corrupt_fixture_and_zero_cases(tmp_path):
    def corrupt(work):
        (work / "recorded_actuals.json").write_text("{not json", encoding="utf-8")

    proc, _ = run_gate(tmp_path, corrupt)
    assert proc.returncode == 2

    def empty(work):
        path = work / "eval_set.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["cases"] = []
        path.write_text(json.dumps(data), encoding="utf-8")

    proc, _ = run_gate(tmp_path / "b", empty)
    assert proc.returncode == 2


def test_shrinking_the_eval_set_to_hide_failures_is_not_green(tmp_path):
    def shrink(work):
        path = work / "eval_set.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["cases"] = [c for c in data["cases"] if c["caseId"] != "fx-003"]
        path.write_text(json.dumps(data), encoding="utf-8")
        acts = work / "recorded_actuals.json"
        actuals = json.loads(acts.read_text(encoding="utf-8"))
        del actuals["fx-003"]
        acts.write_text(json.dumps(actuals), encoding="utf-8")

    proc, _ = run_gate(tmp_path, shrink)
    assert proc.returncode == 2
    assert "baseline" in proc.stdout


def test_baseline_cannot_be_lowered_by_update(tmp_path):
    def mutate(work):
        path = work / "recorded_actuals.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["fx-003"]["routeCode"] = "GENERAL_CHAT"
        path.write_text(json.dumps(data), encoding="utf-8")

    proc, work = run_gate(tmp_path, mutate, extra=("--update-baseline",))
    assert proc.returncode == 2
    assert "refusing to lower" in proc.stdout


def test_gate_fixture_names_exist_in_the_java_sources():
    """Fixture wiring: a recorded tool/intent that no longer exists is a stale fixture."""

    cases = json.loads((FIXTURES / "eval_set.json").read_text(encoding="utf-8"))["cases"]
    java = "\n".join(p.read_text(encoding="utf-8", errors="ignore")
                     for p in (JAVA_SRC / "java").rglob("*Tool.java"))
    sql = "\n".join(p.read_text(encoding="utf-8", errors="ignore")
                    for p in (JAVA_SRC / "resources").rglob("*.sql"))
    for item in cases:
        assert re.search(rf"return \"{item['requiredTools'][-1]}\"", java), item["requiredTools"]
        assert f"'{item['expectedRoute']}'" in sql, item["expectedRoute"]


# ------------------------------------------------- domain separation + service

@pytest.mark.asyncio
async def test_factory_context_accepts_factory_routes_and_rejects_the_restaurant_route():
    service = AgentOpsService(InMemoryAgentOpsStore())
    ctx = factory_ctx()
    created = await service.create_eval_set(
        ctx, request_id=request_id(1), name="fac", version=1, description="",
        cases=[factory_case()])
    assert created.name == "fac"
    with pytest.raises(AgentOpsValidationError, match="UNSUPPORTED_EXPECTED_ROUTE"):
        await service.create_eval_set(
            ctx, request_id=request_id(2), name="fac2", version=1, description="",
            cases=[case()])  # restaurant gross-margin case


@pytest.mark.asyncio
async def test_restaurant_context_cannot_create_factory_routes():
    service = AgentOpsService(InMemoryAgentOpsStore())
    ctx = AgentOpsContext("R001", "42", "platform_admin", "corr")
    with pytest.raises(AgentOpsValidationError, match="UNSUPPORTED_EXPECTED_ROUTE"):
        await service.create_eval_set(
            ctx, request_id=request_id(1), name="x", version=1, description="",
            cases=[factory_case()])


@pytest.mark.asyncio
async def test_factory_experiment_uses_the_factory_evaluator_and_detects_failure():
    service = AgentOpsService(InMemoryAgentOpsStore())
    ctx = factory_ctx()
    record = await service.create_eval_set(
        ctx, request_id=request_id(1), name="fac", version=1, description="",
        cases=[factory_case("a"), factory_case("b")])
    from smartbi.agent.eval import RunnerBounds
    experiment = await service.run_experiment(
        ctx, request_id=request_id(2), eval_set_id=record.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"a": factory_actual(), "b": factory_actual(route="OTHER_INTENT")},
        bounds=RunnerBounds(),
    )
    assert experiment.evaluator_version == "factory-offline-v1"
    assert experiment.aggregate["passedCount"] == 1
    failed = [r for r in experiment.case_results if not r["passed"]]
    assert failed[0]["caseId"] == "b" and "ROUTE_MISMATCH" in failed[0]["failureCodes"]


@pytest.mark.asyncio
async def test_runtime_shadow_stays_restaurant_only():
    service = AgentOpsService(InMemoryAgentOpsStore())
    with pytest.raises(AgentOpsValidationError, match="RUNTIME_SHADOW_RESTAURANT_ONLY"):
        await service.import_runtime_corpus(
            factory_ctx(), request_id=request_id(1), name="x", version=1,
            description="", max_cases=1)


# --------------------------------------------------------------------- traces

def trace_payload(run="00000000-0000-4000-8000-0000000000aa"):
    return {
        "runId": run, "intentCode": "MATERIAL_BATCH_QUERY", "outcome": "SUCCESS",
        "latencyMs": 420,
        "steps": [
            {"toolName": "material_batch_query", "latencyMs": 200, "outcome": "SUCCESS",
             "sourceRef": "material_batch"},
            {"toolName": "material_fifo_recommend", "latencyMs": 180, "outcome": "SUCCESS",
             "sourceRef": "material_batch"},
        ],
    }


@pytest.mark.asyncio
async def test_factory_trace_round_trip_is_tenant_scoped_and_paged():
    service = AgentOpsService(InMemoryAgentOpsStore())
    ctx = factory_ctx()
    run = "00000000-0000-4000-8000-0000000000aa"
    await service.record_factory_trace(ctx, trace_payload(run))
    await service.record_factory_trace(ctx, trace_payload(run))  # idempotent replay
    page = await service.trace(ctx, run, after_sequence=0, limit=1)
    assert page["routeCode"] == "MATERIAL_BATCH_QUERY" and page["latencyMs"] == 420
    assert [e["toolName"] for e in page["events"]] == ["material_batch_query"]
    assert page["page"]["hasMore"] is True and page["page"]["nextAfterSequence"] == 1
    rest = await service.trace(ctx, run, after_sequence=1, limit=10)
    assert [e["toolName"] for e in rest["events"]] == ["material_fifo_recommend"]
    with pytest.raises(AgentOpsAccessError):
        await service.trace(factory_ctx("F999"), run, after_sequence=0, limit=10)


@pytest.mark.asyncio
async def test_factory_trace_rejects_silent_success_and_changed_replay():
    service = AgentOpsService(InMemoryAgentOpsStore())
    ctx = factory_ctx()
    bad = trace_payload()
    bad["outcome"] = "OK"
    with pytest.raises(AgentOpsValidationError, match="INVALID_TRACE_OUTCOME"):
        await service.record_factory_trace(ctx, bad)
    await service.record_factory_trace(ctx, trace_payload())
    changed = trace_payload()
    changed["latencyMs"] = 1
    from smartbi.agent.eval.store import AgentOpsConflictError
    with pytest.raises(AgentOpsConflictError):
        await service.record_factory_trace(ctx, changed)


# ------------------------------------------------------------------- HTTP gate

def app_for(business_type):
    app = FastAPI()
    store = InMemoryAgentOpsStore()

    @app.middleware("http")
    async def trusted(request: Request, call_next):
        request.state.auth_method = "internal"
        request.state.factory_id = "F006"
        request.state.user_id = "42"
        request.state.role = "factory_super_admin"
        request.state.business_type = business_type
        return await call_next(request)

    app.include_router(router)
    app.dependency_overrides[get_agent_ops_service] = lambda: AgentOpsService(store)
    return app


def test_http_accepts_factory_and_still_rejects_other_domains():
    ok = TestClient(app_for("FACTORY"))
    body = {"schemaVersion": "1.0", "requestId": request_id(7), "name": "api fac",
            "version": 1, "description": "", "cases": [factory_case()]}
    assert ok.post("/api/internal/smartbi/agent/runs/ops/eval-sets", json=body).status_code == 201
    posted = ok.post("/api/internal/smartbi/agent/runs/ops/traces/factory", json=trace_payload())
    assert posted.status_code == 201
    got = ok.get("/api/internal/smartbi/agent/runs/ops/traces/" + trace_payload()["runId"])
    assert got.status_code == 200 and got.json()["domain"] == "FACTORY"
    shadow = ok.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets/import-runtime-corpus",
        json={"schemaVersion": "1.0", "requestId": request_id(8), "name": "s", "version": 1})
    assert shadow.status_code == 403
    other = TestClient(app_for("MANUFACTURING"))
    assert other.get("/api/internal/smartbi/agent/runs/ops/eval-sets").status_code == 403
    rest = TestClient(app_for("RESTAURANT"))
    assert rest.post("/api/internal/smartbi/agent/runs/ops/traces/factory",
                     json=trace_payload()).status_code == 403


# ------------------------------------------------------------ migration shape

def test_factory_trace_migration_is_tenant_isolated_and_append_only():
    sql = (REPO / "backend/python/smartbi/database/migrations"
           / "V20261103_111__factory_agent_trace.sql").read_text(encoding="utf-8")
    assert "ENABLE ROW LEVEL SECURITY" in sql and "FORCE ROW LEVEL SECURITY" in sql
    assert sql.count("current_setting('app.factory_id', true)") >= 2
    assert "smart_bi_agentops_append_only" in sql
    assert "GRANT SELECT, INSERT ON smart_bi_factory_agent_trace" in sql
    assert "UPDATE" not in sql.split("GRANT", 1)[1].split("\n", 1)[0]
