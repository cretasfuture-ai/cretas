"""飞轮 B 接线：SSE 聊天入口。

任务卡：`backend/python/smartbi/gold/restaurant/refusal_queue.py`(飞轮 B,
拒答按【拒答原因】归类)已经接到 RN App / Java 委派入口
(`gold_reads.post_restaurant_tiered_answer`, 见
`tests/test_restaurant_refusal_queue.py::test_java_delegate_entry_records_refusal`)。
本文件补上 **SSE 聊天入口**(`chat.general_analysis_stream` 的 `event_stream`
生成器, 网页/多端聊天面板的生产入口)那一处 —— 同一次调用、同一条纪律。

## 判据(与 `test_java_delegate_entry_records_refusal` 同形)

1. 跑在**产品真实入口**上：`smartbi.api.chat.general_analysis_stream`
   的真实 `event_stream` 生成器、真实的路由分支判断(`table_type ==
   "restaurant_ops"` 才会走这条 gold-ops 路)、真的 `RestaurantQuerySpec`、
   真的返回字典形状。只桩掉外部 IO：
   - `smartbi.config.get_pg_pool`(数据库连接池)
   - `smartbi.api.chat._try_tiered_restaurant_intent`
     (LLM 语义规划 + 执行, 重量级, 与 gold_reads.py 侧桩掉
     `restaurant_intent_service.tiered_answer` 同一层)
   - `smartbi.services.llm_fallback_logger.log_template_hit`(那一次 INSERT)
   ⇒ 变异「把 chat.py 里那段 `record_refusal` 接线删掉」必须让阳性断言红。
2. 拒答有两种形状(clarification / 带 DATA_GAP·OUT_OF_DOMAIN 码的 answer)，
   本文件覆盖 clarification 一支(与 gold_reads 侧同一份 `_mismatch_result`
   约定同形)；`kind == "answer"` 一支的分类逻辑已经在
   `test_restaurant_refusal_queue.py` 里用纯函数验过（`classify_refusal`
   本身不区分调用方是哪个入口），这里不重复造第二份分类断言（形态 D）。
3. `kind == "unavailable"`(系统故障)阴性对照：即使走 SSE 入口，也不许
   被记成拒答。
4. 阴性对照：成功答案不进队列——否则第 1 条只证明了「这个入口会写一行」。
"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from smartbi.gold.restaurant import refusal_queue as rq
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec


# ─── 真实 spec 构造（同 test_restaurant_refusal_queue.py 的 `_spec` 约定，
#     ⛔ 不用 SimpleNamespace 冒充 —— 冒充会让「字段改名了」这类漂移看不见）──


def _spec(**overrides) -> RestaurantQuerySpec:
    defaults = dict(
        intent="RESTAURANT_OPS_RECIPE_COST",
        domain="restaurant",
        date_range=(None, None),
        window_label="本月",
        relative_window=True,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


#: 两个 resolver 合起来只声明 `{dish}`（同 test_restaurant_refusal_queue.py
#: 的 `_GAP_PLAN`）——问句要求按 `store` 拆 ⇒ `extra = {store}`。
_GAP_PLAN = ("RESTAURANT_OPS_RECIPE_COST", "RESTAURANT_OPS_DISCOUNT_SUMMARY")


def _dimension_gap_spec() -> RestaurantQuerySpec:
    return _spec(dimensions=("store",), planned_intents=_GAP_PLAN)


def _clarification_result(spec) -> dict:
    """维度闸那一支的返回形状——与 gold_reads 侧 `_mismatch_result` 同一份约定
    （`restaurant_intent_service.py` 维度闸的真实产出形状）。
    """
    return {
        "kind": "clarification",
        "answer_text": "我不确定你要看的是哪一层的数，所以这次我没敢算。……",
        "contract_pass": False,
        "structured_context": {},
        "spec": spec,
    }


def _unavailable_result(spec) -> dict:
    """`restaurant_intent_service.py` 里「LLM 额度不可用/执行链挂了」的真实产出
    形状（line ~2580）——系统故障，⛔ 不是能力缺口。
    """
    return {
        "kind": "unavailable",
        "answer_text": "**AI 助手当前不可用**：模型额度或链路暂时用尽……",
        "contract_pass": False,
        "structured_context": None,
        "spec": spec,
    }


def _http_request(factory_id="MOCK_REST", role="restaurant_manager"):
    return SimpleNamespace(
        state=SimpleNamespace(factory_id=factory_id, role=role, user_id=None),
    )


async def _drain_stream(request, http_request):
    """跑真实入口 `general_analysis_stream`，排空 SSE 事件流。

    `StreamingResponse.body_iterator` 就是 `event_stream()` 那个真实的
    async generator——不是另起一套调用形状，是直接消费生产路径本身产出的东西。
    """
    import smartbi.api.chat as chat_mod

    response = await chat_mod.general_analysis_stream(request, http_request)
    events = []
    async for chunk in response.body_iterator:
        events.append(chunk)
    return events


async def _settle_background_tasks():
    """`_spawn_chat_bg` 用 `asyncio.create_task`——记账对用户零延迟，
    要让事件循环转几圈那个后台任务才跑得到。
    """
    await asyncio.sleep(0)
    await asyncio.sleep(0)
