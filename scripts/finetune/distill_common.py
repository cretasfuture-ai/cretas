"""INTENT-DISTILL-20261111 shared pieces: sample schema validation, read/write table,
JSONL io and a dollar-capped, resumable API client.

Nothing here trains a model, calls a product endpoint, or prints a credential.
"""
import datetime as dt
import hashlib
import json
import sys
import threading
import time
from pathlib import Path

ACTS = ('NEW_TASK', 'CHANGE_PARAM', 'PAGE', 'CANCEL', 'NEED_CLARIFY')
INTENT_ACTS = ('NEW_TASK', 'CHANGE_PARAM', 'PAGE')       # intent must be a candidate
CONTEXT_ACTS = ('CHANGE_PARAM', 'PAGE', 'CANCEL')        # need a previous task in state
NO_INTENT_ACTS = ('CANCEL', 'NEED_CLARIFY')              # intent must be empty
NONE_KEY = '__NONE__'                                   # probability mass on "no intent"
PARAM_SOURCES = ('explicit_turn', 'confirmed', 'default')  # priority: explicit > confirmed > default
SPLITS = ('train', 'validation', 'calibration')

LUNA = 'gpt-6-luna'
TERRA = 'gpt-6.1-terra'
GENERATOR = 'gpt-5.4-2026-03-05'
# Conservative list-price upper bounds in USD per 1M tokens (input, output). Terra uses the
# highest (cache-write) input price, the generator the highest output price used in the
# 2026-11-05 reconciliation; Luna Decisions is input-only. These are bounds, not invoices.
PRICES = {LUNA: (0.25, 0.0), TERRA: (2.5, 5.0), GENERATOR: (2.5, 15.0)}

ROOT = Path(__file__).resolve().parents[2]
CATALOG_PY = ROOT / 'backend' / 'python'
TOOL_IMPL = ROOT / 'backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl'


# ---------------------------------------------------------------- jsonl
def read_jsonl(path):
    path = Path(path)
    if not path.exists():
        return []
    return [json.loads(x) for x in path.read_text(encoding='utf-8').splitlines() if x.strip()]


def write_jsonl(path, rows):
    # compute first, then open: a failing expression must not truncate the target
    body = ''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(body)


def append_jsonl(path, row):
    with open(path, 'a', encoding='utf-8', newline='') as f:
        f.write(json.dumps(row, ensure_ascii=False) + '\n')


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


# ---------------------------------------------------------------- intent table
def build_intent_table(allow_unresolved=False):
    """code -> dict(name, description, family, route_type, access_mode, read_only).

    access_mode comes from each Tool's declared ``getAccessMode()`` (the CI-enforced binary
    declaration, ``ToolAccessModeDeclarationTest``), read by ``tool_access_modes.scan`` which
    follows superclasses and ignores comments. A SKILL intent is read-only only if every bound
    tool declares READ. Fail closed: if any training-label intent cannot be resolved to READ or
    WRITE the table refuses to build (``UnresolvedAccessMode``) instead of guessing.
    """
    sys.path.insert(0, str(CATALOG_PY))
    sys.path.insert(0, str(Path(__file__).parent))
    from ai import factory_catalog as fc
    from tool_access_modes import scan
    cat = fc.get_catalog()
    labels = fc.training_catalog_labels(cat)
    rows = cat['canonical']
    tools = set()
    for code in labels:
        r = rows[code]
        tools.update(r.get('skill_tools') or [] if r.get('route_type') == 'SKILL' else [r.get('tool_name')])
    tools.discard(None)
    mode = {k: v['mode'] for k, v in scan().items()}
    table = {}
    for code in labels:
        r = rows[code]
        names = (r.get('skill_tools') or []) if r.get('route_type') == 'SKILL' else [r.get('tool_name')]
        modes = [mode.get(n, 'UNRESOLVED') for n in names] if names else ['UNRESOLVED']
        if all(m == 'READ' for m in modes):
            access, ro = 'READ', True
        elif any(m == 'WRITE' for m in modes):
            access, ro = 'WRITE', False
        else:
            access, ro = 'UNRESOLVED', None
        table[code] = dict(code=code, name=r.get('intent_name') or code, description=r.get('description') or '',
                           family=family_of(code), route_type=r.get('route_type'), access_mode=access, read_only=ro)
    bad = {c: v for c, v in table.items() if v['access_mode'] == 'UNRESOLVED'}
    if bad and not allow_unresolved:
        raise UnresolvedAccessMode('intents without a resolvable Tool access mode: ' + ', '.join(sorted(bad)))
    return table


