"""Evaluation-only model transport for the Python decision layer (INTENT-DISTILL line E).

Runs the production ``ai.orchestrator.Orchestrator`` / ``ai.matcher.decision.DecisionMatcher``
code unchanged and swaps only the wire: ``DecisionMatcher._call`` (every generative call:
request purpose, shortlist, merged understanding) goes to an OpenAI chat model, and
``DecisionMatcher.choose`` / ``clarify_predicate`` go to the Decisions endpoint (Luna) with the
production prompt builders. This mirrors the 2026-11-07 Codex development probe (mini for
generation, Luna for choice) so readings are comparable with it; it is NOT the production
provider chain (prod DECISION = aliyun qwen through ``common.llm_router``).

Every request is budget-reserved before it is sent and written to ``calls.jsonl`` (no
credential) with its label, model, tokens and latency. The label comes from a context
variable set by the wrapper methods, so concurrent calls inside one turn stay attributed.
"""
from __future__ import annotations

import asyncio
import contextvars
import json
import math
import pathlib
import random
import threading
import time
from typing import Any, Dict, Optional

import httpx

from ai.matcher.candidates import BlendRetriever
from ai.matcher.decision import (
    CLARIFY_KEY, LETTERS, NONE_KEY, SPECIAL_OPTIONS, DecisionMatcher, build_choice_messages, intent_text,
)

MINI = 'gpt-5.4-mini-2026-03-17'
LUNA = 'gpt-6-luna'
# Conservative list-price upper bounds, USD per 1M tokens (input, output). Cached input is
# priced as uncached on purpose: these are bounds, not invoices.
PRICES = {MINI: (1.0, 6.0), LUNA: (0.25, 0.0)}

LABEL: contextvars.ContextVar[str] = contextvars.ContextVar('decision_eval_label', default='unlabelled')
TURN: contextvars.ContextVar[Optional[list]] = contextvars.ContextVar('decision_eval_turn', default=None)
UNDERSTOOD: contextvars.ContextVar[Optional[list]] = contextvars.ContextVar('decision_eval_understood', default=None)


class BudgetStop(RuntimeError):
    pass


class QuotaWait(BudgetStop):
    """Kept for callers that catch it; the ledger no longer raises it (paid usage continues instead)."""


# Daily free-token groups (organizer 2026-11-11): never spill into paid usage.
DAILY_GROUP_LIMIT = {'gpt-5.4-2026-03-05': 1_000_000, 'gpt-5.4-mini-2026-03-17': 10_000_000}
GROUP_SAFETY = 0.9  # other sessions share the groups and are only partly visible here


def _quota_group(model: str) -> str:
    import sys as _sys
    root = pathlib.Path(__file__).resolve().parents[2] / 'scripts' / 'finetune'
    if str(root) not in _sys.path:
        _sys.path.insert(0, str(root))
    from factory_quota_accounting import quota_group  # read-only use; the module is owned by line F
    return quota_group(model)


def _utc_day(ts: float) -> str:
    import datetime as _dt
    return _dt.datetime.fromtimestamp(ts, _dt.timezone.utc).date().isoformat()


def _row_tokens(row: Dict[str, Any]) -> int:
    u = row.get('usage') or {}
    return int((u.get('prompt_tokens', u.get('input_tokens', 0)) or 0) + (u.get('completion_tokens', u.get('output_tokens', 0)) or 0))


def _row_day(row: Dict[str, Any]) -> Optional[str]:
    if isinstance(row.get('at'), (int, float)):
        return _utc_day(row['at'])
    resp = row.get('response') if isinstance(row.get('response'), dict) else {}
    created = row.get('response_created_at') or resp.get('created_at') or resp.get('created')
    if isinstance(created, (int, float)):
        return _utc_day(created)
    return row.get('day')


def _read_key_file(path: pathlib.Path) -> Dict[str, str]:
    out = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


