"""Exercise existing canonical apply/accept/version APIs in the owned P37 clone only."""
from pathlib import Path
import importlib.util
import json
import os
import sys
import requests

if sys.argv[1:] != ['NON_PRODUCTION_ONLY']:
    raise SystemExit('Explicit NON_PRODUCTION_ONLY acknowledgement required')
os.environ['DAILY_REVIEW_PROFILE'] = 'P37'
helper = Path(__file__).resolve().parents[1] / 'refund-close-concurrency/support.py'
spec = importlib.util.spec_from_file_location('p37_readiness_support', helper)
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
assert s.PROFILE == 'P37' and s.DATABASE == 'p37_factory_generality_20261018'
prefix = f'/api/platform/factory-assembly/factories/{s.FACTORY}/ai-initialize/blueprints'
base = 'http://127.0.0.1:10072'
credentials = json.loads((Path.home() / 'Documents/cretas-connect-20261015/vision/platform-entry/credentials.private.json').read_text(encoding='utf-8'))
platform = requests.Session()
login = platform.post(base + '/api/mobile/auth/unified-login', json={
    'username': credentials['username'], 'password': credentials['password'], 'clientType': 'web'}, timeout=30)
login.raise_for_status()
actor = login.json()['data']
assert actor['role'] == 'super_admin' and not actor.get('factoryId'), 'Platform namespace proof required'
platform.headers['Authorization'] = 'Bearer ' + (actor.get('accessToken') or actor['token'])
denied = s.admin.get(base + prefix + '/current', timeout=30)
assert denied.status_code == 403, 'Daily factory account must not enter platform assembly'

def request(method, suffix, payload=None):
    assert suffix.startswith('/') and '..' not in suffix and '://' not in suffix
    response = platform.request(method, base + prefix + suffix, json=payload, timeout=90)
    body = response.json()
    assert response.status_code == 200 and body.get('success'), (response.status_code, body.get('message'))
    return body['data']

def once(key, method, suffix, payload):
    key = 'readiness.' + key
    if key not in s.steps:
        s.steps[key] = request(method, suffix, payload)
        s.persist()
    return s.steps[key]

before = s.snapshot()
current = request('GET', '/current')
if 'readiness.save-v1' not in s.steps:
    assert current is None, 'Existing canonical version needs inspection before preparing this scenario'
    with s.dbconnect() as db:
        products = db.execute("select code,name,unit from product_types where factory_id=%s and deleted_at is null and is_active=true order by code", (s.FACTORY,)).fetchall()
    payload = {'industryCode': 'FOOD', 'industryName': '食品加工', 'overwrite': False,
               'schemas': [{'entityType': 'PROCESSING_BATCH', 'entityName': '生产批次',
                            'fields': [{'name': 'batchNumber', 'label': '批次号', 'type': 'text', 'required': True}]}],
               'suggestedData': {'productTypes': [dict(zip(('code','name','unit'), row)) for row in products]}}
    once('save-v1', 'POST', '/draft', {'factoryName': '新厂日常闭环本地核对', 'idempotencyKey': 'p37-readiness-save-v1', 'payload': payload})

blueprint = request('GET', '/current')
if 'readiness.accept-v1' not in s.steps:
    for action in ('validate', 'simulate', 'publish', 'apply'):
        blueprint = request('GET', '/current')
        once(action + '-v1', 'POST', '/' + blueprint['id'] + '/' + action,
             {'idempotencyKey': 'p37-readiness-' + action, 'expectedRowVersion': blueprint['rowVersion']})
    blueprint = request('GET', '/current')
    eligible = [row for row in blueprint['capabilities'] if row['acceptanceEligible']]
    assert eligible, 'No configuration receipts eligible for simulated acceptance'
    once('accept-v1', 'POST', '/' + blueprint['id'] + '/accept', {
        'idempotencyKey': 'p37-readiness-accept', 'expectedRowVersion': blueprint['rowVersion'],
        'decisions': [{'capabilityId': row['id'], 'decision': 'ACCEPTED',
                       'note': '本地接口模拟配置验收，不代表业务负责人签署',
                       'evidenceRefs': ['P37-local-configuration-readback']} for row in eligible]})

applied = s.steps['readiness.accept-v1']
if 'readiness.applied-report' not in s.steps:
    report = request('GET', '/' + applied['id'] + '/readiness')
    assert report['businessWriteCount'] == 0 and report['businessAcceptanceState'] == 'UNVERIFIED'
    assert any(row['stage'] == 'CONFIGURATION_ACCEPTED' for row in report['capabilities'])
    assert report['appliedVersion']['blueprintId'] == applied['id']
    s.steps['readiness.applied-report'] = report; s.persist()

candidate = once('save-v2', 'POST', '/draft', {
    'factoryName': applied['factoryName'], 'idempotencyKey': 'p37-readiness-save-v2',
    'expectedRowVersion': applied['rowVersion'], 'payload': applied['sourcePayload']})
current = request('GET', '/current')
report = request('GET', '/' + current['id'] + '/readiness')
assert current['id'] == candidate['id'] and current['lifecycleState'] == 'DRAFT'
assert report['appliedVersion']['blueprintId'] == applied['id']
assert report['appliedVersion']['candidateDiffers'] is True
assert report['businessAcceptanceState'] == 'UNVERIFIED' and report['businessWriteCount'] == 0
assert all(row['stage'] != 'CONFIGURATION_ACCEPTED' for row in report['capabilities'] if row['stage'] != 'NOT_APPLICABLE')
assert report['currentRuntime'], 'Existing factory runtime must not disappear for a new draft'
assert before == s.snapshot(), 'Configuration preview/acceptance changed business history'
s.save('readiness-acceptance.json', {'factoryId': s.FACTORY, 'applied': s.steps['readiness.applied-report'],
       'candidate': report, 'businessHistoryUnchanged': True, 'productionTouched': False,
       'businessSignature': 'UNVERIFIED', 'simulatedConfigurationAcceptance': True})
print(json.dumps({'status': 'PASS', 'appliedVersion': applied['version'], 'candidateVersion': current['version'],
                  'runtimeProducts': len(report['currentRuntime']), 'businessHistoryUnchanged': True}))
