"""Real JWT-protected route calls across two Python processes and a restart."""
import json
import multiprocessing
import os
from contextlib import ExitStack, contextmanager, suppress
from functools import lru_cache
from io import BytesIO

import pytest

from common import smartbi_analysis_cache as storage
from tests.analysis_cache_fixtures import MemoryCache, analysis_cache  # noqa: F401
from tests.analysis_cache_worker import serve
from tests.test_chart_insight_answer_cache import FACTORY, OTHER_FACTORY, PATH, SECRET, headers, payload


@contextmanager
def worker(url, namespace):
    context = multiprocessing.get_context("spawn")
    local, remote = context.Pipe()
    process = context.Process(target=serve, args=(remote, url, namespace, SECRET))
    process.start()
    remote.close()
    try:
        assert local.poll(45), f"worker {process.pid} did not start"
        assert local.recv() == process.pid
        yield local, process.pid
    finally:
        if process.is_alive():
            with suppress(BrokenPipeError, OSError):
                local.send(None)
        process.join(10)
        local.close()
        if process.is_alive():
            process.terminate()
            process.join(5)


def records(n):
    return [{"产品": "甲", "产量": n, "成本": n * 2},
            {"产品": "乙", "产量": n + 10, "成本": n + 30}]


@lru_cache
def workbook(n, title="产量表"):
    from openpyxl import Workbook

    output = BytesIO()
    document = Workbook()
    document.active.title = title
    document.active.append(["产品", "产量"])
    document.active.append(["甲", n])
    document.active.append(["乙", n + 1])
    document.save(output)
    document.close()
    return {"file_bytes": output.getvalue()}


