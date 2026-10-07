"""DeepSeek 官方 —— 通道已于 2026-10-07 清出, 本文件守它留下的东西。

🔴 2026-10-07 Steve 复审裁定「清掉死通道, 大模型后备只留我们自己的阿里云账号」:
   deepseek 官方两条(prod 探针 401 key 无效, 至少自 09-10)从 `_SAFE_MODELS` /
   `_MINIMAL_SAFE_SET` / 各槽池 / `_provider_config` 删除。「它真的不在了、环境变量
   也接不回来」由 test_llm_router_registry.py 的
   ::test_only_our_aliyun_accounts_remain_after_the_2026_10_07_ruling 与
   ::test_removed_channel_cannot_be_revived_by_env_alone 守。

⛔ 随通道一起删的断言(主语不在了, 换别的模型顶替就不是同一件事):
   key 不回落 / base_url 可覆盖 / 2026-11-15 复审日 / 活过 registry 超龄悬崖 /
   覆盖 aistore 的五个槽 / 排在免费尾巴前面 / aistore 到期前仍是链首。

留下来的:
  1. registry 超龄闸**真的会咬人**的阳性对照 —— 与账号无关, 任何新条目漏登
     `_MINIMAL_SAFE_SET` 都会撞上它。
  2. thinking 翻译机制(`_DEEPSEEK_THINKING_OBJECT_MODELS`)按「删条目不删机制」保留,
     改成**每个槽 × 每个型号**直接打 `_apply_slot_params`, 不再依赖链成员
     (链上已没有 deepseek, 按链遍历会闸空转)。
  3. 记账 `record_local` 真的接上了(llm_budget 曾经整个是死代码)。
"""
from __future__ import annotations

import datetime

from common import llm_router
from common.llm_router import SLOT

DEEPSEEK_SKUS = ("deepseek-v4-flash", "deepseek-v4-pro")


# ── registry 超龄悬崖 ───────────────────────────────────────────────────
def test_the_staleness_cliff_is_real_for_a_model_outside_the_minimal_set(monkeypatch):
    """阳性对照: 证明 registry_stale 这道闸**真的会咬人**。

    对照的主体由测试自己**构造**(ghost), ⛔ 不依赖生产表碰巧有一个
    「在 _SAFE_MODELS 但不在 _MINIMAL_SAFE_SET」的条目 —— 2026-10-07 两张表仍然
    相等(8/8), 这样的真实条目不存在。
    """
    ghost = ("aliyun_c_vl", "__not-in-minimal-set__")
    monkeypatch.setitem(llm_router._SAFE_MODELS, ghost, datetime.date(2099, 1, 1))
    assert ghost not in llm_router._MINIMAL_SAFE_SET
    stale = (llm_router._REGISTRY_AUDIT_DATE
             + datetime.timedelta(days=llm_router._REGISTRY_MAX_AGE_DAYS + 1))
    assert llm_router._registry_stale(stale) is True
    assert llm_router._refuse_reason(*ghost, stale) == "registry_stale"
    # 阴性: 同一条目在 registry 未超龄时(审计当天)不该被这道闸拒
    assert llm_router._refuse_reason(*ghost, llm_router._REGISTRY_AUDIT_DATE) is None


# ── thinking 注入(机制保留) ─────────────────────────────────────────────
def test_deepseek_thinking_translation_follows_each_slot_profile():
    """拿不拿 `thinking:{type:disabled}` **由槽的 profile 决定**。

    实测(2026-08-15): 不关思考时 ct=800 rt=800 **content=0** finish=length ——
    要害是**返回空正文**, 不是慢。
    """
    base = {"messages": [{"role": "user", "content": "return json"}]}
    checked = 0
    for slot in SLOT:
        wants_off = (llm_router._SLOT_PARAMS.get(slot) or {}).get(
            "enable_thinking") is False
        for model in DEEPSEEK_SKUS:
            out = llm_router._apply_slot_params(slot, "deepseek", model, base)
            got = out.get("thinking") == {"type": "disabled"}
            assert got == wants_off, (
                f"{slot.value}/{model}: profile 要求关思考={wants_off}, 实际注入={got}")
            # DashScope 的参数不该出现在 DeepSeek 的 payload 里
            assert "enable_thinking" not in out
            checked += 1
    assert checked == len(list(SLOT)) * len(DEEPSEEK_SKUS)


def test_deepseek_thinking_switch_is_keyed_by_the_exact_model_string():
    """阳性对照: 型号名差一个字母, 开关就**静默**失效 —— 这就是上面那道闸的理由。"""
    base = {"messages": [{"role": "user", "content": "return json"}]}
    real = llm_router._apply_slot_params(
        SLOT.REVIEW, "deepseek", "deepseek-v4-flash", base)
    assert real["thinking"] == {"type": "disabled"}
    typo = llm_router._apply_slot_params(
        SLOT.REVIEW, "deepseek", "deepseek-v4-flash-x", base)
    assert "thinking" not in typo


# ── 记账真的接上了吗(形态 B: 机制在、没接上) ────────────────────────────
def test_budget_recording_is_actually_wired(monkeypatch):
    """🔴 `llm_budget` 整个模块曾经是**死代码**: `record_local` 与
    `deepseek_over_budget` 零生产调用点, 而它正是 $19.49/12 天那次事故之后建的。

    ⛔ 这条不能只断言「函数存在」—— 那正是它坏掉时的样子。要断言**它被调用**,
       且拿到的是从 usage 里解析出来的真数。
    """
    seen = []
    monkeypatch.setattr(llm_router, "record_local",
                        lambda *a: seen.append(a))
    llm_router._log_cache_and_record_budget(
        "review", "deepseek", "deepseek-v4-flash",
        {"usage": {"prompt_tokens": 4000, "completion_tokens": 120,
                   "prompt_tokens_details": {"cached_tokens": 3000}}})
    assert seen == [("deepseek-v4-flash", 4000, 120, 3000)], (
        f"记账没被调用或参数不对: {seen} —— "
        "函数名写着 record_budget 而函数体只有 log, 就是它坏掉时的样子")


def test_budget_recording_is_not_swallowed_when_the_module_is_missing():
    """⛔ import 必须在模块级: 放函数里的话 import 失败会被那个
    `except Exception: logger.debug(...)` 吞掉, 记账**静默停摆**而无任何信号。
    """
    import inspect
    src = inspect.getsource(llm_router._log_cache_and_record_budget)
    assert "import" not in src, (
        "记账的 import 又被挪回函数体里了 —— 它会被 except 吞掉")
    assert hasattr(llm_router, "record_local"), "模块级没有 record_local"
