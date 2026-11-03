"""Isolated WSL Java authority environment. Never changes the shared service/DB.

Run with WSL Python as root, --inspect or --prepare/--start. Credentials stay in
root-only files. Only the selected test factory and users are copied, not other
factory data. A verified official Java artifact is supplied by the caller.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import time

ROOT = Path('/opt/cretas-blacklake-cost-test-20261103')
DB = 'blacklake_cost_auth_20261103'
ROLE = 'blacklake_cost_auth_20261103'
FACTORY = 'BLACKLAKE_POC_TEST'


def run(command, data=None):
    value = subprocess.run(command, input=data, capture_output=True)
    if value.returncode:
        if ROOT.exists():
            # psql may include a COPY row or SQL literal in stderr. Do not log
            # authentication material, even in this private test directory.
            (ROOT/'last-command-error.log').write_text('ISOLATED_TEST_COMMAND_FAILED exit='+str(value.returncode))
        raise RuntimeError('ISOLATED_TEST_COMMAND_FAILED')
    return value.stdout


def sql(database, query):
    return run(['runuser','-u','postgres','--','psql','-X','-q','-v','ON_ERROR_STOP=1','-p','15432','-d',database,'-At'], query.encode())


def inspect():
    result={'sourceDatabase':'cretas_testenv','testPort':11210,
        'sourceFlyway':sql('cretas_testenv','SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;').decode().strip(),
        'sourceFactoryRows':int(sql('cretas_testenv',f"SELECT count(*) FROM factories WHERE id='{FACTORY}';")),
        'sourceUserRows':int(sql('cretas_testenv',f"SELECT count(*) FROM users WHERE factory_id='{FACTORY}';")),
        'exists':ROOT.exists()}
    if (ROOT/'receipt.json').exists():
        result['isolated']={
            'database':DB,
            'flyway':sql(DB,'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;').decode().strip(),
            'failedMigrations':int(sql(DB,'SELECT count(*) FROM flyway_schema_history WHERE NOT success;')),
            'factoryRows':int(sql(DB,'SELECT count(*) FROM factories;')),
            'userRows':int(sql(DB,'SELECT count(*) FROM users;'))}
    print(json.dumps(result))


def prepare(jar, expected_sha, build_commit):
    if os.geteuid()!=0 or not jar.is_file() or len(expected_sha)!=64:
        raise RuntimeError('VERIFIED_ROOT_TEST_ARTIFACT_REQUIRED')
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=expected_sha:
        raise RuntimeError('ARTIFACT_HASH_MISMATCH')
    manifest_path=jar.parent/'release-jar.manifest'
    manifest=dict(line.split('=',1) for line in manifest_path.read_text().splitlines() if '=' in line)
    if (manifest.get('format')!='cretas-release-jar-manifest-v1' or manifest.get('success')!='true'
            or manifest.get('jar_sha256')!=expected_sha or manifest.get('build_commit')!=build_commit):
        raise RuntimeError('MATCHING_RELEASE_MANIFEST_REQUIRED')
    recovering=ROOT.exists()
    if recovering and ((ROOT/'receipt.json').exists() or int(sql(DB,"SELECT count(*) FROM pg_tables WHERE schemaname='public';"))):
        raise RuntimeError('EXISTING_ISOLATED_ENVIRONMENT_PRESERVED')
    ROOT.mkdir(mode=0o700,exist_ok=True)
    password=secrets.token_urlsafe(32)
    if recovering:sql('postgres',f"ALTER ROLE {ROLE} PASSWORD '{password}';")
    else:
        sql('postgres',f"CREATE ROLE {ROLE} LOGIN PASSWORD '{password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;")
        sql('postgres',f'CREATE DATABASE {DB} OWNER {ROLE};')
    sql(DB,'CREATE EXTENSION IF NOT EXISTS vector;')
    schema=run(['runuser','-u','postgres','--','pg_dump','-p','15432','-d','cretas_testenv','--schema-only','--no-owner','--no-privileges'])
    schema=re.sub(r'^(CREATE EXTENSION|COMMENT ON EXTENSION)[^;]*;\s*','',schema.decode(),flags=re.MULTILINE)
    sql(DB, f'SET ROLE {ROLE};\n'+schema)
    copied={}
    for table, predicate in [('flyway_schema_history','true'),('factories',f"id='{FACTORY}'"),('users',f"factory_id='{FACTORY}'")]:
        content=sql('cretas_testenv',f'COPY (SELECT * FROM {table} WHERE {predicate}) TO STDOUT;')
        sql(DB, f'COPY {table} FROM STDIN;\n'+content.decode()+'\\.\n')
        copied[table]=int(sql(DB,f'SELECT count(*) FROM {table};'))
    values={}
    for line in Path('/opt/cretas-testenv/testenv.env').read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            k,v=line.split('=',1);values[k]=v.strip().strip('"').strip("'")
    if values.get('PYTHON_ENV')!='test' or values.get('DB_NAME')!='cretas_testenv' or values.get('DB_HOST')!='127.0.0.1':
        raise RuntimeError('SOURCE_TEST_ENVIRONMENT_REQUIRED')
    # Fresh env: no source API keys, remote providers or production destinations.
    env={'PATH':'/usr/bin:/bin','JAVA_HOME':'/usr/lib/jvm/java-21-openjdk-amd64',
         'DB_PASSWORD':password,'JWT_SECRET':values['JWT_SECRET'],
         'SMARTBI_DB_PASSWORD':password,'POSTGRES_SMARTBI_PASSWORD':password}
    target=ROOT/'backend.jar';target.write_bytes(jar.read_bytes());target.chmod(0o600)
    (ROOT/'release-jar.manifest').write_text(manifest_path.read_text())
    (ROOT/'runtime.json').write_text(json.dumps(env));(ROOT/'runtime.json').chmod(0o600)
    receipt={'buildCommit':build_commit,'sha256':expected_sha,'database':DB,'role':ROLE,'port':11210,
             'copied':copied,'sourceBusinessWrites':0,'sharedServiceModified':False,'isolated':True}
    (ROOT/'receipt.json').write_text(json.dumps(receipt,indent=2))
    print(json.dumps(receipt))


def start():
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',11210))==0:
            raise RuntimeError('TEST_PORT_ALREADY_OWNED')
    if (ROOT/'pid').exists():
        old=Path('/proc')/(ROOT/'pid').read_text().strip()
        if old.exists() and 'State:\tZ' not in (old/'status').read_text():
            raise RuntimeError('EXISTING_TEST_PROCESS_PRESERVED')
    env=json.loads((ROOT/'runtime.json').read_text())
    args=['/usr/lib/jvm/java-21-openjdk-amd64/bin/java','-Djava.net.preferIPv4Stack=true','-Xms256m','-Xmx1536m','-jar',str(ROOT/'backend.jar'),
          '--spring.profiles.active=pg','--server.address=127.0.0.1','--server.port=11210',
          f'--spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/{DB}?stringtype=unspecified',
          f'--spring.datasource.username={ROLE}','--spring.jpa.hibernate.ddl-auto=validate',
          '--spring.flyway.validate-on-migrate=true','--spring.flyway.out-of-order=true','--smartbi.postgres.enabled=false',
          '--python-smartbi.enabled=false','--python-error-analysis.enabled=false','--python-classifier.enabled=false',
          '--ai.multi-intent.llm-fallback-enabled=false','--cretas.active-learning.scheduler.enabled=false',
          '--spring.task.scheduling.enabled=false','--spring.jmx.enabled=false',
          '--logging.level.root=WARN','--logging.file.name='+str(ROOT/'java.log')]
    log=open(ROOT/'console.log','ab',buffering=0)
    process=subprocess.Popen(args,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    (ROOT/'pid').write_text(str(process.pid))
    print(json.dumps({'started':True,'pid':process.pid,'port':11210,'sharedServiceModified':False}))


def restart_owned():
    pid=int((ROOT/'pid').read_text())
    proc=Path('/proc')/str(pid)
    if proc.exists():
        if str(ROOT/'backend.jar').encode() not in (proc/'cmdline').read_bytes().split(b'\0'):
            raise RuntimeError('PROCESS_OWNER_IDENTITY_CHANGED')
        os.kill(pid,signal.SIGTERM)
        deadline=time.monotonic()+45
        while proc.exists() and 'State:\tZ' not in (proc/'status').read_text():
            if time.monotonic()>deadline:raise RuntimeError('OWNED_JAVA_SHUTDOWN_TIMEOUT')
            time.sleep(.25)
    start()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--inspect',action='store_true');parser.add_argument('--prepare',action='store_true');parser.add_argument('--start',action='store_true');parser.add_argument('--restart',action='store_true')
    parser.add_argument('--jar',type=Path);parser.add_argument('--sha');parser.add_argument('--commit')
    args=parser.parse_args()
    if args.inspect:inspect()
    elif args.prepare:prepare(args.jar,args.sha,args.commit)
    elif args.start:start()
    elif args.restart:restart_owned()
