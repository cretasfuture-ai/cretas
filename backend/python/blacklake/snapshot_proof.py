"""Read-only independent bindings for the replay source and installed artifact."""
import hashlib
import json
from pathlib import Path
import subprocess

import psycopg2

from .full_archive import Archive, digest
from .full_verify import offline_context, validate_retained_run
from .secrets import load_local_secret


def snapshot_proof(config):
    directory,store=offline_context()
    try:
        run=load_local_secret(directory/'full-run.dpapi')
        report=load_local_secret(directory/'full-report.dpapi')
        if (*store.scope,run['run_id'])!=(config['tenant_id'],config['source_factory_id'],config['run_id']):
            raise RuntimeError('REPLAY_SOURCE_SCOPE_MISMATCH')
        if run['source']!=digest(store.scope):raise RuntimeError('REPLAY_SOURCE_BINDING_MISMATCH')
        validate_retained_run(run,report,Archive(None,store,{},run['run_id']).audit())
        with store.transaction() as cur:
            cur.execute("SELECT s.resource,s.external_id,s.content_hash,r.payload FROM blacklake.full_snapshot s JOIN blacklake.raw_record r ON r.tenant_id=s.tenant_id AND r.source_factory_id=s.source_factory_id AND r.resource='archive:'||s.resource AND r.external_id=s.external_id AND r.content_hash=s.content_hash WHERE s.run_id=%s",(run['run_id'],))
            source=cur.fetchall()
        with psycopg2.connect(config['dsn']) as connection:
            connection.set_session(readonly=True)
            with connection.cursor() as cur:
                cur.execute("SELECT set_config('blacklake.tenant_id',%s,true),set_config('blacklake.source_factory_id',%s,true)",store.scope)
                cur.execute('SELECT resource,external_id,content_hash,payload FROM blacklake_bi.record WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s',(*store.scope,run['run_id']))
                target=cur.fetchall()
        if not source or any(digest(p)!=h for _,_,h,p in source+target):raise RuntimeError('REPLAY_CONTENT_HASH_FAILED')
        source_digest=digest(sorted((r,i,h) for r,i,h,_ in source))
        target_digest=digest(sorted((r,i,h) for r,i,h,_ in target))
        if source_digest!=target_digest or len(source)!=len(target):raise RuntimeError('REPLAY_SOURCE_TARGET_MISMATCH')
        return {'tenantId':store.scope[0],'sourceFactoryId':store.scope[1],'runId':run['run_id'],
                'sourceRecordDigest':source_digest,'targetRecordDigest':target_digest,'rows':len(source),
                'retainedRunValidationPassed':True,'originalArchiveContentMatchesTarget':True}
    finally:store.connection.close()


def artifact_proof():
    root=Path(__file__).resolve().parents[3]
    # current/ is a shared moving cache. Bind the installed bytes to the
    # independently retained successful release receipt of the selected JAR.
    receipt_path=Path.home()/'.cache/cretas/release-reports/cretas-1793715027-5639.json'
    receipt=json.loads(receipt_path.read_text())
    if (receipt.get('format')!='cretas-unified-release-report-v1' or receipt.get('exit_code')!=0
            or receipt.get('status')!='deployed' or receipt.get('main_guard',{}).get('status')!='passed'):
        raise RuntimeError('TRUSTED_ARTIFACT_RELEASE_RECEIPT_REQUIRED')
    manifest=receipt['java_manifest'];sha=manifest['sha256']
    if receipt['head_sha']!=manifest['build_commit']:raise RuntimeError('ARTIFACT_RELEASE_HEAD_MISMATCH')
    tree=subprocess.check_output(['git','rev-parse',manifest['build_commit']+':backend/java/cretas-api'],cwd=root,text=True).strip()
    subprocess.run(['git','merge-base','--is-ancestor',manifest['build_commit'],'origin/main'],cwd=root,check=True,capture_output=True)
    if tree!=manifest['tree']:raise RuntimeError('ARTIFACT_SOURCE_TREE_MISMATCH')
    installed=subprocess.check_output(['wsl','-d','Ubuntu-22.04','--','sha256sum','/opt/cretas-blacklake-cost-test-20261103/backend.jar'],text=True).split()[0]
    if installed!=sha:raise RuntimeError('INSTALLED_ARTIFACT_HASH_MISMATCH')
    return {'buildCommit':manifest['build_commit'],'backendTree':tree,'jarSha256':sha,
            'retainedReleaseReceiptVerified':True,'buildIsMainAncestor':True,'installedJarHashMatches':True,
            'releaseReceipt':receipt_path.name,'releaseReceiptSha256':hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
            'note':'Existing release receipt predates this task; this task did not deploy production'}
