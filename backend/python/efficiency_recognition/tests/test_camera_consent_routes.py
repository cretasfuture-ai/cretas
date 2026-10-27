"""人效识别 / 照片 / 录像 / 场景 / 跟踪 / 取流: 本厂没开「摄像头抓拍 AI 识别」就不读图、不发视觉模型。

与 Java CameraSnapshotAiConsent 同一开关 (factory_settings.ai_settings.cameraSnapshotAnalysis), 默认关。
- 20 个会把画面 / 照片交给视觉模型的接口: 没开 403, 没有登录工厂 401, 都不调用模型
- 开了才调用 (正向对照)
- 闸: 这几个路由表里的每个 POST 要么挂了开关依赖, 要么登记在 NO_IMAGE 里 (新增接口忘了挂会红)
- 开关解析规则与 Java 一致 (必须是 JSON 布尔 true)
- main.py 用的就是这些路由对象和前缀
"""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import FastAPI, Request
from fastapi.routing import APIRoute, APIWebSocketRoute
from fastapi.testclient import TestClient

from efficiency_recognition.api import camera_consent
from efficiency_recognition.api import photo_routes
from efficiency_recognition.api import (
    photo_router,
    recording_router,
    router,
    scene_router,
    stream_router,
    tracking_router,
)

PREFIXES = [
    (router, "/api/efficiency"),
    (stream_router, "/api/efficiency/streams"),
    (photo_router, "/api/efficiency/photo"),
    (tracking_router, "/api/efficiency/tracking"),
    (recording_router, "/api/efficiency/recording"),
    (scene_router, "/api/efficiency/scene"),
]

GATED = [
    "/api/efficiency/analyze-frame",
    "/api/efficiency/analyze-frame-upload",
    "/api/efficiency/analyze-video-upload",
    "/api/efficiency/batch-analyze",
    "/api/efficiency/analyze",
    "/api/efficiency/edge-triggered",
    "/api/efficiency/photo/analyze",
    "/api/efficiency/photo/analyze-base64",
    "/api/efficiency/photo/ocr",
    "/api/efficiency/photo/ocr-base64",
    "/api/efficiency/photo/counting",
    "/api/efficiency/photo/counting-base64",
    "/api/efficiency/recording/analyze",
    "/api/efficiency/scene/understand",
    "/api/efficiency/scene/detect-changes",
    "/api/efficiency/scene/compare",
    "/api/efficiency/streams/add",
    "/api/efficiency/streams/batch-add",
    "/api/efficiency/tracking/identify",
    "/api/efficiency/tracking/identify-upload",
]

# 不读图、不调视觉模型的接口 (只读状态 / 改内存配置 / 停任务 / 转换已有结果); 新增接口不登记就红
NO_IMAGE = {
    "POST /api/efficiency/build-efficiency-record",
    "POST /api/efficiency/workstation-mapping",
    "GET /api/efficiency/workstation-mapping",
    "GET /api/efficiency/task/{task_id}",
    "GET /api/efficiency/health",
    "GET /api/efficiency/config",
    "GET /api/efficiency/recording/task/{task_id}",
    "POST /api/efficiency/recording/cancel/{task_id}",
    "GET /api/efficiency/recording/tasks",
    "DELETE /api/efficiency/recording/tasks/cleanup",
    "GET /api/efficiency/scene/current/{camera_id}",
    "GET /api/efficiency/scene/history",
    "POST /api/efficiency/scene/auto-adapt/{change_id}",
    "GET /api/efficiency/scene/all",
    "GET /api/efficiency/scene/stats",
    "DELETE /api/efficiency/scene/camera/{camera_id}",
    "POST /api/efficiency/streams/remove",
    "GET /api/efficiency/streams/status",
    "GET /api/efficiency/streams/{stream_id}/status",
    "POST /api/efficiency/streams/stop-all",
    "GET /api/efficiency/streams/analysis-types",
    "GET /api/efficiency/streams/preprocessing-stats",
    "GET /api/efficiency/tracking/trajectory/{tracking_id}",
    "GET /api/efficiency/tracking/record/{tracking_id}",
    "POST /api/efficiency/tracking/topology",
    "GET /api/efficiency/tracking/topology",
    "POST /api/efficiency/tracking/link",
    "GET /api/efficiency/tracking/all",
    "GET /api/efficiency/tracking/features/{tracking_id}",
    "GET /api/efficiency/tracking/stats",
    "DELETE /api/efficiency/tracking/cleanup",
    "GET /api/efficiency/tracking/by-camera/{camera_id}",
    "GET /api/efficiency/tracking/search/badge",
    "GET /api/efficiency/tracking/search/clothing",
    "GET /api/efficiency/tracking/movement/{tracking_id}",
    "DELETE /api/efficiency/tracking/topology/{camera_a_id}/{camera_b_id}",
}

ON = '{"cameraSnapshotAnalysis": true}'


