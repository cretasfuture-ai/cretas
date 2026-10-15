"""Opt-in loopback fixture using the real router, authentication and PG pools.

No production lifespan/cron is started. The companion Java fixture owns the DB.
"""
import os
from fastapi import FastAPI
from auth_middleware import JWTAuthMiddleware
from smartbi.api.indicator_bridge import router

if os.environ.get("CRETAS_INDICATOR_BRIDGE_PG_VERIFY") != "true":
    raise RuntimeError("indicator bridge private fixture opt-in is required")
if os.environ.get("POSTGRES_DB") != "cretas_workflow_verify_indicator_bridge_20261015":
    raise RuntimeError("indicator bridge fixture requires its private database")
if os.environ.get("POSTGRES_HOST") != "127.0.0.1" or os.environ.get("POSTGRES_PORT") != "55458":
    raise RuntimeError("indicator bridge fixture must use the owned loopback PG")
app = FastAPI()
app.add_middleware(JWTAuthMiddleware, jwt_secret="local-indicator-acceptance-jwt-secret", enabled=True)
app.include_router(router)
