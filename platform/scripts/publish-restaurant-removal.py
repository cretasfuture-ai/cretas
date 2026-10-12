"""Publish this scoped static-site update from clean exact origin/main.

Checks the live baseline before writing; backs up and rolls back changed files.
Does not modify nginx, APIs, services, or other website files.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

FILES = ['index.html', 'demo.html', 'solutions-factory.html',
         'solutions-logistics.html', 'solutions-ai.html', 'solutions-custom.html',
         'smartbi.html', 'cockpit.html', 'sitemap.xml',
         'solutions-restaurant.html', 'restaurantbi-example/index.html',
         'css/v6.css', 'js/v6.js', 'js/v6-intro.js', 'js/v6-motion.js',
         'js/v6-premium.js']
HOST = 'root@139.196.165.140'
ROOT = '/www/wwwroot/showcase/cretaceousfuture'
REPO = Path(__file__).resolve().parents[2]
# Reviewed live drift: the retired page and two previously hotfixed JS files.
# Full pre-change content is retained in the local acceptance evidence.
REVIEWED_LIVE_BASELINES = {
    'solutions-restaurant.html': '50940d1b25ebb012b683be67a1a6e4f4da7ece8a2df0ff46471b7d2782d2baa1',
    'js/v6.js': 'fd8b704c68a384d9f81b30460ea46cba7c772d2d9f81d8a3115781599e7f8fe3',
    'js/v6-premium.js': '4cc153b0004c8e66ddc1d01d84ada25373d8caab832b3ff52bf1272ced49adf7',
}

REMOTE = '''
import base64, fcntl, hashlib, json, os, pathlib, sys
data=json.load(sys.stdin)
root=pathlib.Path('/www/wwwroot/showcase/cretaceousfuture').resolve()
lock=open('/tmp/cretas-showcase-publish.lock','w')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
def sha(b): return hashlib.sha256(b).hexdigest()
paths=[]
for row in data['files']:
    p=(root/row['path']).resolve()
    if root not in p.parents or (root/row['path']).is_symlink(): raise RuntimeError('invalid path')
    old=p.read_bytes()
    new=base64.b64decode(row['content'])
    if sha(old) not in (row['baseline'],sha(new)):
        raise RuntimeError('live baseline differs: '+row['path'])
    paths.append((p,old,new))
if data['check']:
    print(json.dumps({'status':'baseline_verified','files':len(paths)})); sys.exit(0)
backup=pathlib.Path('/www/backup/cretas-showcase')/data['head']
backup.mkdir(parents=True,exist_ok=True)
changed=[]
try:
    for p,old,new in paths:
        if old==new: continue
        b=backup/p.relative_to(root); b.parent.mkdir(parents=True,exist_ok=True)
        if not b.exists(): b.write_bytes(old)
        tmp=p.with_name(p.name+'.publish-'+data['head'][:12])
        tmp.write_bytes(new); os.chmod(tmp,p.stat().st_mode & 0o777)
        os.chown(tmp,p.stat().st_uid,p.stat().st_gid)
        os.replace(tmp,p); changed.append((p,old))
    for p,old,new in paths:
        if p.read_bytes()!=new: raise RuntimeError('readback mismatch')
except BaseException:
    for p,old in reversed(changed):
        tmp=p.with_name(p.name+'.rollback'); tmp.write_bytes(old); os.replace(tmp,p)
    raise
print(json.dumps({'status':'published','files':len(paths),'changed':len(changed),'backup':str(backup)}))
'''

def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, help='original reviewed origin/main SHA')
    parser.add_argument('--check', action='store_true', help='read-only live baseline check')
    args = parser.parse_args()
    head = git('rev-parse', 'HEAD').decode()
    if not args.check:
        subprocess.run(['git', 'fetch', 'origin', 'main'], cwd=REPO, check=True)
        if git('status', '--porcelain') or head != git('rev-parse', 'origin/main').decode():
            raise RuntimeError('publication requires clean exact origin/main')
    subprocess.run(['git', 'merge-base', '--is-ancestor', args.base, head], cwd=REPO, check=True)
    rows = []
    for name in FILES:
        old = subprocess.check_output(['git', 'show', f'{args.base}:platform/{name}'], cwd=REPO)
        new = (REPO / 'platform' / name).read_bytes()
        rows.append({'path':name, 'baseline':REVIEWED_LIVE_BASELINES.get(name, hashlib.sha256(old).hexdigest()),
                     'content':base64.b64encode(new).decode()})
    payload = json.dumps({'head':head,'check':args.check,'files':rows}).encode()
    program = base64.b64encode(REMOTE.encode()).decode()
    command = "python3 -c \"import base64;exec(base64.b64decode('" + program + "'))\""
    result = subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10',HOST,command],
                            input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace').strip())
    print(result.stdout.decode().strip())
    if not args.check:
        for row in rows:
            url = 'https://www.cretaceousfuture.com/' + row['path'] + '?release=' + head[:12]
            with urllib.request.urlopen(url, timeout=30) as response:
                actual = response.read()
            if actual != base64.b64decode(row['content']):
                raise RuntimeError('HTTPS readback mismatch: '+row['path'])
        print(json.dumps({'status':'https_verified','head':head,'files':len(rows)}))

if __name__ == '__main__':
    main()
