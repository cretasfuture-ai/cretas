"""Bind an authenticated, locally retained recheck to its immutable BI baseline.

Production/cost facts use the same newer observation as the complete-cost view.
Inventory keeps its own, explicitly dated baseline snapshot; it is never relabeled
as a newer stock count merely because work-order evidence was refreshed.
"""
from copy import deepcopy
import json
from pathlib import Path

from .full_archive import digest
from .period_projection import instant


def project_observation(data, metadata, config, observation, bom=None):
    baseline = (config['tenant_id'], str(config['source_factory_id']), config['run_id'])
    scope = (observation.get('tenant_id'), str(observation.get('source_factory_id')), observation.get('run_id'))
    if scope != baseline:
        raise ValueError('OPERATIONAL_SOURCE_SCOPE_MISMATCH')
    if observation.get('snapshot_fingerprint') != digest(data.get('project', [])):
        raise ValueError('OPERATIONAL_BASELINE_FINGERPRINT_MISMATCH')
    raw = observation.get('data')
    if not isinstance(raw, dict) or observation.get('observationFingerprint') != digest(raw):
        raise ValueError('OPERATIONAL_SOURCE_FINGERPRINT_MISMATCH')
    identities = lambda rows: {(str(p['id']), p['projectCode']) for p in rows}
    if (identities(raw.get('project', [])) != identities(data.get('project', []))
            or observation.get('originalProjectsNotReturned') or observation.get('sourceBusinessWrites') != 0
            or observation.get('baselineMutated') is not False):
        raise ValueError('OPERATIONAL_COHORT_OR_READONLY_MISMATCH')
    if (observation.get('negativeControlVerified') is not True
            or not all(p.get('dualQueryEqual') is True for p in observation.get('parentControls', []))):
        raise ValueError('OPERATIONAL_QUERY_CONTROLS_REQUIRED')
    checked = instant(observation.get('finished_at'))
    previous = instant(metadata.get('finished_at'))
    if checked is None or previous is None or checked < previous:
        raise ValueError('OPERATIONAL_SOURCE_TIME_INVALID')
    failed = {r.get('resource') for r in observation.get('failures', [])}
    if failed - {'bom'}:
        raise ValueError('OPERATIONAL_REQUIRED_QUERY_FAILED')
    for key in ('project', 'task', 'output', 'stock_order', 'product', 'process', 'materials'):
        rows = raw.get(key)
        if not isinstance(rows, list) or len({str(r.get('id')) for r in rows}) != len(rows) or any(r.get('id') is None for r in rows):
            raise ValueError('OPERATIONAL_RESOURCE_IDENTITY_INVALID')
    ids = {str(p['id']) for p in raw['project']}
    if any(str(r.get('projectId')) not in ids for k in ('task', 'output') for r in raw[k]):
        raise ValueError('OPERATIONAL_CHILD_SCOPE_MISMATCH')
    result = deepcopy(raw)
    # One canonical representation for every join; do not silently drop wages
    # when the query serializes a child foreign key as text instead of a number.
    canonical_ids={str(p['id']):p['id'] for p in result['project']}
    for resource in ('task','output','materials','project_expand'):
        for row in result.get(resource,[]):
            if str(row.get('projectId')) in canonical_ids:
                row['projectId']=canonical_ids[str(row['projectId'])]
    if failed or bom:
        if (not bom or bom.get('complete') is not True or bom.get('failures')
                or (bom.get('tenantId'), str(bom.get('sourceFactoryId')), bom.get('baselineRunId')) != baseline
                or bom.get('parentProbeId') != observation.get('probeId') or bom.get('sourceBusinessWrites') != 0):
            raise ValueError('OPERATIONAL_BOM_SUPPLEMENT_REQUIRED')
        result['bom'] = deepcopy(bom['rows'])
    # Definitions and balances absent from the recheck retain their original clock.
    for resource in ('warehouse', 'stock'):
        result[resource] = deepcopy(data.get(resource, []))
    source_id = observation.get('probeId')
    if not isinstance(source_id, str) or not source_id:
        raise ValueError('OPERATIONAL_SOURCE_ID_REQUIRED')
    meta = {**metadata, 'run_id': source_id, 'finished_at': observation['finished_at'], 'complete': False,
            'operational_parent_controls': deepcopy(observation['parentControls']),
            'stock_finished_at': metadata.get('stock_finished_at', metadata['finished_at']),
            'operational_source': {'workOrdersCheckedAt': observation['finished_at'],
                                   'inventoryCheckedAt': metadata.get('stock_finished_at', metadata['finished_at']),
                                   'baselineAnchor': config['run_id'], 'observationId': source_id}}
    return result, meta


def load_observation(data, metadata, config, path, bom_path=None):
    source = Path(path)
    if not source.is_absolute():
        raise ValueError('OPERATIONAL_SOURCE_ABSOLUTE_PATH_REQUIRED')
    observation = json.loads(source.read_text(encoding='utf8'))
    bom = None
    if bom_path:
        path = Path(bom_path)
        if not path.is_absolute():
            raise ValueError('OPERATIONAL_BOM_ABSOLUTE_PATH_REQUIRED')
        bom = json.loads(path.read_text(encoding='utf8'))
    return project_observation(data, metadata, config, observation, bom)


def source_revision(config, path, bom_path=None, supplement_path=None):
    """Queue/cache identity only; facts still pass the full reader validation."""
    source=Path(path)
    if not source.is_absolute():raise ValueError('OPERATIONAL_SOURCE_ABSOLUTE_PATH_REQUIRED')
    observation=json.loads(source.read_text(encoding='utf8'))
    if (observation.get('tenant_id'),str(observation.get('source_factory_id')),observation.get('run_id')) != (config['tenant_id'],str(config['source_factory_id']),config['run_id']):
        raise ValueError('OPERATIONAL_SOURCE_SCOPE_MISMATCH')
    bom=None
    if bom_path:
        candidate=Path(bom_path)
        if not candidate.is_absolute():raise ValueError('OPERATIONAL_BOM_ABSOLUTE_PATH_REQUIRED')
        bom=json.loads(candidate.read_text(encoding='utf8'))
    supplement=None
    if supplement_path:
        candidate=Path(supplement_path)
        if not candidate.is_absolute():raise ValueError('OPERATIONS_SUPPLEMENT_ABSOLUTE_PATH_REQUIRED')
        supplement=json.loads(candidate.read_text(encoding='utf8'))
        if (supplement.get('tenant_id'),str(supplement.get('source_factory_id')),supplement.get('baselineRunId'),supplement.get('parentObservationId')) != (config['tenant_id'],str(config['source_factory_id']),config['run_id'],observation['probeId']):
            raise ValueError('OPERATIONS_SUPPLEMENT_SCOPE_MISMATCH')
    return config['run_id']+':operations:'+digest({'observation':observation,'bom':bom,'supplement':supplement,'projection':3})