CASES = {
    "chart": ("POST", PATH, lambda n: payload(series_values=[n, 40]), "finding"),
    "factory-analysis": ("POST", "/api/smartbi/financial-dashboard/factory-analysis",
                         lambda n: {"question": "查看生产情况", "year": 2025, "periodType": "year"}, "answer"),
    "factory-ranking": ("POST", "/api/smartbi/financial-dashboard/factory-recommendations",
                        lambda n: {"year": 2025, "use_ai": True, "detail_candidate_ids": []}, "recommendations"),
    "dashboard-json": ("GET", "/api/smartbi/insights/custom?start_date=2025-01-01&end_date=2025-01-31",
                       lambda n: None, "answer"),
    "dashboard-sse": ("GET", "/api/smartbi/insights/custom/stream?start_date=2025-02-01&end_date=2025-02-28",
                      lambda n: None, "answer"),
    "insight-json": ("POST", "/api/insight/generate", lambda n: {"data": records(n)}, "insights"),
    "insight-sse": ("POST", "/api/insight/generate-stream",
                    lambda n: {"data": records(n), "analysisContext": "核对产量和成本"}, "insights"),
    "insight-metrics": ("POST", "/api/insight/analyze-metrics",
                        lambda n: [{"name": "本月", "value": n}, {"name": "上月", "value": n - 5}], "insights"),
    "financial-analysis": ("POST", "/api/smartbi/financial-dashboard/analyze",
                           lambda n: {"chart_type": "line", "analysis_context": f"成本合计 {n} 元"}, "analysis"),
    "cross-sheet-summary": ("POST", "/api/smartbi/cross-sheet-analysis",
                            lambda n: {"upload_ids": [11, 12], "sheet_names": ["生产一部", "生产二部"]}, "aiSummary"),
    "materialized-summary": ("POST", "/api/smartbi/analytics/materialize/11", lambda n: None, "_saved"),
    "synthesis-json": ("POST", "/api/smartbi/synthesis/comprehensive",
                       lambda n: {"question": "看本月生产情况", "start_date": "2025-03-01", "end_date": "2025-03-31"}, "answer"),
    "synthesis-sse": ("POST", "/api/smartbi/synthesis/comprehensive-stream",
                      lambda n: {"question": "看本月生产情况", "start_date": "2025-04-01", "end_date": "2025-04-30"}, "answer"),
    "chat-text-json": ("POST", "/api/chat/general-analysis",
                       lambda n: {"message": f"分析数据 {n} 的变化", "allow_tenant_data_fallback": False}, "answer"),
    "chat-text-sse": ("POST", "/api/chat/general-analysis-stream",
                      lambda n: {"message": f"分析数据 {n} 的变化", "allow_tenant_data_fallback": False}, "answer"),
    "chat-insight-json": ("POST", "/api/chat/general-analysis",
                          lambda n: {"query": "分析数据", "data": records(n), "allow_tenant_data_fallback": False}, "answer"),
    "chat-insight-sse": ("POST", "/api/chat/general-analysis-stream",
                         lambda n: {"query": "如何改进经营", "data": records(n), "allow_tenant_data_fallback": False}, "answer"),
    "drill-down-sse": ("POST", "/api/chat/drill-down-stream",
                       lambda n: {"sheet_id": "11", "dimension": "产品", "data": records(n)}, "answer"),
    "root-cause-sse": ("POST", "/api/chat/root-cause-stream",
                        lambda n: {"sheet_id": "11", "kpi": "产量", "data": records(n)}, "answer"),
    "benchmark-sse": ("POST", "/api/chat/benchmark-stream",
                      lambda n: {"sheet_id": "11", "industry": "food", "metrics": {"产量": n}}, "answer"),
    "multi-dimension-sse": ("POST", "/api/chat/multi-dimension-stream",
                            lambda n: {"sheet_id": "11", "data": records(n)}, "answer"),
    "excel-structure": ("POST", "/api/excel/analyze-structure", workbook, "insights"),
    "excel-smart-analysis": ("POST", "/api/excel/smart-analyze", workbook, "llm_insights"),
    "excel-workbook": ("POST", "/api/excel/analyze-workbook", workbook, "sheetResults"),
    "excel-workbook-sse": ("POST", "/api/excel/analyze-workbook-stream", workbook, "insights"),
    "excel-export-review": ("POST", "/api/excel/export/validate",
                            lambda n: workbook(n, "导出核对"), "review"),
    "excel-export-fix-review": ("POST", "/api/excel/export/validate-fix",
                                lambda n: workbook(n, "导出修复"), "review"),
    "excel-export-batch-review": ("POST", "/api/excel/export/batch/validate",
                                  lambda n: workbook(n, "批量核对"), "review"),
    "data-alert-inline": ("POST", "/api/smartbi/data-alerts/analyze", lambda n: {
        "source_id": "fixture", "dataset_kind": "production_cost", "with_ai": True,
        "rows": [{"工单编码": "T1", "产品代码": "P", "单位": "kg", "成品良品量": n,
                  "材料成本": None, "人工成本": 0}],
    }, "reviews"),
    "data-alert-upload": ("POST", "/api/smartbi/data-alerts/uploads/11/local-review",
                          lambda n: None, "reviews"),
}


def request(worker_handle, method, path, body=None, *, data=100, generation="初次结论", auth=None):
    channel, pid = worker_handle
    channel.send((method, path, auth if auth is not None else headers(), body, data, generation))
    assert channel.poll(45), f"{pid} timed out on {path}"
    status, raw, calls, saved = channel.recv()
    return status, raw, calls, saved


