#!/usr/bin/env python3
"""Create private local sandbox settings. Never reads/copies production secrets."""
import argparse
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import uuid
from pathlib import Path
from orchestrator import CONTAINERS, VOLUMES, NETWORK, atomic_json, JDBC_URL
from node_credentials import issue_node_token

def create(root, jar, baseline, fingerprint):
    root = root.resolve()
    private_root = Path('C:/Users/Steve/Documents/F006演示-20261105').resolve()
    if root == private_root or not root.is_relative_to(private_root) or any((p/'.git').exists() for p in [root,*root.parents]):
        raise RuntimeError('private configuration must stay outside Git under the F006 private task directory')
    if not root.is_absolute() or root.exists(): raise RuntimeError('private root must be a new absolute directory')
    if not jar.is_file() or not (baseline/'manifest.json').is_file(): raise RuntimeError('JAR and verified baseline are required')
    if len(fingerprint) != 64 or any(c not in '0123456789abcdef' for c in fingerprint): raise RuntimeError('production signing-key fingerprint is required, never the key')
    root.mkdir(mode=0o700)
    if os.name == 'nt':
        user = subprocess.run(['whoami'],capture_output=True,text=True,check=True).stdout.strip()
        subprocess.run(['icacls',str(root),'/inheritance:r','/grant:r',user+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],capture_output=True,check=True)
    private_jar = root/'app.jar'; shutil.copyfile(jar,private_jar)
    pg_password = secrets.token_urlsafe(48); signing_key = secrets.token_urlsafe(64); control_key = secrets.token_urlsafe(48)
    token = issue_node_token(signing_key)
    properties = {
        'server.port':'10040','server.address':'0.0.0.0','spring.profiles.include':'pg','spring.datasource.url':JDBC_URL,
        'spring.datasource.username':'cretas_sandbox','spring.datasource.password':pg_password,
        'spring.datasource.driver-class-name':'org.postgresql.Driver', 'spring.jpa.properties.hibernate.dialect':'org.hibernate.dialect.PostgreSQLDialect',
        'camera.save.path':'/opt/app/data/camera-images',
        'spring.jpa.hibernate.ddl-auto':'validate','spring.flyway.enabled':'false','spring.jpa.show-sql':'false',
        'spring.data.redis.host':'redis','spring.data.redis.port':'6379','spring.data.redis.database':'0',
        'spring.redis.host':'redis','spring.redis.port':'6379','spring.redis.database':'0',
        'cretas.jwt.secret':signing_key,'cretas.demo-sandbox.node.enabled':'true','cretas.demo.sandbox.enabled':'false',
        'cretas.demo.sandbox.production-jwt-sha256':fingerprint,'aliyun.oss.enabled':'false','smartbi.postgres.enabled':'false',
        'cretas.ai.service.url':'http://127.0.0.1:9','cretas.python.base-url':'http://127.0.0.1:9',
        'python-smartbi.url':'http://127.0.0.1:9','grpc.client.embedding-service.address':'static://127.0.0.1:9',
        'logging.file.name':'/opt/app/data/sandbox.log','logging.file.path':'/opt/app/data/logs','spring.lifecycle.timeout-per-shutdown-phase':'20s',
    }
    property_file = root/'application-sandbox.properties'
    property_file.write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n',encoding='utf-8')
    ops = {'db_name':'f006_demo_sandbox','pg_user':'cretas_sandbox','pg_password':pg_password,'network':NETWORK,
        'containers':CONTAINERS,'volumes':VOLUMES,'instance_id':str(uuid.uuid4()),'identity_path':str(root/'physical-identity.private.json'),
        'baseline_dir':str(baseline),'java_jar':str(private_jar),'java_properties':str(property_file),'baseline_proof_path':str(root/'baseline-proof.private.json')}
    ops_file = root/'orchestrator.private.json'; atomic_json(ops_file,ops)
    runtime = {'listen_host':'127.0.0.1','listen_port':10041,'allowed_actor_id':1309,'strict_security':True,
        'control_key':control_key,'sandbox_bearer_token':token,'java_base_url':'http://127.0.0.1:10040','sandbox_base_url':'http://127.0.0.1:10040',
        'sandbox_db_name':'f006_demo_sandbox','pg_access_mode':'DOCKER_EXEC_ONLY','sandbox_redis_port':6387,
        'manifest_path':str(root/'sessions.private.json'),'sandbox_file_root':str(root/'files'),'orchestrator_config_path':str(ops_file)}
    atomic_json(root/'runtime.private.json',runtime)
    return {'root':str(root),'jarSha256':hashlib.sha256(private_jar.read_bytes()).hexdigest()}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--root',required=True); parser.add_argument('--jar',required=True)
    parser.add_argument('--baseline',required=True); parser.add_argument('--production-fingerprint-file',required=True); args = parser.parse_args()
    fingerprint = json.loads(Path(args.production_fingerprint_file).read_text(encoding='utf-8'))['sha256']
    print(json.dumps(create(Path(args.root).resolve(),Path(args.jar).resolve(),Path(args.baseline).resolve(),fingerprint)))
