"""Dedicated, credential-free browser preview; authority stays on this host.

This is NOT mounted in the production SmartBI application. Only an explicit
analytical allowlist reaches the isolated test service. Business writes and
follow-up record changes are deliberately unavailable on this public surface.
"""
import asyncio
from collections import Counter
import logging
import os
import re
import time
from pathlib import Path
from typing import Literal

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from .period_contract import PeriodSelection

TENANT = 'BLACKLAKE_POC_TEST'
UPSTREAM = 'http://127.0.0.1:8095'
PREFIX = '/smartbi-api/api/smartbi/blacklake/'
GET_ROUTES = {'analysis', 'complete-cost-analysis', 'cost-traceability', 'period-reports', 'period-schedule'}
POST_ROUTES = {'sections/followup', 'complete-cost-analysis/followup', 'period-reports'}
QUERY_KEYS = {'start_date', 'end_date', 'basis', 'kind', 'mode'}
SECRET_KEYS = {'accesstoken', 'refreshtoken', 'token', 'password', 'jwtsecret', 'apikey', 'authorization',
               'email', 'phone', 'mobile', 'username', 'updatedby', 'createdby', 'userid', 'host',
               'workerids', 'employees', 'workers', 'personnel', 'salarydetails', 'payrolldetails'}
PERSON_KEYS = re.compile(r'employee|personnel|contact|worker|staff|reporter|operator|username|userid|phone|mobile|email|address|idcard|identitycard|bank|credential|password|secret|authorization|createdby|updatedby|wechat|weixin|landline', re.I)
BUSINESS_NAME_KEYS = {'factoryName', 'productName', 'materialName', 'processName', 'feedProcessName', 'warehouseName', 'unitName'}


class PreviewTurn(BaseModel):
    model_config = ConfigDict(extra='forbid')
    role: Literal['user', 'assistant']
    content: str = Field(min_length=1, max_length=12000)


class PreviewFollowup(BaseModel):
    model_config = ConfigDict(extra='forbid')
    section_id: Literal['coverage', 'material', 'labor', 'finished', 'product_cost', 'production', 'inventory']
    question: str = Field(min_length=1, max_length=500)
    run_id: str = Field(min_length=1, max_length=200)
    history: list[PreviewTurn] = Field(default_factory=list, max_length=6)
    alert_id: str | None = Field(default=None, min_length=1, max_length=200)
    period: PeriodSelection | None = None


class PreviewCompleteFollowup(BaseModel):
    model_config = ConfigDict(extra='forbid')
    fact_id: str = Field(min_length=1, max_length=100)
    run_id: str = Field(min_length=1, max_length=200)
    question: str = Field(min_length=1, max_length=2000)


class PreviewPeriodRequest(PeriodSelection):
    force: bool = False
    expected_fingerprint: str | None = Field(default=None, pattern=r'^[a-f0-9]{64}$')


def clean_response(value, chart=False):
    if isinstance(value, dict):
        return {k: clean_response(v, chart or k == 'echartsOption') for k, v in value.items()
                if re.sub(r'[_-]', '', k).lower() not in SECRET_KEYS and not PERSON_KEYS.search(k)
                and (not k.lower().endswith('name') or k in BUSINESS_NAME_KEYS or chart and k == 'name')}
    if isinstance(value, list):
        return [clean_response(v, chart) for v in value]
    if isinstance(value, str):
        value = re.sub(r'(?<!\d)(?:\+?86[-\s]?)?1[3-9]\d[-\s]?\d{4}[-\s]?\d{4}(?!\d)', '[联系方式已隐藏]', value)
        value = re.sub(r'(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)', '[身份信息已隐藏]', value)
        value = re.sub(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '[联系方式已隐藏]', value)
        value = re.sub(r'(?<!\d)0\d{2,3}[-\s]?\d{7,8}(?!\d)', '[联系方式已隐藏]', value)
        value = re.sub(r'(?:微信|wechat|weixin)\s*[:：]?\s*[A-Za-z][A-Za-z0-9_-]{5,19}', '[联系方式已隐藏]', value, flags=re.I)
    return value


