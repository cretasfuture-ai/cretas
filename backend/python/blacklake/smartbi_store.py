"""Read-only SmartBI dataset reader, isolated from source credentials and source API."""
from collections import Counter, defaultdict
from pathlib import Path
import os

import psycopg2
from psycopg2.extensions import parse_dsn

from .analytics import build
from .intelligence import enrich
from .full_archive import digest
from .secrets import load_local_secret
from .bi_readiness import validate
from .gap_evidence import EVIDENCE_SCHEMA


def bind_evidence(value,records,evidence):
    scope=(value['tenant_id'],value['source_factory_id'],value['run_id'])
    binding=(evidence.get('tenantId'),evidence.get('sourceFactoryId'),evidence.get('runId'))
    if binding!=scope or evidence.get('snapshotFingerprint')!=digest([(r,i,h) for r,i,h,_ in records]):
        return {'status':'scope_or_snapshot_mismatch','summary':{},'verifiedMaterialParents':[]}
    if evidence.get('schema')!=EVIDENCE_SCHEMA or evidence.get('status')!='complete' or evidence.get('negativeControlVerified') is not True:
        return {'status':'incomplete_or_unverified','summary':{},'verifiedMaterialParents':[]}
    return evidence


def settings(path):
    value=load_local_secret(Path(path))
    dsn=parse_dsn(value['dsn'])
    if (dsn.get('host')!='127.0.0.1' or dsn.get('port')!='15432'
            or dsn.get('dbname')!='smartbi_testenv' or dsn.get('user')!='blacklake_bi_reader'
            or value.get('tenant_id')!='BLACKLAKE_POC_TEST'):
        raise RuntimeError('SMARTBI_ISOLATED_TEST_DESTINATION_REQUIRED')
    if value.get('gap_evidence_file'):
        if Path(value['gap_evidence_file']).resolve()!=Path(path).resolve().parent/'gap-evidence.dpapi':
            raise RuntimeError('SMARTBI_EVIDENCE_PATH_INVALID')
    value['labor_supplements_path']=str(Path(path).resolve().parent/'labor-supplements.sqlite3')
    return value


