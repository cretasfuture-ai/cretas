"""Loopback evaluation evidence only;not product state or an authorization source.

Serialized HTTP turns open a span. Async decision tasks bind that immutable span
before to_thread,so late model results remain attached to the original turn.
No user text,business rows or credentials are stored here.
"""
import contextvars
import functools
import json
import math
from pathlib import Path
import re
import threading
import time
import uuid

_lock=threading.RLock()
_bound=contextvars.ContextVar('semantic_evaluation_span',default=None)
_active=None
_spans={}
_path=None


def configure(path):
    global _path
    with _lock:
        if _spans:raise RuntimeError('Never reuse evaluation spans across campaigns')
        _path=Path(path)


def _persist():
    if _path is None:return
    data={'kind':'EVALUATION_ONLY_MODEL_EVIDENCE','spans':_spans}
    pending=_path.with_suffix('.tmp')
    pending.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    pending.replace(_path)


def begin(span):
    global _active
    if not isinstance(span,str) or not re.fullmatch(r'[A-Za-z0-9_:.-]{1,160}',span):raise ValueError('Invalid evaluation span')
    with _lock:
        if _active is not None or span in _spans:raise RuntimeError('Overlapping or replayed HTTP span')
        _active=span
        _spans[span]={'started_at':time.time(),'closed':False,'calls':[],'errors':[]}
        _persist()
        return {'ok':True,'span':span}


def bind_span():
    with _lock:
        if _active is None:raise RuntimeError('HTTP evaluation span was not opened')
        return _bound.set(_active)


def reset_span(token):
    _bound.reset(token)


def model_evidence_failures(timing):
    keys=('queryScopeUnavailable','requestPurposeUnavailable','decisionFallback',
          'decisionUnavailable','contextFallbackBlocked')
    failures=[key for key in keys if key in (timing or {})]
    if (timing or {}).get('shortlistOk')==0:failures.append('shortlistUnavailable')
    return failures


def mark_error(kind,span=None):
    with _lock:
        key=span or _bound.get() or _active
        if key is None or key not in _spans:raise RuntimeError('Unscoped evaluation error')
        _spans[key]['errors'].append({'kind':str(kind)[:120],'at':time.time()})
        _persist()


def end(span):
    global _active
    with _lock:
        if _active!=span:raise RuntimeError('Wrong HTTP evaluation span')
        if any(call['state']=='pending' for call in _spans[span]['calls']):
            _spans[span]['errors'].append({'kind':'pending_model_call_at_http_return','at':time.time()})
        _spans[span]['closed']=True
        _spans[span]['finished_at']=time.time()
        _active=None
        _persist()
        return {'ok':True,'span':span,**json.loads(json.dumps(_spans[span]))}


def _valid_probability(value):
    return not isinstance(value,bool) and isinstance(value,(int,float)) and math.isfinite(value) and 0<=value<=1


def _decision_errors(payload,body):
    answers=body.get('answers') or []
    errors=[]
    for question in payload.get('questions',[]):
        rows=[a for a in answers if a.get('name')==question.get('name')]
        if len(rows)!=1 or rows[0].get('type')!=question.get('type'):
            errors.append('decision_answer_missing_refused_or_wrong_type');continue
        answer=rows[0]
        if question['type']=='predicate' and not _valid_probability(answer.get('probability')):
            errors.append('decision_invalid_predicate_probability')
        if question['type']=='choice':
            probabilities=answer.get('probabilities') or []
            keys=[r.get('value') for r in probabilities]
            if len(keys)!=len(set(keys)) or set(keys)!={q['value'] for q in question['choices']} or any(not _valid_probability(r.get('probability')) for r in probabilities):
                errors.append('decision_invalid_choice_distribution')
    return errors


def wrap_ask(original):
    @functools.wraps(original)
    def wrapped(payload,endpoint,slot):
        with _lock:
            key=_bound.get() or _active
            if key is None or key not in _spans:raise RuntimeError('Model call outside an evaluation HTTP span')
            call={'id':uuid.uuid4().hex,'endpoint':endpoint,'slot':slot,'model':payload.get('model'),
                  'started_at':time.time(),'state':'pending'}
            if _spans[key]['closed']:
                call['state']='blocked_late'
                _spans[key]['calls'].append(call)
                _spans[key]['errors'].append({'kind':'model_call_after_http_return','at':time.time()})
                _persist()
                raise RuntimeError('Model call attempted after its HTTP span closed')
            _spans[key]['calls'].append(call);_persist()
        try:
            result=original(payload,endpoint,slot)
            errors=_decision_errors(payload,result) if endpoint=='decisions' else []
            with _lock:
                call.update(state='completed',finished_at=time.time())
                for error in errors:_spans[key]['errors'].append({'kind':error,'at':time.time()})
                _persist()
            return result
        except Exception as failure:
            with _lock:
                call.update(state='failed',finished_at=time.time(),error=type(failure).__name__)
                _spans[key]['errors'].append({'kind':'model_request_'+type(failure).__name__,'at':time.time()})
                _persist()
            raise
    return wrapped
