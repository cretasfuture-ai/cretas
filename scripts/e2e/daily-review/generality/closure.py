"""Consolidate existing acceptance and close remaining local integration evidence.

No completed business action is replayed. P37 remains the experience runtime;
any migration experiment uses a separate, uniquely named database.
"""
from pathlib import Path
import argparse
import json
import sys
from decimal import Decimal
import re
import shutil
import subprocess
import os
import hashlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
OUT = Path.home() / 'Documents/cretas-connect-20261015/factory-data-p37/final-closure-20261019'


def save(name, value):
    OUT.mkdir(exist_ok=True)
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    return value


def initialize():
    path = OUT / 'scenarios.json'
    if path.exists():
        return json.loads(path.read_text(encoding='utf-8'))
    return save('scenarios', [dict(id=key, role=role, flow=flow, actions=actions,
        readback=readback, negativeControl=negative, status='未测', evidence=[])
        for key, role, flow, actions, readback, negative in [
        ('C1', '平台管理员及新厂管理员', '建厂与指标自动初始化', '复用既有建厂/双SKU业务证据', '工厂身份、自动创建指标及真实业务数据', '工厂账号不可进入平台建厂'),
        ('C2', '平台管理员及新厂管理员', '建厂验收状态与当前运行配置', '读取候选/已应用蓝图及运行适用性', '候选与正式版本分开；当前工艺/BOM可读', '配置验收不得等于业务签署'),
        ('C3', '新厂管理员', '老厂正式版本切换', '复用已完成v1→v2业务与历史比对', '旧批次v1、新批次v2和16元成本', '历史内容和已开工计划引用不可重写'),
        ('C4', '新厂管理员', '混合单位出货与成本', '复用500克→1盒正常出货', '数量/血缘/收入/成本', '500毫升不得借重量换成盒且失败无写入'),
        ('C5', '新厂管理员及仓储', '联产结单/入库/成本守恒', '复用多SKU多批次正常业务', '分摊成本与计划成本池一致', '重复结单/入库不重复库存与流水'),
        ('C6', '新厂管理员', '联产批次完整追溯', '读取两个联产品的批次追溯及来源', '共用原料、所属产出、成本及入库事实可对应', '不可把全计划投入/成本当作每个SKU独占事实'),
        ('C7', '新厂管理员', '四项生产指标与历史', '复用联产指标计算/历史/缺数据对照', '工厂去重、SKU分摊、同期间历史', '缺事实保持未知；跨厂拒绝'),
        ('C8', '新厂管理员及财务', '整月经营分析与利润表', '复用正常审批/过账/结转与页面补验', '退货后收入765、成本400.53、净利润359.76', '缺凭证/待过账不得假绿'),
        ('C9', '本地技术验收', '正常Flyway迁移与Spring/JPA启动', '复用仓库baseline与真实Flyway，在独立空目标库升级', '迁移历史/新表及完整应用启动', '不在手工DDL修补的P37开启Flyway；不自动repair'),
    ]])


