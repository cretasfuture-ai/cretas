"""Contract tests for the isolated semantic-read local candidate wiring."""
from __future__ import annotations

import asyncio
import importlib.util
import json
from pathlib import Path

import pytest
from types import SimpleNamespace

from ai.dto import CandidateIntentDto, MatchMethod


def load_local_runtime():
    path = Path(__file__).resolve().parents[3] / "tests" / "semantic-read" / "local_runtime.py"
    spec = importlib.util.spec_from_file_location("semantic_read_local_runtime", path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("mode,permissions", [("READ",None),("OPERATE",[]),("OPERATE",["material:write"]),(None,None)])
def test_request_catalog_reuses_factory_business_and_read_write_filters(mode, permissions):
    module=load_local_runtime()
    from ai.db import filter_intents_for_request, filter_rows_by_rw_mode
    base=dict(business_type='COMMON',factory_id=None,is_active=True,deleted_at=None)
    rows=[dict(base,intent_code='MATERIAL_BATCH_QUERY',required_permission='material:read'),
          dict(base,intent_code='MATERIAL_BATCH_CREATE',required_permission='material:write'),
          dict(base,intent_code='ORDER_LIST',factory_id='OTHER'),
          dict(base,intent_code='ORDER_STATUS',business_type='RESTAURANT'),
          dict(base,intent_code='MATERIAL_EXPIRED_QUERY',is_active=False)]
    body=SimpleNamespace(factoryId='F006',businessType='FACTORY',mode=mode,userPermissions=permissions)
    expected=filter_rows_by_rw_mode(filter_intents_for_request(rows,'F006','FACTORY'),mode,permissions)
    assert module.request_visible_rows(rows,body)==expected
    codes={r['intent_code'] for r in expected}
    assert 'ORDER_LIST' not in codes and 'ORDER_STATUS' not in codes
    assert 'MATERIAL_EXPIRED_QUERY' not in codes
    if mode=='READ':
        assert codes=={'MATERIAL_BATCH_QUERY'}


class _FakeClassifier:
    def __init__(self, rows):
        self.rows = rows
        self.calls = 0

    async def match(self, query, *, factoryId, businessType):
        self.calls += 1
        return self.rows


class _FakeBlend:
    top_k = 20
    blend = 0.35

    def __init__(self, rows):
        self.rows = rows
        self.calls = 0

    async def match(self, query, *, factoryId, businessType):
        self.calls += 1
        return self.rows


class _FailingClassifier:
    async def match(self, query, *, factoryId, businessType):
        raise RuntimeError("classifier failure")


class _Pool:
    def acquire(self):
        raise AssertionError("unit contract must not touch a real database")


def _candidate(code, method):
    return CandidateIntentDto(intentCode=code, intentName=code, confidence=.4, matchMethod=method)


def test_local_runtime_unions_both_real_candidate_sources_and_records(tmp_path: Path):
    module = load_local_runtime()
    classifier = _FakeClassifier([_candidate("LOCAL", MatchMethod.CLASSIFIER), _candidate("SHARED", MatchMethod.CLASSIFIER)])
    blend = _FakeBlend([_candidate("SHARED", MatchMethod.SEMANTIC), _candidate("VECTOR", MatchMethod.SEMANTIC)])
    seen = []

    async def embedding(_query):
        seen.append(True)
        return [0.1, 0.2]

    trace = tmp_path / "candidate.jsonl"
    runtime = module.LocalRuntimeCandidateRetriever(
        _Pool(), trace_path=trace, classifier_matcher=classifier,
        blend_retriever=blend, embedding_fn=embedding,
    )
    async def run_turn():
        await runtime.prefetch("查订单", "F006", "FACTORY")
        return await runtime.match("查订单", "F006", "FACTORY")

    out = asyncio.run(run_turn())

    assert [row.intentCode for row in out] == ["LOCAL", "SHARED", "VECTOR"]
    assert classifier.calls == blend.calls == runtime.calls == 1
    assert runtime.classifier_calls == runtime.blend_calls == 1
    assert len(seen) == 1
    record = json.loads(trace.read_text(encoding="utf-8"))
    assert [row["intent_code"] for row in record["classifier_candidates"]] == ["LOCAL", "SHARED"]
    assert [row["intent_code"] for row in record["blend_candidates"]] == ["SHARED", "VECTOR"]
    assert [row["intent_code"] for row in record["union_candidates"]] == ["LOCAL", "SHARED", "VECTOR"]
    assert record["components"]["classifier"] == {"called": True, "ok": True, "count": 2}
    assert record["components"]["blend_retriever"] == {"called": True, "ok": True, "count": 2}
    assert record["failures"] == {}
    assert record["classifier_min_confidence"] == .05
    assert record["classifier_temperature"] == 1.5


def test_local_runtime_fails_closed_when_embedding_is_unavailable():
    module = load_local_runtime()

    async def embedding(_query):
        return None

    runtime = module.LocalRuntimeCandidateRetriever(
        _Pool(), classifier_matcher=_FakeClassifier([]), blend_retriever=_FakeBlend([]), embedding_fn=embedding,
    )
    with pytest.raises(RuntimeError, match="embedding gRPC"):
        asyncio.run(runtime.match("查订单", "F006", "FACTORY"))


def test_local_runtime_records_component_failure_and_does_not_accept_empty(tmp_path: Path):
    module = load_local_runtime()
    trace = tmp_path / "failure.jsonl"
    runtime = module.LocalRuntimeCandidateRetriever(
        _Pool(), trace_path=trace, classifier_matcher=_FailingClassifier(),
        blend_retriever=_FakeBlend([]), embedding_fn=lambda _query: _ready_embedding(),
    )
    with pytest.raises(RuntimeError, match="classifier failure"):
        asyncio.run(runtime.match("查订单", "F006", "FACTORY"))
    record = json.loads(trace.read_text(encoding="utf-8"))
    assert record["components"]["classifier"]["ok"] is False
    assert record["failures"]["classifier"] == "RuntimeError"


async def _ready_embedding():
    return [0.1, 0.2]


def test_missing_actual_classifier_inference_cannot_be_a_successful_empty_result(tmp_path):
    module=load_local_runtime()
    class Silent(_FakeClassifier):
        _strict_observed=True
    trace=tmp_path/'silent.jsonl'
    runtime=module.LocalRuntimeCandidateRetriever(_Pool(),trace_path=trace,classifier_matcher=Silent([]),
        blend_retriever=_FakeBlend([]),embedding_fn=lambda _: _ready_embedding())
    with pytest.raises(RuntimeError,match='swallowed classifier failure'):
        asyncio.run(runtime.match('same query','F006','FACTORY'))
    assert json.loads(trace.read_text('utf-8'))['components']['classifier']['ok'] is False


def test_classifier_thread_observation_is_request_local_even_for_the_same_query():
    module=load_local_runtime()
    async def one(value):
        holder={'value':value};token=module._classifier_observation.set(holder)
        try:
            return await module._observed_to_thread(lambda:module._classifier_observation.get()['value'])
        finally:module._classifier_observation.reset(token)
    async def both():return await asyncio.gather(one('first'),one('second'))
    assert asyncio.run(both())==['first','second']

