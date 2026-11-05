"""Tenant-isolated, offline AgentOps evaluation package."""

from .contracts import AgentOpsContext, EvalSetRecord, ExperimentRecord
from .runner import (
    EvaluatorBuildUnavailableError,
    EvaluatorRegistry,
    FactoryOfflineBatchRunner,
    OfflineBatchRunner,
    RunnerBounds,
    compute_evaluator_build,
)
from .service import AgentOpsService
from .store import InMemoryAgentOpsStore, PostgresAgentOpsStore

__all__ = [
    "AgentOpsContext",
    "AgentOpsService",
    "EvalSetRecord",
    "EvaluatorBuildUnavailableError",
    "EvaluatorRegistry",
    "ExperimentRecord",
    "InMemoryAgentOpsStore",
    "FactoryOfflineBatchRunner",
    "OfflineBatchRunner",
    "PostgresAgentOpsStore",
    "RunnerBounds",
    "compute_evaluator_build",
]
