"""factory 评测登录: 没量到必须退 None(调用方退 2), 且绝不碰免密 demo-login。"""
import io
import urllib.error

from smartbi.scripts import factory_eval_auth as fea


def _http_error(code):
    return urllib.error.HTTPError("u", code, "x", {}, io.BytesIO(b""))


def test_missing_credentials_is_not_measured(monkeypatch):
    monkeypatch.delenv("FACTORY_EVAL_USERNAME", raising=False)
    monkeypatch.delenv("FACTORY_EVAL_PASSWORD", raising=False)
    calls = []
    assert fea.factory_login("http://x", lambda *a, **k: calls.append(a)) is None
    assert calls == []  # 缺凭证时一个请求都不发


def test_login_rejected_returns_none(monkeypatch):
    monkeypatch.setenv("FACTORY_EVAL_USERNAME", "u")
    monkeypatch.setenv("FACTORY_EVAL_PASSWORD", "p")

    def boom(*a, **k):
        raise _http_error(403)

    assert fea.factory_login("http://x", boom) is None


def test_wrong_tenant_returns_none(monkeypatch):
    monkeypatch.setenv("FACTORY_EVAL_USERNAME", "u")
    monkeypatch.setenv("FACTORY_EVAL_PASSWORD", "p")
    resp = {"data": {"token": "t", "factoryId": "LIUSHANMEN"}}
    assert fea.factory_login("http://x", lambda *a, **k: resp) is None


def test_success_uses_unified_login_never_demo(monkeypatch):
    monkeypatch.setenv("FACTORY_EVAL_USERNAME", "u")
    monkeypatch.setenv("FACTORY_EVAL_PASSWORD", "p")
    urls = []

    def ok(url, payload, **k):
        urls.append(url)
        return {"data": {"token": "t", "factoryId": "F006"}}

    assert fea.factory_login("http://x", ok) == {"Authorization": "Bearer t"}
    assert urls == ["http://x/api/mobile/auth/unified-login"]