class UnresolvedAccessMode(RuntimeError):
    """A training-label intent whose Tool read/write declaration cannot be read from source."""


def family_of(code):
    """Semantic family = business domain prefix of the intent code; splits never cross it."""
    return code.split('_')[0]


def derive_read_only(intent, state, table):
    """read_only follows the label intent; with no intent, the task the user is acting on."""
    target = intent or (state or {}).get('prev_intent')
    return table[target]['read_only'] if target in table else None


# ---------------------------------------------------------------- schema
class SchemaError(ValueError):
    pass


def validate_state(state, table=None):
    if state is None:
        return
    if not isinstance(state, dict):
        raise SchemaError('state must be an object or null')
    for k in ('prev_intent', 'confirmed_params', 'pending_fields', 'last_action'):
        if k not in state:
            raise SchemaError('state missing ' + k)
    if state['prev_intent'] is not None and (not isinstance(state['prev_intent'], str)
                                             or (table is not None and state['prev_intent'] not in table)):
        raise SchemaError('state.prev_intent is not a known intent')
    if not isinstance(state['pending_fields'], list) or not all(isinstance(x, str) for x in state['pending_fields']):
        raise SchemaError('state.pending_fields must be a list of strings')
    if state['last_action'] is not None and not isinstance(state['last_action'], str):
        raise SchemaError('state.last_action must be string or null')
    if not isinstance(state['confirmed_params'], list):
        raise SchemaError('state.confirmed_params must be a list')
    for p in state['confirmed_params']:
        if not isinstance(p, dict) or set(p) != {'name', 'value', 'source'} or p['source'] not in PARAM_SOURCES:
            raise SchemaError('confirmed_params item needs name/value/source in ' + str(PARAM_SOURCES))
        if not isinstance(p['name'], str) or not isinstance(p['value'], str):
            raise SchemaError('confirmed_params name/value must be strings')


RAW_KEYS = {'id', 'utterance', 'state', 'candidates', 'read_only', 'family', 'generator_plan', 'source'}
LABEL_KEYS = RAW_KEYS | {'dialogue_act', 'intent', 'teacher_probs', 'teachers', 'agreement'}


def validate_sample(row, table, stage='labeled'):
    """Raise SchemaError unless ``row`` obeys the distillation sample contract.

    stage 'raw': generated, not yet labeled.  'labeled': both teachers agreed (silver train).
    'review': teachers disagreed; ``agreement`` False and labels are per-teacher only.
    """
    if stage not in ('raw', 'labeled', 'review'):
        raise SchemaError('bad stage')
    allowed = RAW_KEYS if stage == 'raw' else LABEL_KEYS | {'split', 'review_reason'}
    extra = set(row) - allowed
    if extra:
        raise SchemaError('unexpected keys: ' + ','.join(sorted(extra)))
    for k in RAW_KEYS - {'generator_plan', 'source'}:
        if k not in row:
            raise SchemaError('missing ' + k)
    if not isinstance(row['utterance'], str) or not row['utterance'].strip():
        raise SchemaError('utterance must be non-empty text')
    validate_state(row['state'], table)
    cands = row['candidates']
    if not isinstance(cands, list) or len(cands) < 2 or len(set(cands)) != len(cands) or any(c not in table for c in cands):
        raise SchemaError('candidates must be >=2 distinct known intent IDs')
    if row['read_only'] not in (True, False, None):
        raise SchemaError('read_only must be bool or null')
    if not isinstance(row['family'], str) or not row['family']:
        raise SchemaError('family required')
    if row['state'] and row['state']['prev_intent'] and row['state']['prev_intent'] not in cands:
        raise SchemaError('state.prev_intent must be among candidates')
    if stage == 'raw':
        return
    for k in ('dialogue_act', 'intent', 'teacher_probs', 'teachers', 'agreement'):
        if k not in row:
            raise SchemaError('missing ' + k)
    if stage == 'labeled' and row['agreement'] is not True:
        raise SchemaError('labeled (train) rows must have agreement=true')
    if stage == 'review' and row['agreement'] is not False:
        raise SchemaError('review rows must have agreement=false')
    if set(row['teachers']) != {'luna', 'terra'}:
        raise SchemaError('teachers must hold exactly luna and terra, each independent')
    for t in row['teachers'].values():
        if set(t) < {'dialogue_act', 'intent', 'model'}:
            raise SchemaError('teacher entry needs dialogue_act/intent/model')
    probs = row['teacher_probs']
    if not isinstance(probs, dict) or set(probs) - set(cands) - {NONE_KEY}:
        raise SchemaError('teacher_probs keys must be candidates or ' + NONE_KEY)
    if probs and not (0.97 <= sum(probs.values()) <= 1.03) or any(not 0 <= v <= 1 for v in probs.values()):
        raise SchemaError('teacher_probs must be a distribution')
    if stage == 'labeled':
        check_label(row['dialogue_act'], row['intent'], row['state'], cands)
        if row['read_only'] != derive_read_only(row['intent'], row['state'], table):
            raise SchemaError('read_only disagrees with the Tool access mode table')
        if 'split' in row and row['split'] not in SPLITS:
            raise SchemaError('bad split')


