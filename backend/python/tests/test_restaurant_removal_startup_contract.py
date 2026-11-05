"""Startup contract for the retired restaurant Python surface."""

from main import app
from fastapi.testclient import TestClient

RETIRED_NON_RESTAURANT_NAMED_ROUTES = (
    ("/api/internal/smartbi/agent/runs", "POST"),
    ("/api/internal/smartbi/agent/runs/{run_id}/events", "GET"),
    ("/api/internal/smartbi/agent/runs/{run_id}/cancel", "POST"),
    ("/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale", "POST"),
)


def _paths() -> set[str]:
    return {route.path for route in app.routes if getattr(route, "path", None)}


def test_restaurant_routes_are_not_registered_and_factory_surface_is_live():
    paths = _paths()
    assert not any("restaurant" in path.lower() for path in paths)
    route_keys = {
        (route.path, method)
        for route in app.routes
        if getattr(route, "path", None)
        for method in getattr(route, "methods", set())
    }
    assert not (route_keys & set(RETIRED_NON_RESTAURANT_NAMED_ROUTES))
    assert "/api/chat/general-analysis" in paths
    assert "/api/smartbi/gold/finance-summary" in paths
    assert "/health" in paths

    client = TestClient(app)
    try:
        # JWT middleware protects the shared chat health route; 401 proves the
        # route is registered and reaches the real middleware/handler stack.
        assert client.get("/api/chat/health").status_code == 401
        assert client.get("/health").status_code == 200
    finally:
        client.close()


def test_restaurant_background_switches_and_imports_are_not_in_entrypoint():
    import main

    source = open(main.__file__, encoding="utf-8").read()
    assert "RESTAURANT_OPS_ETL_ENABLED" not in source
    assert "restaurant_ops_etl" not in source
    assert "_reservation_roll_task" not in source