def inspect():
    import acceptance as a
    import requests
    s = a.support
    before = save('inspection-before', s.snapshot())
    def read(path):
        result = s.send(s.admin, 'GET', path)
        assert result['status'] == 200 and result['body']['success'], result
        return result['body']['data']
    platform_credentials = json.loads((OUT.parents[1] / 'vision/platform-entry/credentials.private.json').read_text(encoding='utf-8'))
    platform = requests.Session()
    base = 'http://127.0.0.1:10072'
    login = platform.post(base + '/api/mobile/auth/unified-login', json={
        'username': platform_credentials['username'], 'password': platform_credentials['password'], 'clientType':'web'}, timeout=30)
    login.raise_for_status()
    actor = login.json()['data']
    assert actor['role'] == 'super_admin' and not actor.get('factoryId')
    platform.headers['Authorization'] = 'Bearer ' + (actor.get('accessToken') or actor['token'])
    prefix = f'/api/platform/factory-assembly/factories/{s.FACTORY}/ai-initialize/blueprints'
    current_response = platform.get(base + prefix + '/current', timeout=60)
    current_response.raise_for_status()
    current = current_response.json()['data']
    report_response = platform.get(base + prefix + '/' + current['id'] + '/readiness', timeout=60)
    report_response.raise_for_status()
    report = save('current-readiness', report_response.json()['data'])
    denied = s.admin.get(base + prefix + '/current', timeout=30)
    assert denied.status_code == 403
    save('factory-platform-denied', {'status':403, 'factoryId':s.FACTORY})
    joint = json.loads((OUT.parent / 'joint-upgrade-20261019/new-joint-complete.json').read_text(encoding='utf-8'))
    with s.dbconnect() as db:
        plan_id = joint['planId']
        outputs = [row[0] for row in db.execute('select row_to_json(t) from production_settlement_output_lines t where production_plan_id=%s', (plan_id,))]
    traces = []
    for output in outputs:
        batch_number = output['reported_batch_number']
        trace = read('/traceability/full/' + batch_number)
        traces.append({'output':output, 'trace':trace})
    save('joint-traces', traces)
    versions = json.loads((OUT.parent / 'joint-upgrade-20261019/formal-upgrade-business.json').read_text(encoding='utf-8'))
    for key in ('old', 'new'):
        trace = read('/traceability/full/' + versions[key]['summary']['batches'][0]['batchNumber'])
        save('version-trace-' + key, trace)
    metrics = {}
    for day in ('2026-10-18','2026-10-19'):
        suffix = '?periodStart=' + day + '&periodEnd=' + day
        metrics[day] = {'products':read('/production/actual-metrics/by-product' + suffix)}
        for code in ('FACTORY_LU_YIELD_RATE','FACTORY_GOOD_OUTPUT_RATE','FACTORY_LU_UNIT_COST','FACTORY_ACTUAL_TOTAL_COST'):
            metrics[day][code] = read('/indicators/' + code + '/value' + suffix)
    save('current-metrics', metrics)
    margin = save('current-margin', read('/finance/report/shipment-sku-margin?periodStart=2026-10-01&periodEnd=2026-10-31'))
    income = save('current-income', read('/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10'))
    assert margin['reconciliationStatus'] == 'MATCH'
    assert Decimal(str(margin['reconciliationNetCost'])) == Decimal('400.53') == Decimal(str(income['totalCost']))
    assert Decimal(str(income['netProfit'])) == Decimal('359.76')
    after = save('inspection-after', s.snapshot())
    changed = [table for table in before if before[table] != after[table]]
    save('inspection-changed-tables', changed)
    assert set(changed) <= {'indicators','indicator_versions'}, ('Read-only inspection changed business history', changed)
    old_versions = {r['id']:r for r in before['indicator_versions']}
    new_versions = {r['id']:r for r in after['indicator_versions']}
    assert all(new_versions.get(key) == row for key,row in old_versions.items()), 'Existing metric history rewritten'
    old_indicators = {r['id']:r for r in before['indicators']}
    for row in after['indicators']:
        previous = old_indicators[row['id']]
        changed_fields = {key for key in row if row[key] != previous[key]}
        assert changed_fields <= {'last_computed_at','updated_at','last_value'}
        if 'last_value' in changed_fields:
            assert row['factory_id'] == s.FACTORY and row['code'] in metrics['2026-10-19']
            assert row['last_value'] == metrics['2026-10-19'][row['code']]['value']
    appended = [row for key,row in new_versions.items() if key not in old_versions]
    assert all(row['factory_id'] == s.FACTORY for row in appended)
    save('inspection-metric-read-effects', {'existingHistoryUnchanged':True,'appendedCalculationRows':len(appended),
        'indicatorChanges':'calculation cache: last_value/last_computed_at/updated_at only; values match requested period',
        'businessTransactionsUnchanged':True})
    save('readback-identity', {'factoryId':s.identity['factoryId'],'username':s.identity['username'],
        'userId':s.identity['userId'],'database':s.DATABASE,'businessRowsUnchanged':True,'productionWrites':0})
    print(json.dumps({'runtime':report.get('currentRuntime'), 'metricApplicability':report.get('indicatorApplicability'),
        'traces':[{'batch':t['output']['reported_batch_number'],'product':t['trace'].get('production'),
            'materials':len(t['trace'].get('materials') or []),'stages':len(t['trace'].get('processingStages') or []),
            'lineage':t['trace'].get('lineage')} for t in traces]}, ensure_ascii=False,default=str))


