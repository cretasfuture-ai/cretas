"""Install the cached pgvector PG17 extension into this new, unused private slot.

No database/container is stopped. The source image container is never started;
only its public extension binaries are copied. Existing application data forbids installation.
"""
import json
import secrets
from pathlib import Path
from orchestrator import CONTAINERS, atomic_json, OrchestratorError
SOURCE_DIGEST = 'pgvector/pgvector@sha256:d2ef61f42ef767baa5a1475393303cc235bcd92febd9d7014eddb48b41f3bad0'

def installed_hashes(slot):
    raw = slot._checked(['docker','exec',CONTAINERS['pg'],'sh','-c',
        'sha256sum /usr/lib/postgresql/17/lib/vector.so /usr/share/postgresql/17/extension/vector*']).stdout
    return {line.split(maxsplit=1)[1].strip():line.split()[0] for line in raw.splitlines()}

def verify_receipt(slot, dependency):
    if dependency.get('sourceDigest') != SOURCE_DIGEST or not dependency.get('fileHashes'):
        raise OrchestratorError('reviewed vector source/full file proof is missing')
    if dependency['fileHashes'] != installed_hashes(slot): raise OrchestratorError('installed vector extension file set/content changed')

def install(slot):
    registered = Path(slot.cfg['identity_path']).exists()
    before = slot._verify_identity() if registered else slot._physical_identity()
    if slot._inspect_running('java'): raise OrchestratorError('extension setup requires unused/stopped sandbox Java')
    if slot._pg("SELECT count(*) FROM information_schema.tables WHERE table_schema='public';").strip() != '0':
        raise OrchestratorError('extension setup is only allowed before the first baseline restore')
    cache = Path(slot.cfg['identity_path']).parent/('vector-package-'+secrets.token_hex(8))
    cache.mkdir(exist_ok=False)
    name = 'cretas-f006-vector-package-' + secrets.token_hex(6)
    image = slot._json(['docker','image','inspect','--format={{json .}}',SOURCE_DIGEST])
    if SOURCE_DIGEST not in image.get('RepoDigests',[]): raise OrchestratorError('reviewed vector image digest is unavailable')
    created = slot._checked(['docker','create','--name',name,'--network','none','--read-only','--tmpfs','/var/lib/postgresql/data:rw,nosuid,noexec,size=16m',
        '--label','cretas.sandbox.scope=F006','--label','cretas.sandbox.instance='+slot.cfg['instance_id'],'--label','cretas.sandbox.role=dependency-source',image['Id']]).stdout.strip()
    try:
        slot._checked(['docker','cp',created+':/usr/lib/postgresql/17/lib/vector.so',str(cache/'vector.so')])
        slot._checked(['docker','cp',created+':/usr/share/postgresql/17/extension',str(cache/'extension')])
        package_dir = cache/'extension'
        files = [p for p in package_dir.glob('vector*') if p.is_file()]
        if not (package_dir/'vector.control').is_file() or len(files) < 2: raise OrchestratorError('cached vector extension package is incomplete')
        slot._checked(['docker','cp',str(cache/'vector.so'),CONTAINERS['pg']+':/usr/lib/postgresql/17/lib/vector.so'])
        for file in files: slot._checked(['docker','cp',str(file),CONTAINERS['pg']+':/usr/share/postgresql/17/extension/'+file.name])
        version = slot._pg("SELECT default_version FROM pg_available_extensions WHERE name='vector';").strip()
        if not version: raise OrchestratorError('real vector extension is not available')
        # Load/test the actual compiled extension, then remove only its SQL objects.
        slot._pg('BEGIN; CREATE EXTENSION vector WITH SCHEMA public; SELECT vector_dims(\'[1,2,3]\'::vector); ROLLBACK;')
        hashes = slot._checked(['docker','exec',CONTAINERS['pg'],'sha256sum','/usr/lib/postgresql/17/lib/vector.so','/usr/share/postgresql/17/extension/vector.control']).stdout
        dependency = {'sourceImage':image['Id'],'sourceDigest':SOURCE_DIGEST,'version':version,'installedHashes':hashes.strip(),'fileHashes':installed_hashes(slot)}
        atomic_json(Path(slot.cfg['identity_path']).with_name('vector-dependency.private.json'),dependency)
        after = slot._physical_identity()
        if {k:v for k,v in after.items() if k != 'vectorDependency'} != {k:v for k,v in before.items() if k != 'vectorDependency'}:
            raise OrchestratorError('physical slot changed during extension setup')
        if registered: atomic_json(slot.cfg['identity_path'],after)
        return dependency
    finally:
        source = slot._json(['docker','inspect','--format={{json .}}',created])
        if source['State']['Status'] != 'created' or source['State']['Running'] or source['Config']['Labels'].get('cretas.sandbox.instance') != slot.cfg['instance_id']:
            raise OrchestratorError('temporary package container identity changed; preserved for review')
        slot._checked(['docker','rm',created])
