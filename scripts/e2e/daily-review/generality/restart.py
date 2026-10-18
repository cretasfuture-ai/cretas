"""Restart only the owned P37 JVM after its tested class files have been frozen."""
from pathlib import Path
import hashlib
import json
import os
import socket
import subprocess
import sys
import psutil

assert sys.argv[1:] == ['NON_PRODUCTION_ONLY']
root = Path(__file__).resolve().parents[4]
out = Path.home() / 'Documents/cretas-connect-20261015/factory-data-p37'
runtime = json.loads((out / 'runtime.json').read_text())
assert runtime['owner'] == 'P37-FACTORY-GENERALITY-20261018'
assert runtime['database'] == 'p37_factory_generality_20261018'
assert Path(runtime['javaArgFile']).resolve() == (out / 'entry-java.args').resolve()
assert not psutil.pid_exists(runtime['javaPid']), 'Stop the verified owned JVM before rebuilding'
with socket.socket() as probe:
    assert probe.connect_ex(('127.0.0.1', 10072)) != 0, 'Owned port occupied'
for name, digest in json.loads((out / 'product-final-freeze.json').read_text()).items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
args = Path(runtime['javaArgFile']).read_text()
assert 'p37_factory_generality_20261018' in args and '127.0.0.1:55447' in args
assert '--server.port=10072' in args and 'factory-data-p37' in args
secure = json.loads((out.parent / 'factory-data-p14/runtime.private.json').read_text())
env = {k: v for k, v in os.environ.items() if not any(token in k.upper() for token in
       ('API_KEY', 'API_SECRET', 'JWT_SECRET', 'DB_PASSWORD', 'INTERNAL_API_SECRET'))}
env.update(JWT_SECRET=secure['jwt'], INTERNAL_API_SECRET=secure['internal'],
           VISION_ENTRY_WORKER_TOKEN=secure['worker'])
with (out / 'java.log').open('w', encoding='utf-8') as log:
    process = subprocess.Popen(['C:/Program Files/Zulu/zulu-21/bin/java.exe',
                                '@' + runtime['javaArgFile']],
                               cwd=root / 'backend/java/cretas-api', env=env,
                               stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)
runtime['javaPid'] = process.pid
(out / 'runtime.json').write_text(json.dumps(runtime, indent=2), encoding='utf-8')
print(json.dumps({'status': 'STARTED_NOT_YET_ACCEPTED', 'javaPid': process.pid}))
