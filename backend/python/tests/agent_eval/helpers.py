from smartbi.agent.eval.contracts import AgentOpsContext


def request_id(value: int = 1) -> str:
    return f"00000000-0000-4000-8000-{value:012d}"


def context(
    factory: str = "R001", user: str = "42", role: str = "platform_admin"
) -> AgentOpsContext:
    return AgentOpsContext(factory, user, role, "corr-001")


def config_snapshot() -> dict:
    return {
        "promptSnapshotDigest": "1" * 64,
        "modelSnapshotDigest": "2" * 64,
        "toolSnapshotDigest": "3" * 64,
    }


def case(case_id: str = "margin-1") -> dict:
    return {
        "caseId": case_id,
        "expectedRoute": "FACTORY_MARGIN_ANALYSIS",
        "requiredTools": ["factory_margin_read", "factory_cost_read"],
        "numericTruthRefs": {"ev-1:fact-1": "12.50"},
        "maxRounds": 2,
        "maxToolCalls": 4,
    }


def actual(*, value: str = "12.5", tools=None, route=None) -> dict:
    return {
        "routeCode": route or "FACTORY_MARGIN_ANALYSIS",
        "tools": tools if tools is not None else ["factory_margin_read", "factory_cost_read"],
        "numericTruthRefs": {"ev-1:fact-1": value},
        "roundsUsed": 1,
        "toolCallsUsed": 2,
    }


async def seed_factory_history(store, *, route='MATERIAL_BATCH_QUERY', build=None):
    import uuid
    from smartbi.agent.eval import service as service_module
    from smartbi.agent.eval.service import AgentOpsService
    from smartbi.agent.eval.runner import RunnerBounds
    from smartbi.agent.eval.store import new_experiment_record
    from smartbi.agent.eval.validation import canonical_digest, validate_actual_snapshots
    CTX = AgentOpsContext('F006', '42', 'platform_admin', 'historical-rerun', 'FACTORY')
    build = service_module._LEGACY_BUILD if build is None else build
    case = {'caseId':'factory-1','expectedRoute':route,'requiredTools':['material_batch_query'],
            'numericTruthRefs':{'ev-1:fact-1':'12.50'},'maxRounds':2,'maxToolCalls':4}
    config = {'promptSnapshotDigest':'1'*64,'modelSnapshotDigest':'2'*64,'toolSnapshotDigest':'3'*64}
    service = AgentOpsService(store)
    eval_set = await service.create_eval_set(CTX, request_id=str(uuid.uuid4()), name='Historical factory', version=1, description='', cases=[case])
    actuals = validate_actual_snapshots({'factory-1': {'routeCode':route,'tools':['material_batch_query'],
        'numericTruthRefs':{'ev-1:fact-1':'12.5'},'roundsUsed':1,'toolCallsUsed':1}})
    bounds = RunnerBounds()
    # The frozen old algorithm creates the source, not the current runner.
    old_runner = service_module._load_verified_legacy_runner()
    aggregate, results = await old_runner.run(eval_set, actuals, bounds=bounds)
    assert aggregate['passedCount'] == 1
    snapshot = canonical_digest({'evalSetDigest':eval_set.content_digest,'evaluatorVersion':'factory-offline-v1',
        'evaluatorBuild':build,'configSnapshot':config,'actualSnapshots':actuals,'runnerBounds':bounds.snapshot()})
    request_digest = canonical_digest({'schemaVersion':'1.0','operationKind':'RUN','evalSetId':eval_set.eval_set_id,
        'configSnapshot':config,'actualSnapshots':actuals,'runnerBounds':bounds.snapshot()})
    source = new_experiment_record(CTX, eval_set=eval_set, evaluator_version='factory-offline-v1',evaluator_build=build,
        snapshot_digest=snapshot,request_id=str(uuid.uuid4()),request_digest=request_digest,operation_kind='RUN',
        source_experiment_id=None,config_snapshot=config,actual_snapshots=actuals,runner_bounds=bounds.snapshot(),
        aggregate=aggregate,case_results=results)
    return await store.save_experiment(CTX, source)
