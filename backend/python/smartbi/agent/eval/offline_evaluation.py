"""Neutral, data-only factory evaluator shared by current and compat runners."""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping

@dataclass(frozen=True)
class OfflineEvaluation:
    route_ok: bool
    trajectory_ok: bool
    numeric_truth_ok: bool
    failures: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.failures

@dataclass(frozen=True)
class OfflineCaseExpectation:
    route_code: str
    required_tools_in_order: tuple[str, ...]
    numeric_truth_refs: Mapping[str, str]
    max_rounds: int = 2
    max_tool_calls: int = 10

@dataclass(frozen=True)
class OfflineCaseSnapshot:
    route_code: str
    tools_in_order: tuple[str, ...]
    numeric_truth_refs: Mapping[str, str]
    rounds_used: int
    tool_calls_used: int

def evaluate_offline_case(actual: OfflineCaseSnapshot, expectation: OfflineCaseExpectation) -> OfflineEvaluation:
    failures: list[str] = []
    route_ok = actual.route_code == expectation.route_code
    if not route_ok:
        failures.append("ROUTE_MISMATCH")
    trajectory_ok = (_is_subsequence(expectation.required_tools_in_order, actual.tools_in_order)
                     and actual.rounds_used <= expectation.max_rounds
                     and actual.tool_calls_used <= expectation.max_tool_calls)
    if not trajectory_ok:
        failures.append("TRAJECTORY_MISMATCH")
    numeric_ok = _numeric_refs_match(actual.numeric_truth_refs, expectation.numeric_truth_refs)
    if not numeric_ok:
        failures.append("NUMERIC_TRUTH_FAILED")
    return OfflineEvaluation(route_ok, trajectory_ok, numeric_ok, tuple(failures))

def _numeric_refs_match(actual: Mapping[str, str], expected: Mapping[str, str]) -> bool:
    for ref, expected_value in expected.items():
        observed = actual.get(ref)
        if observed is None:
            return False
        try:
            if Decimal(observed) != Decimal(expected_value) or not Decimal(observed).is_finite() or not Decimal(expected_value).is_finite():
                return False
        except (InvalidOperation, TypeError, ValueError):
            return False
    return True

def _is_subsequence(required: tuple[str, ...], actual: tuple[str, ...]) -> bool:
    iterator = iter(actual)
    return all(any(candidate == item for candidate in iterator) for item in required)