class SessionBroker:
    def __init__(self):
        self.token = None
        self.until = 0
        self.retry_after = 0
        self.lock = asyncio.Lock()

    async def get(self, invalidate=False):
        async with self.lock:
            if invalidate:
                self.until = 0
            if self.token and time.monotonic() < self.until:
                return self.token
            if time.monotonic() < self.retry_after:
                raise RuntimeError('PREVIEW_AUTH_RETRY_LATER')
            # Avoid repeated login storms when the local test authority is down.
            self.retry_after = time.monotonic() + 60
            try:
                from .preview_session import session
                configured_path = os.environ.get('BLACKLAKE_PREVIEW_SESSION_CONFIG')
                result = await asyncio.to_thread(session, Path(configured_path)) if configured_path else await asyncio.to_thread(session)
            except Exception as error:
                reason = str(error) if re.fullmatch(r'[A-Z_]{1,80}', str(error)) else type(error).__name__
                logging.warning('PREVIEW_AUTH_UNAVAILABLE:%s', reason)
                raise
            data = result.get('data') or {}
            if result.get('success') is not True or data.get('factoryId') != TENANT or not data.get('accessToken'):
                raise RuntimeError('PREVIEW_TEST_IDENTITY_REQUIRED')
            self.token = data['accessToken']
            self.until = time.monotonic() + 900
            return self.token