class Ledger:
    """Reserve-before-send budget; spent survives restarts through ``calls.jsonl``."""

    def __init__(self, root: pathlib.Path, cap_usd: float, key_file: pathlib.Path, concurrency: int = 8,
                 quota_roots=(), paid: bool = False):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self.cap = cap_usd
        self.lock = threading.Lock()
        self.sem = asyncio.Semaphore(concurrency)
        cfg = _read_key_file(key_file)
        self._key = cfg.get('OPENAI_API_KEY') or ''
        if not self._key:
            raise BudgetStop('OPENAI_API_KEY missing')
        headers = {'Authorization': 'Bearer ' + self._key, 'Accept-Encoding': 'gzip'}
        for k, h in (('OPENAI_PROJECT_ID', 'OpenAI-Project'), ('OPENAI_ORGANIZATION_ID', 'OpenAI-Organization')):
            if cfg.get(k):
                headers[h] = cfg[k]
        self.http = httpx.AsyncClient(headers=headers, timeout=120)
        self.calls_path = root / 'calls.jsonl'
        self.spent = 0.0
        if self.calls_path.exists():
            for line in self.calls_path.read_text(encoding='utf-8').splitlines():
                self.spent += json.loads(line).get('price_upper_usd', 0.0)
        self.extra_spent = 0.0
        # paid=True (organizer 2026-11-11, Steve): skip the free-group stop, keep the USD cap, tag every row.
        self.paid = paid
        # UTC-day free-group usage: this ledger + every other ledger passed in (E runs, F teacher runs).
        self.group_used: Dict[tuple, int] = {}
        files = [self.calls_path] + [pathlib.Path(q) for q in quota_roots]
        for f in files:
            if not f.exists():
                continue
            for line in f.read_text(encoding='utf-8').splitlines():
                if not line.strip():
                    continue
                row = json.loads(line)
                if not row.get('model') or not row.get('usage'):
                    continue
                day = _row_day(row)
                if day is None:
                    raise BudgetStop('ledger row without UTC day evidence in ' + str(f))
                key = (day, _quota_group(row['model']))
                self.group_used[key] = self.group_used.get(key, 0) + _row_tokens(row)

    @staticmethod
    def cost(model: str, usage: Dict[str, Any]) -> float:
        i, o = PRICES[model]
        tin = usage.get('prompt_tokens', usage.get('input_tokens', 0)) or 0
        tout = usage.get('completion_tokens', usage.get('output_tokens', 0)) or 0
        return (tin * i + tout * o) / 1e6

    async def post(self, endpoint: str, payload: Dict[str, Any], max_out: int) -> Dict[str, Any]:
        model = payload['model']
        encoded = json.dumps(payload, ensure_ascii=False)
        i, o = PRICES[model]
        reserve = (len(encoded.encode('utf-8')) * i + max_out * o) / 1e6
        group = _quota_group(model)
        day = _utc_day(time.time())
        reserve_tokens = len(encoded.encode('utf-8')) // 2 + max_out
        with self.lock:
            used = self.group_used.get((day, group), 0)
            # Free group first; once it is used up, continue on paid usage (Steve 2026-11-11: never stop to
            # wait for the free quota). The USD cap below still applies; each row says which it used.
            billing = 'paid' if (self.paid or used + reserve_tokens > DAILY_GROUP_LIMIT[group] * GROUP_SAFETY)                 else 'free_group'
            self.group_used[(day, group)] = used + reserve_tokens
            if self.spent + self.extra_spent + reserve > self.cap:
                raise BudgetStop('cap %.2f reached (spent %.4f)' % (self.cap, self.spent + self.extra_spent))
            self.spent += reserve
        label = LABEL.get()
        rec: Dict[str, Any] = dict(at=time.time(), label=label, model=model, endpoint=endpoint, billing=billing)
        t = time.perf_counter()
        try:
            async with self.sem:
                t = time.perf_counter()
                r = await self.http.post('https://api.openai.com/v1/' + endpoint, json=payload)
            rec['latency_ms'] = round((time.perf_counter() - t) * 1000, 1)
            rec['status'] = r.status_code
            body = r.json()
            if r.status_code != 200:
                rec['error'] = str(body.get('error', body))[:300]
                raise RuntimeError('HTTP %s %s: %s' % (r.status_code, endpoint, rec['error']))
            usage = body.get('usage') or {}
            rec['usage'] = usage
            rec['price_upper_usd'] = self.cost(model, usage) if usage else reserve
            return body
        except Exception as ex:  # noqa: BLE001
            rec.setdefault('error', type(ex).__name__ + ': ' + str(ex)[:200])
            rec.setdefault('price_upper_usd', reserve)
            raise
        finally:
            with self.lock:
                self.group_used[(day, group)] = self.group_used.get((day, group), 0) - reserve_tokens + _row_tokens(rec)
                self.spent += rec['price_upper_usd'] - reserve
                line = json.dumps(rec, ensure_ascii=False)
                if self._key and self._key in line:
                    raise BudgetStop('credential in record')
                with self.calls_path.open('a', encoding='utf-8') as f:
                    f.write(line + '\n')
            turn = TURN.get()
            if turn is not None:
                turn.append(rec)

    async def close(self):
        await self.http.aclose()