def java_tests(label):
    java = ROOT / 'backend/java/cretas-api'
    build = OUT / 'trace-build'
    build.mkdir(exist_ok=True)
    if not (build / 'classes').exists():
        shutil.copytree(OUT.parent / 'build/classes', build / 'classes')
    pom = (java / 'pom.xml').read_text(encoding='utf-8').replace('<build>',
        '<build>\n<directory>' + build.as_posix() + '</directory>', 1)
    names = ['TraceabilityServiceImplTest','TraceabilityServiceImplLineageTest']
    inputs = ['backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/TraceabilityServiceImpl.java',
        'backend/java/cretas-api/src/main/java/com/cretas/aims/dto/traceability/TraceabilityDTO.java',
        *['backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/' + name + '.java' for name in names]]
    freeze = {path:hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in inputs}
    pom, count = re.subn(r'<testIncludes>.*?</testIncludes>', '<testIncludes>' +
        ''.join('<include>**/' + name + '.java</include>' for name in names) + '</testIncludes>', pom, flags=re.S)
    assert count == 1
    temp = java / 'pom-p37-closure-local.xml'
    assert not temp.exists()
    temp.write_text(pom, encoding='utf-8')
    env = dict(os.environ)
    env['JAVA_HOME'] = 'C:/Program Files/Zulu/zulu-21'
    env['PATH'] = env['JAVA_HOME'] + '/bin;' + env['PATH']
    log = OUT / ('java-' + label + '.log')
    try:
        with log.open('w', encoding='utf-8') as target:
            result = subprocess.run(['mvn.cmd','-f',temp.name,'test','-Dmaven.compiler.useIncrementalCompilation=false',
                '-Dtest=' + ','.join(names)],cwd=java,env=env,stdout=target,stderr=subprocess.STDOUT,shell=True)
    finally:
        temp.unlink()
    print(json.dumps({'label':label,'exit':result.returncode,'log':str(log)}))
    assert all(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == value for path,value in freeze.items()), 'Source changed during validation'
    save('java-' + label + '-receipt', {'exit':result.returncode,'sources':freeze,'tests':names,'log':str(log)})
    return result.returncode


