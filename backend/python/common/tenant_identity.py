"""Tenant identity for request handlers: the factory comes from the login, never from the client.

AuthMiddleware puts the authenticated factory on ``request.state.factory_id``
(JWT ``factoryId`` claim, or ``X-Factory-Id`` on a verified internal call).
Handlers that read or write factory data must scope by that value:

* ``caller_factory`` — the caller's factory, 401 when there is none (anonymous,
  or a platform token without a factory).
* ``require_same_factory`` — same, and a client-supplied factory id (old
  frontends still send one) must match it; a different one is 403, never a
  silent switch of tenant.

2026-10-22 factory-isolation sweep: several SmartBI routes sat in
PUBLIC_PREFIXES or trusted a body/query ``factory_id`` (default "F001"), so any
caller could read or write another factory's data by naming it.
"""
from __future__ import annotations

from typing import Optional

from fastapi import HTTPException, Request


def caller_factory(request: Request) -> str:
    factory_id = getattr(request.state, "factory_id", None)
    if not factory_id:
        raise HTTPException(status_code=401, detail="Authenticated tenant identity required")
    return str(factory_id)


def require_same_factory(request: Request, claimed: Optional[str]) -> str:
    own = caller_factory(request)
    if claimed and str(claimed) != own:
        raise HTTPException(status_code=403, detail="factory_id does not match the authenticated tenant")
    return own


PLATFORM_ROLES = frozenset({"super_admin", "platform_admin", "developer"})


def is_platform_caller(request: Request) -> bool:
    role = getattr(request.state, "role", None)
    return getattr(request.state, "auth_method", None) == "jwt" and str(role or "").lower() in PLATFORM_ROLES


def require_platform_role(request: Request) -> None:
    """平台管理动作 (建厂、把调研公司绑定到工厂、看全部客户名单): 只给平台角色。"""
    if not getattr(request.state, "auth_method", None):
        raise HTTPException(status_code=401, detail="Authentication required")
    if not is_platform_caller(request):
        raise HTTPException(status_code=403, detail="Platform administrator role required")


def require_platform_or_own_factory(request: Request) -> None:
    """路径带 factory_id 的路由: 平台角色, 或该工厂自己的登录用户; 其余路由只给平台角色。"""
    path_factory = request.path_params.get("factory_id")
    if path_factory and not is_platform_caller(request):
        require_same_factory(request, path_factory)
        return
    require_platform_role(request)


def scoped_factory(request: Request, claimed: Optional[str]) -> str:
    """工厂用户只能是自己的工厂; 平台角色必须点名一个工厂 (平台读取另有留痕)。"""
    if is_platform_caller(request):
        if not claimed:
            raise HTTPException(status_code=400, detail="factory_id is required for platform callers")
        return str(claimed)
    return require_same_factory(request, claimed)


def caller_user_id(request: Request) -> Optional[int]:
    raw = getattr(request.state, "user_id", None)
    try:
        return int(raw) if raw is not None else None
    except (TypeError, ValueError):
        return None
