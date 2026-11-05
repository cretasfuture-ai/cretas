import sys
import types

import pytest

from smartbi.services import analytics_warmup


@pytest.mark.asyncio
async def test_factory_warmup_only_dispatches_factory_insights(monkeypatch):
    calls = []

    class FakeNarrativeCache:
        def __init__(self, pool):
            self.pool = pool

    class FakeBudgetTracker:
        def __init__(self, pool):
            self.pool = pool

    class FakeOrchestrator:
        def __init__(self, pool, cache, budget):
            self.pool = pool

        async def answer_insight(self, *, factory_id, question, date_range):
            calls.append((factory_id, question, date_range))

    def forbidden_connect(*args, **kwargs):
        raise AssertionError("factory dashboard warmup must not open a recipe database connection")

    monkeypatch.setattr(analytics_warmup.asyncpg, "connect", forbidden_connect)
    monkeypatch.setitem(
        sys.modules,
        "smartbi.agent.orchestrator",
        types.SimpleNamespace(AgentOrchestrator=FakeOrchestrator),
    )
    monkeypatch.setitem(
        sys.modules,
        "smartbi.agent.narrative_cache",
        types.SimpleNamespace(NarrativeCache=FakeNarrativeCache),
    )
    monkeypatch.setitem(
        sys.modules,
        "smartbi.agent.budget_tracker",
        types.SimpleNamespace(AgentBudgetTracker=FakeBudgetTracker),
    )

    result = await analytics_warmup.warmup_factory_insights(
        object(), "F001", 7, concurrency=2
    )

    assert result == {"started": 5, "concurrency": 2}
    assert len(calls) == len(analytics_warmup.WARMUP_QUESTIONS)
    assert {call[1] for call in calls} == set(analytics_warmup.WARMUP_QUESTIONS)
    assert all(call[0] == "F001" for call in calls)
    assert "auto_recipe_drafts" not in analytics_warmup.warmup_factory_insights.__code__.co_names
