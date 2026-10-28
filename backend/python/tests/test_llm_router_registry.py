"""
_SAFE_MODELS 注册表自身的闸。

与 test_llm_router_chains.py 的分工: 这里只管「注册表这张表对不对」,
链怎么拼、顺序对不对在 chains 那边。
"""
import asyncio
from unittest import mock

import pytest

# 2026-10-29: 没有工厂上下文的外发会被拒绝; 本文件测路由 / 出口行为, 按本厂请求跑 (见 conftest)
pytestmark = pytest.mark.usefixtures("egress_as_factory_request")

from common import llm_router


def test_minimal_safe_set_is_subset_of_safe_models():
    """stale fail-safe 只能退守到真实存在于白名单里的条目。

    _refuse_reason 的顺序是: registry_stale 时只放行 _MINIMAL_SAFE_SET
    → 紧接着查 (account, model) in _SAFE_MODELS。所以最小集里任何不在
    _SAFE_MODELS 的条目在 stale 分支下会被判 not_allowlisted ——
    fail-safe 会 fail 成「没有地板」, 恰恰是它想防的那件事。
    """
    orphans = sorted(p for p in llm_router._MINIMAL_SAFE_SET
                     if p not in llm_router._SAFE_MODELS)
    assert orphans == [], (
        f"_MINIMAL_SAFE_SET 有 {len(orphans)} 个条目不在 _SAFE_MODELS 里, "
        f"stale 时会被 _refuse_reason 判 not_allowlisted: {orphans}"
    )


# 2026-08-09 三账号控制台截图 ∩ 生产探针(经 _apply_slot_params, 判据为非空 content)
# 的交集。单边证据不收: 控制台有余量但探针 403 的不收(aliyun_c/deepseek-v4-flash-0731);
# 探针 200 但控制台未列的更不收 —— 那说明「用完即停」没覆盖它, 可能真在计费(glm-5.2)。
#
# 2026-08-10: 生产探针首次对本注册表整体实跑, 发现 3 条 08-09 当天还 OK 的条目
# 24 小时内变成 403 quota, 已按「移除只需探针证据」剔除 —— 见 llm_router.py
# `_SAFE_MODELS` 后面的 "2026-08-10 探针复审剔除" 段落:
#   aliyun_c/qwen3-max-2025-09-23, aliyun_c/qwen3-vl-32b-instruct,
#   aliyun_c/qwen3.7-max (与 qwen3.7-max-2026-05-17/-preview/-2026-05-20 不同模型)。
#: 人审冻结表。⛔ 改注册表必须同步改这张表, 而改这张表要求有当天的证据。
#:
#: 🔴 2026-08-15 owner 裁定收敛到 aistore / deepseek / zhipu 三家
#:    (原话:「暂时限制用 ai store 和 deepseek 还有 zhipu, 后面我有要求的时候
#:    我们再说」)。旧的 aliyun / tencent / ark 冻结表**直接替换, ⛔ 不留注释**:
#:    这张表不是历史记录, 是**对当前状态的断言**; 把旧值留在里面(哪怕注释掉)
#:    它就同时是「记录」又是「断言」—— 同一个东西有两份, 它一定会漂。
#:    留痕已有两份且都是权威位置: git 历史(完整旧表 + 那个 commit)、
#:    以及 llm_router.py 里 08-09 / 08-13 两轮人审结论的审计注释。
#:
#: 🔴 2026-10-07 Steve 复审裁定「清掉死通道, 大模型后备只留我们自己的阿里云账号」:
#:    aistore ×3(401, 09-13 已过期)/ deepseek ×2(401)/ zhipu ×1(429)整体删除,
#:    同样**直接替换, ⛔ 不留注释**(理由同上)。读数见 llm_router 的 `_REGISTRY_AUDIT_PROBE`。
_FROZEN_REGISTRY = {
    # 2026-09-29 A 账号同源带图探针通过；owner 要求先用有限额模型。
    # ⚠️ 2026-10-07 探针: kimi-k3 / qwen3.8-27b / qwen3.8-max-0902 当天 403 quota ——
    #    原样保留, 已报 owner 裁定(brief: aliyun 条目死了只报告)。
    ("aliyun_a_vl", "qwen3.8-27b"): None,
    ("aliyun_a_vl", "kimi-k3"): None,
    ("aliyun_a_vl", "qwen3.8-max-0902"): None,
    ("aliyun_a_vl", "deepseek-v4.1-flash"): None,
    # ── VL (2026-09-15, OPS-VL-SLOT-QWEN38-FLASH-20260915) ──────────────────
    # ⚠️ 这里的 None 语义是**到期日未知**(不是「永不过期」)。
    # 计费安全不靠日期: aliyun_c 的「免费额度用完即停」是开着的, 证据与 08-09
    # 给 aliyun_b 定性时同源 —— 同一把 key 当天对其它 VL SKU 一律返回
    # 403 AllocationQuota.FreeTierOnly, 而 403-而-非-计费-200 只有在用完即停
    # 已开启时才可能出现。
    # 三账号轮转同一个模型; 实测带图延迟 c 4.8s / a 6.2s / b 10.6s(链序即按此排)。
    # 2026-10-07 起这三条也是**全部文本槽的唯一候选**; b 当天 403 quota(保留, 已报 owner)。
    ("aliyun_c_vl", "qwen3.8-flash"): None,
    ("aliyun_a_vl", "qwen3.8-flash"): None,
    ("aliyun_b_vl", "qwen3.8-flash"): None,
    # 可选备胎(`LLM_VL_MODEL=qwen3.7-flash` 切过去; 它只在 aliyun_a 上验过)。
    # ⚠️ 2026-09-15 实测带图 **33-48s/次**(同模型纯文本 2.4s) ⇒ ⛔ 不作默认:
    #    手机端用户举着手机等的就是这段时间。
    ("aliyun_a_vl", "qwen3.7-flash"): None,
}


