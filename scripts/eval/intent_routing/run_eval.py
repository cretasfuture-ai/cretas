#!/usr/bin/env python3
"""Intent-routing eval runner — exercises /api/mobile/{factoryId}/ai-intents/execute
against a fixed case set and grades each response programmatically.

Layout written (per variant dir, e.g. .claude/hillclimb/intent-routing/baseline/):
  results.jsonl   one row per (case, rep) — appended as each case completes; resume is
                  idempotent at (prompt_id, rep)
  traces/<id>_rep<k>.json   the exchange: user turn, assistant turn, raw request/response
  errors.jsonl    attempts that produced no scorable output (HTTP/timeout/parse), never in results
  summary.json    variant header (description / target / env fingerprint)

Grading (all programmatic, by case `kind`):
  intent          intent_ok = response.intentCode in acceptable
                  (UNSUPPORTED_CAPABILITY also accepted when status is a refusal-shaped status)
  intent+clarify  intent_ok as above; clarify_ok = status==NEED_CLARIFICATION or missingParameters
  clarify         clarify_ok = status==NEED_CLARIFICATION (intentRecognized false also counts)
  permission      permission_ok per expected:
                    PERMISSION_DENIED      -> status==NO_PERMISSION
                    APPROVAL_REQUIRED      -> requiresApproval==true or status in
                                              {WRITE_CONFIRM_REQUIRED, APPROVAL_REQUIRED}
                    CONFIRMATION_REQUIRED  -> status==WRITE_CONFIRM_REQUIRED
                    REJECTED               -> intentCode==UNSUPPORTED_CAPABILITY or status==NO_PERMISSION
  rag             rag_ok = metadata.routeType == expected, or (KNOWLEDGE_SEARCH and
                  intentCode==FOOD_KNOWLEDGE_QUERY)
`pass` (headline) = the kind's primary check (intent_ok / clarify_ok / permission_ok / rag_ok).

Usage:
  python scripts/eval/intent_routing/run_eval.py --out .claude/hillclimb/intent-routing/baseline \
      [--cases .claude/hillclimb/intent-routing/cases.jsonl] [--base-url http://localhost:10010] \
      [--factory LOCALUAT] [--reps 1] [--concurrency 6] [--ids L001,M002] [--limit 5] \
      [--description "baseline"] [--timeout 120]
Login: default account from --user/--password (super admin) for non-permission cases; permission
cases log in as the account named on the case. Passwords come from env EVAL_PASSWORD_<USERNAME>
or --password (local-only fixtures; never commit real credentials).
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

import requests

LOCK = threading.Lock()
REFUSAL_STATUSES = {"UNSUPPORTED", "UNSUPPORTED_CAPABILITY", "NO_MATCH", "UNRECOGNIZED", "REJECTED"}


def log(msg: str) -> None:
    sys.stderr.write(msg + "\n")
    sys.stderr.flush()


def git_sha(repo: Path) -> str:
    try:
        return subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--short", "HEAD"], text=True).strip()
    except Exception:
        return "unknown"


class Client:
    def __init__(self, base_url: str, factory: str, timeout: float):
        self.base = base_url.rstrip("/")
        self.factory = factory
        self.timeout = timeout
        self._tokens: dict[str, str] = {}
        self._lock = threading.Lock()

    def token(self, username: str, password: str) -> str:
        with self._lock:
            if username in self._tokens:
                return self._tokens[username]
        r = requests.post(f"{self.base}/api/mobile/auth/unified-login",
                          json={"username": username, "password": password}, timeout=self.timeout)
        r.raise_for_status()
        body = r.json()
        data = body.get("data") or {}
        tok = data.get("accessToken")
        if not tok:
            raise RuntimeError(f"login failed for {username}: {json.dumps(body, ensure_ascii=False)[:300]}")
        with self._lock:
            self._tokens[username] = tok
        return tok

    def execute(self, token: str, user_input: str, preview_only: bool) -> tuple[dict, float, int]:
        payload = {"userInput": user_input, "previewOnly": preview_only}
        t0 = time.perf_counter()
        r = requests.post(f"{self.base}/api/mobile/{self.factory}/ai-intents/execute",
                          json=payload, headers={"Authorization": f"Bearer {token}"}, timeout=self.timeout)
        dt = time.perf_counter() - t0
        try:
            body = r.json()
        except ValueError:
            raise RuntimeError(f"non-JSON response http={r.status_code}: {r.text[:300]}")
        if isinstance(body, dict):
            body["__correlation_id"] = r.headers.get("X-Correlation-ID")
        return body, dt, r.status_code


def grade(case: dict, resp: dict) -> tuple[dict, dict]:
    """Return (grade dict, explanation dict). resp is the unwrapped IntentExecuteResponse."""
    kind = case["kind"]
    acceptable = set(case.get("acceptable") or [])
    intent = resp.get("intentCode")
    status = resp.get("status")
    meta = resp.get("metadata") or {}
    g: dict = {}
    why: dict = {}

    def intent_ok() -> int:
        if intent in acceptable:
            return 1
        if "UNSUPPORTED_CAPABILITY" in acceptable and (status in REFUSAL_STATUSES or intent is None and status in REFUSAL_STATUSES):
            return 1
        return 0

    def clarify_ok() -> int:
        if status == "NEED_CLARIFICATION":
            return 1
        if resp.get("missingParameters") or resp.get("clarificationQuestions"):
            return 1
        return 0

    if kind == "intent":
        g["intent_ok"] = intent_ok()
        g["pass"] = g["intent_ok"]
        why["intent_ok"] = f"got intentCode={intent} status={status} matchMethod={resp.get('matchMethod')} conf={resp.get('confidence')}; acceptable={sorted(acceptable)}"
    elif kind == "intent+clarify":
        g["intent_ok"] = intent_ok()
        g["clarify_ok"] = clarify_ok()
        g["pass"] = g["intent_ok"]
        why["intent_ok"] = f"got intentCode={intent} status={status}; acceptable={sorted(acceptable)}"
        why["clarify_ok"] = f"status={status} missingParameters={bool(resp.get('missingParameters'))} clarificationQuestions={bool(resp.get('clarificationQuestions'))}"
    elif kind == "clarify":
        g["clarify_ok"] = clarify_ok()
        g["pass"] = g["clarify_ok"]
        why["clarify_ok"] = f"status={status} intentCode={intent} intentRecognized={resp.get('intentRecognized')}"
    elif kind == "permission":
        exp = case["expected"]
        ok = 0
        if exp == "PERMISSION_DENIED":
            ok = int(status == "NO_PERMISSION")
        elif exp == "APPROVAL_REQUIRED":
            ok = int(bool(resp.get("requiresApproval")) or status in {"WRITE_CONFIRM_REQUIRED", "APPROVAL_REQUIRED"})
        elif exp == "CONFIRMATION_REQUIRED":
            ok = int(status == "WRITE_CONFIRM_REQUIRED")
        elif exp == "REJECTED":
            ok = int(intent == "UNSUPPORTED_CAPABILITY" or status in {"NO_PERMISSION", "REJECTED"})
        g["permission_ok"] = ok
        g["pass"] = ok
        why["permission_ok"] = f"expected={exp}; got status={status} requiresApproval={resp.get('requiresApproval')} intentCode={intent} requiredPermission={resp.get('requiredPermission')}"
    elif kind == "rag":
        exp = case["expected"]
        route = meta.get("routeType")
        ok = int(route == exp or (exp == "KNOWLEDGE_SEARCH" and intent == "FOOD_KNOWLEDGE_QUERY"))
        g["rag_ok"] = ok
        g["pass"] = ok
        why["rag_ok"] = f"expected routeType={exp}; got routeType={route} consultationType={meta.get('consultationType')} intentCode={intent} status={status}"
    else:
        raise ValueError(f"unknown kind {kind}")
    return g, why


MATCH_RE = re.compile(r"识别到意图: code=([A-Z0-9_]+).*?matchMethod=([A-Z_]+), confidence=([0-9.]+)")
# short queries take a keyword-only branch that never reaches the 识别到意图 line
KEYWORD_ONLY_RE = re.compile(r"keyword-only classify: intent=([A-Z0-9_]+), score=\d+, conf=([0-9.]+)")
USAGE_RE = re.compile(r"\[LLM_USAGE\] slot=(\S+) path=(\S+) model=(\S+) prompt_tokens=(\d+) completion_tokens=(\d+)")


def annotate_stages(results_path: Path, java_log: Path) -> None:
    """Attach the backend's per-request routing lines (by X-Correlation-ID) to each row's meta and
    derive: match_method (from the orchestrator's 识别到意图 line), llm_fallback, embedding_unavailable,
    phrase_hit, and the Python/DashScope LLM usage (model + tokens) the request consumed."""
    rows = [json.loads(l) for l in results_path.read_text(encoding="utf-8").splitlines() if l.strip()]
    wanted = {r["meta"].get("correlation_id"): r for r in rows if r["meta"].get("correlation_id")}
    if not wanted:
        return
    lines_by: dict[str, list[str]] = {k: [] for k in wanted}
    ansi = re.compile(r"\x1b\[[0-9;]*m")
    skip = ("PerformanceMonitor", "JwtAuthInterceptor", "RateLimitAspect", "CorrelationIdFilter")
    with java_log.open(encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            for cid in wanted:
                if cid in line:
                    clean = ansi.sub("", line).rstrip()
                    if any(x in clean for x in skip):
                        break
                    m = re.search(r"\] \[f:[^\]]*\] (.*)$", clean)
                    lines_by[cid].append((m.group(1) if m else clean)[:600])
                    break
    for cid, r in wanted.items():
        ls = lines_by.get(cid, [])
        meta = r["meta"]
        meta["match_method"] = None
        meta["match_confidence"] = None
        meta["llm_fallback"] = any("LLM Fallback" in l or "LLM fallback" in l or "Python classify" in l for l in ls)
        meta["embedding_unavailable"] = any("Embedding client not available" in l for l in ls)
        meta["phrase_hit"] = any("短语匹配成功" in l for l in ls)
        models: list[str] = []
        pin = pout = 0
        for l in ls:
            mm = MATCH_RE.search(l)
            if mm and meta["match_method"] is None:
                meta["match_method"], meta["match_confidence"] = mm.group(2), float(mm.group(3))
            km = KEYWORD_ONLY_RE.search(l)
            if km and meta["match_method"] is None:
                meta["match_method"], meta["match_confidence"] = "KEYWORD", float(km.group(2))
            um = USAGE_RE.search(l)
            if um:
                models.append(um.group(3))
                pin += int(um.group(4))
                pout += int(um.group(5))
        r["usage"] = {"input_tokens": pin, "output_tokens": pout}
        meta["llm_models"] = sorted(set(models))
        meta["llm_calls"] = len(models)
        # The orchestrator's early phrase shortcut never logs a 识别到意图 line, so it shows up
        # as phrase_hit with no match_method; label it PHRASE_SHORTCUT so it is distinguishable
        # from the pipeline's own SEMANTIC/KEYWORD/LLM verdicts.
        if meta["match_method"] is None and any("语义相似度匹配" in l or "Semantic match found" in l for l in ls):
            meta["match_method"] = "SEMANTIC_SIM"   # v14 phrase-similarity matcher (no 识别到意图 line)
        stage = meta["match_method"] or ("PHRASE_SHORTCUT" if meta["phrase_hit"] else ("LLM" if meta["llm_fallback"] else None))
        if stage is None and meta.get("status") == "NEED_CLARIFICATION":
            stage = "CLARIFY"   # multi-candidate clarification path, no single recognition verdict
        meta["match_stage"] = stage
        meta["stage_log"] = [l for l in ls if not l.startswith("c.c.aims.service.impl.QueryPreprocessorServiceImpl")][:80]
        r["tags"] = r["tags"][:3] + [stage or "stage?"]
    with results_path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    log(f"annotated match_stage for {len(wanted)} rows from {java_log}")

def load_done(results_path: Path) -> set[tuple[str, int]]:
    done: set[tuple[str, int]] = set()
    if results_path.exists():
        for line in results_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                done.add((r["prompt_id"], int(r["rep"])))
    return done


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=".claude/hillclimb/intent-routing/cases.jsonl")
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-url", default=os.environ.get("EVAL_BASE_URL", "http://localhost:10010"))
    ap.add_argument("--factory", default=os.environ.get("EVAL_FACTORY", "LOCALUAT"))
    ap.add_argument("--user", default=os.environ.get("EVAL_USER", "factory_admin1"))
    ap.add_argument("--password", default=os.environ.get("EVAL_PASSWORD", "123456"))
    ap.add_argument("--reps", type=int, default=1)
    ap.add_argument("--concurrency", type=int, default=6)
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--ids", default="")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--kinds", default="", help="comma list to restrict kinds, e.g. intent,clarify")
    ap.add_argument("--description", default="")
    ap.add_argument("--preview-only", action="store_true", help="send previewOnly=true (default false: real path; write guard blocks execution)")
    ap.add_argument("--java-log", default=os.environ.get("EVAL_JAVA_LOG", ""), help="backend log file; per-request routing stage lines are extracted by X-Correlation-ID")
    ap.add_argument("--pre-hook", default=os.environ.get("EVAL_PRE_HOOK", ""), help="shell command run once before the pass (e.g. reset learned expressions)")
    ap.add_argument("--target", default="tools", help="system_prompt|skill|tools|code")
    args = ap.parse_args()

    out = Path(args.out)
    (out / "traces").mkdir(parents=True, exist_ok=True)
    results_path = out / "results.jsonl"
    errors_path = out / "errors.jsonl"
    progress_path = out / "progress.txt"

    cases = [json.loads(l) for l in Path(args.cases).read_text(encoding="utf-8").splitlines() if l.strip()]
    if args.ids:
        want = set(x.strip() for x in args.ids.split(",") if x.strip())
        cases = [c for c in cases if c["prompt_id"] in want]
    if args.kinds:
        want_k = set(x.strip() for x in args.kinds.split(",") if x.strip())
        cases = [c for c in cases if c["kind"] in want_k]
    if args.limit:
        cases = cases[: args.limit]

    repo = Path(__file__).resolve().parents[3]
    fingerprint = {"repo_sha": git_sha(repo), "base_url": args.base_url, "factory": args.factory,
                   "cases_file": args.cases, "n_cases": len(cases), "reps": args.reps,
                   "started_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
    summary = {"description": args.description or out.name, "target": args.target, "env": fingerprint}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    client = Client(args.base_url, args.factory, args.timeout)
    done = load_done(results_path)
    todo = [(c, k) for c in cases for k in range(args.reps) if (c["prompt_id"], k) not in done]
    total = len(cases) * args.reps
    log(f"resolved scope: {len(cases)} cases x {args.reps} reps = {total}; already done {len(done)}; to run {len(todo)}; "
        f"base={args.base_url} factory={args.factory} sha={fingerprint['repo_sha']}")

    if args.pre_hook:
        log(f"pre-hook: {args.pre_hook}")
        rc = subprocess.call(args.pre_hook, shell=True)
        if rc != 0:
            log(f"pre-hook failed rc={rc}; aborting before spending a pass")
            return 2

    # premise probe: default login must work before we spend a pass
    client.token(args.user, args.password)

    counter = {"done": len(done), "err": 0}
    t_start = time.time()

    def one(item):
        case, rep = item
        pid = case["prompt_id"]
        login = case.get("login")
        username = login["username"] if login else args.user
        password = (os.environ.get(f"EVAL_PASSWORD_{username.upper()}") or (login or {}).get("password") or args.password)
        preview = bool(args.preview_only)
        attempts = 0
        while True:
            attempts += 1
            try:
                tok = client.token(username, password)
                body, latency, http = client.execute(tok, case["prompt"], preview)
                if http >= 500 or http == 429:
                    raise RuntimeError(f"http {http}: {json.dumps(body, ensure_ascii=False)[:200]}")
                break
            except Exception as e:  # noqa: BLE001
                if attempts >= 3:
                    with LOCK:
                        counter["err"] += 1
                        with errors_path.open("a", encoding="utf-8") as fh:
                            fh.write(json.dumps({"prompt_id": pid, "rep": rep, "attempts": attempts,
                                                 "failure_class": "harness_or_serving", "error": str(e)[:500]},
                                                ensure_ascii=False) + "\n")
                    return
                time.sleep(1.5 * attempts + random.random())
        corr = body.get("__correlation_id") if isinstance(body, dict) else None
        resp = body.get("data") if isinstance(body, dict) and "data" in body else body
        if not isinstance(resp, dict):
            resp = {}
        g, why = grade(case, resp)
        row = {
            "prompt_id": pid, "rep": rep, "prompt": case["prompt"], "tags": [case["tags"][0], case["kind"], case.get("confidence", "")],
            "grade": g, "explanation": why,
            "model": f"cretas-intent-chain@{fingerprint['repo_sha']}",
            "status": "ok", "stop_reason": "end_turn",
            "latency_s": round(latency, 3), "http_status": http, "attempts": attempts,
            "usage": {"input_tokens": 0, "output_tokens": 0},
            "meta": {"kind": case["kind"], "expected": case.get("expected"), "acceptable": case.get("acceptable"),
                     "intentCode": resp.get("intentCode"), "status": resp.get("status"),
                     "matchMethod": resp.get("matchMethod"), "confidence": resp.get("confidence"),
                     "requiresApproval": resp.get("requiresApproval"), "routeType": (resp.get("metadata") or {}).get("routeType"),
                     "login_as": username, "previewOnly": preview, "old_label": case["meta"].get("old_label"),
                     "correlation_id": corr, "fromCache": resp.get("fromCache"), "cacheHitType": resp.get("cacheHitType")},
        }
        trace = [
            {"role": "user", "content": case["prompt"]},
            {"role": "tool_call", "name": "POST /ai-intents/execute", "content": json.dumps({"userInput": case["prompt"], "previewOnly": preview, "login_as": username}, ensure_ascii=False, indent=2)},
            {"role": "tool_result", "content": json.dumps(resp, ensure_ascii=False, indent=2)},
            {"role": "assistant", "content": str(resp.get("formattedText") or resp.get("message") or "")},
        ]
        with LOCK:
            (out / "traces" / f"{pid}_rep{rep}.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
            with results_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            counter["done"] += 1

    stop = threading.Event()

    def progress():
        while not stop.wait(30):
            el = time.time() - t_start
            rate = max(counter["done"] - len(done), 1) / max(el, 1)
            left = (total - counter["done"]) / rate if rate else 0
            line = f"{counter['done']}/{total} done, {counter['err']} err, ~{int(left)}s left"
            log(line)
            progress_path.write_text(line, encoding="utf-8")

    th = threading.Thread(target=progress, daemon=True)
    th.start()
    with cf.ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        list(ex.map(one, todo))
    stop.set()

    if args.java_log and Path(args.java_log).exists():
        annotate_stages(results_path, Path(args.java_log))

    rows = [json.loads(l) for l in results_path.read_text(encoding="utf-8").splitlines() if l.strip()]
    n = len(rows)
    if n:
        p = sum(r["grade"]["pass"] for r in rows) / n
        half = 1.0 / (n ** 0.5)
        by_kind: dict[str, list] = {}
        for r in rows:
            by_kind.setdefault(r["meta"]["kind"], []).append(r["grade"]["pass"])
        kinds = " ".join(f"{k}={sum(v)/len(v):.2f}({len(v)})" for k, v in sorted(by_kind.items()))
        print(f"RESULT pass={p:.3f} n={n} ci95~±{half:.3f} errors={counter['err']} | {kinds}")
    else:
        print(f"RESULT no rows; errors={counter['err']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
