"""Optional platform assembly entry. Never registered while the feature is off."""
import os
import secrets

from fastapi import APIRouter, Depends, HTTPException, Request

from auth_middleware import JWTAuthMiddleware
from smartbi.config import get_settings
from factory_assembly.generation import FactoryInitRequest, generate_configuration

router = APIRouter(tags=["Platform Factory Assembly"])


def require_platform(request: Request):
    if os.getenv("CRETAS_PLATFORM_FACTORY_ASSEMBLY_ENABLED", "false").lower() != "true":
        raise HTTPException(404, "平台建厂能力未启用")
    # Every generation must pass Java's live administrator lookup first.
    # A client-held JWT alone must not bypass account revocation at that boundary.
    expected = os.getenv("INTERNAL_API_SECRET", "")
    if not expected or not secrets.compare_digest(request.headers.get("X-Internal-Secret", ""), expected):
        raise HTTPException(403, "仅允许平台建厂服务调用")
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "请先登录平台账号")
    verifier = JWTAuthMiddleware(None, get_settings().jwt_secret)
    claims = verifier._verify_token(authorization[7:])
    if claims is None:
        raise HTTPException(401, "平台身份已失效")
    actor_id = claims.get("userId")
    if (claims.get("actorType") != "PLATFORM_ADMIN"
            or claims.get("factoryId") != "PLATFORM"
            or claims.get("role") != "super_admin"
            or type(actor_id) is not int or actor_id <= 0):
        raise HTTPException(403, "仅平台超级管理员可以使用建厂能力")
    return claims


@router.post("/generate", dependencies=[Depends(require_platform)])
async def generate(request: FactoryInitRequest):
    return await generate_configuration(request)
