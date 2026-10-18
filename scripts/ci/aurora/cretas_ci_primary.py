#!/usr/bin/env python3
"""Aurora-local CI supervisor. Never fails back or stops a VM/job."""
import argparse
import datetime
import fcntl
import getpass
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess

HOME = Path('/Users/aurora')
STATE = HOME / '.local/state/cretas-ci-primary'
CONFIG = HOME / '.config/cretas/ci-primary.json'
COLIMA = '/opt/homebrew/bin/colima'
ALLOWED = {
    'actions.runner.cretasfuture-ai-cretas.aurora-cretas.service',
    'actions.runner.cretasfuture-ai-cretas-factory-assembly.aurora-assembly.service',
}
START_FREE = 110 * 2**30
FLOOR = 100 * 2**30


def identity():
    if (socket.gethostname(), getpass.getuser()) != ('Auroras-Mac-mini.local', 'aurora'):
        raise RuntimeError('wrong_host_or_user')


class Local:
    def __init__(self):
        self.env = dict(os.environ, PATH='/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin')

    def command(self, argv, timeout=30):
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout, env=self.env)

    def vm_status(self):
        r = self.command([COLIMA, 'list', '--json'])
        if r.returncode:
            raise RuntimeError('vm_status_unavailable')
        entries = [json.loads(line) for line in r.stdout.splitlines() if line.strip()]
        matches = [x for x in entries if x.get('name') == 'ci']
        if len(matches) != 1:
            raise RuntimeError('expected_existing_ci_profile')
        return matches[0]['status']

    def free(self):
        return shutil.disk_usage(HOME).free

    def start_vm(self):
        # Profile resource limits were installed while stopped. Never resize a
        # running VM, and never change the Docker context used by other stacks.
        r = self.command([COLIMA, 'start', 'ci', '--activate=false'], timeout=240)
        if r.returncode:
            raise RuntimeError('vm_start_failed')

    def vm(self, *argv):
        return self.command([COLIMA, 'ssh', '-p', 'ci', '--', *argv])

    def service_state(self, unit):
        r = self.vm('systemctl', 'is-active', unit)
        state = r.stdout.strip().splitlines()[-1:]
        if state and state[0] in ('active', 'inactive', 'failed', 'activating', 'deactivating'):
            return state[0]
        raise RuntimeError('service_state_unavailable')

    def workers_busy(self):
        r = self.vm('pgrep', '-x', 'Runner.Worker')
        if r.returncode not in (0, 1):
            raise RuntimeError('worker_state_unavailable')
        return r.returncode == 0

    def start_service(self, unit):
        if unit not in ALLOWED:
            raise RuntimeError('service_not_allowlisted')
        # The only privileged operation: start an explicitly configured local
        # CI-VM runner. No host sudo, stop, restart, VM deletion, or registration.
        r = self.vm('sudo', '-n', 'systemctl', 'start', unit)
        if r.returncode:
            raise RuntimeError('service_start_failed')


def supervise(local, units, apply=False):
    if not units or len(set(units)) != len(units) or not set(units) <= ALLOWED:
        raise RuntimeError('invalid_service_allowlist')
    report = {'mode': 'run' if apply else 'check', 'actions': [], 'units': {},
              'free_before_bytes': local.free(), 'vm_status': local.vm_status()}
    if report['free_before_bytes'] < START_FREE:
        report['status'] = 'paused_low_space'
        return report
    if report['vm_status'] == 'Stopped':
        if not apply:
            report['status'] = 'would_start_vm'
            return report
        local.start_vm()
        report['actions'].append('start_ci_vm_without_context_activation')
        report['vm_status'] = local.vm_status()
    if report['vm_status'] != 'Running':
        raise RuntimeError('vm_not_ready')
    for unit in units:
        state = local.service_state(unit)
        report['units'][unit] = state
        if state in ('active', 'activating', 'deactivating'):
            continue
        if local.free() < START_FREE:
            report['status'] = 'paused_low_space'
            return report
        if local.workers_busy():
            report['status'] = 'deferred_existing_worker'
            return report
        if not apply:
            report['actions'].append('would_start:' + unit)
            continue
        local.start_service(unit)
        report['actions'].append('start:' + unit)
        report['units'][unit] = local.service_state(unit)
        if report['units'][unit] not in ('active', 'activating'):
            raise RuntimeError('service_did_not_start')
    report['free_after_bytes'] = local.free()
    if report['free_after_bytes'] < FLOOR:
        raise RuntimeError('capacity_floor_breached_no_jobs_stopped')
    report['status'] = 'ready' if all(x == 'active' for x in report['units'].values()) else 'pending'
    return report


def execute(apply=False):
    identity()
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (STATE / 'worker.lock').open('a+') as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {'status': 'lock_busy'}
        try:
            config = json.loads(CONFIG.read_text())
            report = supervise(Local(), config['units'], apply)
        except Exception as e:
            report = {'status': 'error', 'error_type': type(e).__name__}
            if isinstance(e, RuntimeError):
                report['reason'] = str(e)
        report['utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        data = json.dumps(report, indent=2).encode()
        if len(data) > 32768:
            raise RuntimeError('report_size_exceeded')
        tmp = STATE / '.latest.tmp'
        tmp.write_bytes(data)
        os.replace(tmp, STATE / 'latest.json')
        if report.get('actions') or report['status'] == 'error':
            name = report['utc'].replace(':', '').replace('+', '_')
            (STATE / ('event-' + name + '.json')).write_bytes(data)
            for p in sorted(STATE.glob('event-*.json'), reverse=True)[14:]:
                if p.is_file() and not p.is_symlink():
                    p.unlink()
        return report


def main():
    p = argparse.ArgumentParser()
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument('--run', action='store_true')
    g.add_argument('--check', action='store_true')
    args = p.parse_args()
    report = execute(args.run)
    print(json.dumps(report))
    return 1 if report['status'] == 'error' else 0


if __name__ == '__main__':
    raise SystemExit(main())
