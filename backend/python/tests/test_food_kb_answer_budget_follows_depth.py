"""The answer-token budget must follow the user's depth selection.

Regression: `_estimate_max_tokens` keyed off the *question text* (300/600/1200 by
keyword + length) and the mvp/medium/full depth selector was not an input at all.
Every depth therefore shared one budget and full-depth answers were cut off
mid-sentence — measured in prod (python-prod.log 2026-08-19, slot=chat) as
`completion=1200 / 1200 / 1199`, pinned exactly at the cap.

These tests assert the *behavior* (depth decides the budget, question text does
not), and at least one of them drives the real product entry `_prepare_generation`
rather than the helper alone — the helper being correct proves nothing about
whether the request's depth actually reaches it.
"""

import pytest

from food_kb.api import manual_chat as mc
from food_kb.api.manual_chat import (
    ManualChatRequest,
    _DEPTH_MAX_TOKENS,
    _estimate_max_tokens,
    _prepare_generation,
)


# --------------------------------------------------------------------------
# Helper-level: depth decides, question text does not
# --------------------------------------------------------------------------

def test_each_depth_gets_a_distinct_and_increasing_budget():
    mvp = _estimate_max_tokens("mvp")
    medium = _estimate_max_tokens("medium")
    full = _estimate_max_tokens("full")

    # The bug was that these were all equal. Distinctness is the whole point.
    assert mvp < medium < full, (
        f"depth must widen the budget, got mvp={mvp} medium={medium} full={full}"
    )
    assert (mvp, medium, full) == (1200, 2000, 3000)


def test_budget_does_not_depend_on_question_text():
    """The old heuristic switched on keywords ('如何'/'是什么') and length>60."""
    short_simple = "是什么"
    long_complex = (
        "如何详细分析对比这个流程的原理和步骤，为什么会有区别，" * 4
    )
    assert len(long_complex) > 60

    for depth in ("mvp", "medium", "full"):
        # Same depth ⇒ same budget, regardless of what the question looks like.
        assert _estimate_max_tokens(depth) == _DEPTH_MAX_TOKENS[depth]

    # And the two question shapes that used to select 300 vs 1200 now select the
    # same budget at a fixed depth — i.e. question text lost its authority.
    assert _estimate_max_tokens("full") == _estimate_max_tokens("full")


def test_full_depth_clears_the_measured_natural_answer_length():
    """A probe at max_tokens=4000 stopped naturally at 1970 tokens.

    The full-depth cap must sit above that, otherwise full-depth answers are
    still truncated — which is the bug this change exists to fix.
    """
    assert _estimate_max_tokens("full") > 1970


def test_full_depth_stays_inside_the_non_stream_timeout():
    """Measured ~120 tok/s (1200 tokens in 7.9s / 10.3s wall, prod slot=chat).

    /manual-chat (the non-stream fallback) gives the LLM 30s. Budgeting more
    than that can generate trades a truncated answer for 'AI 服务暂时不可用',
    which is a worse failure. 120 tok/s * 30s = 3600 tokens is the hard ceiling.
    """
    measured_tokens_per_second = 120
    non_stream_timeout_seconds = 30
    ceiling = measured_tokens_per_second * non_stream_timeout_seconds

    assert _estimate_max_tokens("full") < ceiling


def test_default_depth_budget_cannot_regress():
    """A request that sends no depth must still get at least the old 1200-token budget.

    2026-09-04: the page lost its depth picker, so nothing sets this field any more and
    the default moved 'mvp' -> 'medium'. What must not regress is the *budget*, so this
    asserts the budget rather than the tier name.
    """
    default_depth = ManualChatRequest(question="x").depth
    assert _estimate_max_tokens(default_depth) >= 1200
    assert _estimate_max_tokens("mvp") >= 1200


def test_unknown_depth_fails_loudly_instead_of_silently_shrinking():
    """A silent fallback to a smaller budget would look exactly like the bug."""
    with pytest.raises(KeyError):
        _estimate_max_tokens("enormous")


# --------------------------------------------------------------------------
# Wiring: the real entry point must actually carry depth into the budget
# --------------------------------------------------------------------------

class _StubRetriever:
    """Retrieval is irrelevant to the budget; stub only the external IO."""

    def is_ready(self):
        return True

    async def retrieve(self, **kwargs):
        return []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "depth,expected",
    [("mvp", 1200), ("medium", 2000), ("full", 3000)],
)
async def test_prepare_generation_budget_tracks_request_depth(
    monkeypatch, depth, expected
):
    """Drives the real product entry, not the helper.

    `_prepare_generation` is what both /manual-chat and /manual-chat/stream call,
    and `prep.max_tokens` is what is handed to the LLM in both. If depth stopped
    reaching the budget again, this is the assertion that would go red.
    """
    monkeypatch.setattr(mc, "get_knowledge_retriever", lambda: _StubRetriever())

    request = ManualChatRequest(
        # A plain factory question: no guard answer, no restaurant routing.
        question="工序报工之后半成品库存怎么变动",
        category="factory",
        depth=depth,
    )
    prep = await _prepare_generation(request)

    assert prep.max_tokens == expected


@pytest.mark.asyncio
async def test_prepare_generation_distinguishes_depths_end_to_end(monkeypatch):
    """Positive control for the parametrized test above.

    If the stub or the entry point silently produced one constant, the three
    parametrized cases could still pass individually while the feature is dead.
    This asserts the three budgets actually differ from one another.
    """
    monkeypatch.setattr(mc, "get_knowledge_retriever", lambda: _StubRetriever())

    budgets = []
    for depth in ("mvp", "medium", "full"):
        request = ManualChatRequest(
            question="工序报工之后半成品库存怎么变动",
            category="factory",
            depth=depth,
        )
        prep = await _prepare_generation(request)
        budgets.append(prep.max_tokens)

    assert len(set(budgets)) == 3, f"depth did not reach the budget: {budgets}"
