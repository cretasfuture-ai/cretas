"""Factory-domain run traces: one row per run, ordered steps with tool/latency/outcome."""

from __future__ import annotations

from typing import Any, Mapping, Sequence


def build_factory_trace_response(
    run: Mapping[str, Any],
    steps: Sequence[Mapping[str, Any]],
    *,
    after_sequence: int,
    limit: int,
) -> dict[str, Any]:
    """Shape a stored factory trace like the restaurant one (events + page)."""

    eligible = [step for step in steps if step["sequence"] > after_sequence]
    page = eligible[:limit]
    has_more = len(eligible) > limit
    next_sequence = page[-1]["sequence"] if page else after_sequence
    return {
        "runId": run["runId"],
        "domain": "FACTORY",
        "routeCode": run["intentCode"],
        "state": "COMPLETED" if run["outcome"] != "UNKNOWN" else "UNMEASURED",
        "outcome": {"status": run["outcome"]},
        "latencyMs": run["latencyMs"],
        "counters": {"toolCallsUsed": len(steps)},
        "createdAt": run["createdAt"],
        "events": [
            {
                "sequence": step["sequence"],
                "eventType": "STEP_FAILED" if step["outcome"] == "FAILED" else "STEP_COMPLETED",
                "toolName": step["toolName"],
                "latencyMs": step["latencyMs"],
                "outcome": step["outcome"],
                "sourceRef": step["sourceRef"],
            }
            for step in page
        ],
        "page": {
            "afterSequence": after_sequence,
            "limit": limit,
            "returned": len(page),
            "hasMore": has_more,
            "nextAfterSequence": next_sequence if has_more else None,
        },
    }
