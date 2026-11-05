#!/usr/bin/env python3
"""Local operator entry. HTTP never accepts executable paths or shell arguments."""
import argparse
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from orchestrator import SandboxOrchestrator, atomic_json

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('action',choices=['provision','prepare','reset','bootstrap']); parser.add_argument('--config',required=True)
    args = parser.parse_args(); path = Path(args.config).resolve(); cfg = json.loads(path.read_text(encoding='utf-8'))
    slot = SandboxOrchestrator(json.loads(Path(cfg['orchestrator_config_path']).read_text(encoding='utf-8')))
    if args.action == 'bootstrap':
        manifest = Path(cfg['manifest_path'])
        if manifest.exists(): raise RuntimeError('manifest already exists; do not overwrite a live session')
        slot.reset(); slot.assert_clean()
        files = Path(cfg['sandbox_file_root'])
        if files.exists() and any(files.iterdir()): raise RuntimeError('unaccounted files; operator review required')
        atomic_json(manifest,{})
    else: getattr(slot,args.action)()
    print(json.dumps({'action':args.action,'status':'OK'}))

if __name__ == '__main__': main()
