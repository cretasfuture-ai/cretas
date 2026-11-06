"""Bounded read-only visibility, not proof that every future writer is paused.

No raw cron, process argv, env, keys, panel DB or command output is returned.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess

UNITS = ('cretas-restaurant-audit.service', 'cretas-restaurant-audit.timer')
TARGETS = ('/var/spool/cron/root', '/var/spool/cron/crontabs/root') + tuple(
    '/etc/systemd/system/' + unit for unit in UNITS)


def command(args):
    try:
        p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
        return p.returncode, p.stdout
    except (OSError, subprocess.TimeoutExpired):
        return None, b''


def fingerprint(path):
    path = Path(path)
    try:
        info = path.lstat()
        value = dict(exists=True, regular=stat.S_ISREG(info.st_mode), symlink=path.is_symlink(),
                     uid=info.st_uid, mode=oct(stat.S_IMODE(info.st_mode)), inode=info.st_ino,
                     mtimeNs=info.st_mtime_ns, bytes=info.st_size)
        if stat.S_ISREG(info.st_mode) and info.st_size < 1024 * 1024:
            value['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        return value
    except FileNotFoundError:
        return dict(exists=False)
    except OSError:
        return dict(exists='UNKNOWN', readable=False)


def writer_kind(argv):
    """Classifies explicit argv only: interpreters and remote commands can hide writes."""
    if not argv:
        return None
    base = os.path.basename(argv[0])
    if base == 'crontab':
        # No arguments and '-u root' alone install stdin; only explicit read/help is exempt.
        if any(arg in ('-l', '-V', '--version', '--help', '-h') for arg in argv[1:]):
            return None
        return 'crontab-mutator'
    if base == 'systemctl' and any(unit in argv for unit in UNITS):
        if any(verb in argv for verb in ('start', 'stop', 'restart', 'enable', 'disable',
                                        'edit', 'mask', 'unmask', 'revert', 'link')):
            return 'target-unit-mutator'
    if any(target in arg for target in TARGETS for arg in argv[1:]):
        return 'target-path-process-review-required'
    return None


def main():
    result = dict(observedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  readOnly=True, allWritersExclusiveProof=False, rawCommandsReturned=0,
                  targetFiles={p: fingerprint(p) for p in TARGETS})
    rc, cron = command(['crontab', '-l'])
    result['rootCron'] = dict(readExit=rc, sha256=hashlib.sha256(cron).hexdigest() if rc == 0 else None,
        activeLines=sum(bool(line.strip()) and not line.lstrip().startswith(b'#')
                        and not re.match(rb'^[A-Za-z_][A-Za-z0-9_]*=', line.strip())
                        for line in cron.splitlines()) if rc == 0 else None)
    result['units'] = {}
    for unit in UNITS:
        result['units'][unit] = {}
        for verb in ('is-active', 'is-enabled'):
            rc, value = command(['systemctl', verb, unit])
            text = value.decode('utf-8', 'replace').strip()
            allowed = ('active', 'inactive', 'disabled', 'enabled', 'static', 'masked', 'not-found', 'failed')
            result['units'][unit][verb] = text if text in allowed else 'UNKNOWN'
    writers, sessions, agents = [], [], []
    unreadable = 0
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            argv = [x.decode('utf-8', 'replace') for x in (directory / 'cmdline').read_bytes().split(b'\0') if x]
            uid = directory.stat().st_uid
        except OSError:
            unreadable += 1
            continue
        kind = writer_kind(argv)
        if kind:
            writers.append(dict(pid=int(directory.name), uid=uid, category=kind))
        if argv and os.path.basename(argv[0]).startswith('sshd:'):
            sessions.append(dict(pid=int(directory.name), uid=uid))
        text = ' '.join(argv).lower()
        for category, needles in (
            ('bt-panel', ('/www/server/panel/bt-panel', '/www/server/panel/bt-task')),
            ('aliyun-cloud-assist', ('aliyun_assist', 'aliyun-assist', 'aliyun-service')),
            ('configuration-management', ('salt-minion', 'puppet agent', 'chef-client', 'ansible-pull'))):
            if any(value in text for value in needles):
                agents.append(dict(pid=int(directory.name), uid=uid, category=category))
    result.update(writerOrTargetAccessCandidates=writers, sshSessionProcessMetadata=sessions,
                  managementAgentProcesses=agents, procUnreadable=unreadable)
    rc, data = command(['systemctl', 'list-units', '--type=service', '--state=running', '--no-legend', '--plain'])
    result['managementServices'] = [line.split()[0] for line in data.decode('utf-8', 'replace').splitlines()
        if line.split() and re.search(r'cron|sshd|auditd|atd|bt|panel|puppet|salt|chef|ansible|aliyun|cloud', line.split()[0], re.I)]
    rc, queued = command(['atq'])
    result['atQueue'] = dict(readExit=rc, queuedEntries=len(queued.splitlines()) if rc == 0 else None)
    rc, timers = command(['systemctl', 'list-timers', '--all', '--no-legend', '--plain'])
    timer_names = sorted(set(re.findall(r'\S+\.timer\b', timers.decode('utf-8', 'replace'))))
    result['timerVisibility'] = dict(readExit=rc, timerCount=len(timer_names) if rc == 0 else None,
        targetTimers=[name for name in timer_names if 'restaurant-audit' in name])
    result['managementMetadata'] = {}
    # Panel database presence only. Do not open its contents or key material.
    for name in ('/www/server/panel/data/default.db', '/www/server/panel/data/default.db-wal',
                 '/www/server/cron', '/etc/cron.d', '/etc/ansible', '/etc/puppetlabs',
                 '/etc/salt', '/var/spool/at'):
        path = Path(name)
        result['managementMetadata'][name] = dict(exists=path.exists(), symlink=path.is_symlink())
        if path.is_dir() and not path.is_symlink():
            try:
                result['managementMetadata'][name]['directEntryCount'] = len(list(path.iterdir()))
            except OSError:
                result['managementMetadata'][name]['directEntryCount'] = 'UNKNOWN'
    rc, settings = command(['sshd', '-T'])
    result['sshdEffectiveReadExit'] = rc
    result['sshAccessChannels'] = {key: value for key, value in
        (line.split(None, 1) for line in settings.decode('utf-8', 'replace').splitlines() if ' ' in line)
        if key in ('permitrootlogin', 'passwordauthentication', 'pubkeyauthentication', 'usepam')}
    auditctl = shutil.which('auditctl')
    result['auditVisibility'] = dict(auditctlAvailable=bool(auditctl))
    if auditctl:
        rc, rules = command([auditctl, '-l'])
        result['auditVisibility'].update(ruleReadExit=rc, targetRelevantRuleCount=sum(
            any(target.encode() in line for target in TARGETS + ('/var/spool/cron', '/etc/systemd/system'))
            for line in rules.splitlines()) if rc == 0 else None)
    result['limitations'] = [
        'Process scan is an instant snapshot; inline, remote, short-lived or future writers can be invisible.',
        'Management channel presence does not identify or pause its operator.',
        'Panel database, authorized_keys, env files, credentials and application databases were not opened.',
        'Audit rule presence alone does not prove complete historical event coverage.',
        'No cron or unit was edited; no service, timer, session or agent was stopped.']
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
