"""Generated frozen factory compatibility execution; regenerate with the provenance gate.

This implementation has its own digest. It does not claim to be the historical
two-file source artifact and provides no restaurant runtime or external tools.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Mapping, Sequence

# EvalSetRecord is only an annotation in the frozen runner, never a constructor.
EvalSetRecord = Any
# Preserve the old validator's historical mismatch sentinel as data, not a route.
RouteCode = SimpleNamespace(GROSS_MARGIN_DECLINE_ATTRIBUTION=SimpleNamespace(value='GROSS_MARGIN_DECLINE_ATTRIBUTION'))
# Preserve original method expressions without importing the retired runtime.
runtime_evaluation = sys.modules[__name__]
HISTORICAL_SOURCE_BUILD = '31999b828125844a2fcbbc75edef466bd24756bd2c85d35258c75d5bebe3ac47'


# Frozen source: validation.py::MAX_CASES
MAX_CASES = 100


# Frozen source: validation.py::MAX_SNAPSHOT_BYTES
MAX_SNAPSHOT_BYTES = 64_000


# Frozen source: validation.py::MAX_AGENT_OPS_PAYLOAD_BYTES
MAX_AGENT_OPS_PAYLOAD_BYTES = 4 * 1024 * 1024


# Frozen source: validation.py::MAX_TOOLS
MAX_TOOLS = 10


# Frozen source: validation.py::MAX_NUMERIC_REFS
MAX_NUMERIC_REFS = 100


# Frozen source: validation.py::_ID
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


# Frozen source: validation.py::AgentOpsValidationError
class AgentOpsValidationError(ValueError):
    pass


# Frozen source: validation.py::validate_actual_snapshot
def validate_actual_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    keys = {"routeCode", "tools", "numericTruthRefs", "roundsUsed", "toolCallsUsed"}
    if not isinstance(value, Mapping) or set(value) != keys:
        raise AgentOpsValidationError("INVALID_ACTUAL_SNAPSHOT_SCHEMA")
    route = value["routeCode"]
    if route != RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION.value:
        # Other controlled strings are admitted so route mismatch can be measured.
        route = _identifier(route, "INVALID_ACTUAL_ROUTE")
    tools = _tools(value["tools"])
    rounds_used = _bounded_int(value["roundsUsed"], 0, 2, "INVALID_ROUNDS_USED")
    tool_calls_used = _bounded_int(
        value["toolCallsUsed"], 0, MAX_TOOLS, "INVALID_TOOL_CALLS_USED"
    )
    if tool_calls_used < len(tools) or (tools and rounds_used == 0):
        raise AgentOpsValidationError("INCONSISTENT_ACTUAL_COUNTERS")
    normalized = {
        "routeCode": route,
        "tools": tools,
        "numericTruthRefs": _numeric_refs(value["numericTruthRefs"]),
        "roundsUsed": rounds_used,
        "toolCallsUsed": tool_calls_used,
    }
    _bounded_json(normalized, MAX_SNAPSHOT_BYTES, "ACTUAL_SNAPSHOT_TOO_LARGE")
    return normalized


# Frozen source: validation.py::validate_actual_snapshots
def validate_actual_snapshots(
    value: Mapping[str, Mapping[str, Any]],
) -> dict[str, dict[str, Any]]:
    if not isinstance(value, Mapping) or not 1 <= len(value) <= MAX_CASES:
        raise AgentOpsValidationError("ACTUAL_SNAPSHOT_COUNT_OUT_OF_BOUNDS")
    normalized: dict[str, dict[str, Any]] = {}
    for raw_case_id in sorted(value):
        case_id = _identifier(raw_case_id, "INVALID_CASE_ID")
        normalized[case_id] = validate_actual_snapshot(value[raw_case_id])
    _bounded_json(
        normalized,
        MAX_AGENT_OPS_PAYLOAD_BYTES,
        "ACTUAL_SNAPSHOTS_PAYLOAD_TOO_LARGE",
    )
    return normalized


# Frozen source: validation.py::validate_runner_bounds_snapshot
def validate_runner_bounds_snapshot(value: Mapping[str, Any]) -> dict[str, Any]:
    expected = {"maxCases", "maxConcurrency", "perCaseTimeoutMs"}
    if not isinstance(value, Mapping) or set(value) != expected:
        raise AgentOpsValidationError("INVALID_RUNNER_BOUNDS_SCHEMA")
    return {
        "maxCases": _bounded_int(value["maxCases"], 1, MAX_CASES, "INVALID_MAX_CASES"),
        "maxConcurrency": _bounded_int(
            value["maxConcurrency"], 1, 4, "INVALID_MAX_CONCURRENCY"
        ),
        "perCaseTimeoutMs": _bounded_int(
            value["perCaseTimeoutMs"], 50, 75_000, "INVALID_CASE_TIMEOUT"
        ),
    }


# Frozen source: validation.py::_tools
def _tools(value: Any) -> list[str]:
    if not isinstance(value, list) or len(value) > MAX_TOOLS:
        raise AgentOpsValidationError("INVALID_TOOL_TRAJECTORY")
    return [_identifier(item, "INVALID_TOOL_NAME") for item in value]


# Frozen source: validation.py::_numeric_refs
def _numeric_refs(value: Any) -> dict[str, str]:
    if not isinstance(value, Mapping) or len(value) > MAX_NUMERIC_REFS:
        raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_REFS")
    result: dict[str, str] = {}
    for ref, raw in value.items():
        key = _identifier(ref, "INVALID_NUMERIC_TRUTH_REF")
        if not isinstance(raw, str) or len(raw) > 96:
            raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_VALUE")
        try:
            parsed = Decimal(raw)
        except InvalidOperation as exc:
            raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_VALUE") from exc
        if not parsed.is_finite():
            raise AgentOpsValidationError("INVALID_NUMERIC_TRUTH_VALUE")
        rendered = format(parsed, "f")
        if "." in rendered:
            rendered = rendered.rstrip("0").rstrip(".")
        result[key] = rendered or "0"
    return result


# Frozen source: validation.py::_identifier
def _identifier(value: Any, code: str) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise AgentOpsValidationError(code)
    return value


# Frozen source: validation.py::_bounded_int
def _bounded_int(value: Any, low: int, high: int, code: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise AgentOpsValidationError(code)
    return value


# Frozen source: validation.py::_bounded_json
def _bounded_json(value: Any, maximum: int, code: str) -> None:
    try:
        encoded = json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise AgentOpsValidationError(code) from exc
    if len(encoded) > maximum:
        raise AgentOpsValidationError(code)


# Frozen source: runtime/evaluation.py::OfflineEvaluation
@dataclass(frozen=True)
class OfflineEvaluation:
    route_ok: bool
    trajectory_ok: bool
    numeric_truth_ok: bool
    failures: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.failures


# Frozen source: runtime/evaluation.py::OfflineCaseExpectation
@dataclass(frozen=True)
class OfflineCaseExpectation:
    """Immutable expectations stored in a versioned Eval Set."""

    route_code: str
    required_tools_in_order: tuple[str, ...]
    numeric_truth_refs: Mapping[str, str]
    max_rounds: int = 2
    max_tool_calls: int = 10


# Frozen source: runtime/evaluation.py::OfflineCaseSnapshot
@dataclass(frozen=True)
class OfflineCaseSnapshot:
    """Sanitized observed result supplied to the offline batch harness."""

    route_code: str
    tools_in_order: tuple[str, ...]
    numeric_truth_refs: Mapping[str, str]
    rounds_used: int
    tool_calls_used: int


# Frozen source: runtime/evaluation.py::evaluate_offline_case
def evaluate_offline_case(
    actual: OfflineCaseSnapshot, expectation: OfflineCaseExpectation
) -> OfflineEvaluation:
    """Evaluate one data-only fixture with the same route/trajectory semantics.

    Numeric truth references are evidence-bound identifiers mapped to canonical
    finite decimal strings.  This is the persisted-fixture equivalent of
    :class:`NumericTruthGuard`: every expected reference must exist and carry
    the expected value; missing references are never interpreted as zero.
    """

    failures: list[str] = []
    route_ok = actual.route_code == expectation.route_code
    if not route_ok:
        failures.append("ROUTE_MISMATCH")

    trajectory_ok = (
        _is_subsequence(expectation.required_tools_in_order, actual.tools_in_order)
        and actual.rounds_used <= expectation.max_rounds
        and actual.tool_calls_used <= expectation.max_tool_calls
    )
    if not trajectory_ok:
        failures.append("TRAJECTORY_MISMATCH")

    numeric_ok = _numeric_refs_match(
        actual.numeric_truth_refs, expectation.numeric_truth_refs
    )
    if not numeric_ok:
        failures.append("NUMERIC_TRUTH_FAILED")
    return OfflineEvaluation(route_ok, trajectory_ok, numeric_ok, tuple(failures))


# Frozen source: runtime/evaluation.py::_numeric_refs_match
def _numeric_refs_match(actual: Mapping[str, str], expected: Mapping[str, str]) -> bool:
    if not expected:
        return True
    for ref, expected_value in expected.items():
        observed = actual.get(ref)
        if observed is None:
            return False
        try:
            if Decimal(observed) != Decimal(expected_value):
                return False
            if not Decimal(observed).is_finite() or not Decimal(expected_value).is_finite():
                return False
        except (InvalidOperation, TypeError, ValueError):
            return False
    return True


# Frozen source: runtime/evaluation.py::_is_subsequence
def _is_subsequence(required: tuple[str, ...], actual: tuple[str, ...]) -> bool:
    iterator = iter(actual)
    return all(any(candidate == item for candidate in iterator) for item in required)


# Frozen source: runner.py::FACTORY_EVALUATOR_VERSION
FACTORY_EVALUATOR_VERSION = "factory-offline-v1"


# Frozen source: runner.py::RunnerBounds
@dataclass(frozen=True)
class RunnerBounds:
    max_cases: int = MAX_CASES
    max_concurrency: int = 4
    per_case_timeout_seconds: float = 1.0

    def __post_init__(self) -> None:
        if not 1 <= self.max_cases <= MAX_CASES:
            raise ValueError("max_cases out of bounds")
        if not 1 <= self.max_concurrency <= 4:
            raise ValueError("max_concurrency out of bounds")
        if not 0.05 <= self.per_case_timeout_seconds <= 5.0:
            raise ValueError("per_case_timeout_seconds out of bounds")

    def snapshot(self) -> dict[str, int]:
        return {
            "maxCases": self.max_cases,
            "maxConcurrency": self.max_concurrency,
            "perCaseTimeoutMs": round(self.per_case_timeout_seconds * 1000),
        }

    @classmethod
    def from_snapshot(cls, value: Mapping[str, Any]) -> "RunnerBounds":
        safe = validate_runner_bounds_snapshot(value)
        return cls(
            max_cases=safe["maxCases"],
            max_concurrency=safe["maxConcurrency"],
            per_case_timeout_seconds=safe["perCaseTimeoutMs"] / 1000.0,
        )


# Frozen source: runner.py::OfflineBatchRunner
class OfflineBatchRunner:
    """Consumes only supplied snapshots; it has no Tool or network dependency."""


    async def run(
        self,
        eval_set: EvalSetRecord,
        actual_by_case: Mapping[str, Mapping[str, Any]],
        *,
        bounds: RunnerBounds,
    ) -> tuple[dict[str, Any], tuple[dict[str, Any], ...]]:
        if len(eval_set.cases) > bounds.max_cases:
            raise AgentOpsValidationError("RUNNER_CASE_LIMIT_EXCEEDED")
        if set(actual_by_case) != {case["caseId"] for case in eval_set.cases}:
            raise AgentOpsValidationError("ACTUAL_CASE_SET_MISMATCH")
        semaphore = asyncio.Semaphore(bounds.max_concurrency)

        async def one(case: Mapping[str, Any]) -> dict[str, Any]:
            async with semaphore:
                return await asyncio.wait_for(
                    asyncio.to_thread(self._evaluate, case, actual_by_case[case["caseId"]]),
                    timeout=bounds.per_case_timeout_seconds,
                )

        results = tuple(await asyncio.gather(*(one(case) for case in eval_set.cases)))
        return aggregate_case_results(results), results

    @staticmethod
    def _evaluate(case: Mapping[str, Any], actual_raw: Mapping[str, Any]) -> dict[str, Any]:
        actual = validate_actual_snapshot(actual_raw)
        result = runtime_evaluation.evaluate_offline_case(
            runtime_evaluation.OfflineCaseSnapshot(
                route_code=actual["routeCode"],
                tools_in_order=tuple(actual["tools"]),
                numeric_truth_refs=actual["numericTruthRefs"],
                rounds_used=actual["roundsUsed"],
                tool_calls_used=actual["toolCallsUsed"],
            ),
            runtime_evaluation.OfflineCaseExpectation(
                route_code=case["expectedRoute"],
                required_tools_in_order=tuple(case["requiredTools"]),
                numeric_truth_refs=case["numericTruthRefs"],
                max_rounds=case["maxRounds"],
                max_tool_calls=case["maxToolCalls"],
            ),
        )
        return {
            "caseId": case["caseId"],
            "passed": result.passed,
            "routeOk": result.route_ok,
            "trajectoryOk": result.trajectory_ok,
            "numericTruthOk": result.numeric_truth_ok,
            "failureCodes": list(result.failures),
        }


# Frozen source: runner.py::FactoryOfflineBatchRunner
class FactoryOfflineBatchRunner(OfflineBatchRunner):
    """Same data-only semantics, separate identity so factory results are never
    mistaken for (or compared against) restaurant ones."""

    evaluator_version = FACTORY_EVALUATOR_VERSION


# Frozen source: runner.py::aggregate_case_results
def aggregate_case_results(
    results: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    total = len(results)
    if total == 0:
        raise AgentOpsValidationError("RUNNER_REQUIRES_CASES")
    passed = sum(1 for item in results if item["passed"])
    return {
        "caseCount": total,
        "passedCount": passed,
        "failedCount": total - passed,
        "passRate": format(passed / total, ".6f"),
        "routePassCount": sum(1 for item in results if item["routeOk"]),
        "trajectoryPassCount": sum(1 for item in results if item["trajectoryOk"]),
        "numericTruthPassCount": sum(
            1 for item in results if item["numericTruthOk"]
        ),
    }



def verify_local_artifact(expected_manifest_digest: str, *, artifact_dir=None) -> bool:
    """Validate local immutable bytes only; never consult Git or a network."""
    if not isinstance(expected_manifest_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_manifest_digest):
        return False
    directory = Path(artifact_dir) if artifact_dir is not None else Path(__file__).parent
    try:
        raw = (directory / "provenance.json").read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected_manifest_digest:
            return False
        manifest = json.loads(raw)
        if manifest["historical"]["evaluatorBuild"] != HISTORICAL_SOURCE_BUILD:
            return False
        module = (directory / "factory_31999.py").read_bytes()
        return hashlib.sha256(module).hexdigest() == manifest["implementation"]["moduleSha256"]
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        return False
