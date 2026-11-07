"""Isolated local266 + BlendRetriever wiring for the semantic-read transport.

This module is intentionally a test-process adapter.  It does not change the
production matcher or classifier singleton: the frozen classifier is loaded
from an explicit path, and its ``ClassifierMatcher`` is used only by the
transport process.  Both candidate sources remain candidates; the existing
LLM decision matcher still chooses the final intent.
"""
from __future__ import annotations

import asyncio
import contextvars
import hashlib
import json
import logging
import math
import os
import pathlib
import threading
from typing import Any, Awaitable, Callable, Dict, List, Optional

from ai.dto import CandidateIntentDto
from ai.embedding import get_embedding_cached
from ai.matcher.candidates import BlendRetriever
from ai.matcher.classifier import ClassifierMatcher
from ai.matcher import classifier as classifier_module

logger = logging.getLogger(__name__)
_classifier_observation = contextvars.ContextVar('semantic_read_classifier_observation', default=None)


async def _observed_to_thread(function,*args,**kwargs):
    # The production Python3.8 executor shim does not copy ContextVars. Preserve
    # its executor/inference behavior while carrying only this test request's
    # observation into the worker thread. No production service is patched.
    context=contextvars.copy_context()
    return await asyncio.get_running_loop().run_in_executor(None,lambda:context.run(function,*args,**kwargs))

MODEL_DIR = pathlib.Path(
    os.environ.get(
        "SEMANTIC_LOCAL266_MODEL_DIR",
        r"C:\Users\Steve\Documents\Cretas意图泛化-20261105\models\boundary-a\final",
    )
)
MODEL_SHA256 = "0fa159f28bf30b445672086a670f30177eeebfa9101146952acc49d319e741b2"
MAPPING_SHA256 = "ccd2497bc53ac5958d7531f6539cf0b62301c2f530904ea64feeccc9a669cb5b"
CLASSIFIER_TEMPERATURE = 1.5
CLASSIFIER_MIN_CONFIDENCE = 0.05
CLASSIFIER_LABEL_COUNT = 266


def _sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _candidate_json(candidate: CandidateIntentDto) -> Dict[str, Any]:
    return {
        "intent_code": candidate.intentCode,
        "intent_name": candidate.intentName,
        "confidence": float(candidate.confidence),
        "match_method": getattr(candidate.matchMethod, "value", candidate.matchMethod),
    }


