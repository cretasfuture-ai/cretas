"""Low-frequency read-only supplements for the one-minute live collector.

Strategy (keeps the existing minute collection unchanged and inside quotas):
- Runs only after the existing resources were collected, with its own per-cycle
  budget (EXTRA_BUDGET requests, and never closer than RESERVE to max_requests).
  Upstream quota is 10000 requests/hour per account; the base collector uses
  about 65/min plus an hourly ~700 refresh, so 50/min extra stays below it.
- Each resource has its own interval (FREQUENT 30 min, DAILY 24 h). A resource is
  refreshed whole or not at all; a failure keeps the previous rows, records a
  constant error code in extraState and is retried after its interval. Extras
  never fail the cycle, except lease/auth/bound errors which must stop the cycle.
- Work-order cost detail (7 source types per cost record) and price lists (one
  request per product and side) are rolling: refreshed when the cost summary row
  changed, or after 24 h, oldest first, within the remaining budget.
- Rows are scrubbed of person data (names, phones, contacts, addresses, salary,
  creator/updator objects, attachments, custom field blobs) before storage.
"""
from datetime import datetime, timedelta, timezone
import re
from zoneinfo import ZoneInfo

from .contracts import BlacklakeError
from .full_archive import digest

EXTRA_BUDGET = 50
RESERVE = 100
FREQUENT = 1800
DAILY = 86400
RETRY = 600  # A failed resource is retried after 10 minutes instead of its full interval.
COST_SOURCE_TYPES = (10, 11, 12, 20, 21, 30, 31)
OPERATION_LOG_OBJECTS = ('工单', '报工', '任务', '出入库单', '产品', '物料清单', '采购订单', '销售订单')
# Errors that must stop the whole cycle instead of being isolated per resource.
FATAL = ('CONNECTION_LEASE_LOST', 'LIVE_REQUEST_BOUND_REACHED', 'AUTH_REFRESH_EXHAUSTED', 'LOGIN_',
         'SOURCE_FACTORY_', 'LIVE_FACTORY_NOT_VERIFIED')
SENSITIVE = re.compile(r'phone|mobile|password|token|email|contact|address|addres|salary|wage|idcard|creator|updator|'
                       r'operator|applier|approver|approvor|purchaser|responsible|bizuser|staff|worker|^user|username|'
                       r'attachment|customfieldvalues|printrecord', re.I)
SHANGHAI = ZoneInfo('Asia/Shanghai')


class BudgetExhausted(BlacklakeError):
    pass


def scrubbed(value):
    if isinstance(value, dict):
        return {k: scrubbed(v) for k, v in value.items() if not SENSITIVE.search(k)}
    if isinstance(value, list):
        return [scrubbed(v) for v in value]
    return value


def local(moment):
    return moment.astimezone(SHANGHAI).strftime('%Y-%m-%d %H:%M:%S')


def windows(now):
    # Upstream rejects created-time windows over 2 years (code 1078; 729 days + 1 was rejected live);
    # inspection orders allow at most 1 year. Records created earlier are not covered.
    two = {'start': local(now - timedelta(days=720)), 'end': local(now)}
    one = {'start': local(now - timedelta(days=360)), 'end': local(now)}
    return two, one


def plans(now, projects):
    two, one = windows(now)
    gte = {'createdAtGte': two['start'], 'createdAtLte': two['end']}
    start = {'createdAtStart': two['start'], 'createdAtEnd': two['end']}
    midnight = now.astimezone(SHANGHAI).replace(hour=0, minute=0, second=0, microsecond=0)
    codes = sorted({p['projectCode'] for p in projects})
    return [
        ('project_cost', FREQUENT, [({'projectCodes': codes[i:i + 100]}, ('projectCode', set(codes[i:i + 100])))
                                    for i in range(0, len(codes), 100)]),
        ('sale_order', FREQUENT, [(gte, None)]),
        ('purchase_order', FREQUENT, [(gte, None)]),
        ('purchase_requirement', FREQUENT, [(gte, None)]),
        ('receivable', FREQUENT, [({}, None)]),
        ('receipt', FREQUENT, [({}, None)]),
        ('payable', FREQUENT, [({}, None)]),
        ('payment', FREQUENT, [({}, None)]),
        ('customer_ledger', FREQUENT, [({}, None)]),
        ('vendor_ledger', FREQUENT, [({}, None)]),
        ('invoicing', FREQUENT, [({}, None)]),
        ('inspection_order', FREQUENT, [({'orderType': t, 'createdAtGte': one['start'], 'createdAtLte': one['end']},
                                         ('orderType', {t})) for t in (1, 2, 3, 4, 5)]),
        ('inspection_result', FREQUENT, [({}, None)]),
        ('collaborative_task', FREQUENT, [({}, None)]),
        ('outsource_task', FREQUENT, [({'status': [0, 10, 20]}, None)]),
        ('outsource_deliver_return', FREQUENT, [(gte, None)]),
        ('outsource_receive_return', FREQUENT, [(gte, None)]),
        ('owner_project', FREQUENT, [({'fieldQueryValues': [{'fieldName': 'status', 'queryOperator': 'in',
                                                             'fieldValue': [0, 10, 20, 30]}]}, None)]),
        # Today's (Asia/Shanghai) log entries only; earlier days stay in older cycle snapshots.
        ('operation_log', FREQUENT, [({'objectName': name, 'logFrom': int(midnight.timestamp() * 1000),
                                       'logTo': int(now.timestamp() * 1000)}, ('objectName', {name}))
                                      for name in OPERATION_LOG_OBJECTS]),
        ('customer', DAILY, [(start, None)]),
        ('vendor', DAILY, [(start, None)]),
        ('equipment', DAILY, [(start, None)]),
        ('inspection_item', DAILY, [(gte, None)]),
        ('inspection_spec', DAILY, [(gte, None)]),
    ]


