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
    """Training-sample capture / export look up the factory's lists
    (process-secret terms, people, customers) in the Cretas main database.
    Unit tests have no such database: default to "empty lists". Tests about the
    rule itself monkeypatch these lookups on the module that imported them.

    Deliberately NOT built on ``monkeypatch``: requesting monkeypatch from this
    root autouse fixture would make it the outermost fixture, so its undo would
    run after other fixtures' teardown checks (test_probe_llm_registry's
    _SAFE_MODELS guard saw its own test's patches still in place)."""
    from smartbi.services.process_secret_names import EMPTY_LISTS

    async def _empty_lists(*_a, **_k):
        return EMPTY_LISTS, False

    async def _none_map(*_a, **_k):
        return {}

    async def _no_settings_database(*_a, **_k):
        raise RuntimeError("no secrecy settings database in unit tests")

    # factory_secrecy reads a failed read as every item ON. Tests about the switches replace _read.
    from common import factory_secrecy

    restore = [(factory_secrecy, "_read", factory_secrecy._read)]
    factory_secrecy._read = _no_settings_database
    factory_secrecy.invalidate()
    targets = (("smartbi.services.distillation_capture", "factory_lists_or_last_good", _empty_lists),
               ("scripts.export_distillation_dataset", "all_factory_lists", _none_map))
    # Corpus scripts (LLM judge / human review sampling / eval freeze) filter
    # their rows by the same lists. Patched only when a test already imported
    # them: importing corpus_judge here would bypass its test's import stubs.
    loaded_only = (("scripts.corpus_judge", "all_factory_lists", _none_map),
                   ("scripts.corpus_g3_sample", "all_factory_lists", _none_map),
                   ("scripts.corpus_eval_freeze", "all_factory_lists", _none_map),
                   ("scripts.synthesis_family_report", "all_factory_lists", _none_map))
    for module_name, attr, stub in targets:
        try:
            module = __import__(module_name, fromlist=[attr])
        except Exception:  # pragma: no cover - not importable in some isolated runs
            continue
        restore.append((module, attr, getattr(module, attr, None)))
        setattr(module, attr, stub)
    for module_name, attr, stub in loaded_only:
        module = sys.modules.get(module_name)
        if module is None or not hasattr(module, attr):
            continue
        restore.append((module, attr, getattr(module, attr)))
        setattr(module, attr, stub)
    try:
        yield
    finally:
        for module, attr, original in reversed(restore):
            setattr(module, attr, original)
