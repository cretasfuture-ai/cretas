"""Platform-only structured factory generation; legacy AI proxy is unchanged.

Factory contract helpers are ported from assembly cf184bfd29a0. Generation
uses the main repository LLM router, including its existing quota/billing guards.
"""
from __future__ import annotations
import asyncio
import hashlib
import json
import logging
import re
import time
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field
logger = logging.getLogger(__name__)

logger = logging.getLogger(__name__)

class AiProxyCache:
    """
    LRU + TTL application-level result cache for AI proxy endpoints.

    Caches LLM response content keyed by MD5(endpoint_name + prompt_text).
    This supplements (not replaces) DashScope's prompt-level ephemeral cache.
    """

    def __init__(self, ttl_seconds: int=1800, max_entries: int=500):
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries

    @staticmethod
    def make_key(*parts: str) -> str:
        """Generate cache key from arbitrary string parts."""
        raw = ''.join(parts)
        return hashlib.md5(raw.encode('utf-8')).hexdigest()[:20]

    def get(self, key: str) -> Optional[str]:
        """Return cached LLM content if present and not expired, else None."""
        entry = self._cache.get(key)
        if entry is None:
            return None
        if time.time() - entry['created_at'] > self._ttl_seconds:
            del self._cache[key]
            return None
        entry['accessed_at'] = time.time()
        entry['access_count'] += 1
        logger.info('[AiProxyCache] HIT  key=%s  hits=%d', key, entry['access_count'])
        return entry['content']

    def drop(self, key: str) -> bool:
        """扔掉一条 —— 内容**没通过下游校验**时用。

        ⛔ 2026-08-13 实测缺陷: 建厂生成时模型偶发吐了个非法 JSON, 而这里在
           **解析之前**就已经把它存进来了。于是同一份工厂描述**连试两次都失败在
           完全相同的位置**(位置 789) —— 看起来像「稳定复现的 bug」, 实际是
           一次偶发被缓存放大成了 30 分钟。判据: **不要缓存还没验过的东西**;
           来不及先验就在失败时把它扔掉。
        """
        return self._cache.pop(key, None) is not None

    def set(self, key: str, content: str) -> None:
        """Store LLM content in cache, evicting LRU entries if needed."""
        now = time.time()
        self._cache[key] = {'content': content, 'created_at': now, 'accessed_at': now, 'access_count': 1}
        if len(self._cache) > self._max_entries:
            self._evict()

    def _evict(self) -> None:
        """Remove oldest-accessed 10 % of entries."""
        if len(self._cache) <= self._max_entries:
            return
        sorted_keys = sorted(self._cache.keys(), key=lambda k: self._cache[k]['accessed_at'])
        to_remove = max(1, len(sorted_keys) // 10)
        for k in sorted_keys[:to_remove]:
            del self._cache[k]
        logger.debug('[AiProxyCache] evicted %d entries', to_remove)

    def get_stats(self) -> Dict[str, Any]:
        now = time.time()
        active = [e for e in self._cache.values() if now - e['created_at'] < self._ttl_seconds]
        return {'total_entries': len(self._cache), 'active_entries': len(active), 'total_hits': sum((e['access_count'] - 1 for e in self._cache.values())), 'ttl_seconds': self._ttl_seconds, 'max_entries': self._max_entries}

_ai_proxy_cache = AiProxyCache(ttl_seconds=1800, max_entries=500)

class FactoryInitRequest(BaseModel):
    factory_description: str = Field(..., description='Factory description')
    factory_name: Optional[str] = Field(None)
    industry_hint: Optional[str] = Field(None)
    include_business_data: bool = Field(default=True)

async def _call_llm(system_prompt: str, user_prompt: str, max_tokens: int=2000, cache_key: Optional[str]=None, model: Optional[str]=None, timeout: float=30.0, aggregate_stream: bool=False) -> str:
    """Call DashScope LLM API with optional application-level result cache.

    Args:
        system_prompt: System message for the LLM.
        user_prompt: User message for the LLM.
        max_tokens: Maximum tokens in the response.
        cache_key: If provided, check/store result in AiProxyCache.
        model: Model name to use. Defaults to settings.llm_model if not provided.
        timeout: 整条 fallback 链的总预算(秒)。默认 30 是**交互**口径 —— 用户在
            等着看回复, 超过它宁可报错也别挂着。**批处理调用方必须自己传大值**:
            30s 对一次 4000-token 的建厂配置生成是不够的(实测见下)。
        aggregate_stream: 由服务端流式接收并聚合为完整文本后再返回。调用方仍只会
            收到完整结果；用于兼容会长时间缓冲大响应的 OpenAI-compatible 网关。
    """
    if cache_key is not None:
        cached_content = _ai_proxy_cache.get(cache_key)
        if cached_content is not None:
            return cached_content
    from common.llm_router import call_chain, call_chain_stream, SLOT
    from common.llm_metrics import llm_caller_context
    payload = {'messages': [{'role': 'system', 'content': [{'type': 'text', 'text': system_prompt, 'cache_control': {'type': 'ephemeral'}}]}, {'role': 'user', 'content': user_prompt}], 'temperature': 0.3, 'max_tokens': max_tokens, 'enable_thinking': False}
    with llm_caller_context('ai_proxy'):
        if aggregate_stream:
            chunks: List[str] = []

            async def _collect_stream() -> None:
                async for event in call_chain_stream(SLOT.CHAT, payload, timeout=timeout):
                    if event.get('type') == 'delta' and event.get('text'):
                        chunks.append(str(event['text']))
            await asyncio.wait_for(_collect_stream(), timeout)
            content = ''.join(chunks)
            if not content.strip():
                raise RuntimeError('LLM stream completed without content')
        else:
            data = await call_chain(SLOT.CHAT, payload, timeout=timeout)
            usage = data.get('usage', {})
            cached = (usage.get('prompt_tokens_details') or {}).get('cached_tokens', 0)
            if cached > 0:
                logger.info(f"[ContextCache] HIT cached={cached}/{usage.get('prompt_tokens', 0)} tokens")
            content = data['choices'][0]['message']['content']
    if cache_key is not None:
        _ai_proxy_cache.set(cache_key, content)
        logger.info('[AiProxyCache] STORE key=%s len=%d', cache_key, len(content))
    return content

_FACTORY_INIT_ENTITY_TYPES = ['MATERIAL_BATCH', 'PROCESSING_BATCH', 'QUALITY_CHECK', 'SHIPMENT']

_FACTORY_INIT_SYSTEM_PROMPT = '你是食品加工工厂配置专家。根据工厂描述生成初始化配置。\n\n⛔ 只输出 JSON 本身, 不要 markdown 围栏, 不要任何解释文字。\n⛔ 描述里没提到的东西不要编 —— 宁可少给, 也不要造一个"合理的默认值"。\n\nJSON 必须**逐字**是这个结构:\n{\n  "industryCode": "行业代码, 2-10 位大写字母, 如 FOOD",\n  "industryName": "行业中文名",\n  "aiSummary": "一句话说明你从描述里识别到了什么",\n  "schemas": [\n    {\n      "entityType": "只能取: MATERIAL_BATCH | PROCESSING_BATCH | QUALITY_CHECK | SHIPMENT",\n      "entityName": "中文名",\n      "description": "这张表在这家厂里记什么",\n      "fields": [\n        {"name": "英文字段名", "label": "中文标签", "type": "string|number|date|boolean|enum",\n         "required": true, "unit": "单位, 没有就省略"}\n      ]\n    }\n  ],\n  "suggestedData": {\n    "productTypes":    [{"code": "...", "name": "...", "unit": "计量单位, 如 kg/箱/袋", "description": "...",\n                         "level1Unit": "一级(包装)单位, 如「箱」; 说不出就省略这两个键",\n                         "boxConversionCoefficient": 20}],\n    "materialTypes":   [{"code": "...", "name": "...", "unit": "计量单位", "description": "..."}],\n    "conversionRates": [{"fromCode": "...", "toCode": "...", "rate": 0.62, "note": "..."}],\n    "workProcesses": [\n      {\n        "name": "工序名, 如「滚揉（注射保水）」",\n        "category": "只能取: 前处理 | 加工 | 包装",\n        "sortOrder": 1,\n        "unit": "投入单位, 必填",\n        "outputUnit": "产出单位; 包装工序常与投入不同(kg→盒/箱)",\n        "yieldMin": 0.86, "yieldMax": 0.92,\n        "outputKind": "SEMI_FINISHED | FINISHED_GOOD",\n        "customFields": [{"key": "英文键", "label": "中文名", "type": "number|text|date"}],\n        "byproducts":  [{"name": "副产物名, 如鱼头/内脏/脂肪"}],\n        "auxMaterials":  [{"name": "辅料名", "quantity": 0.5, "unit": "kg"}],\n        "packMaterials": [{"name": "包材名", "quantity": 1, "unit": "个"}]\n      }\n    ],\n    "haccpCheckpoints": [{"name": "控制点名, 如「杀菌」", "hazardType": "BIOLOGICAL | CHEMICAL | PHYSICAL",\n                          "criticalLimitMin": 75, "criticalLimitMax": 121, "unit": "℃",\n                          "monitoringProcedure": "怎么监控(可省略)"}]\n  }\n}\n\n关于 `aiSummary`(这一句**直接显示给工厂老板看**):\n- 用**大白话**说你识别到了什么, 像跟人说话那样。\n- ⛔ 里面不许出现英文字段名(如 productTypes)、你自己编的编码(如 FD_CHICKEN)、\n  JSON 片段、markdown 记号(`**` `#` 反引号)。客户不懂这些, 看到只会以为系统出错了。\n- 例: 「宠物冻干零食加工厂, 做冻干鸡胸粒和冻干三文鱼块, 走解冻、冻干、包装三道工序。」\n\n注: productTypes / materialTypes 的 `unit` 是**必填** —— 落库时该列非空。\n描述里没说单位就按该品类的行业通行单位给, 并在 description 里注明是推断的。\n\n关于 productTypes 的包装换算(`level1Unit` + `boxConversionCoefficient`):\n- 方向**只有一种**: `1 个 level1Unit = boxConversionCoefficient 个 unit`。\n  描述里「一箱20斤」就是 level1Unit="箱" / boxConversionCoefficient=20 / unit="斤"。\n  ⛔ 反过来写(unit="箱" 而 level1Unit="斤")是 400 倍的错 —— 换算图给反向边挂 1/factor,\n  「1 箱」会被算成 0.05 斤。拿不准就**两个键都不要给**。\n- 两个键**成对出现**, 只给一个会被落库端整体拒绝; `boxConversionCoefficient` 必须 > 0。\n- 描述里如果已经有一段「成品包装规格：… 1 箱 = 20 斤」, 那是从客户答案里解析好的,\n  ⛔ 照抄数值, 不要自己重算或换单位; 你只需要判断每一条属于哪个产品。\n- 描述里没提包装的产品, **两个键都省略** —— ⛔ 不要按行业惯例编一个箱规,\n  编出来的箱规会直接变成采购金额和投料量的倍数错。\n\n关于 conversionRates(原料 → 成品的出成换算):\n- 方向**只有一种**: `fromCode` 必须是 **materialTypes 里的原料 code**,\n  `toCode` 必须是 **productTypes 里的成品 code**。`rate` = 1 个原料单位能出多少成品单位。\n  例: 冷冻鸡胸(RM_CHICKEN) 出成 冻干鸡胸粒(FD_CHICKEN), 出成率 0.3\n      ⇒ {"fromCode": "RM_CHICKEN", "toCode": "FD_CHICKEN", "rate": 0.3}\n- ⛔ **反过来写整次都写不进去**: 落库端逐条校验 fromCode 在 materialTypes 里、\n  toCode 在 productTypes 里, 对不上就**整次 400、一条都不写**(不是跳过这一条)。\n  实测反例(2026-08-17, 连续两次): `fromCode="CHICKEN_TREAT"`(那是成品) +\n  `toCode="CHICKEN_MEAT"`(那是原料) ⇒ 整个建厂请求被拒, 客户什么都拿不到。\n- ⛔ 两个 code 都必须是**你在本次输出里给过的** code, 不许现编、不许写单位名\n  (实测见过 `toCode="kg"`)。拿不准就**这一条不要给** —— 少一条换算远好过整次 400。\n\n关于 workProcesses(描述里给了工序清单才输出, 没给就给空数组):\n- `unit` 必填, 落库时该列非空; 描述里没说就按该品类通行单位给。\n- `yieldMin/yieldMax` **允许大于 1**: 注水、加汁、镀冰衣会增重(比如卤味滚揉, 实测 1.22–1.36)。\n  ⛔ 不要因为"出成率应该小于 1"就去改客户给的数。包装工序通常不填出成率。\n\n关于 haccpCheckpoints(关键控制点):\n- ⛔ **临界限值不许编** —— 描述里没给出具体数值的控制点, **整条都不要输出**。\n  食品安全的限值编错不是误报, 是安全风险; 宁可一条不给, 也不要给一个"行业通行值"。\n- criticalLimitMin / criticalLimitMax / unit **三个都必须有**(库里三列都非空),\n  只给出一个方向时(如"中心温度≥75℃")另一端用该工艺的物理上限, 说不出就整条不要。\n- 描述里没提食品安全或没提控制点, 就给空数组。\n- `customFields` 写这道工序**要控的工艺参数**(如注射率/锅温/时长), 每条必须有 key+label+type,\n  type 只能是 number|text|date, key 不能重复 —— 落库时按这三条校验, 不合规整批拒绝。\n- `byproducts` 写这道工序的下脚料(比如鱼头/内脏/边角料)。它们有回收价值, 漏了成本和收入都会少一块。\n- `auxMaterials`/`packMaterials` 填了 quantity 就必须给 unit; 说不出用量就只给 name。'

def _strip_json_fence(text: str) -> str:
    """LLM 常把 JSON 包在 ```json ... ``` 里。去掉围栏, 不改内容。"""
    s = (text or '').strip()
    if s.startswith('```'):
        s = s.split('\n', 1)[1] if '\n' in s else s[3:]
        if s.rstrip().endswith('```'):
            s = s.rstrip()[:-3]
    return s.strip()

_ACCOUNTS_ANSWER = re.compile('登录账号[：:][ \\t]*([^\\n]*)')

def _wants_default_users(description: str):
    """从访谈答案确定性地判「要不要建默认账号」—— True / False / None(没问过)。

    ⛔ 为什么不交给模型: 这是**单选题的答案**, 不是需要理解的自由文本。
       模型推错的方向是「该建没建」= 配完的工厂**没有任何人能登录**。

    ⛔ 为什么不写进 system prompt: 2026-08-12 实测过反面教材(PR #65) ——
       只要把某个概念写进给模型的文字, 它就会去动那个字段, 哪怕你写的是「不要动」。
       所以这里连提都不提, 由服务端在模型输出之后**覆盖**。

    ## 治的那个缺陷

    访谈问了 createAccounts(「建」/「先不建, 我们自己加」), apply 第 11 步也有
    `if (TRUE.equals(getProvisionDefaultUsers()))` 的分支 —— 但**生成端从来不产出
    这个字段**, 于是那个守卫恒假, 对话建厂建出来的工厂一个能登录的账号都没有。
    契约里记为 users.writer=GUARD_DEAD; 与 product_unit_conversions 在 PR #36
    之前的形状逐字相同, 那次的药方也是「给生成端补上」。

    ⚠️ 顺序要紧: 「先不建, 我们自己加」里**含「建」字**, 必须先判否定分支。
    """
    m = _ACCOUNTS_ANSWER.search(description or '')
    if not m:
        return None
    answer = m.group(1).strip()
    if not answer:
        return None
    if '先不建' in answer or '不建' in answer or '自己加' in answer:
        return False
    if '建' in answer:
        return True
    return None

_WAREHOUSE_ANSWER = re.compile('存放区[：:][ \\t]*([^\\n]*)')

_KNOWN_WAREHOUSES = [('原料仓', 'WH-RAW', 'RAW'), ('半成品仓', 'WH-WIP', 'WIP'), ('在制品仓', 'WH-WIP', 'WIP'), ('成品仓', 'WH-FG', 'FINISHED'), ('生产仓', 'WH-LINE', 'LINESIDE'), ('线边仓', 'WH-LINE', 'LINESIDE'), ('物流仓', 'WH-LOG', 'TEMP'), ('质检仓', 'WH-QC', 'QC'), ('退货仓', 'WH-RET', 'RETURNS'), ('废料仓', 'WH-SCRAP', 'SCRAP')]

_STANDARD_NAMES = ['原料仓', '半成品仓', '成品仓']

_WAREHOUSE_CATEGORY_TO_TYPE = {'原料': 'RAW', '半成品': 'WIP', '在制品': 'WIP', '成品': 'FINISHED', '线边': 'LINESIDE', '暂存': 'TEMP', '质检': 'QC', '退货': 'RETURNS', '废料': 'SCRAP'}

_WAREHOUSE_WITH_CATEGORY = re.compile('([^、,，;；()（）]+)[（(]\\s*([^)）]+?)\\s*[)）]')

_UNRECOGNIZED_WAREHOUSES: list = []

def _default_warehouses(description: str):
    """从访谈「存放区」那一答确定性地给出仓库清单 —— 认不出就返回 []。

    🔴 2026-08-16 本地端到端实测抓到的缺陷: 客户答
    「原料冷库 W1、车间暂存 W2、成品仓 W3」——**三个**, 而本函数只认出
    「成品仓」一个(另外两个不在 _KNOWN_WAREHOUSES 里), 另外两个**静默丢弃**。
    客户在预览上看到「存放区 1 条」, 没有任何地方说另外两个去哪了。

    ⛔ 不能靠猜 type 来救: type 必须落在 FactoryWarehouse.WarehouseType 里,
       编一个就是整次 apply 400。⇒ 认不出就是认不出。
    ✅ 但**必须说出来**(目标③: 跳过的必须说清为什么)——
       认不出的名字记进 _UNRECOGNIZED_WAREHOUSES, 由调用方并进 aiSummary,
       那一段客户在建厂屏上看得见(factory-onboarding/index.vue:382)。

    ⛔ 为什么不交给模型: type 必须落在 FactoryWarehouse.WarehouseType 里,
       模型编一个就是整次 apply 400(校验器 checkWarehouses 会拦)。

    ⛔ 为什么不复用 liushanmenSaltedTemplate: 那是**六扇门一家**的 7 仓
       (含鲜棉仓 / 盐化仓代加工)。spec 里写着同一条判据 ——
       「拿来当默认就是把一个租户的配置发给另一个」。

    ⛔ **按客户点名的仓库给, 不按选项给。** 2026-08-12 真实会话里那一答是
       `存放区：原料仓和成品仓` —— **自由文本, 不是三个选项之一**。
       最初的实现只要命中「原料仓」就返回标准三仓, 于是客户说了两个、系统给三个,
       正是「替客户做主」。合成用例全用预设选项时看不出这一点。

    ⚠️ 「我们自己列」返回 [] 是**有意的**: 客户说了自己列, 就由平台管理员
       在落库前的载荷里补。生成端替他编反而是替客户做主。
    """
    m = _WAREHOUSE_ANSWER.search(description or '')
    if not m:
        return []
    answer = m.group(1).strip()
    if not answer or '自己列' in answer:
        return []
    names = list(_STANDARD_NAMES) if '按标准' in answer or '再加' in answer else []
    for name, _code, _type in sorted(_KNOWN_WAREHOUSES, key=lambda x: answer.find(x[0])):
        if name in answer and name not in names:
            names.append(name)
    lookup = {n: (c, t) for n, c, t in _KNOWN_WAREHOUSES}
    out, seen = ([], set())
    for name in names:
        code, wtype = lookup[name]
        if code in seen:
            continue
        seen.add(code)
        out.append({'code': code, 'name': name, 'type': wtype, 'remarks': '建厂访谈「存放区」: ' + answer[:60]})
    unrecognized = []
    if '按标准' not in answer and '再加' not in answer:
        idx = 0
        for seg in re.split('[、,，;；]+', answer):
            seg = seg.strip()
            if not seg:
                continue
            cm = _WAREHOUSE_WITH_CATEGORY.search(seg)
            cat = cm.group(2).strip() if cm else None
            base = _WAREHOUSE_WITH_CATEGORY.sub('\\1', seg).strip()
            base = re.sub('^\\d+\\s*个[：:]\\s*', '', base)
            base = re.sub('\\s*[A-Za-z]?\\d+\\s*$', '', base).strip()
            if not base:
                continue
            if any((k in base for k in names)):
                continue
            wtype = _WAREHOUSE_CATEGORY_TO_TYPE.get(cat) if cat else None
            if not wtype:
                unrecognized.append(base)
                continue
            idx += 1
            code = 'WH-C%d' % idx
            if code in seen:
                continue
            seen.add(code)
            out.append({'code': code, 'name': base, 'type': wtype, 'remarks': '建厂访谈「存放区」(类别由客户给出): ' + answer[:60]})
    _UNRECOGNIZED_WAREHOUSES[:] = unrecognized
    if unrecognized:
        logger.warning('存放区: 建了 %d 个, 没认出 %s —— 已并进 aiSummary', len(out), unrecognized)
    return out

_PRODUCT_HEAD = re.compile('^\\s*第\\s*\\d+\\s*个\\s+(\\S.*?)\\s*$')

_PRODUCT_STEPS = re.compile('^\\s*经过哪几道[：:]\\s*(\\S.*?)\\s*$')

_STEP_SEP = re.compile('[、,，;；/]+|->|→')

_STEP_HEAD = re.compile('^\\s*第\\s*(\\d+)\\s*道\\s+(\\S.*?)\\s*$')

_ORDINAL_TOKEN = re.compile('^第?(\\d+)道?$')

_TRAILING_QUANTIFIER = re.compile('(全走|都走|全都走|全部走|全部|全都|都要|全要)$')

_PRODUCT_SALES_SPEC_SUFFIX = re.compile('(?:[（(]\\s*)?\\d+(?:\\.\\d+)?\\s*(?:千克|公斤|毫升|克|斤|升|kg|g|ml|l|lb|oz)\\s*(?:[）)])?(?:装)?\\s*$', re.IGNORECASE)

def _nkey(s):
    """匹配用的键 —— 去掉所有空白(含全角)。

    ⛔ 必须去空白: LLM 抽取时会在连字符两侧与数字/中文交界处插空格
       (2026-08-12 实测 25 条真实产品名有 17 条被改写, 见 EntityNameNormalizer)。
       按原样匹配会大面积对不上。
    """
    return re.sub('[\\s\u3000]+', '', str(s or ''))

def _product_identity_keys(name):
    """产品查表键：原名优先，明确规格后缀作为唯一候选别名。

    「200g」这类销售规格不是产品语义的一部分，但两个规格 SKU 可能共享同一
    基础名，所以调用方必须对别名候选做唯一性检查，不能取第一个。
    """
    text = str(name or '').strip()
    exact = _nkey(text)
    if not exact:
        return []
    base = _nkey(_PRODUCT_SALES_SPEC_SUFFIX.sub('', text).strip())
    return [exact] if not base or base == exact else [exact, base]

def _product_processes(description: str, suggested: dict):
    """逐产品的工序链 —— 从访谈答案解析, 再**对着模型自己的输出**解析成 code。

    ⛔ 为什么必须保守: 校验器 checkProductProcesses 要求 productCode 在本次
       productTypes 里、每个工序名在本次 workProcesses 里 —— **对不上就整次 400**,
       那比「0 行」更糟(客户什么都拿不到)。所以只有产品**和它全部工序**都能
       对上时才产出那一条, 任何一处对不上就整条跳过并记日志。

    ⛔ 为什么不交给模型: 它已经在同一次输出里给了 productTypes / workProcesses,
       再让它把两者交叉引用一遍就是给它一次编造 code 的机会 ——
       2026-08-12 实测过它在 conversionRates 上凭空造 `SPICY_BEEF_BOX`(整次 apply 400)。
       这里做的是**查表**, 不是理解。
    """
    if not description or not isinstance(suggested, dict):
        return ([], [])
    codes_by_name = {}
    for t in suggested.get('productTypes') or []:
        if isinstance(t, dict) and t.get('code') and t.get('name'):
            code = str(t['code']).strip()
            for key in _product_identity_keys(t['name']):
                codes_by_name.setdefault(key, set()).add(code)
    process_by_name = {}
    for w in suggested.get('workProcesses') or []:
        if isinstance(w, dict) and w.get('name'):
            process_by_name.setdefault(_nkey(w['name']), str(w['name']).strip())
    if not codes_by_name or not process_by_name:
        return ([], [])
    name_by_ordinal = {}
    for line in description.split('\n'):
        sh = _STEP_HEAD.match(line)
        if sh:
            name_by_ordinal.setdefault(int(sh.group(1)), sh.group(2))
    out, skipped, current = ([], [], None)
    emitted_codes = set()
    for line in description.split('\n'):
        head = _PRODUCT_HEAD.match(line)
        if head:
            current = head.group(1)
            continue
        steps = _PRODUCT_STEPS.match(line)
        if not steps or current is None:
            continue
        product, current = (current, None)
        code = None
        for key in _product_identity_keys(product):
            candidates = codes_by_name.get(key) or set()
            if len(candidates) == 1:
                code = next(iter(candidates))
                break
        if not code:
            skipped.append('%s(这次配出来的成品清单里没有同名的)' % product)
            continue
        if code in emitted_codes:
            skipped.append('%s(同一个产品说了两次, 只按第一次说的链配)' % product)
            continue
        names, missing, seen = ([], [], set())
        for raw in _STEP_SEP.split(steps.group(1)):
            key = _nkey(raw)
            if not key:
                continue
            resolved = process_by_name.get(key)
            if not resolved:
                bare = _TRAILING_QUANTIFIER.sub('', key)
                ordinal = _ORDINAL_TOKEN.match(bare) if bare else None
                if ordinal:
                    by_ordinal = name_by_ordinal.get(int(ordinal.group(1)))
                    if by_ordinal:
                        resolved = process_by_name.get(_nkey(by_ordinal))
            if not resolved:
                missing.append(raw.strip())
            elif resolved not in seen:
                seen.add(resolved)
                names.append(resolved)
        if missing:
            skipped.append('%s(工序对不上: %s)' % (product, '/'.join(missing)))
            continue
        if names:
            emitted_codes.add(code)
            out.append({'productCode': code, 'processNames': names})
    return (out, skipped)

def _normalize_conversions(suggested: dict):
    """出成换算逐条对着**模型自己的**两份清单查表; 对不上的整条丢掉并记下理由。

    ⛔ 为什么必须在这里治: `FactoryBuildApplyValidator.checkConversions` 逐条要求
       `fromCode` 在 materialTypes 里、`toCode` 在 productTypes 里、`rate` 为正,
       **对不上就整次 400、一条都不写**(不是跳过这一条)。2026-08-17 实测那一次
       预览 24 条、只有 1 处不合规, 客户什么都没拿到。

    ⛔ 为什么**不对调**方向: 对调只换 code, 而 `rate` 的语义随方向翻转 ——
       `{from: 成品, to: 原料, rate: 0.3}` 到底是「两个字段写反了」还是
       「1 个成品耗 0.3 原料」, 代码判不出来。对调 code 而不动 rate 是替客户猜一个
       数量级, 与 `checkYieldScale` 拦的「0~1 还是 0~100 搞反就是 100 倍, 而且悄无声息」
       同一个形状。⇒ 拿不准就这一条不要给, 并且**告诉客户**(见 `_conversion_note`)。

    ⚠️ 只查**本次载荷**里的 code。Java 那边还会再查一次库里已有的 code(合法来源有两处),
       所以这里比它严: 一条引用了「库里有、本次没给」的 code 的换算会被丢掉。
       取这个口径是因为 prompt 本来就要求「两个 code 都必须是你在本次输出里给过的」,
       而丢一条 + 说清楚, 远好过整次 400 让客户什么都拿不到。

    :return: (kept, skipped) —— kept 里的 dict **原样**不动; skipped 是给客户看的中文理由
    """
    if not isinstance(suggested, dict):
        return ([], [])
    items = suggested.get('conversionRates')
    if not isinstance(items, list) or not items:
        return ([], [])

    def codes_of(key):
        out = set()
        for t in suggested.get(key) or []:
            if isinstance(t, dict) and str(t.get('code') or '').strip():
                out.add(str(t['code']).strip())
        return out
    names = {}
    for _k in ('materialTypes', 'productTypes'):
        for t in suggested.get(_k) or []:
            if isinstance(t, dict) and str(t.get('code') or '').strip():
                names[str(t['code']).strip()] = str(t.get('name') or '').strip() or str(t['code']).strip()

    def human(code):
        return names.get(code, code or '(没说)')
    materials, products = (codes_of('materialTypes'), codes_of('productTypes'))
    kept, skipped, seen_pairs = ([], [], set())
    for c in items:
        if not isinstance(c, dict):
            skipped.append('有一条换算不是对象, 读不出来')
            continue
        src = str(c.get('fromCode') or '').strip()
        dst = str(c.get('toCode') or '').strip()
        label = '%s → %s' % (human(src), human(dst))
        why = None
        if src in materials and dst in products:
            pass
        elif src in products and dst in materials:
            why = '方向反了(写成了成品→原料)'
        elif src in products and dst in products:
            why = '两端都是成品'
        elif src in materials and dst in materials:
            why = '两端都是原料'
        else:
            missing = []
            if src not in materials:
                missing.append('「%s」不在这次配出来的原料清单里' % human(src))
            if dst not in products:
                missing.append('「%s」不在这次配出来的成品清单里' % human(dst))
            why = '、'.join(missing)
        if why is None:
            rate = c.get('rate')
            if isinstance(rate, bool) or not isinstance(rate, (int, float)) or rate <= 0:
                why = '出成率没给或不是正数(收到 %r)' % (rate,)
        if why is None and (src, dst) in seen_pairs:
            why = '同一对说了两次, 只按第一次说的出成率配'
        if why is None:
            seen_pairs.add((src, dst))
            kept.append(c)
        else:
            skipped.append('%s(%s)' % (label, why))
    return (kept, skipped)

_FIELD_NAME_PLAIN = {'suggestedData': '这次配出来的东西', 'productTypes': '成品清单', 'materialTypes': '原料清单', 'conversionRates': '出成换算', 'workProcesses': '工序清单', 'productProcesses': '产品工序链', 'haccpCheckpoints': '关键控制点', 'codeSegments': '编码大类', 'metricWatches': '要盯的指标', 'moduleConfigs': '功能开关', 'indicatorOptOut': '不看的指标', 'warehouses': '存放区', 'alerts': '提醒', 'fromCode': '原料', 'toCode': '成品', 'boxConversionCoefficient': '一箱装多少', 'level1Unit': '包装单位', 'industryCode': '行业', 'aiSummary': '说明'}

def _plainify(text, suggested):
    """把**已经漏进客户可见文字**里的机器码和字段名换成客户说过的名字。

    ## 为什么需要它(2026-08-17 实测)

    收集一次完整建厂里客户可见的 283 条文字, **93 条含程序员的话**。
    其中建厂屏那段说明里同时出现了字段名 `productTypes` 与机器码 `FD_CHICKEN_BULK`。
    ⇒ owner 的要求是「不许出现」, 那就不能只靠 prompt 求模型自觉。

    ## ⛔ 它做的是**查表**, 不是改写

    code → 名字用的是**本次载荷自己给的**那两份清单; 字段名用的是上面那张固定表。
    两者都对不上就**原样留着** —— ⛔ 不猜、不删、不改写句子。
    留着的那些由探针 `probe-customer-facing-text.py` 抓出来, 到源头去改。

    ⚠️ 按长度倒序替换: `FD_CHICKEN` 是 `FD_CHICKEN_BULK` 的前缀,
       正序会把后者切成「冻干鸡胸粒_BULK」。
    """
    if not text:
        return text
    pairs = []
    if isinstance(suggested, dict):
        for key in ('productTypes', 'materialTypes', 'workProcesses'):
            for t in suggested.get(key) or []:
                if not isinstance(t, dict):
                    continue
                code = str(t.get('code') or '').strip()
                name = str(t.get('name') or '').strip()
                if code and name and (code != name):
                    pairs.append((code, name))
    pairs.extend(_FIELD_NAME_PLAIN.items())
    for code, name in sorted(pairs, key=lambda kv: -len(kv[0])):
        text = text.replace(code, name)
    return text

def _not_built(group, items, why_hint, how_to_fix, kind='missing'):
    """把「客户说了而没配出来」的那几条变成**结构化**的一份, 给复核界面用。

    ## 为什么不能只有 aiSummary 那一句话

    🔴 2026-08-17 实测: 客户说「原料冷库、车间暂存、成品仓」三个存放区, 系统建 1 个。
    另外两个只出现在 `aiSummary` 里, 而 `aiSummary` **只活在建厂那一屏** ——
    客户第二天来复核, 那一屏早关了, 复核界面上一个字都没有。

    ⛔ 它们在 planned / written / skipped 里**一处都不出现**(载荷里根本没有它们),
       所以复核的三份对账**全都对得上**, 而客户说的东西少了两件 —— 对账对不出来。

    ⇒ 结构化之后随 apply 存进快照, 复核向导按 group 逐屏显示。

    :param group:      哪一类, **与预览分组同名**(复核按它归屏)
    :param items:      形如「名字(原因)」的那几条(既有的 skipped 清单原样)
    :param why_hint:   条目本身没带原因时兜底说的那句
    :param how_to_fix: 他现在能怎么补
    """
    out = []
    for raw in items or []:
        text = str(raw or '').strip()
        if not text:
            continue
        m = re.match('^(.*?)[（(]([^)）]*)[)）]\\s*$', text)
        item, why = (m.group(1).strip(), m.group(2).strip()) if m else (text, why_hint)
        out.append({'group': group, 'item': item, 'why': why or why_hint, 'howToFix': how_to_fix, 'kind': kind})
    return out

def _skip_note(title, items, how_to_fix):
    """把「这一类里跳过了哪些、为什么」变成**给客户看**的一句话; 没有就返回空串。

    ⛔ **全生成端唯一一处措辞。** 目标③ 的另一半是「跳过的必须说清为什么」,
       而在这之前三处各拼各的, 措辞已经漂了:
         存放区  「这几个我没认出来，没有给你建」
         工序链  「这几个成品的工序链我没有配上」
       再加第三份就是形态 D(同一个东西三份, 一定会漂)。

    ⚠️ 抽的是**形状**, 不是内容 —— 每一处仍然给自己的 title 和 how_to_fix。

    ⚠️ 出口: aiSummary 那一段在建厂屏上显示给客户
       (web-admin/src/views/platform/factory-onboarding/index.vue:382)。

    :param title:       这一类叫什么(客户话, 不是字段名)
    :param items:       跳过的条目, **原样带原因** ——「跳过了」不构成交代
    :param how_to_fix:  他该怎么补(去哪个页面, 或者换个说法再说一遍)
    """
    if not items:
        return ''
    return '\n⚠️ %s：%s。%s' % (title, '；'.join(items), how_to_fix)

def _warehouse_note(unrecognized):
    """存放区里客户点了名而没被认出来的。

    🔴 2026-08-16 实测: 客户说了 3 个, 系统只建 1 个, 另外 2 个**一个字都没提**。
    """
    return _skip_note('存放区里这几个我没认出来，没有给你建', unrecognized, '可以在「仓库管理」里手动补，或换成标准名称（原料仓/半成品仓/成品仓等）再说一遍')

def _product_process_note(skipped):
    """哪几个成品的工序链没配上。

    🔴 2026-08-16 本地端到端实测: 客户拿到 `productProcesses=0`,
    而 skip 原因(`冻干鸡胸粒(工序对不上: 1/2/3)`)只写进了服务端日志 ——
    建厂屏上一个字都没有, 客户既不知道少了什么, 也不知道该怎么补。
    """
    return _skip_note('这几个成品的工序链我没有配上', skipped, '可以在「产品类型」里手动补；或者把它走的工序按名字（或「第几道」）再说一遍')

def _metric_watch_note(skipped):
    """哪几个「要盯的指标」没配上。

    🔴 2026-08-17 清点发现: 生成端三处「跳过」里, **只有这一处**从来只进日志。
    实测每次都发生:

        metricWatches=0 条; 跳过 2 条 ['产量(认出 0 个指标名)', '良品率(认出 0 个数字)']

    客户明确答了「产量、良品率」, 一条都没配上, 而建厂屏上一个字都没有。

    ⛔ 补救不是「去某个页面补」而是**再说一遍并带上阈值** —— `_metric_watches`
       要的是数字(阈值驱动告警, 解析错一个数字 = 误报/漏报, 所以宁漏不错),
       客户不给数字, 去哪个页面补都没用。这一点必须写进话里。
    """
    return _skip_note('这几个要盯的指标我没有配上', skipped, '这一项要带上阈值数字才算数（像「良品率低于95%、单位成本超过30元」这样说），可以再说一遍；也可以建完厂在「指标设置」里补')

def _conversion_note(skipped):
    """哪几条出成换算没配上。

    🔴 2026-08-17 实测: 模型给了 `FD_CHICKEN_BULK→FD_CHICKEN`(两端都是成品),
    整次 apply 被拒 —— 预览 24 条里只有这 1 处不合规, 而客户**一条都没拿到**,
    错误文案还是一句 `conversionRates[1].fromCode=... 在本次 materialTypes 和库里都找不到`
    这种字段名口径的话。

    ⛔ 「转换率配置」是 web 上那一页的**真实标题**(router meta,
       /production/conversions → /production/bom?tab=conversion), 不是我起的名字。
    """
    return _skip_note('这几条原料→成品的出成换算我没有配上', skipped, '可以在「转换率配置」里手动补；或者按「1 公斤某原料能出多少某成品」再说一遍')

_CODING_ANSWER = re.compile('物料编码大类[：:][ \\t]*([^\\n]*)')

_L1_SEGMENTS = [('原料', '001'), ('包材', '002'), ('辅料', '003')]

def _code_segments(description: str):
    """从访谈「物料编码大类」那一答给出 L1 段码 —— 认不出就返回 []。

    ⛔ 与存放区同一判据: **按客户点名的给**, 不按选项给。
       客户答「只要原料和包材」就只给两条, 不能因为命中「原料」就把三条都塞进去。

    ⚠️ 「我们自己定」返回 [] 是有意的 —— 客户说了自己定。
    """
    m = _CODING_ANSWER.search(description or '')
    if not m:
        return []
    answer = m.group(1).strip()
    if not answer or '自己定' in answer:
        return []
    return [{'code': code, 'label': label} for label, code in _L1_SEGMENTS if label in answer]

_ALERTS_ANSWER = re.compile('要开哪些提醒[：:][ \\t]*([^\\n]*)')

_ALERT_LABELS = [('库存不足', 'INVENTORY_LOW'), ('库存临期', 'INVENTORY_EXPIRING'), ('质检异常', 'QUALITY_ANOMALY'), ('销售额下滑', 'SALES_DECLINE'), ('客户回款逾期', 'CUSTOMER_PAYMENT_OVERDUE'), ('供应商付款到期', 'SUPPLIER_PAYABLE_DUE')]

_PRODUCT_LINE = re.compile('^ {2}第\\d+个[ \\t]+(\\S[^\\n]*?)[ \\t]*$', re.M)

def _declared_products(description: str):
    """客户**自己报过名字**的成品。

    ⛔ 不数「成品个数」那个数字 —— 数字对不上时说不出少的是哪一个，
    而「少的是哪一个」才是客户要的那句话。
    """
    if not description:
        return []
    i = description.find('成品清单')
    if i < 0:
        return []
    out, seen = ([], set())
    for m in _PRODUCT_LINE.finditer(description[i:]):
        name = m.group(1).strip()
        if name and name not in seen:
            seen.add(name)
            out.append(name)
    return out

def _missing_products(description: str, generated):
    """客户报了名字、而生成端一个都没对上的成品。

    🔴 2026-08-18 实测(同一套答案连跑 8 遍, 每遍一家全新空厂):
    客户答「成品个数 2 个」并逐个报了名字, 而库里的 product_types 是
    **1 / 2 / 1 / 2 / 4 / 4 / 1 / 2** —— 只有 3 遍拿到 2 个,
    而**八遍全部报 PASS**(那个判据只看「跑通了 + 库里有行」)。

    少配的那几遍客户**没有被告知任何事** —— 那正是终态条件二不许发生的:
    「他说过的每一件事, 要么真的进了库, 要么系统当面告诉他没进、以及为什么」。

    ⛔ 这里**不替他补**那个成品(不编内容); 只把「你说了、这个没配上」交出去。

    ⚠️ 宽松匹配(互相包含即算配上): 生成端常把名字写成「卤鸭脖（200g/袋）」。
    宁可漏报也不误报 —— **一条会误报的提示比没有提示更糟**(本仓判据):
    它烧掉的是信任, 而那正是这一整条线要建立的东西。

    ## ⚠️ 这道检查**至今一次都没开过火**(2026-08-18 实测 13 次)

    ⛔ 别把它当成「已验证会响的闸」。实测:

        8 遍完整访谈抽样                      少配 0 次
        构造: 报 3 个名字 / 只给 2 个规格      三个全配出来了
        构造: 报 4 个名字 / 只给 2 个规格      四个全配出来了
        构造: 报 6 / 9 / 12 个名字            **每次都一个不少**

    ⇒ 生成端会把「成品清单」里的每个名字**都配出来**, 所以在
       「客户报了名字」这条路上, 它**够不着**。

    ⚠️ 那它为什么还留着: 它守的是**回归** —— 哪天生成端开始丢名字(换模型、
       改 prompt、加数量上限), 客户会当面被告知, 而不是无声少一个成品。
    ⛔ 但在那之前, 不许在报告里把它写成「少配已修复」——**它没有被观测到过**。

    ⚠️ 真正会发生的是**相反方向**(配出客户没报过的名字), 见 `_extra_products` ——
       那一条是实测观测到并在真实浏览器里读到过的。
    """
    declared = _declared_products(description)
    if not declared:
        return []
    names = [str((g or {}).get('name') or '').strip() for g in generated or []]
    names = [n for n in names if n]
    return [d for d in declared if not any((d in n or n in d for n in names))]

_PACKAGING_SECTION = '成品包装规格'

_PACKAGING_LINE = re.compile('^ {2}- (?:(.+?)：)?1 (\\S+) = (\\S+) (\\S+)\\s*$', re.M)

def _missing_packaging(description: str, generated):
    """客户说了「一箱装 20 袋」而它一条都没落到成品字段上。

    ## ⚠️ 我最初报的那个"缺陷"是**我自己的探针造出来的**(2026-08-18, 当场推翻)

    我先用**手写的描述**测出「报 3 个成品名字时, 连有规格的那两个也丢了包装字段,
    product_unit_conversions = 0 行」, 差点写成产品缺陷。真相:

        我手写的描述里**没有**「成品包装规格」那一段。
        而真实访谈**总会**带上它(FactoryBuildInterviewService#assembleDescription
        末尾无条件调 packagingSection -> SkuPackagingSpecParser.renderSection)。

    把那一段补进描述再跑, 同样报 3 个名字:

        配出 3 个; 带包装字段 **2 个**(有规格的那两个都落上了); 库里换算 **2 行**

    ⇒ 与真实访谈跑出来的读数一致(实测 FOOD_3301_032/033/038/039 都是 2 行)。
    ⛔ 判据: **手写描述做探针时, 少一段就等于换了一个被测对象。**

    ## 那这个函数还有什么用

    它守的是「**客户说过、而它没落到成品字段上**」这一类 —— 落库那一步由
    `synchronize` 从 level1Unit / boxConversionCoefficient 两个字段产生 PACKAGING 行,
    字段空着就一行都不会有, 而客户明明说了「一箱装 20 袋」。

    ⚠️ 与 `_missing_products` 一样, 它**至今没有被观测到开过火**(上面两种形状都不该报,
    也确实没报)。⛔ 不许在报告里把它写成「修了某个已发生的缺陷」。

    ⛔ **不替他把系数填上**: 哪个产品对应哪条规格是 SkuPackagingSpecParser 的事,
       在这里再解析一遍就是同一个东西两份。本函数只负责**说出来**。

    ⚠️ 判「落上了没有」看的是生成端返回的产品字段 —— 落库那一步由
       `synchronize` 从这两个字段产生 PACKAGING 行, 字段是空的就一行都不会有。
    """
    if not description or _PACKAGING_SECTION not in description:
        return []
    seg = description[description.find(_PACKAGING_SECTION):]
    lines = _PACKAGING_LINE.findall(seg)
    if not lines:
        return []
    landed = [g for g in generated or [] if (g or {}).get('level1Unit') and (g or {}).get('boxConversionCoefficient')]
    out = []
    for hint, level1, coef, unit in lines:
        text = '%s1 %s = %s %s' % (hint + '：' if hint else '', level1, coef, unit)
        if hint:
            ok = any((hint in str((g or {}).get('name') or '') or str((g or {}).get('name') or '') in hint for g in landed))
        else:
            ok = bool(landed)
        if not ok:
            out.append(text)
    return out

def _extra_products(description: str, generated):
    """生成端配出来、而客户**从来没报过名字**的成品。

    🔴 2026-08-18 实测(同一套答案连跑 8 遍): 客户报了 2 个成品的名字，
    而 `product_types` 落库是 `1 / 2 / 1 / 2 / 4 / 4 / 1 / 2` ——
    **有 2 遍配出了 4 个**，多出来的那两个客户一个字都没说过。

    多出来的东西在预览里**看得见**（它们逐条列在差异面板里），
    但没有任何一句话点出「这几个是我加的，不是你说的」——
    于是客户会以为那也是自己说过的。

    ⛔ **不删**它们（替客户做主是另一种错，而且模型有时是对的：
       他说「2 个成品」却在别处提到第三个）。只把「这几个是我加的」说出来，
       让他自己决定要不要。

    ⚠️ 与 `_missing_products` 同一套宽松匹配（互相包含即算是同一个），
    方向相反。宁可漏报也不误报 —— 把他说过的成品报成「我加的」比不报更糟。
    """
    declared = _declared_products(description)
    if not declared:
        return []
    out = []
    for g in generated or []:
        name = str((g or {}).get('name') or '').strip()
        if name and (not any((d in name or name in d for d in declared))):
            out.append(name)
    return out

def _extra_product_note(extras):
    """多配出来的成品 —— 给客户看的那一句。⛔ 与跳过类共用同一处措辞形状。"""
    return _skip_note('这几个成品是我按你说的情况补的，你没有专门报过它们的名字', extras, '不需要的话，在下面的预览里去掉再确认；要留就照常写进去')

def _alerts(description: str):
    """从访谈「要开哪些提醒」那一答给出 alert_rules 条目 —— 认不出就返回 []。

    ⛔ 与存放区 / 段码同一判据: **按客户勾的给**, 不按「问了就全开」给。

    ⛔ 不产出 amountThreshold: 本题的选项里没有金额类, 而非金额类**填了阈值也会 400**
       (「填了也不会生效, 别让人以为设上了」)。

    ⚠️ 「都不用」是显式选项 —— 客户明确不要提醒, 与「没问过」是两回事, 但落库结果相同(空)。
    """
    m = _ALERTS_ANSWER.search(description or '')
    if not m:
        return []
    answer = m.group(1).strip()
    if not answer or '都不用' in answer:
        return []
    return [{'alertType': t, 'enabled': True} for label, t in _ALERT_LABELS if label in answer]

_METRICS_ANSWER = re.compile('要盯哪些指标[：:][ \\t]*([^\\n]*)')

_METRIC_LABELS = [('良品率', 'yield_rate'), ('不良品数', 'defect_quantity'), ('单位成本', 'unit_cost'), ('批次总成本', 'total_cost'), ('生产效率', 'efficiency'), ('实际产量', 'actual_quantity'), ('工时', 'work_duration_minutes'), ('人工成本', 'labor_cost'), ('设备成本', 'equipment_cost'), ('物料成本', 'material_cost')]

_METRIC_NUMBER = re.compile('\\d+(?:\\.\\d+)?')

_METRIC_SEP = re.compile('[、,，;；]+')

_MODULE_OPT_OUT_ANSWER = re.compile('哪些功能用不上[：:][ \\t]*([^\\n]*)')

_MODULE_LABELS = [('生产', 'production'), ('仓储', 'warehouse'), ('质检', 'quality'), ('调度', 'scheduling'), ('人事', 'hr'), ('设备', 'equipment'), ('财务', 'finance'), ('智能BI', 'smartbi'), ('采购', 'procurement'), ('销售', 'sales'), ('物流', 'logistics'), ('溯源', 'trace'), ('系统', 'system'), ('餐饮', 'restaurant')]

def _module_configs(description: str):
    """从访谈「哪些功能用不上」那一答给出要**关掉**的模块 —— 认不出就返回 []。

    ⚠️ 只产出**关掉的**。没提到的模块不写行, 走系统默认(全开) ——
    「不写行」与「写一行 enabled=true」落库语义相同, 但前者不会把新厂钉死在今天的默认上。

    ⚠️ 「都要」是显式选项: 客户明确全要, 与「没问过」是两回事, 但结果相同(空)。

    ⛔ 宁漏不错: 认不出的词整个跳过, 不猜。关错一个模块的代价是
       **那批后端接口直接 403**(apply 会同时写 factory_module_configs), 比不关严重得多。
    """
    m = _MODULE_OPT_OUT_ANSWER.search(description or '')
    if not m:
        return []
    answer = m.group(1).strip()
    if not answer:
        return []
    out = []
    for label, module_id in _MODULE_LABELS:
        if label in answer:
            out.append({'moduleId': module_id, 'moduleName': label, 'enabled': False})
    if not out and '都要' in answer:
        return []
    return out

_INDICATOR_OPT_OUT_ANSWER = re.compile('哪些指标不用看[：:][ \\t]*([^\\n]*)')

_INDICATOR_LABELS = [('出品率', 'FACTORY_LU_YIELD_RATE'), ('日均产量', 'FACTORY_LU_DAILY_OUTPUT'), ('单位成本', 'FACTORY_LU_UNIT_COST'), ('库存总价值', 'FACTORY_INVENTORY_VALUE'), ('原料周转天数', 'FACTORY_LU_MATERIAL_TURNOVER_DAYS'), ('质检不合格率', 'FACTORY_QUALITY_REJECT_RATE'), ('真空包装合格率', 'FACTORY_LU_VACUUM_PACK_PASS_RATE'), ('本月HACCP违规次数', 'FACTORY_HACCP_VIOLATIONS_MTD'), ('本月销售总额', 'B2B_TOTAL_REVENUE_MTD'), ('本月订单数', 'B2B_ORDER_COUNT_MTD'), ('平均订单金额', 'B2B_AVG_ORDER_VALUE')]

def _indicator_opt_out(description: str):
    """从访谈「哪些指标不用看」那一答给出要**停用**的指标 code —— 认不出就返回 []。

    ⚠️ 与模块那道题的本质差别: 指标在**建厂时就种好了 11 行**
    (FactoryServiceImpl#seedDefaultIndicators 跑在访谈之前),
    所以 apply 的动作是**把这些置 is_active=false**, 不是「不种」。

    ⚠️ 「都要」只在**一个指标名都没认出来**时才当作全都要 ——
    「出品率不看, 其他都要看」是自然说法, 不能被整条吞掉(模块那题实测踩过)。

    ⛔ 宁漏不错: 认不出的词跳过, 不猜。停错一个指标只是看板少一格(比关错模块轻),
       但仍然不该猜。
    """
    m = _INDICATOR_OPT_OUT_ANSWER.search(description or '')
    if not m:
        return []
    answer = m.group(1).strip()
    if not answer:
        return []
    out = [code for label, code in _INDICATOR_LABELS if label in answer]
    if not out and '都要' in answer:
        return []
    return out

def _metric_watches(description: str):
    """从访谈「要盯哪些指标」那一答解析监控项 —— 返回 (items, skipped)。

    ## ⛔ 这里宁漏不错

    阈值驱动的是**告警**: 解析错一个数字 = 误报或漏报;
    而什么都不产出 = 今天的现状(alert_thresholds 0 行)。
    所以下限是「不比现在差」, 不是「尽量多猜几个」。

    ## 安全性靠两条

    ① **按分隔符切段**, 每段必须恰好一个指标名 + 恰好一个正数 ——
       「哪个数字属于哪个指标」因此没有歧义。跟不准的整段跳过并记进 skipped。
    ② 匹配**长名字优先**: 「不良品数」要先于「良品率」被看到, 否则
       「不良品数超过20件」会被当成良品率。

    ## ⚠️ 不解析方向

    高于/低于写在 InterviewAnomalyMetricCatalog 里(LESS_THAN / GREATER_THAN),
    客户只给数字 —— 这消掉了一整类「方向搞反」的错。
    """
    m = _METRICS_ANSWER.search(description or '')
    if not m:
        return ([], [])
    answer = m.group(1).strip()
    if not answer or '不用' in answer:
        return ([], [])
    by_len = sorted(_METRIC_LABELS, key=lambda x: -len(x[0]))
    out, skipped, seen = ([], [], set())
    for seg in _METRIC_SEP.split(answer):
        seg = seg.strip()
        if not seg:
            continue
        hits, consumed = ([], seg)
        for label, metric in by_len:
            if label in consumed:
                hits.append((label, metric))
                consumed = consumed.replace(label, ' ' * len(label))
        if len(hits) != 1:
            skipped.append('%s(认出 %d 个指标名)' % (seg, len(hits)))
            continue
        nums = _METRIC_NUMBER.findall(seg)
        if len(nums) != 1:
            skipped.append('%s(认出 %d 个数字)' % (seg, len(nums)))
            continue
        if float(nums[0]) <= 0:
            skipped.append('%s(阈值不是正数)' % seg)
            continue
        label, metric = hits[0]
        if metric in seen:
            skipped.append('%s(该指标重复)' % seg)
            continue
        seen.add(metric)
        out.append({'metricName': metric, 'enabled': True, 'threshold': nums[0]})
    return (out, skipped)

def _validate_factory_init(obj: object) -> str:
    """返回空串表示合规, 否则返回**具体**的不合规原因。

    ⛔ 不做任何修补/填默认值: 模型没按契约给, 就如实报错让调用方看见,
       而不是补一个看起来像样的空壳 —— 那正是「静默失败」的来源。
    """
    if not isinstance(obj, dict):
        return f'顶层不是对象, 而是 {type(obj).__name__}'
    for k in ('industryCode', 'industryName', 'schemas', 'suggestedData'):
        if k not in obj:
            return f'缺少必需键 {k}'
    if not isinstance(obj['schemas'], list) or not obj['schemas']:
        return 'schemas 必须是非空数组'
    for i, s in enumerate(obj['schemas']):
        if not isinstance(s, dict):
            return f'schemas[{i}] 不是对象'
        for k in ('entityType', 'entityName', 'fields'):
            if k not in s:
                return f'schemas[{i}] 缺少 {k}'
        if s['entityType'] not in _FACTORY_INIT_ENTITY_TYPES:
            return f"schemas[{i}].entityType={s['entityType']!r} 不在允许集合 {_FACTORY_INIT_ENTITY_TYPES}"
        if not isinstance(s['fields'], list) or not s['fields']:
            return f'schemas[{i}].fields 必须是非空数组'
    sd = obj['suggestedData']
    if not isinstance(sd, dict):
        return 'suggestedData 不是对象'
    for k in ('productTypes', 'materialTypes', 'conversionRates'):
        if k not in sd:
            return f'suggestedData 缺少 {k}'
        if not isinstance(sd[k], list):
            return f'suggestedData.{k} 必须是数组'
    for k in ('productTypes', 'materialTypes'):
        for i, it in enumerate(sd[k]):
            if not isinstance(it, dict):
                return f'suggestedData.{k}[{i}] 不是对象'
            for req in ('code', 'name', 'unit'):
                if not str(it.get(req) or '').strip():
                    return f'suggestedData.{k}[{i}] 缺少非空的 {req}'
    for i, it in enumerate(sd['productTypes']):
        if not isinstance(it, dict):
            continue
        level1 = str(it.get('level1Unit') or '').strip()
        coefficient = it.get('boxConversionCoefficient')
        if not level1 and coefficient is None:
            continue
        if not level1 or coefficient is None:
            return f'suggestedData.productTypes[{i}] 的 level1Unit 与 boxConversionCoefficient 必须成对出现(只给一个落库端会整体拒绝)'
        try:
            if float(coefficient) <= 0:
                raise ValueError
        except (TypeError, ValueError):
            return f'suggestedData.productTypes[{i}].boxConversionCoefficient={coefficient!r} 必须是大于 0 的数'
    wps = sd.get('workProcesses')
    if wps is not None:
        if not isinstance(wps, list):
            return 'suggestedData.workProcesses 必须是数组'
        seen_names = set()
        for i, w in enumerate(wps):
            at = f'suggestedData.workProcesses[{i}]'
            if not isinstance(w, dict):
                return f'{at} 不是对象'
            name = str(w.get('name') or '').strip()
            if not name:
                return f'{at} 缺少非空的 name'
            if name in seen_names:
                return f'{at}.name={name!r} 重复 —— 落库幂等键是 工厂+工序名, 重复会互相覆盖'
            seen_names.add(name)
            if not str(w.get('unit') or '').strip():
                return f'{at} 缺少非空的 unit(work_processes.unit 非空; ⛔ 不许补默认值)'
            cat = str(w.get('category') or '').strip()
            if cat and cat not in ('前处理', '加工', '包装'):
                return f'{at}.category={cat!r} 只能是 前处理/加工/包装'
            kind = str(w.get('outputKind') or '').strip()
            if kind and kind not in ('SEMI_FINISHED', 'FINISHED_GOOD'):
                return f'{at}.outputKind={kind!r} 只能是 SEMI_FINISHED/FINISHED_GOOD'
            lo, hi = (w.get('yieldMin'), w.get('yieldMax'))
            if isinstance(lo, (int, float)) and isinstance(hi, (int, float)) and (lo > hi):
                return f'{at} 出成率下界 {lo} 大于上界 {hi}'
            cf = w.get('customFields')
            if cf is not None:
                if not isinstance(cf, list):
                    return f'{at}.customFields 必须是数组'
                keys = set()
                for j, f in enumerate(cf):
                    if not isinstance(f, dict):
                        return f'{at}.customFields[{j}] 不是对象'
                    fk = str(f.get('key') or '').strip()
                    if not fk:
                        return f'{at}.customFields[{j}] 缺少 key'
                    if not str(f.get('label') or '').strip():
                        return f'{at}.customFields[{j}] 「{fk}」缺少 label'
                    if str(f.get('type') or '').strip() not in ('number', 'text', 'date'):
                        return f"{at}.customFields[{j}] 「{fk}」的 type={f.get('type')!r}, 只能是 number/text/date"
                    if fk in keys:
                        return f'{at}.customFields[{j}] key={fk!r} 重复'
                    keys.add(fk)
            for mk in ('auxMaterials', 'packMaterials'):
                ms = w.get(mk)
                if ms is None:
                    continue
                if not isinstance(ms, list):
                    return f'{at}.{mk} 必须是数组'
                for j, m in enumerate(ms):
                    if not isinstance(m, dict) or not str(m.get('name') or '').strip():
                        return f'{at}.{mk}[{j}] 缺少非空的 name'
                    q = m.get('quantity')
                    if q is not None:
                        if not isinstance(q, (int, float)) or q <= 0:
                            return f'{at}.{mk}[{j}].quantity={q!r} 必须是正数(0 会让成本静默归集成 0)'
                        if not str(m.get('unit') or '').strip():
                            return f'{at}.{mk}[{j}] 填了用量却没给单位'
    return ''

_FACTORY_INIT_SYSTEM_PROMPT += '\n物料储存：materialTypes 可以带 storageType，明确对应 fresh=新鲜、frozen=冻货、dry=干货、normal=常温。只有用户明确说了该原料如何储存时才填写；成品的保存方式不能推断成所有原料的保存方式。没有说清就省略，后续由用户确认；禁止默认常温。'

_FACTORY_INIT_BUDGET_S = 100.0

_FACTORY_INIT_RETRY_MIN_S = 50.0

async def generate_configuration(request: FactoryInitRequest):
    """AI-powered factory configuration initialization.

    返回 Java 侧逐字消费的契约形状(装在本服务统一的 data 信封里)。

    ## 坏输出重试一次(2026-08-17)

    实测端到端连跑 4 次, 有 1 次模型吐了非法 JSON(`Expecting ':' delimiter 位置 731`),
    耗时 34.6s —— 客户等了半分钟, 拿到一句看不懂的话, 什么都没配上。
    ⇒ 在**预算允许**时重试一次。⛔ 重试共用同一份预算(见 `_FACTORY_INIT_BUDGET_S`)。
    """
    start = time.time()
    try:
        # All prompt inputs participate: a description is not a factory identity.
        ck = AiProxyCache.make_key('platform_factory_init_v1', _FACTORY_INIT_SYSTEM_PROMPT,
            json.dumps(request.model_dump(), ensure_ascii=False, sort_keys=True))
        parsed, cleaned, why, attempts = (None, '', '', 0)
        budget = _FACTORY_INIT_BUDGET_S
        while attempts < 2:
            left = budget - (time.time() - start)
            if attempts and left < _FACTORY_INIT_RETRY_MIN_S:
                why += '；剩余预算 %.0fs 不够再生成一次(实测最快 31.9s), 这次来不及重试' % left
                break
            attempts += 1
            raw = await _call_llm(system_prompt=_FACTORY_INIT_SYSTEM_PROMPT, user_prompt=f"工厂名称：{request.factory_name or '未知'}\n行业提示：{request.industry_hint or '食品加工'}\n工厂描述：{request.factory_description}\n是否包含业务数据建议：{request.include_business_data}", max_tokens=4000, cache_key=ck, timeout=left if attempts > 1 else budget, aggregate_stream=True)
            cleaned = _strip_json_fence(raw)
            try:
                candidate = json.loads(cleaned)
            except json.JSONDecodeError as je:
                _ai_proxy_cache.drop(ck)
                why = f'AI 输出不是合法 JSON: {je.msg} (位置 {je.pos})'
                logger.error('Factory init: 第 %d 次输出不是合法 JSON (pos=%s), 已丢弃该缓存条目: %.300s', attempts, je.pos, cleaned)
                continue
            contract = _validate_factory_init(candidate)
            if contract:
                _ai_proxy_cache.drop(ck)
                why = f'AI 输出不符合契约: {contract}'
                logger.error('Factory init: 第 %d 次输出不符合契约 —— %s; 已丢弃该缓存条目; 原文前 300 字: %.300s', attempts, contract, cleaned)
                continue
            parsed, why = (candidate, '')
            break
        if parsed is None:
            logger.error('Factory init: %d 次都没拿到合格输出 —— %s', attempts, why)
            return {'success': False, 'message': '这次没能把配置整理出来（已经试了 %d 次）。请再点一次「生成配置」；要是连着几次都这样，把工厂描述里最长的那几段拆短一点再试。' % attempts, 'data': None}
        if attempts > 1:
            logger.info('Factory init: 第一次输出不合格, 重试后拿到了合格输出(共 %d 次)', attempts)
        parsed.setdefault('aiSummary', '')
        parsed['factoryName'] = request.factory_name
        wants_users = _wants_default_users(request.factory_description)
        parsed['suggestedData']['provisionDefaultUsers'] = bool(wants_users)
        logger.info('Factory init: provisionDefaultUsers=%s (访谈答案判定=%s)', parsed['suggestedData']['provisionDefaultUsers'], wants_users)
        warehouses = _default_warehouses(request.factory_description)
        parsed['suggestedData']['warehouses'] = warehouses
        logger.info('Factory init: warehouses=%d 个 %s', len(warehouses), [w['code'] for w in warehouses])
        parsed['aiSummary'] = (parsed.get('aiSummary') or '') + _warehouse_note(_UNRECOGNIZED_WAREHOUSES)
        pps, pp_skipped = _product_processes(request.factory_description, parsed['suggestedData'])
        parsed['suggestedData']['productProcesses'] = pps
        logger.info('Factory init: productProcesses=%d 条; 跳过 %d 条 %s', len(pps), len(pp_skipped), pp_skipped)
        parsed['aiSummary'] = (parsed.get('aiSummary') or '') + _product_process_note(pp_skipped)
        conversions, conv_skipped = _normalize_conversions(parsed['suggestedData'])
        parsed['suggestedData']['conversionRates'] = conversions
        logger.info('Factory init: conversionRates=%d 条; 丢掉 %d 条 %s', len(conversions), len(conv_skipped), conv_skipped)
        parsed['aiSummary'] = (parsed.get('aiSummary') or '') + _conversion_note(conv_skipped)
        segments = _code_segments(request.factory_description)
        parsed['suggestedData']['codeSegments'] = segments
        logger.info('Factory init: codeSegments=%s', [c['code'] for c in segments])
        alerts = _alerts(request.factory_description)
        parsed['suggestedData']['alerts'] = alerts
        logger.info('Factory init: alerts=%s', [a['alertType'] for a in alerts])
        watches, mw_skipped = _metric_watches(request.factory_description)
        parsed['suggestedData']['metricWatches'] = watches
        logger.info('Factory init: metricWatches=%d 条; 跳过 %d 条 %s', len(watches), len(mw_skipped), mw_skipped)
        parsed['aiSummary'] = (parsed.get('aiSummary') or '') + _metric_watch_note(mw_skipped)
        _gen_products = (parsed.get('suggestedData') or {}).get('productTypes')
        missing_products = _missing_products(request.factory_description, _gen_products)
        if missing_products:
            logger.info('Factory init: 客户报了名字而没配出来的成品 %s', missing_products)
        missing_packaging = _missing_packaging(request.factory_description, _gen_products)
        if missing_packaging:
            logger.info('Factory init: 说了而没落上的包装换算 %s', missing_packaging)
        extra_products = _extra_products(request.factory_description, _gen_products)
        if extra_products:
            logger.info('Factory init: 客户没报过名字而配出来的成品 %s', extra_products)
        parsed['aiSummary'] = (parsed.get('aiSummary') or '') + _extra_product_note(extra_products)
        parsed['notBuilt'] = _not_built('存放区', _UNRECOGNIZED_WAREHOUSES, '这个名字没认出来是哪一类仓', '在名字后面用括号带一句它属于哪类（原料/半成品/成品/线边/暂存/质检/退货/废料）再说一遍；也可以建完厂在「仓库管理」里手动加') + _not_built('产品工序链', pp_skipped, '这个成品的工序链没对上', '在「产品-工序配置」里手动补；或者把它走的工序按名字（或「第几道」）再说一遍') + _not_built('转换率', conv_skipped, '这条出成换算对不上原料→成品的方向', '在「转换率配置」里手动补；或者按「1 公斤某原料能出多少某成品」再说一遍') + _not_built('盯指标', mw_skipped, '没给出这条指标的线（要一个数字）', '带上数字再说一遍，像「良品率低于95%」；也可以建完厂在「指标设置」里补') + _not_built('产品类型', missing_packaging, '这条包装换算你说过，但没落到成品上', '在「产品类型管理」里给对应成品补上「一箱几袋」；或者把它按「卤鸭脖一箱装20袋」这样再说一遍') + _not_built('产品类型', missing_products, '这个成品你报过名字，但没被配出来', '把它的名字和规格再说一遍（像「卤牛腱 250g/袋」）；也可以到手机 App 的「产品类型管理」里手动加') + _not_built('产品类型', extra_products, '这个成品你没有专门报过名字，是我按你说的情况补的', '在「产品类型管理」里删掉；下次在成品清单里把有哪几个说清就不会多配', kind='extra')
        logger.info('Factory init: 说了没配上的 %d 条 %s', len(parsed['notBuilt']), [x['item'] for x in parsed['notBuilt']])
        module_configs = _module_configs(request.factory_description)
        parsed['suggestedData']['moduleConfigs'] = module_configs
        logger.info('Factory init: moduleConfigs(关掉的)=%s', [m['moduleId'] for m in module_configs])
        indicator_opt_out = _indicator_opt_out(request.factory_description)
        parsed['suggestedData']['indicatorOptOut'] = indicator_opt_out
        logger.info('Factory init: indicatorOptOut(停用的)=%s', indicator_opt_out)
        before_summary = parsed.get('aiSummary') or ''
        parsed['aiSummary'] = _plainify(before_summary, parsed.get('suggestedData'))
        if parsed['aiSummary'] != before_summary:
            logger.info('Factory init: 说明里把机器码/字段名换成了客户说的名字')
        return {'success': True, 'data': parsed, 'elapsed_ms': int((time.time() - start) * 1000)}
    except Exception as e:
        logger.error(f'Factory batch initialize error: {e}', exc_info=True)
        return {'success': False, 'message': f'处理失败: {type(e).__name__}: {e}', 'data': None}
