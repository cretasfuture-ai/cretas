"""Execute historical factory code and its frozen projection against identical inputs."""
from __future__ import annotations

import ast
import asyncio
from copy import deepcopy
from dataclasses import asdict, is_dataclass
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import time
from types import ModuleType, SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[4]


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


gate = _load("factory_projection_provenance_test", ROOT / "scripts/ci/check_factory_evaluator_provenance.py")
legacy = _load("factory_projection_legacy_test", gate.MODULE)


def _original_node(source, name):
    """Independent extraction of original definitions; not the production generator."""
    text = source.decode("utf-8")
    for node in ast.parse(text).body:
        matches = getattr(node, "name", None) == name
        if isinstance(node, ast.Assign):
            matches = any(isinstance(item, ast.Name) and item.id == name for item in node.targets)
        if matches:
            start = min([node.lineno] + [item.lineno for item in getattr(node, "decorator_list", [])])
            return "\n".join(text.splitlines()[start - 1:node.end_lineno])
    raise AssertionError(f"missing historical node: {name}")


@pytest.fixture(scope="module")
def oracle():
    blobs = gate.load_sources(ROOT)
    module = ModuleType("historical_factory_oracle")
    sys.modules[module.__name__] = module
    parts = ['''from __future__ import annotations
import asyncio, json, re, sys
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from enum import Enum
from typing import Any, Mapping, Sequence
EvalSetRecord = Any
runtime_evaluation = sys.modules[__name__]
EVALUATOR_BUILD = "31999b828125844a2fcbbc75edef466bd24756bd2c85d35258c75d5bebe3ac47"
''']
    parts.append(_original_node(blobs["runtime/run_contracts.py"], "RouteCode"))
    # Original factory execution closure, including original identity declarations.
    selections = {
        "validation.py": ("MAX_CASES", "MAX_SNAPSHOT_BYTES", "MAX_AGENT_OPS_PAYLOAD_BYTES",
                          "MAX_TOOLS", "MAX_NUMERIC_REFS", "_ID", "AgentOpsValidationError",
                          "validate_actual_snapshot", "validate_actual_snapshots", "validate_runner_bounds_snapshot",
                          "_tools", "_numeric_refs", "_identifier", "_bounded_int", "_bounded_json"),
        "runtime/evaluation.py": ("OfflineEvaluation", "OfflineCaseExpectation", "OfflineCaseSnapshot",
                                  "evaluate_offline_case", "_numeric_refs_match", "_is_subsequence"),
        "runner.py": ("EVALUATOR_VERSION", "FACTORY_EVALUATOR_VERSION", "RunnerBounds", "OfflineBatchRunner",
                      "FactoryOfflineBatchRunner", "aggregate_case_results"),
    }
    for source, names in selections.items():
        parts.extend(_original_node(blobs[source], name) for name in names)
    exec(compile("\n\n".join(parts), "<trusted-historical-factory-oracle>", "exec"), module.__dict__)
    return module


def _plain(value):
    if is_dataclass(value):
        return asdict(value)
    return value


def _capture(function):
    try:
        return "ok", _plain(function())
    except Exception as error:
        return "error", type(error).__name__, str(error)


def _case(case_id="factory-1"):
    return {"caseId": case_id, "expectedRoute": "FACTORY_COST_ANALYSIS", "requiredTools": ["factory_query", "cost_calculate"],
            "numericTruthRefs": {"total": "12.00", "yield": "0.5"}, "maxRounds": 2, "maxToolCalls": 4}


def _actual(**changes):
    result = {"routeCode": "FACTORY_COST_ANALYSIS", "tools": ["factory_query", "cost_calculate"],
              "numericTruthRefs": {"total": "12", "yield": "0.50"}, "roundsUsed": 1, "toolCallsUsed": 2}
    result.update(changes)
    return result


def test_exact_projection_and_both_byte_integrities():
    result = gate.check(ROOT)
    assert result["historicalSourceBuild"] != result["implementationSha256"]
    assert legacy.verify_local_artifact(result["manifestSha256"])
    assert not hasattr(legacy.FactoryOfflineBatchRunner(), "evaluator_build")