def create_app(dist: Path, origin: str, broker=None, transport=None):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    broker = broker or SessionBroker()
    dist = dist.resolve()
    read_slots = asyncio.Semaphore(8)
    analysis_slots = asyncio.Semaphore(1)
    pending = Counter()
    pending_total = 0

    def failure(message, code):
        return JSONResponse({'success': False, 'message': message}, status_code=code)

    @app.middleware('http')
    async def boundary(request: Request, call_next):
        nonlocal pending_total
        if request.method not in {'GET', 'HEAD', 'POST'}:
            return failure('公开预览不支持此操作', 405)
        if request.method == 'POST' and request.headers.get('origin') != origin:
            return failure('仅支持从预览页面操作', 403)
        local_path = request.url.path.removeprefix(request.scope.get('root_path', ''))
        tracked = local_path.startswith('/smartbi-api/') or local_path.startswith('/__blacklake/')
        ip = request.headers.get('x-preview-client-ip') or (request.client.host if request.client else 'local')
        # Bounded simultaneous backlog, not a daily/monthly analysis allowance.
        # No await between check and increment: these counters are event-loop atomic.
        if tracked and (pending_total >= 32 or pending[ip] >= 8):
            response = failure('分析请求正在排队，请稍后重试', 429)
            response.headers['Retry-After'] = '3'
            return response
        if tracked:
            pending_total += 1
            pending[ip] += 1
        try:
            response = await call_next(request)
        finally:
            if tracked:
                pending_total -= 1
                pending[ip] -= 1
                if not pending[ip]:
                    del pending[ip]
        response.headers.update({'Cache-Control': 'public, max-age=31536000, immutable' if local_path.startswith('/assets/') else 'no-store', 'X-Content-Type-Options': 'nosniff',
                                 'X-Robots-Tag': 'noindex, nofollow, noarchive',
                                 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY'})
        return response

    @app.post('/__blacklake/preview-session')
    async def preview_session():
        try:
            await broker.get()
        except Exception as error:
            logging.warning('PREVIEW_SESSION_FAILURE:%s', type(error).__name__)
            return failure('本机分析服务暂不可用，请稍后重试', 503)
        # View metadata only: no real identity, permissions or usable credential.
        return {'success': True, 'data': {'factoryId': TENANT, 'previewOnly': True}}

    @app.api_route('/smartbi-api/api/smartbi/blacklake/{resource:path}', methods=['GET', 'POST'])
    async def analytical_proxy(resource: str, request: Request):
        allowed = (resource in GET_ROUTES or bool(re.fullmatch(r'period-reports/[A-Za-z0-9_-]{1,160}', resource))) if request.method == 'GET' else resource in POST_ROUTES
        if not allowed:
            return failure('公开预览仅支持查看和分析，不保存跟进或修改业务数据', 403)
        summary_query=request.method=='GET' and resource=='period-reports'
        allowed_query=QUERY_KEYS|({'summaries_only'} if summary_query else set())
        if set(request.query_params) - allowed_query:
            return failure('不支持的筛选条件', 400)
        if 'summaries_only' in request.query_params and request.query_params.getlist('summaries_only')!=['true']:
            return failure('历史列表仅接受摘要查看参数',400)
        body = b''
        async for chunk in request.stream():
            body += chunk
            if len(body) > 32768:
                return failure('问题内容过长，请缩短后重试', 413)
        if request.method == 'POST':
            import json
            try:
                payload = json.loads(body)
                if not isinstance(payload, dict):
                    raise ValueError()
                # Independent ingress DTOs match the analytical contract without
                # importing the full application/runtime into this small gateway.
                models = {'sections/followup': PreviewFollowup, 'complete-cost-analysis/followup': PreviewCompleteFollowup,
                          'period-reports': PreviewPeriodRequest}
                parsed = models[resource].model_validate(payload)
                if resource == 'period-reports':
                    parsed.force = False  # Reuse the existing queue/cache.
                body = parsed.model_dump_json(exclude_none=True).encode()
            except (ValueError, TypeError):
                return failure('请求内容无效', 400)
        async with (read_slots if request.method == 'GET' else analysis_slots):
            try:
                token = await broker.get()
                async with httpx.AsyncClient(timeout=180, trust_env=False, follow_redirects=False, transport=transport) as client:
                    kwargs = dict(params=request.query_params.multi_items(), content=body,
                                  headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
                    response = await client.request(request.method, UPSTREAM + '/api/smartbi/blacklake/' + resource, **kwargs)
                    if response.status_code == 401:
                        token = await broker.get(invalidate=True)
                        kwargs['headers']['Authorization'] = 'Bearer ' + token
                        response = await client.request(request.method, UPSTREAM + '/api/smartbi/blacklake/' + resource, **kwargs)
                if response.status_code >= 500:
                    return failure('分析服务暂不可用，请稍后重试', 503)
                return JSONResponse(clean_response(response.json()), status_code=response.status_code)
            except Exception:
                return failure('分析服务暂不可用，请稍后重试', 503)

    @app.get('/health')
    async def health():
        return {'success': True, 'preview': 'blacklake-readonly'}

    @app.get('/{path:path}')
    async def static(path: str):
        if '..' in path.split('/') or '\\' in path:
            return failure('页面不存在', 404)
        if path in {'', 'blacklake-smartbi.html'}:
            candidate = dist / 'blacklake-smartbi.html'
        elif path.startswith('assets/') and not path.endswith('.map'):
            candidate = (dist / path).resolve()
        else:
            return failure('页面不存在', 404)
        if not candidate.is_relative_to(dist) or not candidate.is_file():
            return failure('页面不存在', 404)
        return FileResponse(candidate)

    return app


if __name__ == '__main__':
    import uvicorn
    prefix = os.environ['BLACKLAKE_PREVIEW_PATH']
    if not re.fullmatch(r'/preview/[a-f0-9]{32}/', prefix):
        raise RuntimeError('PREVIEW_SHARE_PATH_REQUIRED')
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    app.mount(prefix.rstrip('/'), create_app(Path(os.environ['BLACKLAKE_PREVIEW_DIST']), 'https://test.cretaceousfuture.com'))
    uvicorn.run(app, host='127.0.0.1', port=5188, access_log=False, log_level='warning')