def _usage_tokens(usage: Dict[str, Any]):
    tin = usage.get('prompt_tokens', usage.get('input_tokens', 0)) or 0
    tout = usage.get('completion_tokens', usage.get('output_tokens', 0)) or 0
    details = usage.get('prompt_tokens_details') or usage.get('input_tokens_details') or {}
    return tin, tout, details.get('cached_tokens', 0) or 0


def labelled(label: str):
    def deco(fn):
        async def wrapper(*args, **kwargs):
            token = LABEL.set(label)
            try:
                return await fn(*args, **kwargs)
            finally:
                LABEL.reset(token)
        return wrapper
    return deco


def decision_type(ledger: Ledger, chat_model: str = MINI, effort: str = 'low'):
    """DecisionMatcher subclass whose wire is the evaluation transport."""

    class EvalDecision(DecisionMatcher):
        async def _call(self, payload, body_validator=None, content_validator=None):
            req: Dict[str, Any] = dict(model=chat_model, messages=[
                {'role': m['role'], 'content': m['content']} for m in payload['messages'] if not m.get('partial')],
                reasoning_effort=effort, max_completion_tokens=max(int(payload.get('max_tokens') or 256), 64) + 512)
            if payload.get('response_format'):
                req['response_format'] = payload['response_format']
            body = await ledger.post('chat/completions', req, req['max_completion_tokens'])
            content = ((body.get('choices') or [{}])[0].get('message') or {}).get('content') or ''
            if body_validator and body_validator(body):
                raise ValueError('model body failed production validator')
            if content_validator and content_validator(content):
                raise ValueError('model content failed production validator: ' + content[:120])
            return body

        request_purpose = labelled('purpose')(DecisionMatcher.request_purpose)
        shortlist = labelled('shortlist')(DecisionMatcher.shortlist)
        if hasattr(DecisionMatcher, 'understand'):
            @labelled('understand')
            async def understand(self, *args, **kwargs):
                u = await DecisionMatcher.understand(self, *args, **kwargs)
                sink = UNDERSTOOD.get()
                if sink is not None:
                    import dataclasses
                    sink.append(None if u is None else dict(dataclasses.asdict(u), purpose=u.purpose.value))
                return u

        @labelled('choice')
        async def choose(self, query, role, options, history=None, special_options=False):
            """Production option construction; Luna native choice replaces chat top_logprobs."""
            if not options:
                return None
            n_special = len(SPECIAL_OPTIONS) if special_options else 0
            opts = list(options[: self.k - n_special])
            if len(opts) == 1 and not n_special:
                return {str(opts[0]['intent_code']): 1.0}
            random.Random(query).shuffle(opts)
            if n_special:
                opts = opts + SPECIAL_OPTIONS
            messages = build_choice_messages(query, role, opts, history)
            mapping = {}
            for i, (letter, row) in enumerate(zip(LETTERS, opts)):
                code = str(row['intent_code'])
                if i >= len(opts) - n_special:
                    code = {'NONE': NONE_KEY, 'CLARIFY': CLARIFY_KEY}[code]
                mapping[letter] = code
            choices = [dict(value=letter, description=f"{row['intent_code']}：{intent_text(row)}")
                       for letter, row in zip(LETTERS, opts)]
            payload = dict(model=LUNA, input=messages[1]['content'], questions=[dict(
                type='choice', name='intent', instructions=messages[0]['content'], choices=choices)])
            body = await ledger.post('decisions', payload, 0)
            answer = next((a for a in body.get('answers', []) if a.get('name') == 'intent'), None)
            if answer is None:
                return None
            probs = {r['value']: float(r['probability']) for r in answer.get('probabilities', [])}
            if set(probs) != set(mapping) or any(not math.isfinite(p) or not 0 <= p <= 1 for p in probs.values()):
                raise ValueError('choice distribution mismatch')
            return {mapping[k]: p for k, p in probs.items()}

        @labelled('predicate')
        async def clarify_predicate(self, instructions: str, text: str) -> Optional[float]:
            payload = dict(model=LUNA, input=text, questions=[dict(type='predicate', name='needs_clarification',
                                                                   instructions=instructions)])
            body = await ledger.post('decisions', payload, 0)
            ans = next((a for a in body.get('answers', []) if a.get('name') == 'needs_clarification'), None)
            if not ans:
                return None
            p = ans.get('probability')
            if isinstance(p, bool) or not isinstance(p, (int, float)) or not math.isfinite(p) or not 0 <= p <= 1:
                return None
            return float(p)

    return EvalDecision