def test_registry_matches_frozen_probe_result():
    """人审冻结表 vs 代码里的注册表。两边来源不同, 不是恒真式。"""
    assert llm_router._SAFE_MODELS == _FROZEN_REGISTRY


def test_minimal_safe_set_is_a_subset_of_the_registry():
    """⚠️ 两张表**当前内容相同是偶然, 不是设计**:
    一张是计费白名单, 一张是 registry 超龄后的最小可信集, 语义不同。

    ⇒ 这里只断言**子集**关系(那才是真不变式), ⛔ 不断言相等 —— 否则下一个人
      看到「两张表一样」会以为其中一张多余, 把它删掉。
    """
    assert llm_router._MINIMAL_SAFE_SET <= set(llm_router._SAFE_MODELS)
    assert llm_router._MINIMAL_SAFE_SET, "最小集空了 —— 超龄之后就没有任何可用候选"


def test_every_text_chain_ends_on_the_text_tail():
    """每个文本槽的链非空, 且链尾是 `_TEXT_TAIL` 的成员。

    🔴 2026-10-07 改写。原版 `test_fast_non_dashscope_floor_precedes_the_slow_one`
       断言「zhipu 必须是**链的最后一位**」—— 那条判据的主语(zhipu/glm-4.5-air)
       随 Steve 的复审裁定出局了(429 配额尽), 断言守的是**已经不想要的旧行为**
       (形态 C‴), 红得理直气壮但不该修回去。
       它背后的两件事仍然成立, 拆成两条:
         ① 文本槽有地板且地板在链尾(本条, 断言 SLOT_MODELS —— call_chain 真正遍历的那个);
         ② 「必须排最后」由 `_ABSOLUTE_LAST` 结构键保证(下一条, 用合成成员钉机制)。
    """
    # VL / WAREHOUSE_VL / DECISION 不追加文本地板(见 _NO_TEXT_TAIL_SLOTS 的理由)。
    assert llm_router.SLOT.VL in llm_router._NO_TEXT_TAIL_SLOTS
    tail = set(llm_router._TEXT_TAIL)
    assert tail, "文本地板空了 —— 每个文本槽都会是空链"
    checked = 0
    for slot, chain in llm_router.SLOT_MODELS.items():
        if slot in llm_router._NO_TEXT_TAIL_SLOTS:
            continue
        assert chain, f"{slot.value} 是空链"
        assert chain[-1] in tail, f"{slot.value}: 链尾 {chain[-1]} 不是文本地板成员"
        checked += 1
    assert checked >= 7, f"只扫到 {checked} 个文本槽 —— 闸空转了一部分"


