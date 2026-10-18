"""Prepare an isolated P14 runtime on 10064/18096/31764/18394.

This file only validates prerequisites and prints the launch contract. The root
coordinator supplies clone/database/classpath preparation and starts processes;
this helper never touches 10060/10062 and never invokes Maven or a clock agent.
"""
from __future__ import annotations

import argparse
import json
import socket
from pathlib import Path

PORTS = {"java": 10064, "python": 18096, "web": 31764, "objects": 18394}


def occupied(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--status-only", action="store_true")
    args = parser.parse_args()
    if args.out.resolve() != Path.home() / "Documents/cretas-connect-20261015/factory-data-p14":
        raise ValueError("P14 evidence must stay in the private factory-data-p14 directory")
    if "p10" in str(args.out).lower() or "p10" in args.database.lower():
        raise ValueError("P14 runtime cannot reuse P10 database or runtime")
    if not args.classpath.exists():
        raise FileNotFoundError("Root must provide the independent Java classes directory")
    state = {name: occupied(port) for name, port in PORTS.items()}
    if not args.status_only and any(state.values()):
        raise RuntimeError("P14 port guard failed; inspect owner before starting anything: " + json.dumps(state))
    record = {"owner": "P14-FLOW-HARNESS-20261016", "database": args.database,
              "ports": PORTS, "portsOccupied": state, "classpath": str(args.classpath),
              "productionTouched": False, "clockAgent": False, "maven": False}
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "runtime-preflight.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    print(json.dumps(record, ensure_ascii=False))


if __name__ == "__main__":
    main()
