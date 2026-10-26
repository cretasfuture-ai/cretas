"""Reuse the Java finance permission and row-scope gate before direct financial reads."""
import re
import httpx
import os
from urllib.parse import urlparse


def java_base_url():
    value=os.environ.get('JAVA_API_BASE_URL') or os.environ.get('BACKEND_BASE_URL') or 'http://127.0.0.1:10010'
    parsed=urlparse(value)
    # Only an operator-controlled configured target may receive the bearer.
    # Unconfigured developer/test processes stay on loopback; no production DNS default.
    if parsed.scheme not in {'http','https'} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        return None
    if parsed.scheme=='http' and parsed.hostname not in {'localhost','127.0.0.1','::1'}:
        import ipaddress
        try:
            if not ipaddress.ip_address(parsed.hostname).is_private: return None
        except ValueError: return None
    if parsed.path not in ('','/'): return None
    return value.rstrip('/')


async def full_factory_finance_access(request, factory_id):
    # Client-supplied role/permission flags never authorize a database read.
    token=request.headers.get('authorization','')
    if not token.startswith('Bearer ') or not re.fullmatch(r'[A-Za-z0-9_-]{1,50}',factory_id):
        return False
    from datetime import date
    today=date.today()
    base_url=java_base_url()
    if not base_url: return False
    configured = bool(os.environ.get('JAVA_API_BASE_URL') or os.environ.get('BACKEND_BASE_URL'))
    targets = [base_url]
    if not configured and base_url.endswith(':10010'):
        targets.append(base_url[:-6] + ':10020')
    try:
        async with httpx.AsyncClient(timeout=6,follow_redirects=False) as client:
            for index, target in enumerate(targets):
                try:
                    response=await client.get(
                        f'{target}/api/mobile/{factory_id}/finance/report/cash-flow',
                        params={'startYear':today.year,'startMonth':today.month,'endYear':today.year,'endMonth':today.month},
                        headers={'Authorization':token})
                except httpx.ConnectError:
                    if index + 1 < len(targets):
                        continue
                    return False
                if response.status_code!=200 or response.headers.get('X-Finance-Row-Scope'):
                    return False
                body=response.json()
                data=body.get('data')
                return bool(body.get('success') is True and isinstance(data,dict)
                            and data.get('factoryId')==factory_id and data.get('scopeApplied') is False)
    except Exception:
        return False
