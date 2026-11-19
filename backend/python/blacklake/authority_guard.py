"""Self-heal for the isolated WSL cost-authority Java (127.0.0.1:11210).

The nightly period analysis needs that service. A Windows restart leaves both the WSL
postgres cluster and the Java down, so the adapter checks the port shortly before the
nightly window and starts them in order. Arguments are passed as lists (no shell), and
nothing from the services' environment or SQL is logged.
"""
import socket
import subprocess
import sys
import time
from pathlib import Path

HOST, PORT = '127.0.0.1', 11210
CLUSTER = ('17', 'testenv')
WAIT_SECONDS = 120  # WSL NAT forwarding to Windows measured ~100s
SCRIPT = Path(__file__).resolve().with_name('test_authority_wsl.py')


def port_listening(host=HOST, port=PORT, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def wsl_path(path):
    p = Path(path)
    drive = p.drive.rstrip(':').lower()
    return '/mnt/' + drive + '/' + '/'.join(p.parts[1:])


def _wsl(run, *args):
    return run(['wsl', '-u', 'root', '--', *args], capture_output=True, timeout=180, check=False)


def ensure_authority(probe=port_listening, run=subprocess.run, sleep=time.sleep, clock=time.monotonic,
                     wait_seconds=WAIT_SECONDS, script=SCRIPT, log=None):
    """Returns 'listening' | 'started' | 'failed'. Never raises."""
    log = log or (lambda m: print('[blacklake-authority] ' + m, file=sys.stderr, flush=True))
    try:
        if probe():
            return 'listening'
        log('port %d not listening, starting WSL cluster and authority' % PORT)
        status = _wsl(run, 'pg_ctlcluster', *CLUSTER, 'status')
        if status.returncode != 0:
            started = _wsl(run, 'pg_ctlcluster', *CLUSTER, 'start')
            if started.returncode != 0:
                log('cluster start failed rc=%s' % started.returncode)
                return 'failed'
        # rc!=0 is tolerated: TEST_PORT_ALREADY_OWNED/EXISTING_TEST_PROCESS_PRESERVED mean it is already coming up.
        java = _wsl(run, 'python3', '-I', wsl_path(script), '--start')
        if java.returncode != 0:
            log('authority start rc=%s (continuing to wait for port)' % java.returncode)
        deadline = clock() + wait_seconds
        while True:
            if probe():
                log('port %d is listening' % PORT)
                return 'started'
            if clock() >= deadline:
                log('port %d still not listening after %ss' % (PORT, wait_seconds))
                return 'failed'
            sleep(5)
    except Exception as e:
        log('self-heal error type=%s' % type(e).__name__)
        return 'failed'