def test_absolute_last_mechanism_still_sinks_its_members(monkeypatch):
    """`_ABSOLUTE_LAST` 今天是**空集**(唯一成员 zhipu 已出局), 机制必须仍然有效。

    ⛔ 空集合上的「成员都在最后」恒真 —— 所以这里**造一个合成成员**:
    把 CHAT 链的**链头**塞进 `_ABSOLUTE_LAST`, 重建链, 它必须落到最后一位。
    阴性对照: 不塞时它确实是链头(否则「落到最后」可能只是它本来就在最后)。

    ⚠️ 必须把 `_today` 钉在能力表测量日: 能力表超龄时 `_build_chain` 退回纯到期日
       排序, 根本不看 `_ABSOLUTE_LAST`(2026-10-07 prod 上就是这个状态 —— 能力表
       08-13 测, 早已超龄)。不钉日期, 这条测的就是另一条分支。
    """
    from unittest import mock

    slot = llm_router.SLOT.CHAT
    with mock.patch.object(llm_router, "_today",
                           lambda: llm_router._CAPABILITY_MEASURED_AT):
        assert llm_router._capability_stale() is False
        base = llm_router._build_chain(slot)
        assert len(base) >= 2, f"CHAT 链不足两条, 无法构造本用例: {base}"
        victim = base[0]
        monkeypatch.setattr(llm_router, "_ABSOLUTE_LAST", frozenset({victim}))
        sunk = llm_router._build_chain(slot)
    assert sunk[-1] == victim, f"_ABSOLUTE_LAST 成员 {victim} 没有被沉底: {sunk}"
    assert sorted(sunk) == sorted(base), "沉底不许增删条目"


#: 人写的「我们自己的阿里云逻辑账号」名单 —— 与 llm_router 的 `_ALIYUN_ACCOUNTS`
#: **来源不同**(那张表还含 aliyun_a/b/c 等早已退役的名字), 不是恒真式。
_OUR_ALIYUN_ACCOUNTS = frozenset({"aliyun_c_vl", "aliyun_a_vl", "aliyun_b_vl"})


def _non_aliyun(pairs):
    return sorted({p for p in pairs if p[0] not in _OUR_ALIYUN_ACCOUNTS})


def test_only_our_aliyun_accounts_remain_after_the_2026_10_07_ruling():
    """Steve 2026-10-07:「大模型后备只留我们自己的阿里云账号」。

    四个地方都要查, 缺一个就有一条后门: 计费白名单 / 超龄最小集 / 实际链 /
    DEFAULT_CHAIN(本文件声明的「账号清单唯一来源」)。
    """
    # 阴性对照: 判定函数必须**判得出**一个非阿里云条目, 否则下面全绿没有意义。
    assert _non_aliyun([("zhipu", "glm-4.5-air"), ("aliyun_c_vl", "x")]) == [
        ("zhipu", "glm-4.5-air")]
    assert _non_aliyun(llm_router._SAFE_MODELS) == []
    assert _non_aliyun(llm_router._MINIMAL_SAFE_SET) == []
    for slot, chain in llm_router.SLOT_MODELS.items():
        assert _non_aliyun(chain) == [], f"{slot.value} 链上有非阿里云条目"
    assert set(llm_router.DEFAULT_CHAIN) <= _OUR_ALIYUN_ACCOUNTS
    assert llm_router._SAFE_MODELS, "注册表空了 —— 上面几条恒真"


_REMOVED_CHANNELS = [
    # (逻辑账号, key 环境变量, base_url 环境变量)
    ("aistore", "LLM_AISTORE_API_KEY", "LLM_AISTORE_BASE_URL"),
    ("deepseek", "LLM_DEEPSEEK_API_KEY", "LLM_DEEPSEEK_BASE_URL"),
    ("zhipu", "LLM_ZHIPU_API_KEY", "LLM_ZHIPU_BASE_URL"),
]


@pytest.mark.parametrize("account,key_env,url_env", _REMOVED_CHANNELS)
def test_removed_channel_cannot_be_revived_by_env_alone(monkeypatch, account, key_env, url_env):
    """prod 的 .env.prod 里这三家的 key 仍在(2026-10-07 实读: 非空)。删了映射之后,
    环境变量**不许**再把通道接回来 —— 否则「清掉死通道」只是清掉了一张表。

    主读数: `_provider_config` 返回空; `call_chain(chain=[account])` 明确抛
    no_candidates, 且 HTTP 客户端**一次都没被调用**。
    必然读数(硬约束 9): 同一个测试里, 对一个在册账号同样的调用**真的发出了请求**
    —— 否则「没发请求」可能只是桩根本没装上。
    """
    monkeypatch.setenv(key_env, "sk-" + "x" * 32)
    monkeypatch.setenv(url_env, "https://example.invalid/v1")
    assert llm_router._provider_config(account) == ("", ""), (
        f"{account} 的映射还在 —— 环境变量能把它接回来")

    calls = []

    class _Client:
        async def post(self, url, headers=None, json=None, timeout=None):  # noqa: A002
            calls.append((url, (json or {}).get("model")))
            resp = mock.MagicMock()
            resp.status_code = 200
            resp.text = "{}"
            resp.json = mock.MagicMock(return_value={
                "choices": [{"message": {"content": "在册账号正常返回"}}]})
            return resp

    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: _Client())
    payload = {"messages": [{"role": "user", "content": "hi"}]}
    with pytest.raises(llm_router.LLMChainExhausted) as ei:
        asyncio.run(llm_router.call_chain(llm_router.SLOT.CHAT, payload, chain=[account]))
    assert "no_candidates" in str(ei.value), str(ei.value)
    assert calls == [], f"已删通道居然发出了请求: {calls}"

    # 必然读数: 在册账号走同一条路必须真的发请求并拿到答案。
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "key_c_fake")
    llm_router._CB_FAILURES.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    got = asyncio.run(llm_router.call_chain(llm_router.SLOT.CHAT, payload, chain=["aliyun_c_vl"]))
    assert calls and calls[0][1] == "qwen3.8-flash", f"阳性对照没发出请求: {calls}"
    assert got["choices"][0]["message"]["content"] == "在册账号正常返回"


