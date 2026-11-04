"""Standalone boot must not depend on pytest's SmartBI path bootstrap."""
import os
from pathlib import Path
import subprocess
import sys


def test_standalone_period_adapter_registers_routes_before_any_real_data_access():
    root=Path(__file__).resolve().parents[1]
    code='''
import blacklake.smartbi_app as module
module.settings=lambda p:{'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'synthetic',
                          'run_id':'synthetic','jwt_secret':'synthetic-no-real-key-for-bootstrap-test-1234'}
app=module.create_app()
paths={r.path for r in app.routes}
assert '/api/smartbi/blacklake/period-reports' in paths
assert '/api/smartbi/blacklake/analysis' in paths
print('STANDALONE_PERIOD_ROUTES_READY')
'''
    environment=dict(os.environ)
    environment.pop('PYTHONPATH',None)
    result=subprocess.run([sys.executable,'-c',code],cwd=root,env=environment,capture_output=True,text=True,timeout=40)
    assert result.returncode==0, result.stderr
    assert result.stdout.strip()=='STANDALONE_PERIOD_ROUTES_READY'
