"""`must_not_contain` 不能打在自己的否定句上。

2026-09-03/04 四轮里, 每一轮都有一条**答案正确**的探针被判红, 因为被禁的说法出现在
否定它的那句话里: 「不因库存不足」「不需要重建」「不会出现"按配比 0%"」「同样不支持自由填写」。
每次的修法都是改那一条探针的措辞, 下一轮再长出新的一条 —— 那说明该修的是**尺子**。

⚠️ 判据只看「同一小句里有没有否定词」:
  * 紧邻一个字不够 —— 「不会出现"按配比 0%"」的引号紧贴短语, 否定词在四个字之外;
  * 又不能无限往前看 —— 「不是 A，而是 B」里的 B 必须仍然算数, 所以小句边界是硬停止点。
"""
import importlib.util

import pytest

from food_kb.services.manual_ingester import PROJECT_ROOT

_PROBE_PATH = PROJECT_ROOT / "scripts" / "food-kb" / "probe_manual_chat.py"
_SPEC = importlib.util.spec_from_file_location("probe_manual_chat", _PROBE_PATH)
assert _SPEC is not None and _SPEC.loader is not None, f"probe script not importable: {_PROBE_PATH}"
probe_manual_chat = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(probe_manual_chat)
_asserted = probe_manual_chat._asserted


@pytest.mark.parametrize(
    "text, phrase",
    [
        # 这四条是真实被误判过的答案原文
        ("原料档案的储存类型同样不支持自由填写。", "支持自由填写"),
        ('份额为 0 的主原料行不会出现"按配比 0%"，必须手动录入。', "按配比 0%"),
        ("生成领料单时读不到份额不因库存不足而拦单。", "库存不足"),
        ("补价后直接重新提交本单，不需要重建。", "需要重建"),
    ],
)
def test_a_phrase_inside_its_own_negation_is_not_the_claim(text, phrase):
    asserted, negated = _asserted(text, phrase)
    assert asserted is False, f"{phrase!r} 在 {text!r} 里是被否定的, 不该判红"
    assert negated == 1


@pytest.mark.parametrize(
    "text, phrase",
    [
        ("该页面支持自由填写储存位置。", "支持自由填写"),
        ("操作路径：系统管理 → 账号与角色", "系统管理 → 账号与角色"),
        ("确认领料时提示库存不足，但仓库明明有货。", "库存不足"),
        # 🔴 最要紧的一条: 否定在前一小句, 后一小句才是真正的断言, 必须仍然抓得住
        ("不是自动带价，页面支持自由填写单价。", "支持自由填写"),
    ],
)
def test_a_real_claim_is_still_caught(text, phrase):
    asserted, _ = _asserted(text, phrase)
    assert asserted is True, f"{phrase!r} 在 {text!r} 里是真的断言, 必须判红"


def test_one_negated_and_one_asserted_still_fails():
    """只要有一次是**没被否定**地说出来, 就算断言 —— 否定的那次不能替它脱罪。"""
    text = "这里不支持自由填写。备注字段支持自由填写。"
    asserted, negated = _asserted(text, "支持自由填写")
    assert asserted is True
    assert negated == 1


def test_absent_phrase_is_neither_asserted_nor_negated():
    assert _asserted("和这句话完全无关的一段答案。", "支持自由填写") == (False, 0)
