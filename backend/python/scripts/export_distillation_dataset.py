"""Export the distillation training corpus as JSONL, bucketed by business_type.

Pulls (input → strong-teacher-output) pairs from ``smart_bi_distillation_samples``
(written by Fix2 materialization, see llm_materializer._persist_distillation_sample)
and writes one JSONL file per business_type in chat-messages SFT format, ready for
LoRA / distillation when volume thresholds are met (see memory
project_2026_05_31_vertical_model_strategy_verdict: ≥1000-2000 high-quality samples
per business_type, or monthly fallback >5M tokens).

Each JSONL line:
    {"messages": [
        {"role": "system",    "content": <system_prompt>},
        {"role": "user",      "content": <input_text>},
        {"role": "assistant", "content": <teacher_output>}
     ],
     "meta": {"source": ..., "business_type": ..., "factory_id": ...,
              "task_type": ..., "template_codes": ..., "teacher_model": ...,
              "created_at": ...}}

Run on the server (DB env from the app's .env):
    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python scripts/export_distillation_dataset.py --out /tmp/distill_export

Rows are already deduped at write time (UNIQUE input_hash, ON CONFLICT refresh),
so no dedup needed here. NO RLS on the table → reads all factories cross-business.

训练规则 (Steve 2026-10-24, 见 smartbi/services/training_consent.py):
  - 真实行必须带 metadata.training_rule (采集时已过新规则); 之前采集的旧行一律排除,
    等补检。导出时再查一次 qa_has_blocked_content (工艺参数 / 结构 / 问题处理 /
    人员薪酬), 命中排除 —— 任何工厂, 授权与否。
  - SHARED 导出 (默认, 按 business_type 分桶):
      * 合成行 (SEED_R/SEED_F / metadata.source_kind='synthetic_seed') → 收入。
      * metadata.desensitized 行 (未授权工厂, 采集时已脱敏) → 收入。
      * shared_anon 工厂的原始行 → 经 desensitize_for_training 脱敏 (名称换通用标记、
        数字全部换占位符) 后收入。
      * private 工厂的原始行 → 不进共享导出 (只给它自己的专属模型)。
  - PRIVATE 导出 (--factory F001): 仅当该工厂 consent_scope='private' 时导出其**原始**行
    (不脱敏), 输出 dataset_private_{factory}.jsonl; 否则拒绝并给出清晰提示。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, List, Mapping, Optional

# Path bootstrap — importing smartbi.services.corpus_anonymize triggers smartbi/services/__init__
# which uses bare `from services.X import ...`; mirror main.py so it resolves to smartbi/services/X.
_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from smartbi.services.process_secret_names import (  # noqa: E402
    all_protected_product_names,
    mentions_any,
)
from smartbi.services.training_consent import (  # noqa: E402 (after path bootstrap)
    DESENSITIZED_KEY,
    RULE_KEY,
    SYNTHETIC_FACTORY_IDS,
    WITHDRAWN,
    fetch_consent_map,
    qa_has_blocked_content,
    scope_from_map,
)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("export_distill")


async def _fetch_rows(
    source: Optional[str],
    business_type: Optional[str],
    min_quality: Optional[int],
    pool=None,
    factory_id: Optional[str] = None,
) -> List[Dict[str, Any]]:
    if pool is None:
        from smartbi.config import get_pg_pool

        pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("postgres pool unavailable (check DB env)")

    where = [
        "teacher_output IS NOT NULL",
        "input_text IS NOT NULL",
        # P2 eval-freeze exclusion: rows marked eval_frozen=true are part of
        # the held-out eval slice (G3 spot-check asset).  They must NEVER leak
        # into the training set so the eval benchmark stays uncontaminated.
        # We exclude them via: NOT (metadata ? 'eval_frozen') covers the case
        # where the key is absent; the IS DISTINCT FROM 'true' arm covers rows
        # where the key exists but the value is not true (defensive).
        "(metadata IS NULL OR NOT (metadata ? 'eval_frozen')"
        " OR (metadata->>'eval_frozen') IS DISTINCT FROM 'true')",
    ]
    args: List[Any] = []
    if source:
        args.append(source)
        where.append(f"source = ${len(args)}")
    if business_type:
        args.append(business_type)
        where.append(f"business_type = ${len(args)}")
    if min_quality is not None:
        args.append(min_quality)
        # P0-1 fix: NULL rows are EXCLUDED — only rows with quality >= N are
        # exported.  The old "(quality IS NULL OR quality >= N)" let every
        # unscored row leak into the training set, making the quality gate a
        # no-op.  NULL means "not yet scored / polluted bare-query" and must
        # never reach training.
        where.append(f"quality >= ${len(args)}")
    if factory_id:
        args.append(factory_id)
        where.append(f"factory_id = ${len(args)}")

    sql = f"""
        SELECT id, source, business_type, factory_id, task_type, template_codes,
               system_prompt, input_text, teacher_model, teacher_output,
               created_at, metadata
        FROM smart_bi_distillation_samples
        WHERE {' AND '.join(where)}
        ORDER BY business_type, created_at
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, *args)
    return [dict(r) for r in rows]


