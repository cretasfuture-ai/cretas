#!/usr/bin/env python3
"""Generate the independent reviewed factory training-label directory.

This writes a catalog of canonical labels only; it intentionally does not
rewrite the existing classifier ``label_mapping.json`` or model head.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _catalog_module():
    backend_python = Path(__file__).resolve().parents[2] / "backend" / "python"
    if str(backend_python) not in sys.path:
        sys.path.insert(0, str(backend_python))
    from ai import factory_catalog
    return factory_catalog


def write_factory_label_catalog(output_dir: Path | str) -> Path:
    catalog = _catalog_module()
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    target = output / "label_catalog.json"
    payload = {
        "schema_version": 1,
        "source": "factory-intent-catalog.json",
        "labels": catalog.training_catalog_labels(),
    }
    payload["num_labels"] = len(payload["labels"])
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description="Write the reviewed factory label directory")
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    target = write_factory_label_catalog(args.output_dir)
    print(f"Wrote {target}")


if __name__ == "__main__":
    main()
