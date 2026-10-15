"""Opt-in loopback fixture using the real router, authentication and PG pools.

No production lifespan/cron is started. The companion Java fixture owns the DB.
"""
import os

if os.environ.get("CRETAS_INDICATOR_BRIDGE_PG_VERIFY") != "true":
    raise RuntimeError("indicator bridge private fixture opt-in is required")
if os.environ.get("POSTGRES_DB") != "cretas_workflow_verify_indicator_bridge_20261015":
    raise RuntimeError("indicator bridge fixture requires its private database")
if os.environ.get("POSTGRES_HOST") != "127.0.0.1" or os.environ.get("POSTGRES_PORT") != "55458":
    raise RuntimeError("indicator bridge fixture must use the owned loopback PG")
# Use the production entry and middleware stack. The runner disables only its
# lifespan so unrelated production cron/model warm-up does not start in a test.
from main import app  # noqa: E402,F401