# Sentinel factory IDs marking synthetic seed corpus (see seed_chart_insight_corpus.py).
# Defined once in smartbi.services.training_consent (capture uses the same set).
_SENTINEL_FACTORIES = SYNTHETIC_FACTORY_IDS


def _is_synthetic(row: Dict[str, Any]) -> bool:
    """Synthetic-seed rows are UNCONDITIONALLY usable for shared training — they
    belong to no real tenant. Detection mirrors corpus_census / seeder tagging:
    metadata.source_kind == 'synthetic_seed' / metadata.seeded == true / factory
    id in SEED_R/SEED_F. A row tagged with a real tenant's factory_id is never
    synthetic, whatever its metadata says (metadata is caller-supplied)."""
    fid = row.get("factory_id") or ""
    if fid in _SENTINEL_FACTORIES:
        return True
    if fid:
        return False
    meta = row.get("metadata")
    if isinstance(meta, str):
        try:
            meta = json.loads(meta)
        except (ValueError, TypeError):
            meta = None
    if isinstance(meta, dict):
        sk = meta.get("source_kind")
        if isinstance(sk, str) and sk.lower() in ("synthetic_seed", "synthetic"):
            return True
        if meta.get("seeded") is True:
            return True
    return False


async def _load_consent(pool) -> Dict[str, str]:
    """Return {factory_id: consent_scope} from training_consent.

    Missing factory = no row = default-deny ('none'). If the table does not
    exist yet (migration not applied), returns {} → every real tenant is
    treated as 'none' (still default-deny, never a fail-open).
    """
    try:
        async with pool.acquire() as conn:
            return await fetch_consent_map(conn)
    except Exception as e:  # table absent / permission → treat as no consent
        logger.warning(
            "training_consent unreadable (%s) — treating ALL real tenants as "
            "'none' (default-deny). Apply V20261001_01 migration if unexpected.",
            e,
        )
        return {}


def _consent_of(row: Dict[str, Any], consent: Dict[str, str]) -> str:
    """consent_scope for a row's tenant; absent factory → 'none' (default-deny)."""
    return scope_from_map(consent, row.get("factory_id"))


def _to_sft_line(
    row: Dict[str, Any],
    *,
    input_text: Optional[str] = None,
    teacher_output: Optional[str] = None,
    consent_class: str = "synthetic",
) -> Dict[str, Any]:
    """Build one SFT JSONL line.

    ``input_text`` / ``teacher_output`` override the row's raw values (used to
    inject anonymized text for shared_anon real rows). ``consent_class`` tags
    how the row qualified ('synthetic' | 'shared_anon' | 'private') for audit.
    """
    in_txt = input_text if input_text is not None else row["input_text"]
    out_txt = teacher_output if teacher_output is not None else row["teacher_output"]
    messages = []
    if row.get("system_prompt"):
        messages.append({"role": "system", "content": row["system_prompt"]})
    messages.append({"role": "user", "content": in_txt})
    messages.append({"role": "assistant", "content": out_txt})
    return {
        "messages": messages,
        "meta": {
            "id": row.get("id"),
            "source": row.get("source"),
            "business_type": row.get("business_type"),
            "factory_id": row.get("factory_id"),
            "task_type": row.get("task_type"),
            "template_codes": row.get("template_codes"),
            "teacher_model": row.get("teacher_model"),
            "created_at": row["created_at"].isoformat() if row.get("created_at") else None,
            "consent_class": consent_class,
            "anonymized": consent_class in ("shared_anon", "desensitized"),
        },
    }