def test_liveness_probe_positive_control_is_still_on_a_chain():
    """`smartbi/scripts/minimal_safe_set_liveness.py` 的阳性对照必须**在链上**。

    那个脚本只量链上的条目; 对照不在链上 ⇒ 永远「没量到」⇒ 每轮 rc=2 作废。
    2026-10-07 之前它是 aistore/DeepSeek-V4-Flash-A —— aistore 一出局, 这台仪器就停了。
    """
    import ast
    from pathlib import Path

    src = (Path(__file__).resolve().parents[1] / "smartbi" / "scripts"
           / "minimal_safe_set_liveness.py").read_text(encoding="utf-8")
    consts = [n for n in ast.walk(ast.parse(src))
              if isinstance(n, ast.Assign)
              and any(getattr(t, "id", None) == "POSITIVE_CONTROL" for t in n.targets)]
    assert len(consts) == 1, "找不到唯一的 POSITIVE_CONTROL 赋值 —— 先查闸本身"
    pc = tuple(ast.literal_eval(consts[0].value))
    on_chain = {p for chain in llm_router.SLOT_MODELS.values() for p in chain}
    assert pc in on_chain, f"阳性对照 {pc} 不在任何链上 —— 存活探针会每轮 rc=2"
    assert pc in llm_router._MINIMAL_SAFE_SET, f"阳性对照 {pc} 不在默认 scope(最小集)里"


def test_registry_audit_has_a_probe_reading_for_every_entry():
    """复审 = 逐条探针读数, ⛔ 不是推日期。

    ① 审计日期与读数表的日期必须一致 —— **只推 `_REGISTRY_AUDIT_DATE`** ⇒ 红;
    ② 读数表的键 == 注册表的键 —— 新增 / 保留一条却没有当天读数 ⇒ 红;
    ③ 每条读数非空 —— 登记表要的是读数, 不是打钩。
    """
    assert llm_router._REGISTRY_AUDIT_PROBE_DATE == llm_router._REGISTRY_AUDIT_DATE, (
        "审计日期推了, 逐条探针读数没换 —— 这正是「只推日期过闸」")
    probe = llm_router._REGISTRY_AUDIT_PROBE
    missing = sorted(set(llm_router._SAFE_MODELS) - set(probe))
    stale = sorted(set(probe) - set(llm_router._SAFE_MODELS))
    assert not missing, f"注册表里这些条目没有本轮探针读数: {missing}"
    assert not stale, f"读数表里有已不在注册表的条目(删了没同步): {stale}"
    assert all(v.strip() for v in probe.values()), "有空读数"


def test_registry_audit_date_is_not_stale():
    """审计日期过期(>21d) → router 收缩到 _MINIMAL_SAFE_SET。

    ⏰ 这是一道**故意的定时闸, 不是缺陷**。它会在 _REGISTRY_AUDIT_DATE + 21 天
    那一刻变红, 与任何人改了什么代码无关 —— 这正是它存在的意义。

    红了怎么办: 去三个控制台核对余量 + 跑 scripts/probe_llm_registry, 按判据
    (控制台有余量 ∩ 探针非空内容)更新 _SAFE_MODELS 与本文件的冻结表, 然后把
    _REGISTRY_AUDIT_DATE 推到复审当天。⛔ 不许只推日期不复审 —— 那等于把这道
    闸拆了。

    为什么必须是红灯而不是告警: 2026-08-09 之所以出事, 恰恰是因为没人盯这个
    日期(现值 07-26, 距 staleness 只剩 7 天), 而 prod 里同时有 23 个模型在
    额度退避、约 1800 万 token 可用额度 router 够不着 —— 告警在那 6 天里
    每天都发, 没人处理。红灯拦得住, 告警拦不住。
    """
    assert not llm_router._registry_stale(llm_router._today())
