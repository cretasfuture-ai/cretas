"""Scoped static release. Default read-only; production writes need explicit flags."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

# Assets precede pages that reference their new cache-busting version.
FILES = ['css/v6.css', 'js/v6.js', 'js/v6-intro.js', 'js/v6-motion.js',
         'js/v6-premium.js', 'index.html', 'demo.html', 'solutions-factory.html',
         'solutions-logistics.html', 'solutions-ai.html', 'solutions-custom.html',
         'smartbi.html', 'cockpit.html', 'sitemap.xml',
         'solutions-restaurant.html', 'restaurantbi-example/index.html']
HOST = 'root@139.196.165.140'
ROOT = '/www/wwwroot/showcase/cretaceousfuture'
REPO = Path(__file__).resolve().parents[2]
# Reviewed live drift; complete pre-change sources retained in local evidence.
REVIEWED_LIVE_BASELINES = {
    'solutions-restaurant.html': '50940d1b25ebb012b683be67a1a6e4f4da7ece8a2df0ff46471b7d2782d2baa1',
    'js/v6.js': 'fd8b704c68a384d9f81b30460ea46cba7c772d2d9f81d8a3115781599e7f8fe3',
    'js/v6-premium.js': '4cc153b0004c8e66ddc1d01d84ada25373d8caab832b3ff52bf1272ced49adf7',
}

REMOTE = '''
import base64, fcntl, hashlib, json, os, pathlib, re, sys, tempfile
data=json.load(sys.stdin)
root=pathlib.Path('/www/wwwroot/showcase/cretaceousfuture').resolve()
lock=os.fdopen(os.open('/tmp/cretas-showcase-publish.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600),'r+')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
if not re.fullmatch('[0-9a-f]{40}',data['head']): raise RuntimeError('invalid head')
def sha(b): return hashlib.sha256(b).hexdigest()
def target(name):
    p=(root/name).resolve()
    if root not in p.parents or (root/name).is_symlink(): raise RuntimeError('invalid path')
    return p
def replace(p,content,meta):
    fd,name=tempfile.mkstemp(prefix='.cretas-publish-',dir=str(p.parent))
    tmp=pathlib.Path(name)
    try:
        os.fchmod(fd,meta['mode'])
        os.fchown(fd,meta['uid'],meta['gid'])
        with os.fdopen(fd,'wb') as stream:
            fd=None
            stream.write(content)
        os.replace(tmp,p)
    finally:
        if fd is not None: os.close(fd)
        if tmp.exists(): tmp.unlink()
backup=pathlib.Path('/www/backup/cretas-showcase')/data['head']
manifest=backup/'manifest.json'
if data['action']=='rollback':
    try:
        receipt=json.loads(manifest.read_text())
        if receipt['head']!=data['head']: raise RuntimeError('rollback head mismatch')
        rows=receipt['files']
        # Refuse to overwrite a concurrent release or a manual edit.
        for row in rows:
            if sha(target(row['path']).read_bytes())!=row['new']:
                raise RuntimeError('rollback current content differs: '+row['path'])
            if sha((backup/row['path']).read_bytes())!=row['old']:
                raise RuntimeError('rollback backup differs: '+row['path'])
        for row in reversed(rows):
            replace(target(row['path']),(backup/row['path']).read_bytes(),row)
    except BaseException as error:
        raise RuntimeError('ROLLBACK_BLOCKED: '+str(error))
    print(json.dumps({'status':'rolled_back','files':len(rows),'head':data['head']}));sys.exit(0)
paths=[]
for row in data['files']:
    p=target(row['path']);old=p.read_bytes();new=base64.b64decode(row['content'])
    if sha(old) not in (row['baseline'],sha(new)):
        raise RuntimeError('live baseline differs: '+row['path'])
    stat=p.stat()
    meta={'path':row['path'],'mode':stat.st_mode & 0o777,'uid':stat.st_uid,'gid':stat.st_gid,
          'old':sha(old),'new':sha(new)}
    paths.append((p,old,new,meta))
if data['action']=='check':
    print(json.dumps({'status':'baseline_verified','files':len(paths)}));sys.exit(0)
if all(old==new for p,old,new,meta in paths):
    print(json.dumps({'status':'already_published','head':data['head']}));sys.exit(0)
if any(old==new for p,old,new,meta in paths):
    raise RuntimeError('partial preexisting candidate; resolve before publication')
backup.mkdir(parents=True,exist_ok=True)
if manifest.exists(): raise RuntimeError('release backup already exists; use a new reviewed commit')
for p,old,new,meta in paths:
    b=backup/p.relative_to(root);b.parent.mkdir(parents=True,exist_ok=True);b.write_bytes(old)
manifest.write_text(json.dumps({'head':data['head'],'files':[meta for p,old,new,meta in paths]}))
changed=[]
try:
    for p,old,new,meta in paths:
        if p.read_bytes()!=old: raise RuntimeError('concurrent change before replacement')
        replace(p,new,meta);changed.append((p,old,new,meta))
    for p,old,new,meta in paths:
        if p.read_bytes()!=new: raise RuntimeError('readback mismatch')
except BaseException:
    try:
        if any(p.read_bytes()!=new for p,old,new,meta in changed):
            raise RuntimeError('concurrent change before rollback; nothing restored')
        for p,old,new,meta in reversed(changed): replace(p,old,meta)
    except BaseException as error:
        raise RuntimeError('ROLLBACK_BLOCKED: restoration failed: '+str(error))
    raise
print(json.dumps({'status':'published','files':len(paths),'backup':str(backup),'head':data['head']}))
'''

def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO).strip()

class RollbackBlocked(RuntimeError):
    pass

def remote(payload):
    program = base64.b64encode(REMOTE.encode()).decode()
    command = "python3 -c \"import base64;exec(base64.b64decode('" + program + "'))\""
    result = subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10',HOST,command],
                            input=json.dumps(payload).encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        message = result.stderr.decode(errors='replace').strip()
        raise (RollbackBlocked if 'ROLLBACK_BLOCKED:' in message else RuntimeError)(message)
    return json.loads(result.stdout)

def verify_https(rows, head):
    for row in rows:
        url = 'https://www.cretaceousfuture.com/' + row['path'] + '?release=' + head[:12]
        with urllib.request.urlopen(url, timeout=30) as response:
            actual = response.read()
        if actual != base64.b64decode(row['content']):
            raise RuntimeError('HTTPS readback mismatch: '+row['path'])

def publish_and_verify(payload):
    try:
        result = remote(payload)
    except Exception as error:
        print(json.dumps({'final_status':'rollback_blocked' if isinstance(error,RollbackBlocked) else 'publish_failed',
                          'error':str(error)}))
        raise
    try:
        verify_https(payload['files'], payload['head'])
    except Exception as error:
        if result['status'] != 'published':
            print(json.dumps({'final_status':'verification_failed_no_change','verification_error':str(error)}))
            raise RuntimeError('HTTPS verification failed; this invocation made no changes') from error
        try:
            restored = remote(dict(payload, action='rollback'))
        except Exception as rollback_error:
            print(json.dumps({'final_status':'rollback_blocked','verification_error':str(error),
                              'rollback_error':str(rollback_error)}))
            raise RuntimeError('verification failed and guarded rollback was blocked') from rollback_error
        print(json.dumps({'final_status':'rolled_back','receipt':restored,'verification_error':str(error)}))
        raise RuntimeError('HTTPS verification failed; prior website restored') from error
    return {'final_status':'published_verified','receipt':result,'head':payload['head']}

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='original reviewed origin/main SHA')
    parser.add_argument('--expected-head', required=True, help='exact reviewed release commit')
    parser.add_argument('--check', action='store_true', help='read-only live baseline check (default)')
    parser.add_argument('--confirm-prod', choices=['YES-PROD'])
    parser.add_argument('--rollback', action='store_true', help='restore this release, with current-content guard')
    args = parser.parse_args(argv)
    action = 'check' if args.check or not args.confirm_prod else 'publish'
    if args.rollback:
        if not args.confirm_prod or args.check:
            raise RuntimeError('rollback requires --confirm-prod YES-PROD and no --check')
        action = 'rollback'
    head = git('rev-parse', 'HEAD').decode()
    if head != args.expected_head:
        raise RuntimeError('HEAD differs from reviewed expected-head')
    if action == 'publish':
        subprocess.run(['git', 'fetch', 'origin', 'main'], cwd=REPO, check=True)
        if git('status', '--porcelain') or head != git('rev-parse', 'origin/main').decode():
            raise RuntimeError('publication requires clean exact origin/main')
    if action == 'rollback':
        try:
            receipt = remote({'head':head,'action':action})
        except Exception as error:
            print(json.dumps({'final_status':'rollback_blocked','error':str(error)}))
            raise
        print(json.dumps({'final_status':'rolled_back','receipt':receipt}))
        return
    subprocess.run(['git', 'merge-base', '--is-ancestor', args.base, head], cwd=REPO, check=True)
    rows = []
    for name in FILES:
        old = subprocess.check_output(['git', 'show', f'{args.base}:platform/{name}'], cwd=REPO)
        new = (REPO / 'platform' / name).read_bytes()
        rows.append({'path':name, 'baseline':REVIEWED_LIVE_BASELINES.get(name, hashlib.sha256(old).hexdigest()),
                     'content':base64.b64encode(new).decode()})
    payload = {'head':head,'action':action,'files':rows}
    print(json.dumps(remote(payload) if action=='check' else publish_and_verify(payload)))

if __name__ == '__main__':
    main()
