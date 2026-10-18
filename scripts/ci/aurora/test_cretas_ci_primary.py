import fcntl
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import cretas_ci_primary as m

UNIT = sorted(m.ALLOWED)[0]


class Fake:
    def __init__(self, vm='Running', service='active', free=180*2**30, busy=False):
        self.status, self.service, self.space, self.busy = vm, service, free, busy
        self.calls = []
    def vm_status(self): return self.status
    def free(self): return self.space
    def start_vm(self): self.calls.append('start_vm'); self.status='Running'
    def service_state(self, unit): return self.service
    def workers_busy(self): return self.busy
    def start_service(self, unit): self.calls.append(('start', unit)); self.service='active'


class Tests(unittest.TestCase):
    def test_ready_idempotent(self):
        f=Fake(); self.assertEqual(m.supervise(f,[UNIT],True)['status'],'ready');self.assertEqual(f.calls,[])
    def test_check_does_not_boot_vm(self):
        f=Fake(vm='Stopped');self.assertEqual(m.supervise(f,[UNIT])['status'],'would_start_vm');self.assertEqual(f.calls,[])
    def test_boot_stopped_vm(self):
        f=Fake(vm='Stopped');self.assertEqual(m.supervise(f,[UNIT],True)['status'],'ready');self.assertEqual(f.calls,['start_vm'])
    def test_starts_inactive_service(self):
        f=Fake(service='inactive');self.assertEqual(m.supervise(f,[UNIT],True)['status'],'ready');self.assertEqual(f.calls,[('start',UNIT)])
    def test_check_never_starts_service(self):
        f=Fake(service='inactive');m.supervise(f,[UNIT]);self.assertEqual(f.calls,[])
    def test_busy_worker_preserved(self):
        f=Fake(service='inactive',busy=True);self.assertEqual(m.supervise(f,[UNIT],True)['status'],'deferred_existing_worker');self.assertEqual(f.calls,[])
    def test_active_worker_never_stopped(self):
        f=Fake(busy=True);self.assertEqual(m.supervise(f,[UNIT],True)['status'],'ready');self.assertEqual(f.calls,[])
    def test_space_guard_stopped_vm(self):
        f=Fake(vm='Stopped',free=109*2**30);self.assertEqual(m.supervise(f,[UNIT],True)['status'],'paused_low_space');self.assertEqual(f.calls,[])
    def test_space_guard_inactive_service(self):
        f=Fake(service='inactive',free=99*2**30);self.assertEqual(m.supervise(f,[UNIT],True)['status'],'paused_low_space');self.assertEqual(f.calls,[])
    def test_no_resize_running_vm(self):
        f=Fake();m.supervise(f,[UNIT],True);self.assertNotIn('start_vm',f.calls)
    def test_allowlist(self):
        for units in [[],[UNIT,UNIT],['ssh.service']]:
            with self.assertRaises(RuntimeError):m.supervise(Fake(),units,True)
    def test_vm_not_ready_fails(self):
        with self.assertRaises(RuntimeError):m.supervise(Fake(vm='Starting'),[UNIT],True)
    def test_start_failure_propagates(self):
        f=Fake(service='failed')
        with patch.object(f,'start_service',side_effect=RuntimeError('failure')):
            with self.assertRaises(RuntimeError):m.supervise(f,[UNIT],True)
    def test_boot_is_context_safe(self):
        x=m.Local()
        with patch.object(x,'command') as call:
            call.return_value.returncode=0;x.start_vm()
        self.assertEqual(call.call_args.args[0],[m.COLIMA,'start','ci','--activate=false'])
    def test_privilege_is_vm_start_only(self):
        x=m.Local()
        with patch.object(x,'vm') as call:
            call.return_value.returncode=0;x.start_service(UNIT)
        self.assertEqual(call.call_args.args,('sudo','-n','systemctl','start',UNIT))
        with self.assertRaises(RuntimeError):x.start_service('ssh.service')
    def test_unknown_service_state_fails_closed(self):
        x=m.Local()
        with patch.object(x,'vm') as call:
            call.return_value.stdout='';call.return_value.returncode=1
            with self.assertRaises(RuntimeError):x.service_state(UNIT)
    def test_worker_probe_failure_fails_closed(self):
        x=m.Local()
        with patch.object(x,'vm') as call:
            call.return_value.returncode=2
            with self.assertRaises(RuntimeError):x.workers_busy()
    def test_single_instance_and_bounded_reports(self):
        with tempfile.TemporaryDirectory() as t:
            state=Path(t);config=state/'config.json';config.write_text(json.dumps({'units':[UNIT]}))
            with patch.object(m,'STATE',state),patch.object(m,'CONFIG',config),patch.object(m,'identity'),patch.object(m,'Local',return_value=Fake()):
                with (state/'worker.lock').open('a+') as lock:
                    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);self.assertEqual(m.execute()['status'],'lock_busy')
                for i in range(20):(state/f'event-2000-{i:02}.json').write_text('{}')
                with patch.object(m,'Local',return_value=Fake(service='inactive')):m.execute(True)
                self.assertEqual(len(list(state.glob('event-*.json'))),14)
                self.assertLess((state/'latest.json').stat().st_size,32768)

if __name__=='__main__':unittest.main(verbosity=2)
