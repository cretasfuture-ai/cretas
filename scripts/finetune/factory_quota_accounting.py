"""UTC-day/shared-group accounting; unknown model/day evidence fails closed."""
import datetime as dt
import hashlib
import json
import re
from pathlib import Path

SMALL='gpt-5.4-mini-2026-03-17'
LARGE='gpt-5.4-2026-03-05'
# Current official help-table snapshot, 2026-11-05. This does not imply that a
# deprecated model is callable, or that a given account/project is enrolled.
LARGE_FAMILIES={'gpt-6.1-sol','gpt-6-astra','gpt-6-sol','gpt-6-luna','gpt-5.6-sol','gpt-5.5','gpt-5.4','gpt-5.2','gpt-5.1','gpt-5.1-codex','gpt-5-codex','gpt-5','gpt-5-chat-latest','gpt-4.5-preview','gpt-4.1','gpt-4o','o3','o1-preview','o1'}
SMALL_FAMILIES={'gpt-5.6-terra','gpt-5.6-luna','gpt-5.4-mini','gpt-5.4-nano','gpt-5.1-codex-mini','gpt-5-mini','gpt-5-nano','gpt-4.1-mini','gpt-4.1-nano','gpt-4o-mini','o4-mini','o1-mini','codex-mini-latest'}


def quota_group(model):
    for families,group in [(LARGE_FAMILIES,LARGE),(SMALL_FAMILIES,SMALL)]:
        if any(re.fullmatch(re.escape(family)+r'(?:-\d{4}-\d{2}-\d{2})?',model) for family in families):return group
    raise ValueError('Model free-token group is unverified: '+str(model))


def utc_day(row,legacy_day=None):
    value=row.get('day')
    response=row.get('response')
    created=row.get('response_created_at')
    if created is None and isinstance(response,dict):created=response.get('created_at')
    server_day=dt.datetime.fromtimestamp(created,dt.timezone.utc).date().isoformat() if isinstance(created,(int,float)) else None
    if value and server_day and value!=server_day:raise ValueError('Client/server UTC date conflict')
    value=server_day or value or legacy_day
    if value is None:raise ValueError('Request UTC day unavailable; explicit legacy-date evidence required')
    if dt.date.fromisoformat(value).isoformat()!=value:raise ValueError('Noncanonical UTC day')
    return value


def legacy_rows(path):
    path=Path(path)
    rows=[json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    sidecar=path.with_suffix('.utc-days.json');legacy_day=None
    if sidecar.exists():
        evidence=json.loads(sidecar.read_text(encoding='utf-8'))
        if evidence['source_sha256']!=hashlib.sha256(path.read_bytes()).hexdigest():raise ValueError('Legacy date evidence no longer matches file')
        if not evidence.get('evidence_ref'):raise ValueError('Legacy UTC date evidence reference required')
        legacy_day=evidence['default_utc_day']
    return [dict(row,day=utc_day(row,legacy_day)) for row in rows]


def add_usage(totals,row,today):
    if utc_day(row)!=today:return
    usage=row.get('usage') or {}
    if not usage:return
    group=quota_group(row['model'])
    totals[group]+=usage.get('input_tokens',0)+usage.get('output_tokens',0)
