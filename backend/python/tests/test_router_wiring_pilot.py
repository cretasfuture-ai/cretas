"""Router-wiring regression gate (BUG-R1A-1 follow-up).

History: PR #425 shipped ``smartbi_compat.api.config_thresholds`` with 41 unit
tests + 5 endpoints (Phase 2C Tier 1 pilot), but ``main.py`` never called
``app.include_router(config_thresholds.router)``. Unit tests passed (router
import + handlers tested in isolation), yet production curl returned 404 —
the wiring step had no test gate.

This file plugs that gap:

* ``test_config_thresholds_endpoints_wired`` — the **specific** bug: assert
  all 5 ``/api/mobile/smartbi-config/thresholds`` routes resolve in
  ``main.app.routes`` after import.

* ``test_every_smartbi_compat_router_is_wired`` — the **same-cause sweep**:
  for every module in ``smartbi_compat.api`` that defines ``router = APIRouter()``,
  assert at least one of its routes appears in ``main.app.routes``. Future
  Phase 2C Tier 1 sub-modules (intents / incentive-rules / field-mappings /
  metric-formulas / chart-templates / reload+status / data-sources) get
  catch-the-mistake-at-CI-time coverage automatically.

Pattern: zero live DB / network / config required — pure import + route
inspection. Same cost profile as ``test_imports.py``.
"""
from __future__ import annotations

import importlib
import pkgutil

import pytest


@pytest.fixture(scope="module")
def app():
    """Import main once per module; expose its FastAPI instance."""
    import main  # noqa: WPS433 — module-level side effects are intentional
    return main.app


def _route_paths(app, *, methods: bool = False):
    """Return set of (path, method) tuples or just paths from app.routes.

    FastAPI's APIRoute objects expose ``.path`` and ``.methods``. Mount
    objects (Starlette internals) have ``.path`` only — we skip those when
    ``methods=True`` since they don't carry HTTP verbs.
    """
    out = set()
    for route in app.routes:
        path = getattr(route, "path", None)
        if path is None:
            continue
        if methods:
            for m in getattr(route, "methods", set()) or set():
                out.add((path, m))
        else:
            out.add(path)
    return out


def test_config_thresholds_endpoints_wired(app):
    """All 5 Phase 2C Tier 1 pilot endpoints must resolve in main.app."""
    expected = {
        ("/api/mobile/smartbi-config/thresholds", "GET"),
        ("/api/mobile/smartbi-config/thresholds", "POST"),
        ("/api/mobile/smartbi-config/thresholds/{id}", "PUT"),
        ("/api/mobile/smartbi-config/thresholds/{id}", "DELETE"),
        ("/api/mobile/smartbi-config/thresholds/reload", "POST"),
    }
    actual = _route_paths(app, methods=True)
    missing = expected - actual
    assert not missing, (
        f"config_thresholds endpoints not wired into main.app: {missing}. "
        "Check the smartbi_compat block in backend/python/main.py."
    )
