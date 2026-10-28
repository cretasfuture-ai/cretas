#!/usr/bin/env python3
"""Offline factory-domain AgentOps regression gate.

Replays a versioned factory eval set (expected intent/tool per case) against
RECORDED run snapshots through the real AgentOps evaluator and compares the
result with a stored baseline.  No LLM, network or database is touched.

Exit codes (open set on purpose: anything that is not 0 is a failed gate):
  0  measured, nothing regressed against the baseline
  1  measured, regressed (pass rate, a previously passing case, or a metric)
  2  could NOT measure (missing/corrupt fixture, zero cases, case-set mismatch,
     stale baseline, evaluator unreachable, or the evaluator failed its own
     negative controls).  Never reported as green.

``--update-baseline`` rewrites the baseline from the current readings but
refuses to lower the pass rate, so a regression cannot be "baselined away".
"""

from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import json
import sys
import types
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
PY_ROOT = REPO / "backend" / "python"
FIXTURES = PY_ROOT / "tests" / "fixtures" / "factory_agentops"

RC_OK, RC_REGRESSION, RC_UNMEASURED = 0, 1, 2


class Unmeasured(Exception):
    """The gate could not take a valid reading."""


def _load_evaluator():
    """Import only the evaluator leaves; the heavy smartbi package __init__ chain
    (pandas, LLM clients, ...) is irrelevant to an offline replay."""

    for name, rel in (
        ("smartbi", "smartbi"),
        ("smartbi.agent", "smartbi/agent"),
        ("smartbi.agent.eval", "smartbi/agent/eval"),
        ("smartbi.agent.runtime", "smartbi/agent/runtime"),
    ):
        module = types.ModuleType(name)
        module.__path__ = [str(PY_ROOT / rel)]
        sys.modules[name] = module
    sys.path.insert(0, str(PY_ROOT))
    from smartbi.agent.eval import runner, validation  # type: ignore
    from smartbi.agent.eval.contracts import EvalSetRecord  # type: ignore

    return runner, validation, EvalSetRecord


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise Unmeasured(f"cannot read {path}: {exc}") from exc


def _digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _mutants(cases, actuals):
    """Known-bad variants of recorded runs.  The evaluator MUST fail every one."""

    for case in cases:
        cid = case["caseId"]
        base = actuals[cid]
        wrong_route = copy.deepcopy(base)
        wrong_route["routeCode"] = "NOT_A_ROUTE"
        yield cid, "wrong-route", wrong_route
        if case["requiredTools"]:
            no_tool = copy.deepcopy(base)
            no_tool["tools"] = []
            no_tool["toolCallsUsed"] = 0
            yield cid, "missing-tool", no_tool
        if case["numericTruthRefs"]:
            wrong_num = copy.deepcopy(base)
            key = sorted(case["numericTruthRefs"])[0]
            wrong_num["numericTruthRefs"] = dict(wrong_num["numericTruthRefs"])
            wrong_num["numericTruthRefs"][key] = "999999999"
            yield cid, "wrong-number", wrong_num
        over = copy.deepcopy(base)
        over["roundsUsed"] = 2
        over["toolCallsUsed"] = 10
        if case["maxToolCalls"] < 10:
            yield cid, "over-budget", over


async def _measure(eval_set_path: Path, actuals_path: Path):
    runner, validation, EvalSetRecord = _load_evaluator()
    raw = _read_json(eval_set_path)
    actuals_raw = _read_json(actuals_path)
    if not isinstance(raw, dict) or raw.get("domain") != "FACTORY":
        raise Unmeasured("eval set must declare domain FACTORY")
    try:
        cases = validation.validate_cases(raw.get("cases") or [], "FACTORY")
        actuals = validation.validate_actual_snapshots(actuals_raw)
    except validation.AgentOpsValidationError as exc:
        raise Unmeasured(f"fixture rejected by validation: {exc}") from exc
    if not cases:
        raise Unmeasured("zero cases")
    if set(actuals) != {case["caseId"] for case in cases}:
        raise Unmeasured("recorded actuals do not match the eval-set case ids")

    record = EvalSetRecord(
        "00000000-0000-4000-8000-000000000000", "FIXTURE", str(raw.get("name", "fx")),
        int(raw.get("version", 1)), "", tuple(cases), _digest(cases),
        "00000000-0000-4000-8000-000000000001", "0" * 64, "gate", "1970-01-01T00:00:00Z",
    )
    bounds = runner.RunnerBounds(max_cases=max(len(cases), 1), max_concurrency=2)
    gate_runner = runner.FactoryOfflineBatchRunner()
    aggregate, results = await gate_runner.run(record, actuals, bounds=bounds)

    # Negative controls: an evaluator that cannot fail proves nothing.
    tried = survived = 0
    survivors = []
    for cid, kind, mutant in _mutants(cases, actuals):
        tried += 1
        probe = dict(actuals)
        probe[cid] = mutant
        _, probe_results = await gate_runner.run(record, probe, bounds=bounds)
        row = next(item for item in probe_results if item["caseId"] == cid)
        if row["passed"]:
            survived += 1
            survivors.append(f"{cid}:{kind}")
    if tried == 0:
        raise Unmeasured("no negative controls could be built")
    if survived:
        raise Unmeasured(
            f"evaluator failed {survived}/{tried} negative controls (cannot detect failures): "
            + ", ".join(survivors[:10])
        )
    return {
        "aggregate": aggregate,
        "results": list(results),
        "evalSetDigest": _digest(cases),
        "controls": tried,
    }