def due(state, key, interval, now, failed=False):
    at = state.get(key)
    return at is None or (now - datetime.fromisoformat(at)).total_seconds() >= (min(interval, RETRY) if failed else interval)


def collect_extras(archive, client, data, prior_state, projects, *, now=None, budget=EXTRA_BUDGET):
    """Mutates data with extra resources; returns (new state, requests used)."""
    now = now or datetime.now(timezone.utc)
    state = {'at': dict((prior_state or {}).get('at', {})), 'errors': dict((prior_state or {}).get('errors', {})),
             'costChecked': dict((prior_state or {}).get('costChecked', {})),
             'priceChecked': dict((prior_state or {}).get('priceChecked', {}))}
    used = 0
    original = client.query

    def limited(resource, body):
        nonlocal used
        if used >= budget:
            raise BudgetExhausted('EXTRA_BUDGET_EXHAUSTED')
        used += 1
        return original(resource, body)

    def fetch(resource, body):
        rows, _ = archive.fetch(resource, body)
        keys = archive.contract[resource].get('keys')
        seen = set()
        for row in rows:
            key = archive.identity(resource, row, ()) if keys else digest(row)
            if key in seen:
                raise BlacklakeError('EXTRA_RESOURCE_DUPLICATE')
            seen.add(key)
            archive.normalized(resource, row, ())
        return rows

    def isolated(resource, work):
        """Run one resource refresh; isolate upstream/shape errors, propagate fatal ones."""
        try:
            work()
            state['errors'].pop(resource, None)
            return True
        except BudgetExhausted:
            raise
        except BlacklakeError as error:
            code = str(error)
            if code.startswith(FATAL):
                raise
            state['errors'][resource] = code
            return False

    client.query = limited
    try:
        for resource, interval, requests in plans(now, projects):
            if not due(state['at'], resource, interval, now, resource in state['errors']):
                continue
            def work(resource=resource, requests=requests):
                rows = []
                for body, scope in requests:
                    part = fetch(resource, body)
                    if scope and any(str(r.get(scope[0])) not in {str(v) for v in scope[1]}
                                     for r in part if r.get(scope[0]) is not None):
                        raise BlacklakeError('EXTRA_SCOPE_MISMATCH')
                    rows.extend(part)
                data[resource] = scrubbed(rows)
            ok = isolated(resource, work)
            state['at'][resource] = now.isoformat()
            if not ok:
                data.setdefault(resource, [])
        # Rolling: cost source detail per cost record, oldest/changed first.
        summary = {str(r['projectCostId']): r for r in data.get('project_cost', []) if r.get('projectCostId') is not None}
        details = [r for r in data.get('project_cost_detail', []) if str(r.get('projectCostId')) in summary]
        checked = {k: v for k, v in state['costChecked'].items() if k in summary}
        pending = sorted((k for k, r in summary.items()
                          if k not in checked or checked[k]['hash'] != digest(r) or due(checked[k], 'at', DAILY, now)),
                         key=lambda k: checked.get(k, {}).get('at', ''))
        for key in pending:
            if budget - used < len(COST_SOURCE_TYPES):
                break
            rows = []
            def work(key=key, rows=rows):
                for source in COST_SOURCE_TYPES:
                    part = fetch('project_cost_detail', {'projectCostId': int(key), 'costSourceType': source})
                    if any(str(r.get('projectCostId')) != key for r in part):
                        raise BlacklakeError('EXTRA_SCOPE_MISMATCH')
                    rows.extend(part)
            if isolated('project_cost_detail', work):
                details = [r for r in details if str(r.get('projectCostId')) != key] + scrubbed(rows)
            # Marked on failure too: previous rows are kept and the record is retried after 24 h.
            checked[key] = {'at': now.isoformat(), 'hash': digest(summary[key])}
        state['costChecked'] = checked
        data['project_cost_detail'] = sorted(details, key=lambda r: (str(r.get('projectCostId')), str(r.get('id'))))
        # Rolling: purchase/sale price lists per product (productCode is required upstream).
        codes = sorted({p['productCode'] for p in data.get('product', []) if p.get('productCode')})
        prices = {name: [r for r in data.get(name, []) if r.get('productCode') in codes]
                  for name in ('vendor_price', 'customer_price')}
        priced = {k: v for k, v in state['priceChecked'].items() if k in codes}
        for code in sorted((c for c in codes if c not in priced or due(priced, c, DAILY, now)), key=lambda c: priced.get(c, '')):
            if budget - used < 2:
                break
            fresh = {}
            def work(code=code, fresh=fresh):
                for name in ('vendor_price', 'customer_price'):
                    part = fetch(name, {'productCode': code})
                    if any(r.get('productCode') not in (None, code) for r in part):
                        raise BlacklakeError('EXTRA_SCOPE_MISMATCH')
                    fresh[name] = scrubbed(part)
            if isolated('price_list', work):
                for name in prices:
                    prices[name] = [r for r in prices[name] if r.get('productCode') != code] + fresh[name]
            priced[code] = now.isoformat()
        state['priceChecked'] = priced
        for name, rows in prices.items():
            data[name] = sorted(rows, key=lambda r: (str(r.get('productCode')), digest(r)))
    except BudgetExhausted:
        pass  # Remaining resources stay due and are picked up next cycle.
    finally:
        client.query = original
    return state, used