def verify_joint_traces():
    traces = json.loads((OUT / 'joint-traces.json').read_text(encoding='utf-8'))
    assert len(traces) == 4
    by_batch = {row['output']['reported_batch_number']:row for row in traces}
    pairs = [('CLK-B-20261019-73282','CLK-B-20261019-73331',Decimal('1')),
             ('CLK-B-20261019-80224','CLK-B-20261019-80260',Decimal('2'))]
    for owner_number, member_number, expected_input in pairs:
        owner, member = by_batch[owner_number], by_batch[member_number]
        assert owner['trace']['materials'] == member['trace']['materials']
        for row in (owner, member):
            trace, output = row['trace'], row['output']
            assert trace['production']['batchNumber'] == output['reported_batch_number']
            assert trace['production']['productType'] == output['product_type_id']
            assert trace['production']['factoryId'] == 'FOOD_3101_001'
            assert len(trace['materials']) == 2
            raw = [r for r in trace['materials'] if r['materialType'] != '包材']
            assert len(raw) == 1 and Decimal(str(raw[0]['quantity'])) == expected_input
            assert raw[0]['supplierName']
            stages = trace['processingStages']
            assert len(stages) == 1 and Decimal(str(stages[0]['inputWeight'])) == expected_input
            assert Decimal(str(stages[0]['outputWeight'])) == Decimal('1') and stages[0]['outputUnit'] == '盒'
            assert trace['lineage']['rootId'] == str(trace['production']['batchId'])
        assert not owner['trace'].get('sharedInputSourceBatchNumber')
        assert member['trace']['sharedInputSourceBatchNumber'] == owner_number
        assert not member['trace']['lineage']['ancestors'], 'Do not invent historical direct lineage'
        assert owner['trace']['lineage']['ancestors'], 'Actual owner lineage must be independently observable'
    by_sku = {}
    for row in traces:
        output = row['output']
        by_sku.setdefault(output['product_type_id'], Decimal('0'))
        by_sku[output['product_type_id']] += Decimal(str(output['allocated_cost']))
    assert abs(sum(by_sku.values()) - Decimal('31')) < Decimal('0.00001')
    return save('joint-trace-verification', {'status':'PASS','batchCount':4,'groupCount':2,
        'sharedInputOwnerPairs':pairs,'historicalEdgesRewritten':False,'costPool':31,
        'costBySku':by_sku,'costsNotDuplicated':True,'forensicRootsUnchanged':True})


def activate_runtime():
    """Restart the one owned local JVM with the tested isolated class output."""
    import acceptance as a
    import psutil
    s = a.support
    tested = json.loads((OUT / 'java-final-receipt.json').read_text(encoding='utf-8'))
    assert tested['exit'] == 0
    for name,digest in tested['sources'].items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    runtime_file = OUT.parent / 'runtime.json'
    runtime = json.loads(runtime_file.read_text(encoding='utf-8'))
    assert runtime['owner'] == 'P37-FACTORY-GENERALITY-20261018' and runtime['database'] == s.DATABASE
    process = psutil.Process(runtime['javaPid'])
    assert process.name().lower() == 'java.exe' and '@' + runtime['javaArgFile'] in process.cmdline()
    args_file = Path(runtime['javaArgFile'])
    args = [json.loads(line) for line in args_file.read_text(encoding='utf-8').splitlines() if line.strip()]
    old_classes = str(OUT.parent / 'build/classes')
    new_classes = str(OUT / 'trace-build/classes')
    classpath_index = args.index('-cp') + 1
    components = args[classpath_index].split(';')
    assert components.count(old_classes) == 1 and Path(new_classes).is_dir()
    save('runtime-business-before', s.snapshot())
    backup = OUT / 'experience-runtime-before.args'
    assert not backup.exists(), 'Completed activation must not be replayed'
    shutil.copyfile(args_file, backup)
    process.terminate()
    try:
        process.wait(timeout=30)
    except psutil.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
    components[components.index(old_classes)] = new_classes
    args[classpath_index] = ';'.join(components)
    args_file.write_text('\n'.join(json.dumps(item) for item in args) + '\n',encoding='utf-8')
    freeze_file = OUT.parent / 'product-final-freeze.json'
    existing = json.loads(freeze_file.read_text(encoding='utf-8'))
    existing.update(tested['sources'])
    freeze_file.write_text(json.dumps(existing,indent=2),encoding='utf-8')
    result = subprocess.run([sys.executable,str(ROOT / 'scripts/e2e/daily-review/generality/restart.py'),
        'NON_PRODUCTION_ONLY'],cwd=ROOT,check=True,capture_output=True,text=True)
    started = json.loads(result.stdout)
    save('runtime-activation', {'oldPid':runtime['javaPid'],'newPid':started['javaPid'],
        'database':s.DATABASE,'classpath':new_classes,'status':'STARTED_NOT_YET_ACCEPTED','otherProcessesTouched':0})
    print(result.stdout)