class SpyAnalyzer:
    def __init__(self) -> None:
        self.calls = 0

    def analyze_counting(self, image_base64):
        self.calls += 1
        return {"total_count": 1}


@pytest.fixture()
def app(monkeypatch):
    application = FastAPI()

    @application.middleware("http")
    async def fake_auth(request: Request, call_next):  # 代替 AuthMiddleware: 登录工厂放在 request.state
        factory = request.headers.get("x-test-factory")
        if factory:
            request.state.factory_id = factory
        return await call_next(request)

    for r, prefix in PREFIXES:
        application.include_router(r, prefix=prefix)
    spy = SpyAnalyzer()
    monkeypatch.setattr(photo_routes, "get_analyzer", lambda: spy)
    application.state.spy = spy
    return application


def _consent(monkeypatch, settings_by_factory):
    looked_up = []

    async def fake_enabled(factory_id):
        looked_up.append(factory_id)
        return camera_consent.switch_on(settings_by_factory.get(factory_id))

    monkeypatch.setattr(camera_consent, "camera_snapshot_ai_enabled", fake_enabled)
    return looked_up


@pytest.mark.parametrize("path", GATED)
def test_every_image_route_is_refused_when_the_factory_did_not_turn_it_on(app, monkeypatch, path):
    looked_up = _consent(monkeypatch, {"F006": None})
    client = TestClient(app)

    resp = client.post(path, json={}, headers={"x-test-factory": "F006"})

    assert resp.status_code == 403, resp.text
    assert resp.json()["detail"] == camera_consent.DISABLED_MESSAGE
    assert looked_up == ["F006"]  # 问的是登录工厂的开关


@pytest.mark.parametrize("path", GATED)
def test_every_image_route_needs_a_logged_in_factory(app, monkeypatch, path):
    looked_up = _consent(monkeypatch, {})
    client = TestClient(app)

    resp = client.post(path, json={})

    assert resp.status_code == 401
    assert looked_up == []


def test_photo_is_not_sent_to_the_model_when_off_and_is_sent_once_turned_on(app, monkeypatch):
    client = TestClient(app)
    body = {"image_base64": "aW1n", "analysis_type": "counting"}

    _consent(monkeypatch, {"F006": '{"cameraSnapshotAnalysis": false}', "F007": ON})
    refused = client.post("/api/efficiency/photo/analyze-base64", json=body, headers={"x-test-factory": "F006"})
    assert refused.status_code == 403
    assert app.state.spy.calls == 0

    # 正向对照: 开了的工厂照常调用模型
    allowed = client.post("/api/efficiency/photo/analyze-base64", json=body, headers={"x-test-factory": "F007"})
    assert allowed.status_code == 200, allowed.text
    assert allowed.json()["counting"] == {"total_count": 1}
    assert app.state.spy.calls == 1


def test_every_route_is_either_gated_or_registered_as_not_sending_images():
    gated, ungated = set(), set()
    for r, prefix in PREFIXES:
        for route in r.routes:
            if isinstance(route, APIWebSocketRoute):
                ungated.add("WS " + prefix + route.path)
                continue
            assert isinstance(route, APIRoute), route
            deps = [d.dependency for d in route.dependencies]
            for method in sorted(route.methods):
                key = f"{method} {prefix}{route.path}"
                (gated if camera_consent.require_camera_snapshot_ai in deps else ungated).add(key)
    assert gated == {"POST " + p for p in GATED}
    assert ungated == NO_IMAGE


@pytest.mark.parametrize("raw, expected", [
    (None, False),
    ("", False),
    ("   ", False),
    ("not json", False),
    ("{}", False),
    ('{"cameraSnapshotAnalysis": false}', False),
    ('{"cameraSnapshotAnalysis": "true"}', False),
    ('{"cameraSnapshotAnalysis": 1}', False),
    ('[true]', False),
    (ON, True),
    (b'{"cameraSnapshotAnalysis": true}', True),
    ({"cameraSnapshotAnalysis": True}, True),
])
def test_switch_parsing_matches_java(raw, expected):
    assert camera_consent.switch_on(raw) is expected


@pytest.mark.asyncio
async def test_lookup_failure_counts_as_off(monkeypatch):
    import smartbi.config as cfg

    async def broken_pool():
        raise RuntimeError("db down")

    monkeypatch.setattr(cfg, "get_cretas_pool", broken_pool)
    assert await camera_consent.camera_snapshot_ai_enabled("F006") is False


@pytest.mark.asyncio
async def test_lookup_reads_own_factory_live_row(monkeypatch):
    import smartbi.config as cfg
    seen = {}

    class Conn:
        async def fetchval(self, sql, factory_id):
            seen["sql"], seen["factory"] = sql, factory_id
            return ON

    class Acquire:
        async def __aenter__(self):
            return Conn()

        async def __aexit__(self, *exc):
            return False

    class Pool:
        def acquire(self):
            return Acquire()

    async def pool():
        return Pool()

    monkeypatch.setattr(cfg, "get_cretas_pool", pool)
    assert await camera_consent.camera_snapshot_ai_enabled("F006") is True
    assert seen["factory"] == "F006"
    assert "deleted_at IS NULL" in seen["sql"] and "factory_settings" in seen["sql"]