def qwen_decision_type():
    """DecisionMatcher on the production ``llm_router`` DECISION chain (qwen), traced per call.

    Nothing is swapped: prompts, egress redaction and the provider chain are production. The
    wrapper only records label, model, tokens and latency of each call into the turn sink.
    """

    class QwenDecision(DecisionMatcher):
        async def _call(self, payload, body_validator=None, content_validator=None):
            label = LABEL.get()
            rec: Dict[str, Any] = dict(at=time.time(), label=label, model='llm_router:decision', endpoint='call_chain')
            t = time.perf_counter()
            try:
                body = await DecisionMatcher._call(self, payload, body_validator, content_validator)
                rec['latency_ms'] = round((time.perf_counter() - t) * 1000, 1)
                rec['model'] = 'llm_router:' + str(body.get('model') or 'decision')
                rec['usage'] = body.get('usage') or {}
                return body
            except Exception as ex:  # noqa: BLE001
                rec['latency_ms'] = round((time.perf_counter() - t) * 1000, 1)
                rec['error'] = type(ex).__name__ + ': ' + str(ex)[:200]
                raise
            finally:
                turn = TURN.get()
                if turn is not None:
                    turn.append(rec)

        request_purpose = labelled('purpose')(DecisionMatcher.request_purpose)
        shortlist = labelled('shortlist')(DecisionMatcher.shortlist)
        choose = labelled('choice')(DecisionMatcher.choose)

    return QwenDecision


def load_llm_env(path: str) -> None:
    """Export only ``LLM_*`` keys from a local .env into this process (values never printed)."""
    import os
    for line in pathlib.Path(path).read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            k, v = line.split('=', 1)
            k, v = k.strip(), v.strip().strip('"').strip("'")
            if k.startswith('LLM_') and v:
                os.environ[k] = v


class SnapshotRetriever(BlendRetriever):
    """Prod candidate stage replayed from ``retrieval_snapshot_remote.py`` output.

    ``match()`` returns the BlendRetriever top-20; ``match(top_k=pool)`` returns the rerank
    top-10 first, then blend, so ``SnapshotRerank`` reproduces the prod rerank order. A query
    whose rerank call failed in the snapshot returns ``None`` from the rerank stub, which makes
    the orchestrator fall back to the LLM shortlist exactly as prod does.
    """

    def __init__(self, snapshot: Dict[str, Any]):  # noqa: super not called: no pool, replay only
        self.pool, self.top_k, self.blend = None, 20, 0.35
        self.snapshot = snapshot
        self.last_rerank: Dict[str, int] = {}

    def _rows(self, query):
        rec = self.snapshot.get(query)
        if rec is None:
            raise KeyError('query not in retrieval snapshot: ' + query[:40])
        return rec

    async def match(self, query, factoryId=None, businessType=None, top_k=None):
        from ai.dto import CandidateIntentDto, MatchMethod
        rec = self._rows(query)
        blend = [(c, s) for c, s in rec.get('blend') or []]
        if top_k is None:
            items = blend
        else:
            rr = rec.get('rerank') or []
            self.last_rerank[query] = len(rr)
            seen, items = set(), []
            for c, s in [(c, s) for c, s in rr] + blend:
                if c not in seen:
                    seen.add(c)
                    items.append((c, s))
            items = items[:top_k]
        return [CandidateIntentDto(intentCode=c, confidence=float(s), matchMethod=MatchMethod.SEMANTIC) for c, s in items]


class SnapshotRerank:
    def __init__(self, retriever: SnapshotRetriever):
        self.retriever = retriever
        self.query = None

    async def scores(self, query, docs):
        rec = self.retriever._rows(query)
        if not rec.get('rerank'):
            return None
        n = len(rec['rerank'])
        return [float(100 - i) if i < n else -1.0 for i in range(len(docs))]


__all__ = ['Ledger', 'BudgetStop', 'QuotaWait', 'decision_type', 'TURN', 'UNDERSTOOD', 'LABEL', 'MINI', 'LUNA', '_usage_tokens']
