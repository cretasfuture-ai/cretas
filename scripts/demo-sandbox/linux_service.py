#!/usr/bin/env python3
"""Render review-only Linux service files for the F006 demo sandbox.

This module deliberately does not invoke systemctl, Docker, SSH, or any
gateway deployment command. The generated files are operator-review inputs.
"""
from __future__ import annotations

import argparse
import ast
import os
import sys
from pathlib import Path

CODE_DIR = '/opt/cretas/f006-demo-sandbox/code'
RUNTIME_DIR = '/var/lib/cretas/f006-demo-sandbox/runtime'
CONFIG_PATH = RUNTIME_DIR + '/runtime.private.json'
PYTHON_BIN = '/opt/cretas/f006-demo-sandbox/venv/bin/python'
MANAGER_URL = 'http://127.0.0.1:10041'
SERVICE_NAME = 'cretas-f006-demo-sandbox.service'
GATEWAY_REVIEW_NAME = 'cretas-f006-demo-sandbox.gateway.env.review'


def _check_python(path=PYTHON_BIN):
    """Require a dedicated Python >=3.9 interpreter without starting it."""
    if str(path) != PYTHON_BIN:
        raise ValueError('sandbox service must use the fixed private venv interpreter')
    # The target host owns the venv. Generation only validates the local
    # interpreter when it happens to be present; it never installs or runs it.
    if Path(path).is_file():
        import subprocess
        result = subprocess.run([str(path), '-c', 'import sys; print(sys.version_info[:2])'],
                                check=True, capture_output=True, text=True)
        major, minor = ast.literal_eval(result.stdout.strip())
    else:
        major, minor = sys.version_info[:2]
    if (major, minor) < (3, 9):
        raise RuntimeError('sandbox service requires Python >= 3.9')


def render_systemd_unit():
    """Return a fixed loopback-only manager unit with no secret arguments."""
    _check_python()
    return f'''[Unit]
Description=Cretas F006 demo sandbox manager (review-only template)
After=network-online.target docker.service
Wants=network-online.target
Requires=docker.service

[Service]
Type=simple
User=root
Group=root
WorkingDirectory={CODE_DIR}
ExecStart={PYTHON_BIN} {CODE_DIR}/runtime.py --config {CONFIG_PATH}
Restart=on-failure
RestartSec=5s
NoNewPrivileges=true
UMask=0077
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadOnlyPaths={CODE_DIR}
ReadWritePaths={RUNTIME_DIR}
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
'''


def render_gateway_review_env():
    """Return blue/green gateway values as a review-only local env file."""
    return '''# REVIEW ONLY: apply separately to blue and green gateway env during review.
# This file is not installed, sourced, or sent to a remote host by this tool.
# The blank control-key value is an explicit review placeholder, never a key.
CRETAS_DEMO_SANDBOX_ENABLED=false
CRETAS_DEMO_SANDBOX_MANAGER_BASE_URL=http://127.0.0.1:10041
CRETAS_DEMO_SANDBOX_CONTROL_KEY=
'''


def write_review_files(output_dir):
    """Write only local, review-labelled files and return their paths."""
    output = Path(output_dir).expanduser().resolve()
    if not output.is_absolute() or output == Path('/'):
        raise ValueError('review output directory must be a private local directory')
    output.mkdir(parents=True, exist_ok=True)
    unit = output / SERVICE_NAME
    gateway = output / GATEWAY_REVIEW_NAME
    unit.write_text(render_systemd_unit(), encoding='utf-8')
    gateway.write_text(render_gateway_review_env(), encoding='utf-8')
    os.chmod(unit, 0o644)
    os.chmod(gateway, 0o600)
    return {'service': unit, 'gateway_env_review': gateway}


def main(argv=None):
    parser = argparse.ArgumentParser(description='render review-only F006 Linux service files')
    parser.add_argument('--output-dir', required=True)
    args = parser.parse_args(argv)
    files = write_review_files(args.output_dir)
    print('\n'.join(f'{key}={value}' for key, value in files.items()))


if __name__ == '__main__':
    main()