@pytest.mark.parametrize("changes", [
    {}, {"tools": ()}, {"tools": ("factory_query", "cost_calculate")},
    {"tools": ["factory_query"] * 11}, {"tools": ["bad tool"]},
    {"tools": ["cost_calculate", "factory_query"]}, {"tools": ["factory_query", "extra", "cost_calculate"], "toolCallsUsed": 3},
    {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"}, {"routeCode": "WRONG_ROUTE"},
    {"routeCode": "bad route"}, {"routeCode": None}, {"routeCode": "中"},
    {"roundsUsed": 0}, {"roundsUsed": 3}, {"roundsUsed": True},
    {"toolCallsUsed": 1}, {"toolCallsUsed": 11}, {"toolCallsUsed": False},
    {"numericTruthRefs": {"total": "NaN"}}, {"numericTruthRefs": {"total": "Infinity"}},
    {"numericTruthRefs": {"total": "-Infinity"}}, {"numericTruthRefs": {"total": 12}},
    {"numericTruthRefs": {"total": "-0.000"}}, {"numericTruthRefs": {"total": "1e-4"}},
    {"numericTruthRefs": {"bad ref": "1"}}, {"numericTruthRefs": {}}, {"unexpected": True},
])
def test_normalization_and_case_validation_are_identical(oracle, changes):
    actual = _actual(**changes)
    assert _capture(lambda: oracle.validate_actual_snapshot(deepcopy(actual))) == _capture(lambda: legacy.validate_actual_snapshot(deepcopy(actual)))
    assert _capture(lambda: oracle.FactoryOfflineBatchRunner._evaluate(_case(), deepcopy(actual))) == _capture(lambda: legacy.FactoryOfflineBatchRunner._evaluate(_case(), deepcopy(actual)))


@pytest.mark.parametrize("bits", range(8))
def test_all_failure_axis_combinations_and_order_match(oracle, bits):
    def evaluate(module):
        return module.evaluate_offline_case(
            module.OfflineCaseSnapshot("WRONG" if bits & 1 else "FACTORY_COST_ANALYSIS",
                                       () if bits & 2 else ("factory_query", "cost_calculate"),
                                       {"total": "13" if bits & 4 else "12"}, 1, 2),
            module.OfflineCaseExpectation("FACTORY_COST_ANALYSIS", ("factory_query", "cost_calculate"), {"total": "12"}),
        )
    assert _capture(lambda: evaluate(oracle)) == _capture(lambda: evaluate(legacy))


@pytest.mark.parametrize("values", [(1, 1, .05), (100, 4, 5.0), (0, 1, 1), (101, 1, 1),
                                     (1, 0, 1), (1, 5, 1), (1, 1, .049), (1, 1, 5.001), (True, 1, 1)])
def test_constructor_bounds_and_exact_error_text_match(oracle, values):
    assert _capture(lambda: oracle.RunnerBounds(*values)) == _capture(lambda: legacy.RunnerBounds(*values))


@pytest.mark.parametrize("changes", [{}, {"maxCases": 0}, {"maxCases": True}, {"maxConcurrency": 5},
                                     {"perCaseTimeoutMs": 49}, {"perCaseTimeoutMs": 5000},
                                     {"perCaseTimeoutMs": 75000}, {"perCaseTimeoutMs": 75001}, {"extra": 1}])
def test_snapshot_bounds_validator_and_constructor_match(oracle, changes):
    value = {"maxCases": 100, "maxConcurrency": 4, "perCaseTimeoutMs": 1000, **changes}
    assert _capture(lambda: oracle.validate_runner_bounds_snapshot(value)) == _capture(lambda: legacy.validate_runner_bounds_snapshot(value))
    assert _capture(lambda: oracle.RunnerBounds.from_snapshot(value)) == _capture(lambda: legacy.RunnerBounds.from_snapshot(value))


def test_payload_budgets_and_actual_set_errors_match(oracle):
    for value, maximum in (({"v": "x" * 20}, 5), ({"v": float("nan")}, 500), ({"v": "值"}, 500)):
        assert _capture(lambda: oracle._bounded_json(value, maximum, "BUDGET")) == _capture(lambda: legacy._bounded_json(value, maximum, "BUDGET"))
    for actual in ({}, {"factory-1": _actual()}, {"bad id": _actual()}, {str(index): _actual() for index in range(101)}):
        assert _capture(lambda: oracle.validate_actual_snapshots(actual)) == _capture(lambda: legacy.validate_actual_snapshots(actual))


def _run(module, cases, actuals, **bounds):
    return asyncio.run(module.FactoryOfflineBatchRunner().run(SimpleNamespace(cases=tuple(cases)), deepcopy(actuals), bounds=module.RunnerBounds(**bounds)))


def test_original_batch_runner_aggregate_limits_and_missing_cases_match(oracle):
    cases = [_case(), _case("factory-2")]
    actuals = {"factory-1": _actual(), "factory-2": _actual(routeCode="WRONG_ROUTE")}
    vectors = [(cases, actuals, {}), (cases, actuals, {"max_cases": 1}), ([], {}, {}),
               (cases, {"factory-1": _actual()}, {}),
               (cases, {**actuals, "extra": _actual()}, {}),
               (cases, {**actuals, "factory-1": _actual(tools=())}, {})]
    for selected, observed, bounds in vectors:
        assert _capture(lambda: _run(oracle, selected, observed, **bounds)) == _capture(lambda: _run(legacy, selected, observed, **bounds))
    for results in ([], [{"passed": True, "routeOk": True, "trajectoryOk": True, "numericTruthOk": True}]):
        assert _capture(lambda: oracle.aggregate_case_results(results)) == _capture(lambda: legacy.aggregate_case_results(results))


def test_committed_factory_corpus_runs_identically(oracle):
    fixtures = ROOT / "backend/python/tests/fixtures/factory_agentops"
    cases = json.loads((fixtures / "eval_set.json").read_text(encoding="utf-8"))["cases"]
    actuals = json.loads((fixtures / "recorded_actuals.json").read_text(encoding="utf-8"))
    assert _capture(lambda: _run(oracle, cases, actuals)) == _capture(lambda: _run(legacy, cases, actuals))
    assert _capture(lambda: _run(oracle, cases, actuals))[0] == "ok"


def test_original_runner_timeout_is_preserved(oracle):
    observed = []
    for name, module in (("old", oracle), ("legacy", legacy)):
        base = module.FactoryOfflineBatchRunner
        def slow(case, actual, base=base, name=name):
            time.sleep(.1)
            observed.append(name)
            return base._evaluate(case, actual)
        slow_type = type("SlowFactoryRunner", (base,), {"_evaluate": staticmethod(slow)})
        result = _capture(lambda: asyncio.run(slow_type().run(SimpleNamespace(cases=(_case(),)),
                                                             {"factory-1": _actual()}, bounds=module.RunnerBounds(per_case_timeout_seconds=.05))))
        assert result[:2] == ("error", "TimeoutError")
    assert observed == ["old", "legacy"]


def test_actual_file_mutations_fail_and_rehashed_logic_cannot_pass_projection(tmp_path):
    original_module, original_manifest = gate.expected_projection(gate.load_sources(ROOT))
    module_path, manifest_path = tmp_path / "factory_31999.py", tmp_path / "provenance.json"
    module_path.write_bytes(original_module)
    manifest_path.write_bytes(original_manifest)
    pinned = hashlib.sha256(original_manifest).hexdigest()
    assert legacy.verify_local_artifact(pinned, artifact_dir=tmp_path)
    module_path.write_bytes(original_module + b"\n")
    assert not legacy.verify_local_artifact(pinned, artifact_dir=tmp_path)
    module_path.write_bytes(original_module)
    manifest_path.write_bytes(original_manifest + b" ")
    assert not legacy.verify_local_artifact(pinned, artifact_dir=tmp_path)
    mutated = original_module.replace(b"not isinstance(value, list)", b"not isinstance(value, (list, tuple))", 1)
    assert mutated != original_module
    module_path.write_bytes(mutated)
    rehashed = json.loads(original_manifest)
    rehashed["implementation"]["moduleSha256"] = hashlib.sha256(mutated).hexdigest()
    manifest_path.write_text(json.dumps(rehashed, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    with pytest.raises(AssertionError, match="projection differs"):
        gate.check(ROOT, module_path=module_path, manifest_path=manifest_path)