class LocalRuntimeCandidateRetriever:
    """Union the real classifier and BlendRetriever for one decision request.

    The classifier source is first only to retain its ranked order.  This class
    never selects an intent, changes a score, or applies a decision threshold.
    ``Orchestrator._match_decision`` receives the union and continues to the
    existing shortlist/chooser path.
    """

    def __init__(
        self,
        pool: Any,
        *,
        trace_path: Optional[pathlib.Path] = None,
        classifier_matcher: Optional[ClassifierMatcher] = None,
        blend_retriever: Optional[BlendRetriever] = None,
        embedding_fn: Callable[[str], Awaitable[Optional[List[float]]]] = get_embedding_cached,
    ) -> None:
        if pool is None:
            raise RuntimeError("local runtime requires an asyncpg pool; refusing silent empty retrieval")
        self.pool = pool
        self.classifier_matcher = classifier_matcher or _build_explicit_classifier_matcher()
        self.blend_retriever = blend_retriever or BlendRetriever(pool, top_k=20, blend=0.35)
        self.embedding_fn = embedding_fn
        self.trace_path = pathlib.Path(trace_path) if trace_path else None
        self._trace_lock = threading.Lock()
        self.calls = 0
        self.classifier_calls = 0
        self.blend_calls = 0
        self.embedding_calls = 0
        self.vector_corpus_hash: Optional[str] = None
        self.vector_corpus_rows: Optional[int] = None
        self.transaction_read_only: Optional[str] = None
        self._prefetched: contextvars.ContextVar = contextvars.ContextVar(
            "semantic_read_prefetched_candidates", default=None
        )

    async def preflight(self, factory_id: str = "F006", business_type: str = "FACTORY") -> Dict[str, Any]:
        """Check model, embedding and pgvector without making business writes."""
        classifier = getattr(self.classifier_matcher, "_explicit_classifier", None)
        if classifier is not None and not classifier.is_loaded():
            raise RuntimeError("local266 classifier failed to load")
        if classifier is not None and len(classifier.id_to_label or {}) != CLASSIFIER_LABEL_COUNT:
            raise RuntimeError("local266 label mapping is not the frozen 266-label mapping")
        vector = await self.embedding_fn("语义读取本地运行时预检")
        self.embedding_calls += 1
        if not vector:
            raise RuntimeError("embedding gRPC returned no vector; refusing silent empty retrieval")
        rows = await self._read_vector_corpus(factory_id, business_type)
        encoded = "\n".join(
            f"{row['source']}\t{row['row_count']}\t{row['digest']}" for row in rows
        ).encode("utf-8")
        self.vector_corpus_hash = hashlib.sha256(encoded).hexdigest()
        self.vector_corpus_rows = sum(int(row["row_count"]) for row in rows)
        return {
            "classifier_loaded": classifier is None or classifier.is_loaded(),
            "classifier_label_count": len(classifier.id_to_label or {}) if classifier is not None else None,
            "embedding_dimensions": len(vector),
            "vector_corpus_rows": self.vector_corpus_rows,
            "vector_corpus_sha256": self.vector_corpus_hash,
            "transaction_read_only": self.transaction_read_only == "on",
            "blend": {"top_k": self.blend_retriever.top_k, "blend": self.blend_retriever.blend},
        }

    async def _read_vector_corpus(self, factory_id: str, business_type: str) -> List[Dict[str, str]]:
        # Hash only the scoped metadata/vector representation; no rows are
        # printed or persisted.  The SQL is read-only and mirrors BlendRetriever.
        config_sql = """
        SELECT count(*) AS row_count,
               md5(coalesce(string_agg(intent_code || E'\\t' || coalesce(embedding::text, ''),
                                      E'\\n' ORDER BY intent_code, embedding), '')) AS digest
          FROM ai_intent_configs
         WHERE deleted_at IS NULL AND is_active = true
           AND (factory_id IS NULL OR factory_id = $1)
           AND (business_type = 'COMMON' OR business_type = $2)
        """
        example_sql = """
        SELECT count(*) AS row_count,
               md5(coalesce(string_agg(intent_code || E'\\t' || embedding::text,
                                      E'\\n' ORDER BY intent_code, embedding), '')) AS digest
          FROM ai_intent_examples
         WHERE embedding IS NOT NULL
        """
        async with self.pool.acquire() as conn:
            self.transaction_read_only = await conn.fetchval("SHOW transaction_read_only")
            if self.transaction_read_only != "on":
                raise RuntimeError("local runtime database session is not read-only")
            config = await conn.fetchrow(config_sql, factory_id, business_type)
            records = [{"source": "config", "row_count": int(config["row_count"]), "digest": str(config["digest"])}]
            try:
                example = await conn.fetchrow(example_sql)
                records.append({"source": "example", "row_count": int(example["row_count"]), "digest": str(example["digest"])})
            except Exception as exc:  # noqa: BLE001
                # BlendRetriever has an explicit description-vector fallback
                # when this optional migration is absent; preserve that exact
                # behavior while making all other DB failures fatal.
                if "does not exist" not in str(exc):
                    raise
                records.append({"source": "example", "row_count": 0, "digest": "TABLE_MISSING"})
        return [
            {
                "source": str(row["source"]),
                "row_count": str(row["row_count"]),
                "digest": str(row["digest"]),
            }
            for row in records
        ]

    async def prefetch(self, query: str, factoryId: str, businessType: str) -> List[CandidateIntentDto]:
        """Run both sources before Orchestrator, covering exact-route turns too."""
        union = await self._retrieve(query, factoryId, businessType)
        self._prefetched.set(((query, factoryId, businessType), union))
        return union

    async def match(self, query: str, factoryId: str, businessType: str) -> List[CandidateIntentDto]:
        key = (query, factoryId, businessType)
        pending = self._prefetched.get()
        if pending is not None and pending[0] == key:
            self._prefetched.set(None)
            return pending[1]
        return await self._retrieve(query, factoryId, businessType)

    def discard_prefetch(self, query: str, factoryId: str, businessType: str) -> None:
        pending = self._prefetched.get()
        if pending is not None and pending[0] == (query, factoryId, businessType):
            self._prefetched.set(None)

    async def _retrieve(self, query: str, factoryId: str, businessType: str) -> List[CandidateIntentDto]:
        # Use the same request-context cache as BlendRetriever.  A missing
        # embedding is a hard dependency failure, never an empty fallback.
        embedding = await self.embedding_fn(query)
        self.embedding_calls += 1
        if not embedding:
            self._record(query, factoryId, businessType, [], [], [], {"embedding": "Unavailable"})
            raise RuntimeError("embedding gRPC returned no vector during local retrieval")

        observation = {'called':False,'ok':False}
        observation_token = _classifier_observation.set(observation)
        try:
            classifier_task = asyncio.create_task(
                self.classifier_matcher.match(query, factoryId=factoryId, businessType=businessType)
            )
            blend_task = asyncio.create_task(
                self.blend_retriever.match(query, factoryId=factoryId, businessType=businessType)
            )
            results = await asyncio.gather(classifier_task, blend_task, return_exceptions=True)
        finally:
            _classifier_observation.reset(observation_token)
        classifier_error = results[0] if isinstance(results[0], BaseException) else None
        blend_error = results[1] if isinstance(results[1], BaseException) else None
        classifier_candidates = [] if classifier_error else results[0]
        blend_candidates = [] if blend_error else results[1]
        strict_outcome = observation if getattr(self.classifier_matcher, '_strict_observed', False) else None
        if strict_outcome is not None and not strict_outcome["ok"] and classifier_error is None:
            classifier_error = RuntimeError(
                f"ClassifierMatcher swallowed classifier failure: {strict_outcome.get('error') or 'unknown'}"
            )
            classifier_candidates = []
        self.calls += 1
        self.classifier_calls += 1
        self.blend_calls += 1

        union: List[CandidateIntentDto] = []
        seen = set()
        for candidate in list(classifier_candidates) + list(blend_candidates):
            code = str(candidate.intentCode)
            if code not in seen:
                seen.add(code)
                union.append(candidate)
        failures = {}
        if classifier_error is not None:
            failures["classifier"] = type(classifier_error).__name__
        if blend_error is not None:
            failures["blend_retriever"] = type(blend_error).__name__
        self._record(query, factoryId, businessType, classifier_candidates, blend_candidates, union, failures)
        if classifier_error is not None:
            raise classifier_error
        if blend_error is not None:
            raise blend_error
        return union

    def _record(
        self,
        query: str,
        factory_id: str,
        business_type: str,
        classifier_candidates: List[CandidateIntentDto],
        blend_candidates: List[CandidateIntentDto],
        union: List[CandidateIntentDto],
        failures: Dict[str, str],
    ) -> None:
        if self.trace_path is None:
            return
        self.trace_path.parent.mkdir(parents=True, exist_ok=True)
        record = {
            "query": query,
            "factory_id": factory_id,
            "business_type": business_type,
            "classifier_candidates": [_candidate_json(item) for item in classifier_candidates],
            "blend_candidates": [_candidate_json(item) for item in blend_candidates],
            "union_candidates": [_candidate_json(item) for item in union],
            "components": {
                "classifier": {"called": True, "ok": "classifier" not in failures, "count": len(classifier_candidates)},
                "blend_retriever": {"called": True, "ok": "blend_retriever" not in failures, "count": len(blend_candidates)},
            },
            "failures": failures,
            "classifier_min_confidence": CLASSIFIER_MIN_CONFIDENCE,
            "classifier_temperature": CLASSIFIER_TEMPERATURE,
        }
        with self._trace_lock:
            with self.trace_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(record, ensure_ascii=False) + "\n")