def _compare(reading, baseline) -> list[str]:
    problems: list[str] = []
    agg = reading["aggregate"]
    if float(agg["passRate"]) + 1e-9 < float(baseline["passRate"]):
        problems.append(f"passRate {agg['passRate']} < baseline {baseline['passRate']}")
    now_pass = {r["caseId"] for r in reading["results"] if r["passed"]}
    lost = sorted(set(baseline["passingCaseIds"]) - now_pass)
    if lost:
        problems.append("previously passing cases now fail: " + ", ".join(lost))
    for key in ("routePassCount", "trajectoryPassCount", "numericTruthPassCount"):
        if agg[key] < baseline[key]:
            problems.append(f"{key} {agg[key]} < baseline {baseline[key]}")
    return problems


def _validate_baseline(baseline: Any, reading) -> None:
    need = {"caseCount", "passRate", "passingCaseIds", "routePassCount",
            "trajectoryPassCount", "numericTruthPassCount", "evalSetDigest"}
    if not isinstance(baseline, dict) or not need <= set(baseline):
        raise Unmeasured("baseline missing required keys")
    if baseline["caseCount"] != reading["aggregate"]["caseCount"]:
        raise Unmeasured(
            f"case count {reading['aggregate']['caseCount']} != baseline {baseline['caseCount']} "
            "(eval set shrank or grew without a baseline refresh)"
        )
    if baseline["evalSetDigest"] != reading["evalSetDigest"]:
        raise Unmeasured("eval set content changed without a baseline refresh")


def _baseline_from(reading) -> dict[str, Any]:
    agg = reading["aggregate"]
    return {
        "caseCount": agg["caseCount"],
        "passRate": agg["passRate"],
        "passingCaseIds": sorted(r["caseId"] for r in reading["results"] if r["passed"]),
        "routePassCount": agg["routePassCount"],
        "trajectoryPassCount": agg["trajectoryPassCount"],
        "numericTruthPassCount": agg["numericTruthPassCount"],
        "evalSetDigest": reading["evalSetDigest"],
    }


def run(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--eval-set", type=Path, default=FIXTURES / "eval_set.json")
    parser.add_argument("--actuals", type=Path, default=FIXTURES / "recorded_actuals.json")
    parser.add_argument("--baseline", type=Path, default=FIXTURES / "baseline.json")
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args(argv)

    reading = asyncio.run(_measure(args.eval_set, args.actuals))
    agg = reading["aggregate"]
    print(f"cases={agg['caseCount']} passed={agg['passedCount']} passRate={agg['passRate']} "
          f"controls_failed_as_expected={reading['controls']}")
    for item in reading["results"]:
        if not item["passed"]:
            print(f"  FAIL {item['caseId']}: {','.join(item['failureCodes'])}")

    baseline = _read_json(args.baseline)
    if args.update_baseline:
        if float(agg["passRate"]) + 1e-9 < float(baseline.get("passRate", 0)):
            raise Unmeasured("refusing to lower the baseline pass rate")
        args.baseline.write_text(
            json.dumps(_baseline_from(reading), indent=2, sort_keys=True) + "\n",
            encoding="utf-8", newline="\n",
        )
        print("baseline updated")
        return RC_OK
    _validate_baseline(baseline, reading)
    problems = _compare(reading, baseline)
    if problems:
        for line in problems:
            print("REGRESSION:", line)
        return RC_REGRESSION
    print("no regression against baseline")
    return RC_OK


def main(argv: list[str] | None = None) -> int:
    try:
        rc = run(sys.argv[1:] if argv is None else argv)
    except Unmeasured as exc:
        print(f"UNMEASURED: {exc}")
        rc = RC_UNMEASURED
    except SystemExit:
        raise
    except Exception as exc:  # open set: anything unexpected is "could not measure"
        print(f"UNMEASURED: {type(exc).__name__}: {exc}")
        rc = RC_UNMEASURED
    print(f"FACTORY_AGENTOPS_GATE rc={rc}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