def _metadata(row: Dict[str, Any]) -> Dict[str, Any]:
    meta = row.get("metadata")
    if isinstance(meta, str):
        try:
            meta = json.loads(meta)
        except (ValueError, TypeError):
            meta = None
    return meta if isinstance(meta, dict) else {}


def passes_training_rule(row: Dict[str, Any],
                         protected_names: Optional[Mapping[str, Any]] = None) -> bool:
    """A real row may be exported only if it was captured under the 2026-10-24
    rule and still has no blocked content (re-checked here, defense in depth),
    and it does not name one of its factory's process-secret products (privacy
    clause 6; rows captured before the product was protected are caught here)."""
    if RULE_KEY not in _metadata(row):
        return False
    if qa_has_blocked_content(row.get("input_text"), row.get("teacher_output")):
        return False
    names = (protected_names or {}).get(row.get("factory_id") or "", ())
    return not mentions_any(names, row.get("input_text"), row.get("teacher_output"))


def gate_shared_rows(
    rows: List[Dict[str, Any]],
    consent: Dict[str, str],
    protected_names: Optional[Mapping[str, Any]] = None,
) -> List[Dict[str, Any]]:
    """Gate rows for the SHARED (cross-tenant) export.

    Synthetic -> raw. Real rows must pass the training rule; then desensitized
    rows -> as stored, shared_anon raw rows -> anonymized, private raw rows ->
    excluded (own model only).

    Returned items: {"row", "input_text", "teacher_output", "consent_class"}.
    """
    from smartbi.services.corpus_anonymize import desensitize_for_training

    out: List[Dict[str, Any]] = []
    for r in rows:
        if _is_synthetic(r):
            out.append({"row": r, "input_text": None, "teacher_output": None,
                        "consent_class": "synthetic"})
            continue
        if not passes_training_rule(r, protected_names):
            continue
        if _consent_of(r, consent) == WITHDRAWN:
            continue  # withdrawn factories leave every model update (clause 10)
        if _metadata(r).get(DESENSITIZED_KEY):
            out.append({"row": r, "input_text": None, "teacher_output": None,
                        "consent_class": "desensitized"})
            continue
        scope = _consent_of(r, consent)
        if scope == "shared_anon":
            anon_in, anon_out = desensitize_for_training(
                r.get("input_text") or "",
                r.get("teacher_output") or "",
            )
            out.append({"row": r, "input_text": anon_in, "teacher_output": anon_out,
                        "consent_class": "shared_anon"})
        # private raw rows stay out of the shared export
    return out


async def _export_private(args, pool) -> None:
    """PRIVATE single-tenant export: raw rows for one factory, ONLY if it has
    consent_scope='private'. Else refuse with a clear message."""
    consent = await _load_consent(pool)
    scope = scope_from_map(consent, args.factory)
    if scope != "private":
        logger.error(
            "REFUSED: factory %s has consent_scope=%r — private raw export "
            "requires 'private'. Authorize first:\n"
            "  python -m scripts.set_training_consent --factory %s --scope private --by <name>",
            args.factory, scope, args.factory,
        )
        return

    rows = await _fetch_rows(args.source, args.business_type, args.min_quality,
                             pool=pool, factory_id=args.factory)
    # process content and process-secret products never enter any set, the
    # factory's own model included
    protected = await all_protected_product_names()
    rows = [r for r in rows if passes_training_rule(r, protected)]
    if not rows:
        logger.info("No samples for factory %s — nothing to export.", args.factory)
        return

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"dataset_private_{args.factory}.jsonl")
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            # RAW — private model uses the tenant's own exact data, no anonymization.
            f.write(json.dumps(_to_sft_line(r, consent_class="private"),
                               ensure_ascii=False) + "\n")
    logger.info(
        "PRIVATE export: %d RAW samples (factory %s, consent=private) -> %s",
        len(rows), args.factory, path,
    )


