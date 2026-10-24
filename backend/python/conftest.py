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
def _no_process_secret_products_by_default(monkeypatch):
    """Training-sample capture / export look up the factory's process-secret
    product names in the Cretas main database (privacy clause 6). Unit tests
    have no such database: default to "no protected products". Tests about the
    rule itself override these names on the module that imported them."""
    async def _none(*_a, **_k):
        return frozenset()

    async def _none_map(*_a, **_k):
        return {}

    try:
        from smartbi.services import distillation_capture
    except Exception:  # pragma: no cover - package not importable in some isolated runs
        return
    monkeypatch.setattr(distillation_capture, "protected_product_names", _none, raising=False)
    try:
        import scripts.export_distillation_dataset as export_mod
    except Exception:  # pragma: no cover
        return
    monkeypatch.setattr(export_mod, "all_protected_product_names", _none_map, raising=False)
