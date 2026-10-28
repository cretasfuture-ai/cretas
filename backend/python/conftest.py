"""Root-level conftest for pytest.

Ensures `backend/python/` and `backend/python/smartbi/` are on sys.path so
that the `smartbi.services.*` package can load (its __init__ imports
top-level `services.*` which requires `smartbi/` on sys.path).
"""
from __future__ import annotations

import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent  # backend/python
_SMARTBI = _HERE / "smartbi"

for p in (_HERE, _SMARTBI):
    p_str = str(p)
    if p_str not in sys.path:
        sys.path.insert(0, p_str)


import pytest  # noqa: E402


@pytest.fixture(autouse=True)
def _no_process_secret_products_by_default():
    """Training-sample capture / export look up the factory's process-secret
    product names in the Cretas main database (privacy clause 6). Unit tests
    have no such database: default to "no protected products". Tests about the
    rule itself monkeypatch these names on the module that imported them.

    Deliberately NOT built on ``monkeypatch``: requesting monkeypatch from this
    root autouse fixture would make it the outermost fixture, so its undo would
    run after other fixtures' teardown checks (test_probe_llm_registry's
    _SAFE_MODELS guard saw its own test's patches still in place)."""
    async def _none(*_a, **_k):
        return frozenset()

    async def _none_map(*_a, **_k):
        return {}

    restore = []
    targets = (("smartbi.services.distillation_capture", "protected_product_names", _none),
               ("smartbi.services.distillation_capture", "all_protected_product_names", _none_map),
               ("scripts.export_distillation_dataset", "all_protected_product_names", _none_map))
    for module_name, attr, stub in targets:
        try:
            module = __import__(module_name, fromlist=[attr])
        except Exception:  # pragma: no cover - not importable in some isolated runs
            continue
        restore.append((module, attr, getattr(module, attr, None)))
        setattr(module, attr, stub)
    try:
        yield
    finally:
        for module, attr, original in reversed(restore):
            setattr(module, attr, original)