def read(value, period=None, *, live_cycle_id=None):
    connection=psycopg2.connect(value['dsn'],connect_timeout=5)
    try:
        connection.set_session(readonly=True)
        with connection:
            with connection.cursor() as cur:
                validate(cur,parse_dsn(value['dsn'])['user'])
                cur.execute('SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user')
                if any(cur.fetchone()): raise RuntimeError('SMARTBI_READER_BYPASSES_RLS')
                cur.execute("SELECT set_config('blacklake.tenant_id',%s,true),set_config('blacklake.source_factory_id',%s,true)",
                            (value['tenant_id'],value['source_factory_id']))
                scope=(value['tenant_id'],value['source_factory_id'],value['run_id'])
                cur.execute('SELECT metadata FROM blacklake_bi.dataset WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s',scope)
                found=cur.fetchone()
                if not found: raise RuntimeError('SMARTBI_DATASET_NOT_FOUND')
                metadata=found[0]
                cur.execute('SELECT resource,external_id,content_hash,payload FROM blacklake_bi.record WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s ORDER BY resource,external_id',scope)
                records=cur.fetchall()
        counts=dict(Counter(r[0] for r in records))
        if counts!=metadata['source_totals'] or any(digest(p)!=h for _,_,h,p in records):
            raise RuntimeError('SMARTBI_DATASET_INTEGRITY_FAILED')
        data=defaultdict(list)
        for resource,_,_,payload in records: data[resource].append(payload)
        operational = os.environ.get('BLACKLAKE_OPERATIONAL_SOURCE_FILE')
        if operational:
            from .operational_source import load_observation
            data,metadata=load_observation(data,metadata,value,operational,os.environ.get('BLACKLAKE_OPERATIONAL_BOM_FILE'))
        if os.environ.get('BLACKLAKE_OPERATIONS_SUPPLEMENT_FILE'):
            if not operational:raise ValueError('OPERATIONS_PARENT_OBSERVATION_REQUIRED')
            from .operations_supplement import load
            data,metadata=load(data,metadata,value,os.environ['BLACKLAKE_OPERATIONS_SUPPLEMENT_FILE'])
        if os.environ.get('BLACKLAKE_LIVE_CONFIG'):
            from .live_monitor import load
            data,metadata=load(data,metadata,value,os.environ['BLACKLAKE_LIVE_CONFIG'],live_cycle_id)
        from .period_projection import instant
        from zoneinfo import ZoneInfo
        completed=[instant(p.get('actualEndTime')) for p in data['project'] if str(p.get('status'))=='20']
        metadata={**metadata,'latest_completed_date':max((d.astimezone(ZoneInfo('Asia/Shanghai')).date().isoformat() for d in completed if d is not None),default=None)}
        evidence=None
        if value.get('gap_evidence_file'):
            evidence=load_local_secret(value['gap_evidence_file'])
            evidence=bind_evidence(value,records,evidence)
        from .traceability import mapped,subset
        parents=defaultdict(list)
        trusted=evidence and evidence.get('status')=='complete' and evidence.get('negativeControlVerified') is True
        if trusted:
            for row in evidence.get('verifiedMaterialParents',[]):parents[row['projectId']].append(row['materialId'])
        if operational:
            # The archived recheck has separately verified parent-query controls.
            parents=defaultdict(list,{p['projectId']:p['materialIds'] for p in metadata['operational_parent_controls']})
            trusted=True
            evidence=None  # Baseline supplemental evidence is not a current probe.
        controls=[{'projectId':pid,'dualQueryEqual':True,'materialIds':ids} for pid,ids in parents.items()]
        trace=mapped(data,build(data,metadata),scope[:2],metadata['run_id'],metadata.get('finished_at'),controls,bool(trusted))
        period_metadata=None
        if period is not None:
            from .period_projection import project_period
            data,metadata,period_metadata=project_period(data,metadata,period)
            # Whole-history supplemental evidence is never relabeled as a period probe.
            evidence=None
        analysis=build(data,metadata)
        if value.get('labor_supplements_path'):
            from .labor_supplements import attach
            attach(analysis,value)
            if period_metadata is not None and any(t['status']!='missing' for t in analysis['laborSupplements']['tasks']):
                supplementary=[{k:t.get(k) for k in ('taskId','revision','status','signature')} for t in analysis['laborSupplements']['tasks']]
                period_metadata['fingerprint']=digest({'source':period_metadata['fingerprint'],'labor':supplementary})
                analysis['runId']=period_metadata['baseRunId']+':period:'+period_metadata['fingerprint'][:20]
                analysis['laborSupplements']['sourceRunId']=analysis['runId']
        analysis['operationalSource']=metadata.get('operational_source')
        analysis['latestCompletedDate']=metadata.get('latest_completed_date')
        analysis['operationsSupplement']=metadata.get('operations_supplement')
        analysis['costTraceability']=subset(trace,[p['id'] for p in data['project']],analysis['runId']) if period is not None else trace
        analysis['costIntelligence']=enrich(analysis,data,metadata,evidence)
        if period_metadata is not None:
            analysis['costIntelligence']['gapEvidence']={'status':'period_not_verified'}
        from smartbi.services.cost_data_alerts import evaluate
        from .cost_review import review
        analysis['costAlerts']=evaluate(analysis['cost']['projects'],scope=digest(scope[:2]),run_id=analysis['runId'])
        from .labor_supplement_analysis import apply_alerts
        apply_alerts(analysis)
        # A former 8B review is historical evidence, not the selected runtime.
        # Only attach a current, fully validated shared 27B review.
        if period is None and os.environ.get('LOCALAPPDATA'):
            from .section_review import attach
            attach(analysis['costAlerts'], Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/cost-common-ai.dpapi')
        analysis['eligibleCostReview']=review(analysis)
        # Common machine contract, preserving the existing Blacklake API key.
        analysis['dataAlerts']=analysis['costAlerts']
        from .section_insights import build as build_sections
        analysis['sectionInsights']=build_sections(analysis)
        from .labor_supplement_analysis import explain
        explain(analysis)
        if period_metadata is not None:
            from .period_projection import describe_period
            describe_period(analysis,period_metadata)
        return analysis
    finally:
        connection.close()
