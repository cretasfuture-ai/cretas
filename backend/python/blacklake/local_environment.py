"""Explicit local-PoC helpers; production connector settings never auto-discover secrets."""
import os
from pathlib import Path

from .secrets import load_local_secret
from .contracts import BlacklakeError


def cache_url():
    path = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake/test-cache.dpapi"
    saved = load_local_secret(path)
    if saved.get("initialization_complete") is not True:
        raise BlacklakeError("TEST_CACHE_READINESS_NOT_VERIFIED")
    return saved["url"]


def apply_source_binding():
    path = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake/source-binding.dpapi"
    binding = load_local_secret(path)
    if binding.get("owner_confirmed") is not True or not binding.get("expected_org_id"):
        raise BlacklakeError("SOURCE_OWNER_BINDING_REQUIRED")
    expected = str(binding["expected_org_id"])
    configured = os.environ.get("BLACKLAKE_SOURCE_FACTORY_ID")
    if configured and configured != expected:
        raise BlacklakeError("CONFIGURED_SOURCE_FACTORY_MISMATCH")
    os.environ["BLACKLAKE_SOURCE_FACTORY_ID"] = expected