def analysis_response(case, worker_handle, data=100, *, generation="初次结论"):
    method, path, body, normalized = CASES[case]
    status, raw, calls, saved = request(worker_handle, method, path, body(data), data=data, generation=generation)
    assert status == 200, (case, status, raw)
    if case == "dashboard-sse":
        frames = [json.loads(line.removeprefix("data: ")) for line in raw.splitlines() if line.startswith("data: ")]
        result = next((entry for entry in frames if entry["type"] == "done"), None)
        assert result, (case, frames)
        result["answer"] = "".join(entry["text"] for entry in frames if entry["type"] == "delta")
    elif case.endswith("-sse"):
        frames = [frame.splitlines() for frame in raw.strip().split("\n\n")]
        final = next((lines[1][6:] for lines in frames if lines[0] == "event: done"), None)
        assert final, (case, frames)
        result = json.loads(final)
        if case == "excel-workbook-sse":
            sheet = next(json.loads(lines[1][6:]) for lines in frames if lines[0] == "event: sheet")
            result["insights"] = sheet["result"]["insights"]
    else:
        result = json.loads(raw)
    if case in {"chart", "factory-analysis", "factory-ranking"} or case.startswith("data-alert-"):
        assert result["success"] and result["data"], (case, result)
        result = result["data"]
    if case.startswith("data-alert-"):
        result = result["ai"]
    if case.startswith("excel-export-"):
        review = result.get("validation") or (result.get("validation_results") or [{}])[0]
        result["review"] = review.get("llm_review")
    if case == "excel-export-fix-review":
        assert len(result["cacheTokens"]) == 2
        result["cacheToken"] = result["cacheTokens"][-1]
        result["review"] = result["fix_log"]
    if normalized == "_saved":
        result["_saved"] = saved
    assert result.get("success", True) and result.get(normalized), (case, result)
    assert isinstance(result.get("cacheHit"), bool) and result.get("cacheToken"), (case, result)
    return result, calls, result[normalized]


def downvote(case, target, answer):
    if case == "chart":
        path = "/api/smartbi/insight/feedback"
        body = {"input_hash": answer["input_hash"], "vote": "down", "factory_id": FACTORY,
                "cache_token": answer["cacheToken"]}
    else:
        path = "/api/smartbi/analysis-feedback"
        body = {"cache_token": answer["cacheToken"]}
    status, raw, calls, _ = request(target, "POST", path, body)
    assert status == 200 and json.loads(raw)["success"] and calls == 0, (case, status, raw)


@pytest.mark.timeout(180)
async def test_route_across_workers_restart_and_negative_feedback(analysis_cache):
    if isinstance(analysis_cache, MemoryCache):
        pytest.skip("set SMARTBI_TEST_ANALYSIS_REDIS_URL to a private Redis for process evidence")
    url = os.environ["SMARTBI_TEST_ANALYSIS_REDIS_URL"]
    namespace = {name: getattr(storage, name) for name in ("_VALUES", "_INDEX", "_SEQUENCE", "_REVOKED")}

    initial = {}
    failures = []
    with ExitStack() as stack:
        secondary = stack.enter_context(worker(url, namespace))
        with worker(url, namespace) as primary:
            initial_pid = primary[1]
            assert initial_pid != secondary[1]
            for case in CASES:
                try:
                    answer, first, content = analysis_response(case, primary)
                    # Grounded chat plans then drafts. Exports first detect structure;
                    # the repair route also reviews and suggests fixes.
                    expected_calls = {
                        "chat-insight-sse": 2,
                        "excel-export-review": 2,
                        "excel-export-fix-review": 3,
                        "excel-export-batch-review": 2,
                    }.get(case, 1)
                    assert first == expected_calls and answer["cacheHit"] is False, (case, first)
                    refreshed, refresh, same = analysis_response(case, primary)
                    alternate, alternate_calls, another = analysis_response(case, secondary)
                    assert refreshed["cacheHit"] and alternate["cacheHit"] and same == content == another
                    assert refresh == alternate_calls == 0
                    initial[case] = (answer, content, first, refresh, alternate_calls)
                except (AssertionError, KeyError) as error:
                    failures.append(f"{case} before restart: {error}")
            if "insight-json" in initial:
                status, raw, calls, _ = request(
                    secondary, "POST", "/api/insight/generate-stream", {"data": records(100)},
                )
                frames = [frame.splitlines() for frame in raw.strip().split("\n\n")]
                final = next(json.loads(line[1][6:]) for line in frames if line[0] == "event: done")
                assert status == 200 and calls == 0 and final["cacheHit"]
                assert final["cacheToken"] == initial["insight-json"][0]["cacheToken"]
        restarted = stack.enter_context(worker(url, namespace))
        assert restarted[1] not in {initial_pid, secondary[1]}
        for case, (answer, content, first, refresh, alternate_calls) in initial.items():
            try:
                reused, restart_calls, after_restart = analysis_response(case, restarted)
                assert reused["cacheHit"] and after_restart == content and restart_calls == 0
                changed, changed_calls, _ = analysis_response(case, restarted, 101)
                assert not changed["cacheHit"] and changed_calls == first, (case, changed, changed_calls)
                downvote(case, secondary, answer)
                regenerated, bad_calls, after_vote = analysis_response(case, restarted, generation="更新结论")
                assert not regenerated["cacheHit"] and bad_calls == 1
                assert regenerated["cacheToken"] != answer["cacheToken"] and after_vote != content, (case, after_vote)
                final, model_calls, new_content = analysis_response(case, secondary)
                assert final["cacheHit"] and model_calls == 0 and new_content == after_vote
                print("ANALYSIS_CALLS", json.dumps({
                    "entry": case, "first": first, "refresh": refresh, "other_worker": alternate_calls,
                    "restarted_worker": restart_calls, "changed": changed_calls, "downvote_then_other_worker": bad_calls,
                    "refresh_after_regeneration": model_calls, "workers": [initial_pid, secondary[1], restarted[1]],
                }))
            except (AssertionError, KeyError) as error:
                failures.append(f"{case} after restart: {error}")
    assert not failures, "\n".join(failures)
    assert len(initial) == len(CASES)


