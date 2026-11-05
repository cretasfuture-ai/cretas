"""Execute historical source records through the real service and HTTP rerun boundary."""
import asyncio
import copy
import logging
import uuid
from pathlib import Path

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from smartbi.agent.eval.contracts import AgentOpsContext
from smartbi.agent.eval.runner import EvaluatorBuildUnavailableError, RunnerBounds
from smartbi.agent.eval import service as service_module
from smartbi.agent.eval.service import AgentOpsService
from smartbi.agent.eval.store import InMemoryAgentOpsStore
from smartbi.api.agent_ops import get_agent_ops_service, router

from tests.agent_eval.helpers import seed_factory_history as seed_history

LEGACY = service_module._LEGACY_BUILD
CTX = AgentOpsContext('F006', '42', 'platform_admin', 'historical-rerun', 'FACTORY')



def make_app(store):
    app = FastAPI()
    @app.middleware('http')
    async def trusted(request: Request, call_next):
        request.state.auth_method='internal'
        request.state.factory_id=CTX.factory_id
        request.state.user_id=CTX.user_id
        request.state.role=CTX.role
        request.state.business_type='FACTORY'
        return await call_next(request)
    app.include_router(router)
    app.dependency_overrides[get_agent_ops_service] = lambda: AgentOpsService(store)
    return app


@pytest.mark.asyncio
@pytest.mark.parametrize('route', ['MATERIAL_BATCH_QUERY','PRODUCTION_BATCH_QUERY'])
async def test_historical_service_rerun_saves_source_identity_and_preserves_snapshot(route, caplog):
    store=InMemoryAgentOpsStore();source=await seed_history(store, route=route)
    before=copy.deepcopy(source.safe_dict())
    service=AgentOpsService(store)
    assert service._registry.current().evaluator_build != LEGACY
    with caplog.at_level(logging.INFO):
        result=await service.rerun_experiment(CTX,source.experiment_id,request_id=str(uuid.uuid4()))
    assert result.evaluator_build == LEGACY
    assert result.evaluator_version == source.evaluator_version
    assert result.snapshot_digest == source.snapshot_digest
    assert result.source_experiment_id == source.experiment_id and result.operation_kind == 'RERUN'
    assert result.aggregate['passedCount'] == 1 and result.aggregate['passRate'] == '1.000000'
    assert (await store.get_experiment(CTX,source.experiment_id)).safe_dict() == before
    details=service._registry.registration_for(service._registry.resolve('factory-offline-v1',LEGACY))
    assert details.source_build == LEGACY and details.implementation_sha256 == service_module._LEGACY_MODULE_SHA
    assert details.provenance_sha256 == service_module._LEGACY_MANIFEST_SHA and not details.is_current
    assert not hasattr(details.runner,'evaluator_build')
    assert 'implementation_sha256='+service_module._LEGACY_MODULE_SHA in caplog.text
    assert 'source_build='+LEGACY in caplog.text


def test_http_historical_rerun_and_idempotency_preserve_original_record():
    store=InMemoryAgentOpsStore();source=asyncio.run(seed_history(store));before=copy.deepcopy(source.safe_dict())
    with TestClient(make_app(store)) as client:
        body={'schemaVersion':'1.0','requestId':str(uuid.uuid4())}
        url='/api/internal/smartbi/agent/runs/ops/experiments/'+source.experiment_id+'/rerun'
        response=client.post(url,json=body)
        assert response.status_code == 201, response.text
        result=response.json()
        assert result['evaluatorBuild'] == LEGACY and result['snapshotDigest'] == source.snapshot_digest
        assert result['sourceExperimentId'] == source.experiment_id and result['operationKind'] == 'RERUN'
        assert result['aggregate']['passedCount'] == 1
        replay=client.post(url,json=body)
        assert replay.status_code == 201 and replay.json()['experimentId'] == result['experimentId']
        original=client.get('/api/internal/smartbi/agent/runs/ops/experiments/'+source.experiment_id)
        assert original.status_code == 200 and original.json() == before
    assert len(store._experiments) == 2


@pytest.mark.parametrize('failure', ['unknown','module','manifest','missing'])
def test_historical_unknown_or_tamper_is_409_and_current_requests_stay_available(monkeypatch,failure):
    store=InMemoryAgentOpsStore();source=asyncio.run(seed_history(store,build='0'*64 if failure=='unknown' else LEGACY))
    before=copy.deepcopy(source.safe_dict())
    original_read=Path.read_bytes
    def read_bytes(path):
        if path.name == ('provenance.json' if failure=='manifest' else 'factory_31999.py'):
            if failure=='missing':raise FileNotFoundError('isolated missing historical artifact')
            if failure in ('module','manifest'):return original_read(path)+b'\n# altered bytes'
        return original_read(path)
    monkeypatch.setattr(Path,'read_bytes',read_bytes)
    with TestClient(make_app(store),raise_server_exceptions=False) as client:
        current=client.get('/api/internal/smartbi/agent/runs/ops/eval-sets')
        assert current.status_code == 200
        response=client.post('/api/internal/smartbi/agent/runs/ops/experiments/'+source.experiment_id+'/rerun',json={'schemaVersion':'1.0','requestId':str(uuid.uuid4())})
        assert response.status_code == 409, response.text
        assert response.json()['detail'] == 'EVALUATOR_BUILD_UNAVAILABLE'
        assert client.get('/api/internal/smartbi/agent/runs/ops/experiments').status_code == 200
    assert store._experiments[source.experiment_id].safe_dict() == before
    assert len(store._experiments) == 1


def test_current_and_unknown_resolve_never_fall_forward_to_legacy():
    registry=AgentOpsService(InMemoryAgentOpsStore())._registry
    assert registry.current().evaluator_build != LEGACY
    for build in (LEGACY,'0'*64):
        with pytest.raises(EvaluatorBuildUnavailableError):registry.resolve('factory-offline-v1',build)