def _build_explicit_classifier_matcher() -> ClassifierMatcher:
    model_file = MODEL_DIR / "model.safetensors"
    mapping_file = MODEL_DIR / "label_mapping.json"
    if not model_file.exists() or not mapping_file.exists():
        raise RuntimeError(f"local266 model files missing under {MODEL_DIR}")
    actual_model_sha = _sha256(model_file)
    actual_mapping_sha = _sha256(mapping_file)
    if actual_model_sha != MODEL_SHA256 or actual_mapping_sha != MAPPING_SHA256:
        raise RuntimeError("local266 model or mapping hash does not match the frozen manifest")

    # ClassifierMatcher obtains the service through get_classifier().  Seed
    # that singleton with this explicit instance in this isolated process only;
    # no production module or long-running service state is changed.
    from classifier.services import intent_classifier

    classifier = intent_classifier.IntentClassifierService(
        model_path=str(MODEL_DIR), temperature=CLASSIFIER_TEMPERATURE
    )
    if not classifier.is_loaded():
        raise RuntimeError("local266 classifier failed to load")
    if len(classifier.id_to_label or {}) != CLASSIFIER_LABEL_COUNT:
        raise RuntimeError("local266 label mapping is not the frozen 266-label mapping")
    intent_classifier._classifier_instance = classifier
    def strict_predict(query: str):
        outcome = _classifier_observation.get()
        if outcome is None:raise RuntimeError('Classifier inference outside its request observation')
        outcome.update(called=True,ok=False,error=None)
        try:
            result = classifier.classify(query, top_k=10)
            if not isinstance(result,dict) or result.get("success") is not True:
                raise RuntimeError('classifier returned no successful result')
            intents = result.get("intents")
            if not isinstance(intents,list) or any(not isinstance(item,dict) or not isinstance(item.get('intent_code'),str)
                    or isinstance(item.get('confidence'),bool) or not isinstance(item.get('confidence'),(int,float))
                    or not math.isfinite(item['confidence']) or not 0<=item['confidence']<=1 for item in intents):
                raise RuntimeError('Invalid local classifier result')
            prediction = [
                (item["intent_code"], float(item["confidence"]))
                for item in intents
                if "intent_code" in item and "confidence" in item
            ]
            outcome["ok"] = True
            return prediction
        except Exception as exc:  # noqa: BLE001
            outcome["error"] = type(exc).__name__
            raise

    # ClassifierMatcher resolves _predict from its module at call time.  This
    # strict seam stays inside the isolated transport process and makes its
    # swallowed-error behavior observable without changing production code.
    classifier_module._predict = strict_predict
    classifier_module._to_thread = _observed_to_thread
    matcher = ClassifierMatcher(min_confidence=CLASSIFIER_MIN_CONFIDENCE)
    matcher._explicit_classifier = classifier  # type: ignore[attr-defined]
    matcher._strict_observed = True  # type: ignore[attr-defined]
    return matcher


