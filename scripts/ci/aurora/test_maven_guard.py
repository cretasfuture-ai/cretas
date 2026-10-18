import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

@unittest.skipUnless(sys.platform == 'linux' and shutil.which('flock'), 'Linux CI VM required')
class MavenGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        stub = self.root/'real-maven'
        stub.write_text(r'''#!/usr/bin/python3
import json, os, sys, time
with open(os.environ['HOME']+'/events','a') as f:
 f.write(json.dumps({'event':'start','args':sys.argv[1:],'maven':os.environ['MAVEN_OPTS'],'java':os.environ['JAVA_TOOL_OPTIONS']})+'\n')
time.sleep(.1)
with open(os.environ['HOME']+'/events','a') as f:f.write(json.dumps({'event':'end'})+'\n')
sys.exit(37 if 'fail' in sys.argv else 0)
''')
        stub.chmod(0o700)
        script = Path(__file__).with_name('maven.sh').read_text()
        self.assertNotIn('spring.test.context.cache.maxSize',script)
        self.wrapper = self.root/'mvn'
        self.wrapper.write_text(script.replace('/usr/local/bin/mvn',str(stub)))
        self.wrapper.chmod(0o700)
        (self.root/'maven_output_guard.py').write_bytes(Path(__file__).with_name('maven_output_guard.py').read_bytes())
        self.env = dict(os.environ,HOME=str(self.root))
        for key in ('MAVEN_OPTS','JAVA_TOOL_OPTIONS'): self.env.pop(key,None)
    def rows(self):
        return [json.loads(l) for l in (self.root/'events').read_text().splitlines()]
    def test_memory_and_process_isolation_preserve_selector(self):
        subprocess.run([str(self.wrapper),'-Dtest=Selector One'],env=self.env,check=True)
        row=self.rows()[0]
        self.assertEqual('-Xmx1g',row['maven'])
        self.assertEqual('-Xmx3g',row['java'])
        self.assertEqual(['-DreuseForks=false','-Dmaven.compiler.fork=true','-Dmaven.compiler.meminitial=256m','-Dmaven.compiler.maxmem=2g'],row['args'][:4])
        self.assertEqual('-Dtest=Selector One',row['args'][-1])
    def test_two_repositories_serialize_in_one_vm(self):
        first=subprocess.Popen([str(self.wrapper),'first'],env=self.env)
        second=subprocess.Popen([str(self.wrapper),'second'],env=self.env)
        self.assertEqual(0,first.wait());self.assertEqual(0,second.wait())
        self.assertEqual(['start','end','start','end'],[r['event'] for r in self.rows()])
    def test_explicit_workflow_memory_settings_remain_effective(self):
        env=dict(self.env,MAVEN_OPTS='-Xmx2g',JAVA_TOOL_OPTIONS='-Xmx4g')
        subprocess.run([str(self.wrapper),'explicit'],env=env,check=True)
        self.assertEqual('-Xmx2g',self.rows()[0]['maven'])
        self.assertEqual('-Xmx4g',self.rows()[0]['java'])
    def test_timeout_releases_lock_and_preserves_nonzero_status(self):
        script=self.wrapper.read_text().replace('90m','0.03s')
        self.wrapper.write_text(script)
        self.assertEqual(124,subprocess.run([str(self.wrapper),'timeout'],env=self.env).returncode)
        # A second invocation must acquire the released lock, then return its own status.
        self.wrapper.write_text(script.replace('0.03s','90m'))
        self.assertEqual(37,subprocess.run([str(self.wrapper),'fail'],env=self.env,timeout=3).returncode)
    def test_busy_lock_exits_without_starting_maven(self):
        import fcntl
        lock=self.root/'.cache/cretas-ci/maven.lock';lock.parent.mkdir(parents=True)
        self.wrapper.write_text(self.wrapper.read_text().replace('--wait 300','--wait 0.03'))
        with lock.open('a') as handle:
            fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual(75,subprocess.run([str(self.wrapper),'busy'],env=self.env,timeout=3).returncode)
        self.assertFalse((self.root/'events').exists())
    def test_failure_exit_status_is_propagated(self):
        self.assertEqual(37,subprocess.run([str(self.wrapper),'fail'],env=self.env).returncode)

if __name__ == '__main__': unittest.main()