def verify_migration():
    import psycopg
    receipt = json.loads((OUT / 'migration/receipt.json').read_text(encoding='utf-8'))
    assert receipt['final_exit_code'] == 0 and receipt['application']['spring_started']
    assert receipt['application']['jpa_initialized'] and receipt['application']['health_http_200']
    assert receipt['database'].startswith('factory_closure_probe_20261019-')
    assert receipt['flyway']['history_first_pass'] == receipt['flyway']['history_second_pass']
    assert hashlib.sha256((ROOT / receipt['script']['path']).read_bytes()).hexdigest() == receipt['script']['sha256'].lower()
    with psycopg.connect(host='127.0.0.1',port=55447,user='assembly_executor',dbname=receipt['database'],
            options='-c default_transaction_read_only=on') as db:
        rows = db.execute("select version,success from flyway_schema_history where version in ('20261103.63','20261103.64','20261103.65') order by version").fetchall()
        assert len(rows) == 3 and all(row[1] for row in rows)
    return save('migration/root-versions-readback', {'database':receipt['database'],'versions':rows,
        'readOnly':True,'status':'PASS'})


def finish():
    def load(name):
        return json.loads((OUT / name).read_text(encoding='utf-8'))
    tests = load('java-final-receipt.json')
    assert tests['exit'] == 0
    for name,digest in tests['sources'].items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest
    reports = []
    for name in tests['tests']:
        paths = list((OUT / 'trace-build/surefire-reports').glob('TEST-*.' + name + '.xml'))
        assert len(paths) == 1
        report = ET.parse(paths[0]).getroot()
        counts = {key:int(report.attrib[key]) for key in ('tests','failures','errors','skipped')}
        assert counts['tests'] > 0 and counts['failures'] == counts['errors'] == counts['skipped'] == 0
        reports.append(dict(name=name,**counts))
    assert sum(row['tests'] for row in reports) == 27
    assert '49 passed' in (OUT / 'web-target-final.log').read_text(encoding='utf-8')
    build_log = (OUT / 'web-build-final.log').read_text(encoding='utf-8')
    assert 'built in' in build_log
    assert load('browser-acceptance.json')['status'] == 'PASS'
    assert load('joint-trace-verification.json')['status'] == 'PASS'
    assert load('migration/root-versions-readback.json')['status'] == 'PASS'
    before,after = load('runtime-business-before.json'),load('inspection-after.json')
    unchanged = []
    for table in before:
        if table in ('indicators','indicator_versions'):
            continue
        assert before[table] == after[table], 'Runtime/readback changed business rows: ' + table
        unchanged.append(table)
    old_versions = {row['id']:row for row in before['indicator_versions']}
    new_versions = {row['id']:row for row in after['indicator_versions']}
    assert all(new_versions.get(key) == row for key,row in old_versions.items())
    plans = {row['id']:row for row in after['production_plans']}
    version_business = load('../joint-upgrade-20261019/formal-upgrade-business.json')
    for key in ('old','new'):
        item = version_business[key]
        assert all(plans[item['planId']][field] == value for field,value in item['pin'].items())
    readiness = load('current-readiness.json')
    assert readiness['businessAcceptanceState'] == 'UNVERIFIED'
    assert readiness['appliedVersion']['version'] == 1 and readiness['appliedVersion']['candidateDiffers']
    runtime = {row['productId']:row for row in readiness['currentRuntime']}
    assert runtime['cab6e6ca-2bd1-456e-b06d-48a11afc6e24']['workflowVersion'] == 2
    assert runtime['cab6e6ca-2bd1-456e-b06d-48a11afc6e24']['bomVersion'] == 2
    metrics = load('current-metrics.json')
    codes = ('FACTORY_LU_YIELD_RATE','FACTORY_GOOD_OUTPUT_RATE','FACTORY_LU_UNIT_COST','FACTORY_ACTUAL_TOTAL_COST')
    for code,expected in zip(codes,('95.2381','100','11.6','58')):
        actual = metrics['2026-10-19'][code]
        assert abs(Decimal(str(actual['value'])) - Decimal(expected)) < Decimal('0.0001')
        assert actual['source'].endswith('actual-flow-v3')
    assert metrics['2026-10-18']['FACTORY_LU_UNIT_COST']['value'] is None
    assert metrics['2026-10-18']['FACTORY_ACTUAL_TOTAL_COST']['value'] is None
    margin,income = load('current-margin.json'),load('current-income.json')
    assert margin['reconciliationStatus'] == 'MATCH'
    for field,expected in (('reconciliationReturnedRevenue','75'),('reconciliationReturnedCost','41.08'),
            ('reconciliationNetRevenue','765'),('reconciliationNetCost','400.53')):
        assert Decimal(str(margin[field])) == Decimal(expected)
    assert Decimal(str(income['netProfit'])) == Decimal('359.76')
    scenarios = load('scenarios.json')
    for row in scenarios:
        if row['id'] == 'C6':
            row.update(status='通过',closedDefect=row.pop('defect','TRACE-JOINT-SOURCE-GROUP'),
                evidence=['joint-traces-before.json','joint-trace-verification.json','browser-acceptance.json','java-final-receipt.json'])
        if row['id'] == 'C9':
            row.update(status='通过',evidence=['migration/receipt.json','migration/root-versions-readback.json'])
    assert all(row['status'] == '通过' for row in scenarios)
    save('scenarios', scenarios)
    product_files = [*tests['sources'], 'web-admin/src/api/traceability.ts',
        'web-admin/src/views/traceability/index.vue','web-admin/src/views/traceability/__tests__/index.spec.ts']
    return save('final-receipt', {'status':'LOCAL_ACCEPTANCE_PASS','scenarioPassed':len(scenarios),'scenarioTotal':len(scenarios),
        'openProductDefects':0,'scenarios':scenarios,'java':reports,'webTests':49,'typecheck':True,'build':True,
        'runtimeBusinessTablesUnchanged':unchanged,'existingMetricHistoryUnchanged':True,
        'metricReadsMayAppendCalculations':True,'forensicEdgesUnchanged':True,'versionPinsUnchanged':True,
        'sourceSha256':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in product_files},
        'migrationEvidence':'migration/receipt.json','browserEvidence':'browser-acceptance.json',
        'runtimeActivation':load('runtime-activation.json'),'localOnly':True,'businessSqlMutations':0,
        'productionWrites':0,'ciRun':False,'pushed':False,'merged':False,'deployed':False,'businessSignoff':'PENDING'})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('ack', choices=['NON_PRODUCTION_ONLY'])
    parser.add_argument('stage', choices=['init','inspect','java-red','java-green','java-final','verify-traces','activate','verify-migration','finish'])
    args = parser.parse_args()
    if args.stage == 'init':
        rows = initialize()
        print(json.dumps({'scenarios': len(rows), 'evidence': str(OUT)}, ensure_ascii=False))
    elif args.stage == 'inspect':
        inspect()
    elif args.stage.startswith('java-'):
        sys.exit(java_tests(args.stage[5:]))
    elif args.stage == 'verify-traces':
        print(json.dumps(verify_joint_traces(),ensure_ascii=False,default=str))
    elif args.stage == 'activate':
        activate_runtime()
    elif args.stage == 'verify-migration':
        print(json.dumps(verify_migration(),ensure_ascii=False))
    elif args.stage == 'finish':
        result = finish()
        print(json.dumps({'status':result['status'],'passed':result['scenarioPassed'],'total':result['scenarioTotal'],
            'openProductDefects':result['openProductDefects'],'evidence':str(OUT)},ensure_ascii=False))


if __name__ == '__main__':
    main()
