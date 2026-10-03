"""第①层: 已审核的整句精确匹配表 (verified phrase registry)。

一句话归一化之后与表里的键**整句相等**才命中 —— ⛔ 不放宽成「包含」, 与
「子串 / 向量命中从不授权」的规矩一致。表里只有 status='APPROVED' 的条目参与匹配;
老师模型(离线大模型)提出的条目先是 PENDING, 审核后才生效。

归一化 (与 2026-10 离线评测的 N2 逐字一致, 改这里要同步重跑评测):
  N1 去标点空白、小写
  N2 + 参数抽象: 实体别名 -> <物料>/<产品>/<工序>/<供应商>/<客户>/<设备>/<人名>;
     数量+单位 -> <量>; 单号/批次号 -> <号>; 日期词 -> <日期>
实体别名来自 ai_entity_lexicon (按工厂)。

两张表由 Java Flyway 建 (V20261101_47)。Python 可能先于 Java 迁移上线 ——
表不存在时注册表为空并告警一次, ⛔ 不报错、⛔ 不影响后面的层。
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_PUNCT = re.compile(r"[\s，。！？、,.!?~…：:；;\"'“”‘’（）()【】\[\]…—\-]+")
_NUM = r"(?:\d+(?:\.\d+)?|[零一二两三四五六七八九十百千万半]+)"
_UNIT = r"(?:斤|公斤|千克|kg|g|克|吨|箱|袋|桶|件|包|个|盒|瓶|托|卷|板|只|块|份|条|根|筐|框|kg)"
_QTY = re.compile(_NUM + r"\s*" + _UNIT, re.I)
_ID = re.compile(r"(?:po|so|pc|b)-?\d{3,}|\d{1,2}号那批|B\d{6,}", re.I)
_DATE = re.compile(r"明天|后天|下周[一二三四五六日天]|周[一二三四五六日天]|这周[一二三四五六日天]|\d{1,2}号")

ENTITY_TAGS: Dict[str, str] = {
    "MATERIAL": "<物料>",
    "PRODUCT": "<产品>",
    "PROCESS": "<工序>",
    "SUPPLIER": "<供应商>",
    "CUSTOMER": "<客户>",
    "EQUIPMENT": "<设备>",
    "PERSON": "<人名>",
}


def n1(text: str) -> str:
    return _PUNCT.sub("", (text or "").strip().lower())


def normalize(text: str, lexicon: List[Tuple[str, str]]) -> str:
    """N2。``lexicon`` = [(小写别名, 标签)], 必须按别名长度降序(长名优先)。"""
    s = n1(text)
    s = _ID.sub("<号>", s)
    s = _QTY.sub("<量>", s)
    s = _DATE.sub("<日期>", s)
    for alias, tag in lexicon:
        if alias in s:
            s = s.replace(alias, tag)
    return s


@dataclass
class RegistrySnapshot:
    # (factory_id, business_type) -> {normalized_text: intent_code}
    phrases: Dict[Tuple[str, str], Dict[str, str]] = field(default_factory=dict)
    # factory_id -> [(alias, tag)] 长名优先
    lexicon: Dict[str, List[Tuple[str, str]]] = field(default_factory=dict)
    loaded_at_unix: float = 0.0
    available: bool = False  # 两张表都读到了


_current = RegistrySnapshot()
_missing_table_warned = False

PHRASES_SQL = """
SELECT factory_id, business_type, normalized_text, intent_code
FROM ai_intent_phrase_registry
WHERE status = 'APPROVED'
"""

LEXICON_SQL = """
SELECT factory_id, entity_type, alias
FROM ai_entity_lexicon
"""


def build_snapshot(phrase_rows: List[Dict[str, Any]], lexicon_rows: List[Dict[str, Any]]) -> RegistrySnapshot:
    phrases: Dict[Tuple[str, str], Dict[str, str]] = {}
    for r in phrase_rows:
        key = (str(r["factory_id"]), str(r["business_type"]).upper())
        phrases.setdefault(key, {})[str(r["normalized_text"])] = str(r["intent_code"])
    # 一个别名只对应一个标签。同一别名登记在两类实体下 (「酱牛肉」既是物料也是产品) 时,
    # 按 ENTITY_TAGS 的书写顺序取第一个 —— ⛔ 不能依赖 set 的迭代顺序: 字符串哈希每个进程
    # 随机, prod 两个 uvicorn worker 会把同一句话归一化成两个不同的键 (2026-10-02 实测)。
    rank = {t: i for i, t in enumerate(ENTITY_TAGS)}
    lex: Dict[str, Dict[str, Tuple[int, str]]] = {}
    for r in lexicon_rows:
        et = str(r["entity_type"]).upper()
        tag = ENTITY_TAGS.get(et)
        alias = str(r["alias"] or "").strip().lower()
        if tag and len(alias) >= 2:
            cur = lex.setdefault(str(r["factory_id"]), {})
            if alias not in cur or rank[et] < cur[alias][0]:
                cur[alias] = (rank[et], tag)
    lexicon = {f: sorted(((a, t) for a, (_, t) in m.items()), key=lambda p: (-len(p[0]), p[0]))
               for f, m in lex.items()}
    return RegistrySnapshot(phrases=phrases, lexicon=lexicon, loaded_at_unix=time.time(), available=True)


async def load_registry(pool) -> RegistrySnapshot:
    """启动时 + 每次 ai 快照刷新时调用。表不存在 -> 空注册表 (告警一次)。"""
    global _current, _missing_table_warned
    try:
        async with pool.acquire() as conn:
            phrase_rows = [dict(r) for r in await conn.fetch(PHRASES_SQL)]
            lexicon_rows = [dict(r) for r in await conn.fetch(LEXICON_SQL)]
    except Exception as ex:  # noqa: BLE001
        if "does not exist" in str(ex):
            if not _missing_table_warned:
                logger.warning("[ai-registry] 注册表/词表尚未建表 (Java 迁移未跑?), 第①层停用: %s", ex)
                _missing_table_warned = True
            _current = RegistrySnapshot(loaded_at_unix=time.time(), available=False)
            return _current
        raise
    _current = build_snapshot(phrase_rows, lexicon_rows)
    logger.info(
        "[ai-registry] loaded: %d approved phrases over %d scopes, lexicon %d factories",
        sum(len(v) for v in _current.phrases.values()), len(_current.phrases), len(_current.lexicon),
    )
    return _current


def get_registry() -> RegistrySnapshot:
    return _current


def set_registry(snap: RegistrySnapshot) -> None:
    """测试 / 运维用。"""
    global _current
    _current = snap


def lookup(snap: RegistrySnapshot, query: str, factory_id: str, business_type: str) -> Tuple[Optional[str], str]:
    """返回 (intent_code 或 None, 归一化后的键)。"""
    lexicon = snap.lexicon.get(factory_id, [])
    key = normalize(query, lexicon)
    table = snap.phrases.get((factory_id, (business_type or "").upper()))
    if not table or not key:
        return None, key
    return table.get(key), key


__all__ = ["normalize", "n1", "build_snapshot", "load_registry", "get_registry", "set_registry", "lookup",
           "RegistrySnapshot", "ENTITY_TAGS"]