async def main() -> None:
    ap = argparse.ArgumentParser(description="Export distillation corpus as JSONL by business_type")
    ap.add_argument("--out", default="./distill_export", help="output directory")
    ap.add_argument("--source", default=None, help="filter by source (materialization|fallback|manual)")
    ap.add_argument("--business-type", default=None, help="filter by business_type")
    ap.add_argument("--min-quality", type=int, default=4,
                    help="keep rows with quality >= N (default 4; NULL/below excluded). "
                         "Pass --min-quality 1 to include lower-quality rows explicitly.")
    ap.add_argument("--factory", default=None,
                    help="PRIVATE mode: export ONLY this factory's RAW rows, "
                         "requires its consent_scope='private'")
    args = ap.parse_args()

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("postgres pool unavailable (check DB env)")

    # PRIVATE single-tenant path.
    if args.factory:
        await _export_private(args, pool)
        return

    # SHARED (default) path: consent-gated, by business_type.
    consent = await _load_consent(pool)
    rows = await _fetch_rows(args.source, args.business_type, args.min_quality, pool=pool)
    if not rows:
        logger.info("No samples matched — nothing to export.")
        return

    protected = await all_protected_product_names()   # failure aborts the export
    gated = gate_shared_rows(rows, consent, protected)
    excluded = len(rows) - len(gated)
    if not gated:
        logger.info(
            "No rows passed consent-gate (default-deny). %d real rows excluded "
            "(no 'shared_anon' consent); no synthetic rows present.", excluded,
        )
        return

    os.makedirs(args.out, exist_ok=True)
    # Bucket by business_type → one JSONL file each.
    buckets: Dict[str, List[Dict[str, Any]]] = {}
    for item in gated:
        bt = item["row"].get("business_type") or "unknown"
        buckets.setdefault(bt, []).append(item)

    total = 0
    n_synth = sum(1 for i in gated if i["consent_class"] == "synthetic")
    n_anon = sum(1 for i in gated if i["consent_class"] == "shared_anon")
    for bt, items in sorted(buckets.items()):
        path = os.path.join(args.out, f"dataset_{bt}.jsonl")
        with open(path, "w", encoding="utf-8") as f:
            for it in items:
                line = _to_sft_line(
                    it["row"],
                    input_text=it["input_text"],
                    teacher_output=it["teacher_output"],
                    consent_class=it["consent_class"],
                )
                f.write(json.dumps(line, ensure_ascii=False) + "\n")
        logger.info(f"  {bt:14s}: {len(items):6d} samples -> {path}")
        total += len(items)

    logger.info(
        "SHARED export: %d samples across %d bucket(s) (%d synthetic + %d "
        "shared_anon; %d real rows excluded by default-deny) to %s",
        total, len(buckets), n_synth, n_anon, excluded, args.out,
    )
    # Readiness hint vs the distillation threshold.
    # GREEN criteria (mirrors corpus_census): ≥1000 quality≥4 rows AND synthetic ≤ 50%.
    # If min_quality < 4, rows below 4 will be counted but they don't contribute to
    # census-GREEN; report honestly.
    for bt, items in sorted(buckets.items()):
        total_bt = len(items)
        n_synthetic = sum(1 for it in items if it["consent_class"] == "synthetic")
        synthetic_pct = (n_synthetic / total_bt * 100) if total_bt else 0.0
        if total_bt < 1000:
            ready = f"NOT ready: need {1000 - total_bt} more rows for 1k threshold"
        elif synthetic_pct > 50.0:
            ready = (
                f"NOT ready: synthetic {synthetic_pct:.0f}% > 50% cap — "
                f"needs {n_synthetic - total_bt // 2} fewer synthetic or more organic"
            )
        else:
            ready = "READY for LoRA"
        logger.info(
            f"  threshold check [{bt}]: {total_bt}/1000  synthetic={synthetic_pct:.0f}%  — {ready}"
        )


if __name__ == "__main__":
    asyncio.run(main())
