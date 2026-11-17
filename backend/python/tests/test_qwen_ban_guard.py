"""2026-11-18 Steve 裁定的两条禁令闸。

1. qwen-max 系列整体禁用(精确名单 ∪ 前缀 qwen3-max / qwen-max)。
   正对照: 把禁用名临时放进白名单, 证明 _refuse_reason 真的按禁用拦下(不是靠白名单没有它才拦)。
   反对照: 当前免费主力 qwen3.7-max-2026-06-08 不被误伤。
2. 不用 Qwen 向量模型。生产 embedding 只用本地 gte-base-zh。
   静态闸: 扫非测试源码, 出现 DashScope/Qwen embedding 调用形状就失败。
"""
import datetime
import re
from pathlib import Path

import pytest

from common import llm_router


PAID_NAMES = [
    "qwen3-max",
    "qwen3-max-2026-01-23",
    "qwen3-max-2025-09-23",
    "qwen3-max-preview",
    "qwen-max",
    "qwen-max-latest",
    "qwen-plus",
]

ALLOWED_NAMES = [
    "qwen3.7-max-2026-06-08",
    "qwen3.7-max-preview",
    "qwen-plus-latest",
    "qwen3.8-flash",
    "qwen3.7-flash",
]


@pytest.mark.parametrize("model", PAID_NAMES)
def test_qwen_max_family_is_denied(model):
    assert llm_router.is_paid_denied(model), model


@pytest.mark.parametrize("model", ALLOWED_NAMES)
def test_current_free_models_are_not_caught_by_the_ban(model):
    assert not llm_router.is_paid_denied(model), model


def test_empty_and_none_are_not_denied():
    assert not llm_router.is_paid_denied("")
    assert not llm_router.is_paid_denied(None)


@pytest.mark.parametrize("model", PAID_NAMES)
def test_refuse_reason_vetoes_denied_name_even_when_allowlisted(monkeypatch, model):
    """正对照: 把它伪装成白名单内、未过期, 只剩禁用闸能拦它。"""
    monkeypatch.setitem(llm_router._SAFE_MODELS, ("aliyun_c", model), datetime.date(2099, 1, 1))
    monkeypatch.setattr(llm_router, "_registry_stale", lambda *a, **k: False)
    assert llm_router._refuse_reason("aliyun_c", model) == "paid_denylist"


def test_refuse_reason_does_not_veto_current_free_flagship(monkeypatch):
    """反对照: 同样的伪装, 对 qwen3.7-max 不能判 paid_denylist。"""
    monkeypatch.setitem(llm_router._SAFE_MODELS, ("aliyun_c", "qwen3.7-max-2026-06-08"),
                        datetime.date(2099, 1, 1))
    monkeypatch.setattr(llm_router, "_registry_stale", lambda *a, **k: False)
    assert llm_router._refuse_reason("aliyun_c", "qwen3.7-max-2026-06-08") != "paid_denylist"


def test_decision_chain_has_no_qwen37_flash():
    chain = llm_router.SLOT_MODELS[llm_router.SLOT.DECISION]
    assert ("aliyun_a_vl", "qwen3.7-flash") not in chain
    assert all(not llm_router.is_paid_denied(m) for _, m in chain)


@pytest.mark.parametrize("model", ["text-embedding-v3", "text-embedding-v4", "qwen3-embedding-0.6b",
                                   "qwen-embedding-v1"])
def test_qwen_embedding_names_are_banned(model):
    assert llm_router.is_banned_embedding_model(model), model


@pytest.mark.parametrize("model", ["gte-base-zh", "bge-m3", ""])
def test_local_embedding_names_are_allowed(model):
    assert not llm_router.is_banned_embedding_model(model), model


# 静态闸: 形状 = DashScope 文本向量模型名 或 qwen 系 embedding 名。
_EMBED_CALL = re.compile(r"text-embedding-v\d|qwen[\w.\-]*embedding", re.IGNORECASE)
_BACKEND_PY = Path(__file__).resolve().parents[1]
_JAVA_MAIN = Path(__file__).resolve().parents[2] / "java" / "cretas-api" / "src" / "main"


def _scan(root: Path, suffix: str):
    hits = []
    for path in root.rglob(f"*{suffix}"):
        parts = set(path.parts)
        if parts & {"tests", "test", "__pycache__", "node_modules", ".venv", "venv"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for lineno, line in enumerate(text.splitlines(), 1):
            if _EMBED_CALL.search(line):
                hits.append(f"{path}:{lineno}: {line.strip()[:120]}")
    return hits


def test_static_gate_regex_positive_control():
    assert _EMBED_CALL.search('model="text-embedding-v3"')
    assert _EMBED_CALL.search('"qwen3-embedding-0.6b"')
    assert not _EMBED_CALL.search('model="gte-base-zh"')


def test_no_qwen_embedding_call_in_non_test_source():
    hits = _scan(_BACKEND_PY, ".py") + (_scan(_JAVA_MAIN, ".java") if _JAVA_MAIN.exists() else [])
    assert hits == [], "发现 Qwen 向量模型调用(2026-11-18 禁用):\n" + "\n".join(hits)