def test_main_mounts_these_routers_with_these_prefixes():
    main = (Path(__file__).resolve().parents[2] / "main.py").read_text(encoding="utf-8")
    for name, prefix in [
        ("efficiency_router", "/api/efficiency"),
        ("efficiency_stream_router", "/api/efficiency/streams"),
        ("efficiency_photo_router", "/api/efficiency/photo"),
        ("efficiency_tracking_router", "/api/efficiency/tracking"),
        ("efficiency_recording_router", "/api/efficiency/recording"),
        ("efficiency_scene_router", "/api/efficiency/scene"),
    ]:
        assert f'app.include_router({name}, prefix="{prefix}"' in main


# ---------------------------------------------------------------- 取流: 只看 / 停本厂的流, 中途关开关就停

@pytest.fixture()
def sampler(monkeypatch):
    from efficiency_recognition.api import stream_routes
    from efficiency_recognition.services.multi_stream_sampler import MultiStreamSampler

    async def no_loop(self, task):  # 不真的去拉流
        return None

    monkeypatch.setattr(MultiStreamSampler, "_run_stream_loop", no_loop)
    s = MultiStreamSampler(max_concurrent=4)
    monkeypatch.setattr(stream_routes, "get_sampler", lambda: s)
    return s


def test_streams_are_listed_removed_and_stopped_only_for_the_factory_that_added_them(app, monkeypatch, sampler):
    _consent(monkeypatch, {"F006": ON, "F007": ON})
    client = TestClient(app)
    f6, f7 = {"x-test-factory": "F006"}, {"x-test-factory": "F007"}
    assert client.post("/api/efficiency/streams/add", json={"stream_id": "s6", "rtsp_url": "rtsp://u:pw@10.0.0.6/1"},
                       headers=f6).json()["success"] is True
    assert client.post("/api/efficiency/streams/add", json={"stream_id": "s7", "rtsp_url": "rtsp://u:pw@10.0.0.7/1"},
                       headers=f7).json()["success"] is True

    status7 = client.get("/api/efficiency/streams/status", headers=f7).json()
    assert set(status7["streams"]) == {"s7"} and status7["active_streams"] == 1
    assert client.get("/api/efficiency/streams/s6/status", headers=f7).status_code == 404
    assert client.post("/api/efficiency/streams/remove", json={"stream_id": "s6"}, headers=f7).json()["success"] is False
    client.post("/api/efficiency/streams/stop-all", headers=f7)

    assert set(sampler.active_streams) == {"s6"}
    assert sampler.active_streams["s6"].config.factory_id == "F006"
    assert client.get("/api/efficiency/streams/s6/status", headers=f6).status_code == 200
    assert client.get("/api/efficiency/streams/status").status_code == 401


@pytest.mark.asyncio
async def test_a_running_stream_stops_before_sending_once_the_switch_is_off(monkeypatch):
    from efficiency_recognition.services.multi_stream_sampler import MultiStreamSampler, StreamConfig, StreamTask

    _consent(monkeypatch, {"F006": '{"cameraSnapshotAnalysis": false}'})
    s = MultiStreamSampler(max_concurrent=1)
    monkeypatch.setattr(s, "capture_frame", lambda url, timeout=10: "aW1n")
    sent = []

    async def spy(frame, config):
        sent.append(frame)
        raise AssertionError("不应送模型")

    monkeypatch.setattr(s, "_analyze_frame", spy)
    task = StreamTask(config=StreamConfig(stream_id="s6", rtsp_url="rtsp://x", interval_seconds=0,
                                          use_local_preprocessing=False, factory_id="F006"))

    await s._run_stream_loop(task)

    assert sent == []
    assert task.is_active is False
    assert task.last_error == camera_consent.DISABLED_MESSAGE


@pytest.mark.asyncio
async def test_a_running_stream_sends_frames_while_the_switch_is_on(monkeypatch):
    from efficiency_recognition.services.multi_stream_sampler import (
        AnalysisResult, MultiStreamSampler, StreamConfig, StreamTask)

    _consent(monkeypatch, {"F006": ON})
    s = MultiStreamSampler(max_concurrent=1)
    monkeypatch.setattr(s, "capture_frame", lambda url, timeout=10: "aW1n")
    task = StreamTask(config=StreamConfig(stream_id="s6", rtsp_url="rtsp://x", interval_seconds=0,
                                          use_local_preprocessing=False, factory_id="F006"))
    sent = []

    async def spy(frame, config):
        sent.append(frame)
        task.is_active = False  # 发了一帧就收工
        return AnalysisResult(stream_id="s6", timestamp="t", analysis_type="efficiency",
                              success=False, error="stub")

    monkeypatch.setattr(s, "_analyze_frame", spy)

    await s._run_stream_loop(task)

    assert sent == ["aW1n"]
