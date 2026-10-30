"""Closed read policy for server-signed platform factory-view credentials."""
import json
import hashlib
import hmac
import jwt
import re
import time
from pathlib import Path
from urllib.parse import parse_qs

_READ_PATHS = tuple(re.compile(pattern) for pattern in json.loads(
    Path(__file__).with_name("platform_factory_view_read_paths.json").read_text(encoding="utf-8")
))
_TENANT_KEYS = {"factoryId", "factory_id", "viewFactoryId", "view_factory_id", "tenantId", "tenant_id"}


def factory_view_signing_key(secret):
    key = secret.encode("utf-8") if isinstance(secret, str) else secret
    return hmac.new(key.ljust(32, b"\0"), b"cretas-platform-factory-view-v1", hashlib.sha256).digest()


def decode_platform_credential(token, secret):
    try:
        claims = jwt.decode(token, secret, algorithms=["HS256"], options={"verify_exp": True})
        if has_view_marker(claims):
            raise jwt.InvalidSignatureError("Factory view requires its dedicated signing key")
        return claims
    except jwt.InvalidSignatureError:
        claims = jwt.decode(token, factory_view_signing_key(secret), algorithms=["HS256"], options={"verify_exp": True})
        if claims.get("platformFactoryView") is not True:
            raise jwt.InvalidSignatureError("Missing factory-view marker")
        return claims


def has_view_marker(claims):
    return any(key in claims for key in ("platformFactoryView", "viewFactoryId", "viewReadOnly"))


def authorize_factory_view(claims, path, method, query=b""):
    """Caller verifies signature/expiry first. None means an ordinary credential."""
    if not has_view_marker(claims):
        return None
    issued, expires = claims.get("iat"), claims.get("exp")
    target = claims.get("viewFactoryId")
    actor_id = claims.get("userId")
    valid = (
        claims.get("platformFactoryView") is True and claims.get("viewReadOnly") is True
        and claims.get("factoryId") == "PLATFORM" and claims.get("actorType") == "PLATFORM_ADMIN"
        and claims.get("role") in {"super_admin", "platform_admin"}
        and type(actor_id) is int and actor_id > 0
        and isinstance(target, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}", target)
        and type(issued) in (int, float) and type(expires) in (int, float)
        and 0 < expires - issued <= 900 and issued <= time.time() + 30 and expires > time.time()
    )
    if not valid:
        raise ValueError("Invalid factory view claims")
    prefix = "/api/mobile/" + target
    if not path.startswith(prefix + "/") or method.upper() not in {"GET", "HEAD", "OPTIONS"}:
        raise ValueError("Factory view is restricted to approved factory reads")
    if not any(pattern.fullmatch(path[len(prefix):]) for pattern in _READ_PATHS):
        raise ValueError("This route is not available in factory view")
    if isinstance(query, bytes):
        query = query.decode("latin-1")
    parameters = parse_qs(query, keep_blank_values=True)
    if any(value != target for name in _TENANT_KEYS for value in parameters.get(name, [])):
        raise ValueError("Factory view tenant mismatch")
    return target
