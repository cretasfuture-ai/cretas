"""Fresh-process SmartBI route fixture. Only model transport and data sources are stubbed."""
from __future__ import annotations

import asyncio
import json
import os
import traceback
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
import sys


def serve(channel, url, redis_keys, signing_key):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "smartbi"))
    os.environ["SMARTBI_ANALYSIS_REDIS_URL"] = url
    os.environ.pop("CHART_INSIGHT_DETERMINISTIC_FIRST", None)
    asyncio.run(_serve(channel, redis_keys, signing_key))


async def _serve(channel, redis_keys, signing_key):
    import pandas as pd
    import httpx
    from fastapi import FastAPI
    from httpx import ASGITransport, AsyncClient

    from auth_middleware import JWTAuthMiddleware
    import config
    from common import llm_router
    from smartbi.agent import api as dashboard, orchestrator, synthesis_engine
    from smartbi.agent.factbook import FactBook
    from smartbi.api import chart_insight, chat, cross_sheet, data_alerts, excel, financial_dashboard, insight, materialized_analytics, synthesis
    from smartbi.services import alert_ai_coordination, alert_local_ai, analytics_warmup, cross_sheet_aggregator
    from common import smartbi_analysis_cache as analysis_result_cache
    from smartbi.services.factory_charts import (
        captured_sources, conversation_store, engine, finance_auth, finance_sources,
        operational_sources, sources, trusted_business_sources,
    )
    from smartbi.services.insights import chart_insight_service, llm_client
    from smartbi.services.materialized_analytics import llm_materializer
    from services.insights import llm_client as llm_alias
    from services.llm_structure_analyzer import LLMStructureAnalyzer
    from services.parallel_processor import ParallelProcessor, SheetResult, WorkbookResult
    from services.structure_detector import StructureDetectionResult, StructureDetector
    from services.unified_analyzer import Insight, UnifiedAnalysisResult, UnifiedAnalyzer
    from tests.test_chart_insight_answer_cache import model_result
    from tests.test_smartbi_shared_alerts import ROWS

    for name, value in redis_keys.items():
        setattr(analysis_result_cache, name, value)
    state = SimpleNamespace(calls=0, data=100, generation="初次结论", path="", saved=None)
    upstream_modes = {"provider-timeout", "provider-error", "provider-eof", "provider-length", "provider-complete"}
    settings = config.get_settings().model_copy(update={
        "llm_api_key": "fixture", "llm_base_url": "http://fixture.invalid", "llm_insight_model": "fixture",
        "llm_model": "fixture", "use_llm_first": True,
    })
    config.get_settings = lambda: settings
    dashboard.get_settings = lambda: settings
    insight.insight_generator.settings = settings
    chat.InsightGenerator.__init__ = lambda self: self.__dict__.update(settings=settings, model_override=None)

    def text():
        state.calls += 1
        return f"{state.generation}。应根据实际记录复核变化。"

    async def chart_model(ctx):
        reply = model_result(ctx)
        reply["finding"] += text()
        return reply

    async def route_model(_slot, payload, **kwargs):
        if kwargs.get("content_validator"):
            provider = json.loads(payload["messages"][1]["content"])
            text()
            index = 0 if state.generation == "初次结论" else -1
            content = json.dumps({"selected_ids": [provider["catalog"][index]["id"]]})
            assert kwargs["content_validator"](content) is None
        elif state.path.endswith("/factory-recommendations"):
            provider = json.loads(payload["messages"][1]["content"])
            text()
            index = 0 if state.generation == "初次结论" else -1
            content = json.dumps({"rankings": [
                {"id": candidate["id"], "reason_code": candidate["allowed_reason_codes"][index]}
                for candidate in provider
            ]})
        elif "/api/excel/export/" in state.path:
            prompt = payload["messages"][0]["content"]
            if "你是一个数据修复专家" in prompt:
                content = json.dumps({"fixes_applied": [{"type": "add_note", "content": text()}]}, ensure_ascii=False)
            else:
                needs_fix = state.path.endswith("/validate-fix")
                content = json.dumps({"overall_ok": not needs_fix, "overall_summary": text(),
                                      "issues": ["格式需要补充备注"] if needs_fix else []}, ensure_ascii=False)
        else:
            content = text()
        return {"choices": [{"message": {"content": content}}]}

    def insight_json():
        return json.dumps({"insights": [{"type": "summary", "text": text()}]}, ensure_ascii=False)

    class ProviderResponse:
        status_code = 200

        async def aiter_lines(self):
            content = insight_json() if state.path.startswith("/api/insight/") else text()
            yield "data: " + json.dumps({"choices": [{"delta": {"content": content}}]}, ensure_ascii=False)
            if state.generation == "provider-timeout":
                raise httpx.ReadTimeout("fixture upstream timed out after sending a chunk")
            if state.generation == "provider-error":
                yield 'data: {"error":{"code":"fixture","message":"fixture SSE error"}}'
            elif state.generation != "provider-eof":
                reason = "length" if state.generation == "provider-length" else "stop"
                yield "data: " + json.dumps({"choices": [{"delta": {}, "finish_reason": reason}]})
                yield "data: [DONE]"

    class Provider:
        @asynccontextmanager
        async def stream(self, *_args, **_kwargs):
            yield ProviderResponse()

    provider = Provider()
    real_structured_stream = llm_client.call_llm_stream
    real_plain_stream = llm_client.call_llm_stream_text
    real_dashboard_stream = orchestrator.AgentOrchestrator._call_llm_stream

    async def raw_model(*args, **kwargs):
        if "/materialize/" in state.path:
            return json.dumps({"insights": {"fixture": text()}}, ensure_ascii=False)
        if state.path == "/api/chat/general-analysis-stream":
            text()
            return '{"intent":"none"}'
        return insight_json()

    async def structured_stream(*args, **kwargs):
        if state.generation in upstream_modes:
            async for chunk in real_structured_stream(*args, **kwargs):
                yield chunk
            return
        yield insight_json()
        kwargs["completion"].completed = True

    async def plain_stream(*args, **kwargs):
        if state.generation in upstream_modes:
            async for chunk in real_plain_stream(*args, **kwargs):
                yield chunk
            return
        yield text()
        kwargs["completion"].completed = True

    async def plain_answer(*args, **kwargs):
        return text()

    async def excel_model(*args, **kwargs):
        return json.dumps({
            "structure": {"header_rows": [0], "data_start_row": 1, "columns": [
                {"col_letter": "A", "col_index": 0, "name": "产品", "role": "dimension"},
                {"col_letter": "B", "col_index": 1, "name": "产量", "role": "measure", "data_type": "number"},
            ]},
            "insights": [text()],
        }, ensure_ascii=False)

    async def workbook_data(*args, **kwargs):
        return WorkbookResult(success=True, total_sheets=1, processed_sheets=1, sheets=[
            SheetResult(0, text(), True, detection_method="llm_fast", row_count=2),
        ])

    async def export_structure(self, raw_rows, merged_cells, sheet_name, total_rows, total_cols):
        text()
        return StructureDetectionResult(
            confidence=0.99, method="llm_fast", sheet_name=sheet_name, total_rows=total_rows,
            total_cols=total_cols, header_row_count=1, data_start_row=1, merged_cells=merged_cells,
        )

    async def unified_sheet(self, file_bytes, sheet_index, question, options):
        assert not options.use_cache and not options.save_to_cache
        return UnifiedAnalysisResult(success=True, insights=[
            Insight("summary", "总结", text(), None, "neutral", 5, model_generated=True),
        ])

    def run_local(infer, payload, *args):
        facts = json.loads(payload["messages"][1]["content"])["facts"]
        text()
        return {"reviews": [{"alertId": fact["alertId"], "check": fact["allowedChecks"][0],
                             "priority": "normal" if state.generation == "初次结论" else "high"}
                            for fact in facts]}

    def alert_upload(factory, upload_id):
        from smartbi.services.dataset_alerts import analyze_dataset
        return analyze_dataset([{**ROWS[0], "成品良品量": state.data}], factory_id=factory,
                               source_type="api", source_id=str(upload_id), dataset_kind="production_cost")

    async def authorized_alert(report, *args, **kwargs):
        return report

    async def empty_source(*args, **kwargs):
        return [], [], []

    async def no_finance(*args, **kwargs):
        return False

    async def dashboard_model(*args, **kwargs):
        return text(), 15, "fixture"

    async def dashboard_stream(*args, **kwargs):
        if state.generation in upstream_modes:
            async for event in real_dashboard_stream(*args, **kwargs):
                yield event
            return
        yield {"text": text(), "tokens": 15}
        yield {"completed": True}

    async def synthesis_model(*args, **kwargs):
        return text(), 15

    async def noop(*args, **kwargs):
        return None

    async def budget(*args, **kwargs):
        return SimpleNamespace(blocked=False, tokens_used=15, tokens_cap=1000)

    class Connection:
        async def fetchrow(self, sql, *args):
            if "distillation_samples" in sql:
                return {"quality": 5, "metadata": {"permission_tier": "finance_visible"}}
            return {"factory_id": "CACHE-TEST-A", "row_count": 2}

        async def fetch(self, *args):
            return []

        async def execute(self, sql, *args):
            assert "factory_id" in sql

    @asynccontextmanager
    async def connection():
        yield Connection()

    async def pool():
        return SimpleNamespace(acquire=connection)

    async def current_dashboard_data(*args):
        return {"finance": {"total_revenue": state.data, "bill_count": 2, "store_count": 1},
                "top_products": [], "discount_breakdown": []}

    async def current_facts(self, _factory, _date_range, _plan, *, period, **_kwargs):
        return FactBook(finance={"total_revenue": state.data, "bill_count": 2}, period=period)

    async def sheet_data(self, upload_id, factory_id):
        assert factory_id == "CACHE-TEST-A"
        return [{"科目": "营业收入", "金额": state.data + upload_id}, {"科目": "营业成本", "金额": 40}]

    async def materialize(*args):
        return [SimpleNamespace(code="fixture", applies=True, error=None, llm_insight=None, value=state.data)]

    async def save_materialization(*args):
        rows = args[-1]
        assert rows[0].llm_insight
        state.saved = rows[0].llm_insight
        return 1

    async def recommendations(body, request):
        candidates = []
        for name, family, metric, count in (
            ("trend", "production.output", "生产趋势", state.data),
            ("quality", "quality.pass", "质量表现", state.data + 1),
        ):
            candidates.append({
                "id": name, "analysis": {"familyId": family}, "title": metric, "domain": "生产",
                "metric": metric, "unit": "件", "priority": "required", "source": "fixture",
                "chart": {"chartType": "bar", "echartsOption": {"series": [{"data": [count]}]}},
                "kpis": [{"label": "合计", "value": count, "unit": "件"}],
                "detailSnapshotKey": f"fixture-version-{state.data}",
            })
        return {"success": True, "data": {"factoryId": request.state.factory_id,
                "source": body.source, "period": {"label": "2025年", "year": body.year},
                "recommendations": candidates, "subjects": []}}

    llm_router.call_chain = route_model
    llm_router.get_llm_http_client = lambda: provider
    llm_router._client_for = lambda _account: provider
    llm_router._refuse_reason = lambda *_: None
    llm_router._provider_config = lambda _account: ("http://fixture.invalid", "fixture")
    llm_router.prefetch_names_for_request = noop
    llm_router.reset_prefetched_names = lambda _token: None
    for slot in (llm_router.SLOT.CHAT, llm_router.SLOT.INSIGHTS):
        llm_router.SLOT_MODELS[slot] = [("openai", "gpt-4.1-mini")]
    for client in (llm_client, llm_alias):
        client.call_llm = raw_model
        client.call_llm_stream = structured_stream
        client.call_llm_stream_text = plain_stream
        client.generate_text_analysis = plain_answer
    chart_insight_service.ChartInsightService._call_llm = lambda self, ctx: chart_model(ctx)
    chart_insight_service.ChartInsightService._read_corpus_cache = noop
    chart_insight_service.persist_distillation_sample = noop
    chart_insight._service = chart_insight_service.ChartInsightService(
        await pool(), SimpleNamespace(check_budget=budget, consume=noop),
    )
    dashboard.get_pg_pool = pool
    orchestrator.AgentBudgetTracker.check_budget = budget
    orchestrator.AgentBudgetTracker.consume = budget
    orchestrator.AgentOrchestrator._gather_data = current_dashboard_data
    orchestrator.AgentOrchestrator._call_llm = dashboard_model
    orchestrator.AgentOrchestrator._call_llm_stream = dashboard_stream
    orchestrator._capture_insight_distillation = noop
    synthesis.get_pg_pool = pool
    synthesis_engine.ComprehensiveSynthesisEngine._build_factbook = current_facts
    synthesis_engine.ComprehensiveSynthesisEngine._call_llm = synthesis_model
    synthesis_engine.ComprehensiveSynthesisEngine._capture_distillation = noop
    # The API imports this service through the historical `services` alias.
    from services import cross_sheet_aggregator as cross_sheet_alias
    cross_sheet_aggregator.CrossSheetAggregator._fetch_sheet_data = sheet_data
    cross_sheet_alias.CrossSheetAggregator._fetch_sheet_data = sheet_data
    materialized_analytics.get_pg_pool = pool
    import smartbi.config
    smartbi.config.get_pg_pool = pool
    materialized_analytics.materialize_upload = materialize
    materialized_analytics.save_materialization_results = save_materialization
    orchestrator.NarrativeCacheService.invalidate_on_upload = noop
    analytics_warmup.warmup_factory_insights = noop
    llm_materializer._persist_distillation_sample = noop
    llm_materializer._summarize_result_for_prompt = lambda row: {"code": row.code, "value": row.value}
    chat._capture_qa_distillation = noop
    financial_dashboard.factory_recommendations = recommendations
    conversation_store.set_store_for_tests(conversation_store.MemoryConversationStore())
    for source in (sources, finance_sources, operational_sources, captured_sources, trusted_business_sources):
        source.load = empty_source
    finance_auth.full_factory_finance_access = no_finance
    engine.build = lambda *_args: ([{
        "id": "fixture", "metric": "合计", "domain": "生产", "priority": "required",
        "chart": {"chartType": "bar"}, "kpis": [{"value": state.data}],
    }], [])
    os.environ["SMARTBI_LOCAL_ANALYST_ENABLED"] = "0"
    alert_local_ai.provider_config = lambda: {"transport": "http", "host": "fixture", "model": "fixture"}
    alert_ai_coordination.run = run_local
    data_alerts.load_upload = alert_upload
    data_alerts.for_request = authorized_alert
    from services import llm_structure_analyzer
    llm_structure_analyzer.get_settings = lambda: settings
    LLMStructureAnalyzer._call_llm = excel_model
    ParallelProcessor.process_workbook = workbook_data
    StructureDetector._detect_with_llm_fast = export_structure
    UnifiedAnalyzer.__init__ = lambda self: None
    UnifiedAnalyzer.analyze = unified_sheet
    UnifiedAnalyzer.close = noop
    from smartbi.services import llm_fallback_logger
    llm_fallback_logger.log_fallback = noop

    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret=signing_key)
    for router, prefix in (
        (chart_insight.router, "/api/smartbi"), (dashboard.router, "/api/smartbi"),
        (financial_dashboard.router, "/api/smartbi/financial-dashboard"), (insight.router, "/api/insight"),
        (cross_sheet.router, "/api/smartbi"), (materialized_analytics.router, "/api/smartbi"),
        (synthesis.router, "/api/smartbi"), (chat.router, "/api/chat"),
        (data_alerts.router, ""), (excel.router, "/api/excel"),
    ):
        app.include_router(router, prefix=prefix)

    channel.send(os.getpid())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://fixture") as client:
        while (message := await asyncio.to_thread(channel.recv)) is not None:
            method, path, headers, body, data, generation = message
            state.path, state.data, state.generation = path, data, generation
            state.saved = None
            before = state.calls
            if body is not None and "file_bytes" in body and path.startswith("/api/excel/"):
                kwargs = {"files": {"file": ("fixture.xlsx", body["file_bytes"])}, "data": body.get("form", {})}
            else:
                kwargs = {"json": body} if body is not None else {}
            try:
                response = await client.request(method, path, headers=headers, **kwargs)
                channel.send((response.status_code, response.text, state.calls - before, state.saved))
            except Exception:
                channel.send((500, traceback.format_exc(), state.calls - before, state.saved))