async def open_local_runtime(
    *, trace_path: pathlib.Path, dsn: Optional[str] = None
) -> LocalRuntimeCandidateRetriever:
    """Open an isolated read-only candidate runtime for the transport process."""
    pool = None
    owned_pool = False
    selected_dsn = dsn or os.environ.get("SEMANTIC_LOCAL_PG_DSN")
    if not selected_dsn:
        raise RuntimeError(
            "local runtime requires explicit SEMANTIC_LOCAL_PG_DSN; refusing a shared non-read-only pool"
        )
    import asyncpg

    pool = await asyncpg.create_pool(
        selected_dsn,
        min_size=1,
        max_size=2,
        command_timeout=30,
        server_settings={"default_transaction_read_only": "on"},
    )
    owned_pool = True
    if pool is None:
        raise RuntimeError(
            "local runtime needs a Cretas asyncpg pool; set SEMANTIC_LOCAL_PG_DSN or configure food_kb_db_url"
        )
    runtime = LocalRuntimeCandidateRetriever(pool, trace_path=trace_path)
    try:
        await runtime.preflight()
    except Exception:
        if owned_pool:
            await pool.close()
        raise
    runtime._owned_pool = owned_pool  # type: ignore[attr-defined]
    return runtime


async def close_local_runtime(runtime: LocalRuntimeCandidateRetriever) -> None:
    pool = getattr(runtime, "pool", None)
    if getattr(runtime, "_owned_pool", False) and pool is not None:
        await pool.close()