async def test_feedback_tokens_are_tenant_role_and_revision_bound(analysis_cache):
    if isinstance(analysis_cache, MemoryCache):
        pytest.skip("set SMARTBI_TEST_ANALYSIS_REDIS_URL to a private Redis for process evidence")
    url = os.environ["SMARTBI_TEST_ANALYSIS_REDIS_URL"]
    namespace = {name: getattr(storage, name) for name in ("_VALUES", "_INDEX", "_SEQUENCE", "_REVOKED")}
    with worker(url, namespace) as handle:
        result, _, _ = analysis_response("financial-analysis", handle)
        path = "/api/smartbi/analysis-feedback"
        body = {"cache_token": result["cacheToken"]}
        for auth in (headers(factory=OTHER_FACTORY), headers(role="warehouse_worker")):
            assert request(handle, "POST", path, body, auth=auth)[0] == 404
        assert analysis_response("financial-analysis", handle)[1] == 0
        downvote("financial-analysis", handle, result)
        assert analysis_response("financial-analysis", handle)[1] == 1
        assert request(handle, "POST", path, body)[0] == 404
        assert analysis_response("financial-analysis", handle)[1] == 0


@pytest.mark.timeout(180)
async def test_partial_upstream_stream_never_enters_analysis_cache(analysis_cache):
    if isinstance(analysis_cache, MemoryCache):
        pytest.skip("set SMARTBI_TEST_ANALYSIS_REDIS_URL to a private Redis for streaming evidence")
    url = os.environ["SMARTBI_TEST_ANALYSIS_REDIS_URL"]
    namespace = {name: getattr(storage, name) for name in ("_VALUES", "_INDEX", "_SEQUENCE", "_REVOKED")}
    with worker(url, namespace) as handle:
        for case in ("chat-insight-sse", "drill-down-sse", "dashboard-sse", "insight-sse"):
            method, path, body, _ = CASES[case]
            for failure in ("provider-timeout", "provider-error", "provider-eof", "provider-length"):
                status, partial, calls, _ = request(handle, method, path, body(100), generation=failure)
                assert status == 200 and calls > 0 and failure in partial, (case, failure, partial)
                assert '"cacheToken"' not in partial and '"cacheHit": false' in partial, (case, failure, partial)
            fresh, calls, content = analysis_response(case, handle, generation="provider-complete")
            assert calls == 1 and not fresh["cacheHit"], (case, calls)
            warm, calls, reused = analysis_response(case, handle, generation="provider-complete")
            assert calls == 0 and warm["cacheHit"] and reused == content, (case, calls)