def check_label(act, intent, state, cands):
    """The label-level rules shared by schema validation and teacher agreement."""
    if act not in ACTS:
        raise SchemaError('dialogue_act must be one of ' + ','.join(ACTS))
    if act in NO_INTENT_ACTS and intent is not None:
        raise SchemaError(act + ' must have empty intent')
    if act in INTENT_ACTS and intent not in cands:
        raise SchemaError(act + ' needs an intent from candidates')
    if act in CONTEXT_ACTS and not (state and state.get('prev_intent')):
        raise SchemaError(act + ' needs a previous task in state')
    if act in ('CHANGE_PARAM', 'PAGE') and intent != state['prev_intent']:
        raise SchemaError(act + ' continues the previous intent')


# ---------------------------------------------------------------- API client
class ApiStop(RuntimeError):
    """Stop the whole run (rejected call, spend cap, unknown result)."""


class DollarClient:
    """Resumable client with a hard USD upper-bound cap and a persistent ledger.

    Every call reserves a conservative cost first; the ledger (``api-ledger.json``) and the
    receipts (``api-results.jsonl``, no credential, parsed body included) live in ``root``.
    An unresolved reservation or a failed receipt is never replayed automatically.
    """

    def __init__(self, root, cap_usd, concurrency=4, extra_spent_roots=(), key_file=None):
        import httpx
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.cap = cap_usd
        self.lock = threading.Lock()
        self.sem = threading.Semaphore(concurrency)
        self.stopped = False
        config = {}
        kf = Path(key_file) if key_file else Path.home() / '.codex/secrets/cretas-openai-eval.env'
        if not kf.exists():
            raise ApiStop('API key file not found; stop and report')
        for line in kf.read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                config[k.strip()] = v.strip().strip('"').strip("'")
        self._key = config.get('OPENAI_API_KEY')
        if not self._key:
            raise ApiStop('OPENAI_API_KEY missing in key file; stop and report')
        headers = {'Authorization': 'Bearer ' + self._key}
        for k, h in (('OPENAI_PROJECT_ID', 'OpenAI-Project'), ('OPENAI_ORGANIZATION_ID', 'OpenAI-Organization')):
            if config.get(k):
                headers[h] = config[k]
        self.http = httpx.Client(headers=headers, timeout=150, follow_redirects=False)
        self.results_path = self.root / 'api-results.jsonl'
        self.ledger_path = self.root / 'api-ledger.json'
        self.done = {r['id']: r for r in read_jsonl(self.results_path)}
        self.ledger = json.loads(self.ledger_path.read_text(encoding='utf-8')) if self.ledger_path.exists() else []
        if any(r['state'] == 'pending' for r in self.ledger):
            raise ApiStop('Unresolved request reservation; inspect before resume')
        self.spent = sum(r.get('price_upper_usd', 0) for r in self.done.values())
        for extra in extra_spent_roots:
            self.spent += sum(r.get('price_upper_usd', 0) for r in read_jsonl(Path(extra) / 'api-results.jsonl'))
        self.calls = 0

    def close(self):
        self.http.close()

    @staticmethod
    def cost(model, usage):
        i, o = PRICES[model]
        return ((usage.get('input_tokens') or 0) * i + (usage.get('output_tokens') or 0) * o) / 1e6

    def call(self, id, endpoint, payload, max_output_tokens=0):
        """POST /v1/<endpoint>; returns the parsed response body. endpoint: responses|decisions."""
        model = payload['model']
        encoded = json.dumps(payload, ensure_ascii=False)
        fingerprint = hashlib.sha256(encoded.encode()).hexdigest()
        with self.lock:
            if id in self.done:
                old = self.done[id]
                if old['payload_sha256'] != fingerprint:
                    raise ApiStop('Request changed after freeze: ' + id)
                if old.get('error') or 'response' not in old:
                    raise ApiStop('Failed receipt needs inspection, never replayed: ' + id)
                return old['response']
            if self.stopped:
                raise ApiStop('Previous request stopped the run')
            i, o = PRICES[model]
            # utf-8 bytes / 1 over-counts tokens for CJK; deliberately conservative
            reserve = (len(encoded.encode('utf-8')) * i + max_output_tokens * o) / 1e6
            if self.spent + reserve > self.cap:
                self.stopped = True
                raise ApiStop('Spend cap %.2f USD reached (spent %.4f, next reserve %.4f)' % (self.cap, self.spent, reserve))
            self.spent += reserve
            resv = dict(id=id, model=model, endpoint=endpoint, state='pending', reserved_price_usd=reserve,
                        payload_sha256=fingerprint, day=dt.datetime.now(dt.timezone.utc).date().isoformat())
            self.ledger.append(resv)
            self._persist()
        record = dict(id=id, model=model, endpoint=endpoint, payload_sha256=fingerprint, reserved_price_usd=reserve)
        start = time.perf_counter()
        try:
            with self.sem:
                response = self.http.post('https://api.openai.com/v1/' + endpoint, json=payload)
            record.update(status=response.status_code, request_id=response.headers.get('x-request-id'),
                          latency_ms=(time.perf_counter() - start) * 1000)
            body = response.json()
            if response.status_code != 200:
                raise ApiStop('HTTP %s from %s %s' % (response.status_code, endpoint, model))
            usage = body.get('usage')
            if not usage:
                raise ApiStop('Missing usage in response; cost unknown')
            actual = self.cost(model, usage)
            record.update(response=body, usage=usage, price_upper_usd=actual)
            if isinstance(body.get('created_at'), (int, float)):
                record['response_created_at'] = body['created_at']
            return body
        except Exception as exc:
            record['error'] = type(exc).__name__
            with self.lock:
                self.stopped = True
            raise
        finally:
            with self.lock:
                if 'status' in record:
                    resv['state'] = 'completed'
                    self.spent += record.get('price_upper_usd', reserve) - reserve
                    if 'price_upper_usd' not in record:
                        record['price_upper_usd'] = reserve   # failed but billed-unknown: keep reserve
                else:
                    resv['state'] = 'pending'      # no HTTP status: result unknown, never replay
                self._persist()
                text = json.dumps(record, ensure_ascii=False)
                if self._key in text:
                    raise ApiStop('Credential in output')
                append_jsonl(self.results_path, record)
                self.done[id] = record
                self.calls += 1

    def _persist(self):
        self.ledger_path.write_text(json.dumps(self.ledger, ensure_ascii=False, indent=2), encoding='utf-8')

    def summary(self):
        by = {}
        for r in self.done.values():
            m = by.setdefault(r['model'], dict(calls=0, input_tokens=0, output_tokens=0, price_upper_usd=0.0))
            m['calls'] += 1
            u = r.get('usage') or {}
            m['input_tokens'] += u.get('input_tokens') or 0
            m['output_tokens'] += u.get('output_tokens') or 0
            m['price_upper_usd'] += r.get('price_upper_usd', 0)
        return dict(total_calls=len(self.done), total_price_upper_usd=sum(m['price_upper_usd'] for m in by.values()),
                    cap_usd=self.cap, by_model=by)
